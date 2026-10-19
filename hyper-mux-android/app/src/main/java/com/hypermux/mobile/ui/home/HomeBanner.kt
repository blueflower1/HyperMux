package com.hypermux.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.ui.common.CoverImage
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.layout.heightCap
import com.hypermux.mobile.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 首页顶部 Banner 轮播（爱奇艺式）：
 * 16:9 横版剧照 + 左侧渐变遮罩 + 片名/评分/年份 + 自动轮播（4.2s）+ 指示点。
 * 主数据源为后端轮播池 [banners]；为空时退回 [fallback]（当前列表/分区），保证不空。
 * 每页占满整宽（pageSpacing/contentPadding 均为 0）；水平内缩不再在本组件内叠加，
 * 因为宿主 LazyVerticalGrid 的 contentPadding 已统一为 [Spacing.page] 与「继续观看」卡片一致，
 * 若这里再加一层 padding 会变成「16dp + 内层 padding」的双重内缩，导致 banner 比其它卡片窄。
 */
@Composable
fun HomeBannerCarousel(
    banners: List<MovieCardVo>,
    onOpen: (Long) -> Unit,
    autoPlay: Boolean = true,
    fallback: List<MovieCardVo> = emptyList()
) {
    // 优先展示有横版剧照的；最多 6 张
    val items = remember(banners, fallback) {
        val source = banners.ifEmpty { fallback }
        val withBackdrop = source.filter { !it.backdropUrl.isNullOrBlank() }
        (if (withBackdrop.isNotEmpty()) withBackdrop else source).take(6)
    }
    if (items.isEmpty()) return

    val pager = rememberPagerState(pageCount = { items.size })
    val profile = LocalLayoutProfile.current

    // 自动轮播（用户手动滑动后依然继续，节奏温和）
    LaunchedEffect(autoPlay, items.size) {
        if (!autoPlay || items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4200)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            // 每页占满整宽：不留页间距/内边距；水平内缩由宿主 grid 的 contentPadding 统一提供，
            // 这里不再叠加，保证与「继续观看」卡片等宽对齐（卡片自身 16dp 圆角）。
            pageSpacing = 0.dp,
            contentPadding = PaddingValues(0.dp)
        ) { page ->
            val m = items[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    // 宽窗口上 16:9 按宽铺满会吃掉整屏高度（932dp 宽 → 524dp 高）；按档封顶，
                    // 剧照由 ContentScale.Crop 轻微裁切。Compact 档 Infinity → 今天的高度不变。
                    .heightCap(profile.heroMaxHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .bounceClick(pressedScale = 0.985f) { onOpen(m.id) }
            ) {
                CoverImage(
                    url = m.backdropUrl ?: m.coverUrl,
                    letter = m.title,
                    contentDescription = m.title,
                    modifier = Modifier.matchParentSize()
                )
                // 左侧压暗，保证文字可读
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xE60B0D10), Color(0x800B0D10), Color(0x140B0D10))
                            )
                        )
                )
                // 左下：角标 + 片名 + 评分/年份/地区
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                ) {
                    if (m.episodeTotal > 1) {
                        Text(
                            text = "全 ${m.episodeTotal} 集",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        text = m.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        m.rating?.let { r ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xCC15171C))
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                            ) {
                                Text(
                                    text = "★ ${"%.1f".format(r)}",
                                    color = Color(0xFFFFC53D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        val sub = buildList {
                            m.year?.let { add("$it") }
                            m.area?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }.joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(text = sub, color = Color(0xCCFFFFFF), fontSize = 12.5.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 指示点与卡片间距统一为 Spacing.md（与 banner →「继续观看」间距一致，避免 9dp/16dp 混用）
        Spacer(Modifier.height(Spacing.md))
        // 指示点
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.indices.forEach { i ->
                val active = i == pager.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .width(if (active) 16.dp else 6.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color(0x4DFFFFFF)
                        )
                )
            }
        }
    }
}
