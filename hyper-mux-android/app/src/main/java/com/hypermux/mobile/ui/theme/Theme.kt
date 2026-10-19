package com.hypermux.mobile.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hypermux.mobile.data.repo.ThemeMode
import com.hypermux.mobile.ui.util.observeThemeMode

/**
 * 设计系统与 web 端（hyper-mux-web/src/style.css）保持一致：
 * 靛紫主色 + 紫色渐变点缀、中性近黑深色底、圆角卡片 + 1px 描边 + 柔和阴影。
 */

// ---------- 语义扩展色（M3 colorScheme 装不下的部分） ----------
data class HyperColors(
    /** 1px 描边（web: --line / --line-strong） */
    val line: Color,
    val lineStrong: Color,
    /** 三级文字（web: --text-3） */
    val text3: Color,
    /** 评分金（web: --rating） */
    val rating: Color,
    /** 危险色带底（web: --danger-soft） */
    val dangerSoft: Color,
    /** 品牌渐变（web: linear-gradient(135deg, primary, #9a6bff)） */
    val primaryGradient: List<Color>,
    /** 进度条渐变（web: linear-gradient(90deg, primary, #9a6bff)） */
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

private val LightHyper = HyperColors(
    line = Color(0x14151515),
    lineStrong = Color(0x24151515),
    text3 = Color(0xFF8A9099),
    rating = Color(0xFFFF9500),
    dangerSoft = Color(0x1AE5484D),
    primaryGradient = listOf(Color(0xFF00BE06), Color(0xFF37D67A)),
    progressGradient = listOf(Color(0xFF00BE06), Color(0xFF37D67A)),
    cardElevation = 3.dp
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

/** 间距梯度（与 web 的 4/8/12/16/20/28 对齐） */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val heroHeight: Dp = 148.dp
    /** 页面左右留白（web 移动端 14px） */
    val page = 14.dp
}

/** 圆角（web: --r-sm 10 / --r 14 / --r-lg 18 / pill 999） */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    // 注意：extraLarge 被 AlertDialog / Dialog / Card 等当**容器圆角**用，
    // 不能设成 999dp（会让弹窗变成大椭圆）。细长控件（胶囊按钮/进度条）自身会按高度收敛成胶囊。
    extraLarge = RoundedCornerShape(20.dp)
)

/** 动效（web 多为 .15s / .18s / .25s ease） */
object Motion {
    const val FAST = 150
    const val BASE = 220
    const val SLOW = 360
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

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00BE06),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0x1A00BE06),
    onPrimaryContainer = Color(0xFF064D09),
    secondary = Color(0xFF5F6368),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6F7),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF5F6368),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0x1AE5484D),
    onErrorContainer = Color(0xFF7A1113),
    outline = Color(0x24151515)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperMuxTheme(content: @Composable () -> Unit) {
    val themeMode: ThemeMode by observeThemeMode()
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    // 系统栏（状态栏 / 导航栏）图标明暗跟随 **App 主题**，而不是系统深色开关。
    // 原因：MainActivity 的 enableEdgeToEdge() 默认 auto 策略只读系统配置，
    // 当"系统深色 + App 浅色"时会给状态栏留白图标，而 App 顶栏是白底 → 顶栏（黑底白字）与内容割裂。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalHyperColors provides if (dark) DarkHyper else LightHyper,
        // 关掉 Material3 原生水波纹：统一改用组件层的按压反馈（bounceClick），去掉"安卓原生感"
        LocalRippleConfiguration provides null
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,
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
