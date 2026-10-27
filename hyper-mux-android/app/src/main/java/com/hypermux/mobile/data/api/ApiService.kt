package com.hypermux.mobile.data.api

import com.hypermux.mobile.data.model.ApiResponse
import com.hypermux.mobile.data.model.CastAcceptReq
import com.hypermux.mobile.data.model.CastAcceptedVo
import com.hypermux.mobile.data.model.CastConfigVo
import com.hypermux.mobile.data.model.CastCurrentVo
import com.hypermux.mobile.data.model.CastDeviceIdVo
import com.hypermux.mobile.data.model.CastDeviceVo
import com.hypermux.mobile.data.model.CastIdReq
import com.hypermux.mobile.data.model.CastPollVo
import com.hypermux.mobile.data.model.CastRegisterReq
import com.hypermux.mobile.data.model.CastRequestedVo
import com.hypermux.mobile.data.model.CastRequestReq
import com.hypermux.mobile.data.model.CastStatusVo
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

    // ---------- 远程投屏与设备（契约 §3.13） ----------

    /** 设备注册（幂等 upsert）：登录成功 / 启动恢复会话 / 打开投屏入口时调用 */
    @POST("api/app/devices/register")
    suspend fun registerDevice(@Body req: CastRegisterReq): ApiResponse<CastDeviceIdVo>

    /** 同账号设备列表（platform 可空 = 全部） */
    @GET("api/app/devices")
    suspend fun castDevices(@Query("platform") platform: String? = null): ApiResponse<List<CastDeviceVo>>

    /** 发起投屏（单并发：新请求抢占旧请求） */
    @POST("api/app/cast/request")
    suspend fun castRequest(@Body req: CastRequestReq): ApiResponse<CastRequestedVo>

    /** 投屏结果轮询（每 1s，上限 17s） */
    @GET("api/app/cast/request/status")
    suspend fun castStatus(@Query("id") id: Long): ApiResponse<CastStatusVo>

    /** 取消投屏（幂等） */
    @POST("api/app/cast/cancel")
    suspend fun castCancel(@Body req: CastIdReq): ApiResponse<Boolean>

    /** 当前投屏（重启/切后台回来恢复状态）：data.active=null 表示没有投屏 */
    @GET("api/app/cast/current")
    suspend fun castCurrent(): ApiResponse<CastCurrentVo>

    /** 投屏配置（开关 / 确认倒计时 / 轮询节奏） */
    @GET("api/app/cast/config")
    suspend fun castConfig(): ApiResponse<CastConfigVo>

    // ---------- 投屏接收侧（本机当目标端） ----------

    /**
     * 接收端轮询（每 2s）。这一发同时是"本机在线"心跳：
     * 服务端 DevicePresenceService 据此把这台手机标成在线，别的设备才允许投过来；停止轮询 30s 后自动离线。
     */
    @GET("api/app/cast/poll")
    suspend fun castPoll(@Query("deviceId") deviceId: String): ApiResponse<CastPollVo>

    /** 接受投屏（reason = user-yes | countdown） */
    @POST("api/app/cast/accept")
    suspend fun castAccept(@Body req: CastAcceptReq): ApiResponse<CastAcceptedVo>

    /** 拒绝投屏（幂等，已是终态也返回成功） */
    @POST("api/app/cast/reject")
    suspend fun castReject(@Body req: CastIdReq): ApiResponse<Boolean>
}
