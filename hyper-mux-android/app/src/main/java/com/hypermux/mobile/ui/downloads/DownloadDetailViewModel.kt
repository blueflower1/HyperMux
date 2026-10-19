package com.hypermux.mobile.ui.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.data.api.NetworkMonitor
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
 * 「我的下载」二级页状态：单部影片的集级列表（分"下载中 / 已下载"两区）+ 集速估算。
 *
 * movieId 从导航参数取；2s 轮询与一级页同口径（下载进度没有变更回调，见 MyDownloadsViewModel 注释）。
 */
@HiltViewModel
class DownloadDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cacheManager: MediaCacheManager,
    private val settingsRepo: SettingsRepo,
    networkMonitor: NetworkMonitor,
    @ApplicationContext context: Context
) : ViewModel() {

    val movieId: Long = savedStateHandle.get<Long>("movieId") ?: -1L

    private val _group = MutableStateFlow<MovieDownloadGroup?>(null)

    /** 该影片的下载分组（集速已估算；无下载时返回 null） */
    val group: StateFlow<MovieDownloadGroup?> = _group

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
        _group.value = cacheManager.groups().firstOrNull { it.movieId == movieId }?.let { g ->
            g.copy(
                episodes = g.episodes.map { ep ->
                    ep.copy(speedBytesPerSec = tracker.speedOf(ep.mediaId, ep.bytes, ep.isActive, now))
                }
            )
        }
    }

    fun pause(item: EpisodeDownload) {
        cacheManager.pauseDownload(item.mediaId)
        refresh()
    }

    fun resume(item: EpisodeDownload) {
        cacheManager.resumeDownload(item.mediaId)
        refresh()
    }

    fun pauseAll() {
        cacheManager.pauseAll()
        refresh()
    }

    fun resumeAll() {
        cacheManager.resumeAll()
        refresh()
    }

    fun remove(item: EpisodeDownload) {
        cacheManager.removeDownload(item.mediaId)
        refresh()
    }

    /** 清空该影片全部下载（含进行中任务 + 元信息） */
    fun clearGroup() {
        cacheManager.removeGroup(movieId)
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
