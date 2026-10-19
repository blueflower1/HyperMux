package com.hypermux.tv.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.repo.ServerMode
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.HyperTextField
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.CharKeypad
import com.hypermux.tv.ui.tv.tvClickable

/**
 * 服务器配置弹窗（TV 版，48-tv-app-detailed.md §8.1）。
 *
 * 与手机端逻辑完全一致（公开 / 自定义二选一 + 测试连接 + 草稿保存），差异：
 * 1. 单选行从 `bounceClick` 改为 `tvClickable`（焦点放大 + 描边），行高放大到 88dp；
 * 2. 自定义地址手输改为**自绘键盘**（起始停在数字符号页：地址只用得上数字与 `.` `:` `-` `/`）；
 *    地址可省 `http://`，由 `ServerUrlInterceptor` 补全（自托管常见输入 `192.168.31.100:8080`）；
 * 3. 弹窗宽度上限 720dp（`HyperDialog` 内置），遥控器视距下更易读。
 */
@Composable
fun ServerConfigDialog(vm: LoginViewModel) {
    val hc = hyperColors()
    HyperDialog(
        onDismissRequest = vm::closeDialog,
        title = {
            Column {
                Text(
                    "服务器配置",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text("选择要连接的服务节点", fontSize = TvType.meta, color = hc.text3)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (AppConfig.HAS_PRESET_SERVER) {
                    ServerOptionRow(
                        selected = vm.dialogMode == ServerMode.PUBLIC,
                        icon = Icons.Filled.Cloud,
                        title = "公开服务器",
                        subtitle = "无需配置，使用公共节点",
                        trailing = {
                            HyperChip(text = "默认", highlight = true, verticalPadding = 2.dp)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.onDialogModeChange(ServerMode.PUBLIC) }
                    )
                }
                ServerOptionRow(
                    selected = vm.dialogMode == ServerMode.CUSTOM,
                    icon = Icons.Filled.Dns,
                    title = "自定义服务器",
                    subtitle = "连接自托管服务",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.onDialogModeChange(ServerMode.CUSTOM) }
                )

                AnimatedVisibility(
                    visible = vm.dialogMode == ServerMode.CUSTOM,
                    enter = fadeIn(tween(Motion.FAST)) +
                        slideInVertically(tween(Motion.FAST)) { it / 24 }
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        HyperTextField(
                            value = vm.dialogCustomUrl,
                            onValueChange = vm::onDialogCustomUrlChange,
                            label = "服务器地址",
                            placeholder = "192.168.31.100:8080",
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.xs)
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HyperButton(
                                text = "测试连接",
                                onClick = vm::testDialogServer,
                                style = HyperButtonStyle.Secondary,
                                small = true,
                                loading = vm.dialogTesting,
                                enabled = vm.dialogCustomUrl.isNotBlank()
                            )
                            vm.dialogStatus?.let { status ->
                                val ok = status.contains("✓")
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    text = status,
                                    fontSize = TvType.meta,
                                    color = if (ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                        // 地址输入用自绘键盘：起始数字符号页；确认键直接触发"测试连接"，少走一步
                        CharKeypad(
                            onChar = { c -> vm.onDialogCustomUrlChange(vm.dialogCustomUrl + c) },
                            onBackspace = { vm.onDialogCustomUrlChange(vm.dialogCustomUrl.dropLast(1)) },
                            onDone = { if (vm.dialogCustomUrl.isNotBlank()) vm.testDialogServer() },
                            doneLabel = "测试",
                            startWithSymbols = true,
                            keySize = 46.dp,
                            keyGap = 8.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        dismissButton = {
            HyperButton(text = "取消", style = HyperButtonStyle.Ghost, onClick = vm::closeDialog)
        },
        confirmButton = {
            // 自定义 + 空地址：保存不可点（地址空是最常见的配错来源）
            val canSave = vm.dialogMode == ServerMode.PUBLIC || vm.dialogCustomUrl.isNotBlank()
            HyperButton(text = "保存", onClick = vm::saveDialogServer, enabled = canSave)
        }
    )
}

/**
 * 弹窗内单选行：radio 圆点 + 图标 + 标题/副题 + 尾随槽（仅标题行右对齐）。
 * 选中 = 主色描边 + 主色 8% 淡底 + 实心 radio；TV 上整行可聚焦（焦点描边由 tvClickable 画）。
 */
@Composable
private fun ServerOptionRow(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val hc = hyperColors()
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else hc.line, shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .tvClickable(shape = shape, focusedScale = 1.02f, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // radio 圆点（自绘，与 HyperSwitch 同源风格）
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary else hc.lineStrong,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else hc.text3,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = TvType.body,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            Text(
                text = subtitle,
                fontSize = TvType.meta,
                color = hc.text3,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
