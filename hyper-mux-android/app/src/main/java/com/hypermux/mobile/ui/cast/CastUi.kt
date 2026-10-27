package com.hypermux.mobile.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hypermux.mobile.data.model.CastBriefVo
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.player.PlayerIcons
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/** 设备名缺省时的平台回落（与服务端 CastService.defaultName 同口径：电视 / 网页端 / 电脑端 / 手机） */
fun castTargetName(platform: String?, deviceName: String?): String =
    deviceName?.takeIf { it.isNotBlank() } ?: when (platform) {
        "web" -> "网页端"
        "windows" -> "电脑端"
        "mobile" -> "手机端"
        else -> "电视"
    }

/**
 * 播放页控制条上的投屏按钮（样式与同区已有的缓存/倍速按钮一致：圆形自绘、固定白色系）。
 * [casting] = true 时叠一层品牌绿淡底，表示"这一集正投在目标端上"。
 */
@Composable
fun CastButton(
    casting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (casting) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                else Modifier
            )
            .bounceClick(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = PlayerIcons.Cast,
            contentDescription = if (casting) "已投屏" else "投屏到电视 / 网页端 / 电脑端 / 手机端",
            tint = Color.White,
            modifier = Modifier.size(size / 2)
        )
    }
}

/**
 * 投屏生效后的手机侧状态卡（**不是遥控器**：只有「取消投屏」，没有任何播放控制控件）。
 * 覆盖整个播放区，本地播放器此时已被调用方 pause。
 */
@Composable
fun CastSentOverlay(
    active: CastBriefVo,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = hyperColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D0C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, hc.line, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = PlayerIcons.Cast,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "已投送至 ${active.targetName ?: "目标端"}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 54.dp, height = 76.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!active.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = active.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = active.movieTitle ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    active.episodeLabel?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "目标端正在播放，本机已停止本地播放",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            HyperButton(
                text = "取消投屏",
                onClick = onCancel,
                style = HyperButtonStyle.Secondary
            )

            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "本机不支持遥控目标端；到电视 / 网页端 / 电脑端 / 目标手机上操作即可。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 接收侧徽标（本机正在播别人投过来的那一集）：一条半透明胶囊 + 「结束投屏」。
 * 与 [CastSentOverlay] 相反 —— 画面是本机自己在播，所以只贴一条徽标、不遮挡播放区。
 * 配色固定白色系：它只出现在播放区（视频/黑底）的浮层里，与同区其它控件同一套颜色。
 */
@Composable
fun CastIncomingBadge(
    senderName: String?,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xB3000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(primary)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "投屏接收中 · 来自 ${senderName ?: "其它设备"}",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xE6FFFFFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "结束投屏",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = primary,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .bounceClick(pressedScale = 0.9f, onClick = onFinish)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
