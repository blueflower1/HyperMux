# HyperMux Android（手机端）

HyperMux 影视系统的**安卓手机原生 App**（Kotlin + Jetpack Compose）。与 TV 端、Web 端共用同一套 Spring Boot 后端与 `/api/app/*` 接口契约（[docs/plans/30-api-contract.md](../docs/plans/30-api-contract.md)），后端零改动。

- 详细设计与验收标准：[docs/plans/45-phone-app-detailed.md](../docs/plans/45-phone-app-detailed.md)
- 总纲：[PLAN.md](../PLAN.md)（里程碑 **M2P**）

## 技术栈

| 项 | 选型 |
|---|---|
| 语言/构建 | Kotlin 2.0 + Gradle KTS（AGP 8.7） |
| SDK | compileSdk 35 / minSdk 24 / targetSdk 35 |
| UI | Jetpack Compose + Material 3（深色默认） |
| 播放 | Media3 ExoPlayer（MP4/MKV/HLS） |
| 网络 | Retrofit2 + OkHttp + Gson |
| 依赖注入 | Hilt |
| 图片 | Coil 3 |
| 持久化 | DataStore Preferences（token / 服务器地址 / 主题 / 搜索历史） |

## 构建运行

### 命令行构建（本机已就绪，2026-10-11 实测）

```powershell
$env:JAVA_HOME = "D:\DEV_HOME\jdk17"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"   # 中文 Windows 必需（否则源码按 GBK 误读）
D:\dev_home\gradle-8.12.1\bin\gradle.bat -p . assembleDebug --no-daemon
D:\dev_home\gradle-8.12.1\bin\gradle.bat -p . assembleRelease --no-daemon
```

- JDK 17 位于 `D:\DEV_HOME\jdk17`；Gradle 8.12.1 位于 `D:\dev_home\gradle-8.12.1`；Android SDK 位于 `D:\dev_home\android-sdk`（`local.properties` 已指向）。
- 产物：`app/build/outputs/apk/{debug,release}/`；已复制到 `dist/`（debug 21.1 MB / release 2.6 MB）。
- 详细环境与验收：[docs/build-and-acceptance.md](docs/build-and-acceptance.md)

### Android Studio

用 **Android Studio**（Ladybug 及以上）打开本目录，同步后运行 `app` 模块到真机/模拟器（minSdk 24+）。

### release 签名

- 自签名密钥：`keystore/hypermux-release.jks`，口令见 `keystore.properties`（二者已 gitignore）。
- 缺 `keystore.properties` 时 release 自动退回 debug 签名（可安装，仅本地测试）。

### 服务器地址

自托管场景：App 启动后在**登录页**填写服务器地址（如 `http://192.168.31.100:8080`）→「测试连接」→ 登录。地址持久化在本地，「我的」页可查看。

- debug/release 默认**均允许明文 http**（自托管测试）；上架时用 `-PhypermuxCleartext=false` 关闭。
- 构建期预置地址：`./gradlew assembleDebug -PhypermuxApiBase=http://host:8080`

## 目录结构

```
app/src/main/java/com/hypermux/mobile/
├─ HyperMuxApp.kt          # Application：Hilt / Coil / 会话预载 / 崩溃兜底
├─ config/AppConfig.kt     # 阈值常量（断点 10s、续播 30s、过期提前量 300s…）
├─ data/
│  ├─ api/                 # ApiService（契约 §3 1:1）、拦截器（鉴权/服务器地址重写）、NetworkMonitor
│  ├─ model/               # DTO（与契约字段同名）
│  ├─ repo/                # Auth / Content（离线缓存降级）/ Playback / Settings
│  └─ cache/CacheStore.kt  # home / 分类第 1 页 JSON 缓存
├─ di/AppModule.kt         # Gson / OkHttp / Retrofit
├─ ui/
│  ├─ navigation/          # 路由表 + 登录守卫 + 底部导航
│  ├─ theme/               # Material 3 深色/浅色品牌色板
│  ├─ common/              # ErrorState / OfflineBanner / CoverImage / 进度条 / 时间格式化
│  ├─ login/               # 登录（服务器地址可配 + 连通性测试）
│  ├─ home/ category/ search/ movie/ player/ history/ me/
│  └─ *_ViewModel.kt
└─ res/
```

## 里程碑进度（对应 45 计划书 §10）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| P0 | 脚手架 + 数据层 + 主题 + 导航 + 登录闭环 | ✅ 已完成（2026-10-11） |
| P1 | 首页聚合（分类 rail + 继续观看 + 影片网格 + 下拉刷新）/ 分类列表分页 + 分类内搜索 / 全局搜索（防抖 + 搜索历史）+ 离线降级 | ✅ 已完成（2026-10-11） |
| P2 | 影片详情（海报头图 + 元数据 + 标签 + 简介 + 季 Tab + 内嵌剧集网格）+ Media3 播放页（横屏锁定/手势层/续播 seek/自动连播/错误分类/10s 断点上报） | ✅ 已完成（2026-10-11） |
| P3 | 历史页（继续看/标记看完/删除确认弹层 + 离线兜底）/ 我的页（封面缓存大小 + 清除确认）/ 加固（首页双击返回退出、行操作 Snackbar 反馈） | ✅ 已完成（2026-10-11） |
| P4 | 真机机型矩阵验收 + release 签名 + 性能/包体 | ✅ 已实施（2026-10-11，见 [docs/build-and-acceptance.md](docs/build-and-acceptance.md)）；真机手测待执行 |

## 约定（与后端契约一致）

- 统一响应 `R<T>{code,msg,data}`，`code==0` 成功；失败 code 即 HTTP 状态码
- 双前缀鉴权：`/api/app/**` 用户 token（role=0，7d）；本 App 只用 app 前缀
- 时间一律毫秒时间戳；**注意**：`videos[].duration` 单位是**秒**，`progress.*Ms` 是毫秒
- 密码字符集 `[a-zA-Z0-9_.]{6,20}`，用户名 `[a-zA-Z0-9_]{3,32}`
- 断点上报：播放中每 10s 节流 + 暂停/退后台即时；position<3s 不上报
