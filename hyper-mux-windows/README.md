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
- versionCode 约定：`major*10000 + minor*100 + patch`（0.1.0 → 100），管理后台发布时按同规则填写
- 管理后台「应用自更新 → Windows」上传 `.exe`

## 扫码登录

桌面壳登录页二维码载荷为 `hypermux://win-login?id=<uuid>`（前端按 Tauri 运行时自动区分 web/win），手机 App「扫一扫」确认。

## 解码策略

WebView2 硬解 → WebView2 软解 → **mpv sidecar 兜底**（构建期由 `ensure-sidecars.mjs` 补齐 `src-tauri/sidecars/mpv-x86_64-pc-windows-msvc.exe`，shinchiro 20261020 构建；安装包内含，仓库不入库）。
播放失败时错误面板出现「用本地解码核心播放」按钮（仅桌面壳），带 `--http-header-fields` 透传播放直链请求头。
