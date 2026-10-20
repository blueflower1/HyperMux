@file:OptIn(UnstableApi::class)

package com.hypermux.tv.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.hypermux.tv.ui.common.fmtDurationMs
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.tv.tvClickable
import com.hypermux.tv.ui.common.HyperButton
import com.hypermux.tv.ui.common.HyperButtonStyle
import com.hypermux.tv.ui.common.HyperDialog
import com.hypermux.tv.ui.common.HyperIconButton
import com.hypermux.tv.ui.common.TvSidePanel
import com.hypermux.tv.ui.tv.TvAutoFocus
import com.hypermux.tv.ui.tv.tvFocusable
import kotlinx.coroutines.delay
import kotlin.math.abs
import java.util.Locale

/* =========================================================================
 * 播放器（TV 版，48-tv-app-detailed.md §9）
 *
 * 与手机端的根本差异：**触屏手势全部删除**，改 D-pad / 媒体键驱动。
 *   删除：GestureLayer（横滑 seek / 竖滑亮度音量 / 双击播放暂停 / 长按倍速）、GestureOsdOverlay、
 *         竖屏控制层 PortraitControlOverlay、锁屏按钮（TV 无遮挡风险）、显式全屏切换（TV 永远全屏横屏）
 *   保留：PlayerController（FFmpeg 软解兜底 + 硬解优先 + ±10s）、续播/连播/断点/错误分类/首帧看门狗、
 *         HyperSpinner 自绘缓冲指示器（350ms 防抖）
 *
 * 键位（§9.4，控制条隐藏 / 显示两态）：
 *   OK         隐藏时唤出控制条；显示时执行当前焦点按钮
 *   LEFT/RIGHT 隐藏时 seek ∓10s（按 repeatCount 节流 200ms）；显示时在按钮间移动焦点（焦点在进度条上时 ∓10s）
 *   UP         唤出顶部信息栏（5s 自动收）
 *   DOWN       唤出选集侧栏
 *   MENU       倍速菜单（替代手机端"长按 2x"）
 *   BACK       收起控制条/侧栏；都没有则退出播放页（先即时上报断点）
 *   媒体键     MEDIA_PLAY_PAUSE ↔ 播放/暂停；NEXT/PREVIOUS ↔ 上下集；FF/REW ↔ ∓10s
 *
 * 焦点策略：全屏根节点是**唯一常驻焦点**（保证控制条隐藏时左右键仍能 seek）；
 * 控制条唤出后才把焦点交给按钮，4s 无操作自动收起并把焦点还给根节点。
 * ========================================================================= */

/** 播放进度的可观察持有者：500ms 轮询写入，只有读它的子树（控制条）重组，不拖累选集网格 */
private class PlaybackProgressState {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
}

/** 重复键节流闸门：首帧放行，长按按 [SEEK_REPEAT_GATE_MS] 节流（否则 OS 重复率会把 seek 打成连发） */
private class SeekGate {
    private var lastAt = 0L
    fun allow(nowMs: Long, repeatCount: Int): Boolean {
        if (repeatCount <= 0) {
            lastAt = nowMs
            return true
        }
        if (nowMs - lastAt >= SEEK_REPEAT_GATE_MS) {
            lastAt = nowMs
            return true
        }
        return false
    }
}

private const val SEEK_REPEAT_GATE_MS = 200L
private const val SEEK_STEP_MS = 10_000L
/** 控制条/信息栏自动收起时间（§9.2：控制条 4s、信息栏 5s） */
private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val INFO_TIMEOUT_MS = 5_000L
/** 首帧看门狗：有播放意图但 8s 内没有首帧 → 给一条非阻塞提示 */
private const val FIRST_FRAME_WATCHDOG_MS = 8_000L
/** 连播倒计时（§9.6） */
private const val AUTO_NEXT_COUNTDOWN_MS = 5_000L

private val PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

@Composable
fun PlayerScreen(
    navController: NavController,
    movieId: Long,
    videoId: Long
) {
    val vm: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val activity = context as? Activity

    // 播放器单实例（§6.7：切集 = 换 MediaItem，不重建）
    val controller = remember { PlayerController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // ---- 全屏横屏 + 屏幕常亮（§9.2 / §9.4）----
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insets = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- 状态 ----
    val playUrl by vm.playUrl.collectAsStateWithLifecycle(initialValue = null)
    val isLoadingUrl by vm.isLoadingUrl.collectAsStateWithLifecycle(initialValue = true)
    val urlError by vm.urlError.collectAsStateWithLifecycle(initialValue = null)
    val autoNext by vm.autoNext.collectAsStateWithLifecycle(initialValue = null)
    val resumeMs by vm.resumeMs.collectAsStateWithLifecycle(initialValue = 0L)
    val targetVideoId by vm.targetVideoId.collectAsStateWithLifecycle(initialValue = videoId)
    val seasons by vm.seasons.collectAsStateWithLifecycle(initialValue = null)
    val movie by vm.movie.collectAsStateWithLifecycle(initialValue = null)
    val rememberSpeed by vm.rememberSpeed.collectAsStateWithLifecycle(initialValue = true)
    val lastSpeed by vm.lastSpeed.collectAsStateWithLifecycle(initialValue = 1f)

    var controlsVisible by remember { mutableStateOf(true) }
    var infoVisible by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedTouched by remember { mutableStateOf(false) }
    /** 任何交互自增：让自动收起计时重新开始 */
    var controlTick by remember { mutableIntStateOf(0) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var resumeHint by remember { mutableStateOf<String?>(null) }
    // 防误触退出：第一次 BACK 只给提示，2s 内再按一次才真正退出
    var exitHint by remember { mutableStateOf(false) }
    var lastBackAt by remember { mutableStateOf(0L) }
    var episodeFinished by remember { mutableStateOf(false) }

    val uiPlaying = remember { mutableStateOf(false) }
    val buffering = remember { mutableStateOf(false) }
    var hasResumed by remember { mutableStateOf(false) }
    val resumeMsState = rememberUpdatedState(resumeMs)
    // 续播位置只对"入口那一集"有效：同屏切集后 resumeMs 仍是旧集的位置
    val entryEpisodeResumeState = rememberUpdatedState(targetVideoId == videoId)

    var playbackError by remember { mutableStateOf<PlaybackException?>(null) }
    var errorAutoRetryIn by remember { mutableIntStateOf(-1) }
    var audioDisabledRetry by remember { mutableStateOf(false) }
    var softDecodeHint by remember { mutableStateOf(false) }
    var firstFrameRendered by remember { mutableStateOf(false) }
    var noFrameHint by remember { mutableStateOf(false) }

    val progress = remember { PlaybackProgressState() }
    val seekGate = remember { SeekGate() }
    val rootRequester = remember { FocusRequester() }
    val playPauseRequester = remember { FocusRequester() }
    val drawerState = rememberLazyGridState()

    // 切集：复位一次性状态
    LaunchedEffect(targetVideoId) {
        hasResumed = false
        playbackError = null
        errorAutoRetryIn = -1
        audioDisabledRetry = false
        softDecodeHint = false
        firstFrameRendered = false
        noFrameHint = false
        episodeFinished = false
        showEpisodes = false
        showSpeed = false
    }

    // ---- 播放源就绪 → 装配 MediaItem（§6.2）----
    LaunchedEffect(playUrl) {
        val pu = playUrl ?: return@LaunchedEffect
        controller.applyHeaders(pu.headers)
        controller.play(MediaItem.Builder().setUri(pu.url).build())
    }

    // ---- 「记忆上次倍速」：进页面把上次倍速套到 player ----
    LaunchedEffect(rememberSpeed, lastSpeed) {
        if (rememberSpeed && !speedTouched) {
            speed = lastSpeed
            controller.player.setPlaybackSpeed(lastSpeed)
        }
    }

    // ---- 播放事件：续播 seek / 播完 / 错误 / 缓冲 ----
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == ExoPlayer.STATE_READY && !hasResumed &&
                    resumeMsState.value > 0 && entryEpisodeResumeState.value
                ) {
                    hasResumed = true
                    controller.player.seekTo(resumeMsState.value)
                    resumeHint = "已从 ${fmtDurationMs(resumeMsState.value)} 继续播放"
                }
                if (state == ExoPlayer.STATE_ENDED) {
                    vm.onEpisodeEnded(controller.player.currentPosition, controller.player.duration)
                    // VM 只在"还有下一集"时给出 autoNext；没有下一集就是末集
                    episodeFinished = vm.nextVideo() == null
                }
                buffering.value =
                    state == ExoPlayer.STATE_BUFFERING && controller.player.playWhenReady
                uiPlaying.value = controller.player.playWhenReady
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                uiPlaying.value = playWhenReady
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                noFrameHint = false
            }

            override fun onPlayerError(error: PlaybackException) {
                // 音轨解码失败容错（TV 盒子尤其常见：DTS/TrueHD 音轨无硬解会让整片报错）：
                // 先禁用音轨 → prepare 重试一次（静音播放，画面照常）；只重试一次防死循环
                val decoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                val audioEnabled = !controller.player.trackSelectionParameters.disabledTrackTypes
                    .contains(C.TRACK_TYPE_AUDIO)
                if (decoderError && audioEnabled && !audioDisabledRetry) {
                    audioDisabledRetry = true
                    controller.player.trackSelectionParameters =
                        controller.player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                    softDecodeHint = true
                    controller.player.prepare()
                    return
                }
                playbackError = error
            }
        }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }

    // ---- 软解提示（真正落到 FFmpeg 软解时给一次性轻提示，不阻塞操作）----
    DisposableEffect(Unit) {
        val listener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (decoderName.contains("ffmpeg", ignoreCase = true)) {
                    softDecodeHint = true
                }
            }
        }
        controller.player.addAnalyticsListener(listener)
        onDispose { controller.player.removeAnalyticsListener(listener) }
    }

    // ---- 首帧看门狗：有播放意图但迟迟没有首帧（4K/AV1 软解过慢）→ 非阻塞提示 ----
    LaunchedEffect(uiPlaying.value, firstFrameRendered, targetVideoId) {
        if (!uiPlaying.value || firstFrameRendered) {
            noFrameHint = false
            return@LaunchedEffect
        }
        delay(FIRST_FRAME_WATCHDOG_MS)
        if (!firstFrameRendered && uiPlaying.value) noFrameHint = true
    }

    // ---- 进度轮询（500ms）：驱动进度条/时间；只在控制条子树读，避免整页重组 ----
    LaunchedEffect(targetVideoId) {
        while (true) {
            runCatching {
                val p = controller.player
                progress.position = p.currentPosition.coerceAtLeast(0L)
                progress.duration = p.duration.coerceAtLeast(0L)
                progress.buffered = p.bufferedPosition.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // ---- 断点上报：播放中 10s 节流（§7：position<3s 不上报由 VM 把守）----
    LaunchedEffect(targetVideoId) {
        while (true) {
            delay(10_000)
            val p = controller.player
            if (p.playWhenReady && p.currentPosition > 0) {
                vm.reportProgress(p.currentPosition, p.duration.coerceAtLeast(0L))
            }
        }
    }

    /** 退出/暂停时的即时上报（§7：暂停 / 离开页即时） */
    fun reportNow() {
        runCatching {
            val p = controller.player
            if (p.currentPosition > 0) {
                vm.reportProgress(p.currentPosition, p.duration.coerceAtLeast(0L))
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { reportNow() }
    }

    // ---- 错误自动重试 ×1（4s 后，§6.5：仅 NETWORK/GENERIC 自动重试）----
    val classified = playbackError?.let { classifyPlaybackError(it) }
    LaunchedEffect(playbackError) {
        val ce = classified ?: return@LaunchedEffect
        if (!ce.autoRetry) return@LaunchedEffect
        errorAutoRetryIn = 4
        while (errorAutoRetryIn > 0) {
            delay(1_000)
            errorAutoRetryIn -= 1
        }
        playbackError = null
        controller.retry()
    }

    // ---- 连播倒计时（§9.6：期间任意键取消）----
    var autoNextLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(autoNext) {
        if (autoNext == null) {
            autoNextLeft = 0
            return@LaunchedEffect
        }
        autoNextLeft = (AUTO_NEXT_COUNTDOWN_MS / 1000).toInt()
        while (autoNextLeft > 0) {
            delay(1_000)
            autoNextLeft -= 1
        }
        vm.playNextEpisode()
    }

    // ---- 控制条/信息栏自动收起 ----
    LaunchedEffect(controlsVisible, controlTick, showEpisodes, showSpeed) {
        if (!controlsVisible || showEpisodes || showSpeed) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
        rootRequester.requestFocus()
    }
    LaunchedEffect(infoVisible, controlTick) {
        if (!infoVisible) return@LaunchedEffect
        delay(INFO_TIMEOUT_MS)
        infoVisible = false
    }
    LaunchedEffect(seekHint) {
        if (seekHint == null) return@LaunchedEffect
        delay(1_200)
        seekHint = null
    }
    LaunchedEffect(resumeHint) {
        if (resumeHint == null) return@LaunchedEffect
        delay(3_000)
        resumeHint = null
    }
    // 退出提示超时自动消失（窗口比 2s 判定略长，保证提示覆盖整个可确认窗口）
    LaunchedEffect(exitHint) {
        if (!exitHint) return@LaunchedEffect
        delay(2_400)
        exitHint = false
    }
    LaunchedEffect(softDecodeHint) {
        if (!softDecodeHint) return@LaunchedEffect
        delay(4_000)
        softDecodeHint = false
    }

    // ---- 进入页面：焦点给根节点（保证 D-pad 一定有人收）----
    TvAutoFocus(rootRequester)

    /** 退出播放页（先即时上报断点，§9.4） */
    fun exitPlayer() {
        reportNow()
        navController.popBackStack()
    }

    /** seek ∓10s + 中央提示（按 repeatCount 节流） */
    fun seekBy(deltaMs: Long, repeatCount: Int) {
        val now = System.currentTimeMillis()
        if (!seekGate.allow(now, repeatCount)) return
        val p = controller.player
        val duration = p.duration.coerceAtLeast(0L)
        val target = (p.currentPosition + deltaMs).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        p.seekTo(target)
        progress.position = target
        seekHint = buildString {
            append(if (deltaMs > 0) "快进 10s" else "快退 10s")
            append("  ·  ")
            append(fmtClock(target))
            if (duration > 0) append(" / ${fmtClock(duration)}")
        }
    }

    /** 控制条显示时：把焦点交给主按钮（播放/暂停）——由下方 TvAutoFocus 完成 */
    fun showControls() {
        controlsVisible = true
        controlTick++
    }

    fun togglePlayPause() {
        val p = controller.player
        val next = !p.playWhenReady
        p.playWhenReady = next
        uiPlaying.value = next
        if (!next) reportNow()
    }

    /** 上一集：已在第一集时回到本集开头 */
    fun playPreviousEpisode() {
        val flat = seasons?.seasons?.flatMap { it.videos }.orEmpty()
        val idx = flat.indexOfFirst { it.id == targetVideoId }
        if (idx > 0) {
            vm.loadPlayUrl(flat[idx - 1].id)
        } else {
            controller.player.seekTo(0)
        }
    }

    fun playNextEpisode() {
        val next = vm.nextVideo() ?: return
        vm.loadPlayUrl(next.id)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootRequester)
            // 根节点用 onKeyEvent（冒泡阶段）：控制条上的按钮先消费 OK/方向键，
            // 只有没人消费的键才落回这里 → "控制条显示时左走焦点、隐藏时左=快退"自动成立
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                controlTick++
                val repeat = e.nativeKeyEvent.repeatCount
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (!controlsVisible) {
                            showControls()
                        } else {
                            togglePlayPause()
                        }
                        true
                    }

                    Key.DirectionLeft -> {
                        if (controlsVisible) false else {
                            seekBy(-SEEK_STEP_MS, repeat); true
                        }
                    }

                    Key.DirectionRight -> {
                        if (controlsVisible) false else {
                            seekBy(SEEK_STEP_MS, repeat); true
                        }
                    }

                    Key.DirectionUp -> {
                        if (controlsVisible) false else {
                            infoVisible = true; true
                        }
                    }

                    Key.DirectionDown -> {
                        if (controlsVisible) false else {
                            showEpisodes = true
                            true
                        }
                    }

                    Key.Menu -> {
                        // MENU = 倍速菜单（替代手机端长按 2x，§9.4）
                        showSpeed = true
                        true
                    }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlayPause(); true
                    }

                    Key.MediaFastForward -> {
                        seekBy(SEEK_STEP_MS, 0); true
                    }

                    Key.MediaRewind -> {
                        seekBy(-SEEK_STEP_MS, 0); true
                    }

                    Key.MediaNext -> {
                        playNextEpisode(); true
                    }

                    Key.MediaPrevious -> {
                        playPreviousEpisode(); true
                    }

                    Key.Back -> {
                        // BACK：先收控制条/信息栏，再退出播放页（§9.4）；
                        // 退出这一步做**双击确认**（防遥控器误触直接退出播放）
                        if (controlsVisible || infoVisible) {
                            controlsVisible = false
                            infoVisible = false
                            exitHint = false
                            rootRequester.requestFocus()
                        } else {
                            val now = System.currentTimeMillis()
                            if (exitHint && now - lastBackAt <= 2_000L) {
                                exitHint = false
                                exitPlayer()
                            } else {
                                lastBackAt = now
                                exitHint = true
                            }
                        }
                        true
                    }

                    else -> false
                }
            }
            // focusable 放在 onKeyEvent 之后（内层）：焦点目标先处理方向键做焦点移动，
            // 移不动（控制条隐藏/到边界）时才冒泡到上面的 onKeyEvent → 变成 seek
            .focusable()
    ) {
        // ---------- 视频画面 ----------
        PlayerSurface(controller = controller, modifier = Modifier.fillMaxSize())

        // ---------- 缓冲指示器（350ms 防抖）----------
        PlayerBufferingOverlay(buffering = buffering.value, modifier = Modifier.fillMaxSize())

        // ---------- 顶部信息栏（UP 唤出）----------
        AnimatedVisibility(
            visible = infoVisible,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            TopInfoBar(
                title = movie?.title ?: "播放中",
                episodeLabel = currentEpisodeLabel(seasons, targetVideoId),
                categoryName = movie?.category?.name,
                onBack = { exitPlayer() }
            )
        }

        // ---------- 错误面板（网络/解码/其他分类 + 手动重试）----------
        classified?.let { ce ->
            if (errorAutoRetryIn < 0) {
                ErrorOverlay(
                    message = ce.message,
                    canRetry = true,
                    onRetry = {
                        playbackError = null
                        controller.retry()
                    },
                    onExit = { exitPlayer() }
                )
            }
        }
        if (errorAutoRetryIn >= 0) {
            RetryOverlay(seconds = errorAutoRetryIn, message = classified?.message.orEmpty())
        }
        urlError?.let { msg ->
            ErrorOverlay(
                message = msg,
                canRetry = true,
                onRetry = { vm.retryPlayUrl() },
                onExit = { exitPlayer() }
            )
        }
        if (isLoadingUrl) LoadingOverlay(text = "正在获取播放地址…")

        // ---------- 自动连播倒计时 ----------
        if (autoNext != null && autoNextLeft > 0) {
            AutoNextOverlay(
                nextLabel = "第 ${autoNext!!.nextVideo.episodeNo} 集",
                seconds = autoNextLeft,
                onPlayNow = { vm.playNextEpisode() },
                onCancel = { vm.cancelAutoNext() }
            )
        }

        // ---------- 末集播完 ----------
        if (episodeFinished) {
            ErrorOverlay(
                message = "本集已播完（已是最后一集）",
                canRetry = false,
                onRetry = {},
                onExit = { exitPlayer() }
            )
        }

        // ---------- 一次性轻提示（续播 / 首帧看门狗 / 软解）----------
        val hintText = when {
            resumeHint != null -> resumeHint
            noFrameHint -> "画面尚未输出：该片源可能需要软解或设备不支持，可返回换 H.264 源"
            softDecodeHint -> "正在使用软件解码（4K/10bit 可能卡顿），建议换 H.264 源"
            else -> null
        }
        AnimatedVisibility(
            visible = hintText != null,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.BASE)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
        ) {
            CaptionCapsule(hintText ?: "")
        }

        // ---------- 中央 seek 提示 ----------
        AnimatedVisibility(
            visible = seekHint != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            CaptionCapsule(seekHint ?: "", strong = true)
        }

        // ---------- 双击退出确认提示（BACK 防误触）----------
        AnimatedVisibility(
            visible = exitHint,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        ) {
            CaptionCapsule("再按一次「返回」退出播放", strong = true)
        }

        // ---------- 底部控制条（OK 唤出 / 4s 自动收）----------
        AnimatedVisibility(
            visible = controlsVisible && !episodeFinished && classified == null && urlError == null,
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            TvControlBar(
                progress = progress,
                playing = uiPlaying.value,
                speedLabel = speedText(speed),
                canNextEpisode = vm.nextVideo() != null,
                playPauseRequester = playPauseRequester,
                onSeekBack = { seekBy(-SEEK_STEP_MS, 0) },
                onSeekForward = { seekBy(SEEK_STEP_MS, 0) },
                onTogglePlayPause = { togglePlayPause() },
                onNextEpisode = { playNextEpisode() },
                onSpeed = { showSpeed = true },
                onEpisodes = { showEpisodes = true },
                onInteract = { controlTick++ }
            )
        }
    }

    // 焦点：进页面时控制条是显示的 → 焦点给「播放/暂停」；控制条每次唤出也落在这里。
    // 控制条隐藏后由上面的自动收起逻辑把焦点还给根节点（保证左右键 = seek）。
    TvAutoFocus(playPauseRequester, ready = controlsVisible)

    // ---------- 选集侧栏（DOWN 唤出；Dialog 自带焦点，关闭后焦点自动回根节点）----------
    if (showEpisodes) {
        TvSidePanel(
            onDismissRequest = {
                showEpisodes = false
                rootRequester.requestFocus()
            },
            width = 720.dp
        ) {
            EpisodeDrawerContent(
                seasons = seasons?.seasons.orEmpty(),
                currentVideoId = targetVideoId,
                gridState = drawerState,
                onPick = { id ->
                    showEpisodes = false
                    vm.loadPlayUrl(id)
                    rootRequester.requestFocus()
                }
            )
        }
    }

    // ---------- 倍速菜单（MENU 唤出）----------
    if (showSpeed) {
        HyperDialog(
            onDismissRequest = {
                showSpeed = false
                rootRequester.requestFocus()
            },
            title = {
                Text(
                    "播放倍速",
                    fontSize = TvType.section,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PLAYBACK_SPEEDS.forEach { v ->
                        val selected = abs(v - speed) < 0.001f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .tvClickable(shape = RoundedCornerShape(12.dp)) {
                                    speed = v
                                    speedTouched = true
                                    controller.player.setPlaybackSpeed(v)
                                    vm.setLastSpeed(v)
                                    showSpeed = false
                                }
                                .padding(horizontal = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = speedText(v),
                                fontSize = TvType.body,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Text(
                                    "当前",
                                    fontSize = TvType.meta,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                HyperButton(
                    text = "关闭",
                    onClick = {
                        showSpeed = false
                        rootRequester.requestFocus()
                    },
                    style = HyperButtonStyle.Ghost
                )
            }
        )
    }

    // ---------- 倍速菜单（MENU 唤出）----------
}

/* =========================== 画面与指示器 =========================== */

/**
 * ExoPlayer 画面：统一 `useController = false`（控制层由 Compose 自绘）；
 * `SHOW_BUFFERING_NEVER` 关掉 media3 原生缓冲转圈，改由自绘品牌绿弧线统一呈现。
 */
@Composable
private fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).also { pv ->
                pv.player = controller.player
                pv.useController = false
                pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // ★ TV 关键 ★ PlayerView 是 Android View，默认在拿到焦点时自行处理
                // D-pad / OK / 媒体键（用它自带的播放器控制层）。那样 Compose 侧
                // 的键位处理就全收不到事件了 → 明确拒绝它及其子视图获取焦点，
                // 焦点始终留在 Compose 的根节点上（§9 播放器不建 Compose 焦点树）。
                pv.isFocusable = false
                pv.isFocusableInTouchMode = false
                pv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { pv -> pv.player = controller.player },
        modifier = modifier
    )
}

/** 自绘品牌绿弧线缓冲指示器（与手机端同一套动画，TV 上放大到 56dp） */
@Composable
private fun HyperSpinner(size: Dp, strokeWidth: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "playerSpinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinnerRotation"
    )
    val sweep by transition.animateFloat(
        initialValue = 45f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "spinnerSweep"
    )
    Canvas(modifier = Modifier.size(size)) {
        val cs = this.size
        val stroke = strokeWidth.toPx()
        val radius = (minOf(cs.width, cs.height) / 2f - stroke / 2f).coerceAtLeast(0f)
        val center = Offset(cs.width / 2f, cs.height / 2f)
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f)
        )
    }
}

/**
 * 缓冲浮层：连续缓冲 ≥350ms 才显示（ExoPlayer 在网络流上会 READY↔BUFFERING 抖动，
 * 直接跟着状态走会一闪一闪）；恢复播放立即隐藏。
 */
@Composable
private fun PlayerBufferingOverlay(buffering: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        if (buffering) {
            delay(350)
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HyperSpinner(size = 56.dp, strokeWidth = 4.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/* =========================== 控制条 =========================== */

/**
 * 底部控制条（TV）：进度条 + 时间 + [◀◀10s][播放/暂停][10s▶▶][下一集][倍速][选集]。
 * 所有按钮都是真正的焦点目标（OK 执行）；进度条也可聚焦，聚焦时 LEFT/RIGHT = ∓10s。
 */
@Composable
private fun TvControlBar(
    progress: PlaybackProgressState,
    playing: Boolean,
    speedLabel: String,
    canNextEpisode: Boolean,
    playPauseRequester: FocusRequester,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextEpisode: () -> Unit,
    onSpeed: () -> Unit,
    onEpisodes: () -> Unit,
    onInteract: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x00000000), Color(0xB3000000), Color(0xE6000000))
                )
            )
            .padding(horizontal = Spacing.page, vertical = Spacing.lg)
    ) {
        // ---- 进度条 + 时间 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fmtClock(progress.position),
                fontSize = TvType.meta,
                color = Color.White,
                modifier = Modifier.width(96.dp)
            )
            TvSeekBar(
                position = progress.position,
                duration = progress.duration,
                buffered = progress.buffered,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = fmtClock(progress.duration),
                fontSize = TvType.meta,
                color = Color(0xB3FFFFFF),
                modifier = Modifier
                    .width(96.dp)
                    .padding(start = Spacing.md)
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // ---- 操作按钮 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HyperIconButton(
                icon = PlayerIcons.FastForward,
                contentDescription = "快退 10 秒",
                onClick = {
                    onInteract()
                    onSeekBack()
                },
                size = 64.dp,
                iconSize = 32.dp,
                tint = Color.White
            )
            HyperIconButton(
                icon = if (playing) PlayerIcons.Pause else PlayerIcons.Play,
                contentDescription = if (playing) "暂停" else "播放",
                onClick = {
                    onInteract()
                    onTogglePlayPause()
                },
                size = 80.dp,
                iconSize = 44.dp,
                tint = Color.White,
                focusRequester = playPauseRequester
            )
            HyperIconButton(
                icon = PlayerIcons.FastForward,
                contentDescription = "快进 10 秒",
                onClick = {
                    onInteract()
                    onSeekForward()
                },
                size = 64.dp,
                iconSize = 32.dp,
                tint = Color.White
            )
            Spacer(Modifier.weight(1f))
            HyperButton(
                text = "下一集",
                onClick = {
                    onInteract()
                    onNextEpisode()
                },
                style = HyperButtonStyle.Secondary,
                small = true,
                enabled = canNextEpisode
            )
            HyperButton(
                text = speedLabel,
                onClick = {
                    onInteract()
                    onSpeed()
                },
                style = HyperButtonStyle.Secondary,
                small = true
            )
            HyperButton(
                text = "选集",
                onClick = {
                    onInteract()
                    onEpisodes()
                },
                style = HyperButtonStyle.Primary,
                small = true
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "OK 播放/暂停 · ◀▶ 快退/快进 10s · ↑ 信息栏 · ↓ 选集 · MENU 倍速 · BACK 返回",
            fontSize = TvType.badge,
            color = Color(0x99FFFFFF)
        )
    }
}

/**
 * TV 进度条：纯展示的进度轨（played / buffered）+ 一个可聚焦的"游标区"。
 *
 * 为什么不做成可拖动的滑块：TV 上没有触摸，拖动只能靠左右键，而左右键的语义
 * 已经在"∓10s 步进"上（§9.4），做成拖动反而让"按一下跳多久"变得不可预测。
 * 因此这里聚焦后 LEFT/RIGHT = ∓10s，进度条只负责"看得见"。
 */
@Composable
private fun TvSeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val total = duration.coerceAtLeast(1L)
    val playedFraction = (position.toFloat() / total).coerceIn(0f, 1f)
    val bufferedFraction = (buffered.toFloat() / total).coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .keySeekBar(onSeekBack = onSeekBack, onSeekForward = onSeekForward)
            .tvFocusable(shape = shape, focusedScale = 1f)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .clip(shape)
                .background(Color(0x4DFFFFFF))
        ) {
            // 缓冲
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(Color(0x66FFFFFF))
            )
            // 已播
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)
                        )
                    )
            )
        }
        // 游标
        Box(
            modifier = Modifier
                .fillMaxWidth(playedFraction)
                .height(1.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .size(if (focused) 20.dp else 14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 进度条在聚焦态接受 LEFT/RIGHT = ∓10s 的键盘处理 */
private fun Modifier.keySeekBar(
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit
): Modifier = this.onKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (e.key) {
        Key.DirectionLeft -> {
            onSeekBack(); true
        }

        Key.DirectionRight -> {
            onSeekForward(); true
        }

        else -> false
    }
}

/* =========================== 覆盖层 =========================== */

/** 顶部信息栏：返回按钮（可聚焦）+ 片名 + 集号 + 分类 + 评分 */
@Composable
private fun TopInfoBar(
    title: String,
    episodeLabel: String?,
    categoryName: String?,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xE6000000), Color(0x99000000), Color(0x00000000))
                )
            )
            .padding(horizontal = Spacing.page, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HyperIconButton(
            icon = PlayerIcons.Back,
            contentDescription = "返回",
            onClick = onBack,
            size = 56.dp,
            iconSize = 30.dp,
            tint = Color.White
        )
        Spacer(Modifier.width(Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(episodeLabel, categoryName)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ")
                    ?: "全屏播放",
                fontSize = TvType.meta,
                color = Color(0xB3FFFFFF),
                maxLines = 1
            )
        }
        Text(
            text = "按 BACK 返回",
            fontSize = TvType.badge,
            color = Color(0x80FFFFFF)
        )
    }
}

/** 居中胶囊文案（seek 提示 / 一次性提示） */
@Composable
private fun CaptionCapsule(text: String, strong: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (strong) Color(0xCC000000) else Color(0x99000000))
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
    ) {
        Text(
            text = text,
            fontSize = if (strong) TvType.cardTitle else TvType.meta,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
            color = Color.White
        )
    }
}

/** 加载中（换直链/起播） */
@Composable
private fun LoadingOverlay(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HyperSpinner(size = 56.dp, strokeWidth = 4.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.lg))
            Text(text = text, fontSize = TvType.meta, color = Color(0xCCFFFFFF))
        }
    }
}

/** 自动重试倒计时浮层（§6.5：NETWORK/GENERIC 自动重试 ×1） */
@Composable
private fun RetryOverlay(seconds: Int, message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message.ifBlank { "播放出错" },
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "${seconds}s 后自动重试…",
                fontSize = TvType.meta,
                color = Color(0xCCFFFFFF)
            )
        }
    }
}

/** 错误面板：分类文案 + 重试 / 返回 */
@Composable
private fun ErrorOverlay(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.page)
        ) {
            Text(
                text = message,
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(Spacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (canRetry) {
                    HyperButton(text = "重试", onClick = onRetry)
                }
                HyperButton(text = "返回", onClick = onExit, style = HyperButtonStyle.Secondary)
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "提示：DTS / TrueHD 音轨与 HEVC / AV1 视频需要设备支持，"
                    + "必要时请在后台把片源换成 H.264（avc）",
                fontSize = TvType.badge,
                color = Color(0x99FFFFFF)
            )
        }
    }
}

/** 自动连播浮层：倒计时 + 立即播放 + 取消 */
@Composable
private fun AutoNextOverlay(
    nextLabel: String,
    seconds: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Column(
            modifier = Modifier
                .padding(end = Spacing.page, bottom = 220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6101214))
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "${seconds}s 后播放 $nextLabel",
                fontSize = TvType.body,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                HyperButton(text = "取消连播", onClick = onCancel, style = HyperButtonStyle.Ghost, small = true)
                HyperButton(text = "立即播放", onClick = onPlayNow, small = true)
            }
        }
    }
}

/* =========================== 选集侧栏 =========================== */

/**
 * 选集侧栏内容：季 chips（多季才显示）+ 集数网格。当前集用主色描边标出（铁律：只认传入的唯一 id）。
 * 打开时自动滚到当前集（`scrollToItem`），避免长剧每次都要翻很多屏。
 */
@Composable
private fun EpisodeDrawerContent(
    seasons: List<com.hypermux.tv.data.model.SeasonVo>,
    currentVideoId: Long,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onPick: (Long) -> Unit
) {
    var seasonNo by remember {
        mutableIntStateOf(
            seasons.firstOrNull { s -> s.videos.any { it.id == currentVideoId } }?.seasonNo
                ?: seasons.firstOrNull()?.seasonNo ?: 1
        )
    }
    val current = seasons.firstOrNull { it.seasonNo == seasonNo }
    val videos = current?.videos.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "选集",
            fontSize = TvType.section,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (seasons.size > 1) {
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                seasons.forEach { s ->
                    HyperButton(
                        text = "第${s.seasonNo}季",
                        onClick = { seasonNo = s.seasonNo },
                        style = if (s.seasonNo == seasonNo) HyperButtonStyle.Primary
                        else HyperButtonStyle.Secondary,
                        small = true
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        val currentIndex = videos.indexOfFirst { it.id == currentVideoId }
        LaunchedEffect(seasonNo, currentIndex) {
            if (currentIndex >= 0) gridState.scrollToItem(currentIndex)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)
        ) {
            items(videos, key = { it.id }) { v ->
                val isCurrent = v.id == currentVideoId
                val finished = v.progress?.finished == 1
                val shape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .aspectRatio(1.35f)
                        .clip(shape)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .tvClickable(shape = shape) { onPick(v.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${v.episodeNo}",
                        fontSize = TvType.body,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.primary
                            finished -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/* =========================== 工具 =========================== */

/** 当前集文案："S1 第6集"（多季）或 "第6集"（单季） */
@Composable
private fun currentEpisodeLabel(
    seasons: com.hypermux.tv.data.model.SeasonsVo?,
    videoId: Long
): String? {
    val list = seasons?.seasons.orEmpty()
    val season = list.firstOrNull { s -> s.videos.any { it.id == videoId } } ?: return null
    val video = season.videos.firstOrNull { it.id == videoId } ?: return null
    return if (list.size > 1) "S${season.seasonNo} 第${video.episodeNo}集" else "第${video.episodeNo}集"
}

/** mm:ss / h:mm:ss */
private fun fmtClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 倍速文案：固定 Locale.US，避免小数点在某些语言环境变成逗号；整档位去掉小数（1x / 1.5x） */
private fun speedText(v: Float): String {
    val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    return "${s}x"
}
