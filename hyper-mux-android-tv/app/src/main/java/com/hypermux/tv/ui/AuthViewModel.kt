package com.hypermux.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hypermux.tv.data.model.SessionUserVo
import com.hypermux.tv.data.repo.AuthRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 会话状态（导航层据此做登录守卫；我的页据此显示有效期） */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepo: AuthRepo
) : ViewModel() {

    val session: StateFlow<SessionUserVo?> = authRepo.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** token 有效期（毫秒时间戳；0 = 无会话） */
    val sessionExpiryAt: StateFlow<Long> = authRepo.session
        .map { authRepo.cachedExpiresAt }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun logout() {
        authRepo.invalidate()
    }
}
