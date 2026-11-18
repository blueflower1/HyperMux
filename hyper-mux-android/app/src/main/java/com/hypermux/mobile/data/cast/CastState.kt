package com.hypermux.mobile.data.cast

import com.google.gson.Gson
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.model.CastIdReq
import com.hypermux.mobile.data.model.CastRequestReq
import com.hypermux.mobile.data.repo.DeviceRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 投屏结果（一次性 UI 事件，UI 消费后 [CastState.consumeOutcome] 清空） */
sealed interface CastOutcome {
    /** 目标端（电视 / 网页端 / 电脑端 / 手机端）已接受：目标端自行跳转播放页开播，投送流程到此结束 */
    data class Accepted(val targetName: String) : CastOutcome
    data object Rejected : CastOutcome
    data object Expired : CastOutcome
    data object Cancelled : CastOutcome
    data class Failed(val msg: String) : CastOutcome
}

/** 等待目标端（电视 / 网页端 / 电脑端 / 手机端）确认中的上下文（UI 显示「正在等待「{目标名}」确认…」」） */
data class CastWaiting(
    val requestId: Long,
    val targetDeviceId: String,
    val targetName: String,
    val movieId: Long,
    val videoId: Long,
    val positionMs: Long
)

/**
 * 投屏状态中枢（手机端 = 投送设备，**不是遥控器，也不是守护者**）。
 *
 * 与 TV / 网页端同一口径：远程投屏只负责「让目标端跳转页面并开播」——
 * send() → waiting（1s 轮 status，上限 17s）→ 终态 outcome，投送流程就此结束。
 * 送达后不再保留任何 active 跟踪：目标端播多久、怎么结束、被谁接管，都与投送端无关；
 * 要关闭投屏在目标端上操作即可。本机作为接收端的状态在 [CastReceiver]，与本类无关。
 *
 * 单并发在服务端强制（每账号最多 1 条 PENDING）；本类只保证同一时刻只有一个轮询任务。
 */
@Singleton
class CastState @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val deviceRepo: DeviceRepo
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _waiting = MutableStateFlow<CastWaiting?>(null)
    val waiting: StateFlow<CastWaiting?> = _waiting

    private val _outcome = MutableStateFlow<CastOutcome?>(null)
    val outcome: StateFlow<CastOutcome?> = _outcome

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var pollJob: Job? = null

    /** 投屏入口是否可用（管理后台可关；打开投屏时刷新一次） */
    fun refreshConfig() {
        scope.launch {
            val r = safeApiCall(gson) { api.castConfig() }
            if (r is ApiResult.Ok) _enabled.value = r.data.enabled
        }
    }

    /** 发起投屏（同一时刻只允许一个等待中的请求） */
    fun send(targetDeviceId: String, targetName: String, movieId: Long, videoId: Long, positionMs: Long) {
        if (_waiting.value != null) return
        scope.launch {
            val senderId = deviceRepo.deviceId()
            val r = safeApiCall(gson) {
                api.castRequest(
                    CastRequestReq(
                        targetDeviceId = targetDeviceId,
                        senderDeviceId = senderId,
                        movieId = movieId,
                        videoId = videoId,
                        positionMs = positionMs.coerceAtLeast(0L)
                    )
                )
            }
            when (r) {
                is ApiResult.Ok -> {
                    val waiting = CastWaiting(
                        requestId = r.data.requestId,
                        targetDeviceId = targetDeviceId,
                        targetName = r.data.targetName?.takeIf { it.isNotBlank() } ?: targetName,
                        movieId = movieId,
                        videoId = videoId,
                        positionMs = positionMs.coerceAtLeast(0L)
                    )
                    _waiting.value = waiting
                    startPolling(waiting)
                }
                is ApiResult.Err -> _outcome.value = CastOutcome.Failed(r.msg)
            }
        }
    }

    /** 每 1s 轮询请求结果，上限 [AppConfig.CAST_POLL_MAX_MS]（服务端 request-ttl 15s + 余量） */
    private fun startPolling(w: CastWaiting) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val deadline = System.currentTimeMillis() + AppConfig.CAST_POLL_MAX_MS
            var consecutiveErrors = 0
            while (System.currentTimeMillis() < deadline) {
                delay(AppConfig.CAST_POLL_INTERVAL_MS)
                val r = safeApiCall(gson) { api.castStatus(w.requestId) }
                when (r) {
                    is ApiResult.Ok -> {
                        consecutiveErrors = 0
                        when (r.data.status) {
                            "PENDING" -> Unit  // 继续等
                            "ACCEPTED" -> {
                                // 送达即结束：目标端开始播，投送端不再跟踪（播完/结束/接管都归目标端）
                                _waiting.value = null
                                _outcome.value = CastOutcome.Accepted(w.targetName)
                                return@launch
                            }
                            "REJECTED" -> {
                                _waiting.value = null
                                _outcome.value = CastOutcome.Rejected
                                return@launch
                            }
                            "EXPIRED" -> {
                                _waiting.value = null
                                _outcome.value = CastOutcome.Expired
                                return@launch
                            }
                            "CANCELLED", "CLOSED" -> {
                                _waiting.value = null
                                _outcome.value = CastOutcome.Cancelled
                                return@launch
                            }
                        }
                    }
                    is ApiResult.Err -> {
                        // 404 = 请求记录不在了（被清扫/被抢占），直接判结束
                        if (r.code == 404) {
                            _waiting.value = null
                            _outcome.value = CastOutcome.Expired
                            return@launch
                        }
                        consecutiveErrors++
                        if (consecutiveErrors >= AppConfig.OFFLINE_RETRY_TIMES) {
                            _waiting.value = null
                            _outcome.value = CastOutcome.Failed(r.msg.ifBlank { "网络异常，请检查连接" })
                            return@launch
                        }
                        delay(AppConfig.OFFLINE_RETRY_DELAY_MS)
                    }
                }
            }
            // 轮询到上限仍 PENDING：服务端 request-ttl 已过，按未响应处理
            _waiting.value = null
            _outcome.value = CastOutcome.Expired
        }
    }

    /**
     * 取消「等待确认」中的请求（目标端确认之前还能撤；送达后没有取消入口，
     * 关闭投屏在目标端上操作）。服务端幂等。
     */
    fun cancelPending(reason: String = "sender-cancel") {
        val id = _waiting.value?.requestId
        _waiting.value = null
        pollJob?.cancel()
        pollJob = null
        if (id == null) return
        scope.launch {
            runCatching { safeApiCall(gson) { api.castCancel(CastIdReq(id, reason)) } }
        }
    }

    /** 登出时清空（避免把上一账号的投屏状态带过去） */
    fun clear() {
        _waiting.value = null
        _outcome.value = null
        pollJob?.cancel()
        pollJob = null
    }

    fun consumeOutcome() {
        _outcome.value = null
    }

    /** 登录成功 / 打开投屏入口时的顺手注册（幂等） */
    fun registerSelf() {
        deviceRepo.registerSelfAsync()
    }
}
