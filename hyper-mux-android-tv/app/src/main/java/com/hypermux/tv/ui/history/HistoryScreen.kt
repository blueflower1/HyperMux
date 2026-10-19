@file:OptIn(ExperimentalTvFoundationApi::class)

package com.hypermux.tv.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import com.hypermux.tv.data.model.HistoryVo
import com.hypermux.tv.ui.NetworkViewModel
import com.hypermux.tv.ui.common.CoverImage
import com.hypermux.tv.ui.common.EmptyView
import com.hypermux.tv.ui.common.ErrorState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.HyperIconButton
import com.hypermux.tv.ui.common.OfflineBanner
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.common.progressPct
import com.hypermux.tv.ui.common.remainText
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvKeys
import com.hypermux.tv.ui.tv.TvKeys.isMenu
import com.hypermux.tv.ui.tv.TvTopBar
import com.hypermux.tv.ui.tv.tvClickable

/**
 * 历史记录页（TV 版，48-tv-app-detailed.md §8.5）。
 *
 * 手机端一行 `HyperCard`（74dp 竖版封面 + 信息 + 两个图标按钮），**整行一个点击目标**；
 * TV 上一行拆成 **3 个可聚焦目标**：主区（OK = 继续播放）、「标记看完」、「移除」——
 * 遥控器没有长按/滑动手势，把操作显式暴露成焦点目标才是可见、可发现的。
 *
 * 底部导航换成左侧导航栏（由 AppNavigation 承载），本页只有顶栏「刷新」。
 */
@Composable
fun HistoryScreen(
    navController: NavController,
    onOpenMovie: (Long) -> Unit,
    onPlay: (movieId: Long, videoId: Long) -> Unit
) {
    val vm: HistoryViewModel = hiltViewModel()
    val networkVm: NetworkViewModel = hiltViewModel()

    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val isOffline by networkVm.isOffline.collectAsStateWithLifecycle()
    val snackbar by vm.snackbar.collectAsStateWithLifecycle(initialValue = null)

    var removeTarget by remember { mutableStateOf<HistoryVo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    var lastKey by rememberSaveable { mutableStateOf<Long?>(null) }
    val focusMap = remember { mutableMapOf<Long, FocusRequester>() }
    val retryRequester = remember { FocusRequester() }

    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.onSnackbarShown()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TvTopBar(
            title = "历史",
            subtitle = if (history.isEmpty()) null else "共 ${history.size} 条播放记录",
            actions = {
                HyperIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    onClick = { vm.load() }
                )
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && history.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.page),
                    contentAlignment = Alignment.TopCenter
                ) {
                    SkeletonPosterGrid(rows = 3, columns = TvSize.gridColumnsFhd)
                }

                error != null && history.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorState(message = error!!, onRetry = { vm.retry() }, focusRequester = retryRequester)
                    TvAutoFocus(retryRequester)
                }

                history.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyView(
                        text = "还没有播放记录",
                        sub = "去首页看看有什么好看的",
                        icon = Icons.Filled.History
                    )
                }

                else -> TvLazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.page,
                        end = Spacing.page,
                        top = Spacing.sm,
                        bottom = Spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isOffline) {
                        item { OfflineBanner(visible = true) }
                    }
                    items(items = history, key = { it.videoId }) { item ->
                        val r = focusMap.getOrPut(item.videoId) { FocusRequester() }
                        HistoryItem(
                            item = item,
                            onPlay = { onPlay(item.movieId, item.videoId) },
                            onOpenDetail = { onOpenMovie(item.movieId) },
                            onMarkDone = { vm.markDone(item) },
                            onRemove = { removeTarget = item },
                            modifier = Modifier
                                .focusRequester(r)
                                .onFocusChanged { if (it.isFocused) lastKey = item.videoId }
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.xl)
            )
        }
    }

    val ready = !isLoading && history.isNotEmpty()
    val restore = lastKey?.let { focusMap[it] } ?: history.firstOrNull()?.let { focusMap[it.videoId] }
    TvAutoFocus(restore, ready = ready)

    // 删除确认（MENU 键也可触发，见 HistoryItem）
    removeTarget?.let { item ->
        HyperDialog(
            onDismissRequest = { removeTarget = null },
            title = {
                Text(
                    "从历史中移除",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "《${item.title}》 S${item.seasonNo} · 第${item.episodeNo}集\n\n" +
                        "移除后该集观看记录会清空，之后重新播放会再生成。",
                    fontSize = TvType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                HyperButton(
                    text = "移除",
                    onClick = {
                        vm.remove(item)
                        removeTarget = null
                    }
                )
            },
            dismissButton = {
                HyperButton(
                    text = "取消",
                    onClick = { removeTarget = null },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }
}

/**
 * 历史条目（TV）：整行 3 个焦点目标 —— 主区（播放）/ 标记看完 / 移除。
 *
 * MENU 键等价手机端**长按**（进影片详情），对齐 §6.4「长按语义改用 MENU 键」。
 */
@Composable
private fun HistoryItem(
    item: HistoryVo,
    onPlay: () -> Unit,
    onOpenDetail: () -> Unit,
    onMarkDone: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = hyperColors()
    val finished = item.finished == 1
    val pct = if (finished) 100 else progressPct(item.positionMs, item.durationMs)
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 主区：OK = 继续播放（MENU = 详情）----
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .tvClickable(shape = RoundedCornerShape(12.dp), onClick = onPlay)
                .padding(Spacing.sm)
                .onMenuKey(onOpenDetail),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = item.coverUrl,
                letter = item.title,
                contentDescription = item.title,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(2f / 3f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.width(Spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = TvType.cardTitle,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "S${item.seasonNo} · 第${item.episodeNo}集" +
                        (item.categoryName?.let { " · $it" } ?: ""),
                    fontSize = TvType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                    ) {
                        if (pct > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct / 100f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Brush.horizontalGradient(hc.progressGradient))
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.md))
                    if (finished) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "已看完",
                                fontSize = TvType.meta,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(
                            text = remainText(item.positionMs, item.durationMs),
                            fontSize = TvType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(Spacing.md))

        // ---- 行操作：两个独立焦点目标（遥控器上没有"长按"可发现性）----
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!finished) {
                HyperIconButton(
                    icon = Icons.Filled.CheckCircle,
                    contentDescription = "标记看完",
                    onClick = onMarkDone,
                    size = 56.dp,
                    iconSize = 30.dp
                )
            }
            HyperIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = "移除",
                onClick = onRemove,
                size = 56.dp,
                iconSize = 30.dp
            )
        }
    }
}

/**
 * MENU 键钩子：TV 上「长按」的替代键位（§6.4）。
 *
 * 历史行主区按 MENU = 手机端长按 → 打开影片详情（不是播放）。
 * 只在 `KeyDown` 上消费，避免 KeyUp 再次触发。
 */
private fun Modifier.onMenuKey(onMenu: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.isMenu()) {
        onMenu()
        true
    } else {
        false
    }
}
