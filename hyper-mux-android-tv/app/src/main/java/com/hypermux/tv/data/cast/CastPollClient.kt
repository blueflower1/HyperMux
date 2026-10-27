package com.hypermux.tv.data.cast

import com.google.gson.Gson
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ApiResult
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.safeApiCall
import com.hypermux.tv.data.model.CastPendingVo
import com.hypermux.tv.data.repo.DeviceRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 轮询收到的投屏事件 */
sealed interface CastIncoming {
    /** 有新的待确认请求：弹确认窗 */
    data class Request(val pending: CastPendingVo) : CastIncoming

    /** 这些投屏已被取消/被其它设备接管：弹窗正显示该 id 就关掉；正在播该集就停止 */
    data class Cancelled(val ids: List<Long>) : CastIncoming

    /** 服务端 404（设备未注册）：注册一次后继续 */
    data object DeviceUnknown : CastIncoming
}

/**
 * TV 侧投屏轮询（计划书 80-cast-detailed.md §3 / §9）。
 *
 * 选型：短间隔轮询（与扫码登录的 2s 轮询同构），不引入 SSE/WebSocket。
 * 轮询本身就是"设备在线"心跳 —— 服务端 DevicePresenceService 据此把 TV 标成在线，
 * 手机端才允许向它投屏；TV 停止轮询 30s 后自动离线。
 *
 * 生命周期由 [com.hypermux.tv.ui.CastViewModel] 控制：登录且 App 在前台才轮询，
 * 退到后台立刻停（避免后台空转把 TV 一直标成在线）。
 */
@Singleton
class CastPollClient @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val deviceRepo: DeviceRepo
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 事件总线：replay=0（弹窗只关心"当下"的事件），缓冲 4 条、满了丢最旧的 */
    private val _incoming = MutableSharedFlow<CastIncoming>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming: SharedFlow<CastIncoming> = _incoming

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val _confirmSeconds = MutableStateFlow(AppConfig.CAST_CONFIRM_COUNTDOWN_S)
    val confirmSeconds: StateFlow<Int> = _confirmSeconds

    /** 轮询是否在跑（「我的」页据此显示"投屏监听：开/关"） */
    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _polling.value = false
    }

    private suspend fun loop() {
        _polling.value = true
        // 启动先注册本机 + 拉一次配置（开关与倒计时都以服务端为准）
        runCatching { deviceRepo.registerSelf() }
        refreshConfig()

        var backoff = AppConfig.CAST_POLL_INTERVAL_MS
        // loop() 是普通成员挂起函数（不是 CoroutineScope 接收者），因此用 currentCoroutineContext() 判活
        while (currentCoroutineContext().isActive) {
            val deviceId = deviceRepo.deviceId()
            when (val r = safeApiCall(gson) { api.castPoll(deviceId) }) {
                is ApiResult.Ok -> {
                    backoff = AppConfig.CAST_POLL_INTERVAL_MS
                    _confirmSeconds.value = r.data.confirmSeconds
                    r.data.pending?.let { _incoming.emit(CastIncoming.Request(it)) }
                    if (r.data.cancelledIds.isNotEmpty()) {
                        _incoming.emit(CastIncoming.Cancelled(r.data.cancelledIds))
                    }
                }

                is ApiResult.Err -> {
                    // 401：会话失效由 AuthInterceptor 统一处理（这里只退避，等会话恢复）
                    // 404：设备未注册（换服务器/清数据后常见）→ 注册一次，下一轮正常
                    // 429：轮询过快（不该发生，间隔已 ≥1s）→ 按服务端节奏退避
                    when (r.code) {
                        404 -> {
                            runCatching { deviceRepo.registerSelf() }
                            _incoming.emit(CastIncoming.DeviceUnknown)
                        }
                        429 -> backoff = (backoff * 2).coerceAtMost(10_000L)
                        else -> backoff = (backoff * 2).coerceAtMost(15_000L)
                    }
                }
            }
            delay(backoff)
        }
        _polling.value = false
    }

    /** 拉投屏配置（开关 / 确认秒数）；失败保持上一次的值，不打扰用户 */
    suspend fun refreshConfig() {
        val r = runCatching { safeApiCall(gson) { api.castConfig() } }.getOrNull()
        if (r is ApiResult.Ok) {
            _enabled.value = r.data.enabled
            _confirmSeconds.value = r.data.confirmSeconds.coerceIn(3, 15)
        }
    }
}
