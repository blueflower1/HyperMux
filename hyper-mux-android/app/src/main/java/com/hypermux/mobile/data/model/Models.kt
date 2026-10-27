package com.hypermux.mobile.data.model

import com.google.gson.annotations.SerializedName

/** 统一响应包装 R<T>：code==0 成功；失败 code 即 HTTP 状态码 */
data class ApiResponse<T>(
    val code: Int,
    val msg: String?,
    val data: T?
)

// ---------- 认证（契约 3.1） ----------

data class LoginReq(val username: String, val password: String)

/** 扫码登录：qr/scan 与 qr/confirm 请求体（id = TV 登录码） */
data class QrIdReq(val id: String)

data class SessionUserVo(
    val id: Long,
    val username: String,
    val nickName: String?
)

data class SessionVo(
    val token: String,
    val expiresAt: Long,
    val user: SessionUserVo
)

// ---------- 首页聚合（契约 3.2） ----------

data class HomeVo(
    val categories: List<CategoryVo> = emptyList(),
    val continueWatching: List<ContinueVo> = emptyList(),
    /**
     * 首页顶栏轮播池：后端每 6 小时随机刷新的 6 张（字段与 [MovieCardVo] 完全一致）。
     * 为空时 UI 会退回「当前列表前几部」以保证轮播不空。
     */
    val banners: List<MovieCardVo> = emptyList()
)

data class CategoryVo(
    val id: Long,
    val name: String,
    val coverUrl: String? = null,
    val movieCount: Int = 0
)

data class ContinueVo(
    val movieId: Long,
    val title: String,
    val coverUrl: String? = null,
    /** 横版剧照（继续观看横幅背景用；可能为空） */
    val backdropUrl: String? = null,
    val categoryName: String? = null,
    val lastVideo: LastVideoVo,
    val positionMs: Long? = null,
    val durationMs: Long? = null
)

data class LastVideoVo(
    val id: Long,
    val seasonNo: Int,
    val episodeNo: Int
)

// ---------- 影片卡片（契约 3.3 / 3.10 共用） ----------

data class MovieCardVo(
    val id: Long,
    val title: String,
    val coverUrl: String? = null,
    /** 横版剧照（16:9，首页信息流用；可能为空） */
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val area: String? = null,
    val episodeTotal: Int = 0,
    val tags: List<String> = emptyList(),
    val progress: ProgressLiteVo? = null,
    val updateTime: Long = 0
)

data class ProgressLiteVo(
    val videoId: Long,
    val seasonNo: Int,
    val episodeNo: Int,
    val positionMs: Long,
    val durationMs: Long
)

data class PageVo<T>(
    val list: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val size: Int = 20
)

// ---------- 影片详情（契约 3.4） ----------

data class MovieDetailVo(
    val id: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val coverUrl: String? = null,
    /** 横版封面（详情页头图用；可能为空） */
    val backdropUrl: String? = null,
    val rating: Double? = null,
    val area: String? = null,
    val language: String? = null,
    val intro: String? = null,
    val tags: List<TagRefVo> = emptyList(),
    val episodeTotal: Int = 0,
    val category: CategoryRefVo,
    val progress: ProgressFullVo? = null
)

data class TagRefVo(
    val id: Long,
    val name: String,
    val type: Int = 0
)

data class CategoryRefVo(
    val id: Long,
    val name: String
)

data class ProgressFullVo(
    val videoId: Long,
    val seasonNo: Int,
    val episodeNo: Int,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Int = 0
)

// ---------- 剧集列表（契约 3.5） ----------

data class SeasonsVo(
    val movieId: Long,
    val seasons: List<SeasonVo> = emptyList()
)

data class SeasonVo(
    val seasonNo: Int,
    val videos: List<VideoItemVo> = emptyList()
)

data class VideoItemVo(
    val id: Long,
    val episodeNo: Int,
    val title: String? = null,
    /** 单位：秒（与断点的 ms 区分，展示时需换算） */
    val duration: Int? = null,
    val progress: VideoProgressVo? = null
)

data class VideoProgressVo(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Int = 0
)

// ---------- 播放直链（契约 3.6） ----------

data class PlayUrlReq(val videoId: Long)

data class PlayUrlVo(
    val url: String,
    val headers: Map<String, String>? = null,
    val expiresAt: Long? = null
)

// ---------- 历史记录（契约 3.7） ----------

data class HistoryVo(
    val videoId: Long,
    val movieId: Long,
    val title: String,
    val coverUrl: String? = null,
    val categoryName: String? = null,
    val seasonNo: Int,
    val episodeNo: Int,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Int = 0,
    val updateTime: Long = 0
)

// ---------- 断点上报 / 看完移除（契约 3.8 / 3.9） ----------

data class ProgressReq(
    val videoId: Long,
    val positionMs: Long,
    val durationMs: Long? = null,
    val finished: Int? = null
)

data class ProgressVo(
    val videoId: Long,
    val positionMs: Long,
    val finished: Int,
    val updateTime: Long
)

data class FinishReq(
    val videoId: Long,
    val action: String = "finish"
)

data class FinishVo(
    val videoId: Long,
    val action: String
)

// ---------- App 自更新（契约 3.11） ----------

/**
 * 检查更新响应（§3.11）：仅当服务器最新版本 > 客户端 [code] 时返回 200 + 本对象；
 * 同版本 / 客户端比服务器新 / 该平台尚未发布 → HTTP 204（空 body，见 ApiService.checkUpdate）。
 * 字段与服务器 UpdateCheckVo 严格同名（camelCase）。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String? = null,
    val mandatory: Boolean = false
)

// ---------- 远程投屏与设备（契约 §3.13，计划书 80-cast-detailed.md） ----------

/** POST /api/app/devices/register */
data class CastRegisterReq(
    val deviceId: String,
    val platform: String,
    val deviceName: String? = null
)

data class CastDeviceIdVo(
    val deviceId: String
)

/** GET /api/app/devices：同账号设备（online = 最近 30s 内有心跳，服务端内存判定） */
data class CastDeviceVo(
    val deviceId: String,
    val platform: String,
    val deviceName: String? = null,
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val casting: Boolean = false
)

/** POST /api/app/cast/request */
data class CastRequestReq(
    val targetDeviceId: String,
    val senderDeviceId: String,
    val movieId: Long,
    val videoId: Long,
    val positionMs: Long = 0L
)

data class CastRequestedVo(
    val requestId: Long,
    val expiresIn: Long,
    val targetName: String? = null
)

/** GET /api/app/cast/request/status（手机每 1s 轮询，上限 17s） */
data class CastStatusVo(
    val id: Long,
    val status: String,
    val targetName: String? = null,
    val endReason: String? = null
)

/** POST /api/app/cast/cancel（reason 可空；手机取消缺省 sender-cancel） */
data class CastIdReq(
    val id: Long,
    val reason: String? = null
)

/**
 * GET /api/app/cast/current。
 * 外层恒非空，"没有投屏"表达为 active=null —— 客户端 safeApiCall 把 data=null 当异常，
 * 因此服务端不能直接回 null data。
 */
data class CastCurrentVo(
    val active: CastBriefVo? = null
)

data class CastBriefVo(
    val id: Long,
    val status: String,
    val senderDeviceId: String? = null,
    val targetDeviceId: String? = null,
    val senderName: String? = null,
    val targetName: String? = null,
    val movieId: Long = 0L,
    val videoId: Long = 0L,
    val movieTitle: String? = null,
    val episodeLabel: String? = null,
    val coverUrl: String? = null,
    val positionMs: Long = 0L,
    val startedTime: Long = 0L
)

/** GET /api/app/cast/config */
data class CastConfigVo(
    val enabled: Boolean = true,
    val confirmSeconds: Int = 6,
    val requestTtlSeconds: Long = 15L,
    val pollIntervalMs: Long = 2000L
)

// ---------- 投屏接收侧（本机当目标端，计划书 80-cast-detailed.md §9.7） ----------

/** GET /api/app/cast/poll 的 pending 载荷：确认弹窗直接渲染它，不需要再查详情 */
data class CastPendingVo(
    val id: Long,
    val senderDeviceId: String? = null,
    val senderName: String? = null,
    val movieId: Long = 0L,
    val videoId: Long = 0L,
    val movieTitle: String? = null,
    val episodeLabel: String? = null,
    val coverUrl: String? = null,
    val positionMs: Long = 0L,
    val expireTime: Long = 0L,
    val confirmSeconds: Int = 6
)

/**
 * GET /api/app/cast/poll 的应答。
 * cancelledIds = 本机最近被取消/被接管的投屏 id（一次性下发）：
 * 弹窗正显示该 id → 关闭；正在播放该请求带来的集 → 停止播放回上一页。
 */
data class CastPollVo(
    val pending: CastPendingVo? = null,
    val cancelledIds: List<Long> = emptyList(),
    val confirmSeconds: Int = 6
)

/** POST /api/app/cast/accept（reason = user-yes | countdown） */
data class CastAcceptReq(
    val id: Long,
    val reason: String? = null
)

data class CastAcceptedVo(
    val id: Long,
    val movieId: Long = 0L,
    val videoId: Long = 0L,
    val positionMs: Long = 0L,
    val movieTitle: String? = null,
    val episodeLabel: String? = null
)
