package com.hypermux.tv.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.hypermux.tv.ui.common.MovieCardItem
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.navigation.navigateMovie
import com.hypermux.tv.ui.navigation.navigateUpOrHome
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvTextInputPanel
import com.hypermux.tv.ui.tv.TvTopBar
import com.hypermux.tv.ui.tv.tvClickable

/**
 * 分类列表页（TV 版，48-tv-app-detailed.md §8.3）。
 *
 * 手机端 → TV 的映射：
 * - 顶栏返回按钮 + 标题 → [TvTopBar]（返回按钮同样可聚焦；BACK 键与按钮两条路都能返回）；
 * - 标题下方**整宽胶囊搜索条**（可触摸输入）→ 可聚焦的「本分类内搜索」行，
 *   按 OK 弹出 [TvTextInputPanel]（自绘键盘）——遥控器无法在页面内直接打字；
 * - 竖版海报**3 列** → **4 列**（1080p 基准；卡片间距 24dp 与首页 rail 统一）；
 * - 触底分页逻辑保持不变（`snapshotFlow { gridState.layoutInfo }`）。
 */
@Composable
fun CategoryScreen(
    navController: NavController,
    categoryId: Long,
    categoryName: String
) {
    val vm: CategoryViewModel = hiltViewModel()
    val hc = hyperColors()

    val movies by vm.movies.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val keyword by vm.keyword.collectAsStateWithLifecycle(initialValue = "")

    // 搜索弹层草稿（确认才写回 VM，避免逐字触发网络请求——遥控器按键慢，逐字搜索会\"打字即加载\"）
    var searchOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    // 焦点恢复：记住最后聚焦的卡片，从详情页返回时回到它
    var lastKey by rememberSaveable { mutableStateOf<Long?>(null) }
    val focusMap = remember { mutableMapOf<Long, FocusRequester>() }
    val retryRequester = remember { FocusRequester() }

    BackHandler { navController.navigateUpOrHome() }

    Column(modifier = Modifier.fillMaxSize()) {
        TvTopBar(
            title = categoryName.ifBlank { "分类" },
            onBack = { navController.navigateUpOrHome() }
        )

        // ---- 本分类内搜索入口（可聚焦行；OK 打开自绘键盘弹层）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = Spacing.sm)
                .height(64.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .tvClickable(
                    shape = RoundedCornerShape(50),
                    onClick = {
                        draft = keyword
                        searchOpen = true
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
                text = keyword.ifBlank { "在本分类内搜索片名 / 演员" },
                fontSize = TvType.body,
                color = if (keyword.isBlank()) hc.text3 else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && movies.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.page),
                    contentAlignment = Alignment.TopCenter
                ) {
                    SkeletonPosterGrid(rows = 3, columns = TvSize.gridColumnsFhd)
                }

                error != null && movies.isEmpty() -> Box(
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
                    items(items = movies, key = { it.id }) { card ->
                        val r = focusMap.getOrPut(card.id) { FocusRequester() }
                        MovieCardItem(
                            card = card,
                            onClick = { navController.navigateMovie(card.id) },
                            // 分类页统一用竖版海报（不被横版剧照带偏成 16:9 大卡）
                            forcePortrait = true,
                            modifier = Modifier
                                .focusRequester(r)
                                .onFocusChanged { if (it.isFocused) lastKey = card.id }
                        )
                    }

                    if (movies.isEmpty() && error == null && !isLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
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

    // 进入页面 → 聚焦上次聚焦的卡片，否则首张卡片
    val ready = !isLoading && movies.isNotEmpty()
    val restore = lastKey?.let { focusMap[it] } ?: movies.firstOrNull()?.let { focusMap[it.id] }
    TvAutoFocus(restore, ready = ready)

    if (searchOpen) {
        TvTextInputPanel(
            title = "在本分类内搜索",
            value = draft,
            onValueChange = { draft = it },
            onConfirm = {
                searchOpen = false
                vm.onKeywordChange(draft)
                vm.retry()
            },
            onDismiss = { searchOpen = false },
            placeholder = "片名 / 演员"
        )
    }
    // categoryId 变化由 VM 的 SavedStateHandle 消费；此处仅作显式依赖，避免\"改了参数不刷新\"
    LaunchedEffect(categoryId) { /* no-op：VM 自行读取导航参数 */ }
}
