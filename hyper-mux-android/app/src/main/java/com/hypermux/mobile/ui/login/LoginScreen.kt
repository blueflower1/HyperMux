package com.hypermux.mobile.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hypermux.mobile.data.repo.ServerMode
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperTextField
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.layout.cappedFill
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/**
 * 登录页（契约 §3.1 / §4.3）。
 * 主区只保留 用户名 / 密码 / 登录 三步；服务器连接降为底部一行（公开服务器默认，
 * 公开地址不出现于 UI，自定义经"服务器配置"弹窗选择）。
 */
@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    val hc = hyperColors()
    val profile = LocalLayoutProfile.current
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val cardScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.94f,
        animationSpec = tween(Motion.SLOW, easing = Motion.Emphasized),
        label = "loginCardScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // 渐变从"透明"起笔：页面顶边与 NavHost 铺的状态栏底色（页面底色）严丝合缝，
                // 顶部主色淡染从约 7% 处起晕开（原来是从页面顶边直接 12% 起，会在
                // 页面顶边与状态栏之间留下一道灰白色带）。
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.07f to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    0.38f to MaterialTheme.colorScheme.background,
                    1f to MaterialTheme.colorScheme.background
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, hc.line),
                shadowElevation = 6.dp,
                modifier = Modifier
                    // 大屏：登录卡不跟着窗口一起拉宽（Compact 档 Infinity → 原行为不变）
                    .cappedFill(profile.formMaxWidth)
                    .scale(cardScale)
                    .alpha(if (appeared) 1f else 0f)
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = Spacing.xl,
                        end = Spacing.xl,
                        top = Spacing.xxl,
                        bottom = Spacing.lg
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo（web .login-logo：54dp 渐变圆角 + 播放三角）
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Brush.linearGradient(hc.primaryGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = "HyperMux",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "影视聚合播放",
                        fontSize = 13.sp,
                        color = hc.text3
                    )
                    Spacer(Modifier.height(Spacing.xl))

                    AnimatedVisibility(
                        visible = appeared,
                        enter = fadeIn(tween(Motion.SLOW)) +
                            slideInVertically(tween(Motion.SLOW, easing = Motion.Emphasized)) { it / 12 }
                    ) {
                        Column {
                            HyperTextField(
                                value = vm.username,
                                onValueChange = vm::onUsernameChange,
                                label = "用户名",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(Spacing.md))

                            HyperTextField(
                                value = vm.password,
                                onValueChange = vm::onPasswordChange,
                                label = "密码",
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.login() }),
                                modifier = Modifier.fillMaxWidth()
                            )

                            AnimatedVisibility(visible = vm.errorMsg != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Spacing.md)
                                ) {
                                    Text(
                                        text = vm.errorMsg.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.5.sp,
                                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = 9.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(Spacing.lg))

                            HyperButton(
                                text = "登 录",
                                onClick = vm::login,
                                enabled = !vm.isLoading,
                                loading = vm.isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            )

                            // ---- 服务器连接（脚注级：当前节点一行 + 小字入口进配置弹窗） ----
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.lg)
                                    .height(1.dp)
                                    .background(hc.line)
                            )
                            ServerFootRow(vm)
                        }
                    }
                }
            }
        }
    }

    if (vm.dialogOpen) {
        ServerConfigDialog(vm)
    }
}

/**
 * 底部服务器行：状态圆点 + 节点名 + 右侧小字入口。
 * 整行点击 = 打开配置弹窗（预选当前模式）；右侧小字 = 预选"自定义服务器"进入。
 * 公开模式不出现地址；自定义模式显示用户自己的 host:port（去 scheme）；
 * 未注入公开地址且用户还没填过 → 显示"当前未指定服务器"占位提示（不留空白）。
 * 左右两段文案同字号、同高度盒（都撑满 44dp 行高并垂直居中），杜绝左右高度不一致。
 */
@Composable
private fun ServerFootRow(vm: LoginViewModel) {
    val hc = hyperColors()
    val isPublic = vm.serverMode == ServerMode.PUBLIC
    val host = vm.customUrl.toHostDisplay()
    val probe = vm.probeState()
    val dotColor = when (probe) {
        null -> hc.text3
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .bounceClick(pressedScale = 0.98f) { vm.openServerDialog(vm.serverMode) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(Spacing.sm))
        // Text 带 heightIn 时内容会贴顶绘制（Text 不会自己垂直居中），
        // 所以两段文案都包进 Box 用 contentAlignment 真正居中，圆点与文字墨迹中线一致。
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = when {
                    isPublic -> "公开服务器"
                    host.isNotBlank() -> host
                    else -> "当前未指定服务器"
                },
                fontSize = 12.5.sp,
                color = if (!isPublic && host.isBlank()) hc.text3 else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = Spacing.sm)
                .bounceClick(pressedScale = 0.95f) { vm.openServerDialog(ServerMode.CUSTOM) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPublic) "使用自定义服务器 ›" else "更改服务器 ›",
                fontSize = 12.5.sp,
                color = hc.text3
            )
        }
    }
}

/** 自定义地址展示：去空白 / scheme / 尾斜杠（用户自己的地址，可以展示） */
private fun String.toHostDisplay(): String =
    trim().removeSuffix("/").removePrefix("http://").removePrefix("https://")
