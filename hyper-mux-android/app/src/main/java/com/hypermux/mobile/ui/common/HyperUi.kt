package com.hypermux.mobile.ui.common

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.layout.cappedFill
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/* =========================================================================
 * 全 App 统一 UI 组件（包装层）
 * 目标：不再直接使用 Material3 的原生观感（弹窗/输入框/按钮），
 *       统一为「深色底 + 品牌绿 + 大圆角 + 胶囊」的 HyperMux 风格。
 * ========================================================================= */

/**
 * 统一对话框（**不使用 AlertDialog**，自己画容器，参数与 AlertDialog 一致，可直接替换）。
 * 布局：大圆角卡片 + 加粗标题 + 次要色正文 + 右下角操作区。
 * [dismissible] = false 时蒙层点击 / 返回键均无法关闭（用于强制更新等不可取消场景）。
 */
@Composable
fun HyperDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    dismissible: Boolean = true,
) {
    val hc = hyperColors()
    val profile = LocalLayoutProfile.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissible,
            dismissOnBackPress = dismissible
        )
    ) {
        // ★ 主题兼容关键点 ★
        // Dialog 的内容不在 MaterialTheme 的 Surface 里，LocalContentColor 会退回默认的**纯黑**，
        // 于是弹窗里任何没显式写 color 的 Text 在**深色主题**下都会变成黑字压深底（看不见）。
        // 这里统一提供 onSurface，弹窗内的裸 Text 才会跟随主题。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        // 平板/大屏：弹窗不能横贯整屏（usePlatformDefaultWidth=false 时窗口是 MATCH_PARENT，
                        // 1280dp 宽平板上原来会得到一张 ~1224dp 宽的卡片）。Compact 档 Infinity → 原行为不变。
                        // Dialog 窗口与 Activity 窗口同尺寸，所以直接沿用 Activity 侧算好的 profile。
                        .cappedFill(profile.formMaxWidth)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, hc.line, RoundedCornerShape(22.dp))
                        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp)
                ) {
                    if (title != null) {
                        Box(modifier = Modifier.padding(bottom = 10.dp)) { title() }
                    }
                    if (text != null) {
                        Box(modifier = Modifier.padding(bottom = 6.dp)) { text() }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * 统一输入框：深色填充 + 12dp 圆角 + 品牌绿聚焦描边，替代原生 OutlinedTextField 观感。
 */
@Composable
fun HyperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable (() -> Unit))? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = RoundedCornerShape(12.dp),
    textStyle: TextStyle = LocalTextStyle.current,
) {
    HyperTextFieldCore(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        label = label,
        leadingIcon = leadingIcon,
        trailing = trailing,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions.Default,
        visualTransformation = visualTransformation,
        shape = shape,
        textStyle = textStyle
    )
}

/**
 * 便利重载：在统一输入框之上补充 IME 动作回调（如密码框回车直接提交）。
 * 本重载要求显式传入 [keyboardActions]，因此不会与基础重载产生解析歧义。
 */
@Composable
fun HyperTextField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable (() -> Unit))? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = RoundedCornerShape(12.dp),
    textStyle: TextStyle = LocalTextStyle.current,
) {
    HyperTextFieldCore(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        label = label,
        leadingIcon = leadingIcon,
        trailing = trailing,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = shape,
        textStyle = textStyle
    )
}

/**
 * 占位文字的**唯一**构造点（输入框胶囊条 / 普通输入框共用）。
 *
 * 占位只能画一遍，否则重影：调用方（[HyperTextField] / [HyperSearchField]）只允许把文案交给底层
 * `placeholder` 槽，不要再在外面自己叠一层 `Text`。
 * 另：必须显式给 lineHeight —— 否则会继承 M3 `bodyLarge` 的 24sp 行高，输入框矮的时候文字会被裁切。
 */
@Composable
private fun HyperPlaceholder(text: String) {
    Text(
        text = text,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        color = hyperColors().text3
    )
}

/** 输入框共用实现（基础重载与便利重载都委托到这里） */
@Composable
private fun HyperTextFieldCore(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String?,
    label: String?,
    leadingIcon: ImageVector?,
    trailing: (@Composable (() -> Unit))?,
    singleLine: Boolean,
    minLines: Int,
    enabled: Boolean,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation,
    shape: Shape,
    textStyle: TextStyle,
) {
    val hc = hyperColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        // 占位只能画一遍，否则重影：只走底层 OutlinedTextField 的 placeholder 槽，包装层不再叠 Text
        placeholder = if (placeholder.isNullOrBlank()) null else { { HyperPlaceholder(placeholder) } },
        // label 同样显式给 lineHeight：默认继承 bodyLarge 的 24sp，会让浮动标签在描边缺口里偏高
        label = label?.let { { Text(it, fontSize = 12.5.sp, lineHeight = 16.sp, color = hc.text3) } },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    it,
                    contentDescription = null,
                    tint = hc.text3,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = textStyle.copy(fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = hc.line,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * 统一「胶囊搜索条」：整宽 + 两端 50 圆角（胶囊），左侧放大镜、右侧清空按钮，回车触发 IME 搜索。
 * 分类页 / 搜索页共用，保证两处搜索框样式完全一致。
 *
 * 为什么不用 [OutlinedTextField]：M3 输入框内部上下各有 16dp 内边距（contentPadding），
 * 一旦用 `Modifier.height(48.dp)` 把它压成 48dp，文字可用高度只剩 16dp，而占位文字的行高继承自
 * `bodyLarge` 的 24sp → **占位/输入文字会被垂直裁切**（截图里看着就像占位被画了两遍/叠影）。
 * 这里改用 BasicTextField + M3 官方 DecorationBox 并自定 `contentPadding`（上下 0），
 * 让 48dp 里全部留给文字、真正水平垂直居中；占位只交给 DecorationBox 的 placeholder 槽画一次。
 *
 * [onSearch] 由「键盘搜索键」与右侧「搜索」按钮共同触发（按钮点击还会顺手收起软键盘）；
 * [showSearchButton] 控制是否显示右侧「搜索」胶囊按钮（默认显示）。
 * [focusRequester] 非空时把焦点交给输入框（搜索页进入即自动聚焦）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    // 是否在输入框右侧显示「搜索」胶囊按钮（默认显示；个别场景可关掉退回纯输入框）
    showSearchButton: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val hc = hyperColors()
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    // 点击「搜索」时用它收起软键盘
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        // 聚焦描边 / 光标统一品牌绿
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = hc.line,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
    )
    val baseTextStyle = LocalTextStyle.current
    val textStyle = baseTextStyle.copy(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = if (baseTextStyle.color == Color.Unspecified) {
            MaterialTheme.colorScheme.onSurface
        } else {
            baseTextStyle.color
        }
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                // 上下内边距归零：48dp 全部留给文字，避免被裁切成"重影/叠影"
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 0.dp),
                colors = colors,
                // 占位只能画一遍，否则重影：只交给底层 placeholder 槽，包装层不再叠 Text
                placeholder = if (placeholder.isBlank()) null else { { HyperPlaceholder(placeholder) } },
                // 图标走 leadingIcon 槽：M3 会把文字排在图标之后，天然不重叠、且与文字同高居中
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = hc.text3,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 有内容才显示清空：空时不占位，避免把文字挤窄
                        if (value.isNotEmpty()) {
                            HyperIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = "清空",
                                onClick = { onValueChange("") },
                                size = 32.dp,
                                iconSize = 18.dp
                            )
                        }
                        // 「搜索」按钮固定在清空图标之后；空内容时只留它
                        if (showSearchButton) {
                            SearchActionButton(
                                onClick = {
                                    // 点击 = 触发搜索 + 收起软键盘
                                    keyboard?.hide()
                                    onSearch()
                                }
                            )
                        }
                    }
                },
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interaction,
                        colors = colors,
                        shape = shape
                    )
                }
            )
        }
    )
}

/**
 * 搜索框右侧的「搜索」胶囊按钮：品牌绿文字 + 14% 品牌绿标签底，32dp 高、左右 12dp 内边距，
 * 按压回弹（[bounceClick]）。点击行为（触发搜索 + 收起键盘）由调用点传入的 [onClick] 决定。
 */
@Composable
private fun SearchActionButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .bounceClick(pressedScale = 0.92f, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "搜索",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 统一按钮外观：primary=品牌绿渐变胶囊；secondary=描边胶囊；ghost=纯文字 */
enum class HyperButtonStyle { Primary, Secondary, Ghost }

/**
 * 统一按钮（替代 Material3 Button）。用 Box 自绘，避免原生 Button 的观感。
 */
@Composable
fun HyperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: HyperButtonStyle = HyperButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    small: Boolean = false,
) {
    val hc = hyperColors()
    val shape = RoundedCornerShape(50)
    val bg = when (style) {
        HyperButtonStyle.Primary -> Brush.horizontalGradient(listOf(Color(0xFF18C22B), Color(0xFF00BE06)))
        HyperButtonStyle.Secondary -> Brush.horizontalGradient(
            listOf(Color.Transparent, Color.Transparent)
        )
        HyperButtonStyle.Ghost -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val contentColor = when (style) {
        HyperButtonStyle.Primary -> Color.White
        HyperButtonStyle.Secondary -> MaterialTheme.colorScheme.onSurface
        HyperButtonStyle.Ghost -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (style == HyperButtonStyle.Secondary) Modifier.border(1.dp, hc.line, shape)
                else Modifier
            )
            .background(bg)
            .then(if (enabled) Modifier.bounceClick(pressedScale = 0.96f, onClick = onClick) else Modifier)
            .padding(
                horizontal = if (small) 14.dp else 20.dp,
                vertical = if (small) 8.dp else 12.dp
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(if (small) 15.dp else 17.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = if (small) 13.sp else 14.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 统一卡片容器：深色底 + 描边 + 大圆角，替代 Surface+MaterialTheme.shapes 的随手写法 */
@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val hc = hyperColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, hc.line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content
    )
}

/** 统一轻量标签（胶囊） */
@Composable
fun HyperChip(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    /** 上下内边距（默认 4dp）。与单行标题并排、不允许把该行撑高时传 0dp */
    verticalPadding: Dp = 4.dp
) {
    val hc = hyperColors()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .padding(horizontal = 10.dp, vertical = verticalPadding)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 统一图标按钮（替代 Material3 IconButton，**不用** Material 的 IconButton）。
 * 主题层已全局关闭 M3 水波纹，这里用 Box 自绘：按压回弹（[bounceClick]）给出点击手感。
 * [highlighted] 为 true 时叠一层品牌色淡底，用于强调当前激活的图标。
 */
@Composable
fun HyperIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 40.dp,
    iconSize: Dp = 21.dp,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .then(
                if (enabled) Modifier.bounceClick(pressedScale = 0.9f, onClick = onClick)
                else Modifier
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 统一「底部弹层」（自绘，替代系统 BottomSheetDialog）：
 * 底部大圆角卡片 + 顶部拖拽条 + 半透明遮罩，遮罩淡入、卡片自底部上滑（[Motion.BASE]）。
 * 点遮罩 / 返回键关闭；用 [Dialog] 承载才能盖住底栏与系统导航栏。
 *
 * 页面内的内容一律走**主题色**（弹层在页面之上、不在媒体之上）。
 */
@Composable
fun HyperBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val hc = hyperColors()
    val profile = LocalLayoutProfile.current
    // Dialog 出现是瞬时的，用一个开关驱动进场动画（退出动画由 Dialog 关闭接管，不再补）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 内容按 edge-to-edge 铺满窗口（不要让 decor 再把系统栏 inset 当内边距加一遍）
            decorFitsSystemWindows = false
        )
    ) {
        // ★ 真机实测根因（窗口层，用运行时实测值兜住）★
        // Android 15（targetSdk 35 强制 edge-to-edge）下，对话框窗口仍会被系统栏 "fit" 内缩：
        // 本机窗口 frame = [0,120]-[1080,2376]（顶部被状态栏顶掉 120px），
        // 但 Compose 内容仍按**满屏高 2376px** 布局、整体挂在 y=120 上，
        // 于是内容底边落到 120+2376 = 2496px，比屏幕底边低 120px（=40dp）——
        // 贴屏幕底的弹层底边跟着下移，底部「已选 N 集 / 缓存(N)」正好被裁掉这 40dp。
        // （试过 setLayout(MATCH_PARENT) + fitInsetsTypes=0，会被框架 FIT_INSETS_CONTROLLED 覆盖，改不动。）
        // 这里改为**实测**内容底边与屏幕底边的差值，用等量 bottom padding 抵消：
        // 窗口没被内缩时差值 = 0，不产生多余边距；被内缩多少就抵消多少，跨机型/跨 ROM 都成立。
        val hostView = LocalView.current
        val density = LocalDensity.current.density
        var bottomCompensation by remember { mutableStateOf(0.dp) }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomCompensation)
                .onGloballyPositioned {
                    val location = IntArray(2)
                    hostView.getLocationOnScreen(location)
                    val contentBottom = location[1] + hostView.height
                    val overflowPx = contentBottom - screenBottomPx(hostView)
                    // 上限兜底：分屏 / 桌面窗口下 currentWindowMetrics 取到的是哪个窗口的底边
                    // 存在 ROM 差异，万一算出离谱的补偿量，最多也只让弹层上移 64dp，不会被顶出屏幕。
                    val next = overflowPx.coerceIn(0, (64 * density).toInt()) / density
                    if (next != bottomCompensation.value) bottomCompensation = next.dp
                }
        ) {
            // 弹层高度上限 = 屏高 90%（用弹窗窗口的实际约束算，不猜屏幕尺寸）。
            // 必须封顶：弹层内的懒网格（LazyVerticalGrid）不会收缩到内容高、而是铺满视口，
            // 长季（40+ 集）内容超出时，要在弹层内部滚动消化，绝不能让弹层顶出屏幕、
            // 把底部按钮裁掉（真机反馈过"弹窗展示不全"，根因之一）。
            // 90% 取值：40 集季（8 行 ≈550dp）+ 弹层头尾 ≈136dp ≈ 屏高 88%，
            // 常见整季无需滚动即可看全；>45 集在网格内滚动，底栏恒定可见。
            val sheetMaxHeight = with(constraints) {
                if (hasBoundedHeight) (maxHeight / density * 0.90f).dp else 600.dp
            }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(Motion.FAST)),
                exit = fadeOut(tween(Motion.FAST))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDismissRequest() }
                )
            }
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(tween(Motion.BASE, easing = Motion.Emphasized)) { it } +
                    fadeIn(tween(Motion.FAST)),
                exit = slideOutVertically(tween(Motion.FAST)) { it } + fadeOut(tween(Motion.FAST)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                // 与 HyperDialog 同理：弹层不在 Surface 里，必须显式提供 onSurface，
                // 否则深色主题下弹层里的裸 Text 会是黑字压深底。
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    Column(
                        modifier = modifier
                            // 大屏封顶：弹层不能横贯整屏（Compact 档 Infinity → 原铺满行为不变）
                            .cappedFill(profile.sheetMaxWidth)
                            .heightIn(max = sheetMaxHeight)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, hc.line, shape)
                            .navigationBarsPadding()
                            .padding(top = Spacing.sm)
                    ) {
                        // 顶部拖拽条：给"可以下拉关闭"的心理暗示
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = Spacing.sm)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(hc.lineStrong)
                        )
                        content()
                    }
                }
            }
        }
    }
}

/**
 * 显示区底边（屏幕可见区域底边）的像素高度。
 *
 * 取**当前窗口**的 metrics（Activity 是全屏 edge-to-edge，即屏幕高度），
 * 30 以下退回 DisplayMetrics。
 */
private fun screenBottomPx(view: View): Int {
    val context = view.context
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds?.bottom
            ?.let { return it }
    }
    @Suppress("DEPRECATION")
    return context.resources.displayMetrics.heightPixels
}

/**
 * 自绘开关（替代 Material3 Switch，沿用主题选择器的圆点风格）。
 *
 * 只做**指示器**（不挂点击）：调用方整行点击，避免一次点击被行与开关各切一次。
 * 配色刻意让两态都清晰，**浅色主题下也能分辨**：
 *  - 选中：品牌绿轨道 + onPrimary（白）圆点；
 *  - 未选中：surfaceVariant 轨道 + onSurfaceVariant（灰）圆点 + 发丝线描边。
 * （旧实现未选中时圆点也是纯白，浅色主题下白点压在近白轨道上几乎看不见。）
 */
@Composable
fun HyperSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val hc = hyperColors()
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(Motion.BASE),
        label = "hyperSwitchKnob"
    )
    Box(
        modifier = modifier
            .width(44.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                1.dp,
                if (checked) Color.Transparent else hc.lineStrong,
                RoundedCornerShape(50)
            )
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
        )
    }
}
