package com.hypermux.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.mobile.data.model.ContinueVo
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/**
 * 信息流卡片（对齐爱奇艺首页卡片样式）：
 * 竖版海报（现有素材）+ 右上绿色角标 + 左下集号 + 右下评分 + 底部进度，
 * 海报下方为「标题（2 行）+ 灰色描述 + ⋮」。
 */
@Composable
fun MovieCardItem(
    card: MovieCardVo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    forcePortrait: Boolean = false
) {
    val hc = hyperColors()
    // 有横版剧照 → 16:9 信息流大卡（爱奇艺样式）；否则退回 2:3 竖版海报。
    // forcePortrait=true 时强制走竖版海报（分类页/搜索页等列表场景使用），默认 false 行为不变。
    val landscape = !forcePortrait && !card.backdropUrl.isNullOrBlank()
    Column(modifier = modifier.bounceClick(pressedScale = 0.97f, onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, hc.line, MaterialTheme.shapes.medium)
        ) {
            CoverImage(
                url = if (landscape) card.backdropUrl else card.coverUrl,
                letter = card.title,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize()
            )
            // 底部渐变压暗，保证角标/进度可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to Color(0xCC000000)
                        )
                    )
            )
            // 右上集数角标（描边提升质感；电影不显示）
            if (card.episodeTotal > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(0.5.dp, Color(0x59FFFFFF), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "全${card.episodeTotal}集",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            // 左下集号（剧集显示"看到第 N 集"；电影显示"继续观看"）
            card.progress?.let { p ->
                Text(
                    text = if (card.episodeTotal > 1) "看到第${p.episodeNo}集" else "继续观看",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 7.dp, end = 44.dp)
                )
            }
            // 右下评分：金色胶囊 ★7.2
            card.rating?.let { r ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xCC15171C))
                        .border(0.5.dp, hc.rating.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = "★ ${ratingText(r)}",
                        color = hc.rating,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // 底部进度条
            card.progress?.let { p ->
                val pct = progressPct(p.positionMs, p.durationMs)
                if (pct > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x59000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct / 100f)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        // 标题
        Text(
            text = card.title,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(4.dp))
        // 描述行（年份 · 地区 · 集数）+ ⋮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildList {
                    card.year?.let { add("$it") }
                    card.area?.takeIf { it.isNotBlank() }?.let { add(it) }
                    card.tags.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · "),
                fontSize = 12.5.sp,
                color = hc.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = hc.text3,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 继续观看横滑卡（沿用「封面 + 信息」结构，配色随主题）。
 */
@Composable
fun ContinueWatchingCard(
    item: ContinueVo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = hyperColors()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, hc.line),
        modifier = modifier
            .width(268.dp)
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.small)
            ) {
                CoverImage(
                    url = item.coverUrl,
                    letter = item.title,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x59000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            ) {
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("第${item.lastVideo.seasonNo}季 ${item.lastVideo.episodeNo}集")
                        val remain = if (item.positionMs != null && item.durationMs != null) {
                            remainText(item.positionMs, item.durationMs)
                        } else ""
                        if (remain.isNotBlank()) append(" · $remain")
                    },
                    fontSize = 12.sp,
                    color = hc.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.positionMs != null && item.durationMs != null) {
                    Spacer(Modifier.height(7.dp))
                    val pct = progressPct(item.positionMs, item.durationMs)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct / 100f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}
