package com.hypermux.mobile.data.cast

import com.google.gson.Gson
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.model.CastAcceptReq
import com.hypermux.mobile.data.model.CastIdReq
import com.hypermux.mobile.data.model.CastPendingVo
import com.hypermux.mobile.data.repo.DeviceRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 接受投屏后要拉起播放的那一集（导航层消费一次） */
data class CastLaunch(
    val castId: Long,
    val movieId: Long,
    val videoId: Long,
    val resumeMs: Long,
    val senderName: String,
    val movieTitle: String,
    val episodeLabel: String
)

/** 投屏携带的起播位置提示：PlayerViewModel 按 (movieId, videoId) 取用，取到即清 */
private data class CastStartHint(val movieId: Long, val videoId: Long, val positionMs: Long, val untilMs: Long)

/**
 * 手机端投屏**接收**侧（计划书 80-cast-detailed.md §9.7）：本机作为目标端被别的设备投屏。
 *
 * 与 [CastState]（投送侧）是两条独立的状态线，不要混用：
 *  - [CastState].active = "我投出去的，别人正在播" → 本机必须停本地播放；
 *  - 本类.activeCastId = "别人投给我的，我正在播" → 本机正常播放并照常上报断点。
 * 同一账号服务端只留 1 条活跃请求，所以两者不会同时成立。
 *
 * 轮询 = 本机"在线"心跳：服务端 DevicePresenceService 据此把这台手机标成在线，别的设备才允许投过来；
 * 停止轮询 30s 后自动离线。因此生命周期由调用方控制：**已登录 + App 在前台**才轮询。
 */
@Singleton
class CastReceiver @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val deviceRepo: DeviceRepo
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** 待确认的投屏请求（非空即弹确认窗） */
    private val _pending = MutableStateFlow<CastPendingVo?>(null)
    val pending: StateFlow<CastPendingVo?> = _pending

    /** 接受成功 → 由导航层消费一次并拉起播放 */
    private val _launch = MutableStateFlow<CastLaunch?>(null)
    val launch: StateFlow<CastLaunch?> = _launch

    /** 正在播的投屏请求 id（播放页退出时据此 cancel；null = 本机正常播放） */
    private val _activeCastId = MutableStateFlow<Long?>(null)
    val activeCastId: StateFlow<Long?> = _activeCastId

    /** 这一路投屏是谁发起的（播放页徽标显示它） */
    private val _activeSender = MutableStateFlow<String?>(null)
    val activeSender: StateFlow<String?> = _activeSender

    /** 这一路投屏要播的是哪一集 (movieId, videoId)：播放页据此判断"这一屏是不是该投屏的落地页" */
    private val _activeCastKey = MutableStateFlow<Pair<Long, Long>?>(null)
    val activeCastKey: StateFlow<Pair<Long, Long>?> = _activeCastKey

    /**
     * 该投屏已被取消/被其它设备接管 → 播放页收到后停止播放并回上一页。
     *
     * 用一次性事件流而不是 StateFlow：StateFlow 会在"没有播放页在听"时把值留着，
     * 等用户下次进任意播放页时被当成新的停止信号、把好好看着的片子踢出去。
     */
    private val _stopRequested = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val stopRequested: SharedFlow<Long> = _stopRequested

    /** 需要提示的一句话（播放页 / 「我的」页消费） */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** 轮询是否在跑（「我的」页据此显示"投屏接收：监听中 / 未监听"） */
    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling

    private val _confirmSeconds = MutableStateFlow(AppConfig.CAST_CONFIRM_COUNTDOWN_S)
    val confirmSeconds: StateFlow<Int> = _confirmSeconds

    private val startHint = AtomicReference<CastStartHint?>(null)

    val isReceiving: Boolean get() = _activeCastId.value != null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { loop() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _polling.value = false
    }

    private suspend fun loop() {
        _polling.value = true
        // 启动先注册本机 + 拉一次配置（确认倒计时以服务端为准）
        runCatching { deviceRepo.registerSelf() }
        refreshConfig()

        var backoff = AppConfig.CAST_RECEIVE_POLL_MS
        // loop() 是普通成员挂起函数（不是 CoroutineScope 接收者），因此用 currentCoroutineContext() 判活
        while (currentCoroutineContext().isActive) {
            val deviceId = deviceRepo.deviceId()
            when (val r = safeApiCall(gson) { api.castPoll(deviceId) }) {
                is ApiResult.Ok -> {
                    backoff = AppConfig.CAST_RECEIVE_POLL_MS
                    _confirmSeconds.value = r.data.confirmSeconds.coerceIn(3, 15)
                    val p = r.data.pending
                    when {
                        // 同一请求在 PENDING 期间会被每轮重复下发：只在 id 变化时更新，避免弹窗倒计时被重置
                        p != null && _pending.value?.id != p.id -> _pending.value = p
                        // 本轮没有 pending 而弹窗还开着 = 该请求已被判过期 / 被抢占 / 已决策，关掉弹窗
                        p == null && _pending.value != null -> _pending.value = null
                    }
                    if (r.data.cancelledIds.isNotEmpty()) handleCancelled(r.data.cancelledIds)
                }

                is ApiResult.Err -> {
                    // 401：会话失效由 AuthInterceptor 统一处理（这里只退避，等会话恢复）
                    // 404：设备未注册（换服务器 / 清数据后常见）→ 注册一次，下一轮正常
                    // 429：轮询过快（服务端护栏 1s）→ 按服务端节奏退避
                    when (r.code) {
                        404 -> runCatching { deviceRepo.registerSelf() }
                        429 -> backoff = (backoff * 2).coerceAtMost(10_000L)
                        else -> backoff = (backoff * 2).coerceAtMost(15_000L)
                    }
                }
            }
            delay(backoff)
        }
        _polling.value = false
    }

    private fun handleCancelled(ids: List<Long>) {
        _pending.value?.let { if (ids.contains(it.id)) _pending.value = null }
        _activeCastId.value?.let { id ->
            if (ids.contains(id)) {
                _activeCastId.value = null
                _activeSender.value = null
                _activeCastKey.value = null
                startHint.set(null)
                _stopRequested.tryEmit(id)
                // 本机自己退出播放页时已先清掉 activeCastId，不会走到这里 → 这条提示只针对"别人替我停的"
                _notice.value = "投屏已结束：发起端已取消，或被同账号其它设备接管"
            }
        }
    }

    /** 拉投屏配置（确认秒数）；失败保持上一次的值，不打扰用户 */
    suspend fun refreshConfig() {
        val r = runCatching { safeApiCall(gson) { api.castConfig() } }.getOrNull()
        if (r is ApiResult.Ok) {
            _confirmSeconds.value = r.data.confirmSeconds.coerceIn(3, 15)
        }
    }

    /** 点「是」或倒计时归零（reason 落库，便于服务端与排查对齐） */
    fun accept(reason: String) {
        val p = _pending.value ?: return
        _pending.value = null
        scope.launch {
            when (val r = safeApiCall(gson) { api.castAccept(CastAcceptReq(p.id, reason)) }) {
                is ApiResult.Ok -> {
                    _activeCastId.value = p.id
                    _activeSender.value = p.senderName?.takeIf { it.isNotBlank() } ?: "其它设备"
                    _activeCastKey.value = r.data.movieId to r.data.videoId
                    startHint.set(
                        CastStartHint(
                            movieId = r.data.movieId,
                            videoId = r.data.videoId,
                            positionMs = r.data.positionMs,
                            // 只给 15s 有效期：栈顶已是同一集时 navigatePlay 的 launchSingleTop 不会新建
                            // PlayerViewModel，这条提示没人取用；不留着让下次正常点开时被莫名 seek
                            untilMs = System.currentTimeMillis() + 15_000L
                        )
                    )
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

    /** 点「否」或按返回键（幂等） */
    fun reject() {
        val p = _pending.value ?: return
        _pending.value = null
        scope.launch {
            runCatching { safeApiCall(gson) { api.castReject(CastIdReq(p.id, "user-no")) } }
        }
    }

    /** 播放页退出：通知服务端结束投屏（幂等），并清本地"正在接收"标记 */
    fun cancelActiveCast(reason: String = "mobile-exit") {
        val id = _activeCastId.value ?: return
        _activeCastId.value = null
        _activeSender.value = null
        _activeCastKey.value = null
        startHint.set(null)
        scope.launch {
            runCatching { safeApiCall(gson) { api.castCancel(CastIdReq(id, reason)) } }
        }
    }

    /** 投屏携带的起播位置：命中 (movieId, videoId) 才返回并消费掉，避免下次正常点开也跳过去 */
    fun takeCastStartMs(movieId: Long, videoId: Long): Long? {
        val h = startHint.get() ?: return null
        if (h.movieId != movieId || h.videoId != videoId) return null
        if (h.untilMs < System.currentTimeMillis()) {
            startHint.compareAndSet(h, null)
            return null
        }
        startHint.compareAndSet(h, null)
        return h.positionMs
    }

    fun consumeLaunch() {
        _launch.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** 登出 / 会话失效：不把上一账号的接收状态带过去 */
    fun clear() {
        _pending.value = null
        _launch.value = null
        _activeCastId.value = null
        _activeSender.value = null
        _activeCastKey.value = null
        _notice.value = null
        startHint.set(null)
        stop()
    }
}
