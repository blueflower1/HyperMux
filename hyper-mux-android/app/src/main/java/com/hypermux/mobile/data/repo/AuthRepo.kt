package com.hypermux.mobile.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hypermux.mobile.config.AppConfig
import com.hypermux.mobile.data.model.SessionUserVo
import com.hypermux.mobile.data.model.SessionVo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore(name = "session")

/**
 * 会话仓库：登录 / 登出 / token 持久化（DataStore）+ 内存缓存（供 [AuthInterceptor] 同步读取）。
 * isLoggedIn 判定：token 非空且 now < expiresAt - 300s（契约 §3.1 App 行为）。
 */
@Singleton
class AuthRepo @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val USER_ID = longPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val NICK_NAME = stringPreferencesKey("nick_name")
    }

    private val dataStore = context.sessionStore

    @Volatile
    var cachedToken: String? = null
        private set

    @Volatile
    var cachedExpiresAt: Long = 0L
        private set

    private val _session = MutableStateFlow<SessionUserVo?>(null)

    /** 当前登录用户；null = 未登录（导航层据此回登录页） */
    val session: StateFlow<SessionUserVo?> = _session

    val isLoggedIn: StateFlow<Boolean> =
        _session.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    /** 启动时预载（拦截器在首请求前需要 token） */
    fun preload() {
        scope.launch {
            val ds = dataStore.data.first()
            val token = ds[Keys.TOKEN]
            val expiresAt = ds[Keys.EXPIRES_AT] ?: 0L
            if (!token.isNullOrBlank() && System.currentTimeMillis() < expiresAt - AppConfig.TOKEN_EXPIRY_BUFFER_MS) {
                cachedToken = token
                cachedExpiresAt = expiresAt
                _session.value = SessionUserVo(
                    id = ds[Keys.USER_ID] ?: -1L,
                    username = ds[Keys.USERNAME] ?: "",
                    nickName = ds[Keys.NICK_NAME]
                )
            } else {
                // 过期/无效：静默清掉，避免带着死 token 打扰用户
                invalidate()
            }
        }
    }

    /**
     * 同步取 token：拦截器在**首个请求**前调用，此时 [preload] 可能尚未完成；
     * 拿不到 token 会让首屏请求 401 并被误判"会话失效"弹回登录页。
     * 这里同步兜底读一次 DataStore（拦截器运行在 OkHttp 后台线程，阻塞可接受）。
     */
    fun tokenBlocking(): String? {
        cachedToken?.let { return it }
        val ds = runBlocking { dataStore.data.first() }
        val token = ds[Keys.TOKEN]
        val expiresAt = ds[Keys.EXPIRES_AT] ?: 0L
        val valid = !token.isNullOrBlank() &&
            System.currentTimeMillis() < expiresAt - AppConfig.TOKEN_EXPIRY_BUFFER_MS
        if (!valid) return null
        cachedToken = token
        cachedExpiresAt = expiresAt
        if (_session.value == null) {
            _session.value = SessionUserVo(
                id = ds[Keys.USER_ID] ?: -1L,
                username = ds[Keys.USERNAME] ?: "",
                nickName = ds[Keys.NICK_NAME]
            )
        }
        return token
    }

    /** 登录成功：持久化 + 内存缓存（由 ViewModel 在 safeApiCall 成功后调用） */
    suspend fun saveSession(vo: SessionVo) {        dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = vo.token
            prefs[Keys.EXPIRES_AT] = vo.expiresAt
            prefs[Keys.USER_ID] = vo.user.id
            prefs[Keys.USERNAME] = vo.user.username
            prefs[Keys.NICK_NAME] = vo.user.nickName.orEmpty()
        }
        cachedToken = vo.token
        cachedExpiresAt = vo.expiresAt
        _session.value = vo.user
    }

    /** 会话失效（401 / 登出 / 过期）：清缓存与持久化 → 导航层自动回登录页 */
    fun invalidate() {
        cachedToken = null
        cachedExpiresAt = 0L
        _session.value = null
        scope.launch {
            dataStore.edit { it.clear() }
        }
    }
}
