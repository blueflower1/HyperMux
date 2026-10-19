package com.hypermux.mobile.ui.search

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

/** 全局搜索（契约 §3.10）：防抖 + 分页 + 搜索历史 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val contentRepo: ContentRepo,
    private val settingsRepo: SettingsRepo
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<MovieCardVo>>(emptyList())
    val results: StateFlow<List<MovieCardVo>> = _results

    private val _total = MutableStateFlow(0L)
    val total: StateFlow<Long> = _total

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 搜索历史（DataStore 持久化，最近 20 条） */
    val searchHistory: StateFlow<List<String>> = settingsRepo.searchHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var page = 1
    private var searchJob: Job? = null

    /** 输入变化 → 防抖搜索 */
    fun onQueryChange(input: String) {
        _query.value = input
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(AppConfig.SEARCH_DEBOUNCE_MS)
            if (input.isBlank()) {
                _results.value = emptyList()
                _total.value = 0
                _error.value = null
                return@launch
            }
            doSearch(page = 1)
        }
    }

    /** 提交（IME 搜索键）→ 记录历史并立即搜索 */
    fun submit() {
        val kw = _query.value.trim()
        if (kw.isBlank()) return
        viewModelScope.launch { settingsRepo.addSearchHistory(kw) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { doSearch(page = 1) }
    }

    /**
     * 点搜索历史里的关键词：填入输入框并**立即**搜索（复用 [submit] 的「记历史 + 立即搜索」语义，
     * 不走 300ms 防抖）；同时把该词提到历史最前。
     */
    fun onSubmitKeyword(keyword: String) {
        _query.value = keyword
        submit()
    }

    fun onResultClick(item: MovieCardVo) {
        val kw = _query.value.trim()
        if (kw.isNotBlank()) {
            viewModelScope.launch { settingsRepo.addSearchHistory(kw) }
        }
    }

    fun loadMore() {
        if (_isSearching.value || _isLoadingMore.value) return
        if (page <= 1) return
        if (_results.value.isEmpty()) return
        if (_results.value.size >= _total.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                doSearch(page = page, append = true)
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
        if (_query.value.isNotBlank()) {
            viewModelScope.launch { doSearch(page = 1) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { settingsRepo.clearSearchHistory() }
    }

    private suspend fun doSearch(page: Int, append: Boolean = false) {
        val kw = _query.value.trim()
        _isSearching.value = !append
        _error.value = null
        try {
            val result = contentRepo.search(
                kw, page, AppConfig.PAGE_SIZE
            )
            _total.value = result.total
            _results.value = (if (append) _results.value + result.list else result.list)
                .distinctBy { it.id }
            if (!append) this@SearchViewModel.page = page + 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!append) {
                _results.value = emptyList()
                _total.value = 0
                _error.value = e.toUiMsg()
            }
        } finally {
            _isSearching.value = false
        }
    }

    private fun Exception.toUiMsg(): String = when (this) {
        is ApiException -> this.msg
        else -> "网络出错了，请检查服务器地址"
    }
}
