package com.hypermux.tv.data.update

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 更新包下载状态（下载 Service 与 UI 之间的桥）：
 * 前台服务跑下载 / 校验 / 落盘，把进度写回这里；更新弹窗与「我的」页从这里读。
 * 做成进程级单例（不是 ViewModel）：Service 独立于 Activity 生命周期，
 * 用户切屏 / 关弹窗 / 进程内导航都不丢下载状态。
 */
@Singleton
class UpdateDownloadState @Inject constructor() {

    sealed interface Down {
        data object Idle : Down
        data class Loading(val received: Long, val total: Long) : Down
        data object Verifying : Down

        /** 校验通过、包已落盘，可直接安装 */
        data class Ready(val apk: File, val versionCode: Int, val versionName: String) : Down
        data class Failed(val msg: String) : Down
    }

    private val _down = MutableStateFlow<Down>(Down.Idle)
    val down: StateFlow<Down> = _down.asStateFlow()

    /**
     * 服务是否真正跑起来（[UpdateDownloadService] 入口置位 / onDestroy 清位）。
     * VM 用它做启动看门狗：startForegroundService 之后若服务一直没活起来
     * （ROM 杀进程 / 延迟投递等），UI 优雅降级为可重试的失败，而不是卡在"下载中"。
     */
    @Volatile
    private var _serviceAlive = false

    val serviceAlive: Boolean
        get() = _serviceAlive

    fun markServiceStarted() {
        _serviceAlive = true
    }

    fun markServiceStopped() {
        _serviceAlive = false
    }

    fun isLoading(): Boolean = _down.value is Down.Loading
    fun isWorking(): Boolean =
        _down.value is Down.Loading || _down.value is Down.Verifying

    fun loading(received: Long, total: Long) {
        _down.value = Down.Loading(received, total)
    }

    fun verifying() {
        _down.value = Down.Verifying
    }

    fun ready(apk: File, versionCode: Int, versionName: String) {
        _down.value = Down.Ready(apk, versionCode, versionName)
    }

    fun failed(msg: String) {
        _down.value = Down.Failed(msg)
    }

    fun idle() {
        _down.value = Down.Idle
    }
}
