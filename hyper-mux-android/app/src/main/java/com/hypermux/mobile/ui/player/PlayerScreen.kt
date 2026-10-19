package com.hypermux.mobile.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.cache.DownloadState
import com.hypermux.mobile.data.cache.MediaCacheManager
import com.hypermux.mobile.data.model.SeasonVo
import com.hypermux.mobile.data.repo.SettingsRepo
import com.hypermux.mobile.ui.common.HyperButton
import com.hypermux.mobile.ui.common.HyperButtonStyle
import com.hypermux.mobile.ui.common.HyperChip
import com.hypermux.mobile.ui.common.HyperIconButton
import com.hypermux.mobile.ui.common.SectionTitle
import com.hypermux.mobile.ui.common.bounceClick
import com.hypermux.mobile.ui.common.fmtDurationMs
import com.hypermux.mobile.ui.common.ratingText
import com.hypermux.mobile.ui.layout.LocalLayoutProfile
import com.hypermux.mobile.ui.layout.LayoutTier
import com.hypermux.mobile.ui.layout.columnsFor
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 播放进度状态持有者（position / duration / buffered）。
 *
 * 为什么是一个"可观察对象"而不是页面里的三个 `mutableLongStateOf`：
 * 进度每 500ms 刷一次，若在 `PlayerScreen` 的根作用域里读这三个值（哪怕是"读了再当参数传下去"），
 * Compose 会把**整页**（选集网格 / 季 Tab / 全部按钮树）标记为失效 → 播放中每秒 2 次整页重组，
 * 帧预算被吃掉、动画（OSD 淡入淡出、进度条）观感就会"卡"。
 * 改成把持有者对象传进控制条、由真正显示进度的浮层自己读，重组范围就收缩到"控制条 + 进度条"这一小块。
 * （[Stable] 保证传对象本身不会引起子级重组；对象引用恒定，只有读它的那一层会失效。）
 */
@Stable
private class PlaybackProgressState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
}

/**
 * 播放页（交互与观感对齐爱奇艺手机端）：
 * - **默认竖屏**：顶部返回 + 标题；16:9 播放器，控制条以**半透明渐变浮层**形式贴在播放区内部底部
 *   （竖屏**默认常显**，单击播放区可切换显隐）；下方影片信息 / 季 Tab / 选集网格；
 * - 点「全屏」才进入**横屏沉浸**（隐藏系统栏），自绘半透明渐变控制条（顶部/底部浮层）+ 锁屏，
 *   横屏播放中 4s 自动隐藏（竖屏不隐藏，避免用户找不到控件）；
 * - 手势：单击显隐控制、双击播放暂停、左右滑动 seek、上下滑动亮度/音量、长按倍速（倍速值可在「我的 → 播放」配置）；
 * - 进度条：自绘可拖拽（轨道 4dp / 拖动 6dp），已缓冲段 + 品牌绿已播放段 + 拖动时间气泡；
 * - 续播 seek、10s 断点上报、自动连播、播放错误分类面板。
 *
 * 注意（不可破坏项）：断点上报循环 / 首帧看门狗 / 解码三层兜底（在 [PlayerController]）/
 * 自动连播 / 竖屏↔横屏复用同一个 ExoPlayer 实例。
 */
@Composable
fun PlayerScreen(
    navController: NavController,
    movieId: Long,
    videoId: Long
) {
    val vm: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val activity = context as? Activity

    // 播放器单实例（§6.7：同屏切集 = 换 MediaItem，不重建）
    // 播放不写缓存（第七轮：只有显式下载落盘；已完整下载的集走 CacheDataSource 只读本地缓存）
    val controller = remember { PlayerController(context, vm.cacheManager) }
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }

    // 全屏（横屏沉浸）开关：手机默认竖屏（符合手机习惯）；
    // 平板 / 盒子这类**起始就是宽而矮**的窗口直接进沉浸 —— 竖屏那套 Column（16:9 画面条 +
    // 下方信息/选集）是按「窄而高」设计的，塞进 1280×800 时画面吃掉 720dp、标题/简介/选集
    // 只剩 80dp 被裁掉；而全屏那套（fillMaxSize 画面 + 右侧选集抽屉 + LandscapeControls）
    // 本来就是按「宽而矮」设计的。手机竖屏时 widthPixels < heightPixels → 仍为 false，行为不变。
    val startImmersive = remember {
        val dm = context.resources.displayMetrics
        dm.widthPixels > dm.heightPixels
    }
    var fullscreen by remember { mutableStateOf(startImmersive) }
    val profile = LocalLayoutProfile.current
    DisposableEffect(fullscreen, profile.tier) {
        val insetsController = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        // 方向锁只在 Compact 档施加：sw≥600dp 且 targetSdk≥31 时系统本来就忽略它，
        // 在大屏上继续声明只会得到一个「以为锁住了」的假契约（Android 16 起连豁免开关都没了）。
        val lockOrientation = profile.tier == LayoutTier.Compact
        if (fullscreen) {
            if (lockOrientation) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = if (lockOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // 退出播放页：恢复系统默认（跟随传感器）
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 退出播放页：亮度交还给系统（-1 = 跟随系统自动亮度），否则会把整个 App 锁在手动亮度上
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = -1f
                w.attributes = lp
            }
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

    var controlsVisible by remember { mutableStateOf(true) }
    /** 锁屏：隐藏全部控件，且不响应单击/双击/滑动，只保留右下角解锁按钮 */
    var locked by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    /** 本页是否已由用户手动改过速（改过就不再被"记忆上次倍速"覆盖，避免来回打脸） */
    var speedTouched by remember { mutableStateOf(false) }

    // ---- 倍速偏好（「我的 → 播放」可改） ----
    val longPressSpeed by vm.longPressSpeed.collectAsStateWithLifecycle(
        initialValue = SettingsRepo.DEFAULT_LONG_PRESS_SPEED
    )
    val rememberSpeed by vm.rememberSpeed.collectAsStateWithLifecycle(initialValue = true)
    val lastSpeed by vm.lastSpeed.collectAsStateWithLifecycle(initialValue = 1f)

    // ---- 缓存状态（「缓存」按钮 + 进度百分比） ----
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle(initialValue = emptyMap())
    // 当前集已显式下载的字节数（轮询，让「缓存」按钮图标态及时更新）
    var cachedBytes by remember { mutableLongStateOf(0L) }

    val isPlayingState = remember { mutableStateOf(false) }
    // 播放/暂停**图标**的驱动源：用 playWhenReady（用户意图），而不是 isPlaying。
    // isPlaying = playWhenReady && 已就绪 —— 网络流一进再缓冲就翻成 false，
    // 图标跟着翻会让"逼停一下"看起来像按钮动画闪烁；用 playWhenReady 后图标只在用户真的暂停/播放时才变。
    val uiPlayingState = remember { mutableStateOf(false) }
    // 是否在缓冲（驱动自绘品牌缓冲指示器，替代 media3 原生转圈）
    val bufferingState = remember { mutableStateOf(false) }
    val hasResumedState = remember { mutableStateOf(false) }
    val resumeMsState = rememberUpdatedState(resumeMs)

    var playbackError by remember { mutableStateOf<PlaybackException?>(null) }
    var autoRetryCount by remember { mutableIntStateOf(0) }
    // 音轨解码失败容错：DTS/E-AC3 等系统无解码器的音轨会让整片报错，
    // 这里禁用音轨后重试一次（只重试一次，防死循环）
    var audioDisabledRetry by remember { mutableStateOf(false) }
    // 软解提示只弹一次（4K 软解会卡，但不阻塞操作）
    var softwareDecodeHintShown by remember { mutableStateOf(false) }
    // 首帧渲染看门狗：有声音但画面不出来（AV1/HEVC 4K 软解过慢或不支持）时给出明确提示
    var firstFrameRendered by remember { mutableStateOf(false) }
    var showNoFrameHint by remember { mutableStateOf(false) }

    // 进度数据（竖屏 + 横屏共用）：装进可观察持有者，交给控制条自己读，
    // 避免 500ms 一次的刷新把整页（含选集网格）拖进重组（见 PlaybackProgressState 注释）
    val progress = remember { PlaybackProgressState() }

    // 手势 OSD（中央浮层）
    var osd by remember { mutableStateOf<GestureOsd?>(null) }
    // 用户正在拖动（进度条 / 手势 seek / 亮度音量）：期间暂停"4s 自动隐藏"，避免控件中途消失
    var interacting by remember { mutableStateOf(false) }
    // 每次交互自增：让自动隐藏计时重新开始
    var controlTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(targetVideoId) {
        hasResumedState.value = false
        playbackError = null
        autoRetryCount = 0
        audioDisabledRetry = false
        softwareDecodeHintShown = false
        // 切集时收起浮层/复位锁屏与手势态
        showEpisodes = false
        showSpeed = false
        locked = false
        interacting = false
        osd = null
    }

    // ---- 播放源就绪 → 装配 MediaItem（§6.2） ----
    // 本地缓存 URI（已完整下载的集，见 loadPlayUrl 的"本地优先"）：切到 CacheDataSource，
    // 按缓存 key 直接读下载缓存的 span 文件——不触网、断网可播、不需要防盗链请求头；
    // 其余（未下 / 部分下载）走网络流，播放不写任何缓存（第七轮）。
    LaunchedEffect(playUrl) {
        val pu = playUrl ?: return@LaunchedEffect
        val local = MediaCacheManager.isCacheUri(pu.url)
        if (local) {
            Log.i("PlayerScreen", "playing local download cache: ${pu.url}")
        } else {
            controller.applyHeaders(pu.headers)
        }
        controller.play(
            androidx.media3.common.MediaItem.Builder()
                .setUri(pu.url)
                .build(),
            fromLocalCache = local
        )
    }

    // ---- 「记忆上次倍速」：进播放页把上次倍速套到 player ----
    // lastSpeed 初值是 1.0f，DataStore 读出真实值后会再触发一次；用 speedTouched 兜住重复应用，
    // 避免"用户刚选了 3.0x、DataStore 异步回流又给改回去"这种来回打脸。
    LaunchedEffect(rememberSpeed, lastSpeed) {
        if (rememberSpeed && !speedTouched) {
            speed = lastSpeed
            controller.player.setPlaybackSpeed(lastSpeed)
        }
    }

    // ---- 已下载字节轮询：该集一旦有显式下载字节（部分/完整），按钮图标态不再变，停止轮询 ----
    LaunchedEffect(targetVideoId) {
        cachedBytes = 0L
        while (true) {
            // SimpleCache.getCachedBytes 要读缓存索引（可能落盘）：挪到 IO 线程，别占主线程
            val bytes = withContext(Dispatchers.IO) { vm.cachedBytes(targetVideoId) }
            cachedBytes = bytes
            // 一旦已有缓存字节，按钮态就不会再变（下载中的百分比由 downloadStates 驱动），无需继续轮询
            if (bytes > 0L) break
            delay(2_000)
        }
    }

    // 续播位置只对"入口那一集"有效：resumeMs 由 VM 在 init 时按入口 videoId 计算，
    // 同屏切集（选集/下一集）后它仍是旧集的位置，直接 seek 会跳到错误的进度，故加此门禁。
    val entryEpisodeResumeState = rememberUpdatedState(targetVideoId == videoId)

    // ---- 播放事件：续播 seek / 播完 / 错误 ----
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !hasResumedState.value &&
                    resumeMsState.value > 0 && entryEpisodeResumeState.value
                ) {
                    hasResumedState.value = true
                    controller.player.seekTo(resumeMsState.value)
                    Toast.makeText(
                        context,
                        "从 ${fmtDurationMs(resumeMsState.value)} 继续播放",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (state == Player.STATE_ENDED) {
                    vm.onEpisodeEnded(controller.player.currentPosition, controller.player.duration)
                }
                // media3 的 Player.Listener 没有 onIsBufferingChanged，
                // 通过 playbackState 的 BUFFERING 态 + playWhenReady 驱动自绘缓冲指示器
                bufferingState.value =
                    state == Player.STATE_BUFFERING && controller.player.playWhenReady
                // 兜底同步图标态（写同值不会触发重组）：即使 playWhenReady 的变更事件因故漏收，
                // 图标也不会停在错误的一侧
                uiPlayingState.value = controller.player.playWhenReady
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlayingState.value = playing
            }

            // 图标态与"4s 自动隐藏"都用 playWhenReady 驱动（见 uiPlayingState 注释）
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                uiPlayingState.value = playWhenReady
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                showNoFrameHint = false
            }

            override fun onPlayerError(error: PlaybackException) {
                // ③ 音轨解码失败容错：MKV 片源的 DTS / E-AC3 等音轨，系统通常没有对应解码器，
                //    一旦音轨解码器初始化失败，ExoPlayer 会让**整片**报错（连画面都出不来）。
                //    因此：当前音轨仍启用时，先禁用音轨 → 重新 prepare 重试一次（静音播放），
                //    让视频照常解码出画面；只重试一次（audioDisabledRetry）避免死循环。
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
                    Toast.makeText(
                        context,
                        "该片源音轨（DTS 等）无法解码，已静音播放",
                        Toast.LENGTH_LONG
                    ).show()
                    controller.player.prepare()
                    return
                }
                // 视频本身也解不了（重试后仍失败）→ 走原有"无法解码"错误面板
                playbackError = error
            }
        }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }

    // ---- 软解提示：真正落到软件解码（4K 会卡）时给一条一次性轻提示，不阻塞操作 ----
    DisposableEffect(Unit) {
        val listener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (!softwareDecodeHintShown && isSoftwareDecoderName(decoderName)) {
                    softwareDecodeHintShown = true
                    Toast.makeText(
                        context,
                        "正在使用软件解码，4K 可能卡顿",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        controller.player.addAnalyticsListener(listener)
        onDispose { controller.player.removeAnalyticsListener(listener) }
    }

    // ---- 断点上报循环（§7） ----
    LaunchedEffect(isPlayingState.value) {
        if (isPlayingState.value) {
            while (true) {
                delay(AppConfig.PROGRESS_REPORT_INTERVAL_MS)
                vm.reportProgress(controller.player.currentPosition, controller.player.duration)
            }
        } else {
            vm.reportProgress(controller.player.currentPosition, controller.player.duration)
        }
    }

    // ---- 进度条刷新（500ms 足够，拖动时用本地拖动值覆盖显示） ----
    LaunchedEffect(Unit) {
        while (true) {
            progress.positionMs = controller.player.currentPosition.coerceAtLeast(0L)
            progress.durationMs = controller.player.duration.coerceAtLeast(0L)
            progress.bufferedMs = controller.player.bufferedPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    // ---- 首帧看门狗：已在播放但 6s 仍无画面 → 提示片源编码问题 ----
    LaunchedEffect(isPlayingState.value, firstFrameRendered) {
        if (isPlayingState.value && !firstFrameRendered) {
            delay(6_000)
            if (!firstFrameRendered) showNoFrameHint = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.reportProgress(controller.player.currentPosition, controller.player.duration)
        }
    }

    // ---- 播放错误分类处理（§6.5） ----
    val classifiedError = playbackError?.let { classifyPlaybackError(it) }
    LaunchedEffect(playbackError) {
        val classified = classifiedError ?: return@LaunchedEffect
        if (classified.autoRetry && autoRetryCount < 1) {
            autoRetryCount++
            delay(4_000)
            controller.retry()
        }
    }

    // ---- OSD 自动淡出：seek/亮度/音量 1s；双击图标 0.65s；长按倍速由松手显式清除 ----
    LaunchedEffect(osd) {
        when (osd) {
            null -> Unit
            is GestureOsd.Speed -> Unit
            is GestureOsd.PlayPause -> {
                delay(650)
                osd = null
            }
            else -> {
                delay(1_000)
                osd = null
            }
        }
    }

    // ---- 全屏态切换：进/出都复位控件态 ----
    // 进入全屏要保证控件短暂可见；退出全屏要让竖屏回到"控制浮层常显 + 未锁屏"的默认态，
    // 并收起可能开着的面板（否则退回竖屏后会残留横屏选集/倍速浮层，且可能卡在锁屏态）。
    LaunchedEffect(fullscreen) {
        controlsVisible = true
        locked = false
        showEpisodes = false
        showSpeed = false
    }

    // ---- 横屏播放中 4s 自动隐藏；竖屏**不自动隐藏**（常显） ----
    // 为什么竖屏常显、只有全屏才自动隐藏：竖屏播放区只有一条 16:9，而「全屏 / 倍速 / 下一集」
    // 的唯一入口就在这条控制浮层上；若照横屏那样自动隐藏，用户需要时往往找不到控件（上一轮实测痛点）。
    // 横屏是沉浸观影、画面铺满整屏，控件必须让位给画面，才保留 4s 自动隐藏（拖动中/暂停/锁屏时不隐藏）。
    LaunchedEffect(fullscreen, controlsVisible, uiPlayingState.value, locked, interacting, controlTick, showSpeed, showEpisodes) {
        // 面板打开时不自动隐藏控制条：倍速弹窗/选集抽屉都锚定在控制条上，底栏消失会让锚点"悬空"
        if (fullscreen && controlsVisible && !locked && !interacting && !showSpeed && !showEpisodes &&
            uiPlayingState.value
        ) {
            delay(4_000)
            controlsVisible = false
        }
    }

    // ---- 返回：全屏时先退全屏（留在播放页），非全屏才真正返回上一页 ----
    // 顶栏返回按钮、横屏浮层返回、错误面板返回、系统返回键全部走这一个入口，
    // 杜绝"两套不一致行为"（曾经横屏顶栏返回在全屏时直接 pop 回上一页）。
    fun handleBack() {
        if (fullscreen) {
            fullscreen = false
        } else {
            vm.reportProgress(controller.player.currentPosition, controller.player.duration)
            navController.popBackStack()
        }
    }

    BackHandler { handleBack() }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 视频区域（竖屏 16:9 / 全屏铺满），两种模式共用同一个 ExoPlayer
    val videoModifier = if (fullscreen) {
        Modifier.fillMaxSize()
    } else {
        // 窄而高的窗口（手机、平板竖屏）：照旧按宽度铺满 16:9，手机侧逐位不变。
        // 宽而矮的窗口（平板横屏 / 分屏半屏）：还按宽度铺满会让 16:9 画面吃掉几乎整个高度
        // （1280×800 时画面 720dp 高，标题/简介/选集只剩 80dp 被裁），
        // 这里改为按高度收敛、左右留黑，把高度还给信息区与选集。
        val dm = context.resources.displayMetrics
        val winW = dm.widthPixels / dm.density
        val winH = dm.heightPixels / dm.density
        if (winH - winW * 9f / 16f >= 260f) {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        } else {
            Modifier.fillMaxWidth().height((winH * 0.55f).dp)
        }
    }

    // 下一集（同季 ep+1）——用 seasons 派生而不是调用 vm.nextVideo()，保证可重组刷新
    val flatVideos = remember(seasons) { seasons?.seasons?.flatMap { it.videos }.orEmpty() }
    val nextEpisode = remember(flatVideos, targetVideoId) {
        val idx = flatVideos.indexOfFirst { it.id == targetVideoId }
        if (idx in 0 until flatVideos.size - 1) flatVideos[idx + 1] else null
    }
    val title = movie?.title ?: "播放"
    val episodeLabel = currentEpisodeLabel(seasons, targetVideoId)

    // ---- 当前集的缓存 UI 态 ----
    // 已缓存 = 显式下载已完成 或 该集已有下载字节；
    // 下载中 = 显示百分比（DownloadManager 的 onDownloadChanged 已经汇进 downloadStates）
    val currentMediaId = MediaCacheManager.mediaIdOf(movieId, targetVideoId)
    val currentDownload = downloadStates[currentMediaId]
    val cacheCached = currentDownload?.isCompleted == true || cachedBytes > 0L
    val cachePercent: Int? = currentDownload?.takeIf { it.isActive }?.percent?.roundToInt()

    /** 缓存当前集：直链与请求头直接复用播放中的那一份，不必再换一次直链 */
    fun cacheCurrentEpisode() {
        val pu = playUrl ?: return
        // 本地缓存入口（已下载集走本地播放）没有可入队的网络直链，直接忽略
        if (MediaCacheManager.isCacheUri(pu.url)) return
        // 入队前预检：设备剩余空间低于阈值时拒绝（提示"设备空间不足"），不下盘写满
        if (!vm.cacheManager.canEnqueueForSpace()) {
            Toast.makeText(context, "设备空间不足，请删除部分下载后再试", Toast.LENGTH_LONG).show()
            return
        }
        vm.cacheEpisode(targetVideoId, pu.url, pu.headers)
    }

    /** 统一的 seek 入口（拖动条/手势共用）：立刻落位并刷新进度显示 */
    fun seekTo(ms: Long) {
        val max = controller.player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = ms.coerceIn(0L, max)
        controller.player.seekTo(target)
        progress.positionMs = target
    }

    if (fullscreen) {
        // ================= 横屏沉浸 =================
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(controller = controller, modifier = videoModifier)

            // 手势层：锁屏时整体不挂 pointerInput（连单击都不响应）
            GestureLayer(
                controller = controller,
                activity = activity,
                audioManager = audioManager,
                enabled = !locked,
                onSingleTap = {
                    controlsVisible = !controlsVisible
                    controlTick++
                },
                onDragActive = {
                    interacting = it
                    if (it) controlTick++
                },
                onOsd = { osd = it },
                longPressSpeed = longPressSpeed,
                modifier = Modifier.fillMaxSize()
            )

            GestureOsdOverlay(osd = osd, modifier = Modifier.fillMaxSize())

            // 缓冲中：自绘品牌绿弧线指示器（替代 media3 原生转圈；纯展示不拦截手势）
            PlayerBufferingOverlay(
                buffering = bufferingState.value,
                modifier = Modifier.fillMaxSize()
            )

            // 半透明渐变浮层控制条（顶部/底部各一层 gradient）
            LandscapeControls(
                visible = controlsVisible && !locked,
                title = title,
                episodeLabel = episodeLabel,
                isPlaying = uiPlayingState.value,
                progress = progress,
                speed = speed,
                hasNext = nextEpisode != null,
                onInteraction = { controlTick++ },
                onBack = { handleBack() },
                onLock = {
                    // 锁屏时把已打开的面板一并收起（锁屏态不应残留/响应任何面板）
                    showEpisodes = false
                    showSpeed = false
                    locked = true
                    controlsVisible = false
                },
                onOpenEpisodes = { showEpisodes = true; showSpeed = false },
                onTogglePlay = {
                    controller.player.playWhenReady = !controller.player.playWhenReady
                    controlTick++
                },
                onSeek = { seekTo(it) },
                onSeekFinished = { seekTo(it) },
                onDragStateChange = { interacting = it; controlTick++ },
                onNext = { nextEpisode?.let { vm.loadPlayUrl(it.id) } },
                onOpenSpeed = { showSpeed = !showSpeed; showEpisodes = false },
                onExitFullscreen = { fullscreen = false },
                cacheCached = cacheCached,
                cachePercent = cachePercent,
                onCache = { cacheCurrentEpisode() },
                modifier = Modifier.fillMaxSize()
            )

            // 锁屏态：只保留右下角解锁按钮
            if (locked) {
                HyperIconButton(
                    icon = PlayerIcons.Unlock,
                    contentDescription = "解锁",
                    onClick = {
                        locked = false
                        controlsVisible = true
                    },
                    tint = Color.White,
                    size = 48.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.xl)
                )
            }

            // ---- 面板层：右侧「选集」抽屉 + 倍速小弹窗 ----
            // 为什么用"透明遮罩 + 侧滑抽屉"（而不是变暗遮罩 + 底部大面板）：
            // ① 全屏观影时画面是主角，大范围压暗会瞬间打断沉浸感；选集只是"翻目录"的次要动作，
            //    所以遮罩只承担"点空白关闭"的命中职责（不加任何底色、不做变暗）；
            // ② 横屏屏幕"宽"而"矮"，右侧竖条抽屉比底部横向面板能一屏塞下更多集数，
            //    且只占屏宽 42%（上限 320dp），中间画面完整留白、不被大面积遮挡。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // 屏宽 × 0.42，上限 320dp：手机横屏约 780dp 宽时取 320dp 上限，避免抽屉过宽压掉画面。
                // 大屏放宽上限（380 / 440dp）：让抽屉多塞一两列，而不是在 1280dp 宽的窗口上
                // 只留一条 320dp 的窄栏、翻目录要点很多次。
                val drawerCap = when (profile.tier) {
                    LayoutTier.Compact -> 320.dp
                    LayoutTier.Medium -> 380.dp
                    LayoutTier.Expanded -> 440.dp
                }
                val drawerWidth = (maxWidth * 0.42f).coerceAtMost(drawerCap)

                EpisodeDrawer(
                    visible = showEpisodes && !locked,
                    width = drawerWidth,
                    seasons = seasons?.seasons.orEmpty(),
                    currentVideoId = targetVideoId,
                    onPick = { vid ->
                        showEpisodes = false
                        vm.loadPlayUrl(vid)
                    },
                    onDismiss = { showEpisodes = false }
                )

                // 倍速小弹窗：锚定在底部控制条"倍速"按钮的正上方（而不是居中铺满全屏）。
                // 为什么必须锚定按钮而不是居中/全屏：倍速是"就地微调"动作，用户点完按钮视线就在按钮上，
                // 弹窗从按钮方向展开时视线无需移动；铺满全屏则会盖住画面、破坏沉浸感。
                if (showSpeed && !locked) {
                    // 透明全屏点击捕获层：点空白处关闭面板；无底色（不遮暗）、也不阻断"点空白关闭"
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showSpeed = false }
                    )
                }
                // 面板本体始终挂在树上（visible 由 showSpeed 驱动），这样关闭时也能完整播完退场动画
                AnimatedVisibility(
                    visible = showSpeed && !locked,
                    // 从按钮方向（右下角）缩放展开 + 淡入，收起同理
                    enter = fadeIn(tween(120)) +
                        scaleIn(
                            animationSpec = tween(160),
                            initialScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        ),
                    exit = fadeOut(tween(120)) +
                        scaleOut(
                            animationSpec = tween(140),
                            targetScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // end 与倍速按钮右缘对齐（16dp 页面内缩 + 40dp 全屏图标按钮 + 8dp 间距）；
                        // bottom 落在"下一集/倍速/全屏"这一行的上沿，做到"贴在按钮上方、不被底栏压住"
                        .padding(end = 64.dp, bottom = 52.dp)
                ) {
                    SpeedPanel(
                        current = speed,
                        onPick = { v ->
                            controller.player.setPlaybackSpeed(v)
                            speed = v
                            speedTouched = true
                            // 面板选速 → 写 lastSpeed（供「记忆上次倍速」下次进入时套用）
                            vm.setLastSpeed(v)
                            showSpeed = false
                        }
                    )
                }
            }

            PlayerOverlays(
                isLoadingUrl = isLoadingUrl,
                playUrl = playUrl,
                urlError = urlError,
                playbackError = playbackError,
                autoRetryCount = autoRetryCount,
                autoNext = autoNext,
                showNoFrameHint = showNoFrameHint,
                // 横屏底部控制条更高，连播卡片上抬，避免被压住
                autoNextBottomInset = 124.dp,
                onRetryUrl = { vm.retryPlayUrl() },
                onRetryPlayer = { playbackError = null; controller.retry() },
                onBack = { handleBack() },
                onCancelAutoNext = { vm.cancelAutoNext() },
                onPlayNext = { vm.playNextEpisode() }
            )
        }
    } else {
        // ================= 竖屏（默认） =================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xs, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HyperIconButton(
                    icon = PlayerIcons.Back,
                    contentDescription = "返回",
                    onClick = { handleBack() }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = episodeLabel,
                        fontSize = 11.5.sp,
                        color = hyperColors().text3,
                        maxLines = 1
                    )
                }
            }

            // 播放器（16:9）：控制条以**半透明浮层**形式挪进播放区内部、底部对齐
            Box(modifier = videoModifier.background(Color.Black)) {
                PlayerSurface(controller = controller, modifier = Modifier.fillMaxSize())

                // 竖屏手势始终可用：双击播放暂停 / 左右 seek / 上下亮度音量 / 长按倍速
                GestureLayer(
                    controller = controller,
                    activity = activity,
                    audioManager = audioManager,
                    enabled = true,
                    // 竖屏单击：切换控制浮层显隐（面板打开时先收面板）；默认状态为"显示"
                    onSingleTap = {
                        if (showSpeed) {
                            showSpeed = false
                        } else {
                            controlsVisible = !controlsVisible
                        }
                    },
                    onDragActive = { interacting = it },
                    onOsd = { osd = it },
                    longPressSpeed = longPressSpeed,
                    modifier = Modifier.fillMaxSize()
                )

                GestureOsdOverlay(osd = osd, modifier = Modifier.fillMaxSize())

                // 缓冲中：自绘品牌绿弧线指示器（替代 media3 原生转圈；纯展示不拦截手势）
                PlayerBufferingOverlay(
                    buffering = bufferingState.value,
                    modifier = Modifier.fillMaxSize()
                )

                // 竖屏控制浮层：底部渐变 scrim + 常显控制条（详见 PortraitControlOverlay 注释）
                PortraitControlOverlay(
                    visible = controlsVisible,
                    isPlaying = uiPlayingState.value,
                    progress = progress,
                    speed = speed,
                    hasNext = nextEpisode != null,
                    onTogglePlay = {
                        controller.player.playWhenReady = !controller.player.playWhenReady
                    },
                    onSpeed = { showSpeed = !showSpeed },
                    onNext = { nextEpisode?.let { vm.loadPlayUrl(it.id) } },
                    onFullscreen = { fullscreen = true },
                    onSeek = { seekTo(it) },
                    onSeekFinished = { seekTo(it) },
                    onDragStateChange = { interacting = it },
                    cacheCached = cacheCached,
                    cachePercent = cachePercent,
                    onCache = { cacheCurrentEpisode() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                // 竖屏倍速面板：同样是**贴着倍速按钮上方**弹出的小弹窗（不铺满播放区、不遮挡控制条）
                // 注意：这里显式用全限定的顶层 AnimatedVisibility —— 该 Box 同时嵌在 Column 里，
                // 不限定会被同时解析到 ColumnScope.AnimatedVisibility 扩展而报"隐式接收者"冲突。
                if (showSpeed) {
                    // 透明点击捕获层：点播放区空白处关闭面板（无底色、不遮暗）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showSpeed = false }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSpeed,
                    enter = fadeIn(tween(120)) +
                        scaleIn(
                            animationSpec = tween(160),
                            initialScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        ),
                    exit = fadeOut(tween(120)) +
                        scaleOut(
                            animationSpec = tween(140),
                            targetScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // end 与全屏按钮右缘对齐（控制条左右内缩 12dp）；bottom 落在控制条上沿
                        .padding(end = 12.dp, bottom = 46.dp)
                ) {
                    SpeedPanel(
                        current = speed,
                        onPick = { v ->
                            controller.player.setPlaybackSpeed(v)
                            speed = v
                            speedTouched = true
                            vm.setLastSpeed(v)
                            showSpeed = false
                        }
                    )
                }

                PlayerOverlays(
                    isLoadingUrl = isLoadingUrl,
                    playUrl = playUrl,
                    urlError = urlError,
                    playbackError = playbackError,
                    autoRetryCount = autoRetryCount,
                    autoNext = autoNext,
                    showNoFrameHint = showNoFrameHint,
                    onRetryUrl = { vm.retryPlayUrl() },
                    onRetryPlayer = { playbackError = null; controller.retry() },
                    onBack = { handleBack() },
                    onCancelAutoNext = { vm.cancelAutoNext() },
                    onPlayNext = { vm.playNextEpisode() }
                )
            }

            // 影片信息 + 选集（紧跟播放区，顶部间距 12dp）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 与其它页面一致：宽窗口上把信息区收窄并居中（Compact 档 gridSideInset 恒为 0）
                    .padding(horizontal = Spacing.page + profile.gridSideInset)
            ) {
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = movie?.title ?: "影片",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    movie?.rating?.let {
                        Text(
                            text = "★ ${ratingText(it)}",
                            color = hyperColors().rating,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                val sub = buildList {
                    movie?.year?.let { add("$it") }
                    movie?.area?.takeIf { it.isNotBlank() }?.let { add(it) }
                    movie?.episodeTotal?.takeIf { it > 0 }?.let { add("共${it}集") }
                }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = sub, fontSize = 12.sp, color = hyperColors().text3)
                }

                Spacer(Modifier.height(Spacing.lg))
                SeasonAndEpisodes(
                    seasons = seasons?.seasons.orEmpty(),
                    currentVideoId = targetVideoId,
                    onPick = { vid -> vm.loadPlayUrl(vid) }
                )
            }
        }
    }
}

/* =========================================================================
 * 播放画面
 * ========================================================================= */

/**
 * ExoPlayer 画面（竖屏/全屏共用）：
 * 统一 `useController = false` —— 控制层由本文件的 Compose 自绘（更贴近爱奇艺观感）；
 * `SHOW_BUFFERING_NEVER` 关掉 media3 自带的**原生缓冲转圈**（Android 系统观感），
 * 缓冲反馈改由 [PlayerBufferingOverlay] 的自绘品牌绿弧线指示器统一呈现。
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
            }
        },
        update = { pv -> pv.player = controller.player },
        modifier = modifier
    )
}

/**
 * 自绘品牌绿弧线缓冲指示器（替代 media3 原生 `CircularProgressIndicator` 观感）。
 *
 * 双通道动画：整体匀速旋转 + 弧线扫掠角往复伸缩（[LinearEasing] 往返），
 * 圆角线帽，与进度条同源色系，压在黑底画面上清晰不刺眼。
 */
@Composable
private fun HyperSpinner(
    size: Dp,
    strokeWidth: Dp,
    color: Color
) {
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
    Canvas(
        modifier = Modifier.size(size)
    ) {
        // 注意：这里取 DrawScope.size（几何 Size），外层形参 size 是 Dp，没有 width/height
        val cs = this.size
        val stroke = strokeWidth.toPx()
        // 描边以弧线路径为中心：半径需让出半个线宽，圆弧才不会被画布裁切
        val radius = (min(cs.width, cs.height) / 2f - stroke / 2f).coerceAtLeast(0f)
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
 * 缓冲浮层：居中显示 [HyperSpinner]，随 [buffering]（player 处于 BUFFERING 且已播放）淡入淡出。
 * 纯展示层——不挂任何 pointerInput/clickable，触摸穿透到下层手势层与控制条。
 *
 * **防抖**：ExoPlayer 在网络流上会在 READY ↔ BUFFERING
 * 之间频繁抖动，若直接跟随原始状态，指示器会一闪一闪 —— 观感上正是"动画卡顿"。
 * 因此要求**连续缓冲 ≥350ms** 才显示；恢复播放则立即隐藏（隐藏不需要防抖）。
 */
@Composable
private fun PlayerBufferingOverlay(
    buffering: Boolean,
    modifier: Modifier = Modifier
) {
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            HyperSpinner(
                size = 46.dp,
                strokeWidth = 3.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/* =========================================================================
 * 手势：OSD 数据 + 手势层
 * ========================================================================= */

/** 中央手势浮层内容（爱奇艺同款） */
private sealed interface GestureOsd {
    /** 左右滑 seek：deltaMs 相对手势起点的有符号偏移，targetMs 目标位置 */
    data class Seek(val deltaMs: Long, val targetMs: Long, val durationMs: Long) : GestureOsd

    /** 上下滑：brightness=true 亮度、false 音量 */
    data class Vertical(val brightness: Boolean, val percent: Int) : GestureOsd

    /** 长按倍速（目标值可配置，见「我的 → 播放 → 长按倍速」） */
    data class Speed(val speed: Float) : GestureOsd

    /** 双击播放/暂停 */
    data class PlayPause(val playing: Boolean) : GestureOsd
}

/** 全屏横滑 seek 的最大映射跨度（全屏宽）：长片"一滑跳半小时"体验很差，故封顶 10 分钟 */
private const val SEEK_SWIPE_MAX_SPAN_MS = 10 * 60_000L

/** 手势层日志 TAG（亮度/音量写系统失败时只记日志，绝不崩） */
private const val GESTURE_TAG = "PlayerGesture"

/**
 * 取一个"渲染期才构建"的自绘图标。
 *
 * 自绘图标是 `by lazy` + SVG 路径解析，解析在**首次渲染那一刻**才跑；一旦路径写错，
 * 崩的是播放页（本轮就吃过这个亏：音量图标的 `q.9 0 .9.9` 让解析抛 NumberFormatException，
 * 表现为"右半屏调音量直接闪退"）。这里兜一道：构建失败只记日志、返回 null，
 * 把"图标错"降级成"少画一个图标 + 一条日志"，播放与手势不受影响。
 */
private fun safePlayerIcon(tag: String, build: () -> ImageVector): ImageVector? =
    runCatching(build)
        .onFailure { Log.w(GESTURE_TAG, "icon build failed: $tag", it) }
        .getOrNull()

/** 倍速档位：7 档（0.5x ~ 3.0x） */
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * 手势回调/取值容器。
 *
 * 为什么用"可变普通对象"而不是 `rememberUpdatedState`：手势层用 `pointerInput(Unit)` 只创建一次
 * 事件协程，直接捕获 composable 参数会永远用第一次重组时的旧闭包；这里在每次重组刷新字段，
 * 事件触发时再取值，从而始终读到最新的回调与最新 player 状态（普通对象不参与快照，不会引起重组循环）。
 */
private class PlayerGestureHandle {
    var playerProvider: () -> Player? = { null }
    var activityProvider: () -> Activity? = { null }
    var audioManagerProvider: () -> AudioManager? = { null }
    var contextProvider: () -> Context? = { null }

    var onOsd: (GestureOsd?) -> Unit = {}
    var onSingleTap: () -> Unit = {}
    var onDoubleTap: () -> Unit = {}
    var onLongPressStart: () -> Unit = {}
    var onLongPressEnd: () -> Unit = {}
    var onDragActive: (Boolean) -> Unit = {}

    /** 手动调整后的亮度（-1 = 尚未手动调整，跟随系统） */
    var brightness = -1f
    /** 长按倍速前的用户倍速（松手恢复，不硬编码 1.0） */
    var speedBeforeBoost = 1f
    /** 长按手势的目标倍速（「我的 → 播放 → 长按倍速」可配置，默认 2.0x） */
    var longPressSpeedProvider: () -> Float = { 2f }
    /** 长按是否真的触发过（普通单击松手时 onPress 也会回调，必须区分，否则会把用户选的倍速重置） */
    var longPressActive = false

    private var baseBrightness = 0f
    private var baseVolume = 0
    private var seekBaseMs = 0L
    private var seekSpanMs = SEEK_SWIPE_MAX_SPAN_MS
    private var pendingSeekMs = 0L
    /** 本次垂直拖动"已经真正写入系统"的亮度百分比 / 音量档位（-1 = 还没写过，用于节流系统调用） */
    private var appliedBrightnessPercent = -1
    private var appliedVolumeIndex = -1

    fun durationForSeek(): Long = playerProvider()?.duration?.takeIf { it > 0 } ?: 0L

    /** 水平方向锁定：记录基准位置与映射跨度 */
    fun beginHorizontal() {
        val p = playerProvider()
        seekBaseMs = p?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val dur = durationForSeek()
        // 映射跨度：跟随片长，但封顶 10 分钟（见 SEEK_SWIPE_MAX_SPAN_MS 注释）
        seekSpanMs = if (dur in 1..SEEK_SWIPE_MAX_SPAN_MS) dur else SEEK_SWIPE_MAX_SPAN_MS
        pendingSeekMs = seekBaseMs
    }

    /**
     * 水平拖动中只更新 OSD（不 seek）。
     * 为什么松手才 seek：滑动 seek 的映射是"猜测意图"，边滑边 seek 会疯狂触发解码器 flush，
     * 松手一次性落位更稳、也更接近爱奇艺的手感。
     */
    fun onHorizontal(dxPx: Float, widthPx: Float, durationMs: Long) {
        if (widthPx <= 0f) return
        val raw = seekBaseMs + dxPx / widthPx * seekSpanMs
        val maxMs = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = raw.roundToInt().toLong().coerceIn(0L, maxMs)
        pendingSeekMs = target
        onOsd(GestureOsd.Seek(target - seekBaseMs, target, durationMs))
    }

    /** 松手落位 */
    fun commitSeek() {
        playerProvider()?.seekTo(pendingSeekMs)
    }

    /** 垂直方向锁定：记录基准亮度/音量（并把"已写入值"复位，新一次拖动总能落下第一档） */
    fun beginVertical() {
        baseBrightness = effectiveBrightness()
        baseVolume = runCatching {
            audioManagerProvider()?.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrNull() ?: 0
        appliedBrightnessPercent = -1
        appliedVolumeIndex = -1
    }

    /**
     * 亮度：只在**显示的整数百分比变化**时才写 Window。
     *
     * 为什么必须节流：`window.attributes = ...` 每次都触发一次 `relayoutWindow` 的 binder 事务，
     * 手指移动时每秒可能上百次 → 主线程被 window 事务压满、拖动明显掉帧。
     * 百分比是显示精度（OSD 文本就是整数百分比），按它节流不会有任何视觉差异。
     */
    fun onBrightness(dyPx: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        val v = (baseBrightness - dyPx / heightPx).coerceIn(0.01f, 1f)
        brightness = v
        val percent = (v * 100).roundToInt()
        if (percent != appliedBrightnessPercent) {
            appliedBrightnessPercent = percent
            // 某些 ROM 上写 screenBrightness 会抛异常（权限/窗口已销毁），吞掉即可，不能让手势崩掉页面
            runCatching {
                activityProvider()?.window?.let { w ->
                    val lp = w.attributes
                    lp.screenBrightness = v
                    w.attributes = lp
                }
            }.onFailure { Log.w(GESTURE_TAG, "write screenBrightness failed", it) }
        }
        onOsd(GestureOsd.Vertical(brightness = true, percent = percent))
    }

    /**
     * 音量：只在**档位变化**时才 `setStreamVolume`。
     *
     * ① 该调用是 AudioService 的 binder 事务，逐帧写会把主线程压满（拖动卡顿）；
     *    档位数通常只有 15 档，节流后一次拖动最多十几次调用。
     * ② `setStreamVolume` 官方要求 MODIFY_AUDIO_SETTINGS，且在免打扰等受限场景会抛
     *    SecurityException —— 这里 `runCatching` 兜住，**调音量永远不会闪退**（最坏只是音量不变）。
     */
    fun onVolume(dyPx: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        val am = audioManagerProvider() ?: return
        val maxVolume = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        if (maxVolume <= 0) return
        val next = (baseVolume - dyPx / heightPx * maxVolume).roundToInt().coerceIn(0, maxVolume)
        if (next != appliedVolumeIndex) {
            appliedVolumeIndex = next
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
                .onFailure { Log.w(GESTURE_TAG, "setStreamVolume failed", it) }
        }
        onOsd(
            GestureOsd.Vertical(
                brightness = false,
                percent = (next.toFloat() / maxVolume * 100).roundToInt()
            )
        )
    }

    /** 滑动起点亮度：优先手动值 → 窗口值 → 系统亮度设置（避免"一滑就从 0 跳"） */
    private fun effectiveBrightness(): Float {
        if (brightness >= 0f) return brightness
        val fromWindow = activityProvider()?.window?.attributes?.screenBrightness ?: -1f
        if (fromWindow >= 0f) return fromWindow
        val ctx = contextProvider() ?: return 0.5f
        return runCatching {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
    }
}

/**
 * 手势层（单击/双击/长按 + 左右 seek + 上下亮度/音量）。
 *
 * 分两层挂 `pointerInput`：
 * ① 拖动层：`awaitEachGesture` 手写事件循环，位移超过 touchSlop 后**锁定方向**（只认水平或垂直），
 *    锁定后立刻 `consume()` —— 这样同一节点上的单击/双击检测器会因事件被消费而取消，
 *    杜绝"滑完 seek 松手又被判成单击"的重复触发；
 * ② 轻触层：`detectTapGestures` 负责单击 / 双击（<300ms）/ 长按（500ms）。
 */
@Composable
private fun GestureLayer(
    controller: PlayerController,
    activity: Activity?,
    audioManager: AudioManager?,
    enabled: Boolean,
    onSingleTap: () -> Unit,
    onDragActive: (Boolean) -> Unit,
    onOsd: (GestureOsd?) -> Unit,
    longPressSpeed: Float,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val handle = remember { PlayerGestureHandle() }
    handle.playerProvider = { controller.player }
    handle.activityProvider = { activity }
    handle.audioManagerProvider = { audioManager }
    handle.contextProvider = { ctx }
    handle.onOsd = onOsd
    handle.onSingleTap = onSingleTap
    handle.onDragActive = onDragActive
    handle.longPressSpeedProvider = { longPressSpeed }
    handle.onDoubleTap = {
        val next = !controller.player.playWhenReady
        controller.player.playWhenReady = next
        onOsd(GestureOsd.PlayPause(next))
    }
    handle.onLongPressStart = {
        handle.longPressActive = true
        handle.speedBeforeBoost = controller.player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        // 用「我的 → 播放」里配置的长按倍速（默认 2.0x），而不是写死 2f
        val boost = handle.longPressSpeedProvider()
        controller.player.setPlaybackSpeed(boost)
        onOsd(GestureOsd.Speed(boost))
    }
    handle.onLongPressEnd = {
        if (handle.longPressActive) {
            handle.longPressActive = false
            // 恢复"用户选择的倍速"而不是写死 1.0（用户可能选了 1.5x）
            controller.player.setPlaybackSpeed(handle.speedBeforeBoost)
            onOsd(null)
        }
    }

    Box(
        modifier = modifier
            .then(if (enabled) Modifier.playerDragLayer(handle) else Modifier)
            .then(if (enabled) Modifier.playerTapLayer(handle) else Modifier)
    )
}

/** 拖动层：方向锁定 + 水平 seek / 垂直亮度音量 */
private fun Modifier.playerDragLayer(handle: PlayerGestureHandle): Modifier = this.pointerInput(Unit) {
    val touchSlop = viewConfiguration.touchSlop

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var horizontal = false
        var active = false

        // 把一次位移落到对应手势上（水平 = seek；垂直 = 左半屏亮度 / 右半屏音量）。
        // 入参用**相对按下点的累计位移**（不是增量），因此"决定方向的那一次"也能直接套用同一份计算。
        fun applyDrag(change: PointerInputChange) {
            if (horizontal) {
                handle.onHorizontal(
                    change.position.x - startX,
                    size.width.toFloat(),
                    handle.durationForSeek()
                )
            } else {
                val dy = change.position.y - down.position.y
                if (startX < size.width / 2f) {
                    handle.onBrightness(dy, size.height.toFloat())
                } else {
                    handle.onVolume(dy, size.height.toFloat())
                }
            }
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!decided) {
                totalX += change.positionChange().x
                totalY += change.positionChange().y
                if (max(abs(totalX), abs(totalY)) > touchSlop) {
                    decided = true
                    horizontal = abs(totalX) > abs(totalY)
                    if (horizontal) handle.beginHorizontal() else handle.beginVertical()
                    active = true
                    handle.onDragActive(true)
                    // 「决定方向的那一次位移」也要落下去：某些输入（快速甩动、合成事件）会把整段位移
                    // 压进同一个事件、甚至直接是 UP 事件，不补的话整段手势会白丢（表现为"滑了没反应"）。
                    change.consume()
                    applyDrag(change)
                }
            } else {
                change.consume()
                applyDrag(change)
            }
            if (!change.pressed) break
        }

        if (active) {
            if (horizontal) handle.commitSeek()
            handle.onDragActive(false)
        }
    }
}

/** 轻触层：单击 / 双击 / 长按 */
private fun Modifier.playerTapLayer(handle: PlayerGestureHandle): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onTap = { handle.onSingleTap() },
        onDoubleTap = { handle.onDoubleTap() },
        onLongPress = { handle.onLongPressStart() },
        onPress = {
            // 每次按下都会走这里；松手/取消后回调（未长按时是无害的空操作）
            tryAwaitRelease()
            handle.onLongPressEnd()
        }
    )
}

/* =========================================================================
 * OSD 浮层
 * ========================================================================= */

/**
 * 手势浮层总入口。
 *
 * 布局原则（用户反馈"居中大圆太丑且挡住画面中心"）：
 * - 左右滑 seek / 长按倍速 / 双击图标 → 仍走**屏幕正中**（横向手势不影响竖向滑动的手指区域）；
 * - 亮度（左半屏上下滑）→ 细长条面板贴**屏幕右侧**；
 * - 音量（右半屏上下滑）→ 细长条面板贴**屏幕左侧**。
 *   为什么放在反侧：手指在左/右滑动时不遮挡中心画面，面板正好落在手指对面的空白处，
 *   既让出画面中心、又不会被手指本身遮住。
 * - 三个浮层互斥：同一时刻只会出现一个，切换手势时立刻换边，不会同时出现。
 */
@Composable
private fun GestureOsdOverlay(osd: GestureOsd?, modifier: Modifier = Modifier) {
    // 淡出期间仍要显示最后一条内容，否则 AnimatedVisibility 里内容会先消失再做淡出动画。
    // 亮度/音量分槽缓存：切到另一种手势时立刻换边，退出动画里不会残留对方的图标/数值。
    var lastOsd by remember { mutableStateOf<GestureOsd?>(null) }
    var lastBrightness by remember { mutableStateOf<GestureOsd.Vertical?>(null) }
    var lastVolume by remember { mutableStateOf<GestureOsd.Vertical?>(null) }
    LaunchedEffect(osd) {
        when (val cur = osd) {
            null -> Unit
            is GestureOsd.Vertical -> if (cur.brightness) lastBrightness = cur else lastVolume = cur
            else -> lastOsd = cur
        }
    }

    Box(modifier = modifier) {
        // 组合期就能拿到当前值：首帧立即有内容，不必等 LaunchedEffect 落一次状态
        val centerOsd = osd?.takeIf { it !is GestureOsd.Vertical } ?: lastOsd
        val brightnessOsd = (osd as? GestureOsd.Vertical)?.takeIf { it.brightness } ?: lastBrightness
        val volumeOsd = (osd as? GestureOsd.Vertical)?.takeIf { !it.brightness } ?: lastVolume

        // ① 中央浮层：左右滑 seek / 长按倍速 / 双击播放暂停（位置保持原样）
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                // 中央浮层保持原有淡入淡出节奏：用户只反馈了亮度/音量的形态，seek OSD 不动
                visible = osd != null && osd !is GestureOsd.Vertical,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(220))
            ) {
                centerOsd?.let { OsdContent(it) }
            }
        }

        // ② 亮度 → 屏幕右侧（左半屏滑动，面板放反侧；距右边 28dp，竖直居中）
        AnimatedVisibility(
            visible = osd is GestureOsd.Vertical && osd.brightness,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = Spacing.xxl)
        ) {
            brightnessOsd?.let { VerticalOsdPanel(it) }
        }

        // ③ 音量 → 屏幕左侧（右半屏滑动，面板放反侧；距左边 28dp，竖直居中）
        AnimatedVisibility(
            visible = osd is GestureOsd.Vertical && !osd.brightness,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Spacing.xxl)
        ) {
            volumeOsd?.let { VerticalOsdPanel(it) }
        }
    }
}

@Composable
private fun OsdContent(osd: GestureOsd) {
    when (osd) {
        is GestureOsd.Seek -> OsdCapsule {
            val deltaSec = abs(osd.deltaMs) / 1000
            Text(
                text = if (osd.deltaMs < 0) "« ${fmtClock(deltaSec)}" else "${fmtClock(deltaSec)} »",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${fmtDurationMs(osd.targetMs)} / ${fmtDurationMs(osd.durationMs)}",
                color = Color(0xCCFFFFFF),
                fontSize = 12.sp
            )
        }

        // 亮度/音量已改为两侧竖向细长条面板（见 VerticalOsdPanel），不再走中央浮层
        is GestureOsd.Vertical -> Unit

        is GestureOsd.Speed -> OsdCapsule {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = PlayerIcons.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${speedText(osd.speed)} 快进中",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 双击播放/暂停：中央图标闪现（不带胶囊，纯图标淡入淡出）
        is GestureOsd.PlayPause -> Icon(
            imageVector = if (osd.playing) PlayerIcons.Play else PlayerIcons.Pause,
            contentDescription = null,
            tint = Color(0xCCFFFFFF),
            modifier = Modifier.size(72.dp)
        )
    }
}

/** OSD 统一胶囊容器 */
@Composable
private fun OsdCapsule(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** 亮度/音量 OSD 竖向面板尺寸：宽 46dp / 高 190dp / 圆角 = 宽 ÷ 2（天然胶囊） */
private val VerticalOsdWidth = 46.dp
private val VerticalOsdHeight = 190.dp

/** 竖向轨道高 96dp、指示圆点 10dp */
private val VerticalOsdTrackHeight = 96.dp
private val VerticalOsdDotSize = 10.dp

/**
 * 亮度/音量 OSD：细长竖条胶囊（替代原先挡在画面正中的大圆）。
 * 内容自上而下：
 * - 图标 20dp（亮度 [PlayerIcons.Brightness]；音量 [PlayerIcons.VolumeUp]，音量为 0 时换 [PlayerIcons.VolumeOff]）；
 * - 竖向轨道 4dp（底色 `0x33FFFFFF`，已填充段自下而上按比例用品牌绿渐变），上加 10dp 白色指示点；
 * - 底部整数百分比（13sp SemiBold 白字）。
 * 该面板**只用于展示**：不挂 `clickable` / `pointerInput`，因此不会消费触摸，滑动仍由底层手势层继续处理。
 */
@Composable
private fun VerticalOsdPanel(osd: GestureOsd.Vertical) {
    val hc = hyperColors()
    val fraction = (osd.percent / 100f).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(VerticalOsdWidth / 2)
    // 已填充段的品牌绿渐变：remember 复用，避免每次重组都新建 Brush/shader
    val fillBrush = remember(hc.progressGradient) { Brush.verticalGradient(hc.progressGradient) }

    // 图标构建放进 safePlayerIcon 兜底（见其注释）：理论上不会失败，失败也只是少画一个图标
    val iconTag = when {
        osd.brightness -> "brightness"
        osd.percent <= 0 -> "volumeOff"
        else -> "volumeUp"
    }
    val osdIcon = remember(iconTag) {
        safePlayerIcon(iconTag) {
            when (iconTag) {
                "brightness" -> PlayerIcons.Brightness
                "volumeOff" -> PlayerIcons.VolumeOff
                else -> PlayerIcons.VolumeUp
            }
        }
    }

    Column(
        modifier = Modifier
            .width(VerticalOsdWidth)
            .height(VerticalOsdHeight)
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        if (osdIcon != null) {
            Icon(
                imageVector = osdIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 轨道容器**不裁剪**：10dp 指示点略宽于 4dp 轨道时才不会被切掉
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(VerticalOsdTrackHeight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0x33FFFFFF))
            ) {
                // 已填充段：底部对齐、按数值比例向上生长，品牌绿渐变
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(99.dp))
                        .background(fillBrush)
                )
            }
            // 当前位置白点：100% 在顶部、0% 在底部
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (VerticalOsdTrackHeight - VerticalOsdDotSize) * (1f - fraction))
                    .size(VerticalOsdDotSize)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        Text(
            text = "${osd.percent}%",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* =========================================================================
 * 进度条（竖屏/横屏同一套）
 * ========================================================================= */

/**
 * 自绘可拖拽进度条：
 * - 轨道 4dp、拖动时 6dp（`animateDpAsState` 过渡），圆角胶囊；
 * - 已缓冲段 `onSurface.copy(alpha = 0.25f)`，已播放段品牌绿渐变；
 * - 拖动时滑块上方显示 `当前拖动位置 / 总时长` 气泡，并**实时 seek**（松手再确认一次）；
 * - 拖动中通过 [onDragStateChange] 通知外部暂停"自动隐藏计时"。
 */
@Composable
private fun HyperProgressBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSeekFinished: (Long) -> Unit = {},
    onDragStateChange: (Boolean) -> Unit = {},
    touchHeight: Dp = 30.dp,
    // 是否叠在深色 scrim 之上：true 时轨道/缓冲段改用白色系，避免浅色主题下轨道"隐形"
    trackOnDark: Boolean = false
) {
    val hc = hyperColors()
    val trackColor = if (trackOnDark) Color(0x33FFFFFF)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val bufferedColor = if (trackOnDark) Color(0x59FFFFFF)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val playedFraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val bufferedFraction = if (durationMs > 0) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val shownFraction = if (dragging) dragFraction else playedFraction

    // 已播放段的品牌绿渐变：remember 复用（在 draw 里每次新建都会每帧产生一个 Skia shader）
    val playedBrush = remember(hc.progressGradient) { Brush.horizontalGradient(hc.progressGradient) }

    // 拖动时轨道变粗 / 滑块变大（过渡动画）
    val thickness by animateDpAsState(
        targetValue = if (dragging) 6.dp else 4.dp,
        animationSpec = tween(150),
        label = "progressTrack"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (dragging) 16.dp else 10.dp,
        animationSpec = tween(150),
        label = "progressThumb"
    )

    BoxWithConstraints(modifier = modifier.height(touchHeight)) {
        val trackWidth = maxWidth

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(touchHeight)
                .pointerInput(durationMs) {
                    val dur = durationMs
                    if (dur <= 0) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        onDragStateChange(true)
                        dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        onSeek((dragFraction * dur).toLong())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            onSeek((dragFraction * dur).toLong())
                            if (!change.pressed) break
                        }
                        val finalMs = (dragFraction * dur).toLong()
                        onSeekFinished(finalMs)
                        dragging = false
                        onDragStateChange(false)
                    }
                }
        ) {
            val h = thickness.toPx()
            val centerY = size.height / 2f
            val radius = CornerRadius(h / 2f, h / 2f)

            // 轨道
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - h / 2f),
                size = Size(size.width, h),
                cornerRadius = radius
            )
            // 已缓冲
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, centerY - h / 2f),
                    size = Size(size.width * bufferedFraction, h),
                    cornerRadius = radius
                )
            }
            // 已播放（品牌绿渐变）
            if (shownFraction > 0f) {
                drawRoundRect(
                    brush = playedBrush,
                    topLeft = Offset(0f, centerY - h / 2f),
                    size = Size(size.width * shownFraction, h),
                    cornerRadius = radius
                )
            }
            // 滑块：两端留出半个滑块，避免贴边被切
            val thumbPx = thumbSize.toPx()
            val usable = (size.width - thumbPx).coerceAtLeast(0f)
            drawCircle(
                color = Color.White,
                radius = thumbPx / 2f,
                center = Offset(thumbPx / 2f + usable * shownFraction, centerY)
            )
        }

        // 时间气泡：跟随滑块位置（两端用夹取防出界）
        if (dragging && durationMs > 0) {
            val bubbleWidth = 108.dp
            val x = (trackWidth - bubbleWidth) * shownFraction
            Surface(
                color = Color(0xF20B0C10),
                shape = RoundedCornerShape(Spacing.sm),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = x, y = (-38).dp)
                    .width(bubbleWidth)
            ) {
                Text(
                    text = "${fmtDurationMs((durationMs * shownFraction).toLong())} / ${fmtDurationMs(durationMs)}",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/* =========================================================================
 * 竖屏控制浮层（播放区内部、底部对齐）
 * ========================================================================= */

/**
 * 竖屏控制浮层：铺在 16:9 播放区 **内部**、底部对齐，作为半透明浮层（不再是页面上的独立胶囊条）。
 *
 * 布局层级：外层 Box（底对齐、高 84dp，只盖住画面下部约 1/3）→ 竖向渐变 scrim 作背景
 * → Row（左右内缩 12dp、底部内缩 8dp）。内容与观感对齐爱奇艺：播放/暂停 + 当前时间 +
 * 进度条 + 总时长 + 倍速 + 下一集 + 全屏，图标按钮收到 32dp。
 *
 * 显隐由调用方控制：竖屏**默认常显**（不做 4s 自动隐藏），只有横屏才自动隐藏 ——
 * 原因是竖屏播放区很小，这些按钮是进入全屏/倍速/下一集的唯一入口，自动隐藏会让用户找不到控件。
 *
 * 进度条拖动气泡画在进度条上方，本浮层是一个不裁剪尺寸的 Box（Compose 的 Box 默认不裁剪子项），
 * 故气泡即使略微超出浮层上沿也能正常显示，不会被裁掉。
 */
@Composable
private fun PortraitControlOverlay(
    visible: Boolean,
    isPlaying: Boolean,
    progress: PlaybackProgressState,
    speed: Float,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onSpeed: () -> Unit,
    onNext: () -> Unit,
    onFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    cacheCached: Boolean,
    cachePercent: Int?,
    onCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 只在本浮层作用域里读进度：进度 500ms 一变，重组范围就止步于此，不会波及整页
    val positionMs = progress.positionMs
    val durationMs = progress.durationMs
    val bufferedMs = progress.bufferedMs
    // scrim 渐变用 remember 复用（每次重组/绘制都新建 Brush 会产生一个新的 shader）
    val scrim = remember { Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                // 底部渐变 scrim：透明 → 黑 80%，只压暗画面下部，白字/图标才有对比度
                .background(scrim),
            contentAlignment = Alignment.BottomStart
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HyperIconButton(
                    icon = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary,
                    size = 32.dp,
                    iconSize = 22.dp,
                    onClick = onTogglePlay
                )
                Text(
                    text = fmtDurationMs(positionMs),
                    fontSize = 11.5.sp,
                    color = Color(0xE6FFFFFF)
                )
                Spacer(Modifier.width(Spacing.sm))
                HyperProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    onSeek = onSeek,
                    onSeekFinished = onSeekFinished,
                    onDragStateChange = onDragStateChange,
                    // 浮层叠在深色 scrim 上：轨道/缓冲段用白色系，浅色主题下也不会"隐形"
                    trackOnDark = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = fmtDurationMs(durationMs),
                    fontSize = 11.5.sp,
                    color = Color(0xE6FFFFFF)
                )
                // 倍速：透明底按钮（去掉 HyperChip 自带底色），只显示当前倍速文字
                SpeedButton(
                    speed = speed,
                    onClick = onSpeed
                )
                // 缓存当前集：未缓存 = 下载图标；下载中 = 百分比；已缓存 = 对勾图标（全部固定白色系）
                CacheButton(
                    cached = cacheCached,
                    percent = cachePercent,
                    onClick = onCache,
                    size = 32.dp
                )
                // 下一集：无下一集时隐藏
                if (hasNext) {
                    HyperIconButton(
                        icon = PlayerIcons.NextEpisode,
                        contentDescription = "下一集",
                        tint = Color.White,
                        size = 32.dp,
                        iconSize = 20.dp,
                        onClick = onNext
                    )
                }
                HyperIconButton(
                    icon = PlayerIcons.Fullscreen,
                    contentDescription = "全屏",
                    tint = Color.White,
                    size = 32.dp,
                    iconSize = 22.dp,
                    onClick = onFullscreen
                )
            }
        }
    }
}

/* =========================================================================
 * 横屏（沉浸）控制条
 * ========================================================================= */

/**
 * 横屏控制条：顶部（返回 / 片名·第N集 / 选集 / 锁屏）+ 底部（播放暂停 / 时间 / 进度条 /
 * 下一集 / 倍速 / 退出全屏）。两侧用 `Brush.verticalGradient` 半透明渐变压住画面，保证白字可读。
 *
 * 交互计时：条上的 pointerInput 只做一件小事 —— `awaitFirstDown` 时上报 onInteraction，
 * 让"4s 自动隐藏"重新计时（父子节点同处命中链，按钮点击也会触发，无需给每个按钮加回调）。
 */
@Composable
private fun LandscapeControls(
    visible: Boolean,
    title: String,
    episodeLabel: String,
    isPlaying: Boolean,
    progress: PlaybackProgressState,
    speed: Float,
    hasNext: Boolean,
    onInteraction: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onOpenSpeed: () -> Unit,
    onExitFullscreen: () -> Unit,
    cacheCached: Boolean,
    cachePercent: Int?,
    onCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 只在本浮层作用域里读进度（同 PortraitControlOverlay：避免整页 2Hz 重组）
    val positionMs = progress.positionMs
    val durationMs = progress.durationMs
    val bufferedMs = progress.bufferedMs
    val topScrim = remember { Brush.verticalGradient(listOf(Color(0xD9000000), Color(0x00000000))) }
    val bottomScrim = remember { Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000))) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(220)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部渐变浮层 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(topScrim)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onInteraction()
                        }
                    }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HyperIconButton(
                    icon = PlayerIcons.Back,
                    contentDescription = "返回",
                    onClick = onBack,
                    tint = Color.White
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.sm)
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = episodeLabel,
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HyperIconButton(
                    icon = PlayerIcons.Episodes,
                    contentDescription = "选集",
                    onClick = onOpenEpisodes,
                    tint = Color.White
                )
                HyperIconButton(
                    icon = PlayerIcons.Lock,
                    contentDescription = "锁屏",
                    onClick = onLock,
                    tint = Color.White
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- 底部渐变浮层 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bottomScrim)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onInteraction()
                        }
                    }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HyperIconButton(
                        icon = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        onClick = onTogglePlay,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = fmtDurationMs(positionMs),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    HyperProgressBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        onSeek = onSeek,
                        onSeekFinished = onSeekFinished,
                        onDragStateChange = onDragStateChange,
                        // 横屏进度条同样叠在深色渐变浮层上：轨道/缓冲段必须走白色系，
                        // 否则亮色主题下用 onSurface 深色，在深底上会"隐形"。
                        trackOnDark = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = fmtDurationMs(durationMs),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasNext) {
                        HyperIconButton(
                            icon = PlayerIcons.NextEpisode,
                            contentDescription = "下一集",
                            onClick = onNext,
                            tint = Color.White
                        )
                    }
                    SpeedButton(
                        speed = speed,
                        onClick = onOpenSpeed
                    )
                    CacheButton(
                        cached = cacheCached,
                        percent = cachePercent,
                        onClick = onCache
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    HyperIconButton(
                        icon = PlayerIcons.ExitFullscreen,
                        contentDescription = "退出全屏",
                        onClick = onExitFullscreen,
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/* =========================================================================
 * 浮层面板：选集 / 倍速
 * ========================================================================= */

/** 全屏「选集」右侧抽屉：透明点击层（点空白关闭、不变暗）+ 右侧滑出面板（季 Tab + 4 列方形集数网格） */
@Composable
private fun EpisodeDrawer(
    visible: Boolean,
    width: Dp,
    seasons: List<SeasonVo>,
    currentVideoId: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // ① 透明点击遮罩：只在抽屉打开时挂载，避免常驻拦截手势层；无底色 → 画面不变暗
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        // ② 右侧面板：横向滑入/滑出（240ms 进入 / 200ms 退出）
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(240)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                color = Color(0xE6121418),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                // 空 clickable 吞掉面板内部点击，避免穿透到遮罩导致误关闭
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width)
                    .clickable(onClick = {})
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左侧 1dp 分隔线：抽屉贴画面一侧给一条清晰边界（被 Surface 圆角裁切，不会戳出角外）
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color(0x33FFFFFF))
                    )
                    EpisodeDrawerContent(
                        seasons = seasons,
                        currentVideoId = currentVideoId,
                        onPick = onPick,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/** 抽屉内容：头部（选集 + 关闭）→ 季 Tab（多季时）→ 4 列方形集数网格，竖向滚动 */
@Composable
private fun EpisodeDrawerContent(
    seasons: List<SeasonVo>,
    currentVideoId: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
    ) {
        // ① 头部：标题 + 关闭
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "选集",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            HyperIconButton(
                icon = PlayerIcons.Close,
                contentDescription = "关闭",
                onClick = onDismiss,
                tint = Color.White
            )
        }

        if (seasons.isEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(text = "暂无可播放剧集", fontSize = 13.sp, color = Color(0xB3FFFFFF)) // 压在媒体上，用固定色（不随主题）
            return@Column
        }

        val currentSeasonNo = seasons
            .firstOrNull { s -> s.videos.any { it.id == currentVideoId } }
            ?.seasonNo ?: seasons.first().seasonNo
        var selectedSeason by remember(currentSeasonNo) { mutableIntStateOf(currentSeasonNo) }
        val videos = seasons.firstOrNull { it.seasonNo == selectedSeason }?.videos.orEmpty()

        // ② 季 Tab（多季才有）
        if (seasons.size > 1) {
            Spacer(Modifier.height(Spacing.sm))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listItems(seasons, key = { it.seasonNo }) { s ->
                    // 季 Tab 压在深色抽屉（0xE6121418）上：不用共享 HyperChip（它默认用 surfaceVariant/onSurfaceVariant，
                    // 亮色主题下是浅底/深字，压在深色抽屉上会突兀），改用固定色（不随主题）的本地 Tab。
                    DrawerSeasonTab(
                        label = "第${s.seasonNo}季",
                        selected = s.seasonNo == selectedSeason,
                        onClick = { selectedSeason = s.seasonNo }
                    )
                }
            }
        }

        // ③ 方形集数网格：手机 4 列、aspectRatio 1:1、10dp 圆角、8dp 间距，竖向滚动
        // （列数随抽屉宽度上限放宽到 5 / 6，格子宽度与手机上今天的 ~68dp 基本一致）
        Spacer(Modifier.height(Spacing.md))
        LazyVerticalGrid(
            columns = GridCells.Fixed(LocalLayoutProfile.current.playerPanelEpisodeColumns),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = PaddingValues(bottom = Spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(videos, key = { it.id }) { v ->
                DrawerEpisodeCell(
                    episodeNo = v.episodeNo,
                    selected = v.id == currentVideoId,
                    watched = v.progress?.finished == 1,
                    onClick = { onPick(v.id) }
                )
            }
        }
    }
}

/**
 * 抽屉内的「第N季」Tab：压在深色抽屉（`0xE6121418`）之上，全部用**固定色（不随主题）**。
 * - 选中：品牌绿淡底 + 绿字 + 1dp 绿描边（品牌绿两种主题下同一个色，压深底可见）；
 * - 未选中：白 12% 底 + 白字。
 * 不复用共享 [HyperChip]：HyperChip 默认读 `surfaceVariant/onSurfaceVariant`，亮色主题下变浅底深字，
 * 压在深色抽屉上会突兀（本页抽屉是"固定深色浮层"，必须走固定白/绿系）。
 */
@Composable
private fun DrawerSeasonTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.18f) else Color(0x1FFFFFFF))
            .then(if (selected) Modifier.border(1.dp, primary, shape) else Modifier)
            .bounceClick(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) primary else Color(0xE6FFFFFF)
        )
    }
}

/** 抽屉里的方形集数格：未看 = 白 10% 底 + 白字；当前集 = 品牌绿淡底 + 绿字 + 1.5dp 绿描边；已看 = 更淡底 + 灰字 */
@Composable
private fun DrawerEpisodeCell(
    episodeNo: Int,
    selected: Boolean,
    watched: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        selected -> primary.copy(alpha = 0.18f)
        watched -> Color(0x0DFFFFFF)
        else -> Color(0x1AFFFFFF)
    }
    val fg = when {
        selected -> primary
        watched -> Color(0xFF8E949C)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(1.5.dp, primary, shape) else Modifier)
            .bounceClick(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$episodeNo",
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

/**
 * 倍速按钮（**透明底**）：不画任何底色/描边，只有文字本体，按压反馈交给 [bounceClick]。
 *
 * 为什么去掉 HyperChip 自带的 surfaceVariant 底：全屏时按钮压在深色 scrim 上，
 * 多一块浅色胶囊底会打断"整条控制栏只有文字/图标"的一致性；透明底让文字直接落在画面上，
 * 非 1.0x（用户改过速）时用品牌绿高亮，作为"当前非原速"的提示。
 *
 * padding 放在 [bounceClick] 之后：padding 计入点击节点尺寸，从而把文字周围的空白也变成热区
 * （倍速文字很小，必须靠 padding 撑出可点区域）。
 */
@Composable
private fun SpeedButton(
    speed: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val changed = abs(speed - 1f) > 0.001f
    Text(
        text = speedText(speed),
        // 倍速文字压在视频/深色 scrim 之上，用固定色（不随主题）：
        // 未改速时固定白色 —— 亮色主题下 onSurface 是深色，压在深色画面上完全看不清（本次 bug 根因）；
        // 改过速时用品牌绿（两种主题下都是同一个绿，且压深底可见）。
        color = when {
            changed -> MaterialTheme.colorScheme.primary
            else -> Color.White
        },
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .bounceClick(pressedScale = 0.92f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * 「缓存本集」按钮（控制条上）：
 * - 未缓存 → [PlayerIcons.Download]；
 * - 下载中 → 直接显示百分比（比转圈更直观，也能看到是否卡住）；
 * - 已缓存（该集已显式下载过）→ [PlayerIcons.DownloadDone]。
 *
 * 图标一律**固定白色系**：控制条压在视频/深色 scrim 上，浅色主题下用 onSurface 会直接隐形。
 * 下载中点击不重复入队（DownloadManager 对同 id 会 merge，但这里直接吞掉更省事）。
 */
@Composable
private fun CacheButton(
    cached: Boolean,
    percent: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val downloading = percent != null
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (downloading) Modifier
                else Modifier.bounceClick(pressedScale = 0.9f, onClick = onClick)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (downloading) {
            Text(
                text = "$percent%",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Icon(
                imageVector = if (cached) PlayerIcons.DownloadDone else PlayerIcons.Download,
                contentDescription = if (cached) "已缓存" else "缓存本集",
                tint = Color.White,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

/**
 * 倍速选择面板：**紧凑锚定小弹窗**（宽 112dp、7 行 × 34dp，总高约 260dp，仍贴在倍速按钮正上方，
 * 不铺满全屏）。行高从 36dp 收到 34dp 是为了在竖屏 16:9 播放区里也塞得下 7 档而不溢出屏幕。
 * 位置由调用方对齐到倍速按钮正上方；选中项品牌绿 + 加粗 + 右侧 ✓，点击即回调并关闭。
 */
@Composable
private fun SpeedPanel(
    current: Float,
    onPick: (Float) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = Color(0xF2121418),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(112.dp)
            // 空 clickable 吞掉面板内点击，避免穿透到全屏透明捕获层导致误关闭
            .clickable(onClick = {})
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            PLAYBACK_SPEEDS.forEach { v ->
                val selected = abs(v - current) < 0.001f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .bounceClick(pressedScale = 0.94f, onClick = { onPick(v) })
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = speedText(v),
                        color = if (selected) primary else Color(0xFFE6E9EF),
                        fontSize = 13.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            imageVector = PlayerIcons.Check,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

/* =========================================================================
 * 选集（竖屏内联 / 横屏面板复用同一套）
 * ========================================================================= */

/** 季 Tab + 6 列方形集数网格（可复用：竖屏内联区与横屏底部面板共用，避免重复代码） */
@Composable
private fun EpisodePicker(
    seasons: List<SeasonVo>,
    currentVideoId: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val hc = hyperColors()
    if (seasons.isEmpty()) {
        Text(text = "暂无可播放剧集", fontSize = 13.sp, color = hc.text3, modifier = modifier)
        return
    }
    val currentSeasonNo = seasons
        .firstOrNull { s -> s.videos.any { it.id == currentVideoId } }
        ?.seasonNo ?: seasons.first().seasonNo
    var selectedSeason by remember(currentSeasonNo) { mutableIntStateOf(currentSeasonNo) }
    val videos = seasons.firstOrNull { it.seasonNo == selectedSeason }?.videos.orEmpty()

    Column(modifier = modifier) {
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listItems(seasons, key = { it.seasonNo }) { s ->
                    SeasonChip(
                        label = "第${s.seasonNo}季",
                        selected = s.seasonNo == selectedSeason,
                        onClick = { selectedSeason = s.seasonNo }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val profile = LocalLayoutProfile.current
            // minCell 46dp ≈ 手机上今天 6 列的格子宽度（(332 − 5×8)/6 ≈ 48.7dp）→ 手机仍算出 6 列；
            // 平板上按实测宽度算，并被档位上限收住，不会因为窗口 1280dp 就排成 23 列。
            val pickerColumns = columnsFor(
                availableWidth = maxWidth,
                minCell = 46.dp,
                gap = Spacing.sm,
                maxColumns = profile.playerEpisodeColumns
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(pickerColumns),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos, key = { it.id }) { v ->
                    EpisodeCell(
                        episodeNo = v.episodeNo,
                        selected = v.id == currentVideoId,
                        watched = v.progress?.finished == 1,
                        onClick = { onPick(v.id) }
                    )
                }
            }
        }
    }
}

/** 竖屏选集区（外观保持不变） */
@Composable
private fun SeasonAndEpisodes(
    seasons: List<SeasonVo>,
    currentVideoId: Long,
    onPick: (Long) -> Unit
) {
    Column {
        SectionTitle(text = "选集")
        Spacer(Modifier.height(Spacing.sm))
        EpisodePicker(
            seasons = seasons,
            currentVideoId = currentVideoId,
            onPick = onPick
        )
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    HyperChip(
        text = label,
        highlight = selected,
        modifier = Modifier.bounceClick(pressedScale = 0.94f, onClick = onClick)
    )
}

@Composable
private fun EpisodeCell(
    episodeNo: Int,
    selected: Boolean,
    watched: Boolean,
    onClick: () -> Unit
) {
    val hc = hyperColors()
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else if (watched) hc.text3 else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .height(40.dp)
            .bounceClick(pressedScale = 0.92f, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "$episodeNo",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun currentEpisodeLabel(seasons: com.hypermux.mobile.data.model.SeasonsVo?, videoId: Long): String {
    val hit = seasons?.seasons
        ?.firstOrNull { s -> s.videos.any { it.id == videoId } }
        ?.let { s -> s to s.videos.first { it.id == videoId } }
    return if (hit != null) {
        "第${hit.first.seasonNo}季 · 第${hit.second.episodeNo}集" +
            (hit.second.title?.let { " $it" } ?: "")
    } else {
        "正在播放"
    }
}

/* =========================================================================
 * 小工具
 * ========================================================================= */

/** 秒 → mm:ss（用于手势 seek 的位移量展示，超过 1 小时给 h:mm:ss） */
private fun fmtClock(totalSec: Long): String {
    if (totalSec <= 0) return "00:00"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 倍速文案：0.5x / 0.75x / 1.0x / 1.25x / 1.5x / 2.0x / 3.0x（固定 Locale.US，避免小数点变成逗号） */
private fun speedText(v: Float): String {
    val s = if (abs(v - v.roundToInt().toFloat()) < 0.001f) {
        String.format(Locale.US, "%.1f", v)
    } else {
        String.format(Locale.US, "%.2f", v).trimEnd('0')
    }
    return "${s}x"
}

/* =========================================================================
 * 覆盖层（保持原有逻辑不变）
 * ========================================================================= */

/** 播放页覆盖层：取直链中 / 换直链失败 / 播放错误 / 自动连播 / 首帧未渲染提示 */
@Composable
private fun BoxScope.PlayerOverlays(
    isLoadingUrl: Boolean,
    playUrl: Any?,
    urlError: String?,
    playbackError: PlaybackException?,
    autoRetryCount: Int,
    autoNext: AutoNextState?,
    showNoFrameHint: Boolean = false,
    autoNextBottomInset: Dp = 72.dp,
    onRetryUrl: () -> Unit,
    onRetryPlayer: () -> Unit,
    onBack: () -> Unit,
    onCancelAutoNext: () -> Unit,
    onPlayNext: () -> Unit
) {
    if (isLoadingUrl && playUrl == null) {
        LoadingOverlay(text = "获取播放地址…")
    }
    urlError?.let { msg ->
        ErrorOverlay(message = msg, onRetry = onRetryUrl, onBack = onBack)
    }
    playbackError?.let { error ->
        val classified = classifyPlaybackError(error)
        ErrorOverlay(
            message = if (classified.autoRetry && autoRetryCount > 0) {
                "${classified.message}，正在重试…"
            } else {
                classified.message
            },
            onRetry = onRetryPlayer,
            onBack = onBack,
            showRetry = !classified.autoRetry || autoRetryCount >= 1
        )
    }
    // 有声音但画面未渲染（AV1/HEVC 4K 软解过慢）→ 顶部提示条，不遮挡操作
    if (showNoFrameHint && playbackError == null && urlError == null) {
        Surface(
            color = Color(0xE6B26A00),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(Spacing.md)
        ) {
            Text(
                text = "只有声音没有画面：片源可能是 AV1 / HEVC 4K，当前设备解码过慢或不支持，建议换 H.264 源",
                color = Color(0xFFFFF7DC),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 8.dp)
            )
        }
    }
    autoNext?.let { next ->
        AutoNextOverlay(
            seasonNo = next.seasonNo,
            episodeNo = next.nextVideo.episodeNo,
            title = next.nextVideo.title,
            bottomInset = autoNextBottomInset,
            onCancel = onCancelAutoNext,
            onTimeout = onPlayNext
        )
    }
}

@Composable
private fun LoadingOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60B0C10)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 自绘品牌绿弧线加载器（替代原生 M3 CircularProgressIndicator 的观感）
            HyperSpinner(size = 34.dp, strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(text = text, color = Color(0xFFC4C9D4), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    showRetry: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60B0C10)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xl)
        ) {
            Text(
                text = message,
                color = Color(0xFFF3F6FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.lg))
            Row {
                if (showRetry) {
                    HyperButton(
                        text = "重试",
                        onClick = onRetry,
                        style = HyperButtonStyle.Primary
                    )
                    Spacer(Modifier.width(Spacing.md))
                }
                HyperButton(
                    text = "返回",
                    onClick = onBack,
                    style = HyperButtonStyle.Ghost
                )
            }
        }
    }
}

/** 即将播放下一集（1.5s 倒计时，任意操作可取消） */
@Composable
private fun AutoNextOverlay(
    seasonNo: Int,
    episodeNo: Int,
    title: String?,
    bottomInset: Dp = 72.dp,
    onCancel: () -> Unit,
    onTimeout: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(AppConfig.PLAYER_AUTO_NEXT_DELAY_MS.toInt() / 1000 + 1) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1_000)
            countdown--
        }
        onTimeout()
    }

    // 连播倒计时进度：品牌绿渐变胶囊条（替代原生 M3 LinearProgressIndicator），
    // 用 animateFloatAsState 平滑到 countdown 对应的剩余比例，避免逐格跳变
    val autoNextFraction by animateFloatAsState(
        targetValue = (countdown / 2f).coerceIn(0f, 1f),
        animationSpec = tween(320),
        label = "autoNextProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomInset),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Color(0xE6171A21),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(Spacing.md)
        ) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "即将播放：第${seasonNo}季 第${episodeNo}集" +
                        (title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = Color(0xFFF3F6FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0x2EFFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(autoNextFraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                Brush.horizontalGradient(hyperColors().progressGradient)
                            )
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                HyperButton(
                    text = "取消连播",
                    onClick = onCancel,
                    style = HyperButtonStyle.Ghost
                )
            }
        }
    }
}
