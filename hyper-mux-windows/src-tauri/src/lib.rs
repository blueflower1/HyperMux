// HyperMux Windows 桌面壳（Tauri v2）
// 前端全部复用 hyper-mux-web（dist 直接嵌入）。
// 桌面专属逻辑：播放页关闭双击确认（对齐 TV 端 BACK 防误触语义）、托盘、mpv sidecar（shell 插件）、
// 应用内下载与离线播放（download.rs，mpv stream-record + XOR 混淆）。

mod download;

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{include_image, Emitter, Manager, RunEvent, State, WindowEvent};

/// 播放页关闭防误触状态：前端进入/离开播放页时经 set_close_guard 开关。
pub struct CloseGuard {
    enabled: AtomicBool,
    last_attempt_ms: AtomicI64,
}

#[tauri::command]
fn set_close_guard(guard: State<'_, CloseGuard>, enabled: bool) {
    guard.enabled.store(enabled, Ordering::SeqCst);
    if !enabled {
        guard.last_attempt_ms.store(0, Ordering::SeqCst);
    }
}

/// 投屏设备名用：浏览器侧读不到计算机名，桌面壳问这里拿（Windows 的 COMPUTERNAME）。
/// 取不到就回空串，前端回落成「电脑端」，不会把 "unknown" 之类噪音投到手机列表里。
#[tauri::command]
fn device_label() -> String {
    for key in ["COMPUTERNAME", "HOSTNAME", "NAME"] {
        if let Ok(v) = std::env::var(key) {
            let v = v.trim().to_string();
            if !v.is_empty() {
                return v;
            }
        }
    }
    String::new()
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub fn run() {
    let mut builder = tauri::Builder::default();

    builder = builder
        .manage(CloseGuard {
            enabled: AtomicBool::new(false),
            last_attempt_ms: AtomicI64::new(0),
        })
        .manage(download::DownloadManager::new())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![
            set_close_guard,
            device_label,
            download::start_download,
            download::pause_download,
            download::cancel_download,
            download::play_local,
            download::delete_download,
            download::delete_part,
            download::check_paths,
            download::get_default_download_dir,
            download::cleanup_parts
        ])
        .on_window_event(|window, event| {
            // 播放页关闭请求：2.5s 内第二次才真正关闭（第一次拦截并提示，语义同 TV 双击 BACK）
            if let WindowEvent::CloseRequested { api, .. } = event {
                let guard = window.state::<CloseGuard>();
                if guard.enabled.load(Ordering::SeqCst) {
                    let now = now_ms();
                    let last = guard.last_attempt_ms.load(Ordering::SeqCst);
                    if now - last > 2500 {
                        guard.last_attempt_ms.store(now, Ordering::SeqCst);
                        api.prevent_close();
                        let _ = window.emit("hypermux://close-hint", ());
                    }
                }
            }
        });

    builder = builder.setup(|app| {
        download::ensure_key(app.handle());
        let show_i = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
        let quit_i = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
        let menu = Menu::with_items(app, &[&show_i, &quit_i])?;
        let _tray = TrayIconBuilder::new()
            // 托盘图标必须显式给：不设 icon 时走系统默认，实测 Windows 通知区只剩一块深色方框（看不见图标）。
            // 用 32x32 而不是 icon.png(512)：include_image 把原始像素写进二进制，小图够用又不会虚胖。
            .icon(include_image!("icons/32x32.png"))
            .tooltip("HyperMux")
            .menu(&menu)
            .show_menu_on_left_click(false)
            .on_menu_event(|app, event| match event.id.as_ref() {
                "show" => {
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
                "quit" => app.exit(0),
                _ => {}
            })
            .on_tray_icon_event(|tray, event| {
                if let TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                } = event
                {
                    let app = tray.app_handle();
                    if let Some(w) = app.get_webview_window("main") {
                        let _ = w.show();
                        let _ = w.set_focus();
                    }
                }
            })
            .build(app)?;
        Ok(())
    });

    let app = builder
        .build(tauri::generate_context!())
        .expect("error while building HyperMux desktop application");
    app.run(|app_handle, event| {
        if let RunEvent::Exit { .. } = event {
            // 退出兜底：强杀仍在跑的下载 / 本地播放 mpv 子进程
            download::kill_all(app_handle);
        }
    });
}
