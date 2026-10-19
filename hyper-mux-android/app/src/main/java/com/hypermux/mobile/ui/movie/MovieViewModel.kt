package com.hypermux.mobile.ui.movie

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.data.cache.DownloadState
import com.hypermux.mobile.data.cache.MediaCacheManager
import com.hypermux.mobile.data.model.MovieDetailVo
import com.hypermux.mobile.data.model.SeasonsVo
import com.hypermux.mobile.data.model.VideoItemVo
import com.hypermux.mobile.data.repo.ContentRepo
import com.hypermux.mobile.data.repo.PlaybackRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 影片详情（契约 §3.4 + §3.5）：详情与剧集并发请求；同时提供「缓存本集/本季」能力 */
@HiltViewModel
class MovieViewModel @Inject constructor(
    private val contentRepo: ContentRepo,
    private val playbackRepo: PlaybackRepo,
    /** 公开：详情页在显式下载前做设备剩余空间预检（提示"设备空间不足"） */
    val cacheManager: MediaCacheManager,
    private val state: SavedStateHandle
) : ViewModel() {

    val movieId: Long = state.get<Long>("movieId") ?: -1L

    private val _detail = MutableStateFlow<MovieDetailVo?>(null)
    val detail: StateFlow<MovieDetailVo?> = _detail

    private val _seasons = MutableStateFlow<SeasonsVo?>(null)
    val seasons: StateFlow<SeasonsVo?> = _seasons

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            // 子任务内部捕获业务失败并返回 Result（绝不往外抛）：
            // 非监督 async 子任务一旦抛异常会立刻取消父协程；若 await 时父协程已先被取消、
            // 另一侧的异常又没被消费，会被判"未捕获异常"直接崩进程
            // （与播放页同款坑，真机断网复现过两种失败时序）
            val (detail, seasons) = try {
                coroutineScope {
                    val dD = async { trySafely { contentRepo.movieDetail(movieId) } }
                    val sD = async { trySafely { contentRepo.movieVideos(movieId) } }
                    dD.await().getOrNull() to sD.await().getOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load failed", e)
                null to null
            }
            _detail.value = detail
            _seasons.value = seasons
            if (detail == null || seasons == null) {
                _error.value = "加载影片信息失败，请检查网络或服务器地址"
            }
            _isLoading.value = false
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

    fun retry() {
        load()
    }

    // ---- 缓存（离线下载）状态与动作 ----

    /** 全部下载项状态（按 mediaId 索引）；集数格角标 / 进度靠它 */
    val downloadStates: StateFlow<Map<String, DownloadState>> = cacheManager.states

    /** 该集是否已在本地（缓存里有分片 或 离线下载已完成） */
    fun isCached(videoId: Long): Boolean =
        cacheManager.isDownloaded(movieId, videoId) ||
            cacheManager.cachedBytesOf(MediaCacheManager.cacheKeyOf(videoId)) > 0L

    fun downloadState(videoId: Long): DownloadState? =
        cacheManager.downloadState(movieId, videoId)

    /**
     * 批量缓存（详情页「缓存」选集面板确认时调用）：**顺序**换直链 + 入队，不用并发。
     * 为什么顺序：网盘/OpenList 换直链与下载都有频控，一季几十集并发会把源方打到限流，
     * 顺序执行虽然慢一点但成功率高得多；已缓存 / 下载中的集直接跳过（避免把进行中的任务打回队首）。
     */
    fun cacheEpisodes(videos: List<VideoItemVo>) {
        viewModelScope.launch {
            videos.forEach { v ->
                val st = cacheManager.downloadState(movieId, v.id)
                val busy = st != null && (st.isActive || st.isCompleted)
                if (!busy && !cacheManager.isDownloaded(movieId, v.id)) enqueueOne(v)
            }
        }
    }

    private suspend fun enqueueOne(video: VideoItemVo) {
        runCatching {
            val pu = playbackRepo.playUrl(video.id)
            val d = _detail.value
            cacheManager.enqueueDownload(
                movieId = movieId,
                videoId = video.id,
                uri = pu.url,
                headers = pu.headers,
                seasonNo = seasonNoOf(video.id),
                episodeNo = video.episodeNo,
                videoTitle = video.title,
                movieTitle = d?.title.orEmpty(),
                coverUrl = d?.coverUrl
            )
        }.onFailure { Log.w(TAG, "缓存第${video.episodeNo}集失败", it) }
    }

    /** 该集所在季（下载元信息里的季号，缓存中心分组排序用） */
    private fun seasonNoOf(videoId: Long): Int =
        _seasons.value?.seasons
            ?.firstOrNull { s -> s.videos.any { it.id == videoId } }
            ?.seasonNo ?: 1

    /** 断点续播的目标集（detail.progress 与最近观看集一致时） */
    fun continueVideo(): VideoItemVo? {
        val progress = _detail.value?.progress ?: return null
        return _seasons.value?.seasons
            ?.flatMap { it.videos }
            ?.find { it.id == progress.videoId }
    }

    /** 首集（无断点时"播放第 1 集"） */
    fun firstVideo(): VideoItemVo? =
        _seasons.value?.seasons?.firstOrNull()?.videos?.firstOrNull()

    private companion object {
        const val TAG = "MovieViewModel"
    }
}
