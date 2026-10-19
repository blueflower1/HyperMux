package com.hypermux.tv.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.tv.config.AppConfig
import com.hypermux.tv.data.api.ApiException
import com.hypermux.tv.data.model.HistoryVo
import com.hypermux.tv.data.repo.PlaybackRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 历史记录（契约 §3.7 + §3.9）：列表 + 继续看 / 标记看完 / 删除 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val playbackRepo: PlaybackRepo
) : ViewModel() {

    private val _history = MutableStateFlow<List<HistoryVo>>(emptyList())
    val history: StateFlow<List<HistoryVo>> = _history

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 操作提示（Snackbar） */
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _history.value = playbackRepo.history(AppConfig.HISTORY_LIMIT)
            } catch (e: Exception) {
                // 离线兜底：保留旧列表，展示错误态（有数据时）
                if (_history.value.isEmpty()) {
                    _error.value = e.toUiMsg()
                } else {
                    _snackbar.value = "刷新失败，展示缓存内容"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retry() {
        load()
    }

    /** 标记看完（§3.9 action=finish）：历史保留，finished=1 */
    fun markDone(item: HistoryVo) {
        viewModelScope.launch {
            runCatching { playbackRepo.finishProgress(item.videoId, "finish") }
                .onSuccess {
                    _history.value = _history.value.map { h ->
                        if (h.videoId == item.videoId) h.copy(finished = 1) else h
                    }
                }
                .onFailure { _snackbar.value = it.toUiMsg() }
        }
    }

    /** 删除记录（§3.9 action=remove）：物理删该行 */
    fun remove(item: HistoryVo) {
        viewModelScope.launch {
            runCatching { playbackRepo.finishProgress(item.videoId, "remove") }
                .onSuccess {
                    _history.value = _history.value.filterNot { h -> h.videoId == item.videoId }
                    _snackbar.value = "已删除记录"
                }
                .onFailure { _snackbar.value = it.toUiMsg() }
        }
    }

    /** Snackbar 消费后清空 */
    fun onSnackbarShown() {
        _snackbar.value = null
    }

    private fun Throwable.toUiMsg(): String = when (this) {
        is ApiException -> this.msg
        else -> "网络出错了，请检查服务器地址"
    }
}
