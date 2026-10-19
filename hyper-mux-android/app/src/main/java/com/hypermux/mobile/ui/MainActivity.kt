package com.hypermux.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hypermux.mobile.ui.layout.AdaptiveScope
import com.hypermux.mobile.ui.navigation.AppNavigation
import com.hypermux.mobile.ui.theme.HyperMuxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperMuxTheme {
                // 窗口尺寸档的唯一来源（手机 / 平板 / 分屏 / 桌面窗口都走这一条）
                AdaptiveScope { AppNavigation() }
            }
        }
    }
}
