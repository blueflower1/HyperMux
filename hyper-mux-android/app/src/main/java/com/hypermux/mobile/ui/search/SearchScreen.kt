package com.hypermux.mobile.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.ErrorState
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperChip
import com.hypermux.mobile.ui.common.HyperIconButton
import com.hypermux.mobile.ui.common.HyperSearchField
import com.hypermux.mobile.ui.common.MovieCardItem
import com.hypermux.mobile.ui.common.SectionTitle
import com.hypermux.mobile.ui.common.SkeletonPosterGrid
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.navigation.navigateMovie
import com.hypermux.mobile.ui.theme.Spacing

/**
 * 全局搜索页（§5.4）：
 * 输入框（自动聚焦 + 防抖 300ms）→ 搜索历史 / 结果网格（分页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavController) {
    val vm: SearchViewModel = hiltViewModel()

    val query by vm.query.collectAsStateWithLifecycle(initialValue = "")
    val results by vm.results.collectAsStateWithLifecycle(initialValue = emptyList())
    val total by vm.total.collectAsStateWithLifecycle(initialValue = 0L)
    val isSearching by vm.isSearching.collectAsStateWithLifecycle(initialValue = false)
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    // 搜索历史来自 SettingsRepo（DataStore）的 StateFlow：写入后立刻刷新 UI
    val history by vm.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    val focusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val profile = LocalLayoutProfile.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    HyperSearchField(
                        value = query,
                        onValueChange = vm::onQueryChange,
                        placeholder = "搜索片名 / 演员",
                        onSearch = { vm.submit() },
                        // 进入搜索页自动聚焦（与原来一致）
                        focusRequester = focusRequester
                    )
                },
                navigationIcon = {
                    HyperIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = { navController.popBackStack() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // 未输入：搜索历史
                query.isBlank() -> {
                    HistorySection(
                        history = history,
                        // 点历史词：填入输入框并立即搜索（内部会记历史，复用 submit 的语义）
                        onPick = { kw -> vm.onSubmitKeyword(kw) },
                        onClear = { vm.clearHistory() }
                    )
                }
                // 搜索中（首屏）
                isSearching && results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SkeletonPosterGrid(
                            rows = 3,
                            columns = profile.tileColumns,
                            modifier = Modifier.padding(horizontal = profile.gridSideInset)
                        )
                    }
                }
                // 失败
                error != null && results.isEmpty() -> {
                    ErrorState(error!!, onRetry = { vm.retry() })
                }
                // 结果
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(profile.tileColumns),
                        state = gridState,
                        // 内容左右内缩统一为 Spacing.page，与其它页面卡片边缘对齐；
                        // gridSideInset：窗口比 gridMaxWidth 宽时收窄并居中（Compact 档恒为 0）
                        contentPadding = PaddingValues(
                            start = Spacing.page + profile.gridSideInset,
                            end = Spacing.page + profile.gridSideInset,
                            top = Spacing.sm,
                            bottom = Spacing.sm
                        ),
                        // 卡片间距 10dp（横纵一致）
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = results,
                            key = { it.id }
                        ) { card ->
                            MovieCardItem(card = card, onClick = {
                                vm.onResultClick(card)
                                navController.navigateMovie(card.id)
                            }, modifier = Modifier.animateItem(), forcePortrait = true)
                        }

                        if (results.isEmpty() && error == null && !isSearching) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                EmptyView(
                                    text = "没有找到「$query」相关影片",
                                    sub = "换个关键词试试"
                                )
                            }
                        }

                        if (isLoadingMore) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().padding(Spacing.lg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索历史（与全站分区标题同风格：[SectionTitle] 的 4dp 渐变竖条 + ExtraBold 标题）。
 * - chips **可换行多行**（FlowRow），不再只横滑一行；
 * - 历史为空时给统一空态（[EmptyView]），不留白页；
 * - 有历史时才显示「清空」（调用 SettingsRepo.clearSearchHistory）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySection(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 与其它页面统一为 Spacing.page 左右内缩；历史条目多时整体可滚动
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page, vertical = Spacing.lg)
    ) {
        SectionTitle(
            text = "搜索历史",
            trailing = {
                if (history.isNotEmpty()) {
                    HyperButton(
                        text = "清空",
                        onClick = onClear,
                        style = HyperButtonStyle.Ghost,
                        small = true
                    )
                }
            }
        )
        Spacer(Modifier.height(Spacing.md))
        if (history.isEmpty()) {
            EmptyView(
                text = "暂无搜索历史",
                sub = "输入片名或演员开始搜索",
                icon = Icons.Filled.History
            )
        } else {
            // 可换行：一行放不下自动折到下一行，不挤成一条横滑
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                history.forEach { kw ->
                    HyperChip(
                        text = kw,
                        highlight = false,
                        modifier = Modifier.bounceClick(pressedScale = 0.94f) { onPick(kw) }
                    )
                }
            }
        }
    }
}
