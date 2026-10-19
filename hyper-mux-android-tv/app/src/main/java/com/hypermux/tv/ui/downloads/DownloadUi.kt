package com.hypermux.tv.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.tv.data.cache.EpisodeDownload
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperCard
import com.hypermux.tv.ui.common.HyperIconButton
import com.hypermux.tv.ui.common.formatBytes
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvTopBar
import java.util.Locale
import kotlin.math.roundToInt

/** 速度文案：≥1MB 用 MB/s（保留 1 位），否则 KB/s；0 → 空串（调用方自己决定占位文案） */
internal fun formatSpeed(bps: Long): String = when {
    bps <= 0L -> ""
    bps >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
    else -> "${(bps / 1024).toInt().coerceAtLeast(1)} KB/s"
}

/**
 * 按"相邻两次轮询的字节差"估算单集速度（2s 轮询周期）：
 * - 首次采样无差值 → 0（下一拍就有真值）；
 * - 字节不增长 → 0（真实卡住/排队等待的信号，界面上表现为"0 KB/s"）；
 * - 正常增长 → 与上次平滑各取一半，避免单拍抖动；
 * - 任务不活动时清掉采样点，避免恢复时拿旧差值算出假速度。
 * 页面重新打开会新建实例，首拍速度为 0 属正常。
 */
internal class SpeedTracker {
    private val lastTime = HashMap<String, Long>()
    private val lastBytes = HashMap<String, Long>()
    private val smoothSpeed = HashMap<String, Long>()

    fun speedOf(mediaId: String, bytes: Long, active: Boolean, now: Long): Long {
        if (!active) {
            lastTime.remove(mediaId)
            lastBytes.remove(mediaId)
            smoothSpeed.remove(mediaId)
            return 0L
        }
        val prevBytes = lastBytes[mediaId]
        val prevTime = lastTime[mediaId]
        lastBytes[mediaId] = bytes
        lastTime[mediaId] = now

        val prev = smoothSpeed[mediaId] ?: 0L
        if (prevBytes != null && prevTime != null) {
            val dt = now - prevTime
            if (dt >= 1_000L) {
                val raw = if (bytes > prevBytes) (bytes - prevBytes) * 1000L / dt else 0L
                // 间隔过久（轮询被挂起/刚恢复）直接用最新值，不与旧值平均
                smoothSpeed[mediaId] = if (dt > 10_000L) raw else (prev + raw) / 2
            }
        }
        return smoothSpeed[mediaId] ?: 0L
    }
}

/** 一集的状态文案（下载中 / 等待 / 已暂停 / 失败 / 已下载）
 * @param managerWaiting DownloadManager 正因运行条件不满足而挂起下载（仅 Wi-Fi + 移动网络 / 断网） */
internal fun episodeStatusText(
    item: EpisodeDownload,
    metered: Boolean,
    online: Boolean,
    managerWaiting: Boolean
): String = when {
    // 排队中的集（运行条件不满足被挂起，如仅 Wi-Fi + 移动网络）：直接给"等什么"，而不是"下载中"
    item.isQueued -> when {
        online && metered -> "等待 Wi-Fi 下载（当前移动网络）"
        else -> "等待网络恢复"
    }
    item.isActive -> buildString {
        append("下载中 ${item.percent.roundToInt()}%")
        if (item.totalBytes > 0L) append(" · ${formatBytes(item.bytes)}/${formatBytes(item.totalBytes)}")
        else append(" · ${formatBytes(item.bytes)}")
        append(" · ").append(formatSpeed(item.speedBytesPerSec).ifEmpty { "测速中…" })
    }
    item.isFailed -> "下载失败，点右侧「重试」"
    item.isUserPaused -> "已暂停 · ${formatBytes(item.bytes)}"
    // 非用户暂停的"停着"（如条件挂起后遗留的 STOPPED 状态）→ 给可读原因而不是干巴巴的"暂停"
    item.isPaused ->
        if (managerWaiting) {
            if (online && metered) "等待 Wi-Fi（当前移动网络）" else "等待网络恢复"
        } else {
            "已暂停 · ${formatBytes(item.bytes)}"
        }
    else -> "已下载 · ${formatBytes(item.bytes)}"
}

/**
 * 二级页顶栏（TV 版）：直接**委托给** [TvTopBar]（48 计划 §10 组件映射表）。
 *
 * 手机端这颗「38dp 圆形返回钮 + 18sp 标题」在 TV 上有两个问题：
 * 1) 38dp 的触摸目标在 10 英尺视距下太小、且 `bounceClick` 的"按压回弹"遥控器看不见；
 * 2) 返回钮是页面里唯一的"出口"，必须能一眼看见焦点 → 需要焦点描边。
 * [TvTopBar] 的返回钮 56dp + `tvClickable` 焦点放大/描边 + 30sp 标题，两个问题一起解决。
 * 保留本函数名与调用点，下载两级页面因此零改动即完成顶栏 TV 化。
 */
@Composable
internal fun DownloadsTopBar(
    title: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    TvTopBar(
        title = title,
        onBack = onBack,
        actions = {
            if (action != null) action()
        }
    )
}

/** 1dp 发丝线（页面背景上的分隔线走主题色 hc.line） */
@Composable
internal fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(hyperColors().line)
    )
}

/** 3dp 细进度条（品牌绿渐变） */
@Composable
internal fun ThinProgress(percent: Float, modifier: Modifier = Modifier) {
    val hc = hyperColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Brush.horizontalGradient(hc.progressGradient))
        )
    }
}

/**
 * 分区小标题（「我的下载」二级页："下载中 · N" / "已下载 · N"）：
 * 6dp 品牌渐变竖条 + 20sp ExtraBold + 右侧操作位（暂停/继续全部）。
 * 手机端是 4dp/14sp，10 英尺视距下偏小，这里整体放大。
 */
@Composable
internal fun SectionHeader(
    title: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.verticalGradient(hyperColors().primaryGradient))
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (action != null) action()
    }
}

/**
 * 「下载中」区的集数行：名称 + 状态（含即时速度）+ 细进度条 + 右侧操作。
 * 非编辑态下整行不可点（避免误触播放未下完的集），操作都走右侧图标。
 * [onPlay] 传 null 时不显示播放入口（只有"已下载"区传）。
 *
 * TV：行高 52→64dp、字号 14/12→18/16sp、图标按钮 30/38→44/52dp —— 手机规格在
 * 10 英尺视距下既读不清也点不准（遥控器只有焦点态，没有"手指精度"）。
 */
@Composable
internal fun EpisodeRow(
    item: EpisodeDownload,
    metered: Boolean,
    online: Boolean,
    managerWaiting: Boolean,
    onPlay: (() -> Unit)?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvSize.infoRowHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = episodeStatusText(item, metered, online, managerWaiting),
                    fontSize = 16.sp,
                    color = when {
                        item.isFailed -> MaterialTheme.colorScheme.error
                        item.isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
            // 删除用小号图标，主操作（暂停/继续/重试/播放）用标准号
            HyperIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = "删除",
                onClick = onRemove,
                size = 44.dp,
                iconSize = 22.dp
            )
            when {
                item.isActive -> HyperIconButton(
                    icon = Icons.Filled.Pause,
                    contentDescription = "暂停",
                    onClick = onPause,
                    size = TvSize.iconButton,
                    iconSize = 26.dp
                )
                item.isFailed -> HyperIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "重试",
                    onClick = onResume,
                    tint = MaterialTheme.colorScheme.primary,
                    size = TvSize.iconButton,
                    iconSize = 26.dp
                )
                item.isDownloaded -> onPlay?.let { play ->
                    HyperIconButton(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        onClick = play,
                        tint = MaterialTheme.colorScheme.primary,
                        size = TvSize.iconButton,
                        iconSize = 26.dp
                    )
                }
                else -> HyperIconButton(
                    icon = Icons.Filled.PlayArrow,
                    contentDescription = "继续",
                    onClick = onResume,
                    tint = MaterialTheme.colorScheme.primary,
                    size = TvSize.iconButton,
                    iconSize = 26.dp
                )
            }
        }
        if (item.isActive) {
            ThinProgress(
                percent = item.percent,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
        }
    }
}

/**
 * 移动网络提示条（「仅 Wi-Fi 下载」开启且当前是移动数据时显示，消除"列表没动 = 卡住了"的误解）：
 * - [onUseMobileData] != null → 暂停态（下载管理器正因运行条件不满足而挂起，如计费流量或被限网）：
 *   红色条 +「用流量继续」一键解除（关开关并恢复全部下载）；
 * - == null → 继续态（运营商未把流量标为计费，系统正在流量上正常下载）：中性条，纯告知。
 */
@Composable
internal fun NetworkNoteBanner(onUseMobileData: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val paused = onUseMobileData != null
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (paused) "当前移动数据，下载已暂停"
                    else "仅 Wi-Fi 下载已开启 · 当前移动数据",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (paused) cs.error else cs.primary
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (paused) "连上 Wi-Fi 后自动继续"
                    else "正在使用移动数据下载",
                    fontSize = 16.sp,
                    color = cs.onSurfaceVariant
                )
            }
            if (onUseMobileData != null) {
                HyperButton(
                    text = "用流量继续",
                    onClick = onUseMobileData,
                    style = HyperButtonStyle.Ghost,
                    small = true
                )
            }
        }
    }
}

/** 空闲对勾（一级行"全部下完"状态图标复用位，按需取用） */
@Composable
internal fun DoneCheck() {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = "全部已下载",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp)
    )
}
