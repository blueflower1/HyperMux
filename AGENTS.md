# HyperMux 仓库协作约定

## Git 提交规则（重要）

- **不要自动提交/推送**。日常调整（改代码、修 UI、打包验证）只在本地进行。
- **仅当用户明确说“提交”“推送”“commit”时**，才执行 `git commit` / `git push`。
- 提交前按惯例先跑敏感信息扫描（git grep 检查密码/IP/keystore 等）。

## 构建速查

- 后端：`hyper-mux-server`，JDK 17 + `D:\ai-projects\hyper-mux\tools\apache-maven-3.9.9\bin\mvn.cmd -B package -DskipTests`（jar 运行中会锁文件，先停进程）
- 管理面板：`hyper-mux-admin` → `npm run build:dist`（产物 `../admin-dist`，重启后端生效）
- App：`hyper-mux-android` / `hyper-mux-android-tv` → `D:\DEV_HOME\gradle-8.12.1\bin\gradle.bat assembleDist`（JAVA_HOME=D:\DEV_HOME\jdk17）
  - 公开服务器注入：`-PhypermuxPublicServerUrl=http://host:port`（不注入则登录页只有自定义档）
- Web 观看端：`hyper-mux-web` → `npm run build`（产物 `dist/`，可部署到 `:8080/web/` 同源子路径）
  - 公共服务器注入：打包前 `set HYPERMUX_PUBLIC_SERVER_URL=http://host:port`（注入则默认公共服务器档；不注入默认自定义档）
  - 跨源自定义档依赖服务端 CORS（默认放行，`hypermux.cors-origins` 可收紧）
- adb：`D:\dev_home\android-sdk\platform-tools\adb.exe`（真机/模拟器调试可用）

## Compose UI 注意

- `Text` 带 `heightIn` 时内容贴顶绘制，不会自动垂直居中；需要居中要包 `Box(contentAlignment = ...)`。
