package com.hypermux.tv.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.TvSize

/* =========================================================================
 * TV 焦点基建（48-tv-app-detailed.md §5.3 / §6.1）
 *
 * 手机端用「按压回弹 bounceClick」；遥控器没有"按住"的连续态，
 * 因此 TV 端统一改为「焦点放大 + 品牌绿描边」：
 *   非焦点 → 1.0 倍、无描边
 *   焦点   → 1.06 倍（180ms）、3dp 品牌绿描边
 * ========================================================================= */

/**
 * TV 可点击项：焦点放大 + 品牌绿描边 + 焦点即响应 OK/Enter。
 *
 * - 描边画在 `clip` **之外**（半宽外溢 1.5dp），避免描边被自身圆角裁掉一半；
 * - 描边为渐入渐出（0f→1f）而不是改宽度：宽度突变会在低端盒子上出现一帧"粗框"闪烁；
 * - 点击语义仍走 [clickable]：它本身就注册为可聚焦节点，并处理 `DPAD_CENTER` / `Enter`。
 *
 * 注意：卡片外需要给焦点放大预留余量（rail 的 `contentPadding` 要含 ~6% 卡宽），
 * 否则焦点卡会被容器裁掉边缘（见 §5.2）。
 */
fun Modifier.tvClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = TvSize.focusScale,
    borderWidth: Dp = TvSize.focusBorder,
    /** 描边色；不传则为品牌绿（`MaterialTheme.colorScheme.primary`） */
    borderColor: Color = Color.Unspecified,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused && enabled

    val scale by animateFloatAsState(
        targetValue = if (active) focusedScale else 1f,
        animationSpec = tween(Motion.FOCUS, easing = Motion.Ease),
        label = "tvFocusScale"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(Motion.FOCUS, easing = Motion.Ease),
        label = "tvFocusRing"
    )
    val ring = if (borderColor == Color.Unspecified) MaterialTheme.colorScheme.primary else borderColor
    val widthPx = with(LocalDensity.current) { borderWidth.toPx() }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (ringAlpha > 0f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = ring.copy(alpha = ring.alpha * ringAlpha),
                    style = Stroke(width = widthPx)
                )
            }
        }
        .clip(shape)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 仅可聚焦（不挂点击）：给需要自行处理 `onKeyEvent` 的元素用
 * （例如播放页控制条按钮、字符键盘按键在特殊键位上做二次判定）。
 */
fun Modifier.tvFocusable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = TvSize.focusScale,
    borderWidth: Dp = TvSize.focusBorder,
    borderColor: Color = Color.Unspecified,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused && enabled
    val scale by animateFloatAsState(
        targetValue = if (active) focusedScale else 1f,
        animationSpec = tween(Motion.FOCUS, easing = Motion.Ease),
        label = "tvFocusScale"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(Motion.FOCUS, easing = Motion.Ease),
        label = "tvFocusRing"
    )
    val ring = if (borderColor == Color.Unspecified) MaterialTheme.colorScheme.primary else borderColor
    val widthPx = with(LocalDensity.current) { borderWidth.toPx() }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (ringAlpha > 0f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = ring.copy(alpha = ring.alpha * ringAlpha),
                    style = Stroke(width = widthPx)
                )
            }
        }
        .clip(shape)
        .focusable(
            interactionSource = interaction,
            enabled = enabled
        )
}

/**
 * 「进入即有焦点」：数据就绪后把焦点交给目标元素（§6.1）。
 *
 * 焦点目标可能在本帧才进入组合（如列表首项），故最多重试 3 帧；
 * `requestFocus()` 在未附着时会抛 `IllegalStateException`，这里吞掉并重试，
 * **绝不**让"没有焦点"成为可观测状态。
 */
@Composable
fun TvAutoFocus(requester: FocusRequester?, ready: Boolean = true) {
    LaunchedEffect(requester, ready) {
        if (!ready || requester == null) return@LaunchedEffect
        // 焦点目标可能在本帧才进入组合（如列表首项），故最多重试 3 帧；
        // 未附着时 requestFocus 会失败（不抛异常），吞掉即可，绝不让"没有焦点"成为可观测状态。
        var granted = false
        repeat(3) {
            if (!granted) {
                withFrameNanos { }
                granted = runCatching { requester.requestFocus() }.isSuccess
            }
        }
    }
}

/** 便捷：把 `FocusRequester` 绑到任意 modifier 上（可空，便于"可选聚焦"场景） */
fun Modifier.focusRequesterIf(requester: FocusRequester?): Modifier =
    if (requester == null) this else this.focusRequester(requester)
