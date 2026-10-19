package com.hypermux.tv.ui.tv

import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 遥控器键位语义（48-tv-app-detailed.md §6.3 / §9.4）。
 *
 * 全 App 的键位判定**只从这里取**，避免各页面各写一套
 * （例如"OK 键"在不同遥控器上会伪装成 `DPAD_CENTER` / `ENTER` / `NUM_PAD_ENTER` / `SPACE`）。
 */
object TvKeys {

    /** 执行键（等价手机 tap）：中心键 / 回车 / 小键盘回车 / 空格 */
    fun KeyEvent.isSelect(): Boolean = when (key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> true
        else -> false
    }

    /** 语音/更多键（长按语义的 TV 替代方案，见 §6.4） */
    fun KeyEvent.isMenu(): Boolean = key == Key.Menu

    fun KeyEvent.isBack(): Boolean = key == Key.Back

    fun KeyEvent.isDpad(): Boolean = when (key) {
        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
        else -> false
    }

    /** 媒体键（USB/蓝牙遥控器与部分电视遥控器会发这些键） */
    fun KeyEvent.isMediaKey(): Boolean = when (key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext,
        Key.MediaPrevious, Key.MediaRewind, Key.MediaFastForward, Key.MediaStop -> true
        else -> false
    }

    /** 音量键（可选：TV 端仅在有需要时接管，见 §9.5） */
    fun KeyEvent.isVolumeKey(): Boolean = key == Key.VolumeUp || key == Key.VolumeDown

    /**
     * 是否"正在用硬件键盘打字"（A~Z / 0~9）。
     * 用于登录页 / 搜索页：检测到真键盘输入即隐藏自绘字符键盘（§8.1）。
     */
    fun KeyEvent.isTypingKey(): Boolean {
        val code = key.nativeKeyCode
        return (code in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z) ||
            (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9)
    }
}

/**
 * 设备是否带有可用硬件键盘（蓝牙/USB 键盘，或电视厂商附带的实体键盘遥控器）。
 *
 * 用途与 [TvKeys.isTypingKey] 互补：这里判断"配置上有没有键盘"，
 * 那里判断"用户是不是真的在用键盘打字"——两者任一为真就隐藏自绘键盘。
 */
@Composable
fun rememberHardwareKeyboardPresent(): Boolean {
    val config = LocalConfiguration.current
    return remember(
        config.keyboard,
        config.keyboardHidden,
        config.hardKeyboardHidden
    ) {
        val hasKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS ||
            config.keyboardHidden == Configuration.KEYBOARDHIDDEN_NO
        val notHidden = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        hasKeyboard && notHidden
    }
}
