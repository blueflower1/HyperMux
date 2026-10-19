package com.hypermux.tv.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.focusRequesterIf
import com.hypermux.tv.ui.tv.tvClickable

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
    val requester = remember { FocusRequester() }
    // 打开即聚焦弹窗内的首个可聚焦元素（通常是主按钮），焦点"困"在弹窗内（§6.1 / §6.2）
    TvAutoFocus(requester)
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
                    .padding(horizontal = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = TvSize.dialogMaxWidth)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, hc.line, RoundedCornerShape(22.dp))
                        .focusRequester(requester)
                        .focusGroup()
                        .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 20.dp)
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
        fontSize = TvType.meta,
        lineHeight = 24.sp,
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
    // CharKeypad 是在字符串末尾追加字符，但 TextField 的光标由它自己维护、外部改值不会跟着走，
    // 结果就是「文字追加对了、光标却停在开头」。这里用 TextFieldValue 托管：外部 value 变化时
    // 同步并把光标放到末尾；value == 内部 text（实体键盘自然编辑）时不干预光标。
    val tfValue = remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (tfValue.value.text != value) {
        tfValue.value = TextFieldValue(value, TextRange(value.length))
    }
    OutlinedTextField(
        value = tfValue.value,
        onValueChange = {
            tfValue.value = it
            onValueChange(it.text)
        },
        modifier = modifier.heightIn(min = 64.dp),
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        // 占位只能画一遍，否则重影：只走底层 OutlinedTextField 的 placeholder 槽，包装层不再叠 Text
        placeholder = if (placeholder.isNullOrBlank()) null else { { HyperPlaceholder(placeholder) } },
        // label 同样显式给 lineHeight：默认继承 bodyLarge 的 24sp，会让浮动标签在描边缺口里偏高
        label = label?.let { { Text(it, fontSize = TvType.badge, lineHeight = 20.sp, color = hc.text3) } },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    it,
                    contentDescription = null,
                    tint = hc.text3,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = textStyle.copy(fontSize = TvType.body),
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
        fontSize = TvType.body,
        lineHeight = 26.sp,
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
            .height(64.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                // 上下内边距归零：64dp 全部留给文字，避免被裁切成"重影/叠影"
                contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
                colors = colors,
                // 占位只能画一遍，否则重影：只交给底层 placeholder 槽，包装层不再叠 Text
                placeholder = if (placeholder.isBlank()) null else { { HyperPlaceholder(placeholder) } },
                // 图标走 leadingIcon 槽：M3 会把文字排在图标之后，天然不重叠、且与文字同高居中
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = hc.text3,
                        modifier = Modifier.size(26.dp)
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
                                size = 44.dp,
                                iconSize = 24.dp
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
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .tvClickable(shape = shape, focusedScale = 1.05f, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "搜索",
            color = MaterialTheme.colorScheme.primary,
            fontSize = TvType.meta,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 统一按钮外观：primary=品牌绿渐变胶囊；secondary=描边胶囊；ghost=纯文字 */
enum class HyperButtonStyle { Primary, Secondary, Ghost }

/**
 * 统一按钮（替代 Material3 Button）。用 Box 自绘，避免原生 Button 的观感。
 *
 * TV 改造（§10）：按压回弹 `bounceClick` → 焦点放大 + 描边 `tvClickable`；高度放大到 56dp（小号 48dp）。
 * [focusRequester] 供弹窗/错误态"打开即聚焦主按钮"使用（§6.1）。
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
    focusRequester: FocusRequester? = null,
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
            .focusRequesterIf(focusRequester)
            .then(if (enabled) Modifier.tvClickable(shape = shape, onClick = onClick) else Modifier)
            .heightIn(min = if (small) 48.dp else TvSize.buttonHeight)
            .padding(
                horizontal = if (small) 20.dp else 28.dp,
                vertical = if (small) 8.dp else 12.dp
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(if (small) 20.dp else 24.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = contentColor,
                fontSize = if (small) TvType.meta else TvType.body,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 统一卡片容器：深色底 + 描边 + 大圆角，替代 Surface+MaterialTheme.shapes 的随手写法 */
@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val hc = hyperColors()
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, hc.line, shape)
            .then(if (onClick != null) Modifier.tvClickable(shape = shape, onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content
    )
}

/** 统一轻量标签（胶囊） */
@Composable
fun HyperChip(
    text: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    /** 上下内边距（默认 6dp）。与单行标题并排、不允许把该行撑高时传 0dp */
    verticalPadding: Dp = 6.dp,
    /** 非空即可聚焦点击；TV 上 chip 多作为"类型/季/历史词"等可选项（§10） */
    onClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val hc = hyperColors()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .focusRequesterIf(focusRequester)
            .then(
                if (onClick != null) {
                    Modifier.tvClickable(shape = shape, focusedScale = 1.08f, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = verticalPadding)
    ) {
        Text(
            text = text,
            fontSize = TvType.meta,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 统一图标按钮（替代 Material3 IconButton，**不用** Material 的 IconButton）。
 * 主题层已全局关闭 M3 水波纹，这里用 Box 自绘：TV 上为焦点放大 + 描边（[tvClickable]）。
 * [highlighted] 为 true 时叠一层品牌色淡底，用于强调当前激活的图标。
 */
@Composable
fun HyperIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = TvSize.iconButton,
    iconSize: Dp = TvSize.iconSize,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .focusRequesterIf(focusRequester)
            .then(
                if (enabled) Modifier.tvClickable(shape = CircleShape, focusedScale = 1.12f, onClick = onClick)
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
 * TV 侧边面板（**替代手机端的底部弹层** `HyperBottomSheet`，见 48 计划 §10）。
 *
 * 为什么改侧边：TV 的焦点在屏幕中部/右侧，底部弹层离焦点远；且底部弹层会被 48dp 安全区裁切。
 * 选集（详情页缓存）与播放页选集抽屉在手机端横屏时本来就是右侧滑入，TV 直接沿用这套形态：
 * 640dp 宽、全高、右侧贴边，左侧半透明遮罩；打开即聚焦面板内首个可聚焦元素（§6.1 弹窗焦点）。
 */
@Composable
fun TvSidePanel(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 640.dp,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val hc = hyperColors()
    var shown by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { shown = true }
    TvAutoFocus(requester)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissible,
            dismissOnBackPress = dismissible,
            decorFitsSystemWindows = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(Motion.FAST)),
                exit = fadeOut(tween(Motion.FAST))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { if (dismissible) onDismissRequest() }
                )
            }
            AnimatedVisibility(
                visible = shown,
                enter = slideInHorizontally(tween(Motion.BASE, easing = Motion.Emphasized)) { it } +
                    fadeIn(tween(Motion.FAST)),
                exit = slideOutHorizontally(tween(Motion.FAST)) { it } + fadeOut(tween(Motion.FAST)),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                // Dialog 内容不在 MaterialTheme 的 Surface 里：必须显式提供 onSurface，
                // 否则深色主题下裸 Text 会是黑字压深底（与 HyperDialog 同一坑）。
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    Column(
                        modifier = modifier
                            .width(width)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp, hc.line,
                                RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                            )
                            .focusRequester(requester)
                            .focusGroup()
                            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
                        content = content
                    )
                }
            }
        }
    }
}

/**
 * 自绘开关（替代 Material3 Switch，沿用主题选择器的圆点风格）。
 *
 * 只做**指示器**（不挂点击）：调用方整行点击，避免一次点击被行与开关各切一次。
 * TV 尺寸放大（56×30dp）：10 英尺视距下 44×24dp 太细，看不清状态。
 */
@Composable
fun HyperSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val hc = hyperColors()
    val trackWidth = 56.dp
    val trackHeight = 30.dp
    val knob = 24.dp
    val knobOffset by animateDpAsState(
        targetValue = if (checked) (trackWidth - knob - 3.dp) else 3.dp,
        animationSpec = tween(Motion.BASE),
        label = "hyperSwitchKnob"
    )
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
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
                .offset(x = knobOffset, y = 3.dp)
                .size(knob)
                .clip(CircleShape)
                .background(
                    if (checked) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
        )
    }
}
