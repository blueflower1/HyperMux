package com.hypermux.mobile.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hypermux.mobile.ui.util.observeServerUrl
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors
import com.hypermux.mobile.ui.util.rememberSettingsRepo
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- 文本工具 ----------

/** 毫秒 → "1:23:45" / "45:00" */
fun fmtDurationMs(ms: Long): String = fmtSeconds((ms / 1000).toInt())

/** 秒 → "1:23:45" / "45:00" */
fun fmtSeconds(totalSec: Int): String {
    if (totalSec <= 0) return "00:00"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/** 剩余时长文案："剩 12 分钟" / "剩 2 小时" */
fun remainText(positionMs: Long, durationMs: Long): String {
    val remainMs = durationMs - positionMs
    if (remainMs <= 0) return "已看完"
    val minutes = remainMs / 60_000
    return when {
        minutes >= 120 -> "剩 ${minutes / 60} 小时"
        minutes >= 1 -> "剩 ${minutes} 分钟"
        else -> "剩 1 分钟内"
    }
}

fun progressPct(positionMs: Long, durationMs: Long): Int {
    if (durationMs <= 0) return 0
    return ((positionMs.toDouble() / durationMs) * 100).toInt().coerceIn(0, 100)
}

fun ratingText(rating: Double?): String = rating?.let { "%.1f".format(it) } ?: "—"

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

// ---------- 通用组件 ----------

/** 分区标题：前置 4dp 渐变竖条（对齐 web .section-title::before） */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val hc = hyperColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(hc.primaryGradient))
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 空态（对齐 web .empty：图标半透明 + 标题 + 说明） */
@Composable
fun EmptyView(
    text: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector = Icons.Filled.MovieFilter
) {
    val hc = hyperColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = hc.text3,
            modifier = Modifier
                .size(30.dp)
                .alpha(0.5f)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = sub,
                color = hc.text3,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 错误态：空态 + 重试按钮 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.CloudOff
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyView(text = "加载失败", sub = message, icon = icon)
        if (onRetry != null) {
            HyperButton(
                text = "重试",
                onClick = onRetry,
                style = HyperButtonStyle.Primary
            )
        }
    }
}

/** 离线提示条 */
@Composable
fun OfflineBanner(visible: Boolean) {
    if (!visible) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, hyperColors().line),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.sm)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "网络已断开，展示缓存内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

/** 骨架屏：影片网格占位 */
@Composable
fun SkeletonPosterGrid(
    rows: Int = 2,
    columns: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(columns) {
                    Column(modifier = Modifier.weight(1f)) {
                        ShimmerBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ShimmerBlock(Modifier.fillMaxWidth(0.7f).height(12.dp))
                        Spacer(Modifier.height(6.dp))
                        ShimmerBlock(Modifier.fillMaxWidth(0.4f).height(10.dp))
                    }
                }
            }
        }
    }
}

/** 封面兜底占位（对齐 web .cover-fallback：渐变底 + 大字母） */
@Composable
fun LetterPlaceholder(letter: String?, modifier: Modifier = Modifier) {
    val hash = letter?.firstOrNull()?.code ?: 0
    val hue = (abs(hash) % 360).toFloat()
    val a = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.22f, 0.30f)))
    val b = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.28f, 0.20f)))
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                Brush.linearGradient(
                    colors = listOf(a, b),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(420f, 620f)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter?.take(1) ?: "?",
            // 首字压在固定的深色占位渐变上（不随主题）：用固定白色系，不用 hc.text3（亮色主题下偏暗）。
            color = Color(0xE6FFFFFF),
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * 远程图片改走自有服务缓存：`{server}/img?u=<encoded>`。
 * 后端会带 Referer 下载并落盘，之后由本站提供，规避豆瓣/TMDB 图床防盗链（豆瓣无 Referer 返回 418）。
 */
fun proxyImageUrl(url: String?, serverBase: String?): String? {
    if (url.isNullOrBlank()) return null
    if (serverBase.isNullOrBlank()) return url
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return url
    val base = serverBase.trimEnd('/')
    if (url.startsWith(base)) return url
    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
    return "$base/img?u=$encoded"
}

/**
 * 封面图：Coil 加载（淡入），失败/无 URL 时退化为首字占位。
 * [contentScale] / [alignment] 默认保持原行为（Crop + Center），可按需传入：
 * 例如横版剧照希望「顶部对齐裁切、不切脸」时传 ContentScale.Crop + Alignment.TopCenter。
 * [shape] 默认 14dp 圆角；详情页顶部沉浸式头图需要整宽直角时传 RectangleShape。
 */
@Composable
fun CoverImage(
    url: String?,
    letter: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    shape: Shape = MaterialTheme.shapes.medium
) {
    // 服务地址：优先实时值（设置里改完立即生效），退缓存值
    val liveBase by observeServerUrl()
    val cachedBase = runCatching { rememberSettingsRepo().cachedServerUrl }.getOrNull()
    val serverBase = liveBase ?: cachedBase
    // 远程图统一走本站 /img 代理：后端带 Referer 下载 + 落盘，并保证返回 image/* 类型
    // （网盘直链常返回 application/octet-stream，Coil 选不到解码器 → 只显示首字占位）
    val proxied = remember(url, serverBase) { proxyImageUrl(url, serverBase) }
    // 代理不可用时（后端未部署 /img 等）回退到原始地址再试一次
    var useRaw by remember(url, serverBase) { mutableStateOf(false) }
    val target = if (useRaw) url else proxied
    Box(modifier = modifier.clip(shape)) {
        if (!letter.isNullOrBlank()) {
            LetterPlaceholder(letter, Modifier.matchParentSize())
        }
        if (!target.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(target)
                    .crossfade(Motion.BASE)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                onError = {
                    if (!useRaw && !url.isNullOrBlank() && url != target) useRaw = true
                },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** 续播进度条（渐变填充，对齐 web） */
@Composable
fun ProgressBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val hc = hyperColors()
    val pct = progressPct(positionMs, durationMs)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (pct > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Brush.horizontalGradient(hc.progressGradient))
            )
        }
    }
}

/** 加载占位：整页骨架屏（原来只有一个小转圈，现统一为海报网格骨架） */
@Composable
fun LoadingDot(modifier: Modifier = Modifier) {
    val profile = LocalLayoutProfile.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        SkeletonPosterGrid(
            rows = 3,
            columns = profile.homeColumns,
            modifier = Modifier.padding(horizontal = profile.gridSideInset)
        )
    }
}
