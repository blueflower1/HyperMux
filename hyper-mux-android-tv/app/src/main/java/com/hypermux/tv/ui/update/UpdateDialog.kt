package com.hypermux.tv.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.tv.data.model.UpdateInfo
import com.hypermux.tv.data.update.UpdateDownloadState
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.formatBytes

/**
 * 更新弹窗（契约 §3.11）：
 * - 普通更新：「立即更新」+「稍后」（点蒙层 / 返回键关闭，效果等同「稍后」）；
 * - 强制更新（[UpdateInfo.mandatory]）：只有「立即更新」，蒙层 / 返回键 / 其他途径一律关不掉，
 *   未更新前 App 不可用（弹窗盖在 App 最上层，见 AppNavigation）。
 * 确认按钮随下载状态切换：「立即更新」→（下载中…禁用）→「立即安装」；
 * 下载完成后点「稍后」关弹窗，后台下载继续跑（系统通知可见），下次开弹窗直接可装。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    down: UpdateDownloadState.Down,
    onDismiss: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
    onInstall: (UpdateInfo) -> Unit
) {
    val working = down is UpdateDownloadState.Down.Loading ||
        down is UpdateDownloadState.Down.Verifying
    val readyForThis = down is UpdateDownloadState.Down.Ready && down.versionCode == info.versionCode

    // 强制更新不给「稍后」、也不能点蒙层 / 返回键关闭（先落局部变量，避免参数位 if 表达式的类型推断坑）
    val dismissRequested: () -> Unit = if (info.mandatory) {
        {}
    } else {
        onDismiss
    }
    var laterButton: (@Composable () -> Unit)? = null
    if (!info.mandatory) {
        laterButton = {
            HyperButton(
                text = "稍后",
                style = HyperButtonStyle.Ghost,
                onClick = onDismiss
            )
        }
    }

    val confirmText = when {
        working -> "下载中…"
        readyForThis -> "立即安装"
        else -> "立即更新"
    }

    HyperDialog(
        onDismissRequest = dismissRequested,
        dismissible = !info.mandatory,
        title = {
            Text(text = "版本更新", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "v${info.versionName} · ${formatBytes(info.sizeBytes)}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val notes = info.releaseNotes?.trim().orEmpty()
                if (notes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = notes,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (working) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val progress: Float? = (down as? UpdateDownloadState.Down.Loading)?.let {
                        if (it.total > 0) (it.received.toDouble() / it.total).toFloat() else null
                    }
                    LinearProgressIndicator(
                        progress = { progress ?: 0.3f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val label = when (down) {
                        is UpdateDownloadState.Down.Loading ->
                            if (progress != null) {
                                "正在下载 ${(progress * 100).toInt()}% · ${formatBytes(down.received)} / ${formatBytes(down.total)}"
                            } else {
                                "正在下载…"
                            }

                        is UpdateDownloadState.Down.Verifying -> "正在校验文件完整性…"
                        else -> ""
                    }
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            HyperButton(
                text = confirmText,
                enabled = !working,
                onClick = {
                    if (readyForThis) onInstall(info) else onUpdate(info)
                }
            )
        },
        dismissButton = laterButton
    )
}
