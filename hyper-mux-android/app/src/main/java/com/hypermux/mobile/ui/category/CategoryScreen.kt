package com.hypermux.mobile.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.runtime.snapshotFlow
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.ErrorState
import com.hypermux.mobile.ui.common.HyperIconButton
import com.hypermux.mobile.ui.common.HyperSearchField
import com.hypermux.mobile.ui.common.MovieCardItem
import com.hypermux.mobile.ui.common.SkeletonPosterGrid
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.navigation.navigateMovie
import com.hypermux.mobile.ui.theme.Spacing

/**
 * 分类列表页（§5.3）：顶栏（返回 + 标题）→ 标题下方整宽胶囊搜索条 → 竖版海报网格分页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    navController: NavController,
    categoryId: Long,
    categoryName: String
) {
    val vm: CategoryViewModel = hiltViewModel()

    val movies by vm.movies.collectAsStateWithLifecycle(initialValue = emptyList())
    val total by vm.moviesTotal.collectAsStateWithLifecycle(initialValue = 0L)
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val keyword by vm.keyword.collectAsStateWithLifecycle(initialValue = "")

    val profile = LocalLayoutProfile.current

    val gridState = rememberLazyGridState()
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
                    Text(
                        text = categoryName.ifBlank { "分类" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                isLoading && movies.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SkeletonPosterGrid(
                            rows = 3,
                            columns = profile.tileColumns,
                            modifier = Modifier.padding(horizontal = profile.gridSideInset)
                        )
                    }
                }
                error != null && movies.isEmpty() -> {
                    ErrorState(error!!, onRetry = { vm.retry() })
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(profile.tileColumns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            // 内容左右内缩统一为 Spacing.page，与首页/搜索/历史等页面卡片边缘对齐；
                            // gridSideInset：窗口比 gridMaxWidth 宽时收窄并居中（Compact 档恒为 0）
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
                        // ---- 标题下方整宽胶囊搜索条（原来挤在右上角的小方框，已重做）----
                        item(span = { GridItemSpan(maxLineSpan) }, key = "category-search") {
                            HyperSearchField(
                                value = keyword,
                                onValueChange = vm::onKeywordChange,
                                placeholder = "搜索片名 / 演员",
                                onSearch = { vm.retry() },
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }

                        items(
                            items = movies,
                            key = { it.id }
                        ) { card ->
                            MovieCardItem(
                                card = card,
                                onClick = { navController.navigateMovie(card.id) },
                                modifier = Modifier.animateItem(),
                                // 分类页统一用竖版海报（不被横版剧照带偏成 16:9 大卡）
                                forcePortrait = true
                            )
                        }

                        if (movies.isEmpty() && error == null && !isLoading) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                EmptyView(
                                    text = if (keyword.isNotBlank()) {
                                        "没有找到「$keyword」相关影片"
                                    } else {
                                        "该分类暂无影片"
                                    },
                                    sub = if (keyword.isNotBlank()) "换个关键词试试" else "换个频道看看"
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
