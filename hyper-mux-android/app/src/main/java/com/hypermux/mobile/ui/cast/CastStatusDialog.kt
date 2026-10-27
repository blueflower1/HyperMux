package com.hypermux.mobile.ui.cast

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hypermux.mobile.data.cast.CastOutcome
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperDialog

/**
 * 投屏等待 / 结果弹窗（计划书 80-cast-detailed.md §8.3）。
 *
 * 文案按流程口径固定（接收端 = 电视 / 网页端 / 电脑端 / 手机端，所以不写死「TV端」）：
 *  - 接受：「目标端已接受你远程投屏的请求」
 *  - 拒绝：「目标端已拒绝投屏请求」
 *  - 超时：「目标端未响应，请稍后重试」
 */
@Composable
fun CastStatusDialog(vm: CastViewModel) {
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()

    waiting?.let { w ->
        HyperDialog(
            onDismissRequest = { vm.cancelCast() },
            title = { DialogTitle("投屏请求") },
            text = {
                Text(
                    text = "正在等待「${w.targetName}」确认…\n目标端（电视 / 网页端 / 电脑端 / 手机端）会弹出确认窗口（默认「是」，倒计时结束自动播放）。",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                HyperButton(
                    text = "取消投屏",
                    onClick = { vm.cancelCast() },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }

    outcome?.let { o ->
        val (title, body) = when (o) {
            is CastOutcome.Accepted -> "投屏成功" to "「${o.targetName}」已接受你远程投屏的请求。"
            is CastOutcome.Rejected -> "投屏被拒绝" to "目标端已拒绝投屏请求。"
            is CastOutcome.Expired -> "投屏未生效" to "目标端未响应，请稍后重试。"
            is CastOutcome.Cancelled -> "已取消投屏" to "投屏请求已取消。"
            is CastOutcome.Failed -> "投屏失败" to o.msg
        }
        HyperDialog(
            onDismissRequest = { vm.dismissOutcome() },
            title = { DialogTitle(title) },
            text = {
                Text(
                    text = body,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                HyperButton(text = "知道了", onClick = { vm.dismissOutcome() })
            }
        )
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
