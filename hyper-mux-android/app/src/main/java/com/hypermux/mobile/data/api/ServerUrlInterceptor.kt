package com.hypermux.mobile.data.api

import com.hypermux.mobile.data.repo.SettingsRepo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器地址运行时可配（手机端特有，决策 D7）：
 * Retrofit 以占位 baseUrl 构建，本拦截器把请求重写为 DataStore 中配置的地址。
 * 播放直链（OpenList 的 /d/ 下载地址）不经此客户端，不受影响。
 */
@Singleton
class ServerUrlInterceptor @Inject constructor(
    private val settingsRepo: SettingsRepo
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val base = settingsRepo.serverUrlBlocking()?.takeIf { it.isNotBlank() }
        if (base == null) return chain.proceed(chain.request())

        val baseUrl = base.normalizeBaseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())
        val original = chain.request()

        val newUrl = baseUrl.newBuilder()
            .encodedPath(original.url.encodedPath)
            .apply {
                original.url.queryParameterNames.forEach { name ->
                    original.url.queryParameterValues(name).forEach { value ->
                        addQueryParameter(name, value)
                    }
                }
            }
            .build()

        return chain.proceed(original.newBuilder().url(newUrl).build())
    }

    /** 规范化：去空白/尾斜杠；缺 scheme 时补 http://（自托管常见输入 "192.168.1.10:8080"） */
    private fun String.normalizeBaseUrl(): String {
        val t = trim().removeSuffix("/")
        if (t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true)
        ) {
            return t
        }
        return "http://$t"
    }
}
