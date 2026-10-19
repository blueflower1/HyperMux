package com.hypermux.mobile.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.mobile.data.model.HistoryVo
import com.hypermux.mobile.ui.NetworkViewModel
import com.hypermux.mobile.ui.common.CoverImage
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.ErrorState
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperCard
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.HyperIconButton
import com.hypermux.mobile.ui.common.OfflineBanner
import com.hypermux.mobile.ui.common.SkeletonPosterGrid
import com.hypermux.mobile.ui.common.progressPct
import com.hypermux.mobile.ui.common.remainText
import com.hypermux.mobile.ui.navigation.BottomNavBar
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/**
 * 历史记录页（§5.7）：
 * 每行一张 HyperCard（竖版 2:3 封面 + 标题/集号 + 细进度条 + 行操作）→ 点击继续看；
 * 行操作：标记看完 / 移除（确认弹层 HyperDialog）。
 * 列表已由后端保证「同一影片只保留最近一条」。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val profile = LocalLayoutProfile.current

    // Snackbar 提示
    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            vm.onSnackbarShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史", fontSize = 18.sp) },
                actions = {
                    HyperIconButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        onClick = { vm.load() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = { BottomNavBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && history.isEmpty() -> {
                    Box(Modifier.fillMaxSize()) {
                        SkeletonPosterGrid(
                            rows = 3,
                            columns = profile.homeColumns,
                            modifier = Modifier.padding(horizontal = profile.gridSideInset)
                        )
                    }
                }
                error != null && history.isEmpty() -> {
                    ErrorState(error!!, onRetry = { vm.retry() })
                }
                history.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyView(
                            text = "还没有播放记录",
                            sub = "去首页看看有什么好看的",
                            icon = Icons.Filled.History
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            // 大屏：整宽行卡不跟着窗口一起拉长（gridSideInset 在 Compact 档恒为 0）
                            start = Spacing.page + profile.gridSideInset,
                            end = Spacing.page + profile.gridSideInset,
                            top = Spacing.sm,
                            bottom = Spacing.sm
                        ),
                        // 行间距 10dp（卡片间距统一 10~12dp）
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isOffline) {
                            item { OfflineBanner(visible = true) }
                        }
                        items(
                            items = history,
                            key = { it.videoId }
                        ) { item ->
                            HistoryItem(
                                item = item,
                                onPlay = { onPlay(item.movieId, item.videoId) },
                                onOpenMovie = { onOpenMovie(item.movieId) },
                                onMarkDone = { vm.markDone(item) },
                                onRemove = { removeTarget = item },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹层
    removeTarget?.let { item ->
        HyperDialog(
            onDismissRequest = { removeTarget = null },
            title = {
                Text("从历史中移除", fontSize = 17.sp)
            },
            text = {
                Text(
                    text = "《${item.title}》 S${item.seasonNo} · 第${item.episodeNo}集\n\n移除后该集观看记录会清空，之后重新播放会再生成。",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
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

/** 历史条目：竖版封面 + 信息 + 细进度条 + 行操作（一张 HyperCard） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    item: HistoryVo,
    onPlay: () -> Unit,
    onOpenMovie: () -> Unit,
    onMarkDone: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = hyperColors()
    val finished = item.finished == 1
    val pct = if (finished) 100 else progressPct(item.positionMs, item.durationMs)

    HyperCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = onOpenMovie
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 竖版 2:3 封面，宽 74dp、圆角 10dp
            CoverImage(
                url = item.coverUrl,
                letter = item.title,
                contentDescription = item.title,
                modifier = Modifier
                    .width(74.dp)
                    .aspectRatio(2f / 3f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.width(12.dp))

            // 信息列
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "S${item.seasonNo} · 第${item.episodeNo}集" +
                        (item.categoryName?.let { " · $it" } ?: ""),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                // 细进度条（4dp 品牌绿渐变）+ 右侧「剩 xx 分钟 / 已看完」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
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
                    Spacer(Modifier.width(8.dp))
                    if (finished) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "已看完",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(
                            text = remainText(item.positionMs, item.durationMs),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // 操作列：标记看完 / 移除（34~36dp 圆形图标按钮）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 6.dp)
            ) {
                if (!finished) {
                    HyperIconButton(
                        icon = Icons.Filled.CheckCircle,
                        contentDescription = "标记看完",
                        onClick = onMarkDone,
                        size = 36.dp,
                        iconSize = 20.dp
                    )
                }
                HyperIconButton(
                    icon = Icons.Filled.DeleteOutline,
                    contentDescription = "移除",
                    onClick = onRemove,
                    size = 36.dp,
                    iconSize = 20.dp
                )
            }
        }
    }
}
