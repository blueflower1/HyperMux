package com.hypermux.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.tv.data.api.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 全局 UI 状态：离线标记（黄条） */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val networkMonitor: NetworkMonitor
) : ViewModel() {
    val isOffline: StateFlow<Boolean> = networkMonitor.isOnline
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
