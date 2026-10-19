package com.hypermux.tv.ui.movie

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.tv.data.model.MovieDetailVo
import com.hypermux.tv.data.model.SeasonsVo
import com.hypermux.tv.data.model.VideoItemVo
import com.hypermux.tv.data.repo.ContentRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 影片详情（契约 §3.4 + §3.5）：详情与剧集并发请求 */
@HiltViewModel
class MovieViewModel @Inject constructor(
    private val contentRepo: ContentRepo,
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
