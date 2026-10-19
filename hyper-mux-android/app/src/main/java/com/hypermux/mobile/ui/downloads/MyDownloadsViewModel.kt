package com.hypermux.mobile.ui.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.data.api.NetworkMonitor
import com.hypermux.mobile.data.cache.CacheStorageInfo
import com.hypermux.mobile.data.cache.EpisodeDownload
import com.hypermux.mobile.data.cache.MediaCacheManager
import com.hypermux.mobile.data.cache.MovieDownloadGroup
import com.hypermux.mobile.data.repo.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我的下载」一级页状态：按影片分组的显式下载列表 + 存储占用 + 实时总速度。
 *
 * 为什么用轮询而不是 flow：`SimpleCache` 的内容索引**没有变更回调**，
 * 显式下载的字节进度（完成时长 / 分片大小）只能靠定期重算发现
 * （下载状态变化本身由 DownloadManager 回调驱动，这里统一 2s 重算一次，两者都能覆盖）。
 * 页面退出后 ViewModel 被清理，轮询随之停止。
 *
 * 时速用 [SpeedTracker] 按相邻两次轮询的字节差估算；"仅 Wi-Fi 下载"开关在「我的」页，
 * 这里只读它的状态 + 当前是否移动网络，用于"流量暂停"提示条。
 */
@HiltViewModel
class MyDownloadsViewModel @Inject constructor(
    private val cacheManager: MediaCacheManager,
    private val settingsRepo: SettingsRepo,
    networkMonitor: NetworkMonitor,
    @ApplicationContext context: Context
) : ViewModel() {

    private val _groups = MutableStateFlow<List<MovieDownloadGroup>>(emptyList())

    /** 全部已下载影片（含进行中的任务），按影片分组，各组集速已估算 */
    val groups: StateFlow<List<MovieDownloadGroup>> = _groups

    private val _storage = MutableStateFlow(CacheStorageInfo(0L, 0L))

    /** 下载占用 / 设备剩余空间（存储条用；无容量上限概念） */
    val storage: StateFlow<CacheStorageInfo> = _storage

    /** 全部下载任务的实时总速度（字节/秒，无任务时为 0） */
    private val _totalSpeed = MutableStateFlow(0L)
    val totalSpeed: StateFlow<Long> = _totalSpeed

    /** 当前活动网络是否为计费网络（移动数据）；无网络时 false */
    private val _metered = MutableStateFlow(false)
    val meteredNetwork: StateFlow<Boolean> = _metered

    /** 当前活动网络是否为蜂窝（移动数据；含不限流的 5G/4G，Wi-Fi 下为 false） */
    private val _isCellular = MutableStateFlow(false)
    val isCellularNetwork: StateFlow<Boolean> = _isCellular

    /** 「仅 Wi-Fi 下载」开关（「我的」页设置，此处只读） */
    val wifiOnly: StateFlow<Boolean> = settingsRepo.wifiOnlyDownload

    /** 是否离线（无可用网络） */
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    /** DownloadManager 正因运行条件不满足而挂起全部下载（仅 Wi-Fi + 移动网络 / 断网） */
    private val _managerWaiting = MutableStateFlow(false)
    val managerWaiting: StateFlow<Boolean> = _managerWaiting

    private val tracker = SpeedTracker()
    private val connMgr =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        refresh()
        viewModelScope.launch {
            networkMonitor.isOnline.collect { _isOffline.value = !it }
        }
        viewModelScope.launch {
            while (true) {
                delay(2_000)
                refresh()
            }
        }
    }

    fun refresh() {
        _metered.value = runCatching { connMgr.isActiveNetworkMetered }.getOrDefault(false)
        val activeNet = connMgr.activeNetwork
        _isCellular.value = activeNet != null && runCatching {
            connMgr.getNetworkCapabilities(activeNet)?.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) == true
        }.getOrDefault(false)
        _managerWaiting.value =
            runCatching { cacheManager.downloadManager.isWaitingForRequirements() }.getOrDefault(false)
        val now = SystemClock.elapsedRealtime()
        val gs = cacheManager.groups().map { g ->
            g.copy(
                episodes = g.episodes.map { ep ->
                    ep.copy(speedBytesPerSec = tracker.speedOf(ep.mediaId, ep.bytes, ep.isActive, now))
                }
            )
        }
        _groups.value = gs
        _totalSpeed.value = gs.flatMap { it.episodes }.filter { it.isActive }
            .sumOf { it.speedBytesPerSec }
        _storage.value = cacheManager.storageInfo()
    }

    fun pauseAll() {
        cacheManager.pauseAll()
        refresh()
    }

    fun resumeAll() {
        cacheManager.resumeAll()
        refresh()
    }

    fun clearAll() {
        cacheManager.clearAll()
        refresh()
    }

    /** 流量兜底：关掉「仅 Wi-Fi 下载」并立即恢复全部下载 */
    fun useMobileDataNow() {
        viewModelScope.launch { settingsRepo.setWifiOnlyDownload(false) }
        cacheManager.setWifiOnly(false)
        cacheManager.resumeAll()
        refresh()
    }
}
