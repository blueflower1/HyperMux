package com.hypermux.tv.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hypermux.tv.data.model.CastPendingVo
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.fmtDurationMs
import kotlinx.coroutines.delay

/**
 * 投屏确认弹窗（计划书 80-cast-detailed.md §9.3，流程口径固定）：
 *
 * - 标题：「接收到来自手机端的投屏请求」
 * - 正文：「是否进入播放：{片名} {集名}」
 * - **默认焦点在「是」**，倒计时归零自动进入播放（默认选择是）
 * - 遥控器 BACK = 「否」（HyperDialog 的 onDismissRequest 走 reject）
 *
 * 倒计时按 `pending.id` 记忆：同一请求在 PENDING 期间会被每轮轮询重复下发，
 * 不能让弹窗每秒被重建、倒计时被重置（CastViewModel 已按 id 去重，这里再兜一层）。
 */
@Composable
fun CastConfirmDialog(
    pending: CastPendingVo,
    confirmSeconds: Int,
    onAccept: (String) -> Unit,
    onReject: () -> Unit
) {
    val total = confirmSeconds.coerceIn(3, 15)
    var left by remember(pending.id) { mutableIntStateOf(total) }

    LaunchedEffect(pending.id) {
        while (left > 0) {
            delay(1_000L)
            left--
        }
        // 倒计时结束 = 默认「是」
        onAccept("countdown")
    }

    HyperDialog(
        onDismissRequest = onReject,
        dismissible = true,
        title = {
            Text(
                text = "接收到来自手机端的投屏请求",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 92.dp, height = 130.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (!pending.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = pending.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(130.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "是否进入播放：" + listOfNotNull(
                                pending.movieTitle?.takeIf { it.isNotBlank() },
                                pending.episodeLabel?.takeIf { it.isNotBlank() }
                            ).joinToString(" "),
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "来自：${pending.senderName ?: "手机端"}",
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pending.positionMs > 0L) {
                            Text(
                                text = "从 ${fmtDurationMs(pending.positionMs)} 处继续",
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = { left.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${left} 秒后自动播放（默认「是」，遥控器返回键 = 「否」）",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // HyperDialog 的行内顺序是 dismissButton → confirmButton，
        // 焦点遍历按布局顺序走，因此把「是」放在前一位 = 打开即聚焦「是」（默认选择是）。
        dismissButton = {
            HyperButton(
                text = if (left > 0) "是（${left}s）" else "是",
                onClick = { onAccept("user-yes") },
                style = HyperButtonStyle.Primary
            )
        },
        confirmButton = {
            HyperButton(
                text = "否",
                onClick = onReject,
                style = HyperButtonStyle.Secondary
            )
        }
    )
}
