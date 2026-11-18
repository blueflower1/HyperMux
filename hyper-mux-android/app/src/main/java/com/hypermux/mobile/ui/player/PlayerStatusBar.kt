package com.hypermux.mobile.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Date

/* =========================================================================
 * 全屏 title 栏的「时钟 + 电量」状态簇
 * ========================================================================= */

/**
 * 全屏沉浸播放时系统状态栏被 `WindowInsetsControllerCompat.hide(systemBars())` 藏掉了
 * —— 时间、电量这两项"看片时最常想确认"的信息跟着一起没了（竖屏时它们本来就在状态栏里，
 * 所以只有全屏需要补回）。这里由 title 栏自带的状态簇顶上：`12:34  ▮ 85%`。
 *
 * 装配位置在 [LandscapeControls] 的 AnimatedVisibility 之内：控件自动隐藏后这块**根本不参与组合**，
 * 于是广播注销、秒级 tick 也停 —— 播放中（控件不显示时）零开销，不会把整页拖进重组。
 */
@Composable
internal fun PlayerStatusCluster(modifier: Modifier = Modifier) {
    val battery = rememberBatteryStatus()
    val clock = rememberClockText()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 时钟压在深色渐变浮层上 → 固定白色系（与倍速/时间文本同一规矩，亮色主题下不会隐形）
        Text(
            text = clock,
            color = Color(0xCCFFFFFF),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        // 读不到电量（个别模拟器 / 特殊 ROM）时只留时钟，不显示 "‑%" 这种怪状态
        if (battery.percent >= 0) {
            BatteryGlyph(
                percent = battery.percent,
                charging = battery.charging,
                modifier = Modifier.size(width = 22.dp, height = 11.dp)
            )
            Text(
                text = "${battery.percent}%",
                color = Color(0xB3FFFFFF),
                fontSize = 11.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * 电量读数。
 *
 * 数据源选 `ACTION_BATTERY_CHANGED`：它是**受保护的系统 sticky 广播**——
 * ① 注册的那一刻就同步返回当前电量（不用先等一次变化才有值，控件刚弹出就能显示）；
 * ② 之后电量或充电状态真的变了才回调；③ 全程不需要任何权限。
 * 比"起个 1s 轮询"或"读 Health Services（要 API 29 + 协程 + 更多代码）"都更省、更实时。
 */
@Composable
private fun rememberBatteryStatus(): BatteryStatus {
    // 用 applicationContext：注册/注销都挂在 app 级上下文上，Activity 重建时不会漏注销
    val ctx = LocalContext.current.applicationContext
    var status by remember(ctx) { mutableStateOf(BatteryStatus.UNKNOWN) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                status = parseBattery(intent)
            }
        }
        // 只收系统广播 → NOT_EXPORTED（别的 App 不能往这个 receiver 里塞假电量）
        val initial = ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // sticky：注册返回值就是当前电量，立刻落位
        if (initial != null) status = parseBattery(initial)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return status
}

/** 电量意图 → 展示用状态（EXTRA_LEVEL / EXTRA_SCALE 缺任一项就当读不到） */
private fun parseBattery(intent: Intent?): BatteryStatus {
    if (intent == null) return BatteryStatus.UNKNOWN
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return BatteryStatus.UNKNOWN
    val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
    val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC,
        BatteryManager.BATTERY_PLUGGED_USB,
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> true
        else -> false
    }
    // 已插电但充满 = 不再"充电中"，显示成普通满电（避免一直挂着闪电图标）
    val full = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ==
        BatteryManager.BATTERY_STATUS_FULL
    return BatteryStatus(percent = percent, charging = plugged && !full)
}

/**
 * 时钟文本。
 *
 * 格式走 `android.text.format.DateFormat.getTimeFormat(context)`：跟随系统的 12/24 小时制设置，
 * 与用户平时在状态栏看到的写法一致（比硬编码 "HH:mm" 更贴手）。
 * tick 1s 一次，但写的是**格式化后的字符串**：同一分钟内字符串相等，
 * Compose 的 `MutableState` 写入走 equals 比较 → 不会触发重组，真正的刷新频率只有每分钟一次。
 */
@Composable
private fun rememberClockText(): String {
    val ctx = LocalContext.current
    val formatter = remember(ctx) { android.text.format.DateFormat.getTimeFormat(ctx) }
    var text by remember(formatter) { mutableStateOf(formatter.format(Date())) }
    LaunchedEffect(formatter) {
        while (true) {
            delay(1_000)
            text = formatter.format(Date())
        }
    }
    return text
}

/** 电量状态：[percent] < 0 = 读不到 */
private data class BatteryStatus(val percent: Int, val charging: Boolean) {
    companion object {
        val UNKNOWN = BatteryStatus(-1, false)
    }
}

/**
 * 自绘电池符号（与 [PlayerIcons] 的"自绘、圆角、去安卓原生感"规范一致）：
 * 圆角描边外壳 + 右侧凸点 + 按电量比例填充。
 *
 * 配色语义：充电中 = 品牌绿（并叠一层淡绿底 + 深色闪电）；
 * ≤15% = 危险红、≤30% = 琥珀、其余 = 白 —— 一眼能看出"还能看多久"。
 */
@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val accent = when {
        charging -> Color(0xFF37D67A)
        percent <= 15 -> Color(0xFFF2555A)
        percent <= 30 -> Color(0xFFFFC53D)
        else -> Color(0xE6FFFFFF)
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = (h * 0.13f).coerceAtLeast(1f)          // 外壳线宽（≈1.4dp @ 11dp 高）
        val nubW = (w * 0.075f).coerceAtLeast(2f)           // 右侧正极凸点
        val bodyW = w - nubW
        val radius = CornerRadius(h * 0.20f, h * 0.20f)

        // ① 外壳（描边）
        drawRoundRect(
            color = Color(0xB3FFFFFF),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, h - stroke),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        // ② 正极凸点
        val nubH = h * 0.42f
        drawRoundRect(
            color = Color(0xB3FFFFFF),
            topLeft = Offset(bodyW + stroke * 0.3f, (h - nubH) / 2f),
            size = Size(nubW - stroke * 0.6f, nubH),
            cornerRadius = CornerRadius(nubW * 0.35f, nubW * 0.35f)
        )

        // ③ 内区（留出描边 + 一条缝，填充不贴着外壳）
        val inset = stroke * 1.8f
        val innerLeft = inset
        val innerTop = inset
        val innerW = (bodyW - inset * 2f).coerceAtLeast(0f)
        val innerH = (h - inset * 2f).coerceAtLeast(0f)
        if (innerW <= 0f || innerH <= 0f) return@Canvas
        val innerRadius = CornerRadius(innerH * 0.32f, innerH * 0.32f)

        if (charging) {
            // 充电中：整格先铺一层淡绿"通电底"，闪电才有对比度可辨（否则深底上看不见）
            drawRoundRect(
                color = accent.copy(alpha = 0.30f),
                topLeft = Offset(innerLeft, innerTop),
                size = Size(innerW, innerH),
                cornerRadius = innerRadius
            )
        }
        // ④ 电量填充
        val fillW = innerW * (percent.coerceIn(0, 100) / 100f)
        if (fillW > 0f) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(innerLeft, innerTop),
                size = Size(fillW, innerH),
                cornerRadius = innerRadius
            )
        }
        // ⑤ 充电闪电（深色笔画压在绿色上；尺寸按内区高度收，不撑破电池）
        if (charging) {
            val bw = innerH * 0.82f
            val bh = innerH * 0.94f
            val ox = innerLeft + (innerW - bw) / 2f
            val oy = innerTop + (innerH - bh) / 2f
            drawPath(
                path = boltPath(ox, oy, bw, bh),
                color = Color(0xFF06180B)
            )
        }
    }
}

/** 闪电多边形（单位方框 [0,1]² 缩放到电池内区中央），手工给定点序，无自交 */
private fun boltPath(ox: Float, oy: Float, bw: Float, bh: Float): Path = Path().apply {
    fun x(u: Float) = ox + u * bw
    fun y(v: Float) = oy + v * bh
    moveTo(x(0.62f), y(0.02f))
    lineTo(x(0.20f), y(0.52f))
    lineTo(x(0.46f), y(0.52f))
    lineTo(x(0.38f), y(0.98f))
    lineTo(x(0.80f), y(0.44f))
    lineTo(x(0.52f), y(0.44f))
    close()
}

/** 状态簇与右侧动作图标之间的细分隔线（把"状态信息"和"可点动作"分开）。
 *  internal 而非 private：private 顶层函数只在**同一文件**内可见，跨文件调用会编译不过。 */
@Composable
internal fun StatusDivider(height: Dp = 14.dp) {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = height)
            .background(Color(0x33FFFFFF))
    )
}
