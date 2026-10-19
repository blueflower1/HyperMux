package com.hypermux.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hypermux.tv.ui.navigation.AppNavigation
import com.hypermux.tv.ui.theme.HyperMuxTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * TV 唯一 Activity：
 * - 方向锁定 landscape（manifest 声明，遥控器场景没有竖屏形态）；
 * - 不做 edge-to-edge（TV 无状态栏/导航栏，安全区由 Compose 层 48dp 内边距保证，见 TvSafeArea）；
 * - 屏幕常亮只在播放页处理（`FLAG_KEEP_SCREEN_ON`），浏览时不阻止系统屏保。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperMuxTheme {
                AppNavigation()
            }
        }
    }
}
