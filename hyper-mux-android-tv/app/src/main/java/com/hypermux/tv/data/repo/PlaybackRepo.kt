package com.hypermux.tv.data.repo

import com.google.gson.Gson
import com.hypermux.tv.data.api.ApiResult
import com.hypermux.tv.data.api.ApiService
import com.hypermux.tv.data.api.safeApiCall
import com.hypermux.tv.data.model.FinishReq
import com.hypermux.tv.data.model.HistoryVo
import com.hypermux.tv.data.model.PlayUrlReq
import com.hypermux.tv.data.model.PlayUrlVo
import com.hypermux.tv.data.model.ProgressReq
import com.hypermux.tv.data.model.ProgressVo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放仓库：play-url 换直链（30s 超时由 OkHttp 全局配置，见 AppModule）/ 断点上报 / 看完移除。
 * 上报类接口失败静默（不阻塞播放，由调用方 catch 后记日志）。
 */
@Singleton
class PlaybackRepo @Inject constructor(
    private val api: ApiService,
    private val gson: Gson
) {
    suspend fun playUrl(videoId: Long): PlayUrlVo {
        val r = safeApiCall(gson) { api.playUrl(PlayUrlReq(videoId)) }
        return r.fold({ it }, { throw it.toApiException("获取播放地址") })
    }

    /** 历史记录（契约 §3.7，updateTime 降序） */
    suspend fun history(limit: Int): List<HistoryVo> {
        val r = safeApiCall(gson) { api.history(limit) }
        return r.fold({ it }, { throw it.toApiException("历史记录") })
    }

    suspend fun reportProgress(
        videoId: Long,
        positionMs: Long,
        durationMs: Long? = null,
        finished: Int? = null
    ): ProgressVo {
        val r = safeApiCall(gson) {
            api.reportProgress(ProgressReq(videoId, positionMs, durationMs, finished))
        }
        return r.fold({ it }, { throw it.toApiException("断点上报") })
    }

    suspend fun finishProgress(videoId: Long, action: String = "finish") {
        val r = safeApiCall(gson) {
            api.finishProgress(FinishReq(videoId, action))
        }
        r.fold({}, { throw it.toApiException("操作历史") })
    }
}
