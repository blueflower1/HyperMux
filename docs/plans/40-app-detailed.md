# 40 · TV 端 App 详细设计（Android TV 原生）

> ⚠️ **已作废（v1.0，从未实施）**：本方案的 Leanback + XML Fragment 路线已被取代。
> 现行有效方案见 **[48-tv-app-detailed.md](../../hyper-mux-android-tv/docs/plans/48-tv-app-detailed.md)**（Compose for TV，UI/交互移植手机端 `hyper-mux-android`）。
> 本文档仅作历史存档，不再作为实施依据。

> 隶属：[PLAN.md](../../PLAN.md) · 版本 v1.0 · 对应里程碑：M2 MVP（M5 加固项标注）
> 接口事实源：[30-api-contract.md](./30-api-contract.md)（§3 TV 端 9 接口）
> 范围说明：本产品为**普通用户版**（非 care 项目老人版）——**无 Kiosk 锁屏、无开机自启**，保留系统返回/最近任务；遥控器为主、支持蓝牙/USB 键盘输入。

---

## 1. 技术栈

| 项 | 选型 | 版本 |
|---|---|---|
| 语言/构建 | Kotlin + Gradle KTS | Kotlin 1.9.24 / AGP 8.5.x |
| SDK | compileSdk 34 / minSdk 24 / targetSdk 34 | （Media3 要求 minSdk 24） |
| UI | AndroidX **Leanback**（`androidx.leanback:leanback:1.0.0`） | `uses-feature leanback required=false` |
| 播放 | **Media3 ExoPlayer + exoplayer-ui**（MP4/MKV/Matroska/HLS 核心库自带） | 1.4.1 |
| 网络 | Retrofit2 + OkHttp + gson converter | 2.11 / 4.12 / 2.11 |
| 异步 | kotlinx-coroutines（全协程，无 RxJava） | 1.8.x |
| 图片 | Coil 2（OkHttp loader，memory+disk 双缓存） | 2.6.0 |
| 持久化 | SharedPreferences（JSON 用 Gson 序列化） | - |
| JSON | Gson（`camelCase` 与契约字段名一致，零映射） | - |
| 包名/工程 | `com.hypermux.tv`，单 module | versionName 0.1.0 |

---

## 2. 工程结构

```
app/
├─ settings.gradle.kts / gradle.properties / local.properties（hypermuxApiBase 可覆盖）
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/hypermux/tv/
      │  ├─ TvApp.kt                      # Application：Coil/Retrofit/Coroutine scope 初始化
      │  ├─ config/AppConfig.kt           # API_BASE(BuildConfig)、阈值常量、字体规格
      │  ├─ data/
      │  │  ├─ api/
      │  │  │  ├─ ApiService.kt           # Retrofit 接口 = 30 契约 §3 的 1:1 映射
      │  │  │  ├─ AuthInterceptor.kt      # 注入 Bearer；401 → AuthRepo.invalidate + 跳转
      │  │  │  └─ ApiResult.kt            # sealed: Ok<T>/Err(code,msg,httpStatus)
      │  │  ├─ model/                     # dto：与契约字段同名（SessionVo, HomeVo, MovieCardVo,
      │  │  │                              #   MovieDetailVo, SeasonsVo, PlayUrlVo, HistoryVo...）
      │  │  ├─ repo/
      │  │  │  ├─ AuthRepo.kt             # 登录/登出/token 持久化（SP key: hypermux_auth）
      │  │  │  ├─ ContentRepo.kt          # home/列表/详情/剧集 + 缓存降级
      │  │  │  └─ PlaybackRepo.kt         # play-url/progress/finish
      │  │  └─ cache/CacheStore.kt        # home/list 的 SP 缓存（key 含 userId/池id）
      │  ├─ ui/
      │  │  ├─ MainActivity.kt            # 单 Activity + Fragment 手动栈（backstack）
      │  │  ├─ nav/AppStack.kt            # push/pop/replace + 路由守卫（未登录→Login）
      │  │  ├─ login/  LoginFragment + CharKeypadView（§5.1）
      │  │  ├─ home/   HomeFragment + HomePagerAdapter
      │  │  ├─ movie/  MovieListFragment / MovieDetailFragment / EpisodeListFragment + 各 Adapter
      │  │  ├─ history/ HistoryFragment
      │  │  └─ common/ ErrorStateView / Focus.kt（焦点工具）/ PhImageLoader（首字母占位）
      │  ├─ player/
      │  │  ├─ PlayerFragment.kt          # 全屏播放页（§7）
      │  │  ├─ PlayerController.kt        # 播放状态机 + 断点 + 自动连播 + 错误分类
      │  │  └─ PlayerReportLoop.kt        # 10s 节流上报协程
      │  └─ util/ (Log.kt / TimeFmt.kt / ScaleUtil.kt)
      └─ res/: bouncy 焦点态 drawable、values(字号/色板)、layout
```

### 2.1 build.gradle.kts 要点

```kotlin
android {
  namespace = "com.hypermux.tv"; compileSdk = 34
  defaultConfig {
    minSdk = 24; targetSdk = 34
    applicationId = "com.hypermux.tv"; versionCode = 1; versionName = "0.1.0"
    buildConfigField("String", "API_BASE",
      "\"${(rootProject.findProperty("hypermuxApiBase") ?: "http://192.168.31.100:8080")}\"")
  }
}
dependencies {
  implementation("androidx.leanback:leanback:1.0.0")
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-exoplayer-ui:1.4.1")
  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-gson:2.11.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("io.coil-kt:coil:2.6.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
buildFeatures { buildConfig = true }
```

API_BASE 覆盖：`local.properties` 或命令行 `-PhypermuxApiBase=...`；**Test 环境 http 明文**靠 `debug build usesCleartextTraffic=true`（release 关闭，必须 https）。

### 2.2 AndroidManifest 要点

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-feature android:name="android.software.leanback" android:required="false"/>
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
<application android:label="HyperMux TV" android:largeHeap="true">
  <activity android:name=".ui.MainActivity"
      android:launchMode="singleTask" android:exported="true"
      android:configChanges="keyboardHidden|keyboard|screenSize|smallestScreenSize|orientation|screenLayout">
    <intent-filter><action android:name="android.intent.action.MAIN"/>
                   <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
  </activity>
</application>
```

（无 `HOME`/`BOOT_COMPLETED`——非 kiosk。）

---

## 3. 全局规范

### 3.1 阈值常量（`AppConfig`）

| 常量 | 值 | 说明 |
|---|---|---|
| PROGRESS_REPORT_INTERVAL | 10s | 播放中断点上报间隔 |
| RESUME_THRESHOLD | 30s | 首次进入：positionMs>30s 且 < duration-30s 才视为续播 |
| NEAR_END_BADGE | 0.95 | 客户端"已看完"角标展示阈值（服务端 -30s 自动 finished） |
| TOKEN_EXPIRY_BUFFER | 300s | 本地判过期提前量（401 兜底） |
| HOME_CACHE_MAX | 64KB / 列表 32KB | SP 缓存软上限，超了不存 |
| OFFLINE_RETRY | 2 次 × 1.5s | 网络/5xx 自动重试 |
| PLAYER_AUTO_NEXT_DELAY | 1.5s | 连播延迟（展示"即将播放下一集"角标） |
| AUTO_NEXT | true（硬编码，后续可配） | 一集播完自动下一集 |

### 3.2 字号与焦点态（TV 3m 观感）

- 标题 30sp / 卡片标题 24sp / 正文 22sp / 辅助 18sp；行高 1.3。
- 焦点态统一 bouncy：`state_focused` = 描边 3dp 品牌橙 + 背景提亮 + **scale 1.05（180ms 动画，Focus.kt#applyFocusScale）**；非焦点 = 微降透明度(0.92)。所有可点控件（卡片/按钮/tab）强制 `isFocusableInTouchMode=false` 走焦点体系。
- 色板：底 `#0F1524`，卡 `#1A2236`，文字 `#F3F6FF`/`#9FB0D6`(弱)，品牌 `#F5A524`，成功 `#22C55E`，错误 `#EF4444`。

### 3.3 导航与返回

| 起始页 | BACK 行为 |
|---|---|
| 登录页 | 无（忽略） |
| 首页 | 忽略（可双击返回? 不做；保持） |
| 影片列表/详情/剧集页 | 回上级（状态保留：滚动位/焦点项） |
| 播放器 | 回剧集页（先即时上报断点，player.pause+destroy） |
| 从首页直接进播放器 → 返回 | 回首页 |

Fragment 手动栈：`AppStack.push(fragment, addToBackStack=true)`；`401` 触发时 `AppStack.clearAll → Login`。

---

## 4. 数据层

### 4.1 ApiService（= 契约 §3 全量）

```kotlin
interface ApiService {
  @POST("api/app/auth/login")      suspend fun login(@Body b: LoginReq): ApiResult<SessionVo>
  @GET("api/app/home")             suspend fun home(): ApiResult<HomeVo>
  @GET("api/app/category/{id}/movies") suspend fun categoryMovies(@Path("id") categoryId: Long,
                                        @Query("page") page: Int, @Query("size") size: Int,
                                        @Query("keyword") kw: String?): ApiResult<PageVo<MovieCardVo>>
  @GET("api/app/movie/{id}")       suspend fun movie(@Path("id") id: Long): ApiResult<MovieDetailVo>
  @GET("api/app/movie/{id}/videos")suspend fun videos(@Path("id") id: Long): ApiResult<SeasonsVo>
  @POST("api/app/play-url")        suspend fun playUrl(@Body b: PlayUrlReq): ApiResult<PlayUrlVo>
  @GET("api/app/history")          suspend fun history(@Query("limit") limit: Int): ApiResult<List<HistoryVo>>
  @PUT("api/app/progress")         suspend fun progress(@Body b: ProgressReq): ApiResult<ProgressVo>
  @POST("api/app/progress/finish") suspend fun finish(@Body b: FinishReq): ApiResult<FinishVo>
}
```

### 4.2 认证链路

- `AuthInterceptor`：每请求注入 `Bearer`；响应 **401**（登录页请求除外）→ `AuthRepo.invalidate()`（清 SP）→ 事件总线上抛 → `AppStack` 切登录页 + 全屏 toast"登录已失效，请重新登录"。
- `AuthRepo`：SP `hypermux_auth` = Gson `{token, expiresAt, userId, username, nickName}`；`isLoggedIn() = token!=null && now < expiresAt - 300s`。
- token 7 天有效 + 盒子常驻 → 重登录频率极低；无 refresh（与后端一致）。

### 4.3 缓存降级（ContentRepo + CacheStore）

| 数据 | 缓存 key | 写入 | 读取时机 |
|---|---|---|---|
| home | `home:{userId}` | 每次成功后（限 64KB） | **API 失败（网络/5xx/超时）**→ 读缓存渲染 + 顶部细条 `离线数据 · {更新时间}` |
| 池列表第 1 页 | `list:{poolId}:p1` | 成功后 | 同上（仅离线兜底首页场景：该池第 1 页） |
| 封面 | Coil disk 100MB | 自动 | 离线卡牌可展示 |

- 缓存**不含**断点真实性诉求的强一致场景（continueWatching 用最后一次成功值，可接受"离线时不更新"）。
- 完全无缓存 + 离线 → `ErrorStateView`（大字"网络未连接" + 重试按钮），**不白屏**。
- 认证失败（401）不走缓存（直接登录页）。

---

## 5. 页面规格

### 5.1 登录页 `/login`

- 布局：左侧插槽图（占位本地图），右侧输入区：用户名框 / 密码框（点阵）/ 登录按钮。
- **字符键盘 `CharKeypadView`**（无硬件键盘时自绘；检测到 IME/物理键盘时输入框退化为标准键盘输入，两者并存不冲突）：

```
┌ 名称页(⇅可切) ──────────────────────────────────┐
│  q  w  e  r  t  y  u  i  o  p                  │
│  a  s  d  f  g  h  j  k  l                     │
│  z  x  c  v  b  n  m                            │
│  [⇅翻页] [⇧大小写] [⌫删除] [✓OK]              │
├ 符号页 ──────────────────────────────────────────┤
│  0 1 2 3 4 5 6 7 8 9 _ .                        │
└──────────────────────────────────────────────────┘
```

- 焦点在框 → 直接点字符键输入；`✓OK` = 提交（用户名页焦点在输入区时）；`⌫` 删除；`⇧` 大小写切换（密码默认开、名字默认关）。
- 本地前置校验（对齐后端 `LoginReq`，不满足不上送）：username `^[a-zA-Z0-9_]{3,32}$`；**password 仅校验长度 6~20，不校验字符集**（`[a-zA-Z0-9_.]` 只用于建用户/重置密码——**见 §13 一致性标注**）。
- 服务端错误 → 大号居中提示（401"用户名或密码错误" / 403"账号已停用，请联系管理员" / 429 原样），保留已输内容。
- 成功 → 存 token → 首页。

### 5.2 首页 `/home`

数据来源：`GET /api/app/home`（一次全拿）。布局（Leanback 标准三行结构）：

```
[ 池 Tab 行        ]  电影库 | 剧集库 | 动漫库 |（横向, 当前池放大）
[ 当前池卡片格子   ]  LazyGridView 4 列（4K 放宽 5 列），翻页加载 page>1
[ 继续观看 行(horiz)] 最多 10 张宽卡（封面+片名+集号+进度条）
```

- 进首页触发：上拉刷新（top 焦点下按）；每次从二级页返回**不**强制重拉（缓存续播位已够），手动下拉或切池时才拉。
- 卡片：封面(2:3) + 片名 + 评分(⭐9.4) + 年份；`progress≠null` → 底部进度条 + `第N集` 角标。
- 池 movieCount=0：Tab 不展示（服务端已过滤）。列表空态：`该池暂无影片`。
- 搜索：Tab 行最右 `🔍 搜索` 焦点项 → 弹全屏输入页（同字符键盘，宽匹配）→ 结果 = `poolMovies(keyword=)` 当前池内搜索。M2 做池内搜索；全池搜索 M5 可选。

### 5.3 影片列表（池内）

- LazyGridView；条数 20/页，底部 sentinel 触发 loadMore（`total>page*size`）。
- 滚动回顶时恢复 Tab 行焦点管理；`holder 绑定 时 requestFocus()` 默认聚焦首个卡片。
- 断点角标数据来自 `MovieCardVo.progress`（服务端已合并，无 N+1）。

### 5.4 影片详情 `/movie/{id}`

Leanback 经典两栏 + 底行：

```
┌──────────────────────────┬──────────────────────────────┐
│  封面(portrait 240宽)    │  标题(30sp)  原名(弱, 可无)    │
│                          │  ★9.4  2023  中国  普通话      │
│                          │  [类型tag] [地区tag] [自定义]   │
│                          │  摘要(3 行截断)                │
├──────────────────────────┴──────────────────────────────┤
│ 按钮行(大):  ▶ 从第6集继续 | ▶ 播放第一季第1集 | 剧集列表 │
└─────────────────────────────────────────────────────────┘
```

- `progress!=null` → 首按钮"从第 {episodeNo} 集继续"；否则"播放第一季第 1 集"（按 seasons 排第一个）。
- OK → PlayerFragment（携带 movieId + videoId + resumeMs）。

### 5.5 剧集页 `/movie/{id}/videos`

- 顶：季 Tab（S1 S2 …，仅一季时隐藏）。
- 体：集格子（横向 6~8 列小卡：大号集号 + 标题一行截断 + 底部进度条；`finished` 的集灰色 ✓）。
- 数据：`GET videos`；`progress` 合并展示；焦点进入后直接连播焦点集（连走两步的快捷：详情页"继续" → 集列表 → 回到详情页再播，不抖动：直接 Intent 进 Player？——**约定：详情页按钮直达 Player，不经过集列表页**）。

### 5.6 播放器 `/player`（详见 §7）

### 5.7 历史页 `/history`

- 入口：首页最左独立焦点项 `🕘 历史`。
- 布局：横向宽卡列表（`GET history?limit=50`）：封面(小) + 片名 + `S{season} · 第{ep}集` + 进度条 + 更新时间。
- 双操作焦点（焦点卡展开为 2 键）：`▶ 继续看`（进该集断点播放）/ `🗑 删除`（`action=remove`，确认弹层"从历史中移除"）。
- 空态：`还没有播放记录`。
- "已看完"的集在列表显示 ✓（finished=1）。

---

## 6. 焦点与交互总则

| 规则 | 实现 |
|---|---|
| 进入页默认焦点 | 首页→Tab 行首项；列表→第一卡；详情→"继续播"；历史→第一卡；失败态→重试按钮 |
| 行列导航 | Leanback 默认 focusSearch；Tab 行/格子互不抢焦（`recyclerView.setFocusable(false)` + 子项 focusable） |
| OK 语义 | 卡片→详情；按钮→执行；播放器→播放/暂停切换 |
| LEFT/RIGHT 在播放器 | seek ∓10s（重复触发=长按连跳） |
| UP 在播放器 | 展开上半屏元信息（片名/集名/年份/标签）；DOWN 收起 |
| Back | 见 §3.3 |
| 列表滚动 | 惯性 + 平滑定位焦点（`smoothScrollToPosition`），焦点随滚动保持 |
| 键盘兼容 | 蓝牙/USB 键盘时 CharKeypadView 自动隐藏，标准 IME 输入 |

---

## 7. 播放器设计（PlayerController 状态机）

### 7.1 ExoPlayer 装配

```kotlin
ExoPlayer.Builder(ctx)
  .setMediaSourceFactory(DefaultMediaSourceFactory())  // MKV/Matroska + MP4 + HLS 全含
  .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
  .setMinBufferMs(15_000).setMaxBufferMs(50_000)
  .setHandleAudioBecomingNoisy(true)
  .build()
// User-Agent：OkHttp 默认带 app UA（若 OpenList 校验 UA 则后端 headers 下发，§7.2）
PlayerView: controllerAutoShow=5s，controlsVisibleOnGesture；进度条可拖。
```

### 7.2 播放链路（严格顺序）

```
进入(videoId, resumeMs)
  → PlayUrlReq → {url, headers}
  → MediaItem.Builder().setUri(url).setHeaders(headers).build()
  → player.setMediaItem + setMediaSource? (MediaItem 即可) + prepare()
  → 监听 STATE_READY：若 resumeMs 有效(resume 条件 §3.1) → player.seekTo(resumeMs)
     toast 一次"从 01:22 继续播放"（仅首次续播时）
```

### 7.3 状态机

```
IDLE → PREPARING → PLAYING ⇄ PAUSED → ENDED ──(1.5s)──▶ 下一集(重走 §7.2, resumeMs=0)
  │      │           │                                   （无下一集 → 回详情页 + finished 上报）
  ├──→ ERROR ──(分类 §7.5)── 自动重试×1(4s) / 手动 / 不换集
  断点上报：PLAYING 每 10s(协程 tick) + PAUSE 时 + 离开页时 + ENDED 时(置满)
Activity onStop → if(isPlaying) pause() + 上报；onDestroy → release()
```

### 7.4 自动连播

- `seasons` 数据已持有（进播放器前从详情页带入选中 season+ep 的列表）。下一集 = 同季 `episodeNo+1` 存在的第一个，否则下一季第一个。
- 连播角标 1.5s（"即将播放下一集：第 7 集"），期间任意键可取消（回 PAUSED 保持当前集尾）。
- 一集自然播完 → 上报 `finished=1`（action=finish）+ 之后自动下一集（顺序：先 finish 再 next，防竞态：末集只 finish 不 next）。

### 7.5 错误分类（大文案，替代白屏/崩溃）

| 情形（PlaybackException） | 文案 | 自动行为 |
|---|---|---|
| IOException / 408 超时 | 网络或资源速度较慢 | 4s 后自动重试 ×1（同集同断点） |
| HTTP 4xx（403/404） | 链接不可用（可能失效） | 不重试；按钮：手动重试 / 返回剧集列表 |
| MediaCodec/不支持流错误码 | 该格式设备不支持（HEVC/10bit?），建议换 H264 源 | 不重试；按钮：返回剧集列表 |
| 其他 | 播放出错了 | 自动 ×1 → 手动 |

- 错误时保持黑屏+居中面板（不 release player 以保留重试上下文，重试成功即恢复）。

### 7.6 性能保护

- 单实例：`onNewIntent` 复用（同 Activity 内从"继续看"切集 = 同 Player 换 MediaItem，不重建）。
- 4K 软解防护：解析首帧后若 `codecName` 含 `CCodec` 且 fps/分辨率异常 → debug log 标记（供 M2 机型矩阵归因）；UI 不处理。

---

## 8. 断点与历史逻辑（客户端职责汇总）

| 事件 | 动作 |
|---|---|
| 播放中 | 每 10s `PUT progress {videoId, positionMs, durationMs}`（durationMs 来自 ready 后 format，带一次即可） |
| 暂停/KEY_EVENT/Activity onStop | 立即 PUT 一次（幂等） |
| 回剧集页/详情/首页 | 先 ensure 上报 ≥ 当前 position |
| 自然播完 | `POST finish {action:"finish"}` |
| 历史删除 | `POST finish {action:"remove"}` |
| App 完全退出 | onStop 已覆盖；进程被杀丢失 ≤10s（可接受） |
| 断点展示 | 首页 continueWatching（服务端排） / 卡片角标（服务端合并） / 集格子进度条（服务端合并）——**客户端零计算**，全部信任接口 |

---

## 9. 异常与降级基线

- 任何网络 API 失败：当前页 `ErrorStateView`（或局部失败如封面 404 → 占位图：标题首字 + 哈希底色（PhImageLoader））。
- 全局"网络连通"指示：首页顶部细条（网络断时黄条"网络已断开，展示缓存"）。
- Crash：`Thread.setDefaultUncaughtException` 收集 → 弹"应用异常，将重启" 1.5s → process kill；log 落 `data/local/hypermux_crash.log`（8KB ring）。
- ANR：不处理（M5 可选 iIG? 无 SDK，靠 crash log 归因）。

---

## 10. 包体

- 目标 < 30MB（leanback + media3 + retrofit，proguard release 开；media3/受 AAR consumer proguard 保护）。
- single ABI：arm64-v8a + armeabi-v7a 双份（ExoPlayer 无 NDK 依赖，纯 Java——实际 **全通用**，无需拆分）。
- 构建命令：`./gradlew :app:assembleRelease`，`signingConfigs.release` 走 `keystore.jks` + `local.properties` 注入密码（不入库）。

---

## 11. 机型兼容矩阵（M2 实测要求）

| 目标 | 说明 |
|---|---|
| 主力机型 ≥2 台 | 不同芯片平台（如 HiSilicon 3798M / 联发科 MT96xx 或 Amlogic），覆盖用户实际盒子 |
| 验证项 | MKV(H264 1080p) 播放/seek/断点；HLS 源； ply 焦点导航；Deep wireless 蓝牙键盘登录 |
| 失败归因 | 解码错误 → §7.5 第 3 类 + 机型清单记录；UI 问题 → 降字号/列数 |

---

## 12. 测试与验收（M2 Definition of Done）

**手测主线（真机）**
1. 首登：字符键盘输账密 → 进首页；杀进程重启 → token 有效免登；后台删用户 → 下次请求 401 → 自动回登录页。
2. 首页：池 Tab 切/卡片渲染/继续观看排序/离线黄条（断网 → 缓存 + 再联网恢复）。
3. 详情→播放器：MKV 直链播通；断点 seek（浮差 ≤5s）；±10s；暂停/继续；回退详情页再进→续播；自然播完自动下一集；末集播完回详情。
4. 历史：卡片进度/继续看/删除（remove 后不再出现）。
5. 错误：坏链（HTTP 404）→ 文案 + 不重试 + 返回剧集列表；慢网 → 自动重试成功恢复。
6. 稳定性：连续 30min 播放无 OOM/泄漏；遥控器全键遍历无卡死焦点（焦点未找到崩溃无发生）。

**单测（JVM）**
7. AuthRepo 过期判定（<300s buffer）、ApiResult 401 流。
8. 断点 tick 节奏（Mock currentTime：10s 边界、pause 即时）。
9. 集排序/next-ep 边界（跨季、末集、中间缺失集号）。
10. PhImageLoader 首字占位与缓存 key。

**跨文档一致性锁定（写给 QA）**
- 密码规则分两处：**建用户/重置密码**字符集 `[a-zA-Z0-9_.]{6,20}`；**登录**仅长度 6~20（后端 `LoginReq`），登录页不得校验字符集。以 30 契约 §4.7 为准。
- 时间全部毫秒；`videos[].duration` 是秒，`progress.*Ms` 是毫秒（打印 debug 对照）。

---

## 13. 后续可选项（M5+，非本期）

- Kiosk/开机自启（care 已验证方案）——若市场转老人版再回插
- 全池搜索、语音、推荐位、字幕、多清晰度
- App 内公告（`app_config` 表已预留）
- 冷启动 < 1.5s 性能预算（M5 压测纳入）
