package com.hypermux.mobile.ui.player

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 播放页专属自绘图标集（**全部替代** `Icons.Filled.*` 等 Material 标准图标，去掉"安卓原生感"）。
 *
 * 统一绘制规范（与整体「深色底 + 品牌绿 + 大圆角/胶囊」设计语言一致）：
 * - 24×24 网格；线性图标统一 1.8dp（强调笔画 2~3dp），**圆头线帽 + 圆角折角**（Round cap/join），
 *   转角用二次曲线（Q/q）手工倒圆，观感比 Material 生硬的直角图标更轻、更现代；
 * - 实面图标（播放/暂停/下一集/锁身等）用 **fill + 同色圆角描边** 的组合，
 *   让尖角/直边自然长出圆润倒角，与线性图标视觉重量一致。
 *
 * 实现说明：图标用 **SVG path 字符串**描述（比手撸 PathNode 节点列表直白得多），
 * 启动时经 [PathBuilder.parseSvgPath] 解析为 [androidx.compose.ui.graphics.vector.PathNode] 节点
 * （SVG `a` 圆弧命令按 W3C F.6.5 转成三次贝塞尔曲线）。
 * 颜色一律走**调用方 tint**：节点内部填/描都取名义黑（与 Material 图标生成器一致），
 * 由 [androidx.compose.material3.Icon] 的 `tint` 统一换色——控制条上叠深底时给白、强调态给品牌绿。
 */
internal object PlayerIcons {

    /** 内部名义色：最终显示色由 Icon(tint=…) 覆盖（行为与 Material 生成图标一致） */
    private val NOMINAL = SolidColor(Color.Black)

    /** 线性图标统一线宽（24 网格单位） */
    private const val STROKE = 1.8f

    /** 内部：线性（纯描边）路径——圆头线帽 + 圆角折角 */
    private fun ImageVector.Builder.line(d: String, width: Float = STROKE) {
        val pb = PathBuilder().parseSvgPath(d)
        addPath(
            pathData = pb.nodes,
            stroke = NOMINAL,
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }

    /** 内部：实面路径（fill + 同色圆角描边 → 尖角自动倒圆，与线性图标视觉重量一致） */
    private fun ImageVector.Builder.solid(d: String, softening: Float, evenOdd: Boolean = false) {
        val pb = PathBuilder().parseSvgPath(d)
        addPath(
            pathData = pb.nodes,
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = NOMINAL,
            stroke = NOMINAL,
            strokeLineWidth = softening,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }

    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    /** 返回：细线描边 + 圆头，替代 Material 粗胖的 ArrowBack */
    val Back: ImageVector by lazy {
        build("PlayerBack") {
            line("M18.5 12H5.5 M10.4 7L5.5 12l4.9 5", width = 1.9f)
        }
    }

    /** 播放：圆角三角（实面 + 倒角） */
    val Play: ImageVector by lazy {
        build("PlayerPlay") {
            solid("M9.4 6L18.8 12l-9.4 6Z", softening = 2.4f)
        }
    }

    /** 暂停：两根圆头竖条 */
    val Pause: ImageVector by lazy {
        build("PlayerPause") {
            line("M9.3 6.6v10.8 M14.7 6.6v10.8", width = 3f)
        }
    }

    /** 进入全屏：四角折角括号（圆润） */
    val Fullscreen: ImageVector by lazy {
        build("PlayerFullscreen") {
            line(
                "M9 4.5H6.5Q4.5 4.5 4.5 6.5V9 " +
                    "M15 4.5h2.5q2 0 2 2V9 " +
                    "M4.5 15v2.5q0 2 2 2H9 " +
                    "M19.5 15v2.5q0 2-2 2h-2.5"
            )
        }
    }

    /** 退出全屏：四角朝内收（圆润） */
    val ExitFullscreen: ImageVector by lazy {
        build("PlayerExitFullscreen") {
            line(
                "M4.5 8.5H7q1.5 0 1.5-1.5V4.5 " +
                    "M19.5 8.5H17q-1.5 0-1.5-1.5V4.5 " +
                    "M4.5 15.5H7q1.5 0 1.5 1.5v2.5 " +
                    "M19.5 15.5H17q-1.5 0-1.5 1.5v2.5"
            )
        }
    }

    /** 下一集：圆角三角 + 圆头竖条 */
    val NextEpisode: ImageVector by lazy {
        build("PlayerNextEpisode") {
            solid("M7.8 6.6L15.2 12l-7.4 5.4Z", softening = 2f)
            line("M18.5 6.9v10.2", width = 2.2f)
        }
    }

    /** 选集：2×2 圆角方块网格（比横线列表图标更贴"选集/目录"语义） */
    val Episodes: ImageVector by lazy {
        build("PlayerEpisodes") {
            // 24 网格内 2×2 方块：每块 6.6，块间距 1.8，圆角 1.8
            line("M6.3 4.5h3q1.8 0 1.8 1.8v3q0 1.8-1.8 1.8h-3Q4.5 11.1 4.5 9.3V6.3Q4.5 4.5 6.3 4.5Z")
            line("M14.7 4.5h3q1.8 0 1.8 1.8v3q0 1.8-1.8 1.8h-3q-1.8 0-1.8-1.8V6.3q0-1.8 1.8-1.8Z")
            line("M6.3 12.9h3q1.8 0 1.8 1.8v3q0 1.8-1.8 1.8h-3q-1.8 0-1.8-1.8V12.9q0-1.8 1.8-1.8Z")
            line("M14.7 12.9h3q1.8 0 1.8 1.8v3q0 1.8-1.8 1.8h-3q-1.8 0-1.8-1.8V12.9q0-1.8 1.8-1.8Z")
        }
    }

    /** 锁屏：圆角锁身（含锁孔）+ 闭合锁梁 */
    val Lock: ImageVector by lazy {
        build("PlayerLock") {
            // 锁身与锁孔合并在一个 path 里，靠 EvenOdd 填充规则挖出锁孔
            solid(
                "M7.6 10.8h8.8q1.9 0 1.9 1.9v5.6q0 1.9-1.9 1.9H7.6q-1.9 0-1.9-1.9V12.7q0-1.9 1.9-1.9Z" +
                    " M12 13.55a1.35 1.35 0 1 0 0 2.7a1.35 1.35 0 1 0 0-2.7Z",
                softening = 1.2f,
                evenOdd = true
            )
            line("M8.9 10.5V8.3a3.1 3.1 0 0 1 6.2 0v2.2")
        }
    }

    /** 解锁：锁身同上，锁梁右侧断开下垂 */
    val Unlock: ImageVector by lazy {
        build("PlayerUnlock") {
            solid(
                "M7.6 10.8h8.8q1.9 0 1.9 1.9v5.6q0 1.9-1.9 1.9H7.6q-1.9 0-1.9-1.9V12.7q0-1.9 1.9-1.9Z" +
                    " M12 13.55a1.35 1.35 0 1 0 0 2.7a1.35 1.35 0 1 0 0-2.7Z",
                softening = 1.2f,
                evenOdd = true
            )
            line("M8.9 10.5V8.3a3.1 3.1 0 0 1 6.2 0v1.4")
        }
    }

    /** 缓存本集：圆头下箭头 + 圆角托盘 */
    val Download: ImageVector by lazy {
        build("PlayerDownload") {
            line("M12 4.6v9.3 M8 10.3l4 4.2 4-4.2")
            line("M4.7 16.4v1q0 2 2 2h10.6q2 0 2-2v-1")
        }
    }

    /** 已缓存：圆头对勾 + 圆角托盘 */
    val DownloadDone: ImageVector by lazy {
        build("PlayerDownloadDone") {
            line("M7.9 10.9l3.3 3.3 4.9-5.8")
            line("M4.7 16.4v1q0 2 2 2h10.6q2 0 2-2v-1")
        }
    }

    /** 关闭：圆头 X（细于 Material 版） */
    val Close: ImageVector by lazy {
        build("PlayerClose") {
            line("M6.9 6.9l10.2 10.2 M17.1 6.9L6.9 17.1", width = 1.9f)
        }
    }

    /** 对勾：圆头粗勾（倍速面板选中项） */
    val Check: ImageVector by lazy {
        build("PlayerCheck") {
            line("M5.8 12.6L9.9 16.7 18.2 7.3", width = 2.2f)
        }
    }

    /** 快进（长按倍速 OSD）：双圆头雪佛龙 */
    val FastForward: ImageVector by lazy {
        build("PlayerFastForward") {
            line("M4.8 6.8L11 12l-6.2 5.2 M13.2 6.8L19.4 12l-6.2 5.2", width = 2f)
        }
    }

    /** 亮度：圆线条太阳（同心圆 + 8 圆头短射线） */
    val Brightness: ImageVector by lazy {
        build("PlayerBrightness") {
            line("M12 8.7a3.3 3.3 0 1 0 0 6.6a3.3 3.3 0 1 0 0-6.6Z")
            line("M12 3.2v2.1 M12 18.7v2.1 M3.2 12h2.1 M18.7 12h2.1")
            line("M5.7 5.7l1.7 1.7 M16.6 16.6l1.7 1.7 M18.3 5.7l-1.7 1.7 M7.4 16.6l-1.7 1.7")
        }
    }

    /** 音量：圆角喇叭身 + 两道圆头声波 */
    val VolumeUp: ImageVector by lazy {
        build("PlayerVolumeUp") {
            solid(
                "M5.7 9.7h1.7l4.2-3.8q.55-.5 1.3-.5h2.3q.9 0 .9.9v10.4q0 .9-.9.9h-2.3q-.75 0-1.3-.5" +
                    "L7.4 14.3H5.7q-.9 0-.9-.9v-2.8q0-.9.9-.9Z",
                softening = 1.6f
            )
            line("M17.8 9.8a2.5 2.5 0 0 1 0 4.4 M20.1 8.2a4.3 4.3 0 0 1 0 7.6")
        }
    }

    /** 静音：喇叭身 + 圆头 X（替代 Material 的斜杠，观感更轻） */
    val VolumeOff: ImageVector by lazy {
        build("PlayerVolumeOff") {
            solid(
                "M5.7 9.7h1.7l4.2-3.8q.55-.5 1.3-.5h2.3q.9 0 .9.9v10.4q0 .9-.9.9h-2.3q-.75 0-1.3-.5" +
                    "L7.4 14.3H5.7q-.9 0-.9-.9v-2.8q0-.9.9-.9Z",
                softening = 1.6f
            )
            line("M17.6 10l4.6 4.4 M22.2 10l-4.6 4.4")
        }
    }
}

/** 2π（kotlin.math.TAU 是 Kotlin 2.1 才加入的，本项目的 Kotlin 版本用 PI 自定） */
private const val TWO_PI = 2.0 * PI

/* =========================================================================
 * SVG path 字符串 → PathNode 解析
 * （24 网格里的曲线/圆弧用 svg 记法写最直观；解析结果缓存进 ImageVector，
 *  只在屏幕首次显示对应图标时跑一次）
 * ========================================================================= */

/**
 * 支持 M/m、L/l、H/h、V/v、Q/q、A/a、Z/z。
 * 命令后的隐含重复参数组（连续写两组坐标）按 SVG 语法规则处理；
 * `A` 圆弧按 W3C《SVG Paths Mini-spec》F.6.5 逐段转成三次贝塞尔（≤90°/段）。
 */
private fun PathBuilder.parseSvgPath(d: String): PathBuilder {
    var cur = 0
    var curX = 0f
    var curY = 0f
    var subX = 0f
    var subY = 0f
    var hasSegment = false

    fun skipSpaces() {
        while (cur < d.length && (d[cur] == ' ' || d[cur] == ',')) cur++
    }

    /**
     * 数字：[+-]? ( \d+ ( '.' \d* )? | '.' \d+ ) ( [eE] [+-]? \d+ )?
     *
     * **一个大坑**：SVG 允许省略分隔符 —— `".9.9"` 是两个数（.9 与 .9）、`"0.6.5"` 是 0.6 与 .5。
     * 所以小数点**最多只能吃一个**。早期版本在这里连着吃掉两个点，substring 变成 ".9.9"，
     * 交给 `toFloat()` 就抛 `NumberFormatException: multiple points`
     * （音量图标 PlayerIcons.VolumeUp/VolumeOff 里的喇叭路径正好有 `q.9 0 .9.9`，
     *  首帧渲染即崩 —— 表现为"右半屏滑动调音量直接闪退"）。
     */
    fun num(): Float {
        skipSpaces()
        val s = cur
        if (cur < d.length && (d[cur] == '+' || d[cur] == '-')) cur++
        var intDigits = 0
        while (cur < d.length && d[cur].isDigit()) { cur++; intDigits++ }
        var fracDigits = 0
        if (cur < d.length && d[cur] == '.') {
            cur++
            while (cur < d.length && d[cur].isDigit()) { cur++; fracDigits++ }
        }
        check(intDigits + fracDigits > 0) { "非法 SVG 数字：'$d' @ $s" }
        // 可选指数；若后面不是合法指数（例如紧跟命令字母 v/h/L），回退，别把命令字母吃进数字
        if (cur < d.length && (d[cur] == 'e' || d[cur] == 'E')) {
            val save = cur
            cur++
            if (cur < d.length && (d[cur] == '+' || d[cur] == '-')) cur++
            var expDigits = 0
            while (cur < d.length && d[cur].isDigit()) { cur++; expDigits++ }
            if (expDigits == 0) cur = save
        }
        return d.substring(s, cur).toFloat()
    }

    // 数字后的"隐含重复"判定：下一个 token 是数字（"l4 4.2 -1 2"、"4.2-4" 都要接住）
    fun numFollows(): Boolean {
        skipSpaces()
        if (cur >= d.length) return false
        val ch = d[cur]
        return ch.isDigit() || ch == '.' || ch == '+' || ch == '-'
    }

    while (true) {
        skipSpaces()
        if (cur >= d.length) break
        val cmd = d[cur]
        cur++
        val rel = cmd in 'a'..'z'
        when (cmd.uppercaseChar()) {
            'M' -> {
                var ax = num()
                var ay = num()
                if (rel) { ax += curX; ay += curY }
                curX = ax; curY = ay
                subX = ax; subY = ay
                moveTo(curX, curY)
                hasSegment = true
            }
            'L' -> while (true) {
                var ax = num()
                var ay = num()
                if (rel) { ax += curX; ay += curY }
                if (!hasSegment) { moveTo(ax, ay); hasSegment = true }
                curX = ax; curY = ay
                lineTo(ax, ay)
                if (!numFollows()) break
            }
            'H' -> while (true) {
                val v = num()
                if (rel) curX += v else curX = v
                if (!hasSegment) { moveTo(curX, curY); hasSegment = true }
                horizontalLineTo(curX)
                if (!numFollows()) break
            }
            'V' -> while (true) {
                val v = num()
                if (rel) curY += v else curY = v
                if (!hasSegment) { moveTo(curX, curY); hasSegment = true }
                verticalLineTo(curY)
                if (!numFollows()) break
            }
            'Q' -> while (true) {
                var c1x = num()
                var c1y = num()
                var ax = num()
                var ay = num()
                if (rel) { c1x += curX; c1y += curY; ax += curX; ay += curY }
                if (!hasSegment) { moveTo(curX, curY); hasSegment = true }
                curX = ax; curY = ay
                quadTo(c1x, c1y, ax, ay)
                if (!numFollows()) break
            }
            'A' -> while (true) {
                val rx = num()
                val ry = num()
                val theta = num()
                val large = num()
                val sweep = num()
                var ex = num()
                var ey = num()
                if (rel) { ex += curX; ey += curY }
                if (!hasSegment) { moveTo(curX, curY); hasSegment = true }
                appendArc(rx, ry, theta, large.toInt(), sweep.toInt(), ex, ey, curX, curY)
                curX = ex; curY = ey
                if (!numFollows()) break
            }
            'Z' -> {
                curX = subX; curY = subY
                close()
            }
            else -> error("不支持的 SVG 命令 '$cmd'：'$d'")
        }
    }
    return this
}

/**
 * SVG `A a rx ry theta laf sf x y` 圆弧 → 三次贝塞尔段（W3C F.6.5 标准推导）。
 * 每段 ≤90°，用 `k = 4/3·tan(Δ/4)` 控制点近似，单段半径误差 <0.03%，播放器图标尺度下完全不可见。
 * 统一转绝对坐标走 [PathBuilder.curveTo]，避免依赖 ArcTo 节点的候选弧选择语义。
 */
private fun PathBuilder.appendArc(
    rx: Float,
    ry: Float,
    thetaDeg: Float,
    largeArc: Int,
    sweep: Int,
    x: Float,
    y: Float,
    preX: Float,
    preY: Float
) {
    var rX = abs(rx.toDouble())
    var rY = abs(ry.toDouble())
    if (rX < 1e-7 || rY < 1e-7) { lineTo(x, y); return }
    if (abs(preX - x) < 1e-7 && abs(preY - y) < 1e-7) return // 零长弧：原地不动

    val f = (thetaDeg.toDouble()) * (PI / 180.0)
    val cosF = cos(f)
    val sinF = sin(f)
    val dx1 = (preX - x) / 2.0
    val dy1 = (preY - y) / 2.0
    val x1p = cosF * dx1 + sinF * dy1
    val y1p = -sinF * dx1 + cosF * dy1

    // 端点超出椭圆时放大半径（SVG 规定的隐式半径膨胀）
    val lambda = (x1p * x1p) / (rX * rX) + (y1p * y1p) / (rY * rY)
    if (lambda > 1.0) {
        val s = sqrt(lambda)
        rX *= s
        rY *= s
    }

    // F.6.5：largeArc 与 sweep 不同 → +1，相同 → −1（决定取哪一侧椭圆中心）
    val sign = if (largeArc == sweep) -1.0 else 1.0
    val denom = (rX * rX) * (y1p * y1p) + (rY * rY) * (x1p * x1p)
    val numer = (rX * rX) * (rY * rY) - (rX * rX) * (y1p * y1p) - (rY * rY) * (x1p * x1p)
    if (denom < 1e-12) { lineTo(x, y); return }
    // 半圆等边界情况 numer 理论为 0，浮点下的微小负值不能当成退化（否则整段弧退化成直线）
    val c = sign * sqrt(maxOf(0.0, numer) / denom)
    val cx2p = c * rX * y1p / rY
    val cy2p = -c * rY * x1p / rX
    val centerX = cosF * cx2p - sinF * cy2p + (preX + x) / 2.0
    val centerY = sinF * cx2p + cosF * cy2p + (preY + y) / 2.0

    fun angle(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dot = (ax * bx + ay * by) /
            ((sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by)))
        var a = acos(dot.coerceIn(-1.0, 1.0))
        if (ax * by - ay * bx < 0.0) a = -a
        return a
    }

    // W3C F.6.5 第 4 步（SVG2 B.2.4 eq 5.5/5.6）：
    // u = (P1−C)/r 分量式 = (x1p−cx2p, y1p−cy2p)/r；v = (P2−C)/r = (−x1p−cx2p, −y1p−cy2p)/r
    // （注意不是 x1p/r —— 必须减去中心向量，否则起角整体偏转）
    val ux = (x1p - cx2p) / rX
    val uy = (y1p - cy2p) / rY
    val vx = (-x1p - cx2p) / rX
    val vy = (-y1p - cy2p) / rY
    val startAngle = angle(1.0, 0.0, ux, uy)
    var delta = angle(ux, uy, vx, vy)
    // fS=0 → Δθ<0，fS=1 → Δθ>0（越界时按 ±360° 修正）
    if (delta > 0.0 && sweep == 0) delta -= TWO_PI
    if (delta < 0.0 && sweep == 1) delta += TWO_PI

    val segments = maxOf(1, ceil(abs(delta) / (PI / 2.0)).toInt())
    val step = delta / segments

    // 单位圆上的点 (xUnit, yUnit) → 椭圆 (缩放 rX/rY) → 旋转 f → 平移到弧中心
    fun toAbsoluteOf(xUnit: Double, yUnit: Double): Pair<Double, Double> =
        (cosF * (xUnit * rX) - sinF * (yUnit * rY) + centerX) to
            (sinF * (xUnit * rX) + cosF * (yUnit * rY) + centerY)

    // 单位圆上的点 (cosα, sinα) → 世界坐标
    fun toAbsolute(a: Double): Pair<Double, Double> = toAbsoluteOf(cos(a), sin(a))

    for (k in 0 until segments) {
        val a0 = startAngle + k * step
        val a1 = a0 + step
        // 弧段控制点：P(α) ± κ·T(α)，T(α) = (−sin α, cos α) 为单位圆切线
        val kappa = 4.0 / 3.0 * tan(step / 4.0)
        val c1 = toAbsoluteOf(
            xUnit = cos(a0) - kappa * sin(a0),
            yUnit = sin(a0) + kappa * cos(a0)
        )
        val c2 = toAbsoluteOf(
            xUnit = cos(a1) + kappa * sin(a1),
            yUnit = sin(a1) - kappa * cos(a1)
        )
        val end = toAbsolute(a1)
        curveTo(
            c1.first.toFloat(), c1.second.toFloat(),
            c2.first.toFloat(), c2.second.toFloat(),
            end.first.toFloat(), end.second.toFloat()
        )
    }
}
