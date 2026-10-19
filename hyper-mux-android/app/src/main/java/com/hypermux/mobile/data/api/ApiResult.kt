package com.hypermux.mobile.data.api

import java.io.IOException

/** 仓库层统一结果类型（ApiService 直接返回实体，由 safeApiCall 包装为本类型） */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>
    data class Err(val code: Int, val msg: String, val httpStatus: Int? = null) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Ok<*>

    fun <R> fold(onSuccess: (T) -> R, onFailure: (Err) -> R): R =
        when (this) {
            is Ok -> onSuccess(data)
            is Err -> onFailure(this)
        }

    fun <R> map(transform: (T) -> R): ApiResult<R> =
        when (this) {
            is Ok -> Ok(transform(data))
            is Err -> this
        }
}

/**
 * 业务异常：code 与 HTTP 状态一致（契约 §2）。
 * 401 由 [AuthInterceptor] 统一触发会话失效；仓库层只需抛出。
 */
class ApiException(
    val code: Int,
    val msg: String,
    val httpStatus: Int? = null
) : IOException("API error $code: $msg")

/** 网络/解析/未知异常 → 统一 Err，避免 UI 层 try/catch 散落 */
fun Throwable.toApiErr(): ApiResult.Err {
    val msg = when (this) {
        is ApiException -> this.msg
        is java.net.SocketTimeoutException -> "连接超时，请检查网络或服务器地址"
        is java.net.UnknownHostException -> "无法连接服务器，请检查服务器地址"
        is java.net.ConnectException -> "连接被拒绝，请检查服务器地址与网络"
        is javax.net.ssl.SSLHandshakeException -> "证书校验失败（自托管请配置受信任证书）"
        else -> this.message ?: "网络出错了"
    }
    val code = if (this is ApiException) this.code else 500
    val http = if (this is ApiException) this.httpStatus else null
    return ApiResult.Err(code, msg, http)
}
