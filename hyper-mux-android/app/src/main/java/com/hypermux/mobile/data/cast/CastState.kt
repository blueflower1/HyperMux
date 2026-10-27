package com.hypermux.mobile.data.cast

import com.google.gson.Gson
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.model.CastBriefVo
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 投屏结果（一次性 UI 事件，UI 消费后 [CastState.consumeOutcome] 清空） */
sealed interface CastOutcome {
    /** 目标端（电视 / 网页端 / 电脑端）已接受：手机弹「已接受你远程投屏的请求」，并停止本地播放 */
    data class Accepted(val targetName: String) : CastOutcome
    data object Rejected : CastOutcome
    data object Expired : CastOutcome
    data object Cancelled : CastOutcome
    data class Failed(val msg: String) : CastOutcome
}

/** 等待目标端（电视 / 网页端 / 电脑端）确认中的上下文（UI 显示「正在等待「{目标名}」确认…」」） */
data class CastWaiting(
    val requestId: Long,
    val targetDeviceId: String,
    val targetName: String,
    val movieId: Long,
    val videoId: Long,
    val positionMs: Long
)

/**
 * 投屏状态中枢（手机端 = 投送设备，**不是遥控器**）。
 *
 * 生命周期：send() → waiting（1s 轮 status，上限 17s）→ 终态 outcome
 *   ACCEPTED → active（手机停止本地播放，只留「取消投屏」）
 *   REJECTED / EXPIRED / CANCELLED → active 保持空
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

    private val _active = MutableStateFlow<CastBriefVo?>(null)
    /** 当前生效的投屏（TV 正在播）。非空时手机本地播放必须停止且不再上报断点。 */
    val active: StateFlow<CastBriefVo?> = _active

    private val _waiting = MutableStateFlow<CastWaiting?>(null)
    val waiting: StateFlow<CastWaiting?> = _waiting

    private val _outcome = MutableStateFlow<CastOutcome?>(null)
    val outcome: StateFlow<CastOutcome?> = _outcome

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var pollJob: Job? = null
    private var watchJob: Job? = null

    val isCasting: Boolean get() = _active.value != null

    init {
        // 投屏生效后开一个低频观察（5s 一轮）：TV 退出播放页 / 被同账号新请求接管时，
        // 手机上的「已投送至 …」胶囊要能自己消失，不能一直挂着。
        scope.launch {
            _active.collect { a ->
                if (a == null) {
                    watchJob?.cancel()
                    watchJob = null
                } else if (watchJob?.isActive != true) {
                    watchJob = startWatch(a.id)
                }
            }
        }
    }

    private fun startWatch(activeId: Long): Job = scope.launch {
        while (isActive) {
            delay(WATCH_INTERVAL_MS)
            val r = runCatching { safeApiCall(gson) { api.castCurrent() } }.getOrNull()
            val cur = (r as? ApiResult.Ok)?.data?.active
            if (cur == null || cur.id != activeId) {
                _active.value = null
                return@launch
            }
            if (cur.status == "REJECTED" || cur.status == "EXPIRED" ||
                cur.status == "CANCELLED" || cur.status == "CLOSED"
            ) {
                _active.value = null
                return@launch
            }
        }
    }

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
                                // 用服务端的 current 补全展示字段（片名/集名/封面），失败退回本地已知信息。
                                // id 必须对得上：本机此刻可能已被别的设备的投屏接管，不能把那条当成自己投出去的。
                                val serverSide = runCatching { safeApiCall(gson) { api.castCurrent() } }
                                    .getOrNull()?.let { (it as? ApiResult.Ok)?.data?.active }
                                    ?.takeIf { it.id == w.requestId }
                                _active.value = serverSide ?: CastBriefVo(
                                    id = w.requestId,
                                    status = "ACCEPTED",
                                    targetDeviceId = w.targetDeviceId,
                                    targetName = w.targetName,
                                    movieId = w.movieId,
                                    videoId = w.videoId,
                                    positionMs = w.positionMs
                                )
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

    /** 取消投屏（等待中或已投送都适用）。服务端幂等。 */
    fun cancelActive(reason: String = "sender-cancel") {
        val id = _waiting.value?.requestId ?: _active.value?.id
        _waiting.value = null
        _active.value = null
        pollJob?.cancel()
        pollJob = null
        if (id == null) return
        scope.launch {
            runCatching { safeApiCall(gson) { api.castCancel(CastIdReq(id, reason)) } }
        }
    }

    /**
     * 重启 / 切后台回来恢复投屏状态（不重复发起）。
     *
     * 只认"我投出去的"那一条：`cast/current` 是按账号查的，而本机现在也可能是别人的投屏目标
     * （手机端已开接收侧）。把别人那条当成自己的，会让本机莫名停播并挂出「已投送至」胶囊。
     */
    fun restore() {
        if (_active.value != null) return
        scope.launch {
            val r = safeApiCall(gson) { api.castCurrent() }
            if (r !is ApiResult.Ok) return@launch
            val cur = r.data.active ?: return@launch
            val mine = deviceRepo.deviceId()
            if (cur.senderDeviceId == null || cur.senderDeviceId == mine) _active.value = cur
        }
    }

    /** 登出时清空（避免把上一账号的投屏状态带过去） */
    fun clear() {
        _waiting.value = null
        _active.value = null
        _outcome.value = null
        pollJob?.cancel()
        pollJob = null
        watchJob?.cancel()
        watchJob = null
    }

    fun consumeOutcome() {
        _outcome.value = null
    }

    /** 登录成功 / 打开投屏入口时的顺手注册（幂等） */
    fun registerSelf() {
        deviceRepo.registerSelfAsync()
    }

    private companion object {
        /** 投屏生效后的低频观察间隔 */
        const val WATCH_INTERVAL_MS = 5_000L
    }
}
