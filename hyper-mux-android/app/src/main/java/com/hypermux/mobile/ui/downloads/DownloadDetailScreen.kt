package com.hypermux.mobile.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.mobile.data.cache.EpisodeDownload
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperCard
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.OfflineBanner
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.common.formatBytes
import com.hypermux.mobile.ui.navigation.navigatePlay
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.theme.Spacing

/**
 * 「我的下载」二级页：单部影片的集级列表。
 *
 * 分区（自上而下）：
 * - 离线 / "流量暂停"提示条；
 * - **「下载中」**：未完成集（下载中 / 等待网络 / 已暂停 / 失败），行内含即时速度与细进度，
 *   右侧逐集 暂停/继续/重试/删除；分区标题右侧 全部暂停/全部继续；
 * - **「已下载」**：已完成集，右侧逐集 播放/删除。
 *
 * 顶栏右侧「清空」= 删除该影片全部下载（带确认）。
 */
@Composable
fun DownloadDetailScreen(navController: NavController) {
    val vm: DownloadDetailViewModel = hiltViewModel()
    val group by vm.group.collectAsStateWithLifecycle()
    val metered by vm.meteredNetwork.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val isOffline by vm.isOffline.collectAsStateWithLifecycle()
    val managerWaiting by vm.managerWaiting.collectAsStateWithLifecycle()
    val cellular by vm.isCellularNetwork.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }

    val episodes = group?.episodes.orEmpty()
    val unfinished = episodes.filter { !it.isDownloaded }
    val finished = episodes.filter { it.isDownloaded }
    val activeHere = episodes.count { it.isActive }
    // 暂停条：管理器因运行条件挂起下载（计费流量等）且还有未完成集；继续条：流量未标计费、实际在流量上下载
    val showPausedBanner = wifiOnly && cellular && !isOffline && managerWaiting && unfinished.isNotEmpty()
    val showDataHint = !showPausedBanner && wifiOnly && cellular && !isOffline && activeHere > 0

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DownloadsTopBar(
                title = group?.title ?: "下载详情",
                onBack = { navController.popBackStack() },
                action = if (episodes.isNotEmpty()) {
                    {
                        Text(
                            text = "清空",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .bounceClick(pressedScale = 0.94f) { showClearDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    null
                }
            )
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
                )
            ) {
                when {
                    episodes.isEmpty() -> {
                        item(key = "empty") {
                            Spacer(Modifier.height(48.dp))
                            EmptyView(
                                text = "还没有下载内容",
                                sub = "返回后在影片详情页点「缓存」选集下载"
                            )
                        }
                    }
                    else -> {
                        if (unfinished.isNotEmpty()) {
                            item(key = "header-downloading") {
                                SectionHeader(
                                    title = "下载中 · ${unfinished.size}",
                                    action = {
                                        if (unfinished.any { it.isActive }) {
                                            HyperButton(
                                                text = "全部暂停",
                                                onClick = { vm.pauseAll() },
                                                style = HyperButtonStyle.Secondary,
                                                small = true
                                            )
                                        } else {
                                            HyperButton(
                                                text = "全部继续",
                                                onClick = { vm.resumeAll() },
                                                style = HyperButtonStyle.Ghost,
                                                small = true
                                            )
                                        }
                                    }
                                )
                            }
                            items(unfinished, key = { it.mediaId }) { ep ->
                                EpisodeCard(
                                    item = ep,
                                    metered = metered,
                                    isOffline = isOffline,
                                    managerWaiting = managerWaiting,
                                    vm = vm,
                                    onPlay = null // 未完成集没有播放入口
                                )
                            }
                        }
                        if (finished.isNotEmpty()) {
                            item(key = "header-done") {
                                SectionHeader(title = "已下载 · ${finished.size}")
                            }
                            items(finished, key = { it.mediaId }) { ep ->
                                EpisodeCard(
                                    item = ep,
                                    metered = metered,
                                    isOffline = isOffline,
                                    managerWaiting = managerWaiting,
                                    vm = vm,
                                    onPlay = {
                                        if (ep.videoId > 0L) {
                                            navController.navigatePlay(vm.movieId, ep.videoId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        HyperDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空「${group?.title ?: ""}」的下载") },
            text = {
                Text(
                    text = "将删除该影片全部 ${episodes.size} 集下载内容（共 ${formatBytes(episodes.sumOf { it.bytes })}），不可恢复。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                HyperButton(
                    text = "清空",
                    onClick = {
                        vm.clearGroup()
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

/** 集数行 + 统一卡片容器 */
@Composable
private fun EpisodeCard(
    item: EpisodeDownload,
    metered: Boolean,
    isOffline: Boolean,
    managerWaiting: Boolean,
    vm: DownloadDetailViewModel,
    onPlay: (() -> Unit)?
) {
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        EpisodeRow(
            item = item,
            metered = metered,
            online = !isOffline,
            managerWaiting = managerWaiting,
            onPlay = onPlay,
            onPause = { vm.pause(item) },
            onResume = { vm.resume(item) },
            onRemove = { vm.remove(item) }
        )
    }
}
