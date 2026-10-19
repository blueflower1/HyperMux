package com.hypermux.tv.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ServerUrlInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 外观模式：DARK / LIGHT / SYSTEM */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * 服务器连接模式：
 * - [PUBLIC] 公开服务器：内置公共节点（[com.hypermux.tv.config.AppConfig.PRESET_SERVER_URL]），UI 不展示地址；
 * - [CUSTOM] 自定义服务器：用户自托管地址（存 [Keys.SERVER_URL]）。
 */
enum class ServerMode { PUBLIC, CUSTOM }

/**
 * 应用设置：服务器地址（运行时可配，决策 D7）、主题模式、搜索历史。
 * 内存缓存 @Volatile 字段供 [ServerUrlInterceptor] 同步读取。
 */
@Singleton
class SettingsRepo @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_MODE = stringPreferencesKey("server_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SEARCH_HISTORY = stringPreferencesKey("search_history_json")
        // ---- 播放偏好（§倍速）----
        val LONG_PRESS_SPEED = floatPreferencesKey("long_press_speed")
        val REMEMBER_SPEED = booleanPreferencesKey("remember_speed")
        val LAST_SPEED = floatPreferencesKey("last_speed")
        // ---- 缓存偏好 ----
        val WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")
        // ---- App 自更新（§3.11）----
        // 这两个值都**按平台隔离**（见 UPDATE_PLATFORM 注释）
        val UPDATE_PLATFORM = stringPreferencesKey("update_platform")
        val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
        val DISMISSED_UPDATE_CODE = intPreferencesKey("dismissed_update_code")
    }

    private val dataStore = context.dataStore

    @Volatile
    var cachedServerUrl: String? = null
        private set

    /**
     * 探测期临时地址：登录页配置弹窗里"测试连接"时，请求需命中**草稿地址**（尚未保存），
     * 设它而非 [setServerUrl] 避免误持久化。预载值之上最高优先级，探测结束置回 null。
     */
    @Volatile
    var probingUrl: String? = null

    // ---- 服务器地址 ----

    val serverUrl: StateFlow<String?> = dataStore.data
        .map { it[Keys.SERVER_URL] }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 服务器连接模式。
     * 显式保存过 [Keys.SERVER_MODE] 的以其为准；**旧版本升级用户**（无该 key）按存量地址推导：
     * 保存过自定义地址 → [ServerMode.CUSTOM]（行为与旧版一致，登录页仍显示原地址）；
     * 全新安装 → 有内置公开服务器时 [ServerMode.PUBLIC]，否则 [ServerMode.CUSTOM]（用户自填）。
     * 构建期未注入预置地址时，持久化的 public 一律回退 CUSTOM（防止空地址登录失败）。
     */
    val serverMode: StateFlow<ServerMode> = dataStore.data
        .map { prefs ->
            when (prefs[Keys.SERVER_MODE]) {
                "public" -> if (AppConfig.HAS_PRESET_SERVER) ServerMode.PUBLIC else ServerMode.CUSTOM
                "custom" -> ServerMode.CUSTOM
                else -> when {
                    !prefs[Keys.SERVER_URL].isNullOrBlank() -> ServerMode.CUSTOM
                    AppConfig.HAS_PRESET_SERVER -> ServerMode.PUBLIC
                    else -> ServerMode.CUSTOM
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, if (AppConfig.HAS_PRESET_SERVER) ServerMode.PUBLIC else ServerMode.CUSTOM)

    suspend fun setServerMode(mode: ServerMode) {
        dataStore.edit { it[Keys.SERVER_MODE] = mode.name.lowercase() }
    }

    /** 启动时预载到内存缓存（拦截器同步读取） */
    fun preload() {
        scope.launch {
            cachedServerUrl = serverUrl.first()
        }
    }

    /**
     * 同步取服务器地址：拦截器在**首个请求**前调用，此时 [preload] 可能尚未完成，
     * 若拿不到就用占位地址 http://localhost:8080 → 请求被拒（ECONNREFUSED）。
     * 因此这里同步兜底读一次 DataStore（拦截器运行在 OkHttp 后台线程，阻塞可接受）。
     * 探测期 [probingUrl] 非空时优先命中它（不持久化的草稿地址）。
     */
    fun serverUrlBlocking(): String? {
        probingUrl?.takeIf { it.isNotBlank() }?.let { return it }
        cachedServerUrl?.let { return it }
        val v = runBlocking { dataStore.data.first()[Keys.SERVER_URL] }
        if (!v.isNullOrBlank()) {
            cachedServerUrl = v
        }
        return cachedServerUrl
    }

    suspend fun setServerUrl(url: String?) {
        cachedServerUrl = url
        dataStore.edit { it[Keys.SERVER_URL] = url.orEmpty() }
    }

    /**
     * 生效地址 + 模式一起落盘（登录成功 / 服务器配置弹窗保存时调用）：
     * 公开模式把内置预置地址写入 [Keys.SERVER_URL]（拦截器 / 缓存 key 继续读它，零改动），
     * 并标记 [Keys.SERVER_MODE]=public —— 登录页 / 我的页据此显示"公开服务器"而不露地址。
     */
    suspend fun applyServer(mode: ServerMode, customUrl: String?) {
        val effective = if (mode == ServerMode.PUBLIC && AppConfig.HAS_PRESET_SERVER) AppConfig.PRESET_SERVER_URL else customUrl
        cachedServerUrl = effective
        dataStore.edit {
            it[Keys.SERVER_URL] = effective.orEmpty()
            it[Keys.SERVER_MODE] = mode.name.lowercase()
        }
    }

    // ---- 主题 ----

    val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { prefs ->
            when (prefs[Keys.THEME_MODE]) {
                "light" -> ThemeMode.LIGHT
                "system" -> ThemeMode.SYSTEM
                else -> ThemeMode.DARK
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.DARK)

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name.lowercase() }
    }

    // ---- 搜索历史（JSON 数组，最近 20 条） ----

    private val searchHistoryFlow = dataStore.data
        .map { prefs ->
            val json = prefs[Keys.SEARCH_HISTORY] ?: return@map emptyList<String>()
            runCatching { gson.fromJson(json, Array<String>::class.java).toList() }
                .getOrDefault(emptyList())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val searchHistory: StateFlow<List<String>> = searchHistoryFlow

    suspend fun addSearchHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val next = (listOf(kw) + searchHistoryFlow.value.filter { it != kw }).take(20)
        dataStore.edit { it[Keys.SEARCH_HISTORY] = gson.toJson(next) }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { it.remove(Keys.SEARCH_HISTORY) }
    }

    // ---- 播放偏好（倍速） ----

    /**
     * 长按手势的临时倍速（松手回到用户当前倍速）。默认 2.0x。
     * 做成可配置：部分用户希望"长按 = 3x 快速过片头"，也有用户觉得 2x 太快。
     */
    val longPressSpeed: StateFlow<Float> = dataStore.data
        .map { prefs -> prefs[Keys.LONG_PRESS_SPEED]?.takeIf { it > 0f } ?: DEFAULT_LONG_PRESS_SPEED }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_LONG_PRESS_SPEED)

    suspend fun setLongPressSpeed(v: Float) {
        dataStore.edit { it[Keys.LONG_PRESS_SPEED] = v }
    }

    /** 记忆上次倍速：开启时进入播放页自动套用 [lastSpeed] */
    val rememberSpeed: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[Keys.REMEMBER_SPEED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setRememberSpeed(v: Boolean) {
        dataStore.edit { it[Keys.REMEMBER_SPEED] = v }
    }

    /** 用户最后一次手动选择的倍速（只有用户在面板里选过才写入） */
    val lastSpeed: StateFlow<Float> = dataStore.data
        .map { prefs -> prefs[Keys.LAST_SPEED]?.takeIf { it > 0f } ?: 1f }
        .stateIn(scope, SharingStarted.Eagerly, 1f)

    suspend fun setLastSpeed(v: Float) {
        dataStore.edit { it[Keys.LAST_SPEED] = v }
    }

    // ---- 缓存偏好 ----

    /**
     * 仅 Wi-Fi 下载（默认开）：移动数据下不消耗流量去缓存，符合国内用户的默认预期。
     * 由 [com.hypermux.tv.data.cache.MediaCacheManager.setWifiOnly] 转成 DownloadManager 的运行条件。
     */
    val wifiOnlyDownload: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[Keys.WIFI_ONLY_DOWNLOAD] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setWifiOnlyDownload(v: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY_DOWNLOAD] = v }
    }

    // ---- App 自更新（§3.11：启动 24h 节流 + 逐版本「稍后」记忆） ----
    //
    // 状态**按平台隔离**（`Keys.UPDATE_PLATFORM`）：手机端与 TV 端各自发各自的包、versionCode 各自递增，
    // 共用一个"已忽略版本"会串味 —— 手机端已到 12，TV 端才 1，`1 > 12` 永远不成立，
    // 用户一旦在手机包上点过「稍后」，TV 端就再也提示不到自己的更新了。
    // 平台不匹配（含老版本写入的无平台记录）→ 视为"从未检查 / 没点过稍后"。

    /** 已记录状态的平台是否就是当前平台 */
    private suspend fun updateStateMatches(platform: String): Boolean =
        dataStore.data.first()[Keys.UPDATE_PLATFORM] == platform

    /** 上次成功检查更新的时间戳（ms）；0 = 从未检查（或平台已切换）。调用方按 [AppConfig.UPDATE_CHECK_INTERVAL_MS] 决策 */
    suspend fun lastUpdateCheckAt(platform: String): Long =
        if (!updateStateMatches(platform)) 0L
        else dataStore.data.first()[Keys.LAST_UPDATE_CHECK_AT] ?: 0L

    suspend fun setLastUpdateCheckAt(platform: String, at: Long) {
        dataStore.edit {
            it[Keys.UPDATE_PLATFORM] = platform
            it[Keys.LAST_UPDATE_CHECK_AT] = at
        }
    }

    /** 用户点过「稍后」的 versionCode：该版本不再主动弹窗（服务端发布更高版本时仍会 > 它而再次提示） */
    suspend fun dismissedUpdateCode(platform: String): Int =
        if (!updateStateMatches(platform)) 0
        else dataStore.data.first()[Keys.DISMISSED_UPDATE_CODE] ?: 0

    suspend fun setDismissedUpdateCode(platform: String, code: Int) {
        dataStore.edit {
            it[Keys.UPDATE_PLATFORM] = platform
            it[Keys.DISMISSED_UPDATE_CODE] = code
        }
    }

    companion object {
        private val gson = com.google.gson.Gson()

        /** 长按倍速默认值（与需求一致：2.0x） */
        const val DEFAULT_LONG_PRESS_SPEED = 2.0f
    }
}
