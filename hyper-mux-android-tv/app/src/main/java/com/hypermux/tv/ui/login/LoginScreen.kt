package com.hypermux.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hypermux.tv.data.repo.ServerMode
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperTextField
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.CharKeypad
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvKeys.isTypingKey
import com.hypermux.tv.ui.tv.rememberHardwareKeyboardPresent
import com.hypermux.tv.ui.tv.tvClickable

/** 当前键盘输入落到哪个框（遥控器自绘键盘一次只能给一个输入框打字） */
private enum class LoginField { USERNAME, PASSWORD }

/**
 * 登录页（TV 版，48-tv-app-detailed.md §8.1）。
 *
 * 与手机端的差异：
 * 1. 左侧表单 + 右侧**自绘字符键盘**（双栏）：遥控器没有软键盘，逐字输入必须由自绘键盘承担；
 * 2. 检测到硬件键盘（蓝牙/USB）时自动隐藏自绘键盘，退回原生输入；
 * 3. 全部控件走焦点态（tvClickable / 输入框聚焦描边），进入页面即聚焦用户名框。
 *
 * 校验规则与手机端完全一致（铁律，见 §13.4）：用户名 3~32 位字母数字下划线；
 * **密码仅校验长度 6~20，不校验字符集**。
 */
@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    val hc = hyperColors()
    var activeField by remember { mutableStateOf(LoginField.USERNAME) }
    // 硬件键盘一旦被使用过，本次会话就隐藏自绘键盘（避免"两个输入源抢焦点"）
    var hardwareTyping by remember { mutableStateOf(false) }
    val keyboardPresent = rememberHardwareKeyboardPresent()
    val showKeypad = !keyboardPresent && !hardwareTyping

    val usernameRequester = remember { FocusRequester() }
    // 进入即聚焦：登录页是启动目的地，不允许出现"无焦点"
    TvAutoFocus(usernameRequester)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.10f to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    0.45f to MaterialTheme.colorScheme.background,
                    1f to MaterialTheme.colorScheme.background
                )
            )
            .onPreviewKeyEvent { e ->
                if (e.isTypingKey()) hardwareTyping = true
                false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.page, vertical = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---------- 左：品牌 + 表单 ----------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(hc.primaryGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(Modifier.width(Spacing.lg))
                    Column {
                        Text(
                            text = "HyperMux",
                            fontSize = TvType.hero,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "影视聚合播放 · TV 版",
                            fontSize = TvType.meta,
                            color = hc.text3
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xxl))

                HyperTextField(
                    value = vm.username,
                    onValueChange = vm::onUsernameChange,
                    label = "用户名",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(usernameRequester)
                        .onFocusChanged { if (it.isFocused) activeField = LoginField.USERNAME }
                )
                Spacer(Modifier.height(Spacing.md))
                HyperTextField(
                    value = vm.password,
                    onValueChange = vm::onPasswordChange,
                    label = "密码",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) activeField = LoginField.PASSWORD }
                )

                if (vm.errorMsg != null) {
                    Spacer(Modifier.height(Spacing.md))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = Spacing.md, vertical = 10.dp)
                    ) {
                        Text(
                            text = vm.errorMsg.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = TvType.meta
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))
                HyperButton(
                    text = "登 录",
                    onClick = vm::login,
                    enabled = !vm.isLoading,
                    loading = vm.isLoading,
                    modifier = Modifier.width(280.dp)
                )

                Spacer(Modifier.height(Spacing.xl))
                ServerFootRow(vm)
            }

            // ---------- 右：自绘字符键盘 ----------
            if (showKeypad) {
                Spacer(Modifier.width(Spacing.xl))
                CharKeypad(
                    onChar = { c ->
                        when (activeField) {
                            LoginField.USERNAME -> vm.onUsernameChange(vm.username + c)
                            LoginField.PASSWORD -> vm.onPasswordChange(vm.password + c)
                        }
                    },
                    onBackspace = {
                        when (activeField) {
                            LoginField.USERNAME -> vm.onUsernameChange(vm.username.dropLast(1))
                            LoginField.PASSWORD -> vm.onPasswordChange(vm.password.dropLast(1))
                        }
                    },
                    // ✓ = 密码框里直接提交；用户名框里只做"先填着"，登录仍由登录按钮执行
                    onDone = { if (activeField == LoginField.PASSWORD) vm.login() },
                    doneLabel = "确定",
                    keySize = 46.dp,
                    keyGap = 8.dp
                )
            }
        }
    }

    if (vm.dialogOpen) {
        ServerConfigDialog(vm)
    }
}

/**
 * 服务器行（可聚焦）：状态圆点 + 节点名 + 「使用自定义服务器 ›」入口。
 * 公开模式不出现地址（契约 §4.3）；自定义模式显示用户自己的 host:port。
 */
@Composable
private fun ServerFootRow(vm: LoginViewModel) {
    val hc = hyperColors()
    val isPublic = vm.serverMode == ServerMode.PUBLIC
    val dotColor = when (vm.probeState()) {
        null -> hc.text3
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(14.dp))
                .tvClickable(shape = RoundedCornerShape(14.dp), onClick = {
                    vm.openServerDialog(vm.serverMode)
                })
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = if (isPublic) "公开服务器" else vm.customUrl.toHostDisplay(),
                fontSize = TvType.meta,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = if (isPublic) "使用自定义服务器 ›" else "更改服务器 ›",
            fontSize = TvType.meta,
            color = hc.text3,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .tvClickable(
                    shape = RoundedCornerShape(14.dp),
                    focusedScale = 1.04f,
                    onClick = { vm.openServerDialog(ServerMode.CUSTOM) }
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/** 自定义地址展示：去空白 / scheme / 尾斜杠（用户自己的地址，可以展示） */
private fun String.toHostDisplay(): String =
    trim().removeSuffix("/").removePrefix("http://").removePrefix("https://")
