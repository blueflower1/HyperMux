package com.hypermux.mobile.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.repo.ServerMode
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperChip
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.HyperTextField
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/**
 * 服务器配置弹窗（契约 §4.3）：公开 / 自定义二选一（单选）。
 * - 公开服务器：内置公共节点，UI 全程不出现地址；仅在构建期注入了预置地址时展示该行；
 * - 自定义服务器：选中后滑出地址输入 + 测试连接，草稿保存后才生效。
 * 单选行自绘（主题层已关 M3 水波纹，按压走 [bounceClick]）。
 */
@Composable
fun ServerConfigDialog(vm: LoginViewModel) {
    val hc = hyperColors()
    HyperDialog(
        onDismissRequest = vm::closeDialog,
        title = {
            Column {
                Text("服务器配置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("选择要连接的服务节点", fontSize = 12.5.sp, color = hc.text3)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (AppConfig.HAS_PRESET_SERVER) {
                    ServerOptionRow(
                        selected = vm.dialogMode == ServerMode.PUBLIC,
                        icon = Icons.Filled.Cloud,
                        title = "公开服务器",
                        subtitle = "无需配置，使用公共节点",
                        // 胶囊传 0dp 上下内边距：默认 4dp 会让胶囊（≈34dp）比 14sp 标题行（≈26dp）高，
                        // 把「公开服务器」行撑得比下面「自定义服务器」行高约 8dp（两行高度不一致）。
                        trailing = {
                            HyperChip(text = "默认", highlight = true, verticalPadding = 0.dp)
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
                            placeholder = "http://192.168.31.100:8080",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.xs)
                        )
                        Spacer(Modifier.height(Spacing.sm))
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
                                    fontSize = 12.5.sp,
                                    color = if (ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
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
 * 选中 = 主色描边 + 主色 8% 淡底 + 实心 radio。
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
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else hc.line, shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .bounceClick(pressedScale = 0.98f, onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // radio 圆点（自绘，与 HyperSwitch 同源风格）
        Box(
            modifier = Modifier
                .size(18.dp)
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
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else hc.text3,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = hc.text3,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
