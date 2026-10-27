package com.hypermux.mobile.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.hypermux.mobile.data.model.CastPendingVo
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.fmtDurationMs
import kotlinx.coroutines.delay

/**
 * 投屏**接收**确认弹窗（计划书 80-cast-detailed.md §9.7，与 TV 端 §9.3 同一流程口径）：
 *
 * - 标题：「收到投屏请求」
 * - 正文：「是否进入播放：{片名} {集名}」+ 发起端 + 续播位置
 * - **默认选择「是」**：倒计时归零自动接受并起播
 * - 点「否」/ 点蒙层 / 按返回键 = 拒绝（服务端记 user-no）
 *
 * 倒计时按 `pending.id` 记忆：同一请求在 PENDING 期间会被每轮轮询重复下发，
 * 不能让弹窗每秒被重建、倒计时被重置（CastReceiver 已按 id 去重，这里再兜一层）。
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
        title = {
            Text(
                text = "收到投屏请求",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 54.dp, height = 76.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (!pending.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = pending.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "是否进入播放：" + listOfNotNull(
                                pending.movieTitle?.takeIf { it.isNotBlank() },
                                pending.episodeLabel?.takeIf { it.isNotBlank() }
                            ).joinToString(" "),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "来自：${pending.senderName ?: "其它设备"}",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pending.positionMs > 0L) {
                            Text(
                                text = "从 ${fmtDurationMs(pending.positionMs)} 处继续",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { left.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${left} 秒后自动播放（默认「是」，点蒙层或返回键 = 「否」）",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            HyperButton(
                text = "否",
                onClick = onReject,
                style = HyperButtonStyle.Ghost
            )
        },
        confirmButton = {
            HyperButton(
                text = if (left > 0) "是（${left}s）" else "是",
                onClick = { onAccept("user-yes") },
                style = HyperButtonStyle.Primary
            )
        }
    )
}
