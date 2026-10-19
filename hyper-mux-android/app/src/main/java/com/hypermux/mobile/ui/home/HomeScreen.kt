package com.hypermux.mobile.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hypermux.mobile.data.model.CategoryVo
import com.hypermux.mobile.data.model.ContinueVo
import com.hypermux.mobile.ui.NetworkViewModel
import com.hypermux.mobile.ui.common.EmptyView
import com.hypermux.mobile.ui.common.CoverImage
import com.hypermux.mobile.ui.common.ErrorState
import com.hypermux.mobile.ui.common.MovieCardItem
import com.hypermux.mobile.ui.common.OfflineBanner
import com.hypermux.mobile.ui.common.SkeletonPosterGrid
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.common.progressPct
import com.hypermux.mobile.ui.common.remainText
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.navigation.BottomNavBar
import com.hypermux.mobile.ui.navigation.navigateScan
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页（对齐爱奇艺首页结构）：
 * 顶栏（Logo + 常驻搜索胶囊 + 历史）→ 频道 Tab 行（粘性、选中加粗高亮）
 * → 金色「继续观看」横幅 → 分区标题 → 双列信息流卡片。
 */
@Composable
fun HomeScreen(
    navController: NavController,
    onOpenCategory: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onPlay: (movieId: Long, videoId: Long) -> Unit
) {
    val vm: HomeViewModel = hiltViewModel()
    val networkVm: NetworkViewModel = hiltViewModel()

    val home by vm.home.collectAsStateWithLifecycle(initialValue = null)
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val movies by vm.movies.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLoadingMore by vm.isLoadingMore.collectAsStateWithLifecycle(initialValue = false)
    val selectedCategoryId by vm.selectedCategoryId.collectAsStateWithLifecycle(initialValue = HomeViewModel.TAB_HOME)
    val sections by vm.sections.collectAsStateWithLifecycle(initialValue = emptyList())
    val isOffline by networkVm.isOffline.collectAsStateWithLifecycle()

    // 窗口尺寸档（手机 Compact → 列数与内缩全部等于 0.1.11 的取值）
    val profile = LocalLayoutProfile.current

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var exitArmed by remember { mutableStateOf(false) }

    BackHandler {
        if (exitArmed) {
            navController.popBackStack()
        } else {
            exitArmed = true
            scope.launch {
                snackbarHostState.showSnackbar("再按一次退出")
                delay(2000)
                exitArmed = false
            }
        }
    }

    LaunchedEffect(Unit) { vm.loadHome() }

    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { info ->
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= info.totalItemsCount - 4) vm.loadMore()
            }
    }

    val categories = home?.categories.orEmpty()
    // 频道 Tab 行最前面插入合成「首页」Tab（哨兵 id = HomeViewModel.TAB_HOME，点击可回首页聚合）
    val tabs = remember(categories) {
        listOf(CategoryVo(id = HomeViewModel.TAB_HOME, name = "首页")) + categories
    }
    val isHomeTab = selectedCategoryId == HomeViewModel.TAB_HOME
    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "首页"
    val continueItem = home?.continueWatching?.firstOrNull()
    // 轮播只在「首页」Tab 出现：数据源固定取后端轮播池（空则退回各分区影片兜底）；
    // 具体类型 Tab 不再置顶轮播，直接从分区标题/信息流开始（见下方渲染条件）。
    val bannerSource = home?.banners.orEmpty()
    val bannerFallback = sections.flatMap { it.movies }

    // ---- ② 频道 Tab 切换：内容层「圆形揭示」动画 ----
    // 动画原理：把「被点击 Tab 按钮的中心点」换算到内容容器坐标系，切换频道时用一个半径 Animatable
    // 从 0 长到"恰好能盖住内容区的最小半径"，再用 Modifier.drawWithContent + clipPath(circlePath(...))
    // 把新内容按圆形裁剪显示 —— 视觉上就是从那个 Tab 文字处"散发出来"再铺满整页。
    // 同时用**同一个 0→1 进度**驱动内容 alpha(0.65→1) 与 scale(0.99→1)，避免只裁剪不淡入的生硬感。
    // 首次进入页面不播：只有用户主动点 Tab（onTabSelect 里）才自增令牌，下拉刷新 / 触底加载 / loadHome 都不会触发。
    // 取舍说明：若某些机型上圆形揭示出现"裁剪残留 / 尺寸抖动"，可退化为以该 Tab 位置为原点的
    // scaleIn + fadeIn（graphicsLayer { transformOrigin = TransformOrigin(xFractionOfTab, 0f) }），
    // 语义一致、只是不再有"圆孔扩张"的视觉效果。
    var revealTick by remember { mutableIntStateOf(0) }              // 动画触发令牌（0 = 尚未用户切换过）
    var revealCenter by remember { mutableStateOf<Offset?>(null) }   // 被点击 Tab 中心（内容容器坐标系）；null = 不裁剪
    var revealMaxRadius by remember { mutableStateOf(0f) }           // 能覆盖内容区的最小半径
    val revealProgress = remember { Animatable(0f) }                 // 0→1 统一进度（裁剪半径 / alpha / scale 共用）
    var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) } // 内容容器坐标（换算 + 算半径用）

    LaunchedEffect(revealTick) {
        if (revealTick == 0) return@LaunchedEffect   // 首次进入：不播动画
        revealProgress.snapTo(0f)                    // 先归零，保证从按钮那一点开始扩散（也避免闪一帧全量内容）
        revealProgress.animateTo(
            targetValue = 1f,
            // 约 300ms（Motion.BASE=220 + 80），落在 220~320ms 区间；缓动用 Emphasized，收尾更顺
            animationSpec = tween(Motion.BASE + 80, easing = Motion.Emphasized)
        )
        revealProgress.snapTo(0f)   // 与下一行的置空同一帧生效 → 不出现"瞬间被裁空"的一帧
        revealCenter = null         // 动画结束撤销裁剪，避免残留裁剪 / 尺寸抖动
    }

    // 点击频道 Tab：只有真的切了频道才播；点当前 Tab 不播也不切换
    val onTabSelect: (Long, Offset?) -> Unit = { id, tabCenterInRoot ->
        if (id != selectedCategoryId) {
            val coords = contentCoords
            if (tabCenterInRoot != null && coords != null) {
                // 根坐标 → 内容容器坐标：Tab 行（topBar）与内容区是兄弟节点，不能用 localPositionOf，
                // 故各自取 positionInRoot() 后相减。
                val center = tabCenterInRoot - coords.positionInRoot()
                val w = coords.size.width.toFloat()
                val h = coords.size.height.toFloat()
                // 最小覆盖半径 = 中心点到内容区「最远角」的距离（hypot(dx, dy)）
                revealMaxRadius = hypot(
                    maxOf(center.x, w - center.x),
                    maxOf(center.y, h - center.y)
                )
                revealCenter = center
                revealTick++          // 令牌自增 → LaunchedEffect 重新从 0 播放
            } else {
                revealCenter = null   // 坐标/尺寸未就绪：退化为直接切换，保证不出错
            }
            vm.selectCategory(id)
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                IQiyiTopBar(
                    onOpenSearch = onOpenSearch,
                    onOpenScan = { navController.navigateScan() }
                )
                if (categories.isNotEmpty()) {
                    ChannelTabs(
                        categories = tabs,
                        selectedId = selectedCategoryId,
                        onSelect = onTabSelect
                    )
                }
            }
        },
        bottomBar = { BottomNavBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 记录内容区（含尺寸）的坐标：① 把 Tab 中心换算到本坐标系 ② 计算覆盖半径
                .onGloballyPositioned { contentCoords = it }
                // 叠加：整体淡入 + 极轻微放大（与圆形揭示共用同一个 revealProgress，严格同步）
                .graphicsLayer {
                    val c = revealCenter
                    if (c != null) {
                        val p = revealProgress.value
                        alpha = 0.65f + 0.35f * p
                        scaleX = 0.99f + 0.01f * p
                        scaleY = scaleX
                    }
                }
                // 圆形揭示裁剪：revealCenter == null 时完全不裁剪（原样画，零开销行为）
                .drawWithContent {
                    val c = revealCenter
                    if (c == null) {
                        drawContent()
                    } else {
                        // 注意：clipPath 的 block 接收者是普通 DrawScope，取不到 ContentDrawScope.drawContent()，
                        // 因此先把外层的 drawContent 捕获成局部函数，再在裁剪块里调用（否则编译报隐式接收者错误）。
                        val drawClipped: () -> Unit = { drawContent() }
                        clipPath(circlePath(c, revealMaxRadius * revealProgress.value)) {
                            drawClipped()
                        }
                    }
                }
        ) {
            when {
                isLoading && home == null -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(Spacing.lg))
                        SkeletonPosterGrid(
                            rows = 3,
                            columns = profile.homeColumns,
                            modifier = Modifier.padding(horizontal = profile.gridSideInset)
                        )
                    }
                }
                home == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(
                            message = error ?: "请检查网络或服务器地址",
                            onRetry = { vm.loadHome() }
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(profile.homeColumns),
                        state = gridState,
                        contentPadding = PaddingValues(
                            // gridSideInset：窗口比 gridMaxWidth 宽时把内容收窄并居中（Compact 档恒为 0）
                            start = Spacing.page + profile.gridSideInset,
                            end = Spacing.page + profile.gridSideInset,
                            bottom = Spacing.xl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isOffline) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OfflineBanner(visible = true)
                            }
                        }

                        // ---- 顶部 Banner 轮播（横版剧照，自动轮播）----
                        // 仅「首页」Tab 渲染轮播；具体类型 Tab 从下方分区标题直接开始（不置顶轮播）
                        if (isHomeTab && (bannerSource.isNotEmpty() || bannerFallback.isNotEmpty())) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                HomeBannerCarousel(
                                    banners = bannerSource,
                                    fallback = bannerFallback,
                                    onOpen = { onOpenMovie(it) }
                                )
                            }
                        }

                        // ---- 「继续观看」横幅（仅在「首页」Tab 展示）----
                        if (isHomeTab && continueItem != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ContinueBanner(
                                    item = continueItem,
                                    onOpen = { onOpenMovie(continueItem.movieId) },
                                    onResume = { onPlay(continueItem.movieId, continueItem.lastVideo.id) }
                                )
                            }
                        }

                        if (isHomeTab) {
                            // ---- 「首页」Tab：按类型分区（分区标题 + 更多 + 双列卡片）----
                            sections.forEach { section ->
                                item(
                                    span = { GridItemSpan(maxLineSpan) },
                                    key = "sec-header-${section.categoryId}"
                                ) {
                                    SectionHeader(
                                        name = section.categoryName,
                                        onMore = { onOpenCategory(section.categoryId) }
                                    )
                                }
                                items(
                                    items = section.movies,
                                    // 分区之间可能含同一部影片：key 加分类前缀，避免 LazyGrid 重复 key 崩溃
                                    key = { "${section.categoryId}-${it.id}" }
                                ) { card ->
                                    MovieCardItem(
                                        card = card,
                                        onClick = { onOpenMovie(card.id) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }

                            if (!isLoading && error == null && sections.isEmpty() &&
                                bannerSource.isEmpty() && bannerFallback.isEmpty() &&
                                continueItem == null
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    EmptyView(text = "暂无内容", sub = "稍后再来看看")
                                }
                            }
                        } else {
                            // ---- 具体类型：保持原有双列信息流行（分区标题 + 更多）----
                            if (movies.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(
                                        name = selectedName,
                                        onMore = { selectedCategoryId?.let { onOpenCategory(it) } }
                                    )
                                }
                            }

                            if (error != null && movies.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ErrorState(message = error!!, onRetry = { vm.refresh() })
                                }
                            }

                            items(items = movies, key = { it.id }) { card ->
                                MovieCardItem(
                                    card = card,
                                    onClick = { onOpenMovie(card.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }

                            if (movies.isEmpty() && error == null && !isLoading) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    EmptyView(text = "该分类暂无影片", sub = "换个频道看看")
                                }
                            }

                            if (isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        Modifier.fillMaxWidth().padding(Spacing.lg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
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
}

/** 顶栏（爱奇艺式：Logo + 搜索胶囊 + 右侧扫一扫图标；「历史」入口在底部 Tab） */
@Composable
private fun IQiyiTopBar(onOpenSearch: () -> Unit, onOpenScan: () -> Unit) {
    val hc = hyperColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.page, end = Spacing.page, top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Brush.linearGradient(hc.primaryGradient)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        // 常驻搜索胶囊
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .bounceClick(pressedScale = 0.98f, onClick = onOpenSearch)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = hc.text3,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "搜索片名 / 演员",
                    fontSize = 13.sp,
                    color = hc.text3,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(
            Icons.Filled.QrCodeScanner,
            contentDescription = "扫一扫",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .bounceClick(pressedScale = 0.9f, onClick = onOpenScan)
        )
    }
}

/**
 * 频道 Tab 行（爱奇艺式：横滑、选中加粗放大高亮）。
 *
 * [onSelect] 的第二个参数 = 被点击 Tab **中心点的根坐标**（动画用，详见 HomeScreen 里 onTabSelect 的说明）；
 * 拿不到坐标时为 null。这里只负责"测量 + 上报"，不关心动画怎么做。
 */
@Composable
private fun ChannelTabs(
    categories: List<CategoryVo>,
    selectedId: Long?,
    onSelect: (Long, Offset?) -> Unit
) {
    val hc = hyperColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        categories.forEach { c ->
            val selected = c.id == selectedId
            // 本 Tab 的布局坐标（由 onGloballyPositioned 写入），点击时用它换算中心点
            var tabCoords by remember(c.id) { mutableStateOf<LayoutCoordinates?>(null) }
            val color by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onBackground
                else hc.text3,
                animationSpec = tween(Motion.BASE),
                label = "tabColor"
            )
            // 选中：文字轻微放大 + 下划线生长动画
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.06f else 1f,
                animationSpec = tween(Motion.BASE),
                label = "tabScale"
            )
            val underline by animateDpAsState(
                targetValue = if (selected) 28.dp else 0.dp,
                animationSpec = tween(Motion.BASE),
                label = "tabUnderline"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    // 记录本 Tab 的布局坐标（含横滑后的实际位置）：点击时用来算出"从按钮处扩散"的原点
                    .onGloballyPositioned { tabCoords = it }
                    .bounceClick(pressedScale = 0.94f) {
                        // 上报 Tab 中心点（根坐标）；布局未就绪时为 null → HomeScreen 侧退化为直接切换
                        val coords = tabCoords
                        onSelect(
                            c.id,
                            coords?.let {
                                it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                            }
                        )
                    }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = c.name,
                    fontSize = if (selected) 17.sp else 15.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = color,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                // 选中下划线（品牌绿，宽度做生长动画；外层固定宽度避免文字跳动）
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(underline)
                            .height(3.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/** 分区标题：绿色渐变竖条 + 名称 + 可选「更多 ›」胶囊（点击进入该类型） */
@Composable
private fun SectionHeader(name: String, onMore: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Brush.verticalGradient(hyperColors().primaryGradient))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (onMore != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.bounceClick(pressedScale = 0.94f, onClick = onMore)
            ) {
                Text(
                    text = "更多 ›",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 继续观看横幅：横版剧照背景 + 深色渐变压暗 + 绿色继续播放（对齐爱奇艺） */
@Composable
private fun ContinueBanner(
    item: ContinueVo,
    onOpen: () -> Unit,
    onResume: () -> Unit
) {
    val pct = if (item.positionMs != null && item.durationMs != null) {
        progressPct(item.positionMs, item.durationMs)
    } else {
        0
    }
    // 横幅内容宽 = 窗口宽 − 左右（页面内缩 + 大屏居中内缩），与网格给 maxLineSpan 的宽度一致
    val profile = LocalLayoutProfile.current
    val bannerWidth = profile.windowWidth - (Spacing.page + profile.gridSideInset) * 2
    // 150dp 这个设计值来自手机：332×150 ≈ 2.2:1，16:9 剧照按 TopCenter 只裁掉约 20%。
    // 平板上横幅内容宽 ~932dp，若还锁 150dp 就变成 6.2:1 —— 剧照被横切掉四分之三、
    // 只剩中间一条窄带，看起来像被放大、比例不对。
    // 让高度随宽度增长、把比例收在 3.2:1 以内，并保留 150dp 下限：
    // 宽度 <480dp（所有手机）仍取 150dp → 手机侧逐位不变。
    // （试过 2.6:1 + 「模糊填充 + 完整画面」的方案：部分 ROM 上 blur 不生效，两侧直接成暗带，
    //  已回退。要更少裁切就把下面的 3.2f 往小调，代价是卡片更高。）
    val bannerHeight = (bannerWidth / 3.2f).coerceAtLeast(150.dp)
    Surface(
        // 圆角与首页轮播卡片统一为 16dp，保证两张卡视觉一致
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.98f, onClick = onOpen)
    ) {
        // 高度见上方 bannerHeight（原来固定 150dp，只在手机上成立）
        Box(modifier = Modifier.fillMaxWidth().height(bannerHeight)) {
            // 背景：横版剧照（没有则用竖版封面），失败自动退回首字占位；
            // 顶部对齐裁切，优先保住人脸（避免居中裁切把头部切掉）
            CoverImage(
                url = item.backdropUrl ?: item.coverUrl,
                letter = item.title,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
            // 左侧压暗，保证白字可读
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF20B0D10), Color(0xC20B0D10), Color(0x330B0D10))
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = Spacing.lg, end = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(
                        text = "继续观看",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xB3FFFFFF),
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            if (item.lastVideo.seasonNo > 1) append("第${item.lastVideo.seasonNo}季 ")
                            append("第${item.lastVideo.episodeNo}集")
                            if (item.positionMs != null && item.durationMs != null) {
                                append(" · ${remainText(item.positionMs, item.durationMs)}")
                            }
                        },
                        fontSize = 12.5.sp,
                        color = Color(0xCCFFFFFF),
                        maxLines = 1
                    )
                    if (pct > 0) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .height(3.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(Color(0x40FFFFFF))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct / 100f)
                                    .height(3.dp)
                                    .clip(MaterialTheme.shapes.extraLarge)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.bounceClick(pressedScale = 0.94f, onClick = onResume)
                ) {
                    Text(
                        text = "继续播放",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 以 [center] 为圆心、[radius] 为半径的圆形 Path（用于圆形揭示裁剪）。
 * radius <= 0 时返回空 Path → clipPath 会把内容完全裁掉，正好作为动画的起点（从按钮那一点开始扩散）。
 */
private fun circlePath(center: Offset, radius: Float): Path = Path().apply {
    if (radius > 0f) {
        addOval(
            Rect(
                offset = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f)
            )
        )
    }
}
