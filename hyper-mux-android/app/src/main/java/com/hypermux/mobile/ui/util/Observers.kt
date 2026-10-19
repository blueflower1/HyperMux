package com.hypermux.mobile.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hypermux.mobile.data.repo.SettingsRepo
import com.hypermux.mobile.data.repo.ThemeMode
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint：非 Hilt 管理的组合树（如 Theme）直接访问单例仓库。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun settingsRepo(): SettingsRepo
}

@Composable
fun rememberSettingsRepo(): SettingsRepo {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppEntryPoint::class.java
        ).settingsRepo()
    }
}

/** 在组合中观察主题模式 */
@Composable
fun observeThemeMode(): State<ThemeMode> =
    rememberSettingsRepo().themeMode.collectAsState(initial = ThemeMode.DARK)

/** 在组合中观察服务器地址 */
@Composable
fun observeServerUrl(): State<String?> =
    rememberSettingsRepo().serverUrl.collectAsState(initial = null)
