@file:OptIn(ExperimentalTvFoundationApi::class)

package com.hypermux.tv.ui.me

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.ExperimentalTvFoundationApi
import androidx.tv.foundation.lazy.list.TvLazyColumn
import com.hypermux.tv.BuildConfig
import com.hypermux.tv.data.model.SessionUserVo
import com.hypermux.tv.data.repo.ServerMode
import com.hypermux.tv.ui.AuthViewModel
import com.hypermux.tv.ui.NetworkViewModel
import com.hypermux.tv.ui.SettingsViewModel
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperCard
import com.hypermux.tv.ui.common.HyperChip
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.HyperIconButton
import com.hypermux.tv.ui.common.HyperSwitch
import com.hypermux.tv.ui.common.OfflineBanner
import com.hypermux.tv.ui.common.formatBytes
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.TvMessageOverlay
import com.hypermux.tv.ui.tv.TvTopBar
import com.hypermux.tv.ui.tv.focusRequesterIf
import com.hypermux.tv.ui.tv.tvClickable
import com.hypermux.tv.ui.update.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的页（TV 版，48-tv-app-detailed.md §8.6）。
 *
 * 手机端 → TV 的映射：
 * - 底部导航栏 → **左侧导航栏**（AppNavigation 承载），本页只剩顶栏；
 * - 顶栏 `TopAppBar` → [TvTopBar]（无返回键：它是导航栏的一级目的地）；
 * - 「外观 / 主题切换」整组**删除**：TV 端锁定深色（§7.2，远距离观影浅色主题眩光且与内容平台惯例不符），
 *   改为一行只读的「主题：深色（TV 固定）」说明，避免用户找不到"为什么没有主题切换"；
 * - 「长按倍速」→ 文案改为「MENU 键倍速」：遥控器没有长按，§6.4 已把长按语义改挂 MENU 键；
 * - 所有信息行放大到 64dp 行高、18sp 标签，并挂 `tvClickable`（焦点放大 + 描边），非可点行不可聚焦；
 * - 触屏「整行可点」的开关行保留（OK 即切换），右侧 [HyperSwitch] 仍只做指示器。
 */
@Composable
fun MeScreen(navController: NavController) {
    val authVm: AuthViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val networkVm: NetworkViewModel = hiltViewModel()
    val session by authVm.session.collectAsStateWithLifecycle()
    val expiryAt by authVm.sessionExpiryAt.collectAsStateWithLifecycle()
    val serverUrl by settingsVm.serverUrl.collectAsStateWithLifecycle()
    val serverMode by settingsVm.serverMode.collectAsStateWithLifecycle()
    val isOffline by networkVm.isOffline.collectAsStateWithLifecycle()
    val longPressSpeed by settingsVm.longPressSpeed.collectAsStateWithLifecycle()
    val rememberSpeed by settingsVm.rememberSpeed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 更新状态必须与 AppNavigation 层的弹窗读同一实例：导航目的地里的默认 hiltViewModel()
    // 会解析到 NavBackStackEntry 作用域（每屏一份），故这里显式取 Activity 作用域
    val updateVm: UpdateViewModel = hiltViewModel(context as ComponentActivity)
    val updateChecking by updateVm.checking.collectAsStateWithLifecycle()
    val updateRowText by updateVm.rowText.collectAsStateWithLifecycle()
    val updateManual by updateVm.manualResult.collectAsStateWithLifecycle(initialValue = null)

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var coverCacheBytes by remember { mutableStateOf(settingsVm.coverCacheBytes()) }
    // 播放偏好：MENU 键倍速选择弹窗
    var showLongPressSpeedDialog by remember { mutableStateOf(false) }
    // 检查更新结果：走 TV 提示层（系统 Toast 在电视上小字落在左下角，读不清）
    var message by remember { mutableStateOf<String?>(null) }

    val firstRowRequester = remember { FocusRequester() }

    // 手动检查结果（启动被动检查不打扰，不走到这里）
    LaunchedEffect(updateManual) {
        updateManual?.let {
            message = if (it.ok) it.msg else "检查更新失败：${it.msg}"
            updateVm.consumeManualResult()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TvTopBar(title = "我的")

        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page,
                end = Spacing.page,
                top = Spacing.sm,
                bottom = Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (isOffline) {
                item(key = "offline") {
                    Box(modifier = Modifier.padding(bottom = Spacing.xs)) {
                        OfflineBanner(visible = true)
                    }
                }
            }

            session?.let { user ->
                item(key = "user") { UserCard(user) }
            }

            // 播放偏好
            item(key = "play") {
                Section(title = "播放") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "MENU 键倍速",
                            value = speedText(longPressSpeed),
                            onClick = { showLongPressSpeedDialog = true },
                            showDivider = true,
                            focusRequester = firstRowRequester
                        )
                        InfoRow(
                            label = "记忆上次倍速",
                            value = if (rememberSpeed) "开" else "关",
                            // 整行可点即切换；右侧开关只做「指示器」（不给它挂点击），避免一次点击被切两次
                            onClick = { settingsVm.setRememberSpeed(!rememberSpeed) },
                            showDivider = false,
                            trailing = { HyperSwitch(checked = rememberSpeed) }
                        )
                        HintRow("（播放中按住遥控器 MENU 键临时加速，松手恢复原速）")
                    }
                }
            }

            // 主题：TV 固定深色，只做说明（不做切换控件）
            item(key = "theme") {
                Section(title = "外观") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(label = "主题", value = "深色（TV 固定）", showDivider = false)
                        HintRow("TV 端固定深色：客厅远距离观影时浅色主题眩光明显")
                    }
                }
            }

            item(key = "server") {
                Section(title = "服务器") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "服务器地址",
                            // 公开模式不露内置地址（契约 §4.3）：显示节点名
                            value = if (serverMode == ServerMode.PUBLIC) "公开服务器"
                            else (serverUrl ?: "未配置"),
                            showDivider = false
                        )
                    }
                    HintRow("修改服务器请到登录页底部「服务器配置」")
                }
            }

            // App 自更新（§3.11）：更新来源 = 用户配置的服务器；手动检查 + 启动被动检查（24h 节流）
            item(key = "update") {
                Section(title = "应用更新") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(label = "当前版本", value = BuildConfig.VERSION_NAME, showDivider = true)
                        InfoRow(
                            label = "检查更新",
                            value = if (updateChecking) "检查中…" else updateRowText,
                            onClick = { updateVm.checkNow() },
                            showDivider = false
                        )
                    }
                }
            }

            // 存储：封面缓存（整行可点 → 打开清除缓存确认弹窗）
            item(key = "storage") {
                Section(title = "存储") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "封面缓存",
                            value = formatBytes(coverCacheBytes),
                            onClick = { showClearCacheDialog = true },
                            showDivider = false,
                            trailing = {
                                HyperIconButton(
                                    icon = Icons.Filled.DeleteOutline,
                                    contentDescription = "清除封面缓存",
                                    onClick = { showClearCacheDialog = true }
                                )
                            }
                        )
                    }
                }
            }

            item(key = "session") {
                Section(title = "登录信息") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(label = "登录有效期", value = expiryText(expiryAt), showDivider = false)
                    }
                }
            }

            item(key = "logout") {
                Column {
                    Spacer(Modifier.height(Spacing.xs))
                    // 退出登录：整宽、次级描边样式（不用刺眼的红色），点击后走既有确认弹窗
                    HyperButton(
                        text = "退出登录",
                        onClick = { showLogoutDialog = true },
                        style = HyperButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "HyperMux TV ${BuildConfig.VERSION_NAME} · 与手机端 / Web 端共用同一套内容",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.md),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // 首屏焦点：给第一行可点项（§6.1 绝不出现无焦点状态）
    TvAutoFocus(firstRowRequester, ready = session != null)

    // 检查更新结果提示（替代系统 Toast；非焦点 Popup，出现在屏幕中下方）
    message?.let { TvMessageOverlay(message = it, onDismiss = { message = null }) }

    if (showLogoutDialog) {
        HyperDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Text(
                    "退出登录",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = { Text("确定要退出当前账号吗？", fontSize = TvType.body) },
            confirmButton = {
                HyperButton(
                    text = "退出",
                    onClick = {
                        showLogoutDialog = false
                        settingsVm.logout()
                    }
                )
            },
            dismissButton = {
                HyperButton(
                    text = "取消",
                    onClick = { showLogoutDialog = false },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }

    // MENU 键倍速选择（1.5x / 2.0x / 2.5x / 3.0x；当前项打勾 + 品牌绿高亮）
    if (showLongPressSpeedDialog) {
        HyperDialog(
            onDismissRequest = { showLongPressSpeedDialog = false },
            title = {
                Text(
                    "MENU 键倍速",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LongPressSpeedOptions.forEach { v ->
                        val selected = kotlin.math.abs(v - longPressSpeed) < 0.001f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .tvClickable(shape = RoundedCornerShape(12.dp)) {
                                    settingsVm.setLongPressSpeed(v)
                                    showLongPressSpeedDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = speedText(v),
                                fontSize = TvType.body,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                HyperButton(
                    text = "关闭",
                    onClick = { showLongPressSpeedDialog = false },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }

    // 清除封面缓存确认
    if (showClearCacheDialog) {
        HyperDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = {
                Text(
                    "清除封面缓存",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "将释放 ${formatBytes(coverCacheBytes)} 空间，封面会重新下载。",
                    fontSize = TvType.body
                )
            },
            confirmButton = {
                HyperButton(
                    text = "清除",
                    onClick = {
                        settingsVm.clearCoverCache()
                        coverCacheBytes = settingsVm.coverCacheBytes()
                        showClearCacheDialog = false
                    }
                )
            },
            dismissButton = {
                HyperButton(
                    text = "取消",
                    onClick = { showClearCacheDialog = false },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }
}

/**
 * 个人资料卡（HyperCard 容器）：头像 + 昵称 + @用户名·角色，右侧「已登录」标签。
 *
 * 头像渲染 bug 根因（沿用手机端修复）：原实现只给了 `height(52.dp)`、**没有给宽度**，
 * Box 会按内容自适应宽度 → 被 CircleShape 裁成「细长绿色竖条」。修复：固定 `size()` 正方形。
 */
@Composable
private fun UserCard(user: SessionUserVo) {
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：外层作淡光晕，内层固定正方形（保证裁切后是正圆）；TV 整体放大
            Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF18C22B), Color(0xFF00BE06)))
                        )
                        .border(1.dp, Color(0x33FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (user.nickName?.takeIf { it.isNotBlank() } ?: user.username).take(1),
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.nickName ?: user.username,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "@${user.username} · 普通用户",
                    fontSize = TvType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HyperChip(text = "已登录", highlight = true)
        }
    }
}

/** 分组小标题：6dp 绿色渐变竖条 + 大号 ExtraBold（与首页分区标题风格一致） */
@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(hyperColors().primaryGradient))
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = title,
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        content()
    }
}

/**
 * 统一的「信息行」：左标签 + 右值（次要色），可整行点击，**行高 64dp**（TV 尺寸）。
 * [showDivider] 为 true 时在该行下方画 1dp 发丝线（最后一行传 false）。
 * 无 `onClick` 的行**不可聚焦**（只读信息不需要占用焦点路径，方向键可以更快略过）。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val hc = hyperColors()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .focusRequesterIfNotNull(focusRequester)
                .then(
                    if (onClick != null) {
                        Modifier.tvClickable(
                            shape = RoundedCornerShape(10.dp),
                            focusedScale = 1.02f,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = TvType.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(
                    text = value,
                    fontSize = TvType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(Spacing.sm))
                trailing()
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(hc.line)
            )
        }
    }
}

/** 分组内的说明文字（不占焦点） */
@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        fontSize = TvType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
    )
}

private fun Modifier.focusRequesterIfNotNull(requester: FocusRequester?): Modifier =
    focusRequesterIf(requester)

/** 登录有效期文案（右值，尽量精简以便单行展示） */
private fun expiryText(expiresAt: Long): String {
    if (expiresAt <= 0L) return "本地会话"
    val leftDays = ((expiresAt - System.currentTimeMillis()) / 86_400_000).toInt()
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(expiresAt))
    return if (leftDays > 1) "剩 $leftDays 天 · $date" else "即将过期 · $date"
}

/** MENU 键倍速候选档（比播放面板少一档：只用于"临时加速"，0.5x/3.0x 之外的组合意义不大） */
private val LongPressSpeedOptions = listOf(1.5f, 2.0f, 2.5f, 3.0f)

/** 倍速文案：固定 Locale.US，避免小数点在某些语言环境下变成逗号 */
private fun speedText(v: Float): String = String.format(Locale.US, "%.1fx", v)
