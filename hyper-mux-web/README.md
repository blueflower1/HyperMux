# HyperMux Web

HyperMux 影视库的 Web 前端（手机 / PC 自适应），复用 tv 端 Spring Boot 后端的 `/api/app/**` 接口。

## 技术栈

- Vue 3.5 + TypeScript + Vite 5
- Pinia（user / theme / toast）+ vue-router 4（hash 模式）
- [artplayer](https://artplayer.org/) 5.x 播放器（圆角卡片 UI 纯手写 CSS，无 UI 框架）
- 明 / 暗主题切换，`localStorage` 持久化

## 开发

```bash
npm install
npm run dev        # 本机 http://localhost:5174
```

Vite 已配置代理：`/api` → `http://127.0.0.1:8080`（后端同机 8080 端口时需先启动，见 `../hyper-mux`）。

开发时也想出现「公共服务器」预设档：`set HYPERMUX_PUBLIC_SERVER_URL=http://<host>:<port>` 后再 `npm run dev`（注入后默认档即为公共服务器，可在登录页 / 我的页切到自定义）。

**手机访问**：`server.host: true` 已开启，同一局域网下用局域网 IP 访问（本机当前为 `http://192.168.1.20:5174`，启动时终端会打印实际地址）；远程经 Tailscale 用 Tailscale IP（`http://141.141.141.64:5174`）。多个网卡时会打印多行 Network 地址。

> npm 11 若提示 `install-scripts` 未批准（esbuild / vue-demi），执行
> `npm install-scripts approve esbuild vue-demi` 再 `npm install` 一次即可（当前环境两者默认行为已兼容，通常无需处理）。

## 构建

```bash
npm run build      # 类型检查 + 产物输出到 dist/
npm run preview
```

`base: './'`（相对路径产物），`dist/` 可放到任意静态子路径直接打开，例如与后端同源部署到 `:8080/web/`（需后端或网关把 `/api` 放行）。

### 预设公共服务器（可选）

构建时注入预设档，登录页 / 我的页会出现「公共服务器」一键切换（语义同 Android 的 `-PhypermuxPublicServerUrl`）：

```bash
# PowerShell
$env:HYPERMUX_PUBLIC_SERVER_URL='http://<host>:<port>'; npm run build
# cmd
set HYPERMUX_PUBLIC_SERVER_URL=http://<host>:<port> && npm run build
```

不注入 = 无预设档，默认档为自定义。服务器档位（公共服务器 / 自定义）存 `localStorage`：有注入默认公共服务器，无注入默认自定义；未填自定义地址前隐式走同源 `/api/app`（dev 代理或 `/web/` 同源部署兜底）。自定义档输入 `http(s)://host[:port]`；跨源访问需后端 CORS（默认放行，可用 `hypermux.cors-origins` 收紧）。

## 测试账号

| 账号 | 密码 | 说明 |
| --- | --- | --- |
| `web` | `web12345` | role=0 普通用户，web 端登录 |

管理后台账号（`/api/admin/**`，非本前端使用）：`admin` / `Admin@1234`。

## 功能

- 登录（仅 role=0 用户；401 自动跳登录页，token 存 `localStorage`）
- 扫码登录：登录页二维码（载荷 `hypermux://web-login?id=`）→ HyperMux 手机 App「扫一扫」确认 → 轮询一次性取回会话；60s 自动换新码，旧服务器无端点时提示「不支持扫码登录」
- 服务器档位：本站（默认同源）/ 公共服务器（构建注入）/ 自定义（用户输入）
- 首页：分类 + 「继续观看」
- 分类列表 / 影片详情 / 剧集网格（播放进度条、已看标记）
- 播放页：artplayer、断点续播（>30s 自动定位并 toast 提示）、10s 定时上报 + 暂停/退出/切后台 keepalive 兜底上报、看完自动标记并连播下一集、倍速 / 画中画 / 横竖屏自动转屏
- 历史记录
- 明暗主题切换（默认跟随系统）

## 接口约定（与 tv 端共享，详见 ../docs/plans/30-api-contract.md）

- 统一 `R<T> { code, msg, data }`，`code == 0` 为成功
- 断点：上报 `PUT /api/app/progress`（positionMs/durationMs，finished 0/1），播放前取 `video.progress` 恢复
- 播放直链：`POST /api/app/play-url` → OpenList `/d/` 直链（服务端已拼上 source.rootPath）
