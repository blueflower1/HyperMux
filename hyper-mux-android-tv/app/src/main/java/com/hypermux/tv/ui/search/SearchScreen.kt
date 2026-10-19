package com.hypermux.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.tv.ui.common.EmptyView
import com.hypermux.tv.ui.common.ErrorState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.MovieCardItem
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.navigation.navigateMovie
import com.hypermux.tv.ui.navigation.navigateUpOrHome
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvSectionHeader
import com.hypermux.tv.ui.tv.TvTextInputPanel
import com.hypermux.tv.ui.tv.TvTopBar
import com.hypermux.tv.ui.tv.tvClickable

/**
 * 全局搜索页（TV 版，48-tv-app-detailed.md §8.4）。
 *
 * 手机端是「顶栏内嵌搜索框 + 自动聚焦 + 300ms 防抖」；TV 无法在页面里打字，改为：
 *  - 顶栏下方一条**可聚焦的搜索行**，按 OK 打开 [TvTextInputPanel]（自绘字符键盘）；
 *  - 确认后一次提交（遥控器按键慢，逐字防抖搜索只会不断打断加载）；
 *  - 搜索历史 chips（FlowRow，最多展示 10 条，避免长历史把结果挤出屏幕）；
 *  - 结果网格 **6 列**（手机 3 列），焦点进入网格后可上下连续翻页。
 */
@Composable
fun SearchScreen(navController: NavController) {
    val vm: SearchViewModel = hiltViewModel()
    val hc = hyperColors()

    val query by vm.query.collectAsStateWithLifecycle(initialValue = "")
    val results by vm.results.collectAsStateWithLifecycle(initialValue = emptyList())
    val isSearching by vm.isSearching.collectAsStateWithLifecycle(initialValue = false)
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val history by vm.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    var inputOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val gridState = rememberLazyGridState()
    val searchRowRequester = remember { FocusRequester() }
    val firstHistoryRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }

    var lastKey by rememberSaveable { mutableStateOf<Long?>(null) }
    val focusMap = remember { mutableMapOf<Long, FocusRequester>() }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    BackHandler { navController.navigateUpOrHome() }

    Column(modifier = Modifier.fillMaxSize()) {
        TvTopBar(title = "搜索", onBack = { navController.navigateUpOrHome() })

        // ---- 搜索行（可聚焦；OK 打开自绘键盘）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.sm)
                .height(64.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .focusRequester(searchRowRequester)
                .tvClickable(
                    shape = RoundedCornerShape(50),
                    onClick = {
                        draft = query
                        inputOpen = true
                    }
                )
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = hc.text3,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = query.ifBlank { "搜索片名 / 演员" },
                fontSize = TvType.body,
                color = if (query.isBlank()) hc.text3 else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                Text(
                    text = "重新输入",
                    fontSize = TvType.meta,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                query.isBlank() -> HistorySection(
                    history = history,
                    onPick = { kw -> vm.onSubmitKeyword(kw) },
                    onClear = { vm.clearHistory() },
                    firstChipRequester = firstHistoryRequester,
                    hasHistory = history.isNotEmpty()
                )

                isSearching && results.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.page),
                    contentAlignment = Alignment.TopCenter
                ) {
                    SkeletonPosterGrid(rows = 3, columns = TvSize.gridColumnsFhd)
                }

                error != null && results.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorState(message = error!!, onRetry = { vm.retry() }, focusRequester = retryRequester)
                    TvAutoFocus(retryRequester)
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(TvSize.gridColumnsFhd),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = Spacing.page,
                        end = Spacing.page,
                        top = Spacing.sm,
                        bottom = Spacing.xxl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
                    verticalArrangement = Arrangement.spacedBy(Spacing.railGap),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = results, key = { it.id }) { card ->
                        val r = focusMap.getOrPut(card.id) { FocusRequester() }
                        MovieCardItem(
                            card = card,
                            onClick = {
                                vm.onResultClick(card)
                                navController.navigateMovie(card.id)
                            },
                            forcePortrait = true,
                            modifier = Modifier
                                .focusRequester(r)
                                .onFocusChanged { if (it.isFocused) lastKey = card.id }
                        )
                    }

                    if (results.isEmpty() && error == null && !isSearching) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyView(
                                text = "没有找到「$query」相关影片",
                                sub = "换个关键词试试"
                            )
                        }
                    }

                    if (isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.lg),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 焦点：未输入 → 搜索行；有历史 → 第一个历史词（更快复用）
    val readyForGrid = query.isNotBlank() && results.isNotEmpty() && !isSearching
    val restore = when {
        readyForGrid -> lastKey?.let { focusMap[it] } ?: results.firstOrNull()?.let { focusMap[it.id] }
        else -> null
    }
    TvAutoFocus(restore ?: searchRowRequester, ready = true)

    if (inputOpen) {
        TvTextInputPanel(
            title = "搜索片名 / 演员",
            value = draft,
            onValueChange = { draft = it },
            onConfirm = {
                inputOpen = false
                vm.onSubmitKeyword(draft)
            },
            onDismiss = { inputOpen = false },
            placeholder = "片名 / 演员"
        )
    }
}

/**
 * 搜索历史（与全站分区标题同风格）：chips 可换行多行（FlowRow），最多 10 条。
 * 每条 chip 都可聚焦（tvClickable）；按 OK 直接以该词搜索（等价手机端点击历史词）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySection(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    firstChipRequester: FocusRequester,
    hasHistory: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.page, vertical = Spacing.lg)
    ) {
        TvSectionHeader(
            title = "搜索历史",
            onMore = if (hasHistory) onClear else null,
            moreLabel = "清空"
        )
        Spacer(Modifier.height(Spacing.lg))
        if (!hasHistory) {
            EmptyView(
                text = "暂无搜索历史",
                sub = "输入片名或演员开始搜索",
                icon = Icons.Filled.History
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                history.take(10).forEachIndexed { index, kw ->
                    HyperChip(
                        text = kw,
                        verticalPadding = 10.dp,
                        focusRequester = if (index == 0) firstChipRequester else null,
                        onClick = { onPick(kw) }
                    )
                }
            }
        }
    }
}
