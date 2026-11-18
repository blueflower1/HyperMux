package com.hypermux.mobile.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.player.PlayerIcons

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
 * 投屏送达后手机端不再跟踪，所以这里没有"正在投屏"高亮态。
 */
@Composable
fun CastButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .bounceClick(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = PlayerIcons.Cast,
            contentDescription = "投屏到电视 / 网页端 / 电脑端 / 手机端",
            tint = Color.White,
            modifier = Modifier.size(size / 2)
        )
    }
}

/**
 * 接收侧徽标（本机正在播别人投过来的那一集）：一条半透明胶囊 + 「结束投屏」。
 * 只贴一条徽标、不遮挡播放区 —— 画面是本机自己在播。
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
