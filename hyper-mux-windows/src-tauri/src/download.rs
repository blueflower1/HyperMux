// HyperMux 桌面下载引擎（双引擎）：
// - 直链（mp4/mkv/ts 等，OpenList /d/ 链接）→ aria2c：
//   --continue 断点续传（跨进程重启有效，.aria2 控制文件）、多连接提速（对 123pan 类限速源）、
//   JSON-RPC（localhost TcpStream，零新增依赖）拿精确 百分比/速度/总量，aria2.forceShutdown 优雅暂停。
// - HLS（.m3u8）→ mpv --stream-record（不支持续传；进度靠 1s 字节轮询 + property 兜底）。
// - 完成后整文件 XOR 混淆并改名 .hypermux（密钥首启生成于 appData，前端不经手）。
// - 本地播放 = 解密到 appCache 临时 .mkv → mpv 播放 → 退出后清理临时文件。

use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, Manager, State};

const CREATE_NO_WINDOW: u32 = 0x08000000;
const CANCELLED: &str = "__cancelled__";
const PAUSED: &str = "__paused__";

#[derive(Serialize, Clone)]
pub struct DlProgress {
    pub id: u64,
    /// 0..100；-1 = 总量未知
    pub percent: f64,
    pub bytes: u64,
    /// 0 = 未知
    pub total: u64,
    /// 字节/秒
    pub speed: u64,
    pub engine: &'static str,
}

#[derive(Serialize, Clone)]
pub struct DlDone {
    pub id: u64,
    pub path: String,
    pub bytes: u64,
}

#[derive(Serialize, Clone)]
pub struct DlError {
    pub id: u64,
    pub msg: String,
}

#[derive(Serialize, Clone)]
pub struct DlCancelled {
    pub id: u64,
}

#[derive(Serialize, Clone)]
pub struct DlPaused {
    pub id: u64,
    pub bytes: u64,
}

struct TaskHandle {
    cancel: Arc<AtomicBool>,
    pause: Arc<AtomicBool>,
    pid: u32,
    engine: &'static str,
    /// aria2 RPC 端口（优雅暂停用）
    rpc_port: u16,
    /// mpv 管道写端克隆：取消时向 mpv 发 quit
    pipe_writer: Option<File>,
}

pub struct DownloadManager {
    tasks: Mutex<HashMap<u64, TaskHandle>>,
    play_pids: Mutex<Vec<u32>>,
    key: Mutex<Vec<u8>>,
    play_seq: AtomicU64,
}

impl DownloadManager {
    pub fn new() -> Self {
        Self {
            tasks: Mutex::new(HashMap::new()),
            play_pids: Mutex::new(Vec::new()),
            key: Mutex::new(Vec::new()),
            play_seq: AtomicU64::new(0),
        }
    }
}

fn e2s<E: std::fmt::Debug>(e: E) -> String {
    format!("{e:?}")
}

/* ---------------- 密钥与 XOR 混淆 ---------------- */

pub fn ensure_key(app: &AppHandle) {
    let mgr = app.state::<DownloadManager>();
    let dir = match app.path().app_data_dir() {
        Ok(d) => d,
        Err(_) => return,
    };
    let _ = fs::create_dir_all(&dir);
    let f = dir.join("hypermux.key");
    let key = if let Ok(bytes) = fs::read(&f) {
        if bytes.len() == 32 {
            bytes
        } else {
            gen_key()
        }
    } else {
        let k = gen_key();
        let _ = fs::write(&f, &k);
        k
    };
    *mgr.key.lock().unwrap() = key;
}

fn gen_key() -> Vec<u8> {
    // 简易熵源混合（混淆用途，非密码学强度）：时间 + 进程 id + 栈地址
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0x9E3779B97F4A7C15);
    let probe = 0u64;
    let addr = &probe as *const u64 as u64;
    let mut state = nanos
        ^ (std::process::id() as u64) << 32
        ^ addr.wrapping_mul(0x2545F4914F6CDD1D);
    if state == 0 {
        state = 0x853C49E6748FEA9B;
    }
    let mut out = Vec::with_capacity(32);
    for _ in 0..4 {
        // xorshift64*
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        let mixed = state.wrapping_mul(0x2545F4914F6CDD1D);
        out.extend_from_slice(&mixed.to_le_bytes());
    }
    out
}

fn ks(key: &[u8], i: usize) -> u8 {
    let h = (i as u32).wrapping_mul(2654435761);
    (key[i % key.len()] as u32 ^ h ^ (h >> 16)) as u8
}

/// 对称 XOR 流变换（加密与解密同一函数）
fn xor_transform(src: &Path, dst: &Path, key: &[u8]) -> Result<u64, String> {
    let mut r = BufReader::new(File::open(src).map_err(e2s)?);
    let mut w = BufWriter::new(File::create(dst).map_err(e2s)?);
    let mut buf = vec![0u8; 4 << 20];
    let mut idx = 0usize;
    let mut total = 0u64;
    loop {
        let n = r.read(&mut buf).map_err(e2s)?;
        if n == 0 {
            break;
        }
        for b in &mut buf[..n] {
            *b ^= ks(key, idx);
            idx += 1;
        }
        w.write_all(&buf[..n]).map_err(e2s)?;
        total += n as u64;
    }
    w.flush().map_err(e2s)?;
    Ok(total)
}

/* ---------------- 工具 ---------------- */

fn sidecar_exe(name: &str) -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(e2s)?;
    let dir = exe.parent().ok_or("无法定位程序目录")?;
    let p = dir.join(name);
    if p.exists() {
        Ok(p)
    } else {
        Err(format!("未找到 {name}：{}", p.display()))
    }
}

fn engine_for(url: &str) -> &'static str {
    let path = url.split(['?', '#']).next().unwrap_or(url);
    if path.to_lowercase().ends_with(".m3u8") {
        "mpv"
    } else {
        "aria2"
    }
}

fn sanitize_name(s: &str) -> String {
    let mut out: String = s
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => ' ',
            c if (c as u32) < 0x20 => ' ',
            c => c,
        })
        .collect();
    out = out.split_whitespace().collect::<Vec<_>>().join(" ");
    out = out.trim_end_matches('.').to_string();
    if out.is_empty() {
        out = "hypermux".to_string();
    }
    if out.chars().count() > 100 {
        out = out.chars().take(100).collect();
    }
    out
}

fn taskkill(pid: u32) {
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/F"])
        .creation_flags(CREATE_NO_WINDOW)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
}

fn part_suffix(engine: &str) -> &'static str {
    if engine == "mpv" {
        ".part.mkv"
    } else {
        ".part"
    }
}

/// URL 规范化：非 ASCII（如 REMOTE 型视频存的原始中文路径）与空格 percent-encode。
/// aria2 的 Windows argv 不处理非 ASCII URL，会直接拒收；已编码的 URL 原样通过（幂等）。
fn normalize_url(url: &str) -> String {
    let mut out = String::with_capacity(url.len());
    for b in url.bytes() {
        if b >= 0x80 || b <= 0x20 {
            out.push('%');
            out.push_str(&format!("{b:02X}"));
        } else {
            out.push(b as char);
        }
    }
    out
}

/* ---------------- aria2 JSON-RPC（localhost TcpStream，零依赖） ---------------- */

fn rpc(port: u16, method: &str, params: Value) -> Option<Value> {
    let body = json!({ "jsonrpc": "2.0", "method": method, "params": params, "id": "hm" }).to_string();
    let addr = format!("127.0.0.1:{port}");
    let mut stream = TcpStream::connect(&addr).ok()?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(3)));
    let req = format!(
        "POST /jsonrpc HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    );
    stream.write_all(req.as_bytes()).ok()?;
    stream.flush().ok()?;
    let mut buf = Vec::new();
    stream.read_to_end(&mut buf).ok()?;
    let text = String::from_utf8_lossy(&buf).into_owned();
    let idx = text.find("\r\n\r\n")?;
    serde_json::from_str(text[idx + 4..].trim()).ok()
}

fn parse_u64_field(v: &Value) -> u64 {
    match v {
        Value::String(s) => s.parse().unwrap_or(0),
        Value::Number(n) => n.as_u64().unwrap_or(0),
        _ => 0,
    }
}

/// 把 aria2 stderr 尾部并入用户可见错误
fn with_tail(tail: &Arc<Mutex<Vec<String>>>, base: &str) -> String {
    let v = tail.lock().unwrap();
    if v.is_empty() {
        base.to_string()
    } else {
        format!("{base}：{}", v.join(" | "))
    }
}

/// 随机挑一个当前空闲的 RPC 端口：
/// 应用被强杀时可能遗留占用旧端口的 aria2 孤儿进程，固定端口会撞车导致 aria2 秒退
fn free_rpc_port(seed: u64) -> u16 {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    let mut x = seed.wrapping_mul(2654435761) ^ nanos;
    for _ in 0..30 {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let port = 20000 + ((x >> 32) % 20000) as u16;
        if TcpListener::bind(("127.0.0.1", port)).is_ok() {
            return port;
        }
    }
    6810
}

/* ---------------- 下载任务 ---------------- */

#[tauri::command]
pub fn start_download(
    app: AppHandle,
    mgr: State<'_, DownloadManager>,
    task_id: u64,
    url: String,
    headers: HashMap<String, String>,
    dir: String,
    file_base: String,
) -> Result<(), String> {
    let key = mgr.key.lock().unwrap().clone();
    if key.len() != 32 {
        return Err("加密密钥未初始化".to_string());
    }
    let engine = engine_for(&url);
    let url = normalize_url(&url);
    let base = sanitize_name(&file_base);
    let dirp = PathBuf::from(&dir);
    fs::create_dir_all(&dirp).map_err(e2s)?;
    let part = dirp.join(format!("{base}{}", part_suffix(engine)));
    let finalp = dirp.join(format!("{base}.hypermux"));

    if engine == "mpv" {
        // mpv 不支持续传：清残留从头开始
        let _ = fs::remove_file(&part);
    }
    // aria2：保留 .part 与 .aria2 控制文件 → --continue 自动续传
    if finalp.exists() {
        let _ = fs::remove_file(&finalp); // 重复下载 = 覆盖
    }

    let cancel = Arc::new(AtomicBool::new(false));
    let pause = Arc::new(AtomicBool::new(false));
    mgr.tasks.lock().unwrap().insert(
        task_id,
        TaskHandle {
            cancel: cancel.clone(),
            pause: pause.clone(),
            pid: 0,
            engine,
            rpc_port: 0,
            pipe_writer: None,
        },
    );

    thread::spawn(move || {
        let outcome = if engine == "aria2" {
            run_task_aria2(&app, task_id, &url, &headers, &dirp, &part, &finalp, &cancel, &pause, &key)
        } else {
            run_task_mpv(&app, task_id, &url, &headers, &part, &finalp, &cancel, &key)
        };
        let h = app.state::<DownloadManager>().tasks.lock().unwrap().remove(&task_id);
        match outcome {
            Ok(bytes) => {
                let _ = app.emit(
                    "hypermux://dl-done",
                    DlDone {
                        id: task_id,
                        path: finalp.display().to_string(),
                        bytes,
                    },
                );
            }
            Err(m) if m == PAUSED => {
                // 保留 .part / .aria2：可继续
                let bytes = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
                let _ = app.emit("hypermux://dl-paused", DlPaused { id: task_id, bytes });
            }
            Err(m) if m == CANCELLED => {
                remove_task_files(&part);
                let _ = app.emit("hypermux://dl-cancelled", DlCancelled { id: task_id });
            }
            Err(m) => {
                // 引擎失败：保留 .part（用户可点"继续"重试续传）
                let _ = h;
                let _ = app.emit("hypermux://dl-error", DlError { id: task_id, msg: m });
            }
        }
    });
    Ok(())
}

fn remove_task_files(part: &Path) {
    let _ = fs::remove_file(part);
    let mut ctrl = part.as_os_str().to_os_string();
    ctrl.push(".aria2");
    let _ = fs::remove_file(Path::new(&ctrl));
}

fn set_pid(app: &AppHandle, id: u64, pid: u32, rpc_port: u16) {
    if let Some(h) = app.state::<DownloadManager>().tasks.lock().unwrap().get_mut(&id) {
        h.pid = pid;
        h.rpc_port = rpc_port;
    }
}

/* ---------------- aria2 引擎 ---------------- */

#[allow(clippy::too_many_arguments)]
fn run_task_aria2(
    app: &AppHandle,
    id: u64,
    url: &str,
    headers: &HashMap<String, String>,
    dirp: &Path,
    part: &Path,
    finalp: &Path,
    cancel: &Arc<AtomicBool>,
    pause: &Arc<AtomicBool>,
    key: &[u8],
) -> Result<u64, String> {
    let aria2 = sidecar_exe("aria2c.exe")?;
    let port: u16 = free_rpc_port(id);

    let out_name = part.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or("download.part".into());
    let mut child = Command::new(&aria2)
        .arg(url)
        .arg("--no-conf")
        .arg(format!("--dir={}", dirp.display()))
        .arg(format!("--out={out_name}"))
        .arg("--continue")
        .arg("--max-connection-per-server=6")
        .arg("--split=6")
        .arg("--min-split-size=1M")
        .arg("--file-allocation=none")
        .arg("--auto-save-interval=10")
        .arg("--max-tries=5")
        .arg("--retry-wait=3")
        .arg("--timeout=30")
        .arg("--connect-timeout=15")
        .arg("--summary-interval=0")
        .arg("--console-log-level=warn")
        .arg("--enable-rpc")
        .arg(format!("--rpc-listen-port={port}"))
        .arg("--rpc-listen-all=false")
        .args(headers.iter().map(|(k, v)| format!("--header={k}: {v}")))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(e2s)?;
    set_pid(app, id, child.id(), port);

    // 收集 aria2 stderr 尾部（warn/error 行），失败时并入用户可见错误
    let err_tail: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    {
        let tail = err_tail.clone();
        if let Some(err) = child.stderr.take() {
            thread::spawn(move || {
                let mut rd = BufReader::new(err);
                let mut line = String::new();
                loop {
                    line.clear();
                    match rd.read_line(&mut line) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {
                            let t = line.trim();
                            if !t.is_empty() {
                                let mut v = tail.lock().unwrap();
                                v.push(t.to_string());
                                if v.len() > 8 {
                                    v.remove(0);
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    // 等 RPC 就绪并取 gid（注意：aria2 RPC 没有 list 方法，活跃任务用 tellActive）
    let t0 = Instant::now();
    let gid: String = loop {
        if cancel.load(Ordering::SeqCst) {
            let _ = child.kill();
            return Err(CANCELLED.to_string());
        }
        if pause.load(Ordering::SeqCst) {
            let _ = child.kill();
            return Err(PAUSED.to_string());
        }
        if let Some(v) = rpc(port, "aria2.tellActive", json!([["gid"]])) {
            if let Some(arr) = v["result"].as_array() {
                if let Some(g) = arr.iter().find_map(|e| e["gid"].as_str()) {
                    break g.to_string();
                }
            }
        }
        if let Some(st) = child.try_wait().map_err(e2s)? {
            // 小文件可能瞬间下完：正常退出且落盘有效则直接走完成流程
            if st.success() && fs::metadata(part).map(|m| m.len()).unwrap_or(0) >= 4096 {
                break String::new(); // 空 gid = 已完成，跳过轮询
            }
            return Err(with_tail(&err_tail, "aria2 提前退出（源地址可能失效或参数被拒）"));
        }
        if t0.elapsed() > Duration::from_secs(20) {
            let _ = child.kill();
            return Err(with_tail(&err_tail, "aria2 RPC 未就绪"));
        }
        thread::sleep(Duration::from_millis(300));
    };

    // 进度轮询
    let mut last_bytes = fs::metadata(part).map(|m| m.len()).unwrap_or(0);
    let mut last_ts = Instant::now();
    let mut speed: u64 = 0;
    let mut total: u64 = 0;
    let mut status_done: Option<Result<u64, String>> = if gid.is_empty() { Some(Ok(0)) } else { None };
    while status_done.is_none() {
        thread::sleep(Duration::from_millis(500));

        if cancel.load(Ordering::SeqCst) {
            let _ = rpc(port, "aria2.forceShutdown", json!([]));
            let _ = child.wait();
            return Err(CANCELLED.to_string());
        }
        if pause.load(Ordering::SeqCst) {
            // 优雅退出：控制文件保持一致，续传可靠
            let _ = rpc(port, "aria2.forceShutdown", json!([]));
            let _ = child.wait();
            return Err(PAUSED.to_string());
        }

        let status_obj = rpc(
            port,
            "aria2.tellStatus",
            json!([gid, ["status", "completedLength", "totalLength", "downloadSpeed"]]),
        )
        .map(|v| v["result"].clone())
        .filter(|r| r.is_object());
        if let Some(r) = &status_obj {
            let completed = parse_u64_field(&r["completedLength"]);
            let t = parse_u64_field(&r["totalLength"]);
            if t > 0 {
                total = t;
            }
            speed = parse_u64_field(&r["downloadSpeed"]);
            let status = r["status"].as_str().unwrap_or("active");
            let percent = if total > 0 { (completed as f64 / total as f64) * 100.0 } else { -1.0 };
            let _ = app.emit(
                "hypermux://dl-progress",
                DlProgress { id, percent, bytes: completed, total, speed, engine: "aria2" },
            );
            last_bytes = completed;
            last_ts = Instant::now();
            match status {
                "complete" => status_done = Some(Ok(0)),
                "error" => status_done = Some(Err(with_tail(&err_tail, "aria2 下载失败（源不可用、限速拒绝或不支持续传）"))),
                _ => {}
            }
        } else {
            // RPC 偶发失败：退回文件字节观测
            let bytes = fs::metadata(part).map(|m| m.len()).unwrap_or(0);
            let dt = last_ts.elapsed().as_secs_f64().max(0.3);
            if bytes > last_bytes {
                speed = ((bytes - last_bytes) as f64 / dt) as u64;
            }
            last_bytes = bytes;
            last_ts = Instant::now();
            let percent = if total > 0 { (bytes as f64 / total as f64) * 100.0 } else { -1.0 };
            let _ = app.emit(
                "hypermux://dl-progress",
                DlProgress { id, percent, bytes, total, speed, engine: "aria2" },
            );
            if let Some(st) = child.try_wait().map_err(e2s)? {
                // RPC 查询失败但进程已退出：成功退出 = 下载完成（tellStatus 竞态），否则才是异常
                if st.success() {
                    status_done = Some(Ok(0));
                } else {
                    status_done = Some(Err(with_tail(&err_tail, "aria2 异常退出")));
                }
            }
        }
    }
    let _ = child.wait();
    status_done.unwrap()?;

    let meta = fs::metadata(part).map_err(e2s)?;
    if meta.len() < 4096 {
        return Err("下载结果过小，源可能不可用".to_string());
    }
    let bytes = xor_transform(part, finalp, key)?;
    remove_task_files(part);
    Ok(bytes)
}

/* ---------------- mpv 引擎（HLS） ---------------- */

fn run_task_mpv(
    app: &AppHandle,
    id: u64,
    url: &str,
    headers: &HashMap<String, String>,
    part: &Path,
    finalp: &Path,
    cancel: &Arc<AtomicBool>,
    key: &[u8],
) -> Result<u64, String> {
    let mpv = sidecar_exe("mpv.exe")?;
    let pipe_name = format!(r"\\.\pipe\hm-dl-{id}");

    let mut child = Command::new(&mpv)
        .arg(url)
        .arg("--no-config")
        .arg("--load-scripts=no")
        .arg("--ytdl=no")
        .arg("--force-window=no")
        .arg("--idle=no")
        // 注意：不能用 --no-video/--no-audio（等价于取消轨道选择，mpv 会拒绝加载）；
        // vo/ao=null 保留轨道（stream-record 需要）但丢弃渲染输出，纯当下载器跑
        .arg("--vo=null")
        .arg("--ao=null")
        .arg("--really-quiet")
        .arg(format!("--stream-record={}", part.display()))
        .arg(format!("--input-ipc-server={pipe_name}"))
        .args(headers.iter().map(|(k, v)| format!("--http-header-fields={k}: {v}")))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(e2s)?;
    set_pid(app, id, child.id(), 0);

    // 连接控制管道（mpv 启动需要时间；期间监测取消与 mpv 早退）
    let t0 = Instant::now();
    let pipe = loop {
        match OpenOptions::new().read(true).write(true).open(&pipe_name) {
            Ok(f) => break f,
            Err(_) => {
                if cancel.load(Ordering::SeqCst) {
                    let _ = child.kill();
                    return Err(CANCELLED.to_string());
                }
                if child.try_wait().map_err(e2s)?.is_some() {
                    return Err("mpv 提前退出（源地址可能失效，请重试）".to_string());
                }
                if t0.elapsed() > Duration::from_secs(15) {
                    let _ = child.kill();
                    return Err("连接 mpv 控制管道超时".to_string());
                }
                thread::sleep(Duration::from_millis(150));
            }
        }
    };

    // 取消通道：写端克隆存进任务表
    let writer = pipe.try_clone().map_err(e2s)?;
    if let Some(h) = app.state::<DownloadManager>().tasks.lock().unwrap().get_mut(&id) {
        h.pipe_writer = Some(writer);
    }

    let mut rd = BufReader::new(&pipe);
    let _ = rd.get_mut().write_all(
        b"{\"command\":[\"observe_property\",1,\"playback-progress\"]}\n\
           {\"command\":[\"observe_property\",2,\"playback-time\"]}\n\
           {\"command\":[\"observe_property\",3,\"duration\"]}\n",
    );
    let _ = rd.get_mut().flush();

    // mpv property 事件在 null 输出下稀疏：1s 轮询线程报落盘字节 + 计算速度
    let pct_x100 = Arc::new(std::sync::atomic::AtomicI64::new(-1));
    let done = Arc::new(AtomicBool::new(false));
    {
        let app2 = app.clone();
        let part2 = part.to_path_buf();
        let stop = cancel.clone();
        let fin = done.clone();
        let pct = pct_x100.clone();
        thread::spawn(move || {
            let mut last_bytes = 0u64;
            let mut last_ts = Instant::now();
            loop {
                thread::sleep(Duration::from_millis(1000));
                if stop.load(Ordering::SeqCst) || fin.load(Ordering::SeqCst) {
                    break;
                }
                let bytes = fs::metadata(&part2).map(|m| m.len()).unwrap_or(0);
                let dt = last_ts.elapsed().as_secs_f64().max(0.5);
                let speed = if bytes > last_bytes { ((bytes - last_bytes) as f64 / dt) as u64 } else { 0 };
                last_bytes = bytes;
                last_ts = Instant::now();
                let p = pct.load(Ordering::SeqCst);
                let _ = app2.emit(
                    "hypermux://dl-progress",
                    DlProgress {
                        id,
                        percent: if p < 0 { -1.0 } else { (p as f64) / 100.0 },
                        bytes,
                        total: 0,
                        speed,
                        engine: "mpv",
                    },
                );
            }
        });
    }

    let mut progress_f: Option<f64> = None;
    let mut time_f: Option<f64> = None;
    let mut dur_f: Option<f64> = None;
    let mut end_error: Option<String> = None;
    let mut line = String::new();
    loop {
        line.clear();
        match rd.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        let v: Value = match serde_json::from_str(line.trim()) {
            Ok(v) => v,
            Err(_) => continue,
        };
        match v["event"].as_str().unwrap_or("") {
            "property-change" => {
                let val = v["value"].as_f64();
                match v["name"].as_str().unwrap_or("") {
                    "playback-progress" => {
                        if val.is_some() {
                            progress_f = val;
                        }
                    }
                    "playback-time" => time_f = val,
                    "duration" => dur_f = val,
                    _ => {}
                }
                let pct = progress_f
                    .or_else(|| match (time_f, dur_f) {
                        (Some(t), Some(d)) if d > 0.0 => Some((t / d).min(1.0)),
                        _ => None,
                    });
                if let Some(p) = pct {
                    pct_x100.store((p * 10000.0).min(10000.0) as i64, Ordering::SeqCst);
                }
            }
            "end-file" => {
                if let Some(e) = v["error"].as_str() {
                    if !e.is_empty() {
                        end_error = Some(e.to_string());
                    }
                }
                break;
            }
            "shutdown" => break,
            _ => {}
        }
    }
    done.store(true, Ordering::SeqCst);

    let status = child.wait().map_err(e2s)?;
    if cancel.load(Ordering::SeqCst) {
        return Err(CANCELLED.to_string());
    }
    if let Some(e) = end_error {
        return Err(format!("下载失败：{e}"));
    }
    if !status.success() {
        return Err(format!("mpv 异常退出（code {:?}）", status.code()));
    }

    let meta = fs::metadata(part).map_err(e2s)?;
    if meta.len() < 4096 {
        return Err("下载结果过小，源可能不可用".to_string());
    }
    let bytes = xor_transform(part, finalp, key)?;
    let _ = fs::remove_file(part);
    Ok(bytes)
}

/* ---------------- 暂停 / 取消 / 记录操作 ---------------- */

#[tauri::command]
pub fn pause_download(mgr: State<'_, DownloadManager>, task_id: u64) {
    if let Some(h) = mgr.tasks.lock().unwrap().get_mut(&task_id) {
        h.pause.store(true, Ordering::SeqCst);
        if h.engine == "aria2" && h.rpc_port != 0 {
            // worker 自己会走优雅退出路径；这里直接发一次加速响应
            let _ = rpc(h.rpc_port, "aria2.forceShutdown", json!([]));
        }
    }
}

#[tauri::command]
pub fn cancel_download(mgr: State<'_, DownloadManager>, task_id: u64) {
    if let Some(h) = mgr.tasks.lock().unwrap().get_mut(&task_id) {
        h.cancel.store(true, Ordering::SeqCst);
        if h.engine == "aria2" && h.rpc_port != 0 {
            let _ = rpc(h.rpc_port, "aria2.forceShutdown", json!([]));
        } else if let Some(w) = &mut h.pipe_writer {
            let _ = w.write_all(b"{\"command\":[\"quit\"]}\n");
            let _ = w.flush();
        } else if h.pid != 0 {
            taskkill(h.pid);
        }
    }
}

#[tauri::command]
pub fn play_local(
    app: AppHandle,
    mgr: State<'_, DownloadManager>,
    path: String,
) -> Result<(), String> {
    let key = mgr.key.lock().unwrap().clone();
    if key.len() != 32 {
        return Err("加密密钥未初始化".to_string());
    }
    let p = PathBuf::from(&path);
    if p.extension().and_then(|e| e.to_str()) != Some("hypermux") || !p.exists() {
        return Err("文件不存在或类型不对".to_string());
    }
    let cache = app.path().app_cache_dir().map_err(e2s)?.join("play");
    fs::create_dir_all(&cache).map_err(e2s)?;
    let stem = p.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "play".into());
    let seq = mgr.play_seq.fetch_add(1, Ordering::SeqCst);
    let tmp = cache.join(format!("{stem}.hmplay{seq}.mkv"));

    xor_transform(&p, &tmp, &key)?;

    let mpv = sidecar_exe("mpv.exe")?;
    let mut child = Command::new(&mpv)
        .arg(&tmp)
        .arg("--no-config")
        .arg("--force-window")
        .arg("--autofit=70%")
        .arg("--title=HyperMux 本地播放")
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(e2s)?;
    let pid = child.id();
    mgr.play_pids.lock().unwrap().push(pid);

    // mpv 自己退出后清理解密临时文件（kill_all 强杀也会走到这里）
    let app2 = app.clone();
    thread::spawn(move || {
        let _ = child.wait();
        let _ = fs::remove_file(&tmp);
        app2
            .state::<DownloadManager>()
            .play_pids
            .lock()
            .unwrap()
            .retain(|x| *x != pid);
    });
    Ok(())
}

#[tauri::command]
pub fn check_paths(paths: Vec<String>) -> Vec<String> {
    paths.into_iter().filter(|p| Path::new(p).exists()).collect()
}

#[tauri::command]
pub fn delete_download(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_string();
    if !matches!(ext.as_str(), "hypermux" | "part" | "mkv") {
        return Err("只能删除 HyperMux 下载文件".to_string());
    }
    if p.exists() {
        fs::remove_file(&p).map_err(e2s)?;
    }
    let mut ctrl = p.as_os_str().to_os_string();
    ctrl.push(".aria2");
    let _ = fs::remove_file(Path::new(&ctrl));
    Ok(())
}

/// 删除未完成任务的 .part / .part.mkv / .aria2（按 fileBase 定位）
#[tauri::command]
pub fn delete_part(dir: String, file_base: String) -> Result<(), String> {
    let base = sanitize_name(&file_base);
    let dirp = PathBuf::from(&dir);
    for suffix in [".part", ".part.mkv"] {
        let p = dirp.join(format!("{base}{suffix}"));
        if p.exists() {
            fs::remove_file(&p).map_err(e2s)?;
        }
        let mut ctrl = p.as_os_str().to_os_string();
        ctrl.push(".aria2");
        let _ = fs::remove_file(Path::new(&ctrl));
    }
    Ok(())
}

#[tauri::command]
pub fn get_default_download_dir(app: AppHandle) -> String {
    let base = app
        .path()
        .download_dir()
        .unwrap_or_else(|_| std::env::temp_dir());
    let dir = base.join("HyperMux");
    // 目录先行创建：面板「打开目录」不依赖首次下载
    let _ = fs::create_dir_all(&dir);
    dir.display().to_string()
}

#[tauri::command]
pub fn cleanup_parts(dir: String) {
    // 只清 mpv 残留（.part.mkv）；aria2 的 .part 保留给续传
    let dirp = PathBuf::from(&dir);
    if let Ok(rd) = fs::read_dir(&dirp) {
        for entry in rd.flatten() {
            let p = entry.path();
            if p.file_name()
                .map(|n| n.to_string_lossy().ends_with(".part.mkv"))
                .unwrap_or(false)
            {
                let _ = fs::remove_file(&p);
            }
        }
    }
}

/* ---------------- 退出清理 ---------------- */

pub fn kill_all(app: &AppHandle) {
    let mgr = app.state::<DownloadManager>();
    let tasks = mgr.tasks.lock().unwrap();
    for h in tasks.values() {
        if h.engine == "aria2" && h.rpc_port != 0 {
            // 优雅退出保控制文件，下次启动可续传
            let _ = rpc(h.rpc_port, "aria2.forceShutdown", json!([]));
        }
        if h.pid != 0 {
            taskkill(h.pid);
        }
    }
    drop(tasks);
    let plays = mgr.play_pids.lock().unwrap();
    for pid in plays.iter() {
        taskkill(*pid);
    }
}
