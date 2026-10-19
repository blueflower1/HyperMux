package com.hypermux.mobile.data.api

import android.util.Log
import com.google.gson.Gson
import com.hypermux.mobile.data.model.ApiResponse
import retrofit2.HttpException
import java.io.IOException

/**
 * 统一解包：code==0 → [ApiResult.Ok]；否则 → [ApiResult.Err]（msg 取业务文案）。
 * 网络/解析异常 → [ApiResult.Err]（code=500，msg 已本地化）。
 */
suspend fun <T> safeApiCall(
    gson: Gson,
    block: suspend () -> ApiResponse<T>
): ApiResult<T> = try {
    val resp = block()
    if (resp.code == 0) {
        resp.data?.let { ApiResult.Ok(it) }
            ?: ApiResult.Err(500, "服务端返回数据为空")
    } else {
        ApiResult.Err(resp.code, resp.msg ?: "请求失败", resp.code)
    }
} catch (e: HttpException) {
    // 非 2xx：尝试解析 R 包装体拿业务文案
    val body = e.response()?.errorBody()?.string()
    val msg = if (!body.isNullOrBlank()) {
        runCatching { gson.fromJson(body, ApiResponse::class.java) }.getOrNull()?.msg
    } else null
    Log.w(TAG, "HTTP ${e.code()} msg=$msg", e)
    ApiResult.Err(e.code(), msg ?: e.message() ?: "请求失败", e.code())
} catch (e: IOException) {
    Log.w(TAG, "IO 异常：${e.javaClass.simpleName} ${e.message}", e)
    val err = e.toApiErr()
    ApiResult.Err(err.code, err.msg)
} catch (e: Exception) {
    // 反序列化 / 类型不匹配等（R8 丢泛型时会在这里暴露：ClassCastException/JsonSyntaxException）
    Log.w(TAG, "解析/未知异常：${e.javaClass.name} ${e.message}", e)
    val err = e.toApiErr()
    ApiResult.Err(err.code, err.msg)
}

private const val TAG = "SafeApiCall"
