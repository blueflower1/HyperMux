# HyperMux Windows 桌面客户端

Tauri v2 壳 + **hyper-mux-web 前端直接复用**（不 fork 代码）+ mpv sidecar 解码兜底。

## 结构

```
hyper-mux-windows/
├─ package.json            # @tauri-apps/cli 入口脚本
├─ app-icon.png            # 品牌绿图标源图（npx tauri icon 生成全套）
└─ src-tauri/
   ├─ tauri.conf.json      # 窗口 / frontendDist 指向 ../../hyper-mux-web/dist
   ├─ Cargo.toml
   ├─ src/lib.rs           # 壳逻辑（opener 插件；mpv sidecar 预留）
   └─ capabilities/        # core + opener 权限
```

## 开发

```bash
npm install
npm run dev        # 先补齐插件（缺失才下载）→ 自动起 hyper-mux-web dev（:5174）+ Tauri 窗口（HMR）
```

## 构建安装包

```bash
set HYPERMUX_PUBLIC_SERVER_URL=http://<host>:<port>   # 可选：预设公共服务器档（语义同 web/Android）
npm run build      # 先补齐插件 → NSIS 安装包 → src-tauri/target/release/bundle/nsis/，并归位 dist/hypermux-windows-<version>-setup.exe
```

## 插件（sidecar：mpv / aria2c）

两个插件合计约 120MB，**不入 git**（`.gitignore` 里 `src-tauri/sidecars/`），但**打包照带**：构建期文件存在，NSIS 安装包里就带上，最终用户不需要任何额外步骤。

拉源码的人只需跑构建入口，缺失才下载（终端进度条，支持断点续传；下载结果会校验 PE 的 `MZ` 头，避免把错误页存成 exe）：

```bash
npm run ensure-sidecars   # 也可单独跑；--force 强制重下
```

| 插件 | 落盘文件 | 来源 |
|---|---|---|
| mpv | `src-tauri/sidecars/mpv-x86_64-pc-windows-msvc.exe` | `https://olist.hypermacro.cn/d/123pan/public/software/mpv.exe` |
| aria2c | `src-tauri/sidecars/aria2c-x86_64-pc-windows-msvc.exe` | `https://olist.hypermacro.cn/d/123pan/public/software/aria2c.exe` |

> 文件名必须带 target triple：Tauri 的 `externalBin` 写的是 `sidecars/mpv`，构建期实际找 `mpv-<triple>.exe`。手动放置时也要按上表命名。

## 自更新（契约 §3.11，platform=windows）

- 客户端启动静默检查 + 「我的」页手动检查/下载（系统浏览器下载 NSIS exe 覆盖安装）
- 管理后台「应用自更新 → Windows」上传 `.exe`
- **是否提示更新由客户端按版本名判定**（`stores/desktop.ts`）：检查更新时上报 `code=0`（要服务端直接把该平台最新发布给过来），本机版本名不比最新发布新（含同号覆盖发布）就显示「已是最新」，也不会出现下载按钮。
- 之所以不再依赖 `versionCode`：它是管理后台手填的，现网 Windows 那条 1.1.0 填的是 `11000`，而客户端自身按 `major*10000+minor*100+patch` 算是 `10100`，`11000 > 10100` 就让客户端一直提示「可更新到 v1.1.0」（更新到自己）。
- 服务端仍要求 versionCode 严格递增，发布时照管理后台的建议值填即可（下一条只要大于 11000，例如 11001）；版本名填准（`1.1.1`）客户端就能正确判断。
- 客户端版本号三处保持一致：`tauri.conf.json`、`Cargo.toml`、`package.json`（`getVersion()` 取 `tauri.conf.json`，缺省时才回落 Cargo.toml）。

## 扫码登录

桌面壳登录页二维码载荷为 `hypermux://win-login?id=<uuid>`（前端按 Tauri 运行时自动区分 web/win），手机 App「扫一扫」确认。

## 托盘与大屏布局

- 托盘图标在 `lib.rs` 里显式 `include_image!("icons/32x32.png")`：不设 icon 时 Windows 通知区只剩一块深色方框（看不见图标），32x32 够用且不会把原始像素塞太多进二进制。
- 内容列宽度由 `hyper-mux-web/src/style.css` 的 `--app-max` 控制：≤1560px 视口收拢到 1440，≥1560px 放开整宽（2400 / 3000 以上再分别收到 2440 / 2820，避免 4K 上正文行长失控）。标题栏与顶栏底色始终按窗口整宽，所以大屏全屏时左右不再留大片空白带。

## 解码策略

WebView2 硬解 → WebView2 软解 → **mpv sidecar 兜底**（构建期由 `ensure-sidecars.mjs` 补齐 `src-tauri/sidecars/mpv-x86_64-pc-windows-msvc.exe`，shinchiro 20261020 构建；安装包内含，仓库不入库）。
播放失败时错误面板出现「用本地解码核心播放」按钮（仅桌面壳），带 `--http-header-fields` 透传播放直链请求头。
