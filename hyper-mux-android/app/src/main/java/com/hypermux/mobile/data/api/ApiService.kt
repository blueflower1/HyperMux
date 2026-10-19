package com.hypermux.mobile.data.api

import com.hypermux.mobile.data.model.ApiResponse
import com.hypermux.mobile.data.model.FinishReq
import com.hypermux.mobile.data.model.FinishVo
import com.hypermux.mobile.data.model.HistoryVo
import com.hypermux.mobile.data.model.HomeVo
import com.hypermux.mobile.data.model.LoginReq
import com.hypermux.mobile.data.model.MovieDetailVo
import com.hypermux.mobile.data.model.PageVo
import com.hypermux.mobile.data.model.MovieCardVo
import com.hypermux.mobile.data.model.PlayUrlReq
import com.hypermux.mobile.data.model.PlayUrlVo
import com.hypermux.mobile.data.model.ProgressReq
import com.hypermux.mobile.data.model.ProgressVo
import com.hypermux.mobile.data.model.QrIdReq
import com.hypermux.mobile.data.model.SeasonsVo
import com.hypermux.mobile.data.model.SessionVo
import com.hypermux.mobile.data.model.UpdateInfo
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

/** 探测请求标记：App 侧拦截器据此跳过 401 会话失效副作用（服务端忽略该头） */
const val PROBE_HEADER = "X-HyperMux-Probe: 1"

/**
 * 契约 §3 的 1:1 映射（[hyper-mux-tv/docs/plans/30-api-contract.md]）。
 *  Suspension 直接返回实体；[ApiResponse] 包装在仓库层用 safeApiCall 解包。
 */
interface ApiService {

    @POST("api/app/auth/login")
    suspend fun login(@Body req: LoginReq): ApiResponse<SessionVo>

    /** 扫码登录（需登录态）：已扫到 TV 登录码 → TV 显示「已扫码待确认」 */
    @POST("api/app/auth/qr/scan")
    suspend fun qrScan(@Body req: QrIdReq): ApiResponse<Boolean>

    /** 扫码登录（需登录态）：确认登录，token 由 TV 轮询取走 */
    @POST("api/app/auth/qr/confirm")
    suspend fun qrConfirm(@Body req: QrIdReq): ApiResponse<Boolean>

    @GET("api/app/home")
    suspend fun home(): ApiResponse<HomeVo>

    @GET("api/app/category/{id}/movies")
    suspend fun categoryMovies(
        @Path("id") categoryId: Long,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("keyword") keyword: String? = null
    ): ApiResponse<PageVo<MovieCardVo>>

    @GET("api/app/search")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): ApiResponse<PageVo<MovieCardVo>>

    @GET("api/app/movie/{id}")
    suspend fun movieDetail(@Path("id") id: Long): ApiResponse<MovieDetailVo>

    @GET("api/app/movie/{id}/videos")
    suspend fun movieVideos(@Path("id") id: Long): ApiResponse<SeasonsVo>

    @POST("api/app/play-url")
    suspend fun playUrl(@Body req: PlayUrlReq): ApiResponse<PlayUrlVo>

    @GET("api/app/history")
    suspend fun history(@Query("limit") limit: Int = 50): ApiResponse<List<HistoryVo>>

    @PUT("api/app/progress")
    suspend fun reportProgress(@Body req: ProgressReq): ApiResponse<ProgressVo>

    @POST("api/app/progress/finish")
    suspend fun finishProgress(@Body req: FinishReq): ApiResponse<FinishVo>

    /**
     * 连通性探测（登录页 /「我的」页的「测试连接」）。
     *
     * 注意：`actuator/health` 部署在**管理端口**（默认 9090，仅本机可访问，见 70-deploy-ops.md），
     * 主端口上没有免鉴权的 GET 端点。因此这里探测受保护的 `/api/app/home`：
     * 预期返回 401（缺少登录凭证）——能拿到 HTTP 响应即代表「服务器可达且是本服务」，
     * 且该 401 由后端 BizException 正常返回，不会在服务端产生 ERROR 日志。
     */
    @Headers(PROBE_HEADER)
    @GET("api/app/home")
    suspend fun probe(): Response<okhttp3.ResponseBody>

    // ---------- App 自更新（契约 3.11） ----------

    /**
     * 检查更新（匿名，登录前可用）：服务器最新版本 > [code] 时返回 200 + R<UpdateInfo>，
     * 否则（同版本 / 客户端更新 / 该平台未发布）返回 HTTP 204 空 body。
     * 因此返回 [Response] 包 body：204 无法用 [ApiResponse] 直接表达。
     */
    @GET("api/app/update/check")
    suspend fun checkUpdate(
        @Query("code") code: Int,
        @Query("platform") platform: String
    ): Response<ApiResponse<UpdateInfo>>
}
