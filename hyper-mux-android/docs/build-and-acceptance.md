# HyperMux Android · 构建与真机验收（P4）

> 对应计划书 [45-phone-app-detailed.md §10](../../docs/plans/45-phone-app-detailed.md) 的 Definition of Done。
> 本文同时记录 P4 首次可编译时修复的关键问题，供后续防再犯。

---

## 1. 本机构建环境（2026-10-11 实测可用）

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | 17.0.10（JAVA_HOME=`D:\DEV_HOME\jdk17`） | 已有 |
| Gradle | 8.12.1 | `D:\dev_home\gradle-8.12.1` |
| Android SDK | cmdline-tools + platforms;android-35 + build-tools;35.0.0 + platform-tools | `D:\dev_home\android-sdk` |
| 工程 SDK 指向 | `local.properties` → `sdk.dir=D\:\\dev_home\\android-sdk` | 工程根 |

命令行构建（PowerShell）：

```powershell
$env:JAVA_HOME = "D:\DEV_HOME\jdk17"
# 关键：中文 Windows 默认 GBK，必须强制所有 JVM 以 UTF-8 读源码，否则中文注释被误解析
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
D:\dev_home\gradle-8.12.1\bin\gradle.bat -p D:\ai-projects\hyper-mux\hyper-mux-android assembleDebug --no-daemon
```

> 也可用 Android Studio（Ladybug+）直接打开工程同步构建；`gradle.properties` 已写入 `kotlin.daemon.jvmargs=-Dfile.encoding=UTF-8`。

## 2. 产物

| 变体 | 路径 | 大小 | 签名 |
|---|---|---|---|
| debug | `dist/hypermux-mobile-0.1.3-debug.apk` | ≈44.5 MB | debug 签名 |
| release | `dist/hypermux-mobile-0.1.3-release.apk` | **≈25.6 MB** | 自签名（v2 方案，R8 压缩 + 资源裁剪） |

- 一条命令产出两个包并落到 `dist/`（命名即上表，与 `app/build.gradle.kts` 的 `assembleDist` 任务一致）：

  ```powershell
  $env:JAVA_HOME = "D:\DEV_HOME\jdk17"
  D:\dev_home\gradle-8.12.1\bin\gradle.bat :app:assembleDist
  ```

- 包体主要来自 `nextlib-media3ext`（FFmpeg 软解，含全部 ABI 原生库）：为兼容 DTS/TrueHD 音轨与无硬解设备，属**有意为之**，不要为减体积而删。

- release 密钥：`keystore/hypermux-release.jks`（alias `hypermux`，口令见 `keystore.properties`，**二者均已 gitignore，请勿提交**）。
- `keystore.properties` 缺失时 release 自动退回 debug 签名（仍可安装，仅本地测试）。
- 包名 `com.hypermux.mobile`，minSdk 24 / targetSdk 35，权限为 `INTERNET` + `ACCESS_NETWORK_STATE` + `MODIFY_AUDIO_SETTINGS`（均为 normal 权限，安装即授予、无运行时弹窗；最后一项是播放页调音量所必需，见 §10 #16）。
- release 已生成 **baseline profile**（`compileReleaseArtProfile`），利于启动帧耗时。

### 明文 http 开关

自托管测试默认允许 http（debug/release 均为 `usesCleartextTraffic=true`）。
上架/正式发布时关闭：

```powershell
gradle assembleRelease -PhypermuxCleartext=false
```

## 3. 真机验收清单（对应 §10）

### 手测主线

- [ ] **1 首登**：输入账号 → 进首页；杀进程重启 → token 有效免登；后台删用户 → 下次请求 401 → 自动回登录页。
- [ ] **2 首页**：分类 rail 切换 / 卡片渲染 / 继续观看排序 / 下拉刷新；断网 → 缓存 + 离线黄条，再联网恢复。
- [ ] **3 搜索**：全局搜索出结果 / 空态 / 搜索历史点击复用 / 防抖不抖动。
- [ ] **4 详情→播放**：MKV 直链播通；断点 seek（浮层「从 … 继续」）；±10s 滑动手势；双击暂停；长按 2 倍速；回退再进 → 续播；自然播完自动下一集；末集播完回详情。
- [ ] **5 历史**：列表进入 / 继续看 / 标记看完 / 删除（remove 后不再出现）。
- [ ] **6 我的**：主题切换即时生效；改服务器地址 → 测试连接 → 重登；清缓存生效（封面缓存数值变化）。
- [ ] **7 错误**：坏链（HTTP 404）→ 文案 + 不重试 + 返回详情；慢链 → 自动重试成功恢复；play-url 超时 loading 文案。
- [ ] **8 稳定性**：连续 30min 播放无 OOM/泄漏；横竖屏切换不重建丢失状态。

### 机型矩阵

| 机型 / 系统 | 分辨率 | 编解码 | 结果 | 备注 |
|---|---|---|---|---|
| （待填） | | | | HEVC/10bit 软解卡顿归因 |

### 单测（JVM）

- [ ] **9** `AuthRepo` 过期判定（-300s buffer）、`ApiResult` 401 流程。
- [ ] **10** 断点 tick 节奏（Mock currentTime：10s 边界、pause 即时、<3s 跳过）。
- [ ] **11** 集排序 / next-ep 边界（跨季、末集、中间缺失集号）。
- [ ] **12** 服务器地址重写 Interceptor（host 替换、path 保留）。

## 4. P4 首次编译修复记录（防再犯）

工程代码此前从未编译过，首次 `assembleDebug` 暴露并修复了以下问题：

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | Hilt 报 `@HiltAndroidApp base class must extend Application. Found: kotlin.Any` | Application 同时实现 `coil3.ImageLoader.Factory` 接口时，Hilt 的 KSP 处理器（基于内置 shaded XProcessing）解析不到类基类 | 改为 `Application()` 单继承，全局 ImageLoader 用 `SingletonImageLoader.setSafe { }` 配置 |
| 2 | Coil 3 依赖坐标错误 | Coil 3 的 groupId 是 `io.coil-kt.coil3`（非 Coil 2 的 `io.coil-kt`） | 修正 `libs.versions.toml` |
| 3 | Coil 3 缓存 API 不存在 | Coil 3 无 `ImageLoader.Builder.memoryCachePercent/diskCachePercent` | 用 `MemoryCache.Builder().maxSizePercent(ctx, 0.25)` + `DiskCache.Builder().directory(File).maxSizePercent(0.05)` |
| 4 | 中文源码被按 GBK 误读 | 中文 Windows 上 JDK17 默认字符集为 GBK，Kotlin 编译守护进程/ KSP worker 独立 fork | `kotlin.daemon.jvmargs=-Dfile.encoding=UTF-8` + 构建时注入 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` |
| 5 | `ServerUrlInterceptor.kt` 报 `Unclosed comment` | Kotlin **块注释支持嵌套**，KDoc 正文里出现 `/**`（形如 `/d/**`）会开启嵌套注释 | 正文避免 `/**`，并全项目核对 `/*` 与 `*/` 配平 |
| 6 | 一批 API 误用/缺导入 | 未编译过的代码里存在过时/臆测 API：`Material3.pullrefresh`（应为 `pulltorefresh.PullToRefreshBox`）、`GridItemSpan.maxLineSpan`（应为 `GridItemSpan(maxLineSpan)`）、`Modifier.clickable(onLongClick=)`（应为 `combinedClickable`）、`kotlinx.coroutines.flow.snapshotFlow`（应为 `androidx.compose.runtime.snapshotFlow`）、`dagger.hilt.EntryPointAccessors`（应为 `dagger.hilt.android.EntryPointAccessors`）、`Color.HSVToColor`（需 `android.graphics.Color`）、`ExoPlayer.Builder.setMinBufferMs`（应为 `DefaultLoadControl`）、`ExoPlayer.retry()`（Media3 1.7 已移除，用 `prepare()`）、`MediaItem.Builder.setHeaders`（已移除，改由 DataSource 注入请求头）、`awaitEachGesture` 包名（`foundation.gestures`）、`AwaitPointerEventScope` 受 `@RestrictsSuspension` 限制不能用 `coroutineScope/launch`（改用 `detectTapGestures` + `onPress.tryAwaitRelease()`）、`StateFlow` 直接 `by` 委托（需 `collectAsStateWithLifecycle()`） | 逐一修正，见各文件 |
| 7 | KSP2 与 Hilt 兼容性 | KSP 2.0.21 默认启用 KSP2，与 Hilt 存在已知不兼容（dagger #4303） | `gradle.properties` 加 `ksp.useKSP2=false`（注意是大写 KSP2） |
| 8 | OkHttp `HttpUrl.parse` 为 ERROR 级废弃 | OkHttp 4.12 将其标记为 `DeprecationLevel.ERROR` | 改用 `String.toHttpUrlOrNull()`；`newBuilder()` 用 `encodedPath()` 保留原路径 |
| 9 | 「测试连接」恒失败（后端 `No static resource actuator/health`） | `actuator/health` 部署在**管理端口 9090**（`management.server.port`，仅本机可达，见 70-deploy-ops.md），主端口 8080 上不存在该路径；且探测不存在的路径会触发后端 `NoResourceFoundException` → 打 ERROR 日志 | 改用受保护的 `GET /api/app/home`（带 `X-HyperMux-Probe` 头）：**401 即判定可达**，由后端 `BizException` 正常返回、无 ERROR 日志；App 侧拦截器据该头跳过 401 会话失效副作用；顺带地址缺 scheme 时自动补 `http://` |
| 10 | 正确密码仍提示「密码需为 6~20 位字母、数字、点或下划线」，无法登录 | App 登录页把**建用户/重置密码**的字符集 `[a-zA-Z0-9_.]{6,20}` 误当作**登录**校验规则；后端 `LoginReq` 对密码**只校验长度 6~20**，不含字符集（初始管理员密码 `Admin@1234` 含 `@`，被本地校验拦下，请求根本没发出去） | App 登录页改为只校验长度 6~20；文档同步订正（40/45 计划书、70 运维） |
| 11 | **release 包首页报"网络出错了"（debug 包正常）** | R8 full mode 裁剪了 Retrofit 方法的**泛型返回类型**，Gson 只能把 `data` 反序列化成 `LinkedTreeMap`，随后强转 `PageVo<MovieCardVo>` 抛 `ClassCastException`（`home` 是非泛型 `HomeVo` 所以正常，只有泛型列表接口挂） | `proguard-rules.pro` 显式补 R8 full-mode 泛型规则（`-keepattributes Signature` + Retrofit 返回类型 `<3>` + `Continuation`/`Response`）；`SafeApiCall` 增加异常日志便于定位 |
| 12 | **启动竞态**：偶发"连接被拒绝"（ECONNREFUSED） | 首个请求可能早于 `SettingsRepo.preload()` 完成，`cachedServerUrl` 仍为 null → 拦截器用 Retrofit 占位地址 `http://localhost:8080/` 发请求 → 被拒 | `SettingsRepo.serverUrlBlocking()` / `AuthRepo.tokenBlocking()`：拦截器同步兜底读一次 DataStore（OkHttp 后台线程，阻塞可接受） |

> 上述 #11/#12 已在真机（模拟器）用 **release 包**复现并验证修复：登录 → 首页（分类 + 影片卡片 + 评分角标）→ 详情 → 历史 → 我的 全链路正常，logcat 无异常。

## 5. UI / 动效（第二轮）

> 第三轮已按 **web 端设计系统**（`hyper-mux-web/src/style.css`）重做，见 §7。以下为第二轮记录。

- 设计令牌：`ui/theme/Theme.kt` 增加 `Spacing`、`Motion`、`AppShapes`。
- 动效工具：`ui/common/Motion.kt` — `Modifier.bounceClick()`、`rememberShimmerBrush()`、`ShimmerBlock`。

## 6. 测试账号（本地测试用）

通过管理端接口创建的 App 用户（role=0）：**`apptest` / `Apptest123`**。
手机端必须用 role=0 用户登录；`admin` 是管理端账号（role=1），登录 App 会被 403「该账号无此端访问权限」。

## 7. 第三轮：崩溃 / 海报 / 按 web 重做 UI（2026-10-12）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 13 | **下拉刷新必崩**（`IllegalArgumentException: Key "117" was already used`） | 下拉刷新会把 `movies` 清空并把 `page` 重置为 1，此时触底监听又触发 `loadMore()`（列表为空 → 绕过 `size >= total` 守卫）→ **重复请求第 1 页**并追加同一批影片 → LazyGrid 出现重复 key | 三个列表 VM（Home/Category/Search）统一加固：刷新期间禁止翻页（`_isLoading` 守卫 + `page<=1`）、追加时 `distinctBy { it.id }` 兜底、刷新取消在途任务、`CancellationException` 透传 |
| 14 | **海报全部不显示** | ① Coil 3 的**网络抓取器是独立 artifact**（`coil-network-okhttp`），未引入 → 根本无法加载网图；② 豆瓣图床**防盗链**：无 Referer 直接 **HTTP 418**（实测带 `Referer: https://movie.douban.com/` 才 200） | 引入 `coil-network-okhttp`；ImageLoader 注册 `OkHttpNetworkFetcherFactory(callFactory = { … })`，对 douban 系域名补 Referer + 浏览器 UA |
| — | **UI 按 web 重做** | 与 web 端视觉语言不一致（橙色 vs 靛紫、直角 vs 圆角描边卡片） | 对齐 `hyper-mux-web/src/style.css`：主色 `#6D7DFF`(深)/`#5B6CFF`(浅) + 紫色渐变 `#9A6BFF`；深色底改中性近黑 `#0E1014`；圆角 10/14/18 + 1px 描边 + 柔和阴影；卡片改「左上★评分胶囊 + 右下集号 + 底部渐变进度条 + 年份·地区·集数 + 标签」；分区标题加 4dp 渐变竖条；分类 chips 改描边胶囊（带影片数）；登录页改居中卡片 + 渐变 Logo；卡片按压缩放反馈 |

> 已在模拟器（MuMu / Android 15）实测：debug 与 release 均 **海报加载成功**（`RealImageLoader: ☁️ Successful (NETWORK)` + 磁盘缓存落盘）、**连续 6 次下拉刷新无崩溃**、进程存活。

## 8. 第四轮：播放页按爱奇艺交互重做 + 黑屏归因（2026-10-12）

**播放黑屏归因（不是 App 逻辑 bug）**：实测片源为 `与凤行-4K-AV1/01.mp4`（1.7GB，**AV1 4K**）。模拟器仅有 `c2.android.av1.decoder` / `c2.android.av1-dav1d.decoder`，且都标注 `variant="slow-cpu,!slow-cpu"`（会被判定为慢而不可用）→ **音频正常、视频帧渲染不出来**。设备实测：播放位置正常推进（00:15 / 37:56）、选集正常，说明播放链路本身是通的。
Media3 无可用的 AV1 软解扩展（`androidx.media3:media3-decoder-av1` 在 1.7.1 不存在），App 无法自带 AV1 解码器；**要真正出画面需把片源换成 H.264(avc)，或使用支持 AV1 硬解的真机**。

App 侧改进（本轮）：
- **播放页改为「默认竖屏 + 显式全屏」**（对齐爱奇艺手机端交互）：顶栏（返回/片名/当前集）+ 16:9 播放区 + 控制条（播放暂停/进度/**全屏**）+ 影片信息 + 季 Tab + **选集网格**（当前集高亮、已看置灰）；点全屏才横屏沉浸，右上角退出；返回键在全屏时先退全屏。不再一进播放页就强制横屏（此前会导致模拟器/系统卡在横屏）。
- 竖屏下保留双击播放暂停、左右滑动 seek、上下滑动亮度/音量、长按 2 倍速。
- 新增**首帧看门狗**：已在播放但 6s 未渲染首帧 → 顶部黄条提示「只有声音没有画面：片源可能是 AV1 / HEVC 4K，当前设备解码过慢或不支持，建议换 H.264 源」。
- 解码类错误文案改为可操作建议（含 AV1）。

> 真机验证建议：优先用 **H.264** 片源回归；AV1 需真机硬解支持。控制条/选集布局已通过 uiautomator 树验证（竖屏 1440x2560，选集 1..39 正常渲染）。

## 9. 第五轮：后端重建 + App 修复（2026-10-12）

**背景**：一次目录搬迁事故导致后端源码丢失（数据库未受影响）。后端已按 `docs/plans/30-api-contract.md` + 现网表结构 1:1 重建（详见 `hyper-mux-server/README.md`），**App 接口契约零变更**。

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 15 | **「标记看完 / 移除」必失败** | `ApiService.finishProgress` 声明为 `@GET` 且带 `@Body` —— Retrofit 在调用时直接抛 `Non-body HTTP method cannot contain @Body` | 改为 `@POST("api/app/progress/finish")`（与契约 §3.9 一致） |

**App 侧新增能力（后端已支持）**：
- `MovieCardVo.backdropUrl` 已纳入契约与后端出参（横版剧照；为空时 App 按现有竖版海报展示，无需改动）。
- 所有远程图片统一走自有服务 `{server}/img?u=<encoded>`（后端带 Referer 下载 + 本地缓存），可同时规避豆瓣防盗链与远程图失效；App 原有的 `proxyImageUrl()` 逻辑不变。

**重建后端后的端到端实测（本机 8080）**：登录 / 首页 / 类型片单 / 搜索 / 详情 / 剧集 / 播放直链（含 Referer 头）/ 断点上报 / 历史 / 图片代理 全部通过；`/api/admin/*`（总览/分类/影片/剧集/标签/用户/数据源/向导/元数据/设置）全部通过，OpenList 实机连通（v4.1.8），目录导入解析出 39 集（S1E1..E39）。

## 10. 第六轮：播放页闪退 + 卡顿修复（2026-10-13）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 16 | **播放页右半屏上下滑调音量必闪退** | `AudioManager.setStreamVolume` 官方标注 `@RequiresPermission(MODIFY_AUDIO_SETTINGS)`（AudioManager 文档同样写明 Requires 该权限），而清单里只有 INTERNET / ACCESS_NETWORK_STATE → 调用直接抛 `SecurityException`；免打扰等受限场景同样会抛 | ① 清单补 `MODIFY_AUDIO_SETTINGS`（normal 权限，安装即授予）；② `onVolume` / `onBrightness` 全部 `runCatching` 兜底：失败只记日志，最坏"音量不变"，**绝不闪退** |
| 17 | **拖动调亮度/音量明显掉帧** | `onBrightness` 每个触摸事件都写 `window.attributes = ...`（一次 `relayoutWindow` binder 事务）；`onVolume` 每个事件都 `setStreamVolume`（AudioService binder 事务）。高刷屏触摸每秒上百事件 → 主线程被系统调用压满 | 按"显示精度"节流：亮度只在**整数百分比变化**时写窗口、音量只在**档位变化**时调用（一次拖动最多十几次，而不是上百次） |
| 18 | **播放中整页 2Hz 重组**（动画掉帧的隐性来源） | `positionMs/durationMs/bufferedMs` 在 `PlayerScreen` 根作用域被读（再当参数传下去也算根作用域读），Compose 会把整页（含选集网格、季 Tab、全部按钮）标记失效 → 每秒 2 次整页重组 | 改为 `@Stable PlaybackProgressState` 持有者对象传入控制条，**只有控制条内部读**，重组范围收缩到"控制条 + 进度条" |
| 19 | 缓冲指示器**一闪一闪** | 直接跟随 `STATE_BUFFERING`：网络流（尤其 CacheDataSource 边播边缓存）在 READY ↔ BUFFERING 之间频繁抖动，指示器跟着闪 | 加 350ms 防抖：连续缓冲 ≥350ms 才显示，恢复播放立即隐藏 |
| 20 | 缓冲时播放/暂停图标**翻成"播放"** | 图标由 `isPlaying`（= playWhenReady && 已就绪）驱动，一进再缓冲就变 false | 改用 `playWhenReady`（用户意图）驱动图标与"4s 自动隐藏" |
| 21 | 缓存字节轮询占主线程 | `SimpleCache.getCachedBytes`（读缓存索引，可能落盘）每 2s 在主线程跑一次 | 挪到 `Dispatchers.IO`；且一旦已有缓存字节即停止轮询（按钮态只由 `downloadStates` 驱动） |
| 22 | 渐变 Brush / Skia shader 每帧新建 | 进度条已播放段、横竖屏两侧 scrim、OSD 竖条填充都在组合/绘制里现场 `Brush.xxxGradient(...)` | 全部 `remember` 复用（避免每帧生成 shader） |

### 10.1 追查修正（#16 并未真正修掉闪退）· 2026-10-14

用户复测反馈：**左半屏调亮度正常，右半屏调音量仍然直接闪退**。说明 #16 的权限/兜底不是真正根因。

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 23 | **右半屏调音量闪退（真根因）** | `PlayerIcons.VolumeUp/VolumeOff` 的喇叭路径里有 **`q.9 0 .9.9`**。SVG 允许省略分隔符（`".9.9"` = `.9` 与 `.9`，`"0.6.5"` = `0.6` 与 `.5`），而我自写的数字扫描器**连续吃掉了两个小数点**，把 `".9.9"` 交给 `toFloat()` → `NumberFormatException: multiple points`。图标是 `by lazy`，**首次渲染那一刻**才构建 → 音量 OSD 一出现就崩（亮度图标没有相邻小数点，所以左侧一直正常） | 按 SVG 文法重写数字扫描器（`fractional-constant` 最多一个小数点；指数可选、非法时回退）；OSD 图标选择处再过一道 `safePlayerIcon`（构建失败只记日志 + 少画一个图标，绝不再崩） |
| 24 | **快速甩动/合成输入时"滑了没反应"** | 拖动层用"超过 touchSlop 的那一次事件"只决定方向、**不把该次位移落到手势上**；当输入把整段位移压进同一个事件（甚至直接是 UP）时，整段手势被丢弃（排查时表现为 adb 注入的 swipe 完全无反应，而同区域点击正常） | 抽出 `applyDrag()`，**决定方向的那一次也套用同一份"相对按下点的累计位移"计算**（水平 seek / 垂直亮度音量统一入口），并顺带 consume，避免同一手势又被判成单击 |

**真机/模拟器验证（MuMu Android 15，adb 16384）**：装修复后的包 → 首页进详情 → 起播 → 在播放区做竖直滑动，`adb logcat` 与截图证据：

- 右半屏下划：`onVolume dy=354 base=63 maxVolume=100` → 音量 **63 → 14 → 0**，音量面板正常渲染（0% 时用的是 `VolumeOff` 图标），**进程存活、无崩溃**；
- 左半屏下划：亮度面板正常渲染（percent 13→70），同样无崩溃；
- 横滑 seek、双击播放暂停均正常。

**验证手段（已落在工程里，值得保留）**：`tools/iconcheck/` 把 `PlayerIcons.kt` 的 `parseSvgPath/appendArc` 原样复制过来，借用 Gradle 缓存里的 `kotlin-compiler-embeddable` 直接编译运行 —— **用真实 Kotlin 语义**跑全部 17 个图标，检查是否抛异常、坐标是否有限且在 24 网格内：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\iconcheck\Run-IconCheck.ps1
```

修前输出：

```
[FAIL] VolumeUp  -> java.lang.NumberFormatException: multiple points
[FAIL] VolumeOff -> java.lang.NumberFormatException: multiple points
```

修后 17 个图标全部 `[ OK ]`。⚠️ `IconCheck.kt` 里的解析代码与图标路径是 `PlayerIcons.kt` 的**副本**，改动 `PlayerIcons.kt` 后要同步过来再跑（新增/修改自绘图标务必跑一遍，比装机器上试快得多）。

**产物**：`gradle :app:assembleDist` 一条命令出 debug + release 并落到 `dist/`（见 §2）。

> 说明：上述 #17~#22 都是"主线程每帧被额外占用"的类型——如果片源本身是 AV1/HEVC 4K 且设备只能软解（见 §8），系统级卡顿仍会存在，那属于解码能力问题，不是 UI 层的可优化项。

## 11. 第七轮：取消边看边缓存 + 显式下载专用 + 「我的下载」单入口合并（2026-10-14）

**需求变更**（用户确认 v2.1）：
1. **取消"边看边缓存"**：播放不再往缓存写任何字节；只有用户**显式发起**的下载才落盘。
2. 「我的」页 download 入口合并为单个「我的下载」：页顶 = 进行中任务区（原下载中心内容），页底 = **全部**下载影片的海报网格（3 列，分类页同款样式，含 下载中/暂停/失败/已完成 全状态）。点海报 → 影片详情页（复用，零改动）。
3. **去掉 2GB 容量上限**：`NoOpCacheEvictor` 不自动淘汰，下载只增不减、由用户显式删除；配套**入队前预检**（设备剩余 < 100MB 拒绝入队 + "设备空间不足"提示）。
4. 存储条瘦身：仅一行 "已下载 X · 设备剩余 Y" + [清空]（剩余 < 1GB 红色警告）；"仅 Wi-Fi 下载"开关归「我的」页。

**变更**：

| 文件 | 变更 |
|---|---|
| `data/cache/MediaCacheManager.kt` | LRU→`NoOpCacheEvictor`（无上限）；`groups()` 单源 = DownloadIndex；新增 `localFileOf(videoId)`（`getContentMetadata`+`getCachedSpans` 判"单个从 0 开始、isCached、覆盖全长"的完整 span → 返回其本地文件）；新增 `removeGroup(movieId)`（整部删除）；新增 `canEnqueueForSpace()`（`MIN_FREE_BYTES_TO_ENQUEUE`=100MB）；init 一次性迁移（清旧播放缓存分片 + 孤儿元数据，prefs 标记 `migrated_v7_downloads_only`）；删除 `notePlayback` / `cacheDataSourceFactory` / `STATE_PLAYBACK_CACHE`；`CacheStats` 增 `movieCount` |
| `data/cache/DownloadMetaStore.kt` | 删 `v_<videoId>` 反查索引（播放缓存归组用，已废弃）；新增 `removeMovie` / `pruneOrphans`（迁移用） |
| `ui/player/PlayerController.kt` | 去掉 CacheDataSource 包装：`DefaultMediaSourceFactory(DefaultDataSourceFactory(context, httpFactory))` —— 1.7 的 `DefaultDataSource` 原生按 scheme 分发，`file://` 走内置 `FileDataSource`（已对照 1.7.1 AAR 字节码核实），http 仍走自有 `DefaultHttpDataSource`（Referer 防盗链头不变） |
| `ui/player/PlayerScreen.kt` / `PlayerViewModel.kt` | 开播先查 `localFileOf`：整集已下载 → `Uri.fromFile`（本地文件、免网络请求头、离线可播，logcat `PlayerScreen: playing local download file`）；否则 → 网络直链流；移除 `setCustomCacheKey` 与 `notePlayback` |
| `ui/downloads/`（整页重构） | `DownloadCenterScreen/ViewModel` → `MyDownloadsScreen/ViewModel`：存储条（一行 + 清空）+ 进行中任务（**仅未完成**集，小组折叠/展开、暂停/继续/删除）+ 3 列海报网格（右上状态角标：下载中/失败/已暂停/已下载 + 底部进度条 + "已下 N/M 集 · 大小"）；编辑模式双粒度：任务行按"集"、海报按"影片"（整组），删除时互斥去重；保留 2s 轮询（SimpleCache 无变更回调） |
| `ui/me/MeScreen.kt` | 「我的缓存」行 → 「我的下载」（`N 部 · X GB`）→ `Route.downloads()`；Wi-Fi 开关行不动 |
| `ui/movie/MovieDetailScreen.kt` / 播放页「缓存」按钮 | 显式下载前 `canEnqueueForSpace()` 预检：不足 → toast「设备空间不足，请删除部分下载后再试」，不入队（详情页按钮 / 缓存 sheet / 播放页按钮 全覆盖） |

**真机验收清单**：
1. 显式下载入口回归：详情页「缓存」选集 sheet、选集格长按、电影单文件「缓存」按钮、播放器「缓存本集」——都能入队并显示进度。
2. 播放任意集**不再产生缓存**（无下载记录的新片看完，`player_cache` 无新文件、下载页无新条目）。
3. 下载完成的集播放：logcat 出现 `playing local download file`，**断网**仍能播（`file://` 本地）。
4. 上限已除：两部 > 2GB 的影片同时下载 / 并存，互不淘汰。
5. 剩余 < 100MB 时入队被拒（可临时调小 `MIN_FREE_BYTES_TO_ENQUEUE` 验证），提示"设备空间不足"。
6. 老版本升级首启：logcat `MediaCacheManager: cache migrated: removed N`，旧播放缓存被清、旧显式下载保留。
7. 「我的」页显示"N 部 · X GB"；下载页：已完成集只出现在海报区；编辑模式按集/按影片删除均生效；存储条「清空」全清；Wi-Fi 开关在「我的」页生效（蜂窝暂停下载）。

## 12. 第八轮：详情页缓存选集弹窗"展示不全"（2026-10-14，连 USB 真机实测定位）

**现象**：详情页"缓存"底部弹层底部内容被裁——先是 24 集季第 5 行（21–24）被拦腰切半，改完又变成底部「已选 N 集 / 缓存(N)」整行被裁在屏幕底边之外。

**定位过程**（前三层是逐步排除出来的，第 4 层才是"底栏被裁"的真根因；最后用 `adb` 直接抓 `dumpsys window` + `uiautomator` 树的**实测数值**定案）：

| # | 根因 | 修复 |
|---|---|---|
| A | `CacheSheet` 网格写死 `heightIn(max = 300.dp)`。`LazyVerticalGrid` 是**视口布局**——给 AT_MOST 约束就铺满、不收缩到内容高；24 集 5 行 ≈340dp，300dp 视口必然在中间切行 | 网格改 `weight(1f, fill = false) + heightIn(max = 620.dp)`：拿弹层内"头+尾之后剩余空间"，常见整季一屏看全；超长季在网格内滚动 |
| B | 弹层整体**没有高度上限**，长季内容会顶出屏幕 | `HyperBottomSheet` 的 `Box` → `BoxWithConstraints`，弹层 Column 加 `heightIn(max = 窗口约束高 × 90%)` |
| C | 曾怀疑 `DialogProperties.decorFitsSystemWindows`（默认 true）导致窗口高 = 内容高 + inset | 改为 `false`（保留，与 D 配套：内容不再被 decor 二次加内边距） |
| D | **真根因**：Android 15 + targetSdk 35 强制 edge-to-edge 后，WindowManager 仍把对话框窗口"fit"进系统栏（实测 `fitTypes=STATUS_BARS NAVIGATION_BARS CAPTION_BAR`，`pfl=EDGE_TO_EDGE_ENFORCED FIT_INSETS_CONTROLLED`）：**窗口 frame = [0,120]-[1080,2376]（顶部被状态栏顶掉 120px），而 Compose 内容仍按满屏高 2376px 布局、整体挂在 y=120 上** → 内容底边落到 120+2376 = 2496px，比屏幕底边（2376px）低 120px（=40dp）→ 贴屏幕底的弹层底边跟着下移，底栏正好被裁 40dp。实测吻合：`uiautomator` 里 "选择要缓存的剧集" bounds 被裁在 2376、格子 21 的 y=2156（正常应为 2036，差值正好 120px） | 先试 `window.setLayout(MATCH_PARENT)` + `fitInsetsTypes/fitInsetsSides = 0`：**实测被框架覆盖（`FIT_INSETS_CONTROLLED`），无效**。改为**运行时实测补偿**：模板里用 `onGloballyPositioned` 量 `hostView.getLocationOnScreen().y + hostView.height − 屏幕底边`，把差值作为 `bottom padding` 加在弹层容器的根 Box 上（窗口未被内缩时差值为 0、不产生多余边距；被内缩多少就抵消多少，跨机型/ROM 成立）。实机验证：24 集与 39 集季均完整显示，cell 21 的 y 从 2156 回到 2036 ✓ |

**顺带**：`versionName` 0.1.0 → 0.1.1 → 0.1.2 → **0.1.3**（`versionCode` 1 → 4），dist 只保留当前版本包并删旧包——此前新旧包同名，真机极易装到旧包误判"未修复"。

**验收（真机 0.1.3 实测通过）**：
1. 24 集季：5 行全部完整可见 + 底部「选择要缓存的剧集 / 缓存」整行完整可见。
2. 39 集季：8 行全在一屏内、底栏完整，无需滚动。
3. 短季：弹层贴内容紧凑显示。
4. 弹层底边 = 屏幕底边，底栏在导航栏之上（`navigationBarsPadding()` 生效）。

> 同文件 `HyperDialog`（居中卡片弹窗，行 ~99）仍为 `decorFitsSystemWindows` 默认值：居中弹窗只比真实中心略偏下（实测偏移 = 顶部 fit inset/2），无裁切，暂不动；若要严格居中，可套用同一套实测补偿。

## 13. 真机联调（adb）：UI 尺寸/裁切类问题的标准查法（2026-10-14 建立）

> 本轮"弹窗展示不全"前两轮靠截图目测推断，都猜错了层；连 USB 抓实测数值后一次定位。凡 UI 尺寸/裁切问题，直接走这套。

```powershell
$adb = "D:\dev_home\android-sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb shell wm size        # 注意 Override 行才是当前生效值（本机 1440x3168 物理 / 1080x2376 生效）
& $adb shell wm density     # 本机 Override 480 → density 3.0（1dp = 3px）
& $adb shell dumpsys package com.hypermux.mobile | Select-String "versionName"
```

```powershell
# 截屏：**不要**用 `adb exec-out screencap -p > x.png`（PowerShell 按文本编码会写坏 PNG），一律 shell + pull
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png "$env:TEMP\s.png"

# 窗口几何（裁切类问题第一现场）：mAttrs / Frames / fitTypes / Requested
#   ⚠ 该 dump 是 UTF-16 文本，用 Get-Content 读，别用 read 工具（会判为 binary）
& $adb shell dumpsys window windows > "$env:TEMP\w.txt"
Get-Content "$env:TEMP\w.txt" | Select-Object -Skip <行号> -First 40

# 节点实际坐标（Compose 语义树）：被窗口裁掉的节点，bounds 会被**裁到窗口底边**，这就是"被裁"的直接证据
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml "$env:TEMP\ui.xml"      # 单行 XML，用 [regex]::Matches 取 text/bounds
```

```powershell
# 装包：debug/release 签名不同，必须装与手机同签名的变体（-r 覆盖安装）
& $adb install -r D:\ai-projects\hyper-mux\hyper-mux-android\app\build\outputs\apk\release\app-release.apk

# 自动化走查（首页→详情→缓存弹层）：tap 坐标 = 截图显示坐标 × 缩放比（1080 / 截图宽）
& $adb shell am start -n com.hypermux.mobile/.ui.MainActivity
& $adb shell input tap 798 1874     # 首页「黄雀」卡片
& $adb shell input tap 962 1715     # 详情页「缓存」按钮
& $adb logcat -c; & $adb logcat -d -s <临时埋点Tag>   # 临时埋点看运行时真实数值
```

> 本轮定案依据：`dumpsys window windows` 里对话框窗口 `Frames: frame=[0,120][1080,2376]`（顶部被状态栏 fit 掉 120px）
> 而 Compose 内容按满屏 2376px 布局；`uiautomator` 里底栏节点 `bounds=[42,2352][829,2376]` 被裁在 2376、
> 格子 21 的 y=2156（正常应为 2036）——差值正好 120px，与"窗口被 fit 内缩、内容仍满屏"完全吻合。

## 14. 第九轮：状态栏配色统一 / 更新 toast 重放 / 登录页与弹窗"高度不一致"（2026-10-16）

**版本**：0.1.10（`versionCode` 11）；dist 只留 `hypermux-mobile-0.1.10-{debug,release}.apk`。

| # | 现象 | 真根因（实测） | 修复 |
|---|---|---|---|
| 1 | 顶栏（系统状态栏）黑底白字，App 内容却是浅色 | 两条独立原因叠加：① `NavHost` 只有 `windowInsetsPadding(statusBars)`、没有铺底色 → 状态栏那条安全区露出窗口 `windowBackground` `#0F1524`（深色），浅色主题下就是"黑底"；② `enableEdgeToEdge()` 默认 `auto` 策略**只读系统深色开关**，本机系统是深色 + App 设成浅色 → 给了亮色图标，于是"深色条 + 白字" 与白色内容割裂 | ① `AppNavigation` 给 NavHost 加 `.background(MaterialTheme.colorScheme.background)`（加在 insets 内缩之前，安全区也铺底色）；② `HyperMuxTheme` 里 `SideEffect` 把 `isAppearanceLightStatusBars/NavigationBars` 设为 `!dark`（**跟随 App 主题**，不跟随系统）；③ 登录页渐变改为从 `Color.Transparent` 起笔，页面顶边与状态栏底色严丝合缝（否则页面顶部 12% 主色淡染会在状态栏下留一条灰白色带） |
| 2 | 每次点「我的」都弹一次"已是最新版本" | 「检查更新」的一次性结果存在 **Activity 作用域** VM 的 `StateFlow`（`manualResult`）里，`collectAsStateWithLifecycle` 每次重新订阅都会把旧值**重放**一遍，`LaunchedEffect(updateManual)` 于是每次进页面都 toast | 新增 `UpdateViewModel.consumeManualResult()`（置 null），MeScreen 弹完 toast 立刻消费 |
| 3 | 登录页「使用自定义服务器」与「公开服务器」高度不一致 | ① 行内两段文案：左 `13.sp`、右 `12.5.sp`，且**只有右侧**带 `heightIn(min = 32.dp)`；② 「服务器配置」弹窗两行：`公开服务器` 行的尾随胶囊（12sp 文字 + 上下 4dp）比 14sp 标题行高，把该行撑高——实测两行外框 `242px` vs `220px`（density 3.0，即 360dp 宽屏，差 ≈7.3dp） | ① 两段文案统一 `12.5.sp` + 同一个 `heightIn(min = 32.dp)` 文案盒（真机实测两侧墨迹行 1774..1806 / 1773..1806，已完全对齐）；② `HyperChip` 加 `verticalPadding: Dp = 4.dp` 参数，弹窗里「默认」胶囊传 `0.dp`（胶囊文字仍比 14sp 标题小，任何 fontScale 下都不会再撑高那一行） → 两行外框实测 `218px` / `217px`（已等高） |

**`enableEdgeToEdge` 与主题的关系（本轮定案）**：状态栏/导航栏**底色**由 Compose 内容铺（NavHost 背景 = 页面底色），**图标明暗**由 `WindowInsetsControllerCompat.isAppearanceLight*` 控制，二者都必须跟 App 主题走；只依赖 `enableEdgeToEdge()` 的默认 `auto` 在"系统深色 + App 浅色"时会错。

## 15. 第十轮：连点两次"弹出两个窗口"（播放页 / 详情页重复入栈）（2026-10-16，真机复现+验证）

**版本**：0.1.11（`versionCode` 12，含 §14 的三处修复）；dist 只留 `hypermux-mobile-0.1.11-{debug,release}.apk`。

**现象**：剧集详情页短时间连点「从第 N 集继续」→ 弹出两个窗口（两个播放页），返回一次还落在第二个播放页上。

**真根因**：Compose 导航的**转场是异步的、导航状态是同步的**——`navigate()` 一调用，返回栈栈顶立刻变成目标页，但来源页在转场期间**仍在组合中、按钮仍可点**。于是连点两下 = 两次 `navigate(同一路由)` = 入栈两个目的地：
- 播放页：两个 `PlayerScreen`（各自 `DisposableEffect` 建/放一个 `ExoPlayer`）→「两个窗口」；
- 详情页同理：双击首页/分类/搜索卡片 → 两个详情页（返回一次还落在同一个详情页上），本轮一并修掉。

**修复**（统一入口集中在 `ui/navigation/NavComponents.kt`，替换各处裸 `navigate(Route...)`；与既有 `navigateTab` 同思路）：

| 入口 | 防护 | 调用点 |
|---|---|---|
| `navigatePlay(movieId, videoId)` | `launchSingleTop = true`（栈顶已是播放页则不再入栈） | 详情页主按钮/剧集格、首页「继续观看」、历史、下载页（4 处） |
| `navigateMovie(movieId)` | 栈顶已是**同一部片**详情页 → 直接忽略（点另一部片仍正常入栈） | 首页/历史 `onOpenMovie`、分类页、搜索页 |
| `navigateCategory(categoryId, name)` | 同一 `categoryId` → 忽略 | 首页「更多 ›」 |
| `navigateSearch()` / `navigateHistory()` / `navigateDownloads()` | 栈顶已是该页 → 忽略 | 首页搜索图标、首页右上角进历史、我的 →「我的下载」 |
| `navigateDownloadDetail(movieId)` | 同一部片 → 忽略 | 我的下载 → 单片详情（其中的播放按钮走 `navigatePlay`） |

> 播放页内切集是「同屏换 MediaItem」不经导航，`launchSingleTop` 不影响它（App 内不存在「播放页 → 播放页」的合法跳转任何第二次入栈都该被丢掉）。
> 其余入口都走同一个私有核心 `navigateOnce(pattern, route, sameArgs)`：**同一目的地 + 同一参数**才忽略；点的是另一个目标（另一部片 / 另一个分类）仍照常入栈，不会吞掉用户的后一次点击。
> 剩下两处裸 `navigate`（登录成功进首页、会话失效回登录页）是**清栈型**跳转，重复触发净效果仍是栈里一个目的地，无需防护。

**真机实测（1080×2376 / density 3.0，本轮 App 为深色主题）**：
- **负向对照**（改前/未加防护的影片卡片）：首页快速双击卡片 → 按一次返回**仍在详情页**（= 入栈两层；两次截图字节完全相同）→ 证明"连点确实会重复入栈"。
- 加防护后：详情页双击「从第 5 集继续」→ 返回一次即回详情页；首页双击卡片 → 返回一次即回首页。
- 顺带确认 §14：App 切深色主题后状态栏同样跟随（深色底 + 亮色图标），说明修复在浅/深两个方向都成立。
- 顺带补测 §14 第 2 条（本轮之前只改了代码、没条件验）：登录态下「我的 → 检查更新」弹出一次 toast，离开再进**不再重放** ✔

**服务器发布信息（2026-10-16 核对）**：`GET /api/app/update/check?code=0&platform=mobile` 当前返回 `versionCode 10 / 0.1.9`；本包 `versionCode 12 / 0.1.11` 严格更大 → 客户端启动被动检查（24h 节流）与手动检查都会提示更新。发布时服务器侧需要填的 `sha256` / `sizeBytes` 见本次交付说明。

**同类入口已全部收口**：`更多›`(分类)、搜索图标、首页右上角进历史、`我的下载` 行、下载详情页等 push 型入口本轮一并加了防护；以后新增二级页入口，按同样思路在 `NavComponents.kt` 里加一个 `navigateXxx` 薄封装即可（不要在业务页面里裸调 `navigate(Route...)`）。

---

## 16. 第十一轮：平板（Android Pad）自适应（2026-10-19，0.1.12 / versionCode 13）

**版本**：0.1.12（`versionCode` 13）。改动前的源码与 0.1.11 两个 APK 已备份到
`../_backup/pad-20261019/`（`src/` + `dist/` + `hypermux-android-src-0.1.11-before-pad.zip`）。

### 16.1 改前的平板实况（审计结论）

App 能装能跑、不会崩，但**完全没有自适应**：无 `layout-sw600dp`/`values-sw600dp`、无 `WindowSizeClass`、
7 处网格全是 `GridCells.Fixed`、弹窗/弹层 `usePlatformDefaultWidth = false` + `fillMaxWidth()`。
在 11"（1280×800dp 横屏）上表现为「手机 UI 被横向拉伸」，必现三处异常：

| 异常 | 根因位置 |
|---|---|
| 播放页标题/简介/选集被裁成一条缝 | 竖屏 `Column` 里 16:9 画面按宽铺满 → 1280dp 宽吃掉 720dp 高，窗口总高 800dp |
| 首页卡片变巨型、一屏只剩两行 | `HomeScreen` `GridCells.Fixed(2)` → 卡宽 ~380dp，2:3 海报高 ~570dp |
| 确认弹窗横贯整屏 | `HyperUi.HyperDialog` `usePlatformDefaultWidth=false` + `fillMaxWidth()` → ~1224dp 宽卡片 |

另外：`PlayerScreen` 的 `requestedOrientation` 方向锁在 sw≥600dp 且 targetSdk≥31 时被系统**忽略**，
原来那套「默认竖屏 / 全屏横屏」在大屏上是假契约（Android 16 起连豁免开关都会取消）。

### 16.2 方案：单一真源 `LayoutProfile`（不新增依赖）

新增 `ui/layout/LayoutProfile.kt`。档位按**窗口最小边**判定（等价 `sw` 语义），因此
**手机横过来最小边仍是 360dp → 仍 Compact**，旋转不会改变手机布局；平板横竖过来档位也不跳变。

| 档位 | 窗口最小边 | gridMaxWidth | formMaxWidth | sheetMaxWidth | heroMaxHeight | 首页 | 分类/搜索 | 选集 | 播放页选集 | 抽屉 |
|---|---|---|---|---|---|---|---|---|---|---|
| Compact | < 600dp | ∞ | ∞ | ∞ | ∞ | 2 | 3 | 5 | 6 | 4 |
| Medium | 600–840dp | 720 | 480 | 640 | 340 | 3 | 4 | 8 | 8 | 5 |
| Expanded | ≥ 840dp | 960 | 480 | 640 | 420 | 5 | 6 | 12 | 12 | 6 |

**Compact 档一列 = 0.1.11 手机实测值，一个都没动**；`∞` 表示不施加任何约束（`cappedFill` /
`heightCap` 直接退回原来的 `fillMaxWidth()` / 自由高度）。

挂载：`MainActivity` → `HyperMuxTheme { AdaptiveScope { AppNavigation() } }`，各屏读
`LocalLayoutProfile.current`。窗口被拖多宽就落进哪一档，不需要监听配置变化。

### 16.3 具体改动

| 点位 | 改法 |
|---|---|
| `AndroidManifest.xml` | `configChanges` 补 `screenLayout\|density\|uiMode`（TV 端早有）。分屏/桌面窗口切换会触发这两项，未声明就重建 Activity → 播放页 `DisposableEffect { controller.release() }` 把播放器整个释放 → 播放重启 |
| `HyperUi.HyperDialog` | 卡片列 `fillMaxWidth()` → `cappedFill(profile.formMaxWidth)` |
| `HyperUi.HyperBottomSheet` | 弹层列 → `cappedFill(profile.sheetMaxWidth)`；`bottomCompensation` 加 `coerceIn(0, 64dp)` 上限兜底（分屏下 `currentWindowMetrics` 取哪个窗口的底边存在 ROM 差异，防止补偿量算飞） |
| `LoginScreen` | 登录卡 → `cappedFill(profile.formMaxWidth)` |
| 首页 / 分类 / 搜索 / 详情 / 历史 / 我的下载 / 下载详情 | 列数走 profile；`contentPadding` 左右加 `profile.gridSideInset`（= (窗口宽 − gridMaxWidth)/2）做「封顶 + 居中」——**布局树不动**，`GridItemSpan(maxLineSpan)` 的轮播/横幅/头图自动跟着收窄 |
| `HomeBanner` / `DetailHero` | 16:9 大图加 `heightCap(profile.heroMaxHeight)`（932dp 宽按 16:9 会得到 524dp 高，12.9" 上吃掉 59% 高度） |
| `MovieDetailScreen.CacheSheet` | 弹层宽度已被封顶 → 改用 `columnsFor(实测宽度, minCell 56dp, gap 10dp, max 档位值)`。手机仍算出 **5** 列；平板弹层 640dp → 9 列、格子仍约 60dp |
| `PlayerScreen` 方向锁 | 只在 Compact 档施加 `requestedOrientation`；Medium/Expanded 一律 `UNSPECIFIED` |
| `PlayerScreen` 进入即沉浸 | `startImmersive = displayMetrics.widthPixels > heightPixels`：宽而矮的起始窗口（平板横屏/盒子）直接进全屏那套（`fillMaxSize` 画面 + 右侧抽屉 + `LandscapeControls`，本来就是按宽而矮设计的）；手机竖屏仍 `false`，行为不变 |
| `PlayerScreen` 非沉浸画面 | 窄而高窗口照旧 `fillMaxWidth().aspectRatio(16/9)`；宽而矮窗口（剩余高 < 260dp）改为按高度收敛（`height(winH × 0.55)`、左右留黑），把高度还给信息区与选集 |
| `PlayerScreen.EpisodePicker` | `Fixed(6)` → `columnsFor(实测宽, minCell 46dp, gap 8dp, max 档位值)`。手机仍 **6** 列；1280dp 宽按实测会排 23 列，被档位上限 12 收住 |
| `PlayerScreen` 全屏抽屉 | 宽度上限 320 → 320 / 380 / 440dp；列数 4 → 4 / 5 / 6（格子宽度与手机上今天 ~68dp 基本一致） |
| `MeScreen` | 设置内容 `cappedFill(formMaxWidth)` + 外层 `CenterHorizontally`，不跟着窗口拉长 |
| 骨架屏 | 6 处 `SkeletonPosterGrid(columns = 2)` 改随档位；顺带修掉「分类/搜索骨架 2 列、真网格 3 列」的既有不一致 |

### 16.3b 平板实测补修：首页「继续观看」横幅图片比例（2026-10-19 平板真机三轮反馈）

**现象**：平板上「继续观看」卡里的剧照看起来被放大 / 比例不对（只剩画面中间一条窄带）。

**根因**：`HomeScreen.ContinueBanner` 是**固定 `height(150.dp)` + `fillMaxWidth()`**。
150dp 这个设计值只在手机上成立：332×150 ≈ **2.2:1**，16:9 剧照按 `TopCenter` 裁掉约 20%。
平板上横幅内容宽 ~932dp，锁 150dp 就变成 **6.2:1** → 16:9 剧照被横切掉约 3/4。
（`ContentScale.Crop` 本身不会拉伸变形，问题纯粹是裁切比例过极端。）

**最终采用**：高度随宽度增长、比例收在 **3.2:1** 以内，保留 150dp 下限。

```kotlin
val bannerWidth  = profile.windowWidth - (Spacing.page + profile.gridSideInset) * 2
val bannerHeight = (bannerWidth / 3.2f).coerceAtLeast(150.dp)
```

| 场景 | 横幅内容宽 | 高度 | 比例 | 剧照保留 |
|---|---|---|---|---|
| 手机 360dp | 332dp | **150dp（下限生效，逐位不变）** | 2.2:1 | ~80% |
| 平板 Medium | ~572–692dp | ~179–216dp | 3.2:1 | ~56% |
| 平板 Expanded | ~932dp | ~291dp | 3.2:1 | ~56% |

宽度 <480dp 时下限 150dp 必然生效 → 所有手机（含 320dp 小屏）逐位不变。

**已试过并回退的方案（别再重复踩）**：2.6:1 + 「底层同图 `Crop`+`blur(18.dp)`+压暗、上层 `ContentScale.Inside` 完整显示 16:9」。
思路本身成立（盒子比 16:9 宽得多时，靠调高度无解：要么裁切、要么卡片过高），但**部分 ROM 上
`Modifier.blur` 不生效（API<31 为 no-op，个别设备即使 API 31+ 也不渲染）**，两侧模糊带直接变成两块暗带，
观感比裁切更差 → 平板真机反馈后回退。若将来还要走这条路，必须给 blur 做可用性兜底（实测渲染结果，
而不是只判 `Build.VERSION`）。

**旋钮**：`3.2f` 往小调 = 卡片更高、裁切更少（2.6f ≈ 保留 68%）；往大调 = 卡片更扁、裁切更多。

**同类点位已排查**：首页轮播（`HomeBanner`）与详情头图（`DetailHero`）走 `aspectRatio(16/9)` +
`heroMaxHeight` 340/420dp 限高，932dp 宽时盒子 2.22:1 → 保住约 80% 画面，**不存在同类问题**；
`MovieDetailScreen:356`（160dp 纯文字空态）、`MyDownloadsScreen:306`（44×60 小海报）、
`PlayerScreen:1879`（84dp 控制条）均无全宽大图。全 App 只有这一处。

### 16.4 手机零回归的守卫（可执行断言）

新增 `app/src/test/.../LayoutProfileTest.kt`（本轮同时补 `testImplementation(libs.junit)`，此前项目无测试源集）：

- `compactReproducesPhoneBaseline`：360dp 必须返回 `∞ / ∞ / ∞ / 0 内缩 / 2 / 3 / 5 / 6 / 4`
- `phonesStayCompact`：320dp 小屏、以及手机横屏（窗口 740×360dp）都必须是 Compact
- `cacheSheetKeepsFiveColumnsOnPhone` / `playerEpisodePickerKeepsSixColumnsOnPhone`：两处 `columnsFor` 在手机上仍算出 5 / 6 列

`gradle testDebugUnitTest` → 6 tests, 0 failures ✔

### 16.5 平板真机验收清单（待测）

设备：11" 平板（sw600dp+，横 + 竖）、12.9" 平板、7" 平板（sw<600，应走 Compact）、分屏 50/50、字体 1.3 / 2.0、深浅主题切换。

| # | 检查项 | 期望 |
|---|---|---|
| 1 | 首页 | 3–5 列、卡片 ~180–220dp 宽、内容居中且左右留白对称、最后一行不被裁 |
| 2 | 首页轮播 / 继续观看 | 高度 ≤ 340（Medium）/ 420（Expanded）dp，不吃掉半屏 |
| 3 | 分类 / 搜索 | 4–6 列，搜索胶囊随 `maxLineSpan` 一起收窄 |
| 4 | 详情页 | 头图限高、选集 8–12 列、`缓存(N)` 弹层宽 ≤ 640dp 且底栏完整可见 |
| 5 | 播放页（平板横屏进入） | **直接是沉浸全屏**；右侧抽屉宽 380/440dp、5–6 列 |
| 6 | 播放页（退出全屏后） | 画面按高度收敛、标题/简介/选集**完整可见**（改前这里只剩 ~80dp） |
| 7 | 播放页（平板竖屏） | 仍按 16:9 铺满宽度，与信息区共存（不应被限高） |
| 8 | 分屏 50/50 | 播放中拖动分隔条改变窗口尺寸 → **播放不重启、不黑屏**（靠 §16.3 manifest 那一项） |
| 9 | 分屏下的缓存弹层 | 底栏不被顶起、也不被裁（`bottomCompensation` 上限 64dp 兜底） |
| 10 | 弹窗（删除确认 / 服务器配置 / 更新） | 宽 ≤ 480dp 且居中，不横贯整屏 |
| 11 | 登录页 | 卡片 ≤ 480dp 居中 |
| 12 | 我的 / 我的下载 | 内容 ≤ 480 / 960dp 居中，行卡不拉长 |
| 13 | 字体 1.3 / 2.0 | 底栏 56dp、`InfoRow` 48dp、`EpisodeCell`、搜索框 48dp 内文字不裁剪 |
| 14 | 深浅主题切换 | 不重建 Activity（`uiMode` 已声明） |
| 15 | **手机回归** | 360dp 手机逐屏与 0.1.11 对比：首页 2 列、分类/搜索 3 列、详情 5 列、播放页 6 列、弹窗铺满、播放页默认竖屏 —— 必须完全一致 |

> 尺寸类问题沿用 §13 的 `uiautomator` bounds 查法；平板上重点比对「列数 × 格子宽度」与 §16.2 表格的期望值。


