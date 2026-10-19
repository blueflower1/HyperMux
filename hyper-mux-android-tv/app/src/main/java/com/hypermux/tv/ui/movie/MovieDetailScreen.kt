@file:OptIn(ExperimentalTvFoundationApi::class)

package com.hypermux.tv.ui.movie

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import com.hypermux.tv.data.model.MovieDetailVo
import com.hypermux.tv.data.model.SeasonVo
import com.hypermux.tv.data.model.VideoItemVo
import com.hypermux.tv.ui.common.CoverImage
import com.hypermux.tv.ui.common.ErrorState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.SectionTitle
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.common.ratingText
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.tvClickable

/**
 * 影片详情页（§5.5）：
 * 沉浸式头图 + 元数据（图上白色系文字） + 简介卡片 + "继续看"主按钮 + 季 Tab + 剧集方格网格。
 */

/** 压在图上的文字统一加暗阴影：叠了两层遮罩后，即使剧照亮部也能保证对比度 ≥ 4.5:1 */
private val HeroShadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1.5f), blurRadius = 10f)

@Composable
fun MovieDetailScreen(
    navController: NavController,
    movieId: Long,
    onPlay: (movieId: Long, videoId: Long) -> Unit
) {
    val vm: MovieViewModel = hiltViewModel()

    val detail by vm.detail.collectAsStateWithLifecycle(initialValue = null)
    val seasons by vm.seasons.collectAsStateWithLifecycle(initialValue = null)
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)

    // 季 Tab（仅多季时展示）
    var selectedSeason by remember { mutableIntStateOf(seasons?.seasons?.firstOrNull()?.seasonNo ?: 1) }
    // 简介展开
    var introExpanded by remember { mutableStateOf(false) }

    // 当前季的集数列表
    val singleMovie = detail?.episodeTotal?.let { it <= 1 } ?: false
    val seasonVideos = if (singleMovie) emptyList() else seasons?.seasons
        ?.firstOrNull { it.seasonNo == selectedSeason }
        ?.videos.orEmpty()

    // 顶栏「随滚动渐显」的唯一数据源：与内容网格共用同一个滚动状态（网格在下方的分支里传入 state）
    val gridState = rememberLazyGridState()
    // 「已滚动」判定：① 首项已不是头图卡片（index > 0，说明头图已完全滚出）
    //                  ② 或头图卡片已向上滚过 40dp（超过顶栏 56dp 的一半，肉眼看就是"开始滚了"）
    // 用 derivedStateOf 只在结果翻转时才触发重组，避免每帧滚动都刷新顶栏。
    val topBarScrolled by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 40
        }
    }

    // 焦点：进入页面后把焦点给主播放按钮（§6.1 详情页的"进入即聚焦"目标）
    val playRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            DetailTopBar(
                title = detail?.title ?: "影片详情",
                scrolled = topBarScrolled,
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        when {
            isLoading && detail == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    SkeletonPosterGrid(rows = 3, columns = 4)
                }
            }
            error != null && detail == null -> {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    ErrorState(error!!, onRetry = { vm.retry() }, focusRequester = retryRequester)
                    TvAutoFocus(retryRequester)
                }
            }
            detail != null -> {
                val d = detail!!
                // 主按钮与选集「当前集」共用同一个续播目标 = 详情断点 detail.progress 指向的那一集。
                // vm.continueVideo() 正是「从第 N 集继续」按钮使用的集，两处保持一致。
                val continueVideo = vm.continueVideo()
                // ★「当前集」的唯一来源 ★
                // 只能取详情断点（progress）指定的这一集。**绝对不能用每格自身的 video.progress 推断**：
                // 每格各自推断时，任何"看过一部分"的历史集（例如看过一点的第 1、2 集）都会被判成"当前集"，
                // 于是出现「主按钮说从第 4 集继续，但 1、2、4 三格都带绿边框」的 bug（本次根因）。
                val currentVideoId = continueVideo?.id
                // 头图不再做「整宽突破 contentPadding」的补偿：
                //   旧实现用 requiredWidth(maxWidth + Spacing.page * 2) + offset(x = -Spacing.page) 让图片顶到屏幕两边，
                //   副作用是把**卡内文案也一起左移**，导致左下角标题/评分贴上屏幕外被裁掉。
                //   现在改为「网格 contentPadding 提供左右各 14dp 内缩 + 16dp 圆角卡片」的常规布局，
                //   彻底删除那套 offset/requiredWidth hack（图片与文案一起被卡片裁进圆角内，不再有裁切）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    LazyVerticalGrid(
                        // 顶栏「渐显」依赖这个 state（见上方 gridState / topBarScrolled）
                        state = gridState,
                        // TV：剧集格从手机端 5 列放大到 **8 列**（1080p 下每格约 200dp，10 英尺视距可读）
                        columns = GridCells.Fixed(8),
                        // 左右留白统一由网格 contentPadding 控制（原来加在每格上 → 横向 28dp / 纵向 0，丑且不均）
                        contentPadding = PaddingValues(
                            start = Spacing.page,
                            end = Spacing.page,
                            top = Spacing.sm,
                            bottom = Spacing.lg
                        ),
                        // 横向与纵向用同一个间距值，格与格的空隙完全一致
                        horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
                        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
                    ) {
                        // ---- 头部：左「竖版海报」+ 右「片名/元数据/简介/标签 + 主按钮」 ----
                        // TV 左右结构，规格与理由见 DetailHeader 的注释（手机端是整宽 16:9 头图 + 图上压字）
                        item(
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            DetailHeader(
                                detail = d,
                                continueVideo = continueVideo,
                                singleMovie = singleMovie,
                                introExpanded = introExpanded,
                                onToggleIntro = { introExpanded = !introExpanded },
                                onPlay = {
                                    val target = continueVideo ?: vm.firstVideo()
                                    if (target != null) onPlay(movieId, target.id)
                                },
                                playRequester = playRequester
                            )
                        }

                        // ---- 剧集区（电影跳过）----
                        if (!singleMovie && !seasons?.seasons.isNullOrEmpty()) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                EpisodeHeader(
                                    seasons = seasons!!.seasons,
                                    selectedSeason = selectedSeason,
                                    onSelect = { selectedSeason = it }
                                )
                            }
                        }

                        if (!singleMovie && seasonVideos.isEmpty()) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(160.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "暂无剧集",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }

                        items(
                            items = seasonVideos,
                            key = { it.id }
                        ) { video ->
                            EpisodeItem(
                                video = video,
                                // 唯一的「当前集」id（可能是 null：没有任何断点，或断点所在集不在本季列表里）
                                currentVideoId = currentVideoId,
                                onClick = { onPlay(movieId, video.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 进入页面 → 焦点给主播放按钮（§6.1）；数据未就绪时等 ready 变 true 再抢焦点
    TvAutoFocus(playRequester, ready = detail != null && !isLoading)
}

/**
 * 详情页头部（TV 左右结构）。
 *
 * 手机端是「整宽 16:9 头图 + 图上压标题 + 下方另起一行放主按钮」，搬到 10 英尺视距上有三个问题：
 *  1) 16:9 头图在 1080p 下高达约 540dp，几乎吞掉一整屏，用户要滚过一屏才看到「选集」；
 *  2) 标题/主按钮被头图推到屏幕上下两端，遥控器焦点路径长，方向键要按好几次；
 *  3) 图上压字的对比度全靠重遮罩换来，暗剧照上仍然发灰、细节全丢。
 *
 * 改成左海报 + 右信息（片名 / 元数据 / 简介 / 标签 / **继续播放主按钮**）后：
 *  - 海报用竖版 2:3、宽 240dp（高约 360dp），高度压到 2/3 屏，选集区一屏内可见；
 *  - 「继续播放」就在海报右侧同一水平线上，进入详情页焦点直接落在这里（[playRequester]），一次 OK 起播；
 *  - 文字放在深色页面底上（不压图），对比度天然达标，不再需要重遮罩。
 * 外层的左右内缩由网格 contentPadding 提供，这里不再叠加 padding 以免双份留白。
 */
@Composable
private fun DetailHeader(
    detail: MovieDetailVo,
    continueVideo: VideoItemVo?,
    singleMovie: Boolean,
    introExpanded: Boolean,
    onToggleIntro: () -> Unit,
    onPlay: () -> Unit,
    playRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    // 年份 · 地区 · 类别（类别名即"电视剧/电影"这类形态信息）
    val meta = listOfNotNull(
        detail.year?.toString(),
        detail.area?.takeIf { it.isNotBlank() },
        detail.category.name.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    val posterShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        verticalAlignment = Alignment.Top
    ) {
        // ---------------- 左：竖版海报（2:3） ----------------
        Box(
            modifier = Modifier
                .width(TvSize.detailPoster)
                .aspectRatio(2f / 3f)
                .clip(posterShape)
                // 图片未加载完成时先铺深底，避免闪白
                .background(Color(0xFF15171C))
                .border(1.dp, hyperColors().line, posterShape)
        ) {
            CoverImage(
                url = detail.coverUrl ?: detail.backdropUrl,
                letter = detail.title,
                contentDescription = detail.title,
                modifier = Modifier.matchParentSize(),
                shape = RectangleShape
            )
        }

        // ---------------- 右：片名 / 元数据 / 简介 / 标签 / 主按钮 ----------------
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            )
            detail.originalTitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 20.sp
                    )
                )
            }
            Spacer(Modifier.height(Spacing.md))
            // 评分 + 年份·地区·类别 + 集数形态（一排胶囊/文字，10 英尺视距下放大到 18sp）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                detail.rating?.let { HeroPill(text = "★ ${ratingText(it)}", textColor = Color(0xFFFFC53D)) }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        style = TextStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 20.sp
                        )
                    )
                }
                // 集数 / 影片形态：电影只显示"电影"，不显示"共 1 集"
                HeroPill(
                    text = if (detail.episodeTotal <= 1) "电影" else "全 ${detail.episodeTotal} 集",
                    textColor = Color.White
                )
            }

            // 标签（TvLazyRow：标签多到超屏时，D-pad 焦点会把它带进可视区）
            if (detail.tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(items = detail.tags, key = { it.id }) { tag ->
                        HyperChip(text = tag.name)
                    }
                }
            }

            // 简介（收起 3 行 / 展开全量，与手机端一致的溢出判定）
            detail.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                var overflow by remember(intro) { mutableStateOf(false) }
                Spacer(Modifier.height(Spacing.md))
                Column(modifier = Modifier.animateContentSize()) {
                    Text(
                        text = intro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        maxLines = if (introExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            // 只用「收起态」的测量结果：≤3 行就不显示「展开」按钮
                            if (!introExpanded) overflow = layout.hasVisualOverflow
                        }
                    )
                    if (overflow) {
                        HyperButton(
                            text = if (introExpanded) "收起 ↑" else "展开 ↓",
                            onClick = onToggleIntro,
                            style = HyperButtonStyle.Ghost,
                            small = true,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }
            }

            // 主按钮：继续播放（进入页面的首焦点）
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                HyperButton(
                    text = when {
                        continueVideo != null && singleMovie -> "继续播放"
                        continueVideo != null -> "从第 ${continueVideo.episodeNo} 集继续"
                        singleMovie -> "立即播放"
                        else -> "播放第 1 集"
                    },
                    onClick = onPlay,
                    icon = Icons.Filled.PlayArrow,
                    // 只占右侧信息栏的宽度（不是整屏）；
                    // 固定 dp 宽度在 1080p(density 320) 下会挤爆右栏，跨密度下不可控
                    modifier = Modifier.weight(1f),
                    focusRequester = playRequester
                )
            }
        }
    }
}

/**
 * 详情页顶栏（自绘 Row，替代原来的朴素 TopAppBar）：
 *  - 返回按钮 = 38dp 圆形半透明胶囊里的图标（不再是裸箭头）；
 *  - 标题 18sp ExtraBold、单行省略，与返回按钮间距 12dp；
 *  - 背景色与底部 1dp 发丝线随列表滚动渐显：未滚动全透明（不挡头图），已滚动为 surface 94%。
 */
@Composable
private fun DetailTopBar(
    title: String,
    scrolled: Boolean,
    onBack: () -> Unit
) {
    val hc = hyperColors()
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    // 两个颜色都用 220ms tween 过渡，与列表滚动手感对齐（不出现"突然变黑"）
    val containerColor by animateColorAsState(
        targetValue = if (scrolled) MaterialTheme.colorScheme.surface.copy(alpha = 0.94f) else Color.Transparent,
        animationSpec = tween(220),
        label = "detailTopBarContainer"
    )
    val hairlineColor by animateColorAsState(
        targetValue = if (scrolled) hc.line else Color.Transparent,
        animationSpec = tween(220),
        label = "detailTopBarHairline"
    )
    // 注意：NavHost 已统一消费状态栏 insets（见 AppNavigation），这里不能再加 statusBarsPadding，否则顶栏会被下推
    Column(modifier = Modifier
        .fillMaxWidth()
        .background(containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = Spacing.page),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回：圆形半透明胶囊 + 细描边；TV 上改焦点放大 + 绿描边（bounceClick 的"按压"在遥控器上不可见）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(surfaceVariant.copy(alpha = 0.7f))
                    .border(1.dp, hc.line, CircleShape)
                    .tvClickable(shape = CircleShape, focusedScale = 1.12f, onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    // 透明态也用 onSurface（深色主题下即白色），压在头图上有阴影兜底、可读
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    shadow = HeroShadow
                )
            )
        }
        // 底部 1dp 发丝线，随滚动淡入（未滚动为全透明 → 不干扰沉浸式头图）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(hairlineColor)
        )
    }
}

/** 压在头图上的小胶囊：半透明黑底 + 高对比文字（黑底白字 / 黑底金字） */
@Composable
private fun HeroPill(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC15171C))
            .padding(horizontal = 13.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 剧集区标题（渐变竖条 + ExtraBold，与首页分区标题同风格）+ 季 Tab（仅多季时展示） */
@Composable
private fun EpisodeHeader(
    seasons: List<SeasonVo>,
    selectedSeason: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(text = "选集")
        if (seasons.size > 1) {
            Spacer(Modifier.height(Spacing.md))
            // 多季时季 Tab 可能超出一屏；用 TvLazyRow 保证 D-pad 焦点自动滚进可视区
            TvLazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(items = seasons, key = { it.seasonNo }) { season ->
                    HyperChip(
                        text = "第${season.seasonNo}季",
                        highlight = season.seasonNo == selectedSeason,
                        onClick = { onSelect(season.seasonNo) }
                    )
                }
            }
        }
    }
}

/**
 * 单个集数格：正方形（aspectRatio(1f)）+ 10dp 圆角，号码水平垂直居中。
 *
 * ★ 「当前集」全局只能有一个 ★：由调用方传入唯一的 [currentVideoId]（= 详情断点 / 主按钮
 *   「从第 N 集继续」的那一集），绝不按每格自身的 video.progress 推断 —— 每格自推会把
 *   所有"看过一部分"的历史集都画成绿色边框的"当前集"（本次 bug 的根因）。
 *
 * 四态：
 *  1. 当前集（唯一）= 主色淡底（primary 12%）+ 1.5dp 主色描边 + 15sp Bold 主色字；
 *  2. 已看完（progress.finished == 1）= 更淡底（surfaceVariant 35%）+ 灰字 + 普通发丝线（无绿边框）；
 *  3. 看过一部分（有 progress、positionMs > 0、非当前集、未看完）= 普通底 + 普通字 +
 *     底部 2dp 主色细进度线（只表示看过一部分，不画绿边框、不用绿字）；
 *  4. 其它 = 普通样式（surfaceVariant 底 + 发丝线描边 + 常规字色）。
 *
 * 交互：单击播放。
 * 原来每格自带 padding(horizontal = Spacing.page)，导致横向空隙 28dp、纵向 0 → 现已删除，改由网格统一控制。
 */
@Composable
private fun EpisodeItem(
    video: VideoItemVo,
    currentVideoId: Long?,
    onClick: () -> Unit
) {
    val hc = hyperColors()
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val finished = video.progress?.finished == 1
    // 当前集：只认调用方给的唯一 id，与这一格自己的进度无关
    val isCurrent = currentVideoId != null && video.id == currentVideoId
    // 看过一部分：有进度、看过一点、未看完，且不是当前集（当前集走专属绿色样式）
    val partiallyWatched = !isCurrent && !finished && (video.progress?.positionMs ?: 0L) > 0L
    // 详情断点指向的集若已被标记看完，仍以「当前集」样式为准，此时不再叠加"已看"角标（避免自相矛盾）
    val showFinishedLabel = finished && !isCurrent
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                when {
                    isCurrent -> primary.copy(alpha = 0.12f)          // 当前集：主色淡底
                    finished -> surfaceVariant.copy(alpha = 0.35f)     // 已看完：比普通底更淡
                    else -> surfaceVariant                              // 看过一部分 / 未看：普通底
                }
            )
            .border(
                // 只有「当前集」有绿色描边；已看完 / 看过一部分 / 未看一律 1dp 发丝线
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) primary else hc.line,
                shape = shape
            )
            .tvClickable(shape = shape, focusedScale = 1.06f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${video.episodeNo}",
            fontSize = 20.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = when {
                isCurrent -> primary
                finished -> hc.text3
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        if (showFinishedLabel) {
            Text(
                text = "已看",
                fontSize = 12.sp,
                color = hc.text3,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
            )
        }
        // 「看过一部分」专属：格底 2dp 细进度线（当前集 / 已看完都不画）
        if (partiallyWatched) {
            video.progress?.let { p ->
                if (p.durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(primary.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
                                )
                                .height(2.dp)
                                .background(primary)
                        )
                    }
                }
            }
        }
    }
}
