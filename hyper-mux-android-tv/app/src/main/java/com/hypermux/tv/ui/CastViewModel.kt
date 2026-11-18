package com.hypermux.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.hypermux.tv.data.api.ApiResult
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.safeApiCall
import com.hypermux.tv.data.cast.CastIncoming
import com.hypermux.tv.data.cast.CastPollClient
import com.hypermux.tv.data.model.CastAcceptReq
import com.hypermux.tv.data.model.CastIdReq
import com.hypermux.tv.data.model.CastPendingVo
import com.hypermux.tv.data.repo.DeviceRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 确认成功后要拉起播放的那一集 */
data class CastLaunch(
    val castId: Long,
    val movieId: Long,
    val videoId: Long,
    val resumeMs: Long,
    val senderName: String,
    val movieTitle: String,
    val episodeLabel: String
)

/**
 * TV 投屏 ViewModel（计划书 80-cast-detailed.md §9）。
 *
 * 挂在导航窗口层（Activity 作用域）：确认弹窗要能在任何页面弹出（用户可能正停在首页/详情页），
 * 播放页也要读同一份状态（徽标、退出时 cancel）。
 */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val pollClient: CastPollClient,
    private val deviceRepo: DeviceRepo
) : ViewModel() {

    /** 待确认的投屏请求（非空即弹确认窗） */
    private val _pending = MutableStateFlow<CastPendingVo?>(null)
    val pending: StateFlow<CastPendingVo?> = _pending

    /** 确认成功 → 由导航层消费一次并拉起播放 */
    private val _launch = MutableStateFlow<CastLaunch?>(null)
    val launch: StateFlow<CastLaunch?> = _launch

    /** 正在播的投屏请求 id（播放页退出时据此 cancel；null = 本地正常播放） */
    private val _activeCastId = MutableStateFlow<Long?>(null)
    val activeCastId: StateFlow<Long?> = _activeCastId

    /**
     * 该投屏已被取消/接管 → 播放页收到后停止播放并回上一页。
     *
     * 用一次性事件流而不是 StateFlow：StateFlow 会在"没有播放页在听"时把值留着，
     * 等用户下次进任意播放页时被当成新的停止信号、把好好看着的片子踢出去。
     */
    private val _stopRequested = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val stopRequested: SharedFlow<Long> = _stopRequested

    /** 需要提示的一句话（走 TV 提示层，不用系统 Toast） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    val enabled: StateFlow<Boolean> = pollClient.enabled
    val confirmSeconds: StateFlow<Int> = pollClient.confirmSeconds
    val polling: StateFlow<Boolean> = pollClient.polling

    fun startPolling() = pollClient.start()
    fun stopPolling() = pollClient.stop()
    fun refreshConfig() {
        viewModelScope.launch { pollClient.refreshConfig() }
    }

    init {
        viewModelScope.launch {
            pollClient.incoming.collect { e ->
                when (e) {
                    is CastIncoming.Request -> {
                        // 同一请求在 PENDING 期间会被每轮重复下发：只在 id 变化时更新，避免弹窗倒计时被重置
                        if (_pending.value?.id == e.pending.id) return@collect
                        _pending.value = e.pending
                    }

                    is CastIncoming.Cancelled -> {
                        _pending.value?.let { if (e.ids.contains(it.id)) _pending.value = null }
                        _activeCastId.value?.let { id ->
                            if (e.ids.contains(id)) {
                                _activeCastId.value = null
                                _stopRequested.tryEmit(id)
                                // TV 自己 BACK 退出时已先清掉 activeCastId，不会走到这里 → 这条提示只针对"别人替我停的"
                                _notice.value = "投屏已结束：发起端已取消，或被同账号其它设备接管"
                            }
                        }
                    }

                    CastIncoming.DeviceUnknown -> runCatching { deviceRepo.registerSelf() }
                }
            }
        }
    }

    /** 点「是」或倒计时归零（reason 落库，便于服务端与排查对齐） */
    fun accept(reason: String) {
        val p = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            when (val r = safeApiCall(gson) { api.castAccept(CastAcceptReq(p.id, reason)) }) {
                is ApiResult.Ok -> {
                    _activeCastId.value = p.id
                    _launch.value = CastLaunch(
                        castId = p.id,
                        movieId = r.data.movieId,
                        videoId = r.data.videoId,
                        resumeMs = r.data.positionMs,
                        senderName = p.senderName ?: "手机端",
                        movieTitle = r.data.movieTitle ?: p.movieTitle ?: "",
                        episodeLabel = r.data.episodeLabel ?: p.episodeLabel ?: ""
                    )
                }

                is ApiResult.Err -> _notice.value = r.msg.ifBlank { "该投屏请求已结束" }
            }
        }
    }

    /** 点「否」或按 BACK */
    fun reject() {
        val p = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            safeApiCall(gson) { api.castReject(CastIdReq(p.id, "user-no")) }
        }
    }

    /** 播放页退出：通知服务端停止投屏（幂等），并清掉本地"正在投屏"标记 */
    fun cancelActiveCast(reason: String) {
        val id = _activeCastId.value ?: return
        _activeCastId.value = null
        viewModelScope.launch {
            safeApiCall(gson) { api.castCancel(CastIdReq(id, reason)) }
        }
    }

    fun consumeLaunch() {
        _launch.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** 播放页自然播完/用户主动退出后清标记（不算取消，服务端会按 CLOSED 收尾） */
    fun clearActiveCast() {
        _activeCastId.value = null
    }

    suspend fun selfDeviceId(): String = deviceRepo.deviceId()
    suspend fun selfDeviceName(): String = deviceRepo.deviceName()
    suspend fun listDevices() = deviceRepo.listDevices()
    fun setSelfName(name: String) {
        viewModelScope.launch {
            deviceRepo.setDeviceName(name)
            deviceRepo.registerSelf()
        }
    }
}
