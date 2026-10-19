# 48 · Android TV 端 App 详细设计（移植手机端 UI/交互）

> 隶属：[PLAN.md](../../../PLAN.md) · 版本 v1.0 · 对应里程碑：**T0–T6（TV 端 MVP）**
> 接口事实源：[30-api-contract.md](../../../docs/plans/30-api-contract.md) §3（`/api/app/*`，TV 端**全量复用、后端零改动**）
> 移植基线：兄弟工作区 `hyper-mux-android`（手机端 **0.1.11**，Kotlin 2.0.21 / Compose / Media3 1.7.1）
> **取代**：[40-app-detailed.md](../../../docs/plans/40-app-detailed.md)（v1.0，Leanback + XML Fragment 方案，从未实施，即日起作废）
> 范围说明：本产品为**普通用户版 TV App**（无 Kiosk、无开机自启、保留系统返回/最近任务），遥控器为主、兼容蓝牙/USB 键盘。

---

## 0. 背景与目标

**一句话**：把手机端（`hyper-mux-android`）已经打磨成熟的**视觉设计系统**与**内容/播放入口结构**整体搬到 Android TV，交互层由「触屏手势」改写为「遥控器焦点体系」，后端与 `/api/app/*` 接口**零改动**。

| 理由 | 说明 |
|---|---|
| 手机端已沉淀完整设计系统 | `ui/theme`（色板/间距/圆角/动效）+ `ui/common`（HyperCard/HyperButton/HyperChip/CoverImage…）为纯 Compose，无触屏耦合，可**近乎原样搬** |
| 数据层与播放器状态机与设备无关 | `data/`（ApiService/Repo/缓存）+ `player/PlayerController` 的续播/连播/断点/错误分类逻辑**同语义平移**，只在交互层做 TV 改写 |
| 契约已就绪 | `/api/app/*` 10 个接口 + probe + update，已由手机端/web 端验证；TV 是第三个消费者 |
| 与旧 TV 方案的差异 | 旧 `40-app-detailed.md` 用 Leanback + XML Fragment 自起一套；本方案**统一到手机端的 Compose 设计语言**，两代 UI 不割裂，维护成本减半 |

**本次不追求**：重做视觉（沿用手机端）、重做后端（零改动）、重做业务逻辑（复用仓库层）。

---

## 1. 现网基线：手机端设计系统盘点（移植基准）

> 以下条目**逐条来自手机端源码实测**，是 TV 端的移植母本。

### 1.1 设计令牌（`ui/theme/Theme.kt`）

| 类别 | 令牌 | 值（手机端） | TV 处理 |
|---|---|---|---|
| 品牌主色 | `primary` | `#00BE06`（成功/进度/焦点/选中统一绿） | 沿用 |
| 主色渐变 | `primaryGradient` / `progressGradient` | `#00BE06 → #37D67A` | 沿用 |
| 页面底 | `background` | `#0F1012` | 沿用 |
| 卡片底 | `surface` | `#181A1C` | 沿用 |
| 次级容器 | `surfaceVariant` | `#232628` | 沿用 |
| 正文色 | `onBackground`/`onSurface` | `#EDEFF2` | 沿用 |
| 弱文字 | `surfaceVariant` 上的 `onSurfaceVariant` / `text3` | `#9AA0A6` | 沿用 |
| 评分金 | `rating` | `#FFB400` | 沿用 |
| 错误红 | `error` | `#F2555A` | 沿用 |
| 发丝线 | `line` / `lineStrong` | `0x14FFFFFF` / `0x24FFFFFF` | 沿用 |
| 间距 | `Spacing` | `xs4 / sm8 / md12 / lg16 / xl20 / xxl28 / page14` | **TV 放大**：`page 48`（overscan 5%），其余成比例放大（见 §5） |
| 圆角 | `AppShapes` | `8 / 10 / 14 / 18 / 20`，胶囊 `RoundedCornerShape(50)` | 沿用 |
| 动效 | `Motion` | `FAST 150 / BASE 220 / SLOW 360`，`Ease = cubic(0.2,0,0,1)`，`Emphasized = cubic(0.05,0.7,0.1,1)` | 沿用；焦点动画新增 `Focus 180ms` |

### 1.2 内容架构（`ui/navigation/Route.kt` + `AppNavigation.kt`）

```
login
home（底部导航 0）
category/{categoryId}?categoryName=   （二级页）
search                                （二级页）
movie/{movieId}                       （二级页）
play/{movieId}/{videoId}              （播放页，自底部上滑）
history（底部导航 1）
me（底部导航 2）
downloads   /  downloads/{movieId}    （我的下载：一级 + 二级）
```

- 底部导航 3 项：**首页 / 历史 / 我的**（`BottomNavBar`：图标 23dp + 文字 11sp，选中品牌绿 + 微放大 + 上移 1dp）。
- 转场语言：底部 Tab 用「淡入 + 96% 缩放 + 11% 屏宽方向位移」；二级页用「整屏自右滑入 + 旧页压到 94%」卡片栈；播放页「自底部上滑 1/3 屏」。
- 防连点：所有 push 入口统一走 `NavComponents.kt` 的 `navigateXxx`（`launchSingleTop` / 同目的地同参数忽略）。

### 1.3 通用组件（`ui/common/`）

`Motion.kt`：`Modifier.bounceClick(pressedScale)`（按压回弹）、`rememberShimmerBrush()`、`ShimmerBlock`。
`Common.kt`：`SectionTitle`（4dp 渐变竖条）、`EmptyView`、`ErrorState`、`OfflineBanner`、`SkeletonPosterGrid`、`LetterPlaceholder`（首字哈希底），`CoverImage`（Coil3 + `{server}/img?u=` 代理 + 首字兜底）、`ProgressBar`、`LoadingDot`，及 `fmtDurationMs / remainText / progressPct / ratingText / formatBytes / fmtTime`。
`HyperUi.kt`：`HyperDialog`、`HyperTextField`、`HyperSearchField`、`HyperButton(Primary/Secondary/Ghost)`、`HyperCard`、`HyperChip`、`HyperIconButton`、`HyperBottomSheet`、`HyperSwitch`。
`MovieCardItem.kt`：`MovieCardItem`（16:9 或 2:3 信息流卡）、`ContinueWatchingCard`。

### 1.4 页面（`ui/*/*Screen.kt`）

| 页面 | 手机端结构要点 |
|---|---|
| 登录 | 渐变底 + 居中卡片：渐变 Logo → 用户名框 → 密码框 → 登录按钮 → 分隔线 → 服务器脚注行（公开/自定义） |
| 首页 | 顶栏（Logo + 常驻搜索胶囊 + 历史图标）→ 频道 Tab（粘性、选中加粗+下划线生长）→ Banner 轮播（16:9 自动 4.2s + 指示点）→ 继续观看横幅（150dp 横版剧照）→ 分区标题 + **双列信息流网格** |
| 分类 | TopAppBar（返回 + 标题）→ 整宽胶囊搜索条 → **3 列**海报网格分页 |
| 搜索 | TopAppBar 内嵌搜索框（自动聚焦 + 300ms 防抖）→ 搜索历史 chips（FlowRow）→ 3 列结果网格 |
| 详情 | 沉浸头图（16:9，随滚动渐显顶栏）→ 简介+标签卡 → 渐变主按钮（"从第 N 集继续"）→ 季 Tab + **5 列**方形集数格（五态） |
| 播放 | 默认竖屏 + 显式全屏：顶栏 + 16:9 播放区（自绘控制浮层 + 手势层）+ 影片信息 + 季 Tab + 选集网格；横屏沉浸 + 锁屏 + 右侧选集抽屉 + 倍速小弹窗 |
| 历史 | 每行 `HyperCard`（74dp 竖版封面 + 标题/集号 + 4dp 进度条 + 剩余时长 + 标记看完/移除两枚图标按钮） |
| 我的 | `UserCard` + 分组（外观/播放/缓存/服务器/应用更新/存储/登录信息）+ `InfoRow` 48dp + 主题选择器 + `HyperSwitch` + 退出登录 |
| 下载 | `MyDownloadsScreen`（存储条 + 进行中任务 + 3 列海报网格）+ `DownloadDetailScreen`（集级管理） |

### 1.5 播放与数据（`player/`、`data/`）

- `PlayerController`：`NextRenderersFactory`（FFmpeg 软解兜底）+ `HardwareOnlyCodecSelector` + 解码回退 + `DefaultLoadControl(15s,50s,2.5s,5s)` + ±10s seek；网络/本地文件双源。
- 播放状态机：`IDLE→PREPARING→PLAYING⇄PAUSED→ENDED→(1.5s)下一集`；错误分类（网络/HTTP4xx/解码/其他）自动重试 ×1。
- 断点：播放中 10s 节流、暂停/退后台/离开页即时上报、`position<3s` 不上报；续播阈值 `positionMs>30s 且 < duration-30s`；离线写 `PendingProgressStore` 待补报。
- 数据层：`ApiService`（Retrofit，`R<T>{code,msg,data}`）→ `SafeApiCall` → `AuthRepo/ContentRepo/PlaybackRepo/SettingsRepo`；`AuthInterceptor`（Bearer + 401 失效）、`ServerUrlInterceptor`（热切 baseUrl）、`NetworkMonitor`（离线黄条）、`CacheStore`（home/列表第 1 页 JSON 降级）。

---

## 2. 手机 → TV 差异矩阵（移植决策依据）

| 维度 | 手机端 | TV 端 | 处理策略 |
|---|---|---|---|
| 输入 | 触屏（tap/双击/滑动/长按） | **D-pad 遥控器**（方向/OK/BACK/MENU/媒体键）+ 蓝牙键盘 | **重写交互层**（§6/§9），去掉全部手势 |
| 默认焦点 | 无（首次点击即激活） | **必须有默认焦点**，且任何页面/弹窗进入即有焦点 | 新增焦点基建（§6） |
| 反馈 | 按压回弹 `bounceClick` | **焦点放大 + 描边**（无按压态） | 新增 `tvClickable`，`bounceClick` 不适用 |
| 屏幕 | 竖屏为主，竖横切换 | **永远横屏**、10 英尺观看 | 字号/间距整体放大（§5），锁定 landscape |
| 导航 | 底部 Tab | 底部导航在 TV 反直觉 | 改**左侧导航栏**（首页/历史/我的/搜索）（§7） |
| 内容流 | 双列/三列信息流网格 | 电视以**横向 rail（行）**为主 | 首页分区由"双列网格"改"每分区一行 `TvLazyRow`"（§8.2） |
| 卡片密度 | 一屏 4~6 张 | 一屏 5~7 张，卡片更大 | 列数/卡宽放大（§5/§8.3） |
| 文本输入 | 软键盘 IME | 遥控器无软键盘，逐字选择极慢 | 登录/搜索提供**自绘字符键盘**（§8.1/§8.4），检测到硬件键盘时退化为 IME |
| 长按 | 长按=缓存/打开影片 | 长按 `DPAD_CENTER` 支持但语义弱 | 用 **MENU 键**或独立的"更多/缓存"按钮替代长按（§8.5） |
| 音量/亮度 | 半屏手势调节 | 系统音量由遥控器控制 | **删除手势层**；可选 `KEYCODE_VOLUME_*` 直接调媒体流（§9.5） |
| 下载 | 显式下载 + 离线播放（`MediaCacheManager`） | 盒子存储小、多为内网固定 | 保留但降优先级（T5），存储位置考虑外置/USB（§8.9） |
| 服务器 | 运行时配置（公开/自定义） | 盒子通常局域网固定 | 保留双模式；构建期可预置地址（§11） |
| 自更新 | `REQUEST_INSTALL_PACKAGES` + FileProvider | TV 安装 UI 不同，但机制一致 | 保留，验收覆盖 TV 安装流程（§8.10） |
| 启动入口 | `LAUNCHER` | 需 `LEANBACK_LAUNCHER` + `android:banner` | manifest 增补（§4.2） |

---

## 3. 移植总策略：三层划分

```
        ┌───────────────────────────────────────────────┐
        │  L3  交互层（TV 新写）                          │
        │      焦点基建 / D-pad 键位 / 左侧导航 / 字符键盘  │
        ├───────────────────────────────────────────────┤
        │  L2  表现层（改造平移）                          │
        │      theme/ 原样搬；common/ 组件加 focus 态；    │
        │      *Screen.kt 逐页重排为 TV 布局               │
        ├───────────────────────────────────────────────┤
        │  L1  数据/播放层（原样搬）                       │
        │      data/ + player/ 逻辑零改动（仅去掉触屏耦合） │
        └───────────────────────────────────────────────┘
```

| 层 | 处置 | 目录/文件 |
|---|---|---|
| **原样搬**（复制后少量命名空间调整） | 整目录平移 | `config/AppConfig.kt`、`data/**`（api/model/repo/cache/update）、`player/PlayerController.kt`、`player/PlayerViewModel.kt`、`player/PlayerIcons.kt`、`ui/theme/Theme.kt`、`ui/common/Motion.kt`、`ui/common/Common.kt`、`ui/common/HyperUi.kt` |
| **改造平移** | 平移后加焦点语义/放大规格 | `ui/common/MovieCardItem.kt`、`ui/navigation/NavComponents.kt`（Tab→左侧导航）、各 `*Screen.kt` |
| **TV 新写** | 无手机等价物 | `ui/tv/`：焦点基建、左侧导航、字符键盘、Banner 焦点化、播放器 D-pad 控制层 |
| **删除** | 触屏专用，不移植 | 播放页 `GestureLayer`/`GestureOsdOverlay`、`bounceLongClick`、`combinedClickable` 的长按分支、横竖屏切换（`fullscreen` 状态与方向锁定） |

---

## 4. 技术栈与工程结构

### 4.1 技术栈（沿用手机端版本，新增 TV 依赖）

| 项 | 选型 | 版本 | 与手机端差异 |
|---|---|---|---|
| 语言/构建 | Kotlin + Gradle KTS | 2.0.21 / AGP 8.7.3 | 一致 |
| SDK | compileSdk 35 / minSdk 24 / targetSdk 35 | — | 一致（TV 盒子多为 7.0~12） |
| UI | **Jetpack Compose + Material 3** | BOM 2024.12.01 | 一致 |
| **TV 焦点组件** | **`androidx.tv:tv-foundation`** | **1.0.0-alpha11**（锁定，见下） | **新增**（`TvLazyRow/TvLazyColumn/TvLazyVerticalGrid` 焦点自动滚动） |
| **TV 文本输入（可选）** | `androidx.tv:tv-material` | 1.0.0 | **可选新增**（仅在字符键盘/SimpleKeyboard 上省事时用；默认自绘） |
| 播放 | Media3 ExoPlayer + media3-ui | 1.7.1 | 一致 |
| **软解兜底** | `nextlib-media3ext` | 1.7.1-0.9.0 | 一致（TV 盒子 HEVC/DTS 硬解缺失更常见，**必须保留**） |
| 网络 | Retrofit2 + OkHttp + converter-gson | 2.11.0 / 4.12.0 | 一致 |
| 图片 | Coil 3（+ `coil-network-okhttp`） | 3.1.0 | 一致 |
| 导航 | Navigation Compose | 2.8.4 | 一致 |
| DI | Hilt | 2.52 | 一致 |
| 持久化 | DataStore Preferences | 1.1.1 | 一致 |
| 包名/工程 | `com.hypermux.tv`，工作区 `hyper-mux-android-tv/` | versionName 0.1.0 | 与手机端 `com.hypermux.mobile` 区分 |

### 4.2 `app/build.gradle.kts` 与 AndroidManifest 要点

```kotlin
android {
  namespace = "com.hypermux.tv"; compileSdk = 35
  defaultConfig {
    minSdk = 24; targetSdk = 35
    applicationId = "com.hypermux.tv"; versionCode = 1; versionName = "0.1.0"
    buildConfigField("String", "API_BASE",
      "\"${(rootProject.findProperty("hypermuxApiBase") ?: "")}\"")
  }
  buildFeatures { compose = true; buildConfig = true }
  // debug 允许明文 http；release 默认关闭（-PhypermuxCleartext 控制），与手机端一致
}
dependencies {
  implementation("androidx.tv:tv-foundation:1.0.0-alpha11")   // TvLazyRow/TvLazyColumn/TvLazyVerticalGrid
  // 其余与手机端 libs.versions.toml 完全一致（compose/media3/nextlib/retrofit/coil3/hilt/datastore）
}
```

> **版本锁定说明（T0 实测，2026-10-17 修正）**：`tv-foundation` 的 lazy list/grid 整包
> （`androidx.tv.foundation.lazy.*`、`PivotOffsets`、`TvLazyRow/TvLazyColumn/TvLazyVerticalGrid`）
> 在 **1.0.0-alpha12 与 1.0.0 稳定版中已被移除**——这两个版本的 AAR 只剩 `androidx.tv.foundation.text`
> （8.9KB，仅 `TvImeOptions`），Compose 基线也迁到了 1.8+，与本项目的 BOM 2024.12.01（Compose 1.7.6）不再配套。
> **最后一个可用版本是 1.0.0-alpha11**（compose 基线 1.7.6，与 BOM 严格对齐），因此本项目锁定 alpha11。
> 校验方式（可复现）：
>
> ```powershell
> # 解包 AAR 看 classes.jar 里有没有 lazy 包
> # alpha11 → 有 androidx/tv/foundation/lazy/list/LazyDslKt.class ✅
> # alpha12 / 1.0.0 → 只有 androidx/tv/foundation/text/* ❌
> ```
>
> 若将来升级 `composeBom` 到 Compose 1.8+，才能考虑把 tv-foundation 升到 1.0.0 稳定版；
> **在此之前绝不可单独升这一项**（会让 Compose 运行时被抬到 1.8，与 BOM 约束打架）。

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<!-- 注意：不再需要 MODIFY_AUDIO_SETTINGS（音量手势已删，见 §9.5） -->

<uses-feature android:name="android.software.leanback" android:required="false"/>
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>

<application android:label="@string/app_name" android:banner="@drawable/tv_banner"
             android:icon="@drawable/ic_launcher" android:theme="@style/Theme.HyperMux">
  <activity android:name=".ui.MainActivity"
      android:exported="true" android:launchMode="singleTask"
      android:screenOrientation="landscape"
      android:configChanges="keyboard|keyboardHidden|navigation|screenSize|smallestScreenSize|screenLayout|orientation">
    <intent-filter>
      <action android:name="android.intent.action.MAIN"/>
      <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
  </activity>
</application>
```

- `leanback` 声明 `required=false`：兼容未认证盒子（Fire TV / 普通 Android 盒子侧装）与开发机。
- 同时声明 `LEANBACK_LAUNCHER` 与 `LAUNCHER`：确保在 TV 主屏与普通 Launcher 都能显示。
- 新增资源：`res/drawable/tv_banner.xml`（320×180 骨架 banner）。

### 4.3 工程目录

```
hyper-mux-android-tv/
├─ settings.gradle.kts / gradle.properties / local.properties（hypermuxApiBase 可覆盖）
├─ gradle/libs.versions.toml
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/hypermux/tv/
      │  ├─ TvApp.kt                     # Application（Hilt/Coil/会话预载/崩溃兜底），同 HyperMuxApp
      │  ├─ config/AppConfig.kt          # 阈值常量（§3.1）+ TV 字号/安全区规格
      │  ├─ data/                        # ★ 从手机端原样搬（包名 com.hypermux.mobile → com.hypermux.tv）
      │  │  ├─ api/  model/  repo/  cache/  update/
      │  ├─ di/AppModule.kt              # 同手机端
      │  ├─ ui/
      │  │  ├─ tv/                       # ★ TV 专属基建（新写）
      │  │  │  ├─ Focus.kt               # tvClickable / tvFocusable / rememberFocusScale
      │  │  │  ├─ TvNavRail.kt           # 左侧导航栏
      │  │  │  ├─ CharKeypad.kt          # 自绘字符键盘（遥控器输入）
      │  │  │  ├─ TvScreen.kt            # 页面骨架（安全区 + 顶栏 + 焦点容器）
      │  │  │  └─ KeyBindings.kt         # D-pad/媒体键 → 语义动作
      │  │  ├─ theme/  common/           # ★ 从手机端搬（组件加 focus 态）
      │  │  ├─ navigation/               # 路由 + 左侧导航 + 焦点恢复
      │  │  ├─ login/ home/ category/ search/ movie/ history/ me/ downloads/
      │  │  └─ player/                   # D-pad 控制层（新写）
      │  └─ res/                          # tv_banner / 图标 / 字符串 / 主题
```

---

## 5. 设计系统移植（10-foot UI 规格）

### 5.1 字号放大（手机 → TV）

| 语义 | 手机 | TV（建议） |
|---|---|---|
| 页面/头图标题 | 26sp | **40sp** |
| 分区标题 | 16.5~18sp | **28sp** |
| 卡片标题 | 15.5sp | **22sp** |
| 按钮/正文 | 14sp | **20sp** |
| 辅助/元数据 | 12.5sp | **18sp** |
| 角标（集数/评分） | 10.5~11sp | **16sp** |
| 导航项 | 11sp | **18sp** |

> 统一放大约 **1.45~1.55×**，但**不是**机械 `fontScale`：TV 用固定 `sp` 规格（在 `theme/TvType.kt` 里集中定义），避免低分辨率盒子文字过大、4K 盒过小。**不做 `fontScale` 缩放**，只按 1080p 基准定值。

### 5.2 间距与安全区

- `Spacing.page`：手机 14dp → TV **48dp**（1080p 下约为 overscan 5% 安全区）。
- 行间距（rail 之间）：手机 10~14dp → TV **32dp**；rail 内卡间距：手机 10dp → TV **24dp**。
- 焦点描边预留：卡片外需留出放大余量（焦点 scale 1.06 + 3dp 描边），rail 的 `contentPadding` 需含该余量，避免焦点卡被裁。

### 5.3 焦点态（TV 独有的视觉语言）

> 手机端 `bounceClick`（按压缩放）**不适用于 TV**（遥控器没有"按住"的连续态），替换为**焦点态**：

```
非焦点：与手机静态一致（可略降透明度 0.92）
焦点：   1) 缩放至 1.06（180ms，Motion.Ease）
          2) 3dp 品牌绿描边（RoundedCornerShape 随卡片圆角）
          3) elevation/阴影提升（Card 类）
          4) 可选：卡片内标题由弱色转亮色
```

- 统一实现 `Modifier.tvClickable(onClick, shape, focusedScale=1.06f)`，内部用 `onFocusChanged` + `animateFloatAsState` + `drawBehind` 描边，替换手机端 `bounceClick`。
- 焦点描边色 = `MaterialTheme.colorScheme.primary`；描边宽度 3dp（1080p 下清晰可见）。

### 5.4 动效保留与裁剪

- **保留**：转场（Tab 淡入/二级页卡片栈）、下划线生长、进度条、`rememberShimmerBrush` 骨架、`Crossfade`（封面）。
- **裁剪**：`bounceClick` 按压回弹（无按压态）、播放页 OSD 手势浮层、圆形揭示（频道 Tab 切换的 `circlePath` 裁剪动画——TV 遥控器切换频道时首屏命中率低，改为简单的 `fadeIn`，降低实现与性能风险）。
- **新增**：焦点放大 + 描边；左侧导航选中态（品牌绿竖条 + 高亮）。

---

## 6. 遥控器焦点体系（核心基建）

> 这是 TV 版**风险最高**的部分，也是与手机端最大的分水岭。目标：**任何页面进入即有唯一焦点、任何方向键都有确定落点、返回后焦点回到原位、列表滚动跟随焦点。**

### 6.1 三项基础能力

| 能力 | 实现 |
|---|---|
| **进入即聚焦** | 每个 `*Screen` 持有目标元素的 `FocusRequester`；`LaunchedEffect(数据就绪)` 后 `requestFocus()`。加载中/错误态把焦点给"重试"按钮，**绝不出现无焦点状态** |
| **焦点恢复** | 容器加 `Modifier.focusRestorer()`；配合 `rememberSaveable` 保存"上次焦点项 key"，返回/重组后恢复到同一卡片（对齐手机端"滚动位/焦点项保留"） |
| **列表滚动跟随** | 横向 rail 用 **`TvLazyRow`**、纵向网格用 `TvLazyColumn`/`TvLazyVerticalGrid`（`tv-foundation`），焦点项自动滚入可视区并保留前置留白（纯 `LazyRow` 无此能力） |

### 6.2 焦点遍历规则（`focusProperties`）

| 场景 | 规则 |
|---|---|
| 左侧导航 → 内容区 | 导航项 `RIGHT` → 内容区当前 rail 首个可聚焦卡 |
| rail ↔ rail（纵向） | `UP/DOWN` 自然切换行；行首 `LEFT` 到导航栏，行尾 `RIGHT` 溢出到"更多"按钮 |
| 详情页 左(封面信息) ↔ 右/下(按钮/剧集) | 显式 `focusProperties` 指定跨区落点 |
| 弹窗 | 打开即聚焦主按钮，焦点**困在弹窗内**（`Modifier.focusGroup()` + 显式 `focusProperties` 边界），关闭后归还给触发元素 |
| 空态/错误态 | 焦点给"重试"；无重试则给返回/导航 |
| 播放页 | 控制条显隐不影响系统焦点（控制条用自定义 `keyEvent` 处理，不用 Compose 焦点，避免与 seek 冲突 —— 见 §9） |

### 6.3 键位映射（全局）

| 键 | 全局语义 |
|---|---|
| `DPAD_UP/DOWN/LEFT/RIGHT` | 焦点移动（列表内滚动/换行） |
| `DPAD_CENTER` / `ENTER` | 执行（等价手机 tap） |
| `BACK` | 上级页；（首页）忽略；播放页按 §9 收控制条 |
| `MENU` | 打开"更多"（缓存放这里，替代手机长按） |
| `MEDIA_PLAY_PAUSE / MEDIA_NEXT / MEDIA_PREVIOUS / MEDIA_REWIND / MEDIA_FAST_FORWARD` | 播放页控制（§9.4） |
| `KEYCODE_DPAD_CENTER` 长按（`onKeyEvent` repeatCount） | 播放页：倍速菜单（替代手机长按 2x） |

### 6.4 长按的 TV 替代方案

- 手机"长按集数格 = 缓存本集"→ TV 改为：**集数格 `MENU` 键 / 聚焦时右下角出现"缓存"操作按钮**（遥控器长按体验差且不直观）。
- 手机"长按历史行 = 打开影片"→ TV 改为：行内两个操作按钮（**继续看 / 详情**）横向并列，`DPAD` 直接切换。
- 手机 `combinedClickable(onLongClick)` 全部**删除**。

---

## 7. 信息架构与导航（TV 重构）

### 7.1 手机 → TV 导航映射

| 手机端 | TV 端 |
|---|---|
| 底部导航（首页/历史/我的） | **左侧导航栏**（首页/历史/我的 + 搜索入口），宽约 240dp，选中品牌绿竖条 + 高亮文字 |
| 顶栏历史图标 | 归入左侧导航"历史" |
| 顶栏搜索胶囊 | 左侧导航"搜索"项（或首页顶栏保留一个聚焦搜索框） |
| 频道 Tab（首页内） | **保留**为内容区顶部横向 Tab 行（`TvLazyRow` of chips），选中下划线生长 |
| 二级页（分类/搜索/详情/下载） | 保留为二级页；二级页顶栏用"返回按钮 + 标题"，返回按钮可聚焦 |

### 7.2 路由（沿用手机端，命名空间改 `com.hypermux.tv`）

```kotlin
object Route {
    const val Login = "login"
    const val Home = "home"
    const val Category = "category/{categoryId}"
    const val Search = "search"
    const val Movie = "movie/{movieId}"
    const val Play = "play/{movieId}/{videoId}"
    const val History = "history"
    const val Me = "me"
    const val Downloads = "downloads"
    const val DownloadDetail = "downloads/{movieId}"
}
```

- 防连点封装（`navigateOnce`/`navigatePlay`/`navigateTab`）**原样保留**——TV 遥控器连按更常见，这层防护更必要。

### 7.3 返回行为

| 起始页 | BACK 行为 |
|---|---|
| 登录页 | 忽略（可聚焦内元素） |
| 首页 | 忽略（不做双击退出——遥控器误触代价高；保留系统返回/最近任务） |
| 分类/搜索/详情/下载 | 回上级（焦点恢复到来源卡片） |
| 历史/我的 | 回首页（左侧导航切到"首页"） |
| 播放器 | 先收控制条 → 再按回上级（先即时上报断点） |

---

## 8. 页面规格（逐页）

### 8.1 登录页

```
┌── 渐变底（同手机端：#00BE06 12% 从顶部晕开 → 背景色） ─────────────┐
│                                                                    │
│        ┌──── 居中卡片（圆角 18dp + 1dp 描边 + 阴影）────┐          │
│        │   [渐变 Logo 64dp]  HyperMux (32sp)             │          │
│        │   影视聚合播放 (18sp 弱色)                       │          │
│        │   ┌ 用户名框（聚焦 3dp 绿描边）────────┐         │          │
│        │   ┌ 密码框（••••）────────────────────┐         │          │
│        │   [ 登 录 ]（渐变胶囊，168sp 宽）               │          │
│        │   ──────────────                                │          │
│        │   ● 公开服务器          使用自定义服务器 ›      │          │
│        └──────────────────────────────────────────────┘          │
│                                                                    │
│   [自绘字符键盘（无硬件键盘时显示）]                                │
│     q w e r t y u i o p                                            │
│     a s d f g h j k l                                              │
│     z x c v b n m                                                  │
│     [⇧ 大小写] [⌫ 删除] [123 符号] [✓ OK]                          │
└────────────────────────────────────────────────────────────────────┘
```

- **焦点流**：用户名框 → 键盘（字符格，`DPAD` 网格移动）→ OK 提交 → 密码框 → 登录按钮 → 服务器行。
- **字符键盘**（新写 `ui/tv/CharKeypad.kt`）：字母页/数字符号页两档；`⇧` 切大小写（密码默认大写关）；`⌫` 删除；`✓OK` = 焦点在输入框时提交或跳到下一框。检测到硬件键盘（`KeyCharacterMap` 有 `ALPHA` 或收到 `KEYCODE_A..Z`）时自动隐藏自绘键盘，退回标准 IME（与手机端并存的策略一致）。
- 校验规则**与手机端一致**：用户名 `^[a-zA-Z0-9_]{3,32}$`；**密码仅校验长度 6~20，不校验字符集**（初始管理员密码 `Admin@1234` 含 `@`）。
- 服务器行：公开模式显示"公开服务器"（不露地址），自定义显示 `host:port`；点开 `ServerConfigDialog`（平移手机端，改为焦点版：公开/自定义单选 → 地址输入 → 测试连接 → 保存）。

### 8.2 首页

```
┌────┬──────────────────────────────────────────────────────────────────┐
│ 左 │  [频道 Tab 行]  首页 | 电视剧 | 电影 | 综艺 | 动漫 | 纪录片 …     │
│ 侧 │ ────────────────────────────────────────────────────────────────  │
│ 导 │  ┌ Banner 轮播（16:9，自动 4.2s + 指示点，整宽）──────────────┐  │
│ 航 │  │  剧照 + 左渐变 + 片名/评分/年份/地区                        │  │
│    │  └──────────────────────────────────────────────────────────┘  │
│ ▸首页│  ┌ 继续观看（横版剧照 180dp + 进度 + "继续播放"按钮）──────┐  │
│  历史│  └──────────────────────────────────────────────────────────┘  │
│  我的│  ▎电视剧                                          [更多 ›]     │
│  搜索│  [卡][卡][卡][卡][卡][卡] → 横向 rail（TvLazyRow）              │
│    │  ▎电影                                            [更多 ›]       │
│    │  [卡][卡][卡][卡][卡][卡] →                                    │
└────┴──────────────────────────────────────────────────────────────────┘
```

- **内容形态**：手机端"分区标题 + 双列网格"→ TV 改为"**分区标题 + 横向 rail**"（每个 `home.sections[i]` 一行）。
- 卡片：有横版剧照用 16:9（爱奇艺大卡），否则 2:3 竖版；卡宽 TV ~ **240dp**（16:9）/ ~**160dp**（2:3）；角标（`全N集`/`看到第N集`/`★评分`）+ 底部进度条沿用 `MovieCardItem` 结构。
- 焦点流：左侧导航（首页）→ 频道 Tab → Banner → 继续观看 → 各 rail。`UP/DOWN` 在 rail 间移动并保持列位；`LEFT` 到导航；行尾 `RIGHT` 到"更多"。
- 频道 Tab 切换：`fadeIn`（不做圆形揭示，§5.4）；切换后焦点给该 Tab 下方首个 rail 首卡。
- Banner 轮播：焦点位于 Banner 上时暂停自动轮播（避免抢焦）——与手机端"自动轮播不打断"改为"聚焦即暂停"。

### 8.3 分类列表页

- 顶栏（返回按钮可聚焦 + 标题）→ 整宽胶囊搜索条 → **纵向网格（`TvLazyVerticalGrid`，5~6 列）**分页（`page/size`，`total>page*size` 触底加载）。
- 与手机端一致：分页大小 30、`distinctBy{id}` 兜底、刷新期间禁翻页（沿用手机端第三轮加固）。
- 焦点：进入聚焦搜索条或首卡（决策 D5）；网格内 `DPAD` 四向移动，行末 `RIGHT` 自然换行。

### 8.4 搜索页

- 顶栏内嵌搜索框（聚焦态 + 300ms 防抖）→ 搜索历史 chips（`TvLazyRow` 可换行用 `FlowRow`，但 TV 上单行横滑更常见）→ **5~6 列结果网格**。
- 文本输入走 §8.1 的字符键盘；键盘与结果网格**分屏**：键盘占下半屏，结果即时刷新在上半屏。
- 空态 `没有找到「{kw}」相关影片`；搜索历史为空显示 `暂无搜索历史`。

### 8.5 影片详情页

```
┌──────────────────────────────────────────────────────────────────────┐
│ [返回]  影片标题                                                       │
├──────────────────┬───────────────────────────────────────────────────┤
│  海报（竖版 2:3   │  标题 (40sp)  原名 (弱色)                          │
│  或横版头图）      │  ★9.4  2023  中国  电视剧                          │
│  16:9 横版头图优先 │  [类型][地区][自定义标签 chips]                    │
│                  │  简介（默认 3 行 + "展开 ↓"，焦点可展开）            │
│                  │  [▶ 从第 6 集继续]（渐变胶囊）  [缓存]（次级）      │
├──────────────────┴───────────────────────────────────────────────────┤
│  ▎选集                                        [缓存本季]              │
│  [第1季][第2季]  ← 季 chips（多季时）                                   │
│  [1] [2] [3] [4] [5] [6] [7] [8] [9] [10] …  ← 方形集数格（8~10 列）  │
│   （当前集绿底绿框；已看完灰；看过一部分底部细进度线；MENU=缓存本集）    │
└──────────────────────────────────────────────────────────────────────┘
```

- 布局改**左右分栏**（手机端是纵向堆叠的沉浸头图）：左海报、右元数据 + 主按钮；下方通栏的选集区。
- 剧集格：手机端 5 列方形格 → TV **8~10 列**；五态（当前集/已看完/看过一部分/未看/已缓存）沿用 `EpisodeItem` 的判定逻辑，配色沿用。
- **"当前集"唯一来源**铁律照搬：只认详情断点（`detail.progress`）那一集，**不得**用每格 `video.progress` 推断（手机端已修过的 bug，详见源码注释）。
- 主按钮文案：`continueVideo != null` → "从第 N 集继续"；否则"播放第 1 集"；电影 → "立即播放"。
- 长按缓存 → 改集数格 `MENU` 键 / 聚焦格右下角"缓存"微按钮，打开 `CacheSheet`（焦点版底部/侧边面板，多选 + 全选 + `缓存(N)`）。

### 8.6 播放器（详见 §9）

TV 端**永远全屏横屏**，无竖屏/全屏切换。

### 8.7 历史页

- 左侧导航"历史" → 列表：每行 `HyperCard`（竖版封面 96dp + 标题 + `S{n}·第{m}集` + 4dp 进度条 + 剩余时长）→ 行内两枚操作按钮（**继续看 / 详情**）+ 右侧（**标记看完 / 移除**）。
- 焦点：行内多按钮横向 `DPAD` 切换；移除弹 `HyperDialog`（焦点版，confirm 默认聚焦）。
- 空态 `还没有播放记录`；离线黄条沿用。

### 8.8 我的页

- 左侧导航"我的" → 纵向滚动的 `HyperCard` 分组（外观/播放/缓存/服务器/应用更新/存储/登录信息），`InfoRow` 行高 TV 放大到 **64dp**。
- 主题选择器：TV 一般固定深色（大屏深色更护眼、与观影场景一致）→ **默认锁定深色，隐藏"浅色/跟随系统"**（决策 D6）；如保留则三档单选。
- 开关项（记忆倍速/仅 Wi-Fi 下载）：`HyperSwitch` 仅作指示，整行可点（沿用手机端"避免一次点击被切两次"的处理）。
- 退出登录：`HyperDialog` 确认。

### 8.9 我的下载（T5，可选里程碑）

- `MyDownloadsScreen`（存储条 + 进行中任务 + 5 列海报网格）+ `DownloadDetailScreen`（集级管理）平移。
- **TV 差异**：盒子内置存储通常很小 → 下载目录优先**外置存储/USB**（`getExternalFilesDirs` 取第一个可用外部目录）；若无可写外置存储则该入口隐藏并提示"设备不支持离线下载"。
- 若 T5 人力紧张，可整体降级为"仅在线播放"（不提供下载），在决策项标注。

### 8.10 App 自更新

- 平移 `UpdateViewModel / UpdateDialog / UpdateDownloadService / FileProvider`（后台 `dataSync` 前台服务 + SHA-256 校验 + 通知）。
- **TV 差异**：安装 UI 不同（TV 的 `ACTION_VIEW` 安装器显示为全屏确认），验收需覆盖；`REQUEST_INSTALL_PACKAGES` 授权跳转在 TV 上可能落在系统设置的一级层级，文案指引需更明确。

---

## 9. 播放器 TV 化（`ui/player/`）

### 9.1 装配（不变）

`PlayerController` **原样搬**：`NextRenderersFactory`（FFmpeg 兜底）+ `HardwareOnlyCodecSelector` + 解码回退 + `DefaultLoadControl(15s,50s,2.5s,5s)` + ±10s seek + 音频变噪自动暂停。

### 9.2 布局（TV 专属）

```
┌──────────────────────────────────── 全屏视频画面 ────────────────────────────────┐
│  顶部信息栏（UP 唤出，5s 自动收）：← 返回   片名 · S1 第6集           ★9.4      │
│                                                                                  │
│                             （画面）                                              │
│                                                                                  │
│  中央浮层：缓冲指示器（自绘绿弧，350ms 防抖，复用手机端 HyperSpinner）             │
│  底部控制条（OK 唤出，4s 自动收）：                                                │
│   [◀◀10s] [▶/⏸] [10s▶▶]   00:32 ──●────────── 45:10    [倍速] [选集] [下一集]   │
│  选集侧栏（DOWN / 焦点到"选集"唤出，右侧滑入，集数网格）                          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **默认全屏横屏**，去掉手机端的"竖屏 + 显式全屏"两态与 `GestureLayer`/`GestureOsdOverlay`/锁屏按钮（TV 无遮挡风险，锁屏价值低）。
- 控制条默认隐藏；`OK` 唤出（或播放页进入 5s 内显示一次）。

### 9.3 状态机与断点（不变）

`IDLE→PREPARING→PLAYING⇄PAUSED→ENDED→(1.5s)下一集`；断点 10s 节流 + 暂停/离开页即时 + `position<3s` 跳过；续播阈值 30s；离线待补报。**逻辑零改动**（`PlayerViewModel` 平移）。

### 9.4 D-pad 键位（播放中，控制条隐藏/显示两态）

| 键 | 控制条隐藏时 | 控制条显示时 |
|---|---|---|
| `OK` | 唤出控制条 | 执行当前焦点按钮 |
| `LEFT` / `RIGHT` | **seek ∓10s**（长按连跳，重复键） | 焦点在按钮间移动；焦点在进度条上时 ∓10s 并预览气泡 |
| `UP` | 唤出**顶部信息栏**（片名/集号/评分） | 焦点上移（到进度条/顶部返回） |
| `DOWN` | 唤出**选集侧栏** | 焦点下移到"选集/下一集" |
| `BACK` | 收起控制条/侧栏 | 收起后退出播放页（**先即时上报断点**） |
| `MENU` | 打开**倍速菜单**（1.0/1.25/1.5/2.0/2.5/3.0） | 同上 |
| `DPAD_CENTER` 长按 | 倍速菜单（与手机长按 2x 等价语义，但改为菜单选择） | — |

- 实现：播放页用 `Modifier.onPreviewKeyEvent` 顶层拦截 `DPAD_*`/媒体键（**不建 Compose 焦点树**），自行做"seek 重复键节流"（按 `KeyEvent.repeatCount`，每次 ∓10s，节流 ~200ms），避免焦点与 seek 语义打架。
- 媒体键：`MEDIA_PLAY_PAUSE` ↔ 播放/暂停；`MEDIA_NEXT/PREVIOUS` ↔ 上下集（走 `vm.loadPlayUrl`）；`MEDIA_FAST_FORWARD/REWIND` ↔ ∓10s。
- 屏幕常亮：播放页显式 `FLAG_KEEP_SCREEN_ON`（TV 场景必要时长观影不灭屏）。

### 9.5 音量与亮度

- **删除**手机端的亮度/音量滑动手势（TV 由遥控器/系统音量控制）。
- 可选：拦截 `KEYCODE_VOLUME_UP/DOWN` → 调 `AudioManager.STREAM_MUSIC`（若盒子遥控器不接管媒体音量）；实现时 `runCatching` 兜底，失败只记日志（沿用手机端"绝不闪退"原则）。**无需 `MODIFY_AUDIO_SETTINGS`**（TV 场景默认不接管）。

### 9.6 自动连播与错误分类（不变）

- 连播角标 1.5s（"即将播放下一集：第 7 集"），期间任意键可取消；先 `finish` 再 `next`；末集只 `finish` 不 `next`。
- 错误分类（网络/HTTP4xx/解码/其他）+ 自动重试 ×1（4s）+ 首帧看门狗 + 软解提示，**全部平移**。

---

## 10. 组件适配清单（手机组件 → TV 组件）

| 手机端组件 | TV 处置 | 改造点 |
|---|---|---|
| `bounceClick(pressedScale)` | **新增** `tvClickable(shape, focusedScale=1.06f)` | 按压态 → 焦点态；描边 3dp 品牌绿 |
| `HyperButton` | 保留，内部点击改 `tvClickable` | 焦点态描边；尺寸放大（高 56dp） |
| `HyperCard` | 保留 | 焦点态描边 + elevation；`InfoRow` 行高 48→64dp |
| `HyperChip` | 保留 | 焦点态高亮；字号 12→18sp；`FlowRow`→单行 `TvLazyRow` |
| `HyperIconButton` | 保留 | 尺寸 40→52dp、图标 21→28dp；焦点态描边 |
| `HyperDialog` | 保留 | 打开默认聚焦 confirm；焦点困在弹窗；宽度上限放大 |
| `HyperBottomSheet` | **改为侧边/居中面板** | TV 底部弹层离焦点远；选集面板改右侧滑入抽屉（对齐手机端横屏抽屉） |
| `HyperSwitch` | 保留（只作指示） | 尺寸放大；整行可点不变 |
| `HyperTextField` | 保留（显示用） | 聚焦态 3dp 绿描边；字符键盘驱动输入 |
| `HyperSearchField` | 保留 | 同上；搜索键改为键盘"搜索"格 |
| `MovieCardItem` | 保留 | `bounceClick`→`tvClickable`；卡宽/字号放大；`animateItem` 保留 |
| `ContinueWatchingCard` | 保留 | 横滑由 `TvLazyRow` 承载 |
| `HomeBannerCarousel` | 保留 | 聚焦暂停自动轮播；指示点放大 |
| `SectionHeader` / `SectionTitle` | 保留 | 竖条 4→6dp，字号 18→28sp |
| `CoverImage` / `LetterPlaceholder` | **原样搬** | 无触屏耦合 |
| `SkeletonPosterGrid` / `ShimmerBlock` | 保留 | 列数随 TV 网格 |
| `EmptyView` / `ErrorState` / `OfflineBanner` | 保留 | 字号放大；重试按钮可聚焦（默认焦点） |
| `ProgressBar` | **原样搬** | 高度 4→6dp |
| `BottomNavBar` | **替换** `TvNavRail` | 底部导航 → 左侧导航 |
| `GestureLayer` / `GestureOsdOverlay` | **删除** | 无触屏 |
| `SpeedPanel` | 保留 | 锚点改为控制条上方；焦点可选 |
| `EpisodeDrawer` | 保留 | 右侧抽屉，集数格放大 |
| `CharKeypadView`（旧 40 计划遗留） | **新写** `ui/tv/CharKeypad.kt` | 见 §8.1 |

---

## 11. 数据层/仓库层复用清单（原样搬，仅调包名与 TV 差异）

| 文件/目录 | 处置 | TV 差异 |
|---|---|---|
| `data/api/ApiService.kt` | 原样 | 无（10 接口 + probe + update/check） |
| `data/api/{AuthInterceptor,ServerUrlInterceptor,SafeApiCall,NetworkMonitor,ApiResult}.kt` | 原样 | 无 |
| `data/model/Models.kt` | 原样 | 无 |
| `data/repo/{AuthRepo,ContentRepo,PlaybackRepo,SettingsRepo,PendingProgressStore,ProgressSync}.kt` | 原样 | 无 |
| `data/cache/{CacheStore,MediaCacheManager,DownloadMetaStore,HyperDownloadService}.kt` | 平移 | 下载目录优先外置存储（§8.9） |
| `data/update/{UpdateDownloadService,UpdateDownloadState}.kt` | 平移 | TV 安装 UI 验收 |
| `di/AppModule.kt` | 原样 | 无 |
| `player/{PlayerController,PlayerViewModel,PlayerError,MediaCodecs,PlayerIcons}.kt` | 原样 | 无（`PlayerScreen` 重写） |
| `config/AppConfig.kt` | 平移 + 增 TV 常量 | `PRESET_SERVER_URL` 构建期经 `-PhypermuxPublicServerUrl` 注入（留空则不提供公开档）；新增 TV 字号/安全区常量 |
| `ui/theme/Theme.kt` | 原样 | 主题模式 TV 默认锁深色（D6） |
| `ui/common/{Motion,Common,HyperUi,MovieCardItem}.kt` | 平移 + 焦点化 | `bounceClick`→`tvClickable` |

> **服务器地址**：公开模式 `AppConfig.PRESET_SERVER_URL`（构建期 `-PhypermuxPublicServerUrl` 注入，代码层持有、UI 不露地址；未注入则无公开档）+ 自定义模式（登录页弹窗）双模式照搬；TV 构建期可 `-PhypermuxApiBase=http://<盒子所在局域网后端>` 预置自定义档。

---

## 12. 里程碑计划（T0–T6）

> 按 1 名全职开发粗估（人日）。L1 数据层已可整体复用，主要成本在 L3 焦点交互与逐页布局。

| 里程碑 | 内容 | 产出 | 粗估 |
|---|---|---|---|
| **T0 脚手架 + 焦点基建** | 工程初始化（包名 `com.hypermux.tv`）；平移 `data/`+`player/`+`theme/`+`common/`；`tvClickable` 焦点态；`TvScreen` 骨架；安全区/字号规格；`tv-foundation` 验证 | 可编译运行，一个焦点 demo 页跑通 D-pad | 2d |
| **T1 登录 + 导航** | 左侧导航栏（首页/历史/我的/搜索）；字符键盘；登录闭环（登录→首页→登出→401 回登录）；`ServerConfigDialog`（焦点版） | 盒子/模拟器可登录进首页 | 2d |
| **T2 内容浏览** | 首页（频道 Tab + Banner + 继续观看 + 分区 rail）；分类网格分页；全局搜索（键盘 + 历史 + 结果网格）；离线降级 | 浏览→搜索主链通 | 3d |
| **T3 详情 + 选集** | 详情页左右分栏 + 简介/标签 + 主按钮；季 Tab + 集数格（五态，MENU 缓存）；`CacheSheet` 焦点版 | 详情→选集主链通 | 2.5d |
| **T4 播放器 TV 化** | 全屏播放 + D-pad 键位（seek/信息栏/选集抽屉/倍速）+ 媒体键 + 续播/连播/断点/错误分类 + 缓冲指示 + 屏幕常亮 | 播放主链通（续播/连播/错误） | 3d |
| **T5 历史/我的/下载/自更新** | 历史行多按钮操作；我的页分组（锁深色）；我的下载（外置存储）；App 自更新（TV 安装验收） | 全功能闭环 | 2d |
| **T6 真机验收与加固** | 焦点全键遍历无死角；机型矩阵（≥2 台不同芯片盒子）；性能/包体；release 签名；焦点恢复回归 | 可发布 APK | 2.5d |

**合计约 17 人日（≈3.5 周）**。后端零改动。

**顺序理由**：T0 的焦点基建是全部页面的公共前置，必须最先落地并用 demo 页验证；T1 最早暴露"遥控器输入 + 导航"这两个 TV 最难项；T4 播放器依赖 T3 的选集数据，故排在详情之后。

### 12.1 实施进度（随代码更新）

| 里程碑 | 状态 | 说明 |
|---|---|---|
| T0 脚手架 + 焦点基建 | ✅ 完成 | 工程/TV 清单/主题令牌/`tvClickable`+`TvAutoFocus`/`TvScreen`/`TvNavRail`/`CharKeypad` 全部落地；`tv-foundation` 实测锁定 **alpha11**（alpha12 起 lazy 包被移除） |
| T1 登录 + 导航 | ✅ 完成 | `LoginScreen`（左表单 + 右键盘双栏、硬件键盘自动隐藏键盘）、`ServerConfigDialog`（公开/自定义 + 自绘键盘 + 测试连接）、`AppNavigation` 左侧常驻导航栏 + `NavHost`、`NavComponents` 跳转封装 |
| T2 内容浏览 | ✅ 完成 | `HomeScreen`（Hero 自动轮播 + 继续观看 rail + 分区 `TvLazyRow` + 频道 chip + 分类 6 列网格分页）、`CategoryScreen`（分类内搜索改弹层键盘）、`SearchScreen`（弹层键盘 + 历史 chips + 结果网格）、离线降级 |
| T3 详情 + 选集 | ✅ 完成 | `MovieDetailScreen`：主播放按钮 → `HyperButton`（进入即聚焦）、剧集格 **8 列**、MENU 键 = 缓存本集、`CacheSheet` → `TvSidePanel`；「当前集」唯一来源铁律未动 |
| T4 播放器 TV 化 | ✅ 完成 | `PlayerScreen` 全新 TV 版：**删除全部触屏手势**（横滑 seek / 竖滑亮度音量 / 双击 / 长按 OSD 层、竖屏控制层、锁屏钮、显式全屏两态）；改用 D-pad 键位表（OK 收放控制条、LEFT/RIGHT ∓10s、UP 信息栏、DOWN 选集侧栏、MENU 倍速、BACK 先收控制条再退出）+ 媒体键；控制条 4s 自动收、信息栏 5s 自动收；根节点常驻焦点保证隐藏态左右键 = seek；全屏横屏 + 屏幕常亮；续播/连播倒计时/断点 10s 节流/错误分类 ± 音轨禁用重试/首帧看门狗/FFmpeg 软解提示全部保留 |
| T5 历史/我的/下载/自更新 | ✅ 完成 | `HistoryScreen`（行 3 焦点目标 + MENU 详情）、`MeScreen`（分组 + 锁深色 + MENU 键倍速）、`MyDownloadsScreen`/`DownloadDetailScreen`（顶栏改 `TvTopBar`、影片行 `tvClickable` + 放大、存储条接管首焦点）、`UpdateDialog`（TV 字号）全部完成；删除无引用手机组件 `ui/home/HomeBanner.kt` |
| T6 真机验收与加固 | ⏳ 待做 | 需真机/模拟器 D-pad 冒烟（本机仅完成到 `:app:assembleDebug` 通过） |

> 编译/打包基线：`:app:compileDebugKotlin` 与 `:app:assembleDist`（debug+release 双包落 `dist/`）全绿（2026-10-17，含 FFmpeg 原生库打包）。

### 12.2 TV 化修订（2026-10-17，模拟器联调前第一轮）

手机端底板搬到电视上后仍有若干"手机味"，本轮统一改掉（逐条理由见各文件注释）：

| # | 问题（手机端遗留） | 修订 |
|---|---|---|
| 1 | **详情页头图过大**：整宽 16:9 头图在 1080p 下约 540dp 高，几乎吃掉一整屏；主按钮被推到下方，焦点路径长、对比度靠重遮罩 | 改**左右结构** `DetailHeader`：左竖版海报（`TvSize.detailPoster` = 240dp / 2:3 ≈ 360dp 高），右为片名(40sp) + 原名 + 评分/年份·地区·类别 + 标签 + 简介(3 行 + 展开) + **继续播放主按钮**（仍是进入页面的首焦点，`weight(1f)` 自适应右栏宽并给电影「缓存」让位）；删除手机式 `DetailHero`、`DetailIntroCard` |
| 2 | **版本检查查的是手机端发布**：`UpdateViewModel.PLATFORM` 写死 `"mobile"`，一进 App 就提示"更新到手机包" | 改 `"tv"`（服务端 `AppReleaseService.PLATFORMS` 本就支持 `mobile/tv`，`/api/app/update/{check,download}` 默认 `mobile`）；并把"24h 节流时间戳 + 已忽略版本"**按平台隔离**（`SettingsRepo.Keys.UPDATE_PLATFORM`）——否则手机端已忽略的 versionCode（12+）会把 TV 端（versionCode 1）永久压住 |
| 3 | **系统 `Toast`**（检查更新结果 / 安装权限 / 空间不足）在电视上落在左下角、按手持 dpi 小字，10 英尺视距读不清 | 新增 `TvMessageOverlay`（非焦点 `Popup`：屏幕中下方、18sp、暗底描边、2.6s 自隐），三处 Toast 全部替换 |
| 4 | **顶栏 12dp 边距**：返回按钮落进 1080p 的 5% overscan 带，且标题与内容（48dp 安全区）左缘对不齐 | `TvTopBar` 左右内缩改 `Spacing.page`（48dp） |
| 5 | **滚动容器仍是手机件**：`LazyColumn` / `Row + horizontalScroll` 在 D-pad 下焦点出屏行为不可控 | 我的 / 历史 / 我的下载 / 下载详情 → `TvLazyColumn`；详情页季 chips、标签行 → `TvLazyRow`（与首页 rail 一致） |
| 6 | **下载两级页字号/控件仍是手机规格**（11~15sp、52dp 行高、30/38dp 图标钮） | 整体放大：分区标题 20sp、集数行 18/16sp + `TvSize.infoRowHeight`(64dp)、图标钮 44dp / `TvSize.iconButton`(52dp)；角标与小胶囊等零散小字一并抬到 12~16sp |

> 线上验证（2026-10-17，构建期注入的公开节点）：
> `GET /api/app/update/check?code=1&platform=tv` → **204**（TV 未发布 → 不提示更新）；
> 同参数 `platform=mobile` → **200**（手机端发布存在）—— 即原来"一进 App 就提示更新"的来源。

---

## 13. 验收（T6 Definition of Done）

### 13.1 手测主线（真机/盒子）

1. **登录**：字符键盘输入账密 → 进首页；杀进程重启 → token 有效免登；后台删用户 → 下次请求 401 → 自动回登录页。
2. **焦点遍历**：首页（导航↔Tab↔Banner↔继续观看↔各 rail）四向全键遍历**无死角、无卡死、无"焦点丢失"**；任一页面返回后焦点回到来源卡片。
3. **首页**：频道 Tab 切换、Banner 轮播（聚焦即暂停）、继续观看续播入口、分页加载；断网 → 缓存 + 离线黄条 → 复网恢复。
4. **搜索**：字符键盘输入 + 防抖；历史复用/清空；结果网格分页；空态。
5. **详情→播放**：左右分栏渲染；季 Tab 切换；集数格五态正确（当前集唯一）；MKV 直链播通；断点续播 seek（浮差 ≤5s）；`LEFT/RIGHT` seek ±10s；`OK` 收放控制条；`UP` 信息栏 / `DOWN` 选集抽屉；自然播完自动下一集；末集播完回详情；`BACK` 先收控制条再退出且已上报断点。
6. **历史**：列表进入/继续看/详情/标记看完/移除（remove 后不再出现）。
7. **我的**：版本信息/服务器只读/清缓存/退出登录；深色固定。
8. **下载（若纳入）**：外置存储入队 + 进度 + 本地离线播放。
9. **自更新**：TV 上发现新版 → 下载 → 校验 → 安装（未知来源授权流程）。
10. **错误**：坏链（HTTP 404）→ 文案 + 不重试 + 返回详情；慢网 → 自动重试恢复。
11. **稳定性**：连续 30min 播放无 OOM/泄漏；遥控器全键快速连按无崩溃（防连点封装生效）。

### 13.2 机型矩阵

| 目标 | 说明 |
|---|---|
| 主力盒子 ≥2 台 | 不同芯片平台（如 Amlogic S905 / 瑞芯微 RK / 联发科 MT9xxx），覆盖用户实际盒子 |
| 验证项 | MKV(H264 1080p) 播放/seek/断点；HEVC/AV1 软解兜底文案；D-pad 焦点导航；蓝牙/USB 键盘登录；分辨率 1080p/4K |
| 失败归因 | 解码错误 → 错误分类 + 机型清单；焦点问题 → 焦点遍历回归清单 |

### 13.3 单测（JVM，沿用手机端并新增 TV 项）

12. `AuthRepo` 过期判定（-300s buffer）、`SafeApiCall` 401 流程。
13. 断点 tick 节奏（10s 边界、pause 即时、<3s 跳过）。
14. 集排序 / next-ep 边界（跨季、末集、中间缺失集号）。
15. **新增**：播放页 D-pad 键位映射（seek 重复键节流、`repeatCount` 边界、控制条两态分支）。
16. **新增**：焦点落点决策（`focusProperties` 的纯函数化封装，脱离 UI 单测）。

### 13.4 跨文档一致性锁定（写给 QA）

- 密码规则分两处：**建用户/重置密码**字符集 `[a-zA-Z0-9_.]{6,20}`；**登录**仅长度 6~20（`LoginReq`），登录页不得校验字符集。
- 时间全部毫秒；`videos[].duration` 是**秒**，`progress.*Ms` 是**毫秒**。
- 断点上报：播放中 10s 节流 + 暂停/退后台即时；`position<3s` 不上报。
- 详情"当前集"唯一来源 = 详情断点集，禁止按每格 `progress` 推断。

---

## 14. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| **焦点体系复杂**（TV 最大风险） | 焦点丢失/卡死/跳错，验收返工 | T0 先落基建 + demo 页；`focusRestorer` + `TvLazyRow`；每个页面写"焦点遍历自测清单"；T6 专项遍历回归 |
| `androidx.tv:tv-foundation` 版本不稳定 | API 变动/构建失败 | **已在 T0 解除**：lazy list/grid 整包在 alpha12 / 1.0.0 被移除，锁定 **1.0.0-alpha11**（详见 §13 依赖说明）；退化方案备而不用：自绘 `focusable` + `BringIntoViewRequester` 手动滚动 |
| Compose API 位置与直觉不符（易踩坑） | 误判"没有这个 API"→ 绕路自研 | 经验：`Modifier.drawOutline` 在 **`androidx.compose.ui.graphics`**（不是 `...graphics.drawscope`）；`Modifier.focusGroup()` 在 `androidx.compose.foundation`；`focusRestorer` 需 `@OptIn(ExperimentalComposeUiApi::class)`；`Key.nativeKeyCode` 挂在 `Key` 上。定位手段：从 Gradle 缓存解包 AAR，用 `javap` 确认类与方法所在包 |
| 遥控器文本输入体验差 | 登录/搜索难用 | 自绘字符键盘 + 硬件键盘自动识别；服务器地址可构建期预置（减少手输） |
| 盒子软解兼容（HEVC/10bit/AV1） | 播放卡顿/黑屏 | 保留 `nextlib-media3ext` FFmpeg 兜底 + 首帧看门狗 + 错误分类文案指引换 H264 源 |
| 盒子存储小 | 下载不可用 | 下载目录优先外置/USB，无则隐藏入口；下载整体降级为可选（T5） |
| 大屏性能（低端盒 GPU 弱） | 动画掉帧 | 裁掉圆形揭示等重绘动画；渐变 Brush/Shader 一律 `remember` 复用（沿用手机端第六轮优化）；卡片懒加载 |
| 分辨率/DPI 差异大 | 布局错位/文字过大过小 | 固定 `sp` 规格 + 安全区 48dp + `BoxWithConstraints` 响应式列数（1080p 5~6 列 / 4K 放宽） |
| App 自更新在 TV 安装流程不同 | 更新失败 | 保留手机端成熟实现；T5 专项验收 TV 安装器流程与授权跳转文案 |
| 与旧 `40-app-detailed.md` 并存混淆 | 实施走错方向 | 本文件**明确取代** 40；实施前在 40 顶部标注"作废，见 48"；README 指向本文件 |

---

## 15. 已锁定决策与默认项

> 沿用项目惯例：默认值无异议即按默认执行（如要改，在实施前指出）。

| # | 事项 | 结论 |
|---|---|---|
| D1 | TV 焦点方案 | **标准 Compose + 自绘焦点态（`tvClickable`）+ `androidx.tv:tv-foundation`（列表焦点滚动）**；不引入 Leanback，避免与手机端设计系统割裂。备选：`androidx.tv:tv-material`（仅在文本输入省事时局部使用） |
| D2 | 复用边界 | **L1 数据/播放层 + `theme`/`common` 平移，L3 交互层重写**；不做手机/TV 公共 module 抽取（避免过早抽象；后续需要再抽） |
| D3 | 导航形态 | 底部导航 → **左侧导航栏**（首页/历史/我的/搜索） |
| D4 | 首页内容形态 | 分区由"双列信息流"改 **"分区标题 + 横向 rail"**（`TvLazyRow`） |
| D5 | 分类页默认焦点 | 进入分类页聚焦**首张卡片**（搜索条不作为默认焦点，避免误触输入） |
| D6 | 主题 | TV **默认锁定深色**（隐藏浅色/跟随系统）；如需保留则位于"我的→外观" |
| D7 | 播放页形态 | **永远全屏横屏**，无竖屏/全屏切换；控制条默认隐藏，`OK` 唤出 |
| D8 | 长按语义 | 全部取消；`MENU` 键或行内显式按钮替代（缓存/更多） |
| D9 | 音量/亮度 | 删除手势；默认交由系统/遥控器音量；可选拦截 `KEYCODE_VOLUME_*` 调媒体流 |
| D10 | 下载 | **可选（T5）**；保留显式下载能力但优先外置存储，人力紧张时降级为"仅在线播放" |
| D11 | 服务器地址 | 沿用双模式（公开节点 + 自定义）；TV 支持构建期预置局域网地址 |
| D12 | 包名/工作区 | `com.hypermux.tv` / `hyper-mux-android-tv/`；versionName 0.1.0 |

---

## 16. 附录：文件映射总表（手机端 → TV 端）

| 手机端（`com.hypermux.mobile`） | TV 端（`com.hypermux.tv`） | 处置 |
|---|---|---|
| `HyperMuxApp.kt` | `TvApp.kt` | 平移 |
| `config/AppConfig.kt` | `config/AppConfig.kt` | 平移 + 增 TV 常量 |
| `data/api/*`、`data/model/*`、`data/repo/*`、`di/AppModule.kt` | 同名 | **原样搬** |
| `data/cache/*` | 同名 | 平移（下载目录改外置） |
| `data/update/*` | 同名 | 平移 |
| `ui/theme/Theme.kt` | `ui/theme/Theme.kt` | 平移（锁深色） |
| `ui/common/Motion.kt` | `ui/common/Motion.kt` | 平移（`bounceClick` 保留供内部复用 + 新增 `tvClickable`） |
| `ui/common/Common.kt`、`HyperUi.kt` | 同名 | 平移 + 焦点化 |
| `ui/common/MovieCardItem.kt` | 同名 | 平移 + 焦点化 + 放大 |
| `ui/navigation/{AppNavigation,Route}.kt` | 同名 | 平移（转场简化 + 左侧导航） |
| `ui/navigation/NavComponents.kt` | 同名 | `BottomNavBar` → `TvNavRail`；防连点封装保留 |
| `ui/login/LoginScreen.kt`、`ServerConfigDialog.kt` | `ui/login/*` | 重排为 TV 布局 + 字符键盘 + 焦点版弹窗 |
| `ui/home/HomeScreen.kt`、`HomeBanner.kt` | `ui/home/*` | 重排（分区 rail + 左右布局） |
| `ui/category/CategoryScreen.kt` | 同名 | 网格列数放大 + 焦点 |
| `ui/search/SearchScreen.kt` | 同名 | 字符键盘 + 分屏 + 焦点 |
| `ui/movie/MovieDetailScreen.kt` | 同名 | 左右分栏重排 + 集数格放大 + MENU 缓存 |
| `ui/history/HistoryScreen.kt` | 同名 | 行内多按钮 + 焦点 |
| `ui/me/MeScreen.kt` | 同名 | 行高放大 + 锁深色 + 焦点 |
| `ui/downloads/*` | 同名 | 平移 + 外置存储（可选） |
| `ui/player/PlayerScreen.kt` | `ui/player/PlayerScreen.kt` | **重写**（D-pad 控制层，删手势层） |
| `ui/player/{PlayerController,PlayerViewModel,PlayerError,MediaCodecs,PlayerIcons}.kt` | 同名 | **原样搬** |
| （无） | `ui/tv/{Focus,TvNavRail,CharKeypad,TvScreen,KeyBindings}.kt` | **TV 新写** |

---

## 17. 与旧 40 计划、手机端 45 计划的关系

- **取代 40**：旧 `docs/plans/40-app-detailed.md`（Leanback + XML Fragment，v1.0，未实施）即日起作废，其"播放器状态机/断点逻辑/错误分类/机型矩阵/阈值常量"思想已被手机端 45 计划吸收，本文件再据手机端实况落到 TV。
- **对齐 45**：本文件与 [45-phone-app-detailed.md](../../../docs/plans/45-phone-app-detailed.md) 保持**业务语义一致**（页面集合、接口、断点、连播、错误分类、缓存降级、服务器双模式、自更新），差异仅限 §2 差异矩阵所列的**交互与呈现**。
- **回灌**：TV 端若在焦点/大屏布局上沉淀出可复用实现（如 `tvClickable`、`TvNavRail`），可反向回灌到未来统一的公共 UI 模块（D2，非本期）。
