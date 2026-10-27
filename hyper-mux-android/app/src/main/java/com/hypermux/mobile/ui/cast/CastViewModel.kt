package com.hypermux.mobile.ui.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.data.cast.CastLaunch
import com.hypermux.mobile.data.cast.CastOutcome
import com.hypermux.mobile.data.cast.CastReceiver
import com.hypermux.mobile.data.cast.CastState
import com.hypermux.mobile.data.cast.CastWaiting
import com.hypermux.mobile.data.model.CastBriefVo
import com.hypermux.mobile.data.model.CastDeviceVo
import com.hypermux.mobile.data.model.CastPendingVo
import com.hypermux.mobile.data.repo.DeviceRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 投屏 ViewModel。手机端**同时是投送端和接收端**（本期已开"手机接手机"）：
 *
 *  - 投送侧状态在 [CastState]（@Singleton）：active / waiting / outcome；
 *  - 接收侧状态在 [CastReceiver]（@Singleton）：pending / launch / activeCastId / stopRequested。
 *
 * 两条线都放在单例里而不是 ViewModel 里，是因为播放页、详情页、「我的」页、导航壳各自
 * `hiltViewModel()` 拿到的是**不同实例**，只有单例才能让它们共享同一份投屏状态。
 */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val deviceRepo: DeviceRepo,
    val cast: CastState,
    val receiver: CastReceiver
) : ViewModel() {

    private val _devices = MutableStateFlow<List<CastDeviceVo>>(emptyList())
    val devices: StateFlow<List<CastDeviceVo>> = _devices

    private val _loadingDevices = MutableStateFlow(false)
    val loadingDevices: StateFlow<Boolean> = _loadingDevices

    private val _lastTargetId = MutableStateFlow<String?>(null)
    val lastTargetId: StateFlow<String?> = _lastTargetId

    /** 当前生效的投屏（目标端正在播）；非空时手机本地播放必须停止 */
    val active: StateFlow<CastBriefVo?> = cast.active
    val waiting: StateFlow<CastWaiting?> = cast.waiting
    val outcome: StateFlow<CastOutcome?> = cast.outcome
    val enabled: StateFlow<Boolean> = cast.enabled

    // ---- 接收侧（本机当目标端） ----
    val incomingPending: StateFlow<CastPendingVo?> = receiver.pending
    val incomingLaunch: StateFlow<CastLaunch?> = receiver.launch
    val incomingActiveId: StateFlow<Long?> = receiver.activeCastId
    val incomingStopRequested: SharedFlow<Long> = receiver.stopRequested
    val incomingNotice: StateFlow<String?> = receiver.notice
    val incomingSender: StateFlow<String?> = receiver.activeSender
    val incomingCastKey: StateFlow<Pair<Long, Long>?> = receiver.activeCastKey
    val receivingPolling: StateFlow<Boolean> = receiver.polling
    val confirmSeconds: StateFlow<Int> = receiver.confirmSeconds

    /** 本机设备名（「我的」页展示与设备列表里区分自己） */
    private val _selfName = MutableStateFlow("")
    val selfName: StateFlow<String> = _selfName

    fun openSheet() {
        cast.refreshConfig()
        cast.registerSelf()
        viewModelScope.launch {
            _selfName.value = deviceRepo.deviceName()
            _lastTargetId.value = deviceRepo.lastCastTarget()
            refreshDevices()
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _loadingDevices.value = true
            _devices.value = deviceRepo.listCastTargets()
            _loadingDevices.value = false
        }
    }

    fun castTo(device: CastDeviceVo, movieId: Long, videoId: Long, positionMs: Long) {
        if (!device.online) return
        viewModelScope.launch { deviceRepo.setLastCastTarget(device.deviceId) }
        cast.send(
            targetDeviceId = device.deviceId,
            targetName = castTargetName(device.platform, device.deviceName),
            movieId = movieId,
            videoId = videoId,
            positionMs = positionMs
        )
    }

    fun cancelCast() = cast.cancelActive()

    fun dismissOutcome() = cast.consumeOutcome()

    // ---- 接收侧动作 ----

    /** 轮询闸门由导航壳控制：已登录 + App 在前台 */
    fun startReceiver() = receiver.start()
    fun stopReceiver() = receiver.stop()

    fun acceptIncoming(reason: String) = receiver.accept(reason)
    fun rejectIncoming() = receiver.reject()

    /** 播放页退出投屏接收（reason 缺省 mobile-exit，服务端按端记收尾原因） */
    fun cancelIncoming(reason: String = "mobile-exit") = receiver.cancelActiveCast(reason)

    fun consumeIncomingLaunch() = receiver.consumeLaunch()
    fun consumeIncomingNotice() = receiver.consumeNotice()

    /** 登出 / 会话失效：投送与接收两条线一起清 */
    fun clearAll() {
        cast.clear()
        receiver.clear()
    }

    /** 「我的」页：改本机设备名（投屏弹窗里显示的就是它） */
    fun setSelfName(name: String) {
        viewModelScope.launch {
            deviceRepo.setDeviceName(name)
            _selfName.value = deviceRepo.deviceName()
            cast.registerSelf()
        }
    }

    /** 本机 deviceId（「我的」页显示尾号，便于与服务端设备表对得上） */
    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId

    init {
        viewModelScope.launch {
            _deviceId.value = deviceRepo.deviceId()
            _selfName.value = deviceRepo.deviceName()
        }
    }
}
