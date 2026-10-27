# HyperMux

自托管家庭影视系统：Spring Boot 后端 + Web 管理面板 + Web 播放端 + Android 手机 App + Android TV App。
影片数据源对接 [OpenList](https://openlistteam.github.io/)（只作为视频直链来源，后端不中转视频流）。

> 规划与详细设计见 [PLAN.md](./PLAN.md) 与 [docs/plans/](./docs/plans)（10 数据模型 / 20 后端 / 30 接口契约 / 40 TV App / 45 手机 App / 50 管理后台 / 60 刮削 / 70 部署运维）。
> **📖 使用教程（部署 → 数据源 → 上架影片 → 三端观看）：[docs/使用教程.md](./docs/使用教程.md)**。

## 目录结构（monorepo）

```
.
├─ hyper-mux-server/      后端（Spring Boot 3.3.5 / Java 17 / MyBatis-Plus / PostgreSQL）
├─ hyper-mux-admin/       管理面板（Vue3 + Vite + Naive UI；构建产物 ../admin-dist 由后端同源托管）
├─ hyper-mux-web/         Web 播放端（Vue3；复用 /api/app/** 接口，role=0 普通用户）
├─ hyper-mux-android/     手机 App（Kotlin / Jetpack Compose，计划书 docs/plans/45-phone-app-detailed.md）
├─ hyper-mux-android-tv/  TV App（Kotlin / Compose for TV，计划书 hyper-mux-android-tv/docs/plans/48-tv-app-detailed.md）
└─ docs/                  设计文档（唯一事实源；改结构先改文档 + SQL，再改代码）
```

角色模型：**普通用户（role=0，手机 / TV / Web 多端通用）** 与 **管理员（role=1）**；
`/api/app/**` 需普通用户 token，`/api/admin/**` 需管理员 token。

## 远程投屏（手机 → TV）

同一账号下，手机把「正在看的那一集」投送到电视上播放；**后端只中转指令，不中转视频流**（TV 自己换 OpenList 直链）。

- 手机播放页 / 详情页点「投屏」→ 选择同账号的 TV 设备 → 手机显示「正在等待「电视名」确认…」
- TV 弹窗「接收到来自手机端的投屏请求 / 是否进入播放：片名 第N集」，**默认「是」，6 秒倒计时后自动播放**（按遥控器 BACK = 拒绝）
- TV 接受后从手机当前位置起播，手机弹「**TV端已接受你远程投屏的请求**」并停止本地播放（只保留「取消投屏」）
- **手机端不做遥控器**：本期没有播放/暂停/seek/选集遥控控件
- **单并发**：每账号同时只允许 1 路投屏，新请求会接管旧投屏
- 前置条件：**两端登录同一账号**，且 **TV App 在前台运行**（TV 每 2s 轮询服务端接收投屏）
- 管理后台「系统设置 → 远程投屏」可关闭功能、调整确认倒计时（3~15 秒，默认 6）

设计、接口契约与**测试流程与预期结果**见 [docs/plans/80-cast-detailed.md](./docs/plans/80-cast-detailed.md) 与 [docs/plans/30-api-contract.md §3.13](./docs/plans/30-api-contract.md)。

## 构建与运行

### 后端 hyper-mux-server

前置：JDK 17、可达的 PostgreSQL。复制 `src/main/resources/application-dev.yml.example`
为 `src/main/resources/application-dev.yml` 并按本机情况填写（该文件已 gitignore，勿提交）。

```bash
cd hyper-mux-server
mvn -B package -DskipTests
java -jar target/hypermux-server.jar
#   API      : http://127.0.0.1:8080
#   Swagger  : http://127.0.0.1:8080/swagger-ui.html   （dev profile）
#   健康检查 : http://127.0.0.1:9090/actuator/health   （管理端口，不对外）
```

首次启动自动执行 `sql/schema.sql` 幂等建表，并在库内无管理员时自举创建 admin。

### 管理面板 hyper-mux-admin

```bash
cd hyper-mux-admin
npm install
npm run dev          # 开发模式 :5173，/api 代理到 :8080
npm run build:dist   # 产物输出 ../admin-dist，重启后端后访问 http://127.0.0.1:8080/ 即管理面板
```

### Web 播放端 hyper-mux-web

```bash
cd hyper-mux-web
npm install
npm run dev          # 开发模式
npm run build        # 产物 dist/，静态部署或直接预览
```

### 手机 App hyper-mux-android / TV App hyper-mux-android-tv

前置：JDK 17、Android SDK（`local.properties` 里 `sdk.dir=...`，该文件已 gitignore）。

```bash
cd hyper-mux-android        # 或 hyper-mux-android-tv
gradle assembleDebug        # 调试包
gradle assembleDist         # debug + release 并复制到 dist/（需先配置 keystore.properties，否则 release 退回 debug 签名）
```

**公开服务器档位（可选）**：两个 App 均支持构建期注入内置「公开服务器」地址：

```bash
gradle assembleRelease -PhypermuxPublicServerUrl=http://your-server:8080
```

不注入时 App 不提供「公开服务器」选项，用户需在登录页自行输入服务器地址。
另有 `-PhypermuxApiBase=http://host:port` 可预填「自定义服务器」输入框（TV 盒子免手输）。

## 安全须知（部署前必读）

- 默认管理员 `admin / Admin@1234` 仅是 **dev profile 自举密码**，上线前必须修改并清空 `admin-bootstrap-password`。
- `JWT_SECRET`、`HYPERMUX_SECRET`（OpenList token 落库加密密钥）生产必须覆盖默认值；改变后旧 token 与已存源 token 失效。
- 数据库连接、keystore、`local.properties` 等本机敏感文件均已在 `.gitignore` 中，切勿提交。

## 合规免责声明

本项目是**自托管媒体管理工具**，仅供学习与技术研究。项目本身不提供任何影视内容；
使用者需自行确保所接入数据源（OpenList 等）中的视频内容**来源合法并拥有相应授权**。
因使用者接入或传播侵权内容产生的任何责任，由使用者自行承担。

## License

[MIT](./LICENSE) © 2026 blueflower1
