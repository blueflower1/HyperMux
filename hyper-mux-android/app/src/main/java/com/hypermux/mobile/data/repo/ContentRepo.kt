package com.hypermux.mobile.data.repo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hypermux.mobile.data.api.ApiException
import com.hypermux.mobile.data.api.ApiResult
import com.hypermux.mobile.data.api.ApiService
import com.hypermux.mobile.data.api.safeApiCall
import com.hypermux.mobile.data.cache.CacheStore
import com.hypermux.mobile.data.model.HomeVo
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.data.model.MovieDetailVo
import com.hypermux.mobile.data.model.PageVo
import com.hypermux.mobile.data.model.SeasonsVo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内容仓库：home / 分类列表 / 搜索 / 详情 / 剧集。
 * home 与分类第 1 页写 JSON 缓存（[CacheStore]），请求失败时离线兜底（契约 §3.2 缓存降级）。
 * 约定：成功返回值；失败抛 [ApiException]（业务码）或 IllegalStateException（离线无缓存）。
 */
@Singleton
class ContentRepo @Inject constructor(
    private val api: ApiService,
    private val gson: Gson,
    private val cacheStore: CacheStore
) {
    suspend fun home(userId: Long, serverHost: String): HomeVo {
        val key = "home:$userId:$serverHost"
        return when (val r = safeApiCall(gson) { api.home() }) {
            is ApiResult.Ok -> {
                cacheStore.put(key, gson.toJson(r.data))
                r.data
            }
            is ApiResult.Err -> {
                val json = cacheStore.read(key)
                    ?: throw r.toApiException("首页")
                gson.fromJson(json, object : TypeToken<HomeVo>() {}.type)
            }
        }
    }

    suspend fun categoryMovies(
        categoryId: Long,
        serverHost: String,
        page: Int,
        size: Int,
        keyword: String? = null
    ): PageVo<MovieCardVo> {
        val cacheable = page == 1 && keyword.isNullOrBlank()
        val key = "list:$categoryId:$serverHost:p1"
        return when (val r = safeApiCall(gson) { api.categoryMovies(categoryId, page, size, keyword) }) {
            is ApiResult.Ok -> {
                if (cacheable) cacheStore.put(key, gson.toJson(r.data), CacheStore.LIST_PAGE_MAX)
                r.data
            }
            is ApiResult.Err -> {
                if (cacheable) {
                    val json = cacheStore.read(key) ?: throw r.toApiException("分类列表")
                    gson.fromJson(json, object : TypeToken<PageVo<MovieCardVo>>() {}.type)
                } else {
                    throw r.toApiException("分类列表")
                }
            }
        }
    }

    suspend fun search(keyword: String, page: Int, size: Int): PageVo<MovieCardVo> {
        val r = safeApiCall(gson) { api.search(keyword, page, size) }
        return r.fold({ it }, { throw it.toApiException("搜索") })
    }

    suspend fun movieDetail(id: Long): MovieDetailVo {
        val r = safeApiCall(gson) { api.movieDetail(id) }
        return r.fold({ it }, { throw it.toApiException("影片详情") })
    }

    suspend fun movieVideos(id: Long): SeasonsVo {
        val r = safeApiCall(gson) { api.movieVideos(id) }
        return r.fold({ it }, { throw it.toApiException("剧集列表") })
    }
}

/** [ApiResult.Err] → [ApiException]（业务码 + 文案，UI 层按码分支） */
fun ApiResult.Err.toApiException(context: String = ""): ApiException =
    ApiException(code, if (context.isBlank()) msg else "$context：$msg", httpStatus)
