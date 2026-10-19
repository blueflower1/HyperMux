package com.hypermux.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvType

/* =========================================================================
 * 自绘字符键盘（48-tv-app-detailed.md §8.1）
 *
 * 遥控器没有软键盘，逐字选择又极慢 —— 因此登录 / 搜索 / 服务器地址等
 * 需要输入的地方都配这套自绘键盘：
 *   - 字母页 / 数字符号页两档；
 *   - ⇧ 切换大小写（密码默认小写）；
 *   - ⌫ 删除、✓ 提交（等价 IME 的"完成/搜索"）；
 *   - 检测到硬件键盘时由调用方隐藏本键盘（见 rememberHardwareKeyboardPresent / TvKeys.isTypingKey）。
 * ========================================================================= */

private enum class KeyPage { LETTERS, SYMBOLS }

private val LETTERS_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOLS_ROWS = listOf("1234567890", "-/:;()$&@\"", ".,?!'_#%+*")

/**
 * @param onChar        输入一个字符
 * @param onBackspace   删除
 * @param onDone        提交（确定 / 搜索）
 * @param keySize       单键边长。默认 58dp（全屏场景）；登录页/弹窗这类双栏布局传 46dp 更省横向空间
 * @param keyGap        键间距
 * @param focusRequester 非空时把焦点给"首个字母键"，便于输入框 → 键盘的焦点流转
 */
@Composable
fun CharKeypad(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String = "确定",
    initiallyUppercase: Boolean = false,
    /** 起始页：true = 数字符号页（服务器地址 `192.168.31.100:8080` 只用得上数字/`.`/`:`/`-`） */
    startWithSymbols: Boolean = false,
    keySize: Dp = 58.dp,
    keyGap: Dp = 10.dp,
    focusRequester: FocusRequester? = null
) {
    var page by remember { mutableStateOf(if (startWithSymbols) KeyPage.SYMBOLS else KeyPage.LETTERS) }
    var upper by remember { mutableStateOf(initiallyUppercase) }
    val rows = if (page == KeyPage.LETTERS) LETTERS_ROWS else SYMBOLS_ROWS
    val maxColumns = rows.maxOf { it.length }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(keyGap)
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                row.forEachIndexed { colIndex, ch ->
                    val label = if (page == KeyPage.LETTERS && upper) ch.uppercaseChar() else ch
                    KeyCell(
                        label = label.toString(),
                        focused = rowIndex == 0 && colIndex == 0,
                        keySize = keySize,
                        focusRequester = if (rowIndex == 0 && colIndex == 0) focusRequester else null,
                        onClick = { onChar(label) }
                    )
                }
                // 行尾补空，保证各行首列对齐（不然短行会被居中，方向键的落点就不整齐）
                repeat(maxColumns - row.length) {
                    Box(Modifier.width(keySize).height(keySize))
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(keyGap),
            modifier = Modifier.padding(top = Spacing.xs)
        ) {
            // ⇧ 大小写（仅字母页有意义，符号页禁用观感）
            IconKey(
                label = if (upper) "ABC" else "abc",
                enabled = page == KeyPage.LETTERS,
                keySize = keySize,
                onClick = { upper = !upper },
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "切换大小写",
                        tint = tint,
                        modifier = Modifier.width(24.dp)
                    )
                }
            )
            // ⌫ 删除
            IconKey(
                label = "删除",
                keySize = keySize,
                onClick = onBackspace,
                icon = { tint ->
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "删除",
                        tint = tint,
                        modifier = Modifier.width(24.dp)
                    )
                }
            )
            // 字母 / 数字符号 切换
            TextKey(
                text = if (page == KeyPage.LETTERS) "123" else "ABC",
                width = 96.dp,
                keySize = keySize,
                onClick = {
                    page = if (page == KeyPage.LETTERS) KeyPage.SYMBOLS else KeyPage.LETTERS
                }
            )
            // ✓ 提交
            TextKey(
                text = doneLabel,
                width = 140.dp,
                keySize = keySize,
                primary = true,
                icon = true,
                onClick = onDone
            )
        }
    }
}

@Composable
private fun KeyCell(
    label: String,
    onClick: () -> Unit,
    keySize: Dp = 58.dp,
    focused: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    Box(
        modifier = Modifier
            .width(keySize)
            .height(keySize)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusRequesterIf(focusRequester)
            .tvClickable(shape = RoundedCornerShape(10.dp), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = TvType.cardTitle,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 功能键（⇧ / ⌫）：图标 + 文案 */
@Composable
private fun IconKey(
    label: String,
    onClick: () -> Unit,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    enabled: Boolean = true,
    keySize: Dp = 58.dp
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .height(keySize)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .tvClickable(
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        icon(tint)
        Text(text = label, fontSize = TvType.meta, color = tint)
    }
}

/** 文本功能键（123/ABC、确定/搜索） */
@Composable
private fun TextKey(
    text: String,
    onClick: () -> Unit,
    width: Dp,
    primary: Boolean = false,
    icon: Boolean = false,
    keySize: Dp = 58.dp
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .width(width)
            .height(keySize)
            .clip(shape)
            .background(
                if (primary) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .tvClickable(shape = shape, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(22.dp)
            )
        }
        Text(
            text = text,
            fontSize = TvType.body,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = if (icon) Spacing.sm else 0.dp)
        )
    }
}
