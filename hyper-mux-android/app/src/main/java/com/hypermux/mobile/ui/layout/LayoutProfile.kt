package com.hypermux.mobile.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口尺寸档（阈值对齐 Google WindowSizeClass，但**按当前窗口宽度**判定，不按设备）。
 *
 * 一套逻辑同时覆盖：平板横/竖屏、分屏、桌面窗口（freeform）、折叠屏内屏 ——
 * 窗口被拖多宽就落进哪一档。不需要 `sw600dp` 资源，也不需要 `material3-adaptive` 依赖。
 *
 * ★ 硬约束 ★ [profileFor] 的 Compact 档必须逐位复现 0.1.11 之前在 360dp 手机上的表现
 * （列数 2/3/5/6、宽度不封顶）。手机侧已经过 10 轮真机修复，自适应改动不允许改变手机布局。
 */
enum class LayoutTier { Compact, Medium, Expanded }

data class LayoutProfile(
    val tier: LayoutTier,
    /** 当前窗口宽度（供 [gridSideInset] 之类换算用） */
    val windowWidth: Dp,
    /** 网格容器最大宽度（水平居中）。[Dp.Infinity] = 沿用今天的铺满行为 */
    val gridMaxWidth: Dp,
    /**
     * 网格 contentPadding 的左右**额外**内缩 = (窗口宽 − [gridMaxWidth]) / 2。
     *
     * 用它把「封顶 + 居中」做在 `contentPadding` 上，而不是把网格包进一层 Box：
     * 布局树不动、`GridItemSpan(maxLineSpan)` 的头图/横幅自动跟着一起被收窄，改动面最小。
     * Compact 档恒为 0 → 各屏 contentPadding 与今天逐字节一致。
     */
    val gridSideInset: Dp,
    /** 弹窗 / 登录卡 / 设置表单的最大宽度 */
    val formMaxWidth: Dp,
    /** 底部弹层（缓存选集等）的最大宽度 */
    val sheetMaxWidth: Dp,
    /**
     * 16:9 大图（首页轮播、详情头图）的限高。
     * 宽窗口上按 16:9 铺满会把整屏吃掉（932dp 宽 → 524dp 高，12.9" 平板上占掉 59% 高度），
     * 这里按档封顶、图片由 ContentScale.Crop 轻微裁切。Compact 档 [Dp.Infinity] = 今天的高度。
     */
    val heroMaxHeight: Dp,
    /** 首页信息流列数（原 `Fixed(2)`） */
    val homeColumns: Int,
    /** 分类页 / 搜索结果列数（原 `Fixed(3)`） */
    val tileColumns: Int,
    /** 详情页选集 + 缓存弹层选集列数（原 `Fixed(5)`） */
    val episodeColumns: Int,
    /** 播放页竖屏选集列数（原 `Fixed(6)`） */
    val playerEpisodeColumns: Int,
    /** 全屏「选集」右侧抽屉的列数（抽屉宽度已被 320 / 380 / 440dp 封顶，见 PlayerScreen） */
    val playerPanelEpisodeColumns: Int,
)

private const val MEDIUM_FROM = 600   // 与 sw600dp 对齐
private const val EXPANDED_FROM = 840

private fun sideInset(width: Dp, gridMaxWidth: Dp): Dp =
    if (gridMaxWidth == Dp.Infinity) 0.dp
    else ((width - gridMaxWidth) / 2f).coerceAtLeast(0.dp)

/**
 * @param size  窗口**最小边**（等价于 sw 语义）—— 决定档位。
 * @param windowWidth 窗口实际宽度 —— 只用于算 [LayoutProfile.gridSideInset]。
 */
fun profileFor(size: Dp, windowWidth: Dp = size): LayoutProfile {
    val tier = when {
        size < MEDIUM_FROM.dp -> LayoutTier.Compact
        size < EXPANDED_FROM.dp -> LayoutTier.Medium
        else -> LayoutTier.Expanded
    }
    val gridMaxWidth = when (tier) {
        LayoutTier.Compact -> Dp.Infinity
        LayoutTier.Medium -> 720.dp
        LayoutTier.Expanded -> 960.dp
    }
    return LayoutProfile(
        tier = tier,
        windowWidth = windowWidth,
        gridMaxWidth = gridMaxWidth,
        gridSideInset = sideInset(windowWidth, gridMaxWidth),
        formMaxWidth = if (tier == LayoutTier.Compact) Dp.Infinity else 480.dp,
        sheetMaxWidth = if (tier == LayoutTier.Compact) Dp.Infinity else 640.dp,
        heroMaxHeight = when (tier) {
            LayoutTier.Compact -> Dp.Infinity
            LayoutTier.Medium -> 340.dp
            LayoutTier.Expanded -> 420.dp
        },
        // Compact 档 = 0.1.11 手机实测值（2 / 3 / 5 / 6），一个都不动
        homeColumns = when (tier) { LayoutTier.Compact -> 2; LayoutTier.Medium -> 3; else -> 5 },
        tileColumns = when (tier) { LayoutTier.Compact -> 3; LayoutTier.Medium -> 4; else -> 6 },
        episodeColumns = when (tier) { LayoutTier.Compact -> 5; LayoutTier.Medium -> 8; else -> 12 },
        playerEpisodeColumns = when (tier) { LayoutTier.Compact -> 6; LayoutTier.Medium -> 8; else -> 12 },
        playerPanelEpisodeColumns = when (tier) { LayoutTier.Compact -> 4; LayoutTier.Medium -> 5; else -> 6 },
    )
}

/**
 * 按**实测可用宽度**算列数：用于宽度已被外层容器封顶的场景（缓存弹层、播放页选集区）。
 * 这些位置的可用宽度不等于窗口宽度，不能直接用 tier 列数。
 *
 * [minCell] 取手机上今天的实测格子宽度，所以手机侧算出的列数与原来的 `Fixed(n)` 一致：
 *  - 缓存弹层：可用 332dp、间距 10dp、minCell 56dp → ⌊342/66⌋ = **5** ✓（今天就是 5）
 *  - 播放页选集：可用 332dp、间距 8dp、minCell 46dp → ⌊340/54⌋ = **6** ✓（今天就是 6）
 */
fun columnsFor(availableWidth: Dp, minCell: Dp, gap: Dp, maxColumns: Int): Int =
    (((availableWidth + gap) / (minCell + gap)).toInt()).coerceIn(1, maxColumns)

/**
 * 默认值取 Compact：任何还没接入 [AdaptiveScope] 的调用点，行为都等于 0.1.11 的手机布局。
 */
val LocalLayoutProfile = staticCompositionLocalOf { profileFor(360.dp) }

/**
 * 在 Activity 内容根上建立尺寸档，向下所有 Screen / 组件通过 [LocalLayoutProfile] 取值。
 *
 * 挂在 `HyperMuxTheme` 之内、`AppNavigation` 之外：窗口尺寸变化（旋转 / 分屏拖拽 / 折叠）
 * 直接体现在 [BoxWithConstraints] 的约束上并触发换档，不需要监听配置变化。
 */
@Composable
fun AdaptiveScope(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val windowWidth = with(density) { constraints.maxWidth.toDp() }
        val windowHeight = with(density) { constraints.maxHeight.toDp() }
        // 档位按窗口**最小边**判定（等价 sw 语义）：手机横过来最小边仍是 360dp，
        // 不会因为旋转掉进 Medium 档而改变手机布局；平板横竖过来档位也不跳变。
        val profile = remember(windowWidth, windowHeight) {
            profileFor(minOf(windowWidth, windowHeight), windowWidth)
        }
        CompositionLocalProvider(LocalLayoutProfile provides profile, content = content)
    }
}

/**
 * `fillMaxWidth()` 的封顶版：[Dp.Infinity] 时**完全等价于今天的 `fillMaxWidth()`**
 * （Compact 档 = 手机零回归），否则先按 [max] 夹紧约束再铺满 —— 顺序很关键：
 * `widthIn` 必须在 `fillMaxWidth` **之前**，否则会把已定的精确宽度往回夹，依赖约束兜底才碰巧对。
 *
 * 用于弹窗 / 底部弹层 / 登录卡：`usePlatformDefaultWidth = false` 时弹窗窗口是 MATCH_PARENT，
 * 1280dp 宽平板上原来会得到一张 ~1224dp 宽的卡片。
 */
fun Modifier.cappedFill(max: Dp): Modifier =
    if (max == Dp.Infinity) this.fillMaxWidth()
    else this.widthIn(max = max).fillMaxWidth()

/** 限高版：[Dp.Infinity] 时不施加任何约束（Compact 档 = 今天按 16:9 自由伸展的高度）。 */
fun Modifier.heightCap(max: Dp): Modifier =
    if (max == Dp.Infinity) this else this.heightIn(max = max)

