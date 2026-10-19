package com.hypermux.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.HyperTextField
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.CharKeypad

/* =========================================================================
 * TV 页面骨架（48-tv-app-detailed.md §5.2 / §6）
 *
 * 为什么不用 Scaffold：TV 无底部导航、无系统栏，且横向 rail 需要**突破**左右安全区
 * 才能做到"卡片左缘对齐 48dp、焦点放大时不被裁"——Scaffold 的统一 padding 反而碍事。
 * ========================================================================= */

/**
 * 安全区容器：默认左右 48dp（overscan 5%）+ 上下 32dp。
 *
 * 含横向 rail 的页面把 `horizontal` 传 0，由 rail 自己的 `contentPadding` 提供左右留白
 * （这样焦点卡放大时不会被父容器裁掉左右边缘）。
 */
@Composable
fun TvSafeArea(
    modifier: Modifier = Modifier,
    horizontal: Dp = Spacing.page,
    vertical: Dp = 32.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontal, vertical = vertical),
        content = content
    )
}

/**
 * 二级页顶栏：可聚焦返回按钮 + 标题 +（可选）右侧动作区。
 *
 * 返回按钮**默认参与焦点链**（TV 上"BACK 键"与"返回按钮"两条路都要能走）。
 * 左右留白走 [Spacing.page]（48dp ≈ 1080p 下 5% overscan）：手机端顶栏只有 12dp，
 * 放到电视上返回按钮会落进过扫描区被裁掉，标题也比页面内容更靠左、视觉对不齐。
 */
@Composable
fun TvTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .tvClickable(shape = RoundedCornerShape(50), onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(TvSize.iconSize)
                )
            }
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TvType.pageTitle,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = TvType.meta,
                    color = hyperColors().text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * 区段标题（首页 rail 用）：6dp 品牌渐变竖条 + 28sp 加粗标题 +（可选）可聚焦「更多 ›」。
 *
 * 对应手机端 `SectionTitle` 的 TV 放大版（竖条 4→6dp、字号 16.5→28sp）。
 */
@Composable
fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "更多"
) {
    val hc = hyperColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 6.dp, height = 26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(hc.primaryGradient))
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            fontSize = TvType.section,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onMore != null) {
            Row(
                modifier = Modifier
                    .tvClickable(shape = RoundedCornerShape(50), onClick = onMore)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = moreLabel,
                    fontSize = TvType.meta,
                    color = hc.text3
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = hc.text3,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 覆盖层（播放页控制条 / 弹窗遮罩上的内容）统一用这个底色，保证浅色内容可读。
 */
val TvOverlayScrim = Brush.verticalGradient(
    0f to androidx.compose.ui.graphics.Color(0xB3000000),
    0.35f to androidx.compose.ui.graphics.Color(0x73000000),
    1f to androidx.compose.ui.graphics.Color(0xCC000000)
)

/** 全屏深色底（页面根容器用，避免切换时露白） */
@Composable
fun TvPageBackground(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = content
    )
}

/**
 * TV 瞬时提示（替代系统 `Toast`）。
 *
 * 系统 Toast 在电视上落在屏幕左下角、字号按手持设备 dpi 计算，10 英尺视距下基本读不清，
 * 部分盒子上甚至不显示 —— TV 端的一次性消息统一走这条覆盖层：居中偏下、18sp、
 * 暗底 + 发丝描边，到时自动消失（[durationMs]，默认 2.6s）。
 *
 * 实现成**非焦点 Popup**：调用方可以把它放在任意位置，不必为它改页面根容器；
 * 也不抢焦点（`focusable = false`），提示出现期间遥控器照常操作底层。
 */
@Composable
fun TvMessageOverlay(
    message: String,
    onDismiss: () -> Unit,
    durationMs: Long = 2_600
) {
    LaunchedEffect(message) {
        delay(durationMs)
        onDismiss()
    }
    Popup(
        alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = false)
    ) {
        Text(
            text = message,
            fontSize = TvType.meta,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                // 距屏幕底部留出安全区（padding 在背景之外 → 只是位置偏移）
                .padding(horizontal = Spacing.page, vertical = Spacing.xxl)
                .widthIn(max = TvSize.dialogMaxWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f))
                .border(1.dp, hyperColors().line, RoundedCornerShape(14.dp))
                .padding(horizontal = Spacing.xl, vertical = Spacing.md)
        )
    }
}

/**
 * TV 文本输入弹层：自绘字符键盘 + 输入框 + 确定。
 *
 * 用于「页面内需要打字」的场景（分类页内搜索、播放页倍速/服务器等）。
 * 无触摸屏时这是**唯一**可用的文本输入方式，因此所有输入都必须走它，
 * 不允许直接把手机端的 inline 输入框搬过来（遥控器点不到、也没有软键盘）。
 *
 * @param startWithSymbols 起始停在数字符号页（数字/`.`/`:`/`-`/`/`，服务器地址用得上）
 */
@Composable
fun TvTextInputPanel(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    doneLabel: String = "确定",
    startWithSymbols: Boolean = false
) {
    HyperDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontSize = TvType.section,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HyperTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.lg))
                CharKeypad(
                    onChar = { onValueChange(value + it) },
                    onBackspace = { onValueChange(value.dropLast(1)) },
                    onDone = onConfirm,
                    doneLabel = doneLabel,
                    startWithSymbols = startWithSymbols
                )
            }
        },
        dismissButton = {
            HyperButton(text = "取消", style = HyperButtonStyle.Ghost, onClick = onDismiss)
        },
        confirmButton = {
            HyperButton(text = doneLabel, onClick = onConfirm)
        }
    )
}
