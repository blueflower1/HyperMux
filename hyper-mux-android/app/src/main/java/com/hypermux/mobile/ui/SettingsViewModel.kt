package com.hypermux.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.cache.CacheStats
import com.hypermux.mobile.data.cache.MediaCacheManager
import com.hypermux.mobile.data.repo.AuthRepo
import com.hypermux.mobile.data.repo.ServerMode
import com.hypermux.mobile.data.repo.SettingsRepo
import com.hypermux.mobile.data.repo.ThemeMode
import coil3.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页动作：主题 / 播放偏好 / 媒体缓存 / 封面缓存 / 登出（服务器地址只读展示，修改走登录页「服务器配置」） */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepo,
    private val authRepo: AuthRepo,
    private val cacheManager: MediaCacheManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepo.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    val serverUrl: StateFlow<String?> = settingsRepo.serverUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 服务器连接模式（公开模式时"我的"页显示"公开服务器"而不露地址） */
    val serverMode: StateFlow<ServerMode> = settingsRepo.serverMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, if (AppConfig.HAS_PRESET_SERVER) ServerMode.PUBLIC else ServerMode.CUSTOM)

    // ---- 播放偏好（倍速） ----

    val longPressSpeed: StateFlow<Float> = settingsRepo.longPressSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepo.DEFAULT_LONG_PRESS_SPEED)

    val rememberSpeed: StateFlow<Boolean> = settingsRepo.rememberSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLongPressSpeed(v: Float) {
        viewModelScope.launch { settingsRepo.setLongPressSpeed(v) }
    }

    fun setRememberSpeed(v: Boolean) {
        viewModelScope.launch { settingsRepo.setRememberSpeed(v) }
    }

    // ---- 显式下载（「我的下载」页 / 我的页共用） ----

    val cacheStats: StateFlow<CacheStats> = cacheManager.stats

    /** 仅 Wi-Fi 下载（缓存中心 / 我的页共用） */
    val wifiOnlyDownload: StateFlow<Boolean> = settingsRepo.wifiOnlyDownload
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setWifiOnlyDownload(v: Boolean) {
        viewModelScope.launch { settingsRepo.setWifiOnlyDownload(v) }
        // 立刻作用到 DownloadManager（蜂窝下正在跑的下载会被系统暂停，回到 Wi-Fi 自动继续）
        cacheManager.setWifiOnly(v)
    }

    /** 重算缓存占用/已完成集数（进入「我的」页时调用） */
    fun refreshCacheStats() {
        cacheManager.syncState()
    }

    fun clearAllCache() {
        cacheManager.clearAll()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun logout() {
        authRepo.invalidate()
    }

    // ---- 封面缓存（Coil 磁盘缓存） ----

    /** 当前封面磁盘缓存大小（字节） */
    fun coverCacheBytes(): Long =
        runCatching { context.imageLoader.diskCache?.size }.getOrNull() ?: 0L

    /** 清除封面磁盘缓存 */
    fun clearCoverCache() {
        runCatching { context.imageLoader.diskCache?.clear() }
    }
}
