package com.hypermux.tv.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 设计系统：与手机端（hyper-mux-android）**同源**，TV 端在保持色板/圆角/动效一致的前提下
 * 放大间距与字号（10-foot UI），并把主题**锁定深色**（48-tv-app-detailed.md §5 / §15 D6）。
 */

// ---------- 语义扩展色（M3 colorScheme 装不下的部分） ----------
data class HyperColors(
    /** 1px 描边 */
    val line: Color,
    val lineStrong: Color,
    /** 三级文字 */
    val text3: Color,
    /** 评分金 */
    val rating: Color,
    /** 危险色带底 */
    val dangerSoft: Color,
    /** 品牌渐变 */
    val primaryGradient: List<Color>,
    /** 进度条渐变 */
    val progressGradient: List<Color>,
    /** 卡片阴影强度（Compose 用 elevation 近似） */
    val cardElevation: Dp
)

private val DarkHyper = HyperColors(
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x24FFFFFF),
    text3 = Color(0xFF9AA0A6),
    rating = Color(0xFFFFB400),
    dangerSoft = Color(0x24F2555A),
    primaryGradient = listOf(Color(0xFF00BE06), Color(0xFF37D67A)),
    progressGradient = listOf(Color(0xFF00BE06), Color(0xFF37D67A)),
    cardElevation = 2.dp
)

private val LocalHyperColors = staticCompositionLocalOf { DarkHyper }

/** 取扩展语义色：`HyperTheme.colors` 或 `hyperColors()` */
@Composable
fun hyperColors(): HyperColors = LocalHyperColors.current

object HyperTheme {
    val colors: HyperColors
        @Composable get() = LocalHyperColors.current
}

// ---------- 兼容旧名（部分组件仍在引用） ----------
val BrandOrange = Color(0xFF00BE06)
val BrandOrangeDeep = Color(0xFF00A006)
val SuccessGreen = Color(0xFF00BE06)
val ErrorRed = Color(0xFFF2555A)
val PageBackground = Color(0xFF0F1012)
val PageBackgroundElevated = Color(0xFF15171A)
val CardBackground = Color(0xFF181A1C)
val TextPrimary = Color(0xFFEDEFF2)
val TextSecondary = Color(0xFF9AA0A6)

/** 间距梯度（手机端基准 4/8/12/16/20/28；TV 的页面留白与行距按 10-foot 规格放大） */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val heroHeight: Dp = 148.dp
    /**
     * 页面左右留白。
     * TV：48dp（1080p 下约等于 overscan 5% 安全区，见 48-tv-app-detailed.md §5.2）。
     * 手机端为 14dp；本工程为 TV 专用，故直接按 TV 规格取值。
     */
    val page = 48.dp
    /** TV：rail（横滑行）之间的纵向间距 */
    val railGap = 32.dp
    /** TV：rail 内相邻卡片的横向间距 */
    val cardGap = 24.dp
    /** TV：区段（标题 + rail）之间的间距 */
    val sectionGap = 28.dp
}

/** 圆角（沿用手机端 8/10/14/18/20 + 胶囊） */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    // 注意：extraLarge 被 AlertDialog / Dialog / Card 等当**容器圆角**用，
    // 不能设成 999dp（会让弹窗变成大椭圆）。细长控件（胶囊按钮/进度条）自身会按高度收敛成胶囊。
    extraLarge = RoundedCornerShape(20.dp)
)

/** 动效（沿用手机端 150/220/360，TV 新增焦点 180ms） */
object Motion {
    const val FAST = 150
    const val BASE = 220
    const val SLOW = 360
    /** TV 新增：焦点放大 / 描边动画时长（见 48-tv-app-detailed.md §5.3） */
    const val FOCUS = 180
    val Ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00BE06),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0x2400BE06),
    onPrimaryContainer = Color(0xFFA5F5A8),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF12151C),
    background = Color(0xFF0F1012),
    onBackground = Color(0xFFEDEFF2),
    surface = Color(0xFF181A1C),
    onSurface = Color(0xFFEDEFF2),
    surfaceVariant = Color(0xFF232628),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFF2555A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0x24F2555A),
    onErrorContainer = Color(0xFFFFC9CB),
    outline = Color(0x24FFFFFF)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperMuxTheme(content: @Composable () -> Unit) {
    // TV 端**锁定深色**（决策 D6）：SettingsRepo 的 ThemeMode 仍保留（供"我的"页展示），但不参与渲染。
    // 系统栏图标恒按深色主题取反色（TV 全屏无系统栏，这里只为开发机/手机侧装时观感一致）。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalHyperColors provides DarkHyper,
        // 关掉 Material3 原生水波纹：统一改用组件层反馈
        // （手机端是按压回弹 bounceClick，TV 端是焦点放大 + 描边 tvClickable），去掉"安卓原生感"
        LocalRippleConfiguration provides null
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            shapes = AppShapes,
            content = content
        )
    }
}

/** 从 Context 链里取宿主 Activity（ComposeView 的 context 常被 ContextThemeWrapper 包一层） */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
