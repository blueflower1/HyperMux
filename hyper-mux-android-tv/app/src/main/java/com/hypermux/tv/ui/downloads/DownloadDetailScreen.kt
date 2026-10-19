@file:OptIn(ExperimentalTvFoundationApi::class)

package com.hypermux.tv.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import com.hypermux.tv.data.cache.EpisodeDownload
import com.hypermux.tv.ui.common.EmptyView
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperCard
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.OfflineBanner
import com.hypermux.tv.ui.common.formatBytes
import com.hypermux.tv.ui.navigation.navigatePlay
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.tv.TvAutoFocus

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

    // 首屏焦点：给顶栏「清空」（页面唯一常驻可点项；无下载内容时页面本就无操作可做）
    val firstFocus = remember { FocusRequester() }

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
                        HyperButton(
                            text = "清空",
                            onClick = { showClearDialog = true },
                            style = HyperButtonStyle.Ghost,
                            small = true,
                            focusRequester = firstFocus
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
            TvLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.page,
                    end = Spacing.page,
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

    // 首屏焦点（§6.1）：有下载内容时落在顶栏「清空」；空页面只有 BACK 可走
    TvAutoFocus(firstFocus, ready = episodes.isNotEmpty())

    if (showClearDialog) {
        HyperDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空「${group?.title ?: ""}」的下载") },
            text = {
                Text(
                    text = "将删除该影片全部 ${episodes.size} 集下载内容（共 ${formatBytes(episodes.sumOf { it.bytes })}），不可恢复。",
                    fontSize = 18.sp,
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
