package com.hypermux.mobile.data.api

import com.hypermux.mobile.data.repo.AuthRepo
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每请求注入 Bearer token（来自 [AuthRepo] 的内存缓存，启动时预载）。
 * 响应 401（非登录请求）→ [AuthRepo.invalidate] 清会话，
 * 由导航层的会话观察自动回登录页（契约 §3.1：401 → 清 token 回登录页）。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authRepo: AuthRepo
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isProbe = request.header("X-HyperMux-Probe") != null
        val isLogin = request.url.encodedPath.contains("auth/login", ignoreCase = true)

        val token = authRepo.tokenBlocking()
        val authed = if (!isLogin && !isProbe && !token.isNullOrBlank()) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }

        val response = chain.proceed(authed)
        if (response.code == 401 && !isLogin && !isProbe) {
            // 同步失效：内存缓存立即清空；DataStore 清理由 invalidate 的协程完成
            authRepo.invalidate()
        }
        return response
    }
}
