package com.hypermux.tv.ui.player

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ApiException
import com.hypermux.tv.data.cache.MediaCacheManager
import com.hypermux.tv.data.model.MovieDetailVo
import com.hypermux.tv.data.model.PlayUrlVo
import com.hypermux.tv.data.model.SeasonsVo
import com.hypermux.tv.data.model.VideoItemVo
import com.hypermux.tv.data.repo.ContentRepo
import com.hypermux.tv.data.repo.PendingProgress
import com.hypermux.tv.data.repo.PendingProgressStore
import com.hypermux.tv.data.repo.PlaybackRepo
import com.hypermux.tv.data.repo.ProgressSync
import com.hypermux.tv.data.repo.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 自动连播角标状态（§6.6） */
data class AutoNextState(
    val nextVideo: VideoItemVo,
    val seasonNo: Int
)

/**
 * 播放页状态机（§6）：
 * 目标集（SavedStateHandle）→ playUrl 换直链（30s 超时）+ 剧集/详情并发 →
 * 续播位置（剧集 progress）→ 自动连播（同季 ep+1，跨季首集）→ 断点上报。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackRepo: PlaybackRepo,
    private val contentRepo: ContentRepo,
    private val state: SavedStateHandle,
    private val settingsRepo: SettingsRepo,
    private val pendingStore: PendingProgressStore,
    private val progressSync: ProgressSync,
    val cacheManager: MediaCacheManager
) : ViewModel() {

    val movieId: Long = state.get<Long>("movieId") ?: -1L
    val videoId: Long = state.get<Long>("videoId") ?: -1L

    // ---- 倍速偏好（「我的」页可改；播放页消费） ----

    /** 长按手势的目标倍速（默认 2.0x） */
    val longPressSpeed: StateFlow<Float> = settingsRepo.longPressSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepo.DEFAULT_LONG_PRESS_SPEED)

    /** 是否"记忆上次倍速"（进入播放页自动套用 [lastSpeed]） */
    val rememberSpeed: StateFlow<Boolean> = settingsRepo.rememberSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 上次手动选择的倍速 */
    val lastSpeed: StateFlow<Float> = settingsRepo.lastSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    fun setLastSpeed(v: Float) {
        viewModelScope.launch { settingsRepo.setLastSpeed(v) }
    }

    // ---- 缓存状态（播放页「缓存」按钮 / 角标） ----

    /** 全部下载项状态（按 mediaId 索引） */
    val downloadStates: StateFlow<Map<String, com.hypermux.tv.data.cache.DownloadState>> =
        cacheManager.states

    /** 当前集已显式下载的字节数（`> 0` 时播放页可停止轮询、显示部分下载提示） */
    fun cachedBytes(videoId: Long): Long =
        cacheManager.cachedBytesOf(MediaCacheManager.cacheKeyOf(videoId))

    /**
     * 缓存当前集（播放页「缓存」按钮）。直链与请求头直接复用当前播放用的那一份，
     * 因此不需要再换一次；片名/集名等元信息从已加载的详情与剧集里取（缓存中心分组展示用）。
     */
    fun cacheEpisode(
        videoId: Long,
        uri: String,
        headers: Map<String, String>?
    ) {
        val v = findVideo(videoId)
        runCatching {
            cacheManager.enqueueDownload(
                movieId = movieId,
                videoId = videoId,
                uri = uri,
                headers = headers,
                seasonNo = seasonNoOf(videoId),
                episodeNo = v?.episodeNo ?: 1,
                videoTitle = v?.title,
                movieTitle = _movie.value?.title.orEmpty(),
                coverUrl = _movie.value?.coverUrl
            )
        }.onFailure { Log.w(TAG, "enqueue download failed", it) }
    }

    private fun findVideo(videoId: Long): VideoItemVo? =
        _seasons.value?.seasons?.flatMap { it.videos }?.firstOrNull { it.id == videoId }

    private fun seasonNoOf(videoId: Long): Int =
        _seasons.value?.seasons
            ?.firstOrNull { s -> s.videos.any { it.id == videoId } }
            ?.seasonNo ?: 1

    /** 当前播放目标（切集时更新 videoId） */
    private val _targetVideoId = MutableStateFlow(videoId)
    val targetVideoId: StateFlow<Long> = _targetVideoId

    // ---- 播放直链 ----

    private val _playUrl = MutableStateFlow<PlayUrlVo?>(null)
    val playUrl: StateFlow<PlayUrlVo?> = _playUrl

    private val _isLoadingUrl = MutableStateFlow(true)
    val isLoadingUrl: StateFlow<Boolean> = _isLoadingUrl

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError

    // ---- 元数据（连播计算 + 续播位置） ----

    private val _seasons = MutableStateFlow<SeasonsVo?>(null)
    val seasons: StateFlow<SeasonsVo?> = _seasons

    private val _movie = MutableStateFlow<MovieDetailVo?>(null)
    val movie: StateFlow<MovieDetailVo?> = _movie

    /** 续播位置（ms；0 = 从头播） */
    private val _resumeMs = MutableStateFlow(0L)
    val resumeMs: StateFlow<Long> = _resumeMs

    /** 当前集在 seasons 中的序号信息（连播角标展示） */
    private val _autoNext = MutableStateFlow<AutoNextState?>(null)
    val autoNext: StateFlow<AutoNextState?> = _autoNext

    init {
        loadMetadata()
        loadPlayUrl(videoId)
        // 进播放页顺手补报一次离线欠账（App 启动/网络恢复两条触发点之外的低成本补充）
        viewModelScope.launch { runCatching { progressSync.flushOnce() } }
    }

    /** 剧集 + 详情（并发）；续播位置取自剧集 progress（精确到集）。
     *  子任务**内部捕获业务失败并返回 Result**（绝不往外抛）：
     *  非监督 async 子任务一旦抛异常会立刻取消父协程；若等 await 时父协程已先被取消、
     *  另一侧的异常又没被 await 消费，就会被判"未捕获异常"直接崩进程
     *  （真机复现过两种时序：谁先失败都崩）。 */
    private fun loadMetadata() {
        viewModelScope.launch {
            val (seasons, detail) = try {
                coroutineScope {
                    val sD = async { trySafely { contentRepo.movieVideos(movieId) } }
                    val dD = async { trySafely { contentRepo.movieDetail(movieId) } }
                    sD.await().getOrNull() to dD.await().getOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadMetadata failed", e)
                null to null
            }
            _seasons.value = seasons
            _movie.value = detail

            if (seasons != null) {
                val current = seasons.seasons
                    .flatMap { it.videos }
                    .find { it.id == videoId }
                val progress = current?.progress
                _resumeMs.value = if (progress != null &&
                    progress.positionMs > AppConfig.RESUME_THRESHOLD_MS &&
                    (progress.durationMs <= 0 ||
                        progress.positionMs < progress.durationMs - AppConfig.RESUME_THRESHOLD_MS)
                ) {
                    progress.positionMs
                } else {
                    0L
                }
            }
        }
    }

    /** 子任务任务体专用：业务失败吞成 Result（不取消父协程）；取消照常上抛 */
    private suspend fun <T> trySafely(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 换取播放直链（§6.2 链路第一步）。
     *  **本地优先**：该集已完整显式下载时，直接下发本地缓存 URI
     *  （[MediaCacheManager.cacheUriOf]），不请求直链接口 —— 断网也能播
     *  （之前断网时这里先等直链超时，已下载的集根本播不了）；
     *  在线时已下载的集也省一次换链请求，且数据源只读本地缓存不触网。 */
    fun loadPlayUrl(videoId: Long) {
        _targetVideoId.value = videoId
        viewModelScope.launch {
            _isLoadingUrl.value = true
            _urlError.value = null
            try {
                if (withContext(Dispatchers.IO) { cacheManager.isFullyCached(videoId) }) {
                    _playUrl.value = PlayUrlVo(url = MediaCacheManager.cacheUriOf(videoId))
                    return@launch
                }
                _playUrl.value = playbackRepo.playUrl(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _playUrl.value = null
                _urlError.value = e.toUiMsg()
            } finally {
                _isLoadingUrl.value = false
            }
        }
    }

    /** 重试换直链（HTTP_BAD 等不自动重试的错误由用户手动触发） */
    fun retryPlayUrl() {
        loadPlayUrl(_targetVideoId.value)
    }

    /**
     * 一集自然播完：先上报 finished=1，再计算下一集（§6.6 顺序：先 finish 再 next，防竞态）。
     * 末集只 finish 不 next。
     * 离线时上报会失败：进度写入本地待补报队列（服务端的 duration-30s 自动 finish 逻辑
     * 会在补报到片尾位置后把这一集标成看完，因此不需要额外的 finished 标记字段）。
     */
    fun onEpisodeEnded(positionMs: Long, durationMs: Long) {
        viewModelScope.launch {
            val ok = runCatching {
                playbackRepo.reportProgress(
                    _targetVideoId.value, positionMs, durationMs, finished = 1
                )
            }.isSuccess
            if (!ok) enqueuePending(positionMs, durationMs)
            val next = nextVideo()
            if (next != null) {
                val seasonNo = _seasons.value?.seasons
                    ?.firstOrNull { it.videos.any { v -> v.id == next.id } }
                    ?.seasonNo ?: 1
                _autoNext.value = AutoNextState(next, seasonNo)
            }
        }
    }

    /** 取消连播（保持当前集尾，PAUSED） */
    fun cancelAutoNext() {
        _autoNext.value = null
    }

    /** 连播倒计时结束 → 换集（重置直链状态，走 §6.2 链路） */
    fun playNextEpisode() {
        val next = _autoNext.value?.nextVideo ?: return
        _autoNext.value = null
        _resumeMs.value = 0L   // 新集从头播
        loadPlayUrl(next.id)
    }

    /** 下一集 = 同季 episodeNo+1 存在的第一个，否则下一季第一个（§6.6） */
    fun nextVideo(): VideoItemVo? {
        val seasons = _seasons.value?.seasons ?: return null
        val flat = seasons.flatMap { it.videos }
        val currentIndex = flat.indexOfFirst { it.id == _targetVideoId.value }
        return if (currentIndex in 0 until flat.size - 1) flat[currentIndex + 1] else null
    }

    /**
     * 断点上报（§7）：播放中 10s 节流 + 暂停/退后台/退出即时；失败静默不阻塞播放。
     *
     * 离线降级：上报失败（异常/无网）时**写入本地待补报队列**（按 videoId 去重只留最新），
     * 网络恢复后由 [ProgressSync] 顺序补报；服务端 detail.progress 仍是续播的唯一来源，
     * 本地队列只做"欠账补偿"，因此不影响现有续播逻辑。
     */
    fun reportProgress(positionMs: Long, durationMs: Long, finished: Int? = null) {
        if (positionMs < AppConfig.PROGRESS_MIN_POSITION_MS) return
        viewModelScope.launch {
            runCatching {
                playbackRepo.reportProgress(
                    _targetVideoId.value, positionMs, durationMs, finished
                )
            }.onFailure {
                Log.w(TAG, "reportProgress failed，转入离线队列", it)
                enqueuePending(positionMs, durationMs)
            }
        }
    }

    /** 把一条断点写进离线队列，并顺手踢一次补报（在线时立刻落地） */
    private suspend fun enqueuePending(positionMs: Long, durationMs: Long) {
        runCatching {
            pendingStore.enqueue(
                PendingProgress(
                    videoId = _targetVideoId.value,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    ts = System.currentTimeMillis()
                )
            )
        }.onSuccess { progressSync.kick() }
    }

    /** 历史/详情入口的集标题（角标用） */
    fun videoTitle(videoId: Long): String? =
        _seasons.value?.seasons
            ?.flatMap { it.videos }
            ?.firstOrNull { it.id == videoId }
            ?.let { "第${it.episodeNo}集" }

    private fun Exception.toUiMsg(): String = when (this) {
        is ApiException -> this.msg
        else -> "获取播放地址失败，请检查网络"
    }

    companion object {
        private const val TAG = "PlayerViewModel"
    }
}
