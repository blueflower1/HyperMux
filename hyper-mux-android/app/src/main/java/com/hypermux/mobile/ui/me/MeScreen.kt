package com.hypermux.mobile.ui.me

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.hypermux.mobile.BuildConfig
import com.hypermux.mobile.data.model.SessionUserVo
import com.hypermux.mobile.data.repo.ServerMode
import com.hypermux.mobile.data.repo.ThemeMode
import com.hypermux.mobile.ui.AuthViewModel
import com.hypermux.mobile.ui.NetworkViewModel
import com.hypermux.mobile.ui.SettingsViewModel
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperCard
import com.hypermux.mobile.ui.common.HyperChip
import com.hypermux.mobile.ui.common.HyperDialog
import com.hypermux.mobile.ui.common.HyperIconButton
import com.hypermux.mobile.ui.common.HyperSwitch
import com.hypermux.mobile.ui.common.OfflineBanner
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.common.formatBytes
import com.hypermux.mobile.ui.navigation.BottomNavBar
import com.hypermux.mobile.ui.navigation.navigateDownloads
import com.hypermux.mobile.ui.navigation.navigateScan
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.layout.cappedFill
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors
import com.hypermux.mobile.ui.update.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的页（§5.8）：个人资料卡 + 主题切换 + 服务器地址 + 存储（封面缓存）+ 登录信息 + 退出登录。
 * 视觉规范：分组小标题（绿色竖条 + 14sp ExtraBold）风格与首页分区标题一致；
 * 每个分组用 HyperCard 包裹，内部是「左标签 + 右值」等高的信息行（约 48dp），行间 1dp 发丝线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(navController: NavController) {
    val authVm: AuthViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val networkVm: NetworkViewModel = hiltViewModel()
    val session by authVm.session.collectAsStateWithLifecycle()
    val expiryAt by authVm.sessionExpiryAt.collectAsStateWithLifecycle()
    val themeMode by settingsVm.themeMode.collectAsStateWithLifecycle()
    val serverUrl by settingsVm.serverUrl.collectAsStateWithLifecycle()
    val serverMode by settingsVm.serverMode.collectAsStateWithLifecycle()
    val isOffline by networkVm.isOffline.collectAsStateWithLifecycle()
    val longPressSpeed by settingsVm.longPressSpeed.collectAsStateWithLifecycle()
    val rememberSpeed by settingsVm.rememberSpeed.collectAsStateWithLifecycle()
    val cacheStats by settingsVm.cacheStats.collectAsStateWithLifecycle()
    val wifiOnly by settingsVm.wifiOnlyDownload.collectAsStateWithLifecycle()
    val profile = LocalLayoutProfile.current
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
    // 播放偏好：长按倍速选择弹窗
    var showLongPressSpeedDialog by remember { mutableStateOf(false) }

    // 进页面重算一次缓存占用（DownloadManager 的索引在别处可能已变化）
    LaunchedEffect(Unit) { settingsVm.refreshCacheStats() }

    // 手动检查结果 toast（启动被动检查不打扰，不走到这里）
    // 展示后立刻消费（清空），否则该结果会随「我的」页每次进入被重放一次
    LaunchedEffect(updateManual) {
        updateManual?.let {
            Toast.makeText(
                context,
                if (it.ok) it.msg else "检查更新失败：${it.msg}",
                Toast.LENGTH_SHORT
            ).show()
            updateVm.consumeManualResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的", fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = { BottomNavBar(navController) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            // 大屏：设置页内容按 formMaxWidth 封顶并居中，不跟着窗口一路拉长
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 离线提示条自带 Spacing.page 内缩，单独放在内容区之外，避免与其叠加成双重留白
            if (isOffline) OfflineBanner(visible = true)

            Column(
                modifier = Modifier
                    // 内容左右内缩统一为 Spacing.page，与首页/分类/搜索/历史等页面边缘对齐
                    .cappedFill(profile.formMaxWidth)
                    .padding(horizontal = Spacing.page)
                    .padding(top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                session?.let { user -> UserCard(user) }

                // 扫一扫登录 TV / 网页端（与目标端登录页二维码配对；本页必然已登录，天然满足前置条件）
                Section(title = "常用") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "扫一扫",
                            value = "登录 HyperMux TV / 网页端",
                            onClick = { navController.navigateScan() },
                            showDivider = false,
                            trailing = {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }

                Section(title = "外观") {
                    ThemeSelector(
                        themeMode = themeMode,
                        onSelect = { settingsVm.setThemeMode(it) }
                    )
                }

                // 播放偏好：长按倍速（可配置）+ 记忆上次倍速
                Section(title = "播放") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "长按倍速",
                            value = speedText(longPressSpeed),
                            onClick = { showLongPressSpeedDialog = true },
                            showDivider = true
                        )
                        InfoRow(
                            label = "记忆上次倍速",
                            value = if (rememberSpeed) "开" else "关",
                            // 整行可点即切换；右侧开关只做「指示器」（不给它挂点击），避免一次点击被切两次
                            onClick = { settingsVm.setRememberSpeed(!rememberSpeed) },
                            showDivider = false,
                            trailing = { HyperSwitch(checked = rememberSpeed) }
                        )
                    }
                }

                // 显式下载（本页唯一下载入口）：占用统计 + 进入「我的下载」页 + 仅 Wi-Fi 开关
                Section(title = "缓存") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "我的下载",
                            value = "${cacheStats.movieCount} 部 · ${formatBytes(cacheStats.bytes)}",
                            onClick = { navController.navigateDownloads() },
                            showDivider = true,
                            trailing = {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        InfoRow(
                            label = "仅 Wi-Fi 下载",
                            value = if (wifiOnly) "开" else "关",
                            // 整行可点即切换；右侧开关只做指示器（避免一次点击被切两次）
                            onClick = { settingsVm.setWifiOnlyDownload(!wifiOnly) },
                            showDivider = false,
                            trailing = { HyperSwitch(checked = wifiOnly) }
                        )
                    }
                }

                Section(title = "服务器") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "服务器地址",
                            // 公开模式不露内置地址（契约 §4.3）：显示节点名
                            value = if (serverMode == ServerMode.PUBLIC) "公开服务器" else (serverUrl ?: "未配置"),
                            showDivider = false
                        )
                    }
                    Text(
                        text = "修改服务器请到登录页底部「服务器配置」",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // App 自更新（§3.11）：更新来源 = 用户配置的服务器；手动检查 + 启动被动检查（24h 节流）
                Section(title = "应用更新") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "当前版本",
                            value = BuildConfig.VERSION_NAME,
                            showDivider = true
                        )
                        InfoRow(
                            label = "检查更新",
                            value = if (updateChecking) "检查中…" else updateRowText,
                            onClick = { updateVm.checkNow() },
                            showDivider = false
                        )
                    }
                }

                // 存储：封面缓存（整行可点 → 打开清除缓存确认弹窗）
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
                                    onClick = { showClearCacheDialog = true },
                                    size = 32.dp,
                                    iconSize = 18.dp
                                )
                            }
                        )
                    }
                }

                Section(title = "登录信息") {
                    HyperCard(modifier = Modifier.fillMaxWidth()) {
                        InfoRow(
                            label = "登录有效期",
                            value = expiryText(expiryAt),
                            showDivider = false
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xs))

                // 退出登录：整宽、次级描边样式（不用刺眼的红色），点击后走既有确认弹窗
                HyperButton(
                    text = "退出登录",
                    onClick = { showLogoutDialog = true },
                    style = HyperButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "HyperMux Android ${BuildConfig.VERSION_NAME} · 与 TV 端 / Web 端共用同一套内容",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showLogoutDialog) {
        HyperDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
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

    // 长按倍速选择（1.5x / 2.0x / 2.5x / 3.0x；当前项打勾 + 品牌绿高亮）
    if (showLongPressSpeedDialog) {
        HyperDialog(
            onDismissRequest = { showLongPressSpeedDialog = false },
            title = { Text("长按倍速") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LongPressSpeedOptions.forEach { v ->
                        val selected = kotlin.math.abs(v - longPressSpeed) < 0.001f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .bounceClick(pressedScale = 0.98f) {
                                    settingsVm.setLongPressSpeed(v)
                                    showLongPressSpeedDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = speedText(v),
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
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
            title = { Text("清除封面缓存") },
            text = { Text("将释放 ${formatBytes(coverCacheBytes)} 空间，封面会重新下载。") },
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
 * 头像渲染 bug 根因：原实现只给了 `height(52.dp)`、**没有给宽度**，
 * Box 在没有宽度约束时会按内容（单个字符 ≈ 20dp）自适应宽度，
 * 于是 52dp 高、~20dp 宽的窄条被 CircleShape 裁成了「细长绿色竖条」，
 * 居中的首字母看起来也特别小。修复：用固定 `size(60.dp)` 正方形 + CircleShape，
 * 不参与父 Row 的 weight/高度压缩。
 */
@Composable
private fun UserCard(user: SessionUserVo) {
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：外层 68dp 作淡光晕，内层固定 60dp 正方形（保证裁切后是正圆）
            Box(modifier = Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(
                            // 品牌绿渐变（与按钮 primary 渐变同色系）
                            Brush.linearGradient(listOf(Color(0xFF18C22B), Color(0xFF00BE06)))
                        )
                        // 0.5~1dp 淡描边，浅色/深色主题下都能分离出边界
                        .border(1.dp, Color(0x33FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (user.nickName?.takeIf { it.isNotBlank() } ?: user.username).take(1),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.nickName ?: user.username,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "@${user.username} · 普通用户",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HyperChip(text = "已登录", highlight = true)
        }
    }
}

/** 分组小标题：4dp 绿色渐变竖条 + 14sp ExtraBold（与首页分区标题风格一致） */
@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(hyperColors().primaryGradient))
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        content()
    }
}

/**
 * 统一的「信息行」：左标签 + 右值（次要色），可整行点击，行高固定 48dp。
 * [showDivider] 为 true 时在该行下方画 1dp 发丝线（最后一行传 false）。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val hc = hyperColors()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .then(
                    if (onClick != null) {
                        Modifier.bounceClick(pressedScale = 0.98f, onClick = onClick)
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 13.sp,
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

@Composable
private fun ThemeSelector(
    themeMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    HyperCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ThemeOption("深色", ThemeMode.DARK, themeMode, onSelect)
            ThemeOption("浅色", ThemeMode.LIGHT, themeMode, onSelect)
            ThemeOption("跟随系统", ThemeMode.SYSTEM, themeMode, onSelect)
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val isSel = selected == mode
    // 淡绿胶囊底 + 品牌绿文字（选中态）
    val bg by animateColorAsState(
        targetValue = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else Color.Transparent,
        animationSpec = tween(Motion.BASE),
        label = "themeOptionBg"
    )
    val fg by animateColorAsState(
        targetValue = if (isSel) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.BASE),
        label = "themeOptionFg"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bounceClick(pressedScale = 0.94f) { onSelect(mode) }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        ThemeRadioDot(selected = isSel)
        Text(
            label,
            fontSize = 13.sp,
            color = fg,
            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** 自绘单选圆点（替代 Material RadioButton，保持深色底 + 品牌绿选中观感） */
@Composable
private fun ThemeRadioDot(selected: Boolean) {
    val hc = hyperColors()
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else hc.line,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 登录有效期文案（右值，尽量精简以便单行展示） */
private fun expiryText(expiresAt: Long): String {
    if (expiresAt <= 0L) return "本地会话"
    val leftDays = ((expiresAt - System.currentTimeMillis()) / 86_400_000).toInt()
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(expiresAt))
    return if (leftDays > 1) "剩 $leftDays 天 · $date" else "即将过期 · $date"
}

/** 长按倍速候选档（比播放面板少一档：长按只用于"临时代速"，0.5x/3.0x 之外的组合意义不大） */
private val LongPressSpeedOptions = listOf(1.5f, 2.0f, 2.5f, 3.0f)

/** 倍速文案：固定 Locale.US，避免小数点在某些语言环境下变成逗号 */
private fun speedText(v: Float): String = String.format(Locale.US, "%.1fx", v)
