@file:OptIn(ExperimentalTvFoundationApi::class)

package com.hypermux.tv.ui.movie

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import com.hypermux.tv.data.cache.DownloadState
import com.hypermux.tv.data.cache.MediaCacheManager
import com.hypermux.tv.data.model.MovieDetailVo
import com.hypermux.tv.data.model.SeasonVo
import com.hypermux.tv.data.model.VideoItemVo
import com.hypermux.tv.ui.common.CoverImage
import com.hypermux.tv.ui.common.ErrorState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.SectionTitle
import com.hypermux.tv.ui.common.SkeletonPosterGrid
import com.hypermux.tv.ui.common.TvSidePanel
import com.hypermux.tv.ui.common.ratingText
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvKeys.isMenu
import com.hypermux.tv.ui.tv.TvMessageOverlay
import com.hypermux.tv.ui.tv.tvClickable
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 影片详情页（§5.5）：
 * 沉浸式头图 + 元数据（图上白色系文字） + 简介卡片 + "继续看"主按钮 + 季 Tab + 剧集方格网格。
 * 另提供离线缓存入口：集数格**长按**弹「缓存本集」、区块标题右侧「缓存本季」、已缓存格右上角小角标。
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

    // 页面内一次性提示（空间不足等）：走 TV 提示层，不用系统 Toast（电视上左下角小字读不清）
    var message by remember { mutableStateOf<String?>(null) }

    /** 显式下载确认的统一入口：先预检设备剩余空间（不足则提示，不入队） */
    fun cacheChecked(videos: List<VideoItemVo>) {
        if (!vm.cacheManager.canEnqueueForSpace()) {
            message = "设备空间不足，请删除部分下载后再试"
            return
        }
        vm.cacheEpisodes(videos)
    }

    val detail by vm.detail.collectAsStateWithLifecycle(initialValue = null)
    val seasons by vm.seasons.collectAsStateWithLifecycle(initialValue = null)
    val isLoading by vm.isLoading.collectAsStateWithLifecycle(initialValue = true)
    val error by vm.error.collectAsStateWithLifecycle(initialValue = null)
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle(initialValue = emptyMap())

    // 季 Tab（仅多季时展示）
    var selectedSeason by remember { mutableIntStateOf(seasons?.seasons?.firstOrNull()?.seasonNo ?: 1) }
    // 简介展开
    var introExpanded by remember { mutableStateOf(false) }

    // 选集缓存底部面板：是否展示 + 打开时预选的集（长按某一格 → 预选该集）
    var showCacheSheet by remember { mutableStateOf(false) }
    var cachePreselect by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 当前季的集数列表（提到网格之前：格角标的"已有分片"轮询需要它）
    val singleMovie = detail?.episodeTotal?.let { it <= 1 } ?: false
    val seasonVideos = if (singleMovie) emptyList() else seasons?.seasons
        ?.firstOrNull { it.seasonNo == selectedSeason }
        ?.videos.orEmpty()
    // 电影（单文件）：没有选集区，缓存入口放在主按钮右侧，这里先取到那一集
    val singleVideo = if (singleMovie) vm.firstVideo() else null

    // 已下载集的 id 快照：下载完成的集由 downloadStates 反映，
    // **部分下载**（有字节但未完成）的状态变化只能靠轮询（Cache 的 content index 无变更回调），
    // （Cache 的 content index 没有变更回调），3s 一次足够，也不会让网格频繁重组。
    var cachedVideoIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(seasonVideos, singleVideo?.id) {
        while (true) {
            cachedVideoIds = (seasonVideos + listOfNotNull(singleVideo)).asSequence()
                .filter { vm.isCached(it.id) }
                .map { it.id }
                .toSet()
            delay(3_000)
        }
    }
    // 电影那一集的下载态（下载中显示百分比、完成显示"已缓存"）
    val singleDownload = singleVideo?.let { downloadStates[MediaCacheManager.mediaIdOf(movieId, it.id)] }
    val singleCached = singleVideo != null &&
        (cachedVideoIds.contains(singleVideo.id) || singleDownload?.isCompleted == true)
    val singleBusy = singleDownload?.isActive == true

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
                                // 电影没有选集区，缓存入口放在主按钮右侧（剧集走「选集」标题旁的「缓存」）
                                cacheLabel = singleVideo?.let {
                                    when {
                                        singleCached -> "已缓存"
                                        singleBusy -> "${singleDownload?.percent?.roundToInt() ?: 0}%"
                                        else -> "缓存"
                                    }
                                },
                                cacheEnabled = singleVideo != null && !singleCached && !singleBusy,
                                introExpanded = introExpanded,
                                onToggleIntro = { introExpanded = !introExpanded },
                                onCache = { if (singleVideo != null) cacheChecked(listOf(singleVideo)) },
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
                                    onSelect = { selectedSeason = it },
                                    // 「缓存」：打开选集面板（多选后批量缓存）
                                    onOpenCache = {
                                        if (seasonVideos.isNotEmpty()) {
                                            cachePreselect = emptySet()
                                            showCacheSheet = true
                                        }
                                    }
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
                            val state = downloadStates[MediaCacheManager.mediaIdOf(movieId, video.id)]
                            EpisodeItem(
                                video = video,
                                // 唯一的「当前集」id（可能是 null：没有任何断点，或断点所在集不在本季列表里）
                                currentVideoId = currentVideoId,
                                onClick = { onPlay(movieId, video.id) },
                                fullCached = cachedVideoIds.contains(video.id) || state?.isCompleted == true,
                                downloadPercent = state?.takeIf { it.isActive }?.percent?.roundToInt(),
                                // 长按 = 打开选集面板并预选该集（比"直接弹确认框"更接近爱奇艺的批量缓存体验）
                                onLongPress = {
                                    cachePreselect = setOf(video.id)
                                    showCacheSheet = true
                                }
                            )
                        }
                    }
                }

                // 选集缓存面板（对齐爱奇艺：多选 + 全选/反选 + 底部「缓存(N)」）
                if (showCacheSheet) {
                    CacheSheet(
                        movieId = movieId,
                        movieTitle = d.title,
                        seasonNo = selectedSeason,
                        episodes = seasonVideos,
                        cachedIds = cachedVideoIds,
                        downloadStates = downloadStates,
                        initialSelected = cachePreselect,
                        onDismiss = { showCacheSheet = false },
                        onConfirm = { picked ->
                            cacheChecked(picked)
                            showCacheSheet = false
                        }
                    )
                }
            }
        }
    }

    // 进入页面 → 焦点给主播放按钮（§6.1）；数据未就绪时等 ready 变 true 再抢焦点
    TvAutoFocus(playRequester, ready = detail != null && !isLoading)

    // 一次性提示（空间不足等）
    message?.let { TvMessageOverlay(message = it, onDismiss = { message = null }) }
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
    cacheLabel: String?,   // 非 null 才显示「缓存 / 已缓存 / xx%」（电影专用，剧集走选集标题旁的「缓存」）
    cacheEnabled: Boolean,
    introExpanded: Boolean,
    onToggleIntro: () -> Unit,
    onCache: () -> Unit,
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

            // 主按钮：继续播放（进入页面的首焦点）+ 电影专用的「缓存」
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
                    // 只占右侧信息栏的宽度（不是整屏），且电影场景下自动给「缓存」按钮让位；
                    // 固定 dp 宽度在 1080p(density 320) 下会挤爆右栏，跨密度下不可控
                    modifier = Modifier.weight(1f),
                    focusRequester = playRequester
                )
                if (cacheLabel != null) {
                    HyperButton(
                        text = cacheLabel,
                        onClick = onCache,
                        style = HyperButtonStyle.Secondary,
                        enabled = cacheEnabled
                    )
                }
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

/** 剧集区标题（渐变竖条 + ExtraBold，与首页分区标题同风格）+ 季 Tab（仅多季时展示）+「缓存」 */
@Composable
private fun EpisodeHeader(
    seasons: List<SeasonVo>,
    selectedSeason: Int,
    onSelect: (Int) -> Unit,
    onOpenCache: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(
            text = "选集",
            trailing = {
                // 区块标题右侧的「缓存」：第二级样式、小尺寸，点开选集缓存面板
                HyperButton(
                    text = "缓存",
                    onClick = onOpenCache,
                    style = HyperButtonStyle.Secondary,
                    small = true
                )
            }
        )
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
 * 五态（新增缓存态）：
 *  1. 当前集（唯一）= 主色淡底（primary 12%）+ 1.5dp 主色描边 + 15sp Bold 主色字；
 *  2. 已看完（progress.finished == 1）= 更淡底（surfaceVariant 35%）+ 灰字 + 普通发丝线（无绿边框）；
 *  3. 看过一部分（有 progress、positionMs > 0、非当前集、未看完）= 普通底 + 普通字 +
 *     底部 2dp 主色细进度线（只表示看过一部分，不画绿边框、不用绿字）；
 *  4. 其它 = 普通样式（surfaceVariant 底 + 发丝线描边 + 常规字色）；
 *  5. 已缓存 / 缓存中 = 右上角小角标：缓存中显示白色百分比，已缓存显示白色「✓」。
 *
 * 交互：单击播放；**长按** = 「缓存本集」（由调用方弹确认框）。
 * 原来每格自带 padding(horizontal = Spacing.page)，导致横向空隙 28dp、纵向 0 → 现已删除，改由网格统一控制。
 */
@Composable
private fun EpisodeItem(
    video: VideoItemVo,
    currentVideoId: Long?,
    onClick: () -> Unit,
    fullCached: Boolean = false,
    downloadPercent: Int? = null,
    onLongPress: () -> Unit = {}
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
            // TV：遥控器没有"长按"，改「OK = 播放 / MENU = 缓存本集」（§6.4）
            // 焦点放大 + 描边由 tvClickable 统一负责（手机端的 detectTapGestures 在 D-pad 下完全不可达）
            .tvClickable(shape = shape, focusedScale = 1.06f, onClick = onClick)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.isMenu()) {
                    onLongPress()
                    true
                } else {
                    false
                }
            },
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
        // 缓存角标：**深色小圆底 + 白字/白勾**（固定色）。
        // 不能只用白字直接压在格底上：格底是 surfaceVariant，浅色主题下接近白色 → 白字会隐形。
        when {
            downloadPercent != null -> CacheBadge(text = "$downloadPercent%")
            fullCached -> CacheBadge()
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

/**
 * 按压回弹 + 长按（集数格专用）：单击播放、长按缓存。
 *
 * 为什么不用 [bounceClick] 再另挂一个 pointerInput：两个 Modifier 各自独立处理指针，
 * 长按后松手会把单击也触发一遍（既缓存又跳转）。这里用 detectTapGestures 统一分发，
 * 长按与单击互斥，行为确定。
 */
private fun Modifier.bounceLongClick(
    pressedScale: Float = 0.92f,
    onClick: () -> Unit,
    onLongPress: () -> Unit
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = Motion.FAST, easing = Motion.Ease),
        label = "episodeBounceScale"
    )
    this
        .scale(scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    // 等抬手或手势被取消（长按后松手同样会返回），保证回弹一定复位
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { onClick() },
                onLongPress = { onLongPress() }
            )
        }
}

/* =========================================================================
 * 选集缓存（底部面板 + 集数格 + 角标）
 * ========================================================================= */

/** 面板里一集的可选状态：可勾选 / 下载中 / 已缓存 */
private data class CacheCellState(
    val cached: Boolean = false,
    val percent: Int? = null
) {
    val selectable: Boolean get() = !cached && percent == null
}

/**
 * 选集缓存底部面板（对齐爱奇艺「缓存」选集）：
 *  - 顶部：季标题 + 全选/取消全选；
 *  - 中部：5 列集数网格（可勾选；已缓存/下载中的集显示状态且不可再选）；
 *  - 底部：已选数量 + 「缓存(N)」主按钮。
 *
 * 为什么做成底部面板而不是弹窗：一季动辄几十集，弹窗列表既放不下也不好看；
 * 底部面板能铺开网格、支持多选与全选，这是长视频 App 的标准做法。
 */
@Composable
private fun CacheSheet(
    movieId: Long,
    movieTitle: String,
    seasonNo: Int,
    episodes: List<VideoItemVo>,
    cachedIds: Set<Long>,
    downloadStates: Map<String, DownloadState>,
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<VideoItemVo>) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary

    // 每集的缓存状态（可勾选 / 下载中 / 已缓存）——普通函数，不参与组合
    fun stateOf(video: VideoItemVo): CacheCellState {
        val download = downloadStates[MediaCacheManager.mediaIdOf(movieId, video.id)]
        return when {
            download?.isCompleted == true || cachedIds.contains(video.id) -> CacheCellState(cached = true)
            download?.isActive == true -> CacheCellState(percent = download.percent.roundToInt())
            else -> CacheCellState()
        }
    }

    // 打开时按预选（长按某一格）初始化；已缓存/下载中的预选会被自动剔除
    var selected by remember(episodes, initialSelected) {
        mutableStateOf(
            episodes.asSequence()
                .filter { initialSelected.contains(it.id) && stateOf(it).selectable }
                .map { it.id }
                .toSet()
        )
    }
    val selectableIds = remember(episodes, cachedIds, downloadStates) {
        episodes.asSequence().filter { stateOf(it).selectable }.map { it.id }.toSet()
    }
    val allSelected = selectableIds.isNotEmpty() && selected.containsAll(selectableIds)

    TvSidePanel(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "缓存 · 第${seasonNo}季",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = movieTitle,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HyperButton(
                text = if (allSelected) "取消全选" else "全选",
                onClick = { selected = if (allSelected) emptySet() else selectableIds },
                style = HyperButtonStyle.Ghost,
                small = true,
                enabled = selectableIds.isNotEmpty()
            )
        }
        Spacer(Modifier.height(Spacing.md))
        // TV：面板整高（TvSidePanel 是右侧抽屉），网格直接吃剩余高度即可；
        // 手机端"底部弹层 90% 屏高 + heightIn 双封顶"的那套约束在抽屉里不再需要。
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            items(episodes, key = { it.id }) { video ->
                CacheCell(
                    video = video,
                    selected = selected.contains(video.id),
                    state = stateOf(video),
                    onToggle = {
                        selected = if (selected.contains(video.id)) {
                            selected - video.id
                        } else {
                            selected + video.id
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selected.isEmpty()) "选择要缓存的剧集" else "已选 ${selected.size} 集",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            HyperButton(
                text = if (selected.isEmpty()) "缓存" else "缓存(${selected.size})",
                onClick = { onConfirm(episodes.filter { selected.contains(it.id) }) },
                enabled = selected.isNotEmpty()
            )
        }
    }
}

/** 面板里的单个集数格（选中 = 品牌绿淡底 + 绿描边 + 右上角绿勾） */
@Composable
private fun CacheCell(
    video: VideoItemVo,
    selected: Boolean,
    state: CacheCellState,
    onToggle: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val hc = hyperColors()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(
                when {
                    state.cached -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    selected -> primary.copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) primary else hc.line,
                shape = shape
            )
            .then(
                if (state.selectable) Modifier.tvClickable(shape = shape, focusedScale = 1.06f, onClick = onToggle)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${video.episodeNo}",
            fontSize = 20.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = when {
                selected -> primary
                state.cached -> hc.text3
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        when {
            state.cached -> CacheBadge()
            state.percent != null -> CacheBadge(text = "${state.percent}%")
            selected -> Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

/**
 * 集数格右上角的缓存角标：**深色小圆底 + 白字/白勾**（固定色，不随主题）。
 * 为什么必须加一层深色底：它压在格底（surfaceVariant）上，而格底在**浅色主题**下接近白色，
 * 纯白文字会直接隐形；固定深色圆底才能在深浅两种主题下都读得清。
 */
@Composable
private fun BoxScope.CacheBadge(text: String? = null) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已缓存",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
