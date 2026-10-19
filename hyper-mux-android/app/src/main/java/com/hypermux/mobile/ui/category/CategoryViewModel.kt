package com.hypermux.mobile.ui.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.api.ApiException
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.data.repo.ContentRepo
import com.hypermux.mobile.data.repo.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 分类列表（契约 §3.3）：分页 + 分类内 keyword 搜索 */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val contentRepo: ContentRepo,
    private val settingsRepo: SettingsRepo,
    private val state: SavedStateHandle
) : ViewModel() {

    val categoryId: Long = state.get<Long>("categoryId") ?: -1L
    val categoryName: String = state.get<String>("categoryName") ?: ""

    private val _movies = MutableStateFlow<List<MovieCardVo>>(emptyList())
    val movies: StateFlow<List<MovieCardVo>> = _movies

    private val _moviesTotal = MutableStateFlow(0L)
    val moviesTotal: StateFlow<Long> = _moviesTotal

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    private var page = 1
    private var searchJob: Job? = null

    init {
        loadFirst()
    }

    /** 分类内搜索（防抖 300ms，对齐 web 端） */
    fun onKeywordChange(kw: String) {
        _keyword.value = kw
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(AppConfig.SEARCH_DEBOUNCE_MS)
            loadFirst()
        }
    }

    fun loadMore() {
        if (_isLoading.value || _isLoadingMore.value) return
        if (page <= 1) return
        if (_movies.value.isEmpty()) return
        if (_movies.value.size >= _moviesTotal.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val result = fetch(page, AppConfig.PAGE_SIZE)
                // distinctBy 兜底：重复项会触发 LazyGrid 重复 key 崩溃
                _movies.value = (_movies.value + result.list).distinctBy { it.id }
                _moviesTotal.value = result.total
                page++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 分页失败静默
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun retry() {
        loadFirst()
    }

    private fun loadFirst() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val result = fetch(1, AppConfig.PAGE_SIZE)
                _movies.value = result.list.distinctBy { it.id }
                _moviesTotal.value = result.total
                page = 2
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUiMsg()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetch(page: Int, size: Int) =
        contentRepo.categoryMovies(
            categoryId,
            settingsRepo.serverUrl.value.orEmpty(),
            page,
            size,
            _keyword.value.takeIf { it.isNotBlank() }
        )

    private fun Exception.toUiMsg(): String = when (this) {
        is ApiException -> this.msg
        else -> "网络出错了，请检查服务器地址"
    }
}
