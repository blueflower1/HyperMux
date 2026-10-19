package com.hypermux.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.api.ApiException
import com.hypermux.mobile.data.model.CategoryVo
import com.hypermux.mobile.data.model.HomeVo
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.data.repo.AuthRepo
import com.hypermux.mobile.data.repo.ContentRepo
import com.hypermux.mobile.data.repo.SettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页聚合（契约 §3.2）：
 * home 一次全拿（分类 + 继续观看 + 轮播池）；「首页」Tab 展示轮播 + 继续观看 + 类型分区，
 * 具体分类 Tab 展示该分类的分页信息流（缓存降级见 ContentRepo）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val contentRepo: ContentRepo,
    private val authRepo: AuthRepo,
    private val settingsRepo: SettingsRepo
) : ViewModel() {

    private val _home = MutableStateFlow<HomeVo?>(null)
    val home: StateFlow<HomeVo?> = _home

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** 首屏失败文案（无缓存兜底时展示 ErrorState） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 当前频道：合成「首页」哨兵 [TAB_HOME] 或具体分类 id */
    private val _selectedCategoryId = MutableStateFlow<Long?>(TAB_HOME)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId

    private val _movies = MutableStateFlow<List<MovieCardVo>>(emptyList())
    val movies: StateFlow<List<MovieCardVo>> = _movies

    private val _moviesTotal = MutableStateFlow(0L)
    val moviesTotal: StateFlow<Long> = _moviesTotal

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** 「首页」Tab 的类型分区（并发取每个类型少量影片；失败的分区跳过） */
    private val _sections = MutableStateFlow<List<HomeSection>>(emptyList())
    val sections: StateFlow<List<HomeSection>> = _sections

    private var page = 1
    private var loadJob: Job? = null

    /** 首屏加载（下拉刷新同入口）；刷新会取消上一次未完成的加载 */
    fun loadHome() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val userId = authRepo.session.value?.id ?: -1L
            val serverKey = settingsRepo.serverUrl.value.orEmpty()
            try {
                val h = contentRepo.home(userId, serverKey)
                _home.value = h
                // 保留用户当前选择；分类已不存在时回「首页」，保证 Tab 与内容一致
                val current = _selectedCategoryId.value
                val target = if (current != null && (current == TAB_HOME || h.categories.any { it.id == current })) {
                    current
                } else {
                    TAB_HOME
                }
                _selectedCategoryId.value = target
                if (target == TAB_HOME) {
                    // 首页 Tab：无分页信息流，改为并发加载各类型分区
                    _movies.value = emptyList()
                    _moviesTotal.value = 0
                    loadSections(h.categories)
                } else {
                    loadCategoryMovies(target, refresh = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUiMsg()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 切换频道：首页哨兵加载分区，具体分类加载其分页信息流（均重置分页） */
    fun selectCategory(id: Long) {
        if (_selectedCategoryId.value == id) return
        _selectedCategoryId.value = id
        _error.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                if (id == TAB_HOME) {
                    _movies.value = emptyList()
                    _moviesTotal.value = 0
                    loadSections(_home.value?.categories.orEmpty())
                } else {
                    loadCategoryMovies(id, refresh = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUiMsg()
            }
        }
    }

    /**
     * 触底加载更多（失败静默，保留已有列表）。
     * 「首页」Tab 无分页信息流，直接跳过。
     * 关键守卫：首屏/刷新进行中、列表为空、page 未推进时都不翻页——
     * 否则会在下拉刷新期间重复请求第 1 页，导致**重复 key 崩溃**。
     */
    fun loadMore() {
        val current = _selectedCategoryId.value ?: return
        if (current == TAB_HOME) return
        if (_isLoading.value || _isLoadingMore.value) return
        if (page <= 1) return
        if (_movies.value.isEmpty()) return
        if (_movies.value.size >= _moviesTotal.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                loadCategoryMovies(current, refresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 分页失败不打断浏览
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun refresh() {
        loadHome()
    }

    private suspend fun loadCategoryMovies(categoryId: Long, refresh: Boolean) {
        if (refresh) {
            page = 1
            _movies.value = emptyList()
        }
        val serverKey = settingsRepo.serverUrl.value.orEmpty()
        val result = contentRepo.categoryMovies(
            categoryId, serverKey, page, AppConfig.PAGE_SIZE
        )
        _moviesTotal.value = result.total
        // distinctBy 兜底：任何来源的重复项都不会再触发 LazyGrid 重复 key 崩溃
        _movies.value = (if (refresh) result.list else _movies.value + result.list)
            .distinctBy { it.id }
        page++
    }

    /**
     * 并发加载「首页」Tab 的类型分区：只取前 [SECTION_LIMIT] 个类型、每类 [SECTION_SIZE] 部。
     * 单个分区失败/为空会被跳过，不影响其它分区（首屏体感优先）。
     */
    private suspend fun loadSections(categories: List<CategoryVo>) {
        val serverKey = settingsRepo.serverUrl.value.orEmpty()
        val picked = categories.take(SECTION_LIMIT)
        val result = coroutineScope {
            picked.map { c ->
                async {
                    try {
                        val pageVo = contentRepo.categoryMovies(c.id, serverKey, 1, SECTION_SIZE)
                        HomeSection(c.id, c.name, pageVo.list.take(SECTION_SIZE))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        _sections.value = result.filterNotNull().filter { it.movies.isNotEmpty() }
    }

    private fun Exception.toUiMsg(): String = when (this) {
        is ApiException -> this.msg
        else -> "网络出错了，请检查服务器地址"
    }

    companion object {
        /** 合成「首页」Tab 的哨兵 id（真实分类 id 均为正数，与 0 不冲突） */
        const val TAB_HOME = 0L

        /** 「首页」Tab 最多展示几个类型分区（首屏少而全） */
        private const val SECTION_LIMIT = 3

        /** 每个类型分区取多少部（page=1,size=6） */
        private const val SECTION_SIZE = 6
    }
}

/** 「首页」Tab 的类型分区：分区名 + 该类型少量影片 */
data class HomeSection(
    val categoryId: Long,
    val categoryName: String,
    val movies: List<MovieCardVo>
)
