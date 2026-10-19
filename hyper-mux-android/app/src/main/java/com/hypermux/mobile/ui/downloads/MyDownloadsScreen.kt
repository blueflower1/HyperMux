package com.hypermux.mobile.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.mobile.data.cache.CacheStorageInfo
import com.hypermux.mobile.data.cache.MovieDownloadGroup
import com.hypermux.mobile.ui.common.CoverImage
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperCard
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.OfflineBanner
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.common.formatBytes
import com.hypermux.mobile.ui.navigation.navigateDownloadDetail
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 「我的下载」一级页（入口在「我的」页）：**只看片，不管理**。
 *
 * 布局（自上而下）：
 * - 离线提示条（无网络时）/ "流量暂停"提示条（仅 Wi-Fi 开着且当前移动网络时）；
 * - **存储与任务卡**：「已下载 X · 设备剩余 Y」+「清空」；有未完成任务时第二行
 *   「下载中 N 个 · 总速度」+ 全部暂停/继续；
 * - **影片列表**：每部影片一行（海报 + 片名 +「已下 N/M 集 · 大小」+ 状态），
 *   点行进二级页 [DownloadDetailScreen]（分「下载中 / 已下载」两区管理单集）。
 *
 * 主题：与旧中心页一致——整页在页面背景之上，一律主题色。
 */
@Composable
fun MyDownloadsScreen(navController: NavController) {
    val vm: MyDownloadsViewModel = hiltViewModel()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val totalSpeed by vm.totalSpeed.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val isOffline by vm.isOffline.collectAsStateWithLifecycle()
    val managerWaiting by vm.managerWaiting.collectAsStateWithLifecycle()
    val cellular by vm.isCellularNetwork.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }

    val allEpisodes = remember(groups) { groups.flatMap { it.episodes } }
    val activeCount = allEpisodes.count { it.isActive }
    val incompleteCount = allEpisodes.count { !it.isDownloaded }
    // 暂停条：管理器确实因运行条件挂起下载（如计费流量）+ 还有没下完的集
    val showPausedBanner = wifiOnly && cellular && !isOffline && managerWaiting && incompleteCount > 0
    // 继续条：流量未被标计费、系统实际在流量上下载 —— 明确告知"没卡住，在用流量下"
    val showDataHint = !showPausedBanner && wifiOnly && cellular && !isOffline && activeCount > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DownloadsTopBar(
                title = "我的下载",
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isOffline) OfflineBanner(visible = true)
            if (showPausedBanner) {
                Spacer(Modifier.height(Spacing.sm))
                NetworkNoteBanner(onUseMobileData = { vm.useMobileDataNow() })
            }
            if (showDataHint) {
                Spacer(Modifier.height(Spacing.sm))
                NetworkNoteBanner(onUseMobileData = null)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    // gridSideInset：窗口比 gridMaxWidth 宽时把整宽行卡收窄并居中（Compact 档恒为 0）
                    start = Spacing.page + LocalLayoutProfile.current.gridSideInset,
                    end = Spacing.page + LocalLayoutProfile.current.gridSideInset,
                    top = Spacing.sm,
                    bottom = Spacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item(key = "storage") {
                    StorageStrip(
                        storage = storage,
                        activeCount = activeCount,
                        incompleteCount = incompleteCount,
                        totalSpeed = totalSpeed,
                        onClearAll = { showClearDialog = true },
                        onPauseAll = { vm.pauseAll() },
                        onResumeAll = { vm.resumeAll() }
                    )
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Spacer(Modifier.height(48.dp))
                        EmptyView(
                            text = "还没有下载内容",
                            sub = "在影片详情页点「缓存」选集，开始显式下载"
                        )
                    }
                } else {
                    items(groups, key = { it.movieId }) { group ->
                        MovieRow(
                            group = group,
                            managerWaiting = managerWaiting,
                            onClick = { navController.navigateDownloadDetail(group.movieId) }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        HyperDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部下载") },
            text = {
                Text(
                    text = "将删除所有下载内容（共 ${formatBytes(storage.usedBytes)}），不可恢复。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                HyperButton(
                    text = "清空",
                    onClick = {
                        vm.clearAll()
                        showClearDialog = false
                    }
                )
            },
            dismissButton = {
                HyperButton(
                    text = "取消",
                    onClick = { showClearDialog = false },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }
}

// ---------------- 存储与任务卡 ----------------

/** 容量警告阈值：设备剩余 < 1GB 时剩余量红色提示（无容量上限，纯提示） */
private const val LOW_FREE_BYTES = 1L * 1024 * 1024 * 1024

@Composable
private fun StorageStrip(
    storage: CacheStorageInfo,
    activeCount: Int,
    incompleteCount: Int,
    totalSpeed: Long,
    onClearAll: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val lowFree = storage.freeBytes in 0..LOW_FREE_BYTES
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已下载 ${formatBytes(storage.usedBytes)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "设备剩余 ${formatBytes(storage.freeBytes)}",
                    fontSize = 12.sp,
                    color = if (lowFree) cs.error else cs.onSurfaceVariant
                )
            }
            if (storage.usedBytes > 0L) {
                HyperButton(
                    text = "清空",
                    onClick = onClearAll,
                    style = HyperButtonStyle.Ghost,
                    small = true
                )
            }
        }

        // 未完成任务汇总行：进行中 → "N 个下载中 · 速度 + 全部暂停"；全部停着 → "N 集待继续 + 全部继续"
        if (incompleteCount > 0) {
            Spacer(Modifier.height(10.dp))
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (activeCount > 0) {
                        Text(
                            text = buildString {
                                append("下载中 ").append(activeCount).append(" 个")
                                val s = formatSpeed(totalSpeed)
                                if (s.isNotEmpty()) append(" · ").append(s)
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "退出应用后仍会继续",
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "$incompleteCount 集待继续（已暂停 / 失败 / 等待网络）",
                            fontSize = 13.5.sp,
                            color = cs.onSurface
                        )
                    }
                }
                if (activeCount > 0) {
                    HyperButton(
                        text = "全部暂停",
                        onClick = onPauseAll,
                        style = HyperButtonStyle.Secondary,
                        small = true
                    )
                } else {
                    HyperButton(
                        text = "全部继续",
                        onClick = onResumeAll,
                        style = HyperButtonStyle.Ghost,
                        small = true
                    )
                }
            }
        }
    }
}

// ---------------- 影片行 ----------------

/**
 * 一级页影片行：小海报 + 片名 +「已下 N/M 集 · 大小」(+ 下载中 x% · 速度)，
 * 右侧状态位（百分比 / 对勾 / 箭头）。整行可点 → 二级页。
 */
@Composable
private fun MovieRow(group: MovieDownloadGroup, managerWaiting: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val done = group.completedCount
    val total = group.episodeCount
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick(pressedScale = 0.98f, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = group.coverUrl,
                letter = group.title,
                contentDescription = group.title,
                modifier = Modifier
                    .width(44.dp)
                    .height(60.dp),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("已下 ").append(done).append('/').append(total).append(" 集")
                        if (group.totalBytes > 0L) append(" · ").append(formatBytes(group.totalBytes))
                    },
                    fontSize = 12.5.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 第三行只给"有进行中任务"的片子：整体进度 + 实时速度
                if (group.activeCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("下载中 ").append(overallPercent(group).roundToInt()).append('%')
                            val s = group.episodes.filter { it.isActive }
                                .sumOf { it.speedBytesPerSec }.let(::formatSpeed)
                            if (s.isNotEmpty()) append(" · ").append(s)
                        },
                        fontSize = 12.sp,
                        color = cs.primary,
                        maxLines = 1
                    )
                } else if (managerWaiting && group.episodes.any { !it.isActive && !it.isDownloaded && !it.isFailed }) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "等待网络",
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                group.activeCount > 0 -> Text(
                    text = "${overallPercent(group).roundToInt()}%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary
                )
                done == total -> DoneCheck()
                else -> Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 整组进度（行内百分比用）：已下字节 / 总字节，未知总长时 0 */
private fun overallPercent(group: MovieDownloadGroup): Float {
    val total = group.episodes.sumOf { it.totalBytes }
    if (total <= 0L) return 0f
    return (group.episodes.sumOf { it.bytes }.toFloat() / total.toFloat()) * 100f
}
