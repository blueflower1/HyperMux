@file:OptIn(ExperimentalTvFoundationApi::class, ExperimentalFoundationApi::class)

package com.hypermux.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import com.hypermux.tv.data.model.CategoryVo
import com.hypermux.tv.data.model.ContinueVo
import com.hypermux.tv.data.model.MovieCardVo
import com.hypermux.tv.ui.NetworkViewModel
import com.hypermux.tv.ui.common.CoverImage
import com.hypermux.tv.ui.common.EmptyView
import com.hypermux.tv.ui.common.ErrorState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.MovieCardItem
import com.hypermux.tv.ui.common.OfflineBanner
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.common.progressPct
import com.hypermux.tv.ui.common.ratingText
import com.hypermux.tv.ui.common.remainText
import com.hypermux.tv.ui.navigation.navigateHistory
import com.hypermux.tv.ui.search.SearchViewModel
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvSectionHeader
import com.hypermux.tv.ui.tv.TvTextInputPanel
import com.hypermux.tv.ui.tv.tvClickable
import kotlinx.coroutines.delay

/**
 * 首页（TV 版，48-tv-app-detailed.md §8.2）。
 *
 * 与手机端的结构差异（一层映射）：
 * | 手机端 | TV 端 |
 * | --- | --- |
 * | 顶栏搜索胶囊 + 历史图标 | **搜索并入首页**：chip 行「搜索」胶囊 → 页内搜索模式（不再跳独立搜索页） |
 * | 频道 Tab 行 | 频道 **chip 行**（TvLazyRow，焦点态放大 + 描边） |
 * | 16:9 轮播 + 圆点分页 | **Hero 大图**（自动轮播，不可手动翻页；焦点只落在「查看详情」） |
 * | 金色「继续观看」横幅 | 「继续观看」rail（宽卡，420dp） |
 * | 分区标题 + **双列信息流网格** | 分区标题 + **横向 rail**（TvLazyRow，卡片 240dp 横版剧照） |
 * | 具体分类 Tab：双列网格 | 具体分类 Tab：**4 列网格**（LazyVerticalGrid，焦点滚动由 LazyLayout 自带） |
 *
 * 焦点（§6.2）：进入页面 → Hero 的「查看详情」；UP/DOWN 走 rail 之间，LEFT/RIGHT 走 rail 内，
 * 从最左侧卡片 LEFT 回导航栏。返回本页时按 [rememberSaveable] 记住的卡片恢复焦点。
 * 搜索模式：BACK 先退出搜索（不清 App），再按才维持「首页 BACK 忽略」约定。
 */
@Composable
fun HomeScreen(
    navController: NavController,
    onOpenCategory: (Long, String?) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (movieId: Long, videoId: Long) -> Unit
) {
    val vm: HomeViewModel = hiltViewModel()
    val searchVm: SearchViewModel = hiltViewModel()
    val networkVm: NetworkViewModel = hiltViewModel()

    val home by vm.home.collectAsStateWithLifecycle(initialValue = null)
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val movies by vm.movies.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val selectedCategoryId by vm.selectedCategoryId.collectAsStateWithLifecycle(initialValue = HomeViewModel.TAB_HOME)
    val sections by vm.sections.collectAsStateWithLifecycle(initialValue = emptyList())
    val isOffline by networkVm.isOffline.collectAsStateWithLifecycle()

    // ---- 页内搜索模式（原独立搜索页并入首页） ----
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var inputOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val searchQuery by searchVm.query.collectAsStateWithLifecycle(initialValue = "")

    fun exitSearch() {
        searchOpen = false
        searchVm.onQueryChange("")   // 清空查询与结果，回到普通首页
    }

    // 首页 BACK：搜索模式先退出搜索；否则忽略（§7.3：TV 上退出 App 用遥控器 HOME 键，不用 BACK）
    BackHandler { if (searchOpen) exitSearch() }

    LaunchedEffect(Unit) { vm.loadHome() }

    val categories = home?.categories.orEmpty()
    val tabs = remember(categories) {
        listOf(CategoryVo(id = HomeViewModel.TAB_HOME, name = "首页")) + categories
    }
    val isHomeTab = (selectedCategoryId ?: HomeViewModel.TAB_HOME) == HomeViewModel.TAB_HOME
    // VM 的 selectedCategoryId 是 Long?（未选中/数据未到时为 null）→ 统一收敛到"首页"哨兵，
    // 避免下面每一处都写 `?: TAB_HOME`
    val currentTab = selectedCategoryId ?: HomeViewModel.TAB_HOME
    val selectedName = categories.firstOrNull { it.id == currentTab }?.name ?: "首页"
    val bannerSource = home?.banners.orEmpty()
    val bannerFallback = sections.flatMap { it.movies }.distinctBy { it.id }
    val heroList = if (bannerSource.isNotEmpty()) bannerSource else bannerFallback
    val continueItem = home?.continueWatching?.firstOrNull()

    // ---- 焦点恢复：记住最后聚焦的卡片 key，返回本页时重新聚焦 ----
    var lastKey by rememberSaveable { mutableStateOf<String?>(null) }
    val focusMap = remember { mutableMapOf<String, FocusRequester>() }
    val heroRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    val searchBarRequester = remember { FocusRequester() }

    val gridState = rememberLazyGridState()
    // 具体分类 Tab 的触底加载（首页 Tab 不需要：分区数据一次返回；搜索模式由 SearchArea 自己翻页）
    LaunchedEffect(gridState, isHomeTab, searchOpen) {
        if (isHomeTab || searchOpen) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶部搜索框：点击即进入搜索模式并弹出键盘（搜索并入首页，无独立搜索页/搜索按钮） ----
        HomeSearchBar(
            searching = searchOpen,
            query = searchQuery,
            focusRequester = searchBarRequester,
            onClick = {
                searchOpen = true
                draft = searchQuery
                inputOpen = true
            },
            onExit = { exitSearch() }
        )

        // 频道 chip 行（手机端是文字 Tab，TV 改成可聚焦 chip：焦点态更明确）
        ChannelChips(
            tabs = tabs,
            selectedId = currentTab,
            onSelect = { vm.selectCategory(it); searchOpen = false }
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                searchOpen -> SearchArea(
                    vm = searchVm,
                    gridState = gridState,
                    onOpenMovie = onOpenMovie
                )

                isLoading && home == null -> SkeletonPosterGrid(rows = 3, columns = 5)

                home == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState(
                        message = error ?: "请检查网络或服务器地址",
                        onRetry = { vm.loadHome() },
                        focusRequester = retryRequester
                    )
                    // 错误态必须有焦点（§6.1），否则遥控器在空页面上会\"失联\"
                    TvAutoFocus(retryRequester)
                }

                isHomeTab -> TvLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.page,
                        end = Spacing.page,
                        top = Spacing.sm,
                        bottom = Spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.railGap)
                ) {
                    if (isOffline) {
                        item(key = "offline") { OfflineBanner(visible = true) }
                    }

                    // ---- Hero 大图（自动轮播，不可手动翻页）----
                    if (heroList.isNotEmpty()) {
                        item(key = "hero") {
                            HeroBanner(
                                banners = heroList,
                                onOpen = onOpenMovie,
                                focusRequester = heroRequester
                            )
                        }
                    } else if (!isLoading && error == null) {
                        item(key = "empty") {
                            EmptyView(text = "暂无内容", sub = "稍后再来看看")
                        }
                    }

                    // ---- 继续观看 ----
                    if (continueItem != null) {
                        item(key = "continue") {
                            Column {
                                TvSectionHeader(title = "继续观看")
                                Spacer(Modifier.height(Spacing.md))
                                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                                    items(listOf(continueItem), key = { "cw-${it.movieId}" }) { item ->
                                        val r = keyedRequester(focusMap, "cw-${item.movieId}")
                                        ContinueWatchingRailCard(
                                            item = item,
                                            onClick = { onOpenMovie(item.movieId) },
                                            modifier = Modifier
                                                .focusRequester(r)
                                                .onFocusChanged { if (it.isFocused) lastKey = "cw-${item.movieId}" }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---- 分区 rail ----
                    sections.forEach { section ->
                        item(key = "sec-${section.categoryId}") {
                            Column {
                                TvSectionHeader(
                                    title = section.categoryName,
                                    onMore = { onOpenCategory(section.categoryId, section.categoryName) }
                                )
                                Spacer(Modifier.height(Spacing.md))
                                // 注意：焦点放大 6% 会外溢，故 rail 左右各留 24dp 余量（§5.2）
                                TvLazyRow(
                                    contentPadding = PaddingValues(horizontal = Spacing.cardGap),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)
                                ) {
                                    items(
                                        items = section.movies,
                                        key = { "${section.categoryId}-${it.id}" }
                                    ) { card ->
                                        val k = "${section.categoryId}-${card.id}"
                                        val r = keyedRequester(focusMap, k)
                                        MovieCardItem(
                                            card = card,
                                            onClick = { onOpenMovie(card.id) },
                                            modifier = Modifier
                                                .width(TvSize.cardLandscape)
                                                .focusRequester(r)
                                                .onFocusChanged { if (it.isFocused) lastKey = k }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isLoading && error == null && sections.isEmpty() && heroList.isEmpty() &&
                        continueItem == null
                    ) {
                        item(key = "empty2") { EmptyView(text = "暂无内容", sub = "稍后再来看看") }
                    }
                }

                else -> {
                    // ---- 具体分类 Tab：4 列网格 ----
                    LazyVerticalGrid(
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
                        if (isOffline) {
                            item(span = { GridItemSpan(maxLineSpan) }) { OfflineBanner(visible = true) }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "cat-header") {
                            Column {
                                TvSectionHeader(
                                    title = selectedName,
                                    onMore = { onOpenCategory(currentTab, selectedName) }
                                )
                                Spacer(Modifier.height(Spacing.md))
                            }
                        }
                        if (error != null && movies.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "cat-error") {
                                ErrorState(message = error!!, onRetry = { vm.refresh() })
                            }
                        }
                        items(items = movies, key = { it.id }) { card ->
                            val k = "cat-${card.id}"
                            val r = keyedRequester(focusMap, k)
                            MovieCardItem(
                                card = card,
                                forcePortrait = true,
                                onClick = { onOpenMovie(card.id) },
                                modifier = Modifier
                                    .focusRequester(r)
                                    .onFocusChanged { if (it.isFocused) lastKey = k }
                            )
                        }
                        if (movies.isEmpty() && error == null && !isLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "cat-empty") {
                                EmptyView(text = "该分类暂无影片", sub = "换个频道看看")
                            }
                        }
                        if (isLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "cat-loading") {
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
    }

    // 数据就绪 → 恢复焦点：优先回到上次聚焦的卡片，否则给 Hero 的「查看详情」；
    // 搜索模式焦点落在顶部搜索框（键盘关闭后自动回到搜索框）
    val ready = (home != null || !isLoading) && !searchOpen
    val restoreRequester = lastKey?.let { focusMap[it] } ?: heroRequester
    if (searchOpen) TvAutoFocus(searchBarRequester, ready = !inputOpen)
    else TvAutoFocus(restoreRequester, ready = ready)

    // 搜索输入弹层（自绘字符键盘，遥控器唯一可用的打字方式）
    if (inputOpen) {
        TvTextInputPanel(
            title = "搜索片名 / 演员",
            value = draft,
            onValueChange = { draft = it },
            onConfirm = {
                inputOpen = false
                val kw = draft.trim()
                if (kw.isNotBlank()) searchVm.onSubmitKeyword(kw)
                // 空提交：保持搜索区（显示搜索历史）
            },
            onDismiss = { inputOpen = false },
            placeholder = "片名 / 演员（支持拼音 / 首字母）"
        )
    }
}

/** 同一个 key 只创建一个 FocusRequester（避免重复 key 的卡片共享 requester 导致焦点歧义） */
private fun keyedRequester(map: MutableMap<String, FocusRequester>, key: String): FocusRequester =
    map.getOrPut(key) { FocusRequester() }

/**
 * 频道 chip 行：手机端是「文字 Tab + 下划线」，TV 改成「可聚焦胶囊 chip」。
 *
 * 为什么改：TV 上文字 Tab 没有明确的焦点边界（放大文字后仍看不出\"焦点在哪\"），
 * 胶囊 chip 的描边 + 放大让焦点一目了然；横向滚动由 [TvLazyRow] 负责把焦点 chip 滚进可视区。
 */
@Composable
private fun ChannelChips(
    tabs: List<CategoryVo>,
    selectedId: Long,
    onSelect: (Long) -> Unit
) {
    TvLazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs, bottom = Spacing.sm),
        contentPadding = PaddingValues(horizontal = Spacing.page),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        items(items = tabs, key = { it.id }) { tab ->
            HyperChip(
                text = tab.name,
                highlight = tab.id == selectedId,
                verticalPadding = 10.dp,
                onClick = { onSelect(tab.id) }
            )
        }
    }
}

/**
 * Hero 大图（TV 版首页顶部）：
 * - 高度 340dp（1080p 下约占屏高 1/3，视距远时足够有冲击力）；
 * - **自动轮播**（7s 一张，最多 6 张）：TV 没有触摸，手机端的手动翻页 + 圆点分页没有意义，
 *   改为不可交互的背景轮播，唯一的焦点目标是「查看详情」；
 * - 底部渐变压暗保证文字可读（与手机端继续观看横幅同一套配方）。
 */
@Composable
private fun HeroBanner(
    banners: List<MovieCardVo>,
    onOpen: (Long) -> Unit,
    focusRequester: FocusRequester
) {
    var index by remember(banners.size) { mutableIntStateOf(0) }
    if (banners.size > 1) {
        LaunchedEffect(banners.size) {
            while (true) {
                delay(7_000)
                index = (index + 1) % banners.size
            }
        }
    }
    val current = banners.getOrNull(index % banners.size) ?: return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Crossfade(
            targetState = current,
            animationSpec = tween(Motion.SLOW),
            label = "hero"
        ) { item ->
            CoverImage(
                url = item.backdropUrl ?: item.coverUrl,
                letter = item.title,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        }
        // 底部 + 左侧压暗（保证标题与按钮可读）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF20B0D10), Color(0xB30B0D10), Color(0x1A0B0D10))
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x00000000), Color(0x00000000), Color(0xCC0B0D10))
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, end = 32.dp, bottom = 28.dp)
                .fillMaxWidth(0.62f)
        ) {
            Text(
                text = current.title,
                fontSize = TvType.hero,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = buildList {
                    current.rating?.let { add("★ ${ratingText(it)}") }
                    current.year?.let { add("$it") }
                    current.area?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (current.episodeTotal > 1) add("全${current.episodeTotal}集")
                }.joinToString("  ·  "),
                fontSize = TvType.meta,
                color = Color(0xD9FFFFFF),
                maxLines = 1
            )
            if (current.tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    current.tags.take(3).forEach { tag ->
                        HyperChip(text = tag, verticalPadding = 6.dp)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            HyperButton(
                text = "查看详情",
                onClick = { onOpen(current.id) },
                focusRequester = focusRequester
            )
        }

        // 轮播指示点（纯展示，不可点击）
        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                banners.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == index % banners.size) 10.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (i == index % banners.size) MaterialTheme.colorScheme.primary
                                else Color(0x66FFFFFF)
                            )
                    )
                }
            }
        }
        // 焦点放大余量：Hero 是圆角容器，焦点描边不会外溢（按钮在容器内部），无需额外留白
        Spacer(Modifier.width(0.dp).height(0.dp))
    }
}

/**
 * 「继续观看」rail 卡（TV 版）：420dp 宽的横版剧照卡 + 集号 + 剩余时长 + 进度条。
 * 点击进详情（手机端点击也是进详情；「继续播放」这个主动作放在详情页的主按钮上，
 * 避免首页出现\"一按就播\"的误触——遥控器上误按比触屏更贵）。
 */
@Composable
private fun ContinueWatchingRailCard(
    item: ContinueVo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val pct = if (item.positionMs != null && item.durationMs != null) {
        progressPct(item.positionMs, item.durationMs)
    } else {
        0
    }
    Box(
        modifier = modifier
            .width(420.dp)
            .height(TvSize.continueCardHeight)
            .clip(shape)
            .tvClickable(shape = shape, onClick = onClick)
    ) {
        CoverImage(
            url = item.backdropUrl ?: item.coverUrl,
            letter = item.title,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xF20B0D10), Color(0xA60B0D10), Color(0x330B0D10))
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.lg)
                .fillMaxWidth(0.8f)
        ) {
            Text(
                text = "继续观看",
                fontSize = TvType.badge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xB3FFFFFF)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (item.lastVideo.seasonNo > 1) append("第${item.lastVideo.seasonNo}季 ")
                    append("第${item.lastVideo.episodeNo}集")
                    if (item.positionMs != null && item.durationMs != null) {
                        append(" · ${remainText(item.positionMs, item.durationMs)}")
                    }
                },
                fontSize = TvType.meta,
                color = Color(0xCCFFFFFF),
                maxLines = 1
            )
            if (pct > 0) {
                Spacer(Modifier.height(Spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x40FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/**
 * 页内搜索区（顶部搜索框进入搜索模式后的内容区）：
 *  - 未输入 → 搜索历史 chips（FlowRow，最多 10 条）；
 *  - 已输入 → 结果网格 **4 列** + 触底翻页（复用首页 gridState，同一时刻只有一个网格）。
 * 搜索框本体在页面顶部（HomeSearchBar），退出/重新输入都在那里操作。
 */
@Composable
private fun SearchArea(
    vm: SearchViewModel,
    gridState: LazyGridState,
    onOpenMovie: (Long) -> Unit
) {
    val query by vm.query.collectAsStateWithLifecycle(initialValue = "")
    val results by vm.results.collectAsStateWithLifecycle(initialValue = emptyList())
    val isSearching by vm.isSearching.collectAsStateWithLifecycle(initialValue = false)
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val history by vm.searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    val retryRequester = remember { FocusRequester() }
    val focusMap = remember { mutableMapOf<Long, FocusRequester>() }

    // 结果网格触底翻页
    LaunchedEffect(gridState, query) {
        if (query.isBlank()) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
            when {
                query.isBlank() -> SearchHistoryArea(
                    history = history,
                    onPick = { kw -> vm.onSubmitKeyword(kw) },
                    onClear = { vm.clearHistory() }
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
                            forcePortrait = true,
                            onClick = {
                                vm.onResultClick(card)
                                onOpenMovie(card.id)
                            },
                            modifier = Modifier.focusRequester(r)
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

/**
 * 首页顶部搜索框：常态是带放大镜的占位胶囊；点击进入搜索模式并弹出自绘键盘。
 * 搜索模式下显示当前关键词与「重新输入」引导，右侧出现「退出搜索」。
 */
@Composable
private fun HomeSearchBar(
    searching: Boolean,
    query: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onExit: () -> Unit
) {
    val hc = hyperColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page)
            .padding(top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .focusRequester(focusRequester)
                .tvClickable(shape = RoundedCornerShape(50), focusedScale = 1.02f, onClick = onClick)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = hc.text3,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = when {
                    !searching -> "搜索片名 / 演员"
                    query.isBlank() -> "输入片名 / 演员开始搜索"
                    else -> "「$query」的搜索结果"
                },
                fontSize = TvType.body,
                color = if (searching && query.isNotBlank()) MaterialTheme.colorScheme.onSurface else hc.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (searching) {
                Text(
                    text = if (query.isBlank()) "输入" else "重新输入",
                    fontSize = TvType.meta,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (searching) {
            Spacer(Modifier.width(Spacing.md))
            HyperButton(
                text = "退出搜索",
                onClick = onExit,
                style = HyperButtonStyle.Ghost
            )
        }
    }
}

/**
 * 搜索历史（页内搜索区空查询时展示）：chips 可换行（FlowRow），最多 10 条；
 * 按 OK 直接以该词搜索；「清空」清全部历史。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryArea(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.page, vertical = Spacing.lg)
    ) {
        TvSectionHeader(
            title = "搜索历史",
            onMore = if (history.isNotEmpty()) onClear else null,
            moreLabel = "清空"
        )
        Spacer(Modifier.height(Spacing.lg))
        if (history.isEmpty()) {
            EmptyView(
                text = "暂无搜索历史",
                sub = "点上方「输入」开始搜索",
                icon = Icons.Filled.History
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                history.take(10).forEach { kw ->
                    HyperChip(
                        text = kw,
                        verticalPadding = 10.dp,
                        onClick = { onPick(kw) }
                    )
                }
            }
        }
    }
}
