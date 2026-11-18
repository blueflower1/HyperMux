# 45 · 手机端 App 详细设计（Android 原生）

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v1.0 · 对应里程碑：**M2P（手机端 MVP）**
> 接口事实源：[30-api-contract.md](./30-api-contract.md) §3（`/api/app/*` 10 个接口，手机端**全量复用、零改动**）
> 范围说明：本轮**只做安卓手机端**（触屏交互）；TV 端（`app/`）按既定约定继续暂缓。手机端与 web 端（`hyper-mux-web`）功能对齐，与 TV 端共用同一套后端、同一套数据模型。

---

## 0. 为什么手机端先行

| 理由 | 说明 |
|---|---|
| 交互验收成本低 | 触屏即点即用，无需遥控器/焦点体系；TV 端 Leanback 焦点导航验收成本高，继续暂缓 |
| 契约已就绪 | `/api/app/*` 10 接口已由 web 端验证可用；手机端是第三个消费者，不改后端 |
| 全链路试金石 | "登录→浏览→搜索→播放→断点→历史"在手机上先跑通，TV 端后续直接复用数据层与播放器状态机 |
| 自托管刚需 | 手机装机后需手动指向服务器地址（TV 盒子在局域网内通常固定）；服务器地址运行时可配是手机端特有 MVP 项 |

**与 TV 端的关系**：TV 端详细设计见 [40-app-detailed.md](./40-app-detailed.md)，其播放器状态机（§7）、断点逻辑（§8）、错误分类（§7.5）在手机端**同语义平移**，仅交互层（手势替代遥控器键）不同。

---

## 1. 技术栈

| 项 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言/构建 | Kotlin + Gradle KTS | Kotlin 2.0 / AGP 8.7 | 单 module |
| SDK | compileSdk 35 / minSdk 24 / targetSdk 35 | — | Media3 要求 minSdk 24 |
| UI | **Jetpack Compose + Material 3** | Compose BOM 2025.x | 默认项；XML+View 为备选（决策 D1） |
| 播放 | **Media3 ExoPlayer + media3-ui-compose** | 1.7.x | MP4/MKV/Matroska/HLS 核心库自带 |
| 网络 | Retrofit2 + OkHttp + converter-gson | 2.11 / 4.12 | Gson 字段名与契约 camelCase 一致，零映射 |
| 异步 | kotlinx-coroutines + Flow | 1.9.x | 全协程，无 RxJava |
| 图片 | Coil 3 | 3.x | OkHttp loader，内存+磁盘双缓存（磁盘 100MB） |
| 导航 | Navigation Compose | 2.8.x | 单 Activity + 路由守卫 |
| 持久化 | **DataStore Preferences** | 1.1.x | token / 服务器地址 / 主题 / 搜索历史 |
| DI | Hilt | 2.52 | 默认项（决策 D2） |
| 包名/目录 | `com.hypermux.mobile`，工程目录 `hyper-mux-android/`（兄弟工作区，与 `hyper-mux` / `hyper-mux-web` 平级） | versionName 0.1.0 | 与 TV 端 `app/`（`com.hypermux.tv`）区分 |

### 1.1 build.gradle.kts 要点

```kotlin
android {
  namespace = "com.hypermux.mobile"; compileSdk = 35
  defaultConfig {
    minSdk = 24; targetSdk = 35
    applicationId = "com.hypermux.mobile"; versionCode = 1; versionName = "0.1.0"
    buildConfigField("String", "API_BASE",
      "\"${(rootProject.findProperty("hypermuxApiBase") ?: "")}\"")  // 空=首次启动引导填
  }
  buildFeatures { compose = true }
  // debug 允许明文 http（自托管测试环境）；release 强制 https（见 §9）
}
dependencies {
  implementation("androidx.media3:media3-exoplayer:1.7.1")
  implementation("androidx.media3:media3-ui-compose:1.7.1")
  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-gson:2.11.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("io.coil-kt:coil-compose:3.6.0")
  implementation("androidx.navigation:navigation-compose:2.8.x")
  implementation("androidx.datastore:datastore-preferences:1.1.x")
  implementation("com.google.dagger:hilt-android:2.52")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.x")
}
```

### 1.2 AndroidManifest 要点

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<application android:label="HyperMux" android:icon="@mipmap/ic_launcher">
  <activity android:name=".ui.MainActivity"
      android:exported="true" android:launchMode="singleTask"
      android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden">
    <intent-filter><action android:name="android.intent.action.MAIN"/>
                   <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
  </activity>
  <!-- 播放页：独立全屏 activity 亦可；MVP 用 Compose 页面 + 横屏方向锁定（决策 D6） -->
</application>
```

无 `LEANBACK` / `HOME` / `BOOT_COMPLETED` 等 feature 声明（手机端普通应用，非 kiosk）。

---

## 2. 工程结构

```
hyper-mux-android/          # 兄弟工作区（安卓手机端，本轮实施）
├─ settings.gradle.kts / gradle.properties / local.properties（hypermuxApiBase 可覆盖）
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/hypermux/mobile/
      │  ├─ HyperMuxApp.kt              # Application：Hilt/DataStore/Coil 初始化
      │  ├─ config/AppConfig.kt         # 阈值常量（§3.1）
      │  ├─ data/
      │  │  ├─ api/
      │  │  │  ├─ ApiService.kt         # Retrofit 接口 = 契约 §3 的 1:1 映射（§4.1）
      │  │  │  ├─ AuthInterceptor.kt    # 注入 Bearer；401 → 会话失效事件
      │  │  │  ├─ ApiResult.kt          # sealed: Ok<T> / Err(code, msg, httpStatus)
      │  │  │  └── NetworkMonitor.kt    # ConnectivityManager → 离线黄条 StateFlow
      │  │  ├─ model/                   # dto：与契约字段同名（SessionVo, HomeVo, CategoryItemVo,
      │  │  │                            #   MovieCardVo, MovieDetailVo, SeasonVo, PlayUrlVo, HistoryVo...）
      │  │  ├─ repo/
      │  │  │  ├─ AuthRepo.kt           # 登录/登出/token 持久化（DataStore key: session）
      │  │  │  ├─ ContentRepo.kt        # home/分类/搜索/详情/剧集 + 缓存降级
      │  │  │  ├─ PlaybackRepo.kt       # play-url/progress/finish
      │  │  │  └── SettingsRepo.kt      # 服务器地址/主题/搜索历史
      │  │  └─ cache/CacheStore.kt      # home/列表第 1 页的 JSON 缓存（SP 兼容层，key 含 userId）
      │  ├─ di/AppModule.kt             # Retrofit(动态 baseUrl)/OkHttp/Coil/Repo
      │  ├─ ui/
      │  │  ├─ navigation/              # NavGraph + 路由守卫（未登录→Login；已登录→Home）
      │  │  ├─ theme/                   # Material3 主题（深色默认/浅色/跟随系统，对齐 web）
      │  │  ├─ login/LoginScreen.kt     # §5.1
      │  │  ├─ home/HomeScreen.kt       # §5.2（分类 rail + 继续观看 + 网格）
      │  │  ├─ category/CategoryScreen.kt  # §5.3
      │  │  ├─ search/SearchScreen.kt   # §5.4（全局搜索 + 搜索历史）
      │  │  ├─ movie/MovieDetailScreen.kt  # §5.5（详情 + 内嵌剧集）
      │  │  ├─ history/HistoryScreen.kt # §5.7
      │  │  ├─ me/MeScreen.kt           # §5.8
      │  │  └─ common/                  # ErrorState / OfflineBanner / CoverImage(首字占位) / Toast
      │  ├─ player/
      │  │  ├─ PlayerScreen.kt          # §6 全屏播放页（手势层）
      │  │  ├─ PlayerController.kt      # 播放状态机 + 续播 + 自动连播 + 错误分类
      │  │  └─ PlayerReportLoop.kt      # 10s 节流上报协程
      │  └─ util/ (TimeFmt.kt / Regex.kt)
      └─ res/: 图标/启动图/字符串/主题色
```

---

## 3. 全局规范

### 3.1 阈值常量（`AppConfig`，与 TV 端 §3.1 对齐）

| 常量 | 值 | 说明 |
|---|---|---|
| PROGRESS_REPORT_INTERVAL | 10s | 播放中断点上报间隔 |
| PROGRESS_MIN_POSITION | 3s | position<3s 不上报（对齐 web 端 `reportNow`） |
| RESUME_THRESHOLD | 30s | 首次进入：positionMs>30s 且 < duration-30s 才视为续播 |
| NEAR_END_BADGE | 0.95 | 客户端"已看完"角标展示阈值（服务端 -30s 自动 finished） |
| TOKEN_EXPIRY_BUFFER | 300s | 本地判过期提前量（401 兜底） |
| HOME_CACHE_MAX | 64KB / 列表 32KB | 缓存软上限，超了不存 |
| OFFLINE_RETRY | 2 次 × 1.5s | 网络/5xx 自动重试 |
| PLAYER_AUTO_NEXT_DELAY | 1.5s | 连播延迟（"即将播放下一集"角标，可取消） |
| AUTO_NEXT | true | 一集播完自动下一集 |
| PLAY_URL_TIMEOUT | 30s | play-url 接口超时（OpenList 解析可能偏慢，对齐 web 端） |
| SEARCH_DEBOUNCE | 300ms | 搜索输入防抖（对齐 web 端） |

### 3.2 主题与色板

- 深色默认（对齐 web 端深色），浅色/跟随系统可切；色板沿用 TV 端：底 `#0F1524`，卡 `#1A2236`，文字 `#F3F6FF`/`#9FB0D6`(弱)，品牌 `#F5A524`，成功 `#22C55E`，错误 `#EF4444`。
- 字号：标题 22sp / 卡片标题 15sp / 正文 14sp / 辅助 12sp（手机观感，非 TV 的大字体系）。

### 3.3 导航与返回

| 页面 | 返回行为 |
|---|---|
| 登录页 | 已登录则直接进首页（守卫拦截）；无上级 |
| 首页 | 双击返回键退出（Snackbar 提示"再按一次退出"） |
| 分类/搜索/详情/历史 | 回首页（底部导航）；详情→播放页为 push，返回回详情（滚动位保留） |
| 播放页 | 先即时上报断点 → 退出全屏回详情（从"继续观看"直达播放时回首页） |

底部导航四页：**首页 / 历史 / 我的**（+顶栏搜索入口）。Fragment 手动栈不适用——Navigation Compose 托管；401 触发 `popUpTo(login) { inclusive }` 清栈。

---

## 4. 数据层

### 4.1 ApiService（= 契约 §3 全量 10 接口，1:1 映射）

```kotlin
interface ApiService {
  @POST("api/app/auth/login")        suspend fun login(@Body b: LoginReq): ApiResult<SessionVo>
  @GET("api/app/home")               suspend fun home(): ApiResult<HomeVo>
  @GET("api/app/category/{id}/movies") suspend fun categoryMovies(@Path("id") categoryId: Long,
                                        @Query("page") page: Int, @Query("size") size: Int,
                                        @Query("keyword") kw: String?): ApiResult<PageVo<MovieCardVo>>
  @GET("api/app/search")             suspend fun search(@Query("keyword") kw: String,
                                        @Query("page") page: Int, @Query("size") size: Int): ApiResult<PageVo<MovieCardVo>>
  @GET("api/app/movie/{id}")         suspend fun movie(@Path("id") id: Long): ApiResult<MovieDetailVo>
  @GET("api/app/movie/{id}/videos")  suspend fun videos(@Path("id") id: Long): ApiResult<SeasonsVo>
  @POST("api/app/play-url")          suspend fun playUrl(@Body b: PlayUrlReq): ApiResult<PlayUrlVo>
  @GET("api/app/history")            suspend fun history(@Query("limit") limit: Int): ApiResult<List<HistoryVo>>
  @PUT("api/app/progress")           suspend fun progress(@Body b: ProgressReq): ApiResult<ProgressVo>
  @POST("api/app/progress/finish")   suspend fun finish(@Body b: FinishReq): ApiResult<FinishVo>
}
```

> 注意契约口径：`videos[].duration` 单位是**秒**，`progress.*Ms` 是**毫秒**（展示换算勿混）；`PlayUrlVo.headers` 可空对象，ExoPlayer `MediaItem.setHeaders()` 必须合并。

### 4.2 认证链路

- `AuthInterceptor`：每请求注入 `Bearer`；响应 **401**（登录请求除外）→ `AuthRepo.invalidate()`（清 DataStore）→ 事件总线上抛 → 导航清栈回登录页 + 全屏提示"登录已失效，请重新登录"。
- `AuthRepo`：DataStore `session` = `{token, expiresAt, userId, username, nickName, serverUrl}`；`isLoggedIn() = token!=null && now < expiresAt - 300s`。
- token 7 天有效，无 refresh（与后端一致）；限频 429 在登录页原样提示。

### 4.3 服务器地址（手机端特有）

- **双模式**（0.1.7 起）：`serverMode` = `PUBLIC | CUSTOM`（DataStore `server_mode`，与 `server_url` 一起持久化）。「公开服务器」档位**仅当构建期注入预置地址时提供**（`-PhypermuxPublicServerUrl=http://host:port` → `BuildConfig.PRESET_SERVER_URL` → `AppConfig.PRESET_SERVER_URL`，代码层持有，**UI 全程不出现该地址**，登录页/我的页均显示节点名"公开服务器"）；未注入时不提供该档位，默认自定义模式、用户必须自行填写服务器地址。
- **自定义服务器**：用户选择后登录页底部小字入口 → "服务器配置"弹窗（公开/自定义单选 + 地址输入 + 测试连接，草稿保存后生效）。老版本升级自动迁移：存过地址 → CUSTOM 并预填原地址；全新安装 → PUBLIC。
- **探测实现**（弹窗内"测试连接"）：`GET /api/app/home`（带 `X-HyperMux-Probe` 头，App 侧拦截器据此跳过 401 会话失效副作用）；**预期 401（缺少登录凭证）即判定可达**——因 `actuator/health` 部署在管理端口 9090、不对外（见 [70-deploy-ops.md](./70-deploy-ops.md)）。探测走 `SettingsRepo.probingUrl` 临时地址，**不写持久化**（取消即无副作用）。地址缺 scheme 时自动补 `http://`。
- 登录成功 / 弹窗保存时 `applyServer(mode, url)` 一键落盘生效地址 + 模式；之后请求以 DataStore 中地址为 baseUrl（Retrofit 通过 `@Url` 或动态 OkHttp baseUrl 切换——MVP 用 **OkHttp Interceptor 重写 host 前缀**实现热切换，免重建 Retrofit）。
- 切换服务器入口在登录页底部（"我的"页只读展示：公开模式显示"公开服务器"）；改后需重新登录（token 与服务器绑定）。
- 构建期预填自定义地址：`-PhypermuxApiBase=http://host:port`（仅 CUSTOM 档预填；公开档恒为代码内置值）。

### 4.4 缓存降级（ContentRepo + CacheStore）

| 数据 | 缓存 key | 写入 | 读取时机 |
|---|---|---|---|
| home | `home:{userId}:{serverHost}` | 每次成功后（限 64KB） | **API 失败**（网络/5xx/超时）→ 读缓存渲染 + 顶部细条 `离线数据 · {更新时间}` |
| 分类列表第 1 页 | `list:{categoryId}:p1` | 成功后 | 同上（仅离线兜底） |
| 封面 | Coil disk 100MB | 自动 | 离线卡牌可展示 |

- 认证失败（401）不走缓存（直接登录页）；完全无缓存 + 离线 → `ErrorStateView`（"网络未连接" + 重试按钮），**不白屏**。
- 断点真实性诉求场景（continueWatching）接受"离线时不更新"。

### 4.5 App 自更新（契约 §3.11）

检查/下载跟随用户配置的服务器（`ServerUrlInterceptor` 重写占位 host，与内容请求同源）；检查端点匿名可用（登录前也能查）。

| 组成 | 职责 |
|---|---|
| `UpdateInfo` / `ApiService.checkUpdate` | `GET /api/app/update/check?code=&platform=mobile`；204 = 无更新（含该平台未发布） |
| `UpdateViewModel`（Activity 作用域，`AppNavigation` 层持有） | 弹窗状态机；`autoCheckIfDue`（登录后 3s 空闲检查，24h 节流 `last_update_check_at`，失败静默不记录）；`checkNow`（手动必查，toast 结果）；「稍后」记忆 `dismissed_update_code`（该版本不主动弹，手动检查仍弹，更高版本仍弹） |
| `UpdateDownloadService`（前台服务，`dataSync`） | 「立即更新」触发；复用 App OkHttp（readTimeout 放宽 180s）；边下边流式 SHA-256（64KB 块），**校验通过才** `rename` 落盘 `files/updates/hypermux-update-{code}.apk`（先 `.tmp.apk`，失败不留坏包）；进度通知（`hypermux_updates` 渠道）；进程级状态写 `UpdateDownloadState`（Service↔UI 桥） |
| 安装 | `FileProvider`（`${applicationId}.update`，仅暴露 `files/updates/`）+ `REQUEST_INSTALL_PACKAGES`；未授权先跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 并提示，授权后再发 `ACTION_VIEW` |
| `mandatory`（默认 false） | 强制更新：弹窗无「稍后」、蒙层/返回关不掉、未安装前不可用主界面 |

实现要点（真机踩坑结论）：
- 服务 `onStartCommand` **前两行即** `startForeground`（带 `dataSync` 类型）——manifest 声明了 FGS 类型后，Android 14+ 不带类型的 `startForeground` 抛 `MissingForegroundServiceTypeException`；ROM 冻结主线程时 5s 未 startForeground 系统直接杀进程（FATAL），致命窗口必须压到最小。
- 下载防重入用**服务实例级标志**，不能读共享 `down` 状态——VM 在 `startForegroundService` 前已置 `Loading`，读共享态会"永远在跑"自停（线上事故根因）。
- VM 侧 6s 看门狗：服务未真正活起来 → `down=Failed`（可重试），而不是 UI 卡死或整个 App 崩溃。
- 进程重启后本地已有同 code 已校验包 → 直接可安装态不重下。
- 弹窗在 `AppNavigation` 窗口层挂载（Tab 内 `hiltViewModel()` 各自一份，共享 VM 必须显式以 Activity 为 owner）。

---

## 5. 页面规格

### 5.1 登录页 `/login`

- 布局：品牌区（HyperMux 字标 + 标语）→ 用户名框 → 密码框（IME 密码点）→ 登录按钮（loading 态防连点）→ 分隔线 → 服务器行（脚注级：状态圆点 + 节点名 + 右侧小字"使用自定义服务器/更改服务器"+"›"，点击进入"服务器配置"弹窗）。**公开地址不出现在 UI**。
- 输入法：标准 IME（`textPassword` / `text`）；用户名框 `imeOptions=Next`，密码框 `Done` 提交。
- 本地前置校验（对齐后端 `LoginReq`，不满足不上送）：username `^[a-zA-Z0-9_]{3,32}$`；**password 仅校验长度 6~20，不校验字符集**（字符集 `[a-zA-Z0-9_.]{6,20}` 只用于后台"建用户/重置密码"，登录接口无此约束——例如初始管理员密码 `Admin@1234` 含 `@`）。
- 服务端错误 → 居中 Snackbar/文本（401"用户名或密码错误" / 403"账号已停用，请联系管理员" / 429 原样"尝试太频繁"），保留已输内容。
- 成功 → 存 token → 首页。

### 5.2 首页 `/home`

数据来源：`GET /api/app/home`（一次全拿）。布局（对齐 web 端信息架构）：

```
┌ 顶栏：HyperMux 字标 ……… 🔍 搜索  [离线黄条(条件渲染)] ┐
│ 继续观看（横滑大卡：封面+片名+S1·第5集+进度条）        │ ← continueWatching ≤10 条，空则不渲染
│ 分类 rail（横滑 chips：全部 + 电视剧/电影/综艺…）       │ ← categories，movieCount=0 不下发
│ 影片网格（LazyVerticalGrid 3 列，封面 2:3 + 片名 + ⭐评分 + 续播角标）│
└ 底部导航：[首页] [历史] [我的] ───────────────────────┘
```

- 分类 rail 选中即切换展示该分类影片（本地过滤已拉取数据 + 翻页拉取该分类列表）；"全部"= 各分类聚合（MVP 简化：全部 = 第一个分类起，逐分类分页拼接；**或** 直接展示 continueWatching + 分类入口两档——见决策 D4，默认后者）。
- 下拉刷新（SwipeToRefresh）；进入页不强制重拉（与 TV 端一致：续播位由接口合并）。
- 卡片：封面(2:3) + 片名 + 评分 + 年份；`progress≠null` → 底部进度条 + `第N集` 角标。
- 空态：`该分类暂无影片`；加载失败：局部 ErrorState + 重试。

### 5.3 分类列表页 `/category/{id}`

- 顶栏标题=分类名；网格 3 列分页（size=30，底部 sentinel 触发 loadMore，`total>page*size`）。
- 顶栏右侧搜索图标 → 该分类内搜索（`keyword` 参数，与 web 端 CategoryView 对齐）。
- 断点角标数据来自 `MovieCardVo.progress`（服务端已合并，无 N+1）。

### 5.4 搜索页 `/search`

- 全局搜索 `GET /api/app/search`（片名/原名模糊，仅 PUBLISHED 且分类启用）——**手机端直接做全局搜索**（TV 端 M2 只做分类内搜索，手机端体验更好）。
- 输入框自动聚焦 + 防抖 300ms；搜索历史本地持久化（DataStore，最近 20 条，点击复用，× 清空）。
- 结果网格同分类列表（分页 30/页）；空态 `没有找到「{kw}」相关影片`。

### 5.5 影片详情页 `/movie/{id}`（详情 + 内嵌剧集）

手机端**不单独做剧集页**（TV 端有 EpisodeListFragment）：详情页纵向滚动内嵌剧集区，减少跳转。

```
┌ 海报头图（封面居中 2:3 + 顶部渐变至背景色）           ┐
│ 标题(22sp)  原名(弱, 可无)                            │
│ ★9.4  2023  中国  普通话                              │
│ [标签 chips：类型/地区/自定义]                         │
│ 简介（默认 3 行截断 + "展开"）                         │
│ [主按钮：▶ 从第 6 集继续]（无断点：▶ 播放第 1 集）      │
├ 剧集 ──────────────────────────────────────────────┤
│ 季 Tab（S1 S2 …，仅一季时隐藏）                       │
│ 集网格（纵向 3 列：集号 + 标题一行截断 + 进度条；finished 灰色 ✓）│
└──────────────────────────────────────────────────┘
```

- `progress!=null` → 主按钮"从第 {episodeNo} 集继续"；否则按 seasons 第一个视频"播放第 1 集"。
- 点击任意集 → 播放页（携带 movieId + videoId + resumeMs；seasons 数据已随详情页持有，播放器直接用于连播计算）。
- 数据：`GET /api/app/movie/{id}` + `GET /api/app/movie/{id}/videos` 并发请求。

### 5.6 播放页 `/play?videoId=...&movieId=...`

见 §6。全屏横屏（进入自动横屏 + 方向锁定，退出恢复）；竖屏下为带控制条的半屏播放亦可（MVP：**强制横屏**，简单稳定——决策 D6）。

**屏幕常亮**：播放页在位期间给窗口加 `FLAG_KEEP_SCREEN_ON`（与 TV 端 §9.2 同一做法：进播放页 `addFlags` / 离开 `clearFlags`），播放中不会被系统超时锁屏。该 flag 只在窗口可见且有焦点时生效，所以**用户按电源键照样能锁屏**，退到后台/离开播放页后系统超时与屏保策略照常恢复，不需要任何权限（media3 的 `PlayerView` 不会替我们加，1.7.1 里没有任何相关代码）。

### 5.7 历史页 `/history`

- 数据：`GET /api/app/history?limit=50`（updateTime 降序）。
- 列表项：封面(小) + 片名 + `S{season} · 第{ep}集` + 进度条 + 剩余时长/更新时间；finished 显示 ✓。
- 操作：**点击=继续看**（断点播放）；右侧菜单：**标记看完**（`action=finish`）/ **删除**（`action=remove`，确认弹层"从历史中移除"）——对齐 web 端 HistoryView 三操作。
- 空态：`还没有播放记录`；下拉刷新。

### 5.8 我的页 `/me`

对齐 web 端 MeView + 手机端特有项：

| 区块 | 内容 |
|---|---|
| 用户卡片 | 首字头像 + 昵称 + `@username · 普通用户` |
| 外观 | 主题切换：深色 / 浅色 / 跟随系统（默认深色） |
| 服务器 | 当前节点只读展示（公开模式显示"公开服务器"、自定义显示地址）；修改走登录页"服务器配置"弹窗 |
| 应用更新 | 当前版本（只读）+「检查更新」行（右侧文案：未检查过 / 检查中… / 已是最新 / vX.Y.Z 可更新）；发现新版弹更新弹窗（§4.5） |
| 登录信息 | 有效期提示（剩余天数，对齐 web 端） |
| 存储 | 清除封面缓存（Coil disk clear，显示释放大小） |
| 关于 | 版本号 0.1.0 |
| 操作 | 退出登录（确认弹层 → 清 token → 登录页） |

---

## 6. 播放器设计（PlayerController 状态机）

### 6.1 ExoPlayer 装配

```kotlin
ExoPlayer.Builder(ctx)
  .setMediaSourceFactory(DefaultMediaSourceFactory())  // MKV/Matroska + MP4 + HLS 全含
  .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
  .setMinBufferMs(15_000).setMaxBufferMs(50_000)
  .setHandleAudioBecomingNoisy(true)
  .build()
// media3-ui-compose 的 PlayerView 承载；controllerAutoShow=5s，进度条可拖
// 若 OpenList 校验 UA/Referer：后端 play-url 已下发 headers，MediaItem.setHeaders() 合并
```

### 6.2 播放链路（严格顺序）

```
进入(videoId, resumeMs)
  → PlayUrlReq（30s 超时，loading 圈）→ {url, headers}
  → MediaItem.Builder().setUri(url).setHeaders(headers).build()
  → player.setMediaItem + prepare()
  → 监听 STATE_READY：resumeMs 有效（§3.1 阈值）→ seekTo(resumeMs)
     toast 一次"从 01:22 继续播放"（仅首次续播时）
```

### 6.3 状态机（与 TV 端 §7.3 同语义）

```
IDLE → PREPARING → PLAYING ⇄ PAUSED → ENDED ──(1.5s)──▶ 下一集（重走 §6.2, resumeMs=0）
  │      │           │                                   （无下一集 → 回详情页 + finished 上报）
  ├──→ ERROR ──(分类 §6.5)── 自动重试×1(4s) / 手动 / 不换集
断点上报：PLAYING 每 10s(协程 tick) + PAUSE 时 + 退后台/离开页时 + ENDED 时(置满)
onStop → 取消连播 + playWhenReady=false + 上报（PlayerScreen 的 LifecycleEventObserver 闸门）
onDestroy → release()；不在前台时出现的"起播意图"（迟到的 play-url / 自动重试 / 连播换集）一律被监听器按回 false
```

### 6.4 手势层（手机端替代遥控器键，对齐 TV 端 §6 键位语义）

| 手势 | 行为 |
|---|---|
| 单击 | 显示/隐藏控制层（5s 自动隐藏） |
| 双击 | 播放/暂停切换 |
| 左右滑动（控制层隐藏时） | seek ∓10s（带预览 Toast `+10s`）；长按连跳 |
| 上下滑动（左半屏/右半屏） | 亮度 / 音量（系统手势冲突时降级为控制层按钮） |
| 长按屏幕 | 2 倍速快进，松开还原（对齐 web 端"按住快放"） |
| 硬件菜单键 | 同双击 |

### 6.5 错误分类（大文案，替代白屏/崩溃，与 TV 端 §7.5 一致）

| 情形（PlaybackException） | 文案 | 自动行为 |
|---|---|---|
| IOException / 408 超时 | 网络或资源速度较慢 | 4s 后自动重试 ×1（同集同断点） |
| HTTP 4xx（403/404） | 链接不可用（可能失效） | 不重试；按钮：手动重试 / 返回详情 |
| MediaCodec/不支持流错误码 | 硬解与软件解码都失败：当前设备硬解和软件解码都处理不了该片源（AV1 / HEVC / 10bit 等），建议换 H264 源 | **先走音轨回退阶梯**（见 §6.5.2），阶梯耗尽再**自动换软解档重试一次**（见 §6.5.1），仍失败才不重试 |
| 阶梯走到静音后仍失败 | 当前设备硬解和软件解码都处理不了该片源的**音轨**（DTS / 杜比 DDP 等），建议把音轨换成 AAC 立体声 | 不重试（`classifyPlaybackError(error, audioTrackFailure = true)`） |
| 其他 | 播放出错了 | 自动 ×1 → 手动 |

- 错误时保持黑屏+居中面板（不 release player 以保留重试上下文）。

#### 6.5.1 解码优先级与「硬解失败自动换软解」兜底

`PlayerController` 的 renderers 工厂有两档，由构造参数 `preferSoftwareDecode` 决定：

| 档 | MediaCodecSelector | ExtensionRendererMode | 语义 |
|---|---|---|---|
| 硬解优先（默认） | `HardwareOnlyCodecSelector`（只返回硬解） | `ON` | 有硬解走硬解；**平台完全无硬解**时 MediaCodec 渲染器声明不支持 → 落到 NextLib 的 FFmpeg 渲染器 |
| 软解优先（兜底） | `MediaCodecSelector.DEFAULT`（硬解 + 平台软解全量候选） | `PREFER` | FFmpeg 抢在 MediaCodec 之前，任何片源都先试软解 |

为什么必须有第二档：默认档下只要设备**声明**了某个硬解，`MediaCodecRenderer` 就认为自己能解，FFmpeg 扩展渲染器永远轮不到；而厂商硬解常见"谎报能力"（只支持 8-bit Main profile、或有分辨率/帧率上限），此时 `setEnableDecoderFallback(true)` 只能在硬解候选里打转 → 整片直接报 `DECODING_FAILED` / `DECODING_FORMAT_EXCEEDS_CAPABILITIES`。典型现象：同一部 AV1 片源，骁龙 8 Gen 3（`c2.qti.av1.decoder`，3840×2176@60）正常，另一台机器直接弹"无法解码"。

两条硬规则（模拟器实测得出，两端一致，违反任何一条都会让"软解兜底"反而更糟）：

1. **`HardwareOnlyCodecSelector` 只作用于视频 mime**：对 `audio/` 前缀直接返回平台默认候选。实测反例——模拟器只有 `c2.android.aac.decoder`（软解），被本 selector 剔除后 `MediaCodecAudioRenderer` 声明不支持 → `FfmpegAudioRenderer` 接手**普通 AAC** → `ffmpeg_jni: Error in swr_init: Invalid argument`（该 `Format` 的 `channelCount` 未设）→ 整片 `DECODING_FAILED`。平台软音频解码器开销极低，把音频抢给 FFmpeg 没有任何收益，只有 DTS / TrueHD 这类平台解不了的音轨才需要 FFmpeg 音频渲染器。
2. **`PREFER` 只允许作用在视频上**：`setExtensionRendererMode()` 是全局的，会把音频渲染器一起顶到前面，同样踩第 1 条坑。所以软解优先档不用裸 `NextRenderersFactory`，而用 `SoftwareFirstVideoRenderersFactory`（`NextRenderersFactory` 子类）覆盖 `buildAudioRenderers`，把音频的扩展模式强制降回 `ON`。覆盖签名（media3 1.7.1，javap 核对）：`buildAudioRenderers(Context, Int, MediaCodecSelector, Boolean, AudioSink, Handler, AudioRendererEventListener, ArrayList<Renderer>)` —— 升级 media3 时须同步核对。

触发与流程（`PlayerScreen.switchToSoftwareDecode()`）：

1. `onPlayerError` 收到解码类错误码（`DECODER_INIT_FAILED` / `DECODER_QUERY_FAILED` / `DECODING_FAILED` / `DECODING_FORMAT_UNSUPPORTED` / `DECODING_FORMAT_EXCEEDS_CAPABILITIES`）
2. 先走**音轨回退阶梯**（§6.5.2，最多 3 跳）：声道上限 → 指定另一条安全音轨 → 静音保画面；判据用 `ExoPlaybackException.rendererFormat` 的 `sampleMimeType`，只有音轨侧的失败才进阶梯
3. 仍失败 → 自动换档：记下当前位置 → 即时上报断点 → `softwareDecodeFallback = true` → `remember(softwareDecodeFallback)` 重建 `PlayerController`（软解优先档）→ `LaunchedEffect(playUrl, controller)` 用同一 URL 重新装配 → 新 player `STATE_READY` 时 seek 回原位置，并提示「硬解失败，已改用软件解码重试」
4. 只切一次（`softwareFallbackTried`）；软解档也失败才落 §6.5 的错误面板，不反复重建

因为换档 = 换 ExoPlayer 实例，所有长生命周期 effect（`Player.Listener`、`AnalyticsListener`、进度轮询、断点上报、倍速应用）都必须以 `controller` 为 key —— 否则会读到已 release 的旧实例。同理，音轨阶梯已经生效的 `trackSelectionParameters` 也会随实例归零，所以装配 MediaItem 前要先 `AudioFallbackState.restoreOn(...)` 把声道上限/禁音带回来。

#### 6.5.2 音轨回退阶梯（`ui/player/AudioFallback.kt`，两端各一份镜像）

**问题形状**（实测片源 `…2160p.IQ.WEB-DL.H.265.DDP5.1.mkv`）：MKV 把 `E-AC3(DDP) 5.1` 标成 `default`，旁边才是百分百能解的 `AAC LC 立体声`；本项目不设语言/声道偏好，`DefaultTrackSelector` 于是正好选中那条不安全的，一路失败即**整片**失败 —— 而旧容错只有一招「禁音重试一次」，安全轨完全轮不到。

| 跳 | 动作 | 何时命中 |
|---|---|---|
| 1 `CHANNEL_CAP` | `maxAudioChannelCount = 2`（恢复用 `Integer.MAX_VALUE`） | 存在 ≤2 声道（或声道数未声明）的其它轨 |
| 2 `FORCE_TRACK` | `clearOverrides()` + 放开声道上限 + `setOverrideForType(TrackSelectionOverride(group, listOf(idx)))` | 剩下的轨都是 5.1，但 mime 属于「平台或 FFmpeg 兜底能解」的安全序（AAC/Opus/Vorbis/MP3/FLAC < AC3/E-AC3/AC4 < TrueHD/DTS 系） |
| 3 `MUTED` | `setTrackTypeDisabled(TRACK_TYPE_AUDIO, true)` | 换无可换（例如只有一条 DTS 轨） |

- 阶梯**单调**（`NONE → CHANNEL_CAP → FORCE_TRACK → MUTED`）且失败过的 mime 记进 `triedMimes`，最多 3 跳，防 `prepare()` 打转。
- 判据：`ExoPlaybackException`（`PlaybackException` 子类）的 `rendererFormat.sampleMimeType`；`AnalyticsListener` 在 media3 1.7 已无 `DecoderInfo` / `onDecoderInitError`，不可用作判据。
- `TrackSelectionOverride` 的 key 是 `TrackGroup` 实例 → 只能在出错时从**当前** `player.currentTracks` 现取现用，跨实例不带。
- 每跳都记 logcat（`音轨解码失败 mime=… → 阶梯 …（第 N 跳）`），供 §机型矩阵 与片源兼容性归因统计。
- 已知边界：MKV 轨名拿不到（`MatroskaExtractor` 只把 NAME 存进内部 `Track.name`，不写 `Format.label`），提示文案走通用版。
- 单测：`app/src/test/java/com/hypermux/mobile/ui/player/AudioFallbackTest.kt`（需 `unitTests.isReturnDefaultValues = true`，因为构造 Media3 `Format`/`TrackGroup` 会路过 `android.text.TextUtils` 桩）。

### 6.6 自动连播（与 TV 端 §7.4 同语义）

- seasons 数据在详情页已持有并传入播放器；下一集 = 同季 `episodeNo+1` 存在的第一个，否则下一季第一个。
- 连播角标 1.5s（"即将播放下一集：第 7 集"），期间任意操作可取消（回 PAUSED 保持当前集尾）。
- 一集自然播完 → 上报 `finished=1`（action=finish）→ 自动下一集（顺序：先 finish 再 next，防竞态；末集只 finish 不 next，回详情页）。

### 6.7 性能保护

- 单实例：同 Activity 内从"继续看"切集 = 同 Player 换 MediaItem，不重建（`onNewIntent`/状态参数变更复用）。
- 4K 软解防护：解析首帧后若 `codecName` 含 `CCodec` 且 fps/分辨率异常 → debug log 标记（供机型矩阵归因）；UI 不处理。

---

## 7. 断点与历史逻辑（客户端职责汇总，与 TV 端 §8 一致）

| 事件 | 动作 |
|---|---|
| 播放中 | 每 10s `PUT progress {videoId, positionMs, durationMs}`（durationMs 来自 ready 后 format，带一次即可） |
| 暂停/退后台/退出播放页 | 立即 PUT 一次（幂等）；position<3s 跳过不上报 |
| 回详情/首页 | 先 ensure 上报 ≥ 当前 position |
| 自然播完 | `POST finish {action:"finish"}` |
| 历史删除 | `POST finish {action:"remove"}` |
| App 进程被杀 | 丢失 ≤10s（可接受） |
| 断点展示 | 首页 continueWatching / 卡片角标 / 集网格进度条——**客户端零计算**，全部信任接口 |

> **退后台/退出播放页那一次怎么保证落地**：`PlayerScreen` 的 `LifecycleEventObserver` 在 `ON_STOP` 时「取消连播 + `playWhenReady = false` + 上报」，播放器监听器再把"不在前台时的起播意图"按回 `false`（迟到的 play-url、错误自动重试、连播换集都走 `play()`），所以黑屏/后台不会再播。上报本身走 `ProgressSync.submit()`（自带 scope）而不是 `viewModelScope`：那一次正好落在 NavBackStackEntry 被销毁、ViewModel 被清空的当口，`viewModelScope` 会把在途请求连离线入队一起取消掉，最后一段进度就丢了。

---

## 8. 异常与降级基线

- 任何网络 API 失败：当前页 `ErrorStateView`（或局部失败如封面 404 → 占位图：标题首字 + 哈希底色，Coil fallback）。
- 全局"网络连通"指示：`NetworkMonitor`（ConnectivityManager）→ 离线时首页/列表顶部黄条"网络已断开，展示缓存"。
- play-url 超时（>30s）：播放器 loading 圈 + "获取播放地址较慢，请稍候"；失败按 §6.5 分类。
- Crash：`Thread.setDefaultUncaughtExceptionHandler` 收集 → 弹"应用异常，将重启" 1.5s → process kill；log 落 `filesDir/hypermux_crash.log`（8KB ring）。
- 401（用户被停用/删除）：清 token 回登录页，提示"登录已失效，请重新登录"。

---

## 9. 包体与构建

- 目标 < 25MB（media3 + retrofit + coil，release 开 R8/ProGuard；media3 受 AAR consumer rules 保护）。
- 全通用 ABI（ExoPlayer 纯 Java/Kotlin，无 NDK 依赖），无需 ABI 拆分。
- 构建命令：`./gradlew :app:assembleRelease`；`signingConfigs.release` 走 `keystore.jks` + `local.properties` 注入密码（不入库）。
- **明文流量**：`debug` `usesCleartextTraffic=true`（自托管 http 测试环境）；`release` 关闭，必须 https（自托管建议套域名+证书；见 [70-deploy-ops.md](./70-deploy-ops.md)）。
- 分发：APK 直装（自托管场景）；应用市场上架非本期目标。

---

## 10. 里程碑与验收（M2P Definition of Done）

| 里程碑 | 内容 | 产出 | 粗估（人日） |
|---|---|---|---|
| **P0 脚手架与数据层** | 工程骨架 + 主题 + DataStore + Retrofit + 登录闭环（登录→首页→登出→401 回登录） | 真机可登录 | 1.5 |
| **P1 内容浏览** | 首页聚合（分类 rail + 继续观看 + 网格）+ 分类列表分页 + 全局搜索 + 封面加载 + 空/错误态 | 浏览主链通 | 2 |
| **P2 详情与播放** | 详情页（内嵌剧集）+ 播放页（续播/连播/手势/10s 断点上报/错误分类） | 播放主链通 | 2.5 |
| **P3 历史与我的** | 历史页（三操作）+ 我的页（主题/服务器地址配置+测试/清缓存/登出）+ 离线降级黄条 | 全功能闭环 | 1.5 |
| **P4 真机验收与加固** | 机型矩阵验证 + 错误分类实测 + 性能与包体 + release 签名 | 可发布 APK | 1.5 |

合计约 **9 人日（≈2 周，1 名全职）**。后端零改动（仅联调验证）。

**手测主线（真机）**

1. 首登：输入账密 → 进首页；杀进程重启 → token 有效免登；后台删用户 → 下次请求 401 → 自动回登录页。
2. 首页：分类 rail 切换/卡片渲染/继续观看排序/下拉刷新；断网 → 缓存 + 离线黄条，再联网恢复。
3. 搜索：全局搜索出结果/空态/搜索历史点击复用/防抖不抖动。
4. 详情→播放：MKV 直链播通；断点 seek（浮差 ≤5s）；±10s 滑动手势；双击暂停；长按 2 倍速；回退再进→续播；自然播完自动下一集；末集播完回详情。
5. 历史：列表进度/继续看/标记看完/删除（remove 后不再出现）。
6. 我的：主题切换即时生效；改服务器地址 → 测试连接 → 重登；清缓存生效。
7. 错误：坏链（HTTP 404）→ 文案 + 不重试 + 返回详情；慢网 → 自动重试成功恢复；play-url 超时 loading 文案。
8. 稳定性：连续 30min 播放无 OOM/泄漏；横竖屏切换不重建丢失状态。

**单测（JVM）**

9. `AuthRepo` 过期判定（<300s buffer）、`ApiResult` 401 流。
10. 断点 tick 节奏（Mock currentTime：10s 边界、pause 即时、<3s 跳过）。
11. 集排序/next-ep 边界（跨季、末集、中间缺失集号）。
12. 服务器地址重写 Interceptor（host 替换、path 保留）。

**跨文档一致性锁定（写给 QA）**

- 密码规则分两处：**建用户/重置密码**用字符集 `[a-zA-Z0-9_.]{6,20}`（后台表单 + 后端 `UserSaveReq`/`ResetPasswordReq`）；**登录**仅校验长度 6~20（后端 `LoginReq`），各端登录页**不得**校验字符集。以 [30-api-contract.md](./30-api-contract.md) §4.7 为准。
- 时间全部毫秒；`videos[].duration` 是秒，`progress.*Ms` 是毫秒（打印 debug 对照）。

---

## 11. 已锁定决策与默认项

> 沿用项目惯例：默认值无异议即按默认执行（如要改，在实施前指出）。

| # | 事项 | 结论 |
|---|---|---|
| D1 | UI 工具包 | **Jetpack Compose + Material 3**（现代原生、代码量少、与 Navigation/Hilt 生态契合）；备选 XML+View+ViewBinding（如团队有 XML 偏好可整体平移，数据层不受影响） |
| D2 | DI | **Hilt**（标准、可测性好）；备选手动工厂（TV 端方案） |
| D3 | token/配置持久化 | **DataStore Preferences**（替代 SharedPreferences，异步安全） |
| D4 | 首页"全部"档 | 默认**不聚合**：首页 = 继续观看 + 分类入口 + 选中分类影片流（与 web 端一致）；全量聚合流留待后续 |
| D5 | 剧集页 | **内嵌详情页**（手机端减少跳转）；不独立成页 |
| D6 | 播放页方向 | **强制横屏**（进入即锁定，退出恢复）；竖屏半屏播放留待后续 |
| D7 | 服务器地址 | **运行时可配**（自托管刚需）；0.1.7 起双模式：「公开服务器」档位由构建期注入预置地址决定（`-PhypermuxPublicServerUrl`，UI 不露地址；未注入则无该档位），自定义（登录页底部"服务器配置"弹窗选择/测试），改后重登；对自托管用户始终保留自定义能力 |
| D8 | 与 TV 端复用 | 数据层/播放器状态机/错误分类**同语义平移**；TV 端启动后手机端 `data/` 与 `player/` 可抽公共模块（本期不抽，避免过早抽象） |
| D9 | 主题 | **深色默认**，浅色/跟随系统可切（对齐 web 端） |

## 12. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 自定义服务器地址配错/证书不受信 | 无法连接 | "服务器配置"弹窗内"测试连接"即时反馈；release 强制 https + 文档指引域名证书；错误文案明确指向"检查服务器地址" |
| 机型 HEVC/10bit 软解卡顿 | 播放体验差 | §6.5 第 3 类文案引导换 H264 源；P4 机型矩阵记录归因 |
| 横竖屏切换 Activity 重建 | 播放中断/状态丢 | `configChanges` + ViewModel 持有 PlayerController；播放器不随重建 release |
| play-url 接口慢（OpenList 挂载源） | 进入播放慢 | 30s 超时 + loading 文案；M5 后端可加缓存（不在本期） |
| 系统手势/厂商手势冲突（全面屏滑动） | 手势误触 | 手势层仅在控制层隐藏时生效；提供控制层按钮兜底（±10s/亮度/音量） |
| 片库规模上涨（数千影片） | 列表变慢 | 分页 30/页 + 封面懒加载 + Coil 磁盘缓存；后端分页已就绪 |
| Compose 与 media3-ui-compose 版本兼容 | 构建失败 | 锁定 BOM 版本，P0 首日验证播放 hello-video 跑通再铺业务 |

---

## 13. 后续可选项（非本期）

- 媒体会话通知（MediaSessionService，锁屏/通知栏控制）
- 下载与离线缓存（大工程，需后端配合鉴权）
- 字幕 / 多清晰度切换（沿用全项目结论：不做）
- App 内公告（`app_config` 表已预留）
- 推送（新版本/更新提醒）
- TV 端复用：手机端验证后的数据层/播放器状态机回灌 TV 端（D8 公共模块抽取）

---

## 14. 投屏（M7 · 2026-10-22 新增）

> 唯一事实源：[80-cast-detailed.md](./80-cast-detailed.md)（流程口径、状态机、失败模式、测试流程与预期结果）；接口契约：[30-api-contract §3.13](./30-api-contract.md)。

**14.1 手机端职责边界（重要）**

手机端本期**只是"能远程投送的设备"，不是遥控器**：
- 有：投屏入口、目标设备选择、等待确认、结果弹窗、取消投屏、设备列表。
- 没有：播放/暂停/seek/选集/音量等任何遥控控件，没有 TV 播放状态回显。

**14.2 设备身份**

| 项 | 做法 |
|---|---|
| `device_id` | 新增独立 DataStore `name = "device"`（不塞进 `settings`：设备身份与用户偏好是两回事）；首次访问 `UUID.randomUUID()` 落盘，之后只读；卸载重装 = 新 id |
| `device_name` | 默认 `手机 · ${Build.MODEL}`，「我的」页可改（≤64） |
| `cast_last_target` | 上次成功投屏的 TV deviceId，设备弹层据此高亮「上次使用」 |
| 注册时机 | `AppNavigation` 的 `LaunchedEffect(session != null)`（登录成功与启动恢复会话都走这里）、打开投屏设备弹层、进入「我的」页，各调一次 `POST /api/app/devices/register`（幂等，顺带刷在线）；登出时 `CastState.clear()` |

**14.3 新增文件**

`data/repo/DeviceRepo.kt`、`data/cast/CastState.kt`（`@Singleton` 投屏状态机：`active/waiting/outcome/enabled` + `send/startPolling/cancelActive/restore/clear`，走现有 `safeApiCall`）、`ui/cast/CastViewModel.kt`（薄壳，把 `CastState`+`DeviceRepo` 暴露给页面）、`ui/cast/CastDeviceSheet.kt`、`ui/cast/CastStatusDialog.kt`、`ui/cast/CastUi.kt`（`CastButton` / `CastSentOverlay` / `CastActiveChip`）；改 `data/api/ApiService.kt`（+8 方法）、`data/model/Models.kt`（+10 模型）、`config/AppConfig.kt`（`CAST_POLL_INTERVAL_MS=1_000L`、`CAST_POLL_MAX_MS=17_000L`）、`ui/player/PlayerIcons.kt`（自绘 `Cast` 图标）。Manifest 无需新权限。

> `CastState` 是 `@Singleton`：播放页 / 详情页 / 「我的」页各自的 `CastViewModel` 读同一份状态，切页不丢投屏状态。

**14.4 交互流程**

```
播放页/详情页「投屏」→ CastDeviceSheet（同账号 tv 设备）
   在线：可点（绿点，上次成功目标默认高亮，DataStore cast_last_target）
   离线：灰显不可点 + 「离线 · 请先在电视上打开 HyperMux」+ 右上角「刷新」
→ POST cast/request → CastStatusDialog「正在等待「{TV名}」确认…」（带「取消投屏」）
   协程每 1s 轮 GET cast/request/status（上限 17s）
   ACCEPTED   → 弹窗「TV端已接受你远程投屏的请求」；本地 playWhenReady=false +
                播放区被 CastSentOverlay 盖住（封面 + 片名 + 集 +「取消投屏」）
   REJECTED   → 弹窗「TV端已拒绝投屏请求」
   EXPIRED    → 弹窗「电视端未响应，请稍后重试」
   CANCELLED  → 弹窗「已取消投屏」
   连续 2 次轮询失败 → 弹窗「投屏失败：{msg}」
投屏生效期间：active 非空 → 起 5s 一轮的 cast/current 低频观察，
   TV 退出播放 / 被接管时手机卡 ≤5s 自己消失（无推送通道，只能靠这层确认）
```

**14.5 与播放器的耦合（关键约束）**

- **投屏闸门**：`PlayerViewModel` 注入 `CastState`，`isCasting` 时 `reportProgress()` 直接 return —— 否则手机与 TV 双写 `play_progress`（`uk_progress_user_video` 是 upsert），会把 TV 的断点覆盖回手机旧位置。
- 投屏成功后手机本地播放必须**停止**（避免双声道）：`LaunchedEffect(castActive)` 里 `playWhenReady = false`，同时手势层 `enabled = false`；播放器实例不重建（沿用现有 `DisposableEffect { controller.release() }` 生命周期）。
- 从详情页发起时 `positionMs` 取该集服务端断点（`VideoItemVo.progress`）或 0，不取本地缓存。
- 手机切后台再回前台：用 `GET /api/app/cast/current`（`data.active`）恢复「已投送至 …」卡，不重复发起。
