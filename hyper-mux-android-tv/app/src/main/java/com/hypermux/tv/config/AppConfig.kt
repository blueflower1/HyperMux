package com.hypermux.tv.config

import com.hypermux.tv.BuildConfig

/** 全局阈值常量（与 TV 端 AppConfig 对齐，见 hyper-mux-tv/docs/plans/45-phone-app-detailed.md §3.1） */
object AppConfig {
    /**
     * 公开服务器地址：登录页「公开服务器」模式的内置节点。
     * 构建期经 -PhypermuxPublicServerUrl=http://host:port 注入（见 app/build.gradle.kts）；
     * 未注入时为空 → 不提供公开模式，用户必须自行输入服务器地址。
     * 仅代码层持有，UI 文案一律显示"公开服务器"、不出现该地址（契约 §4.3）。
     */
    val PRESET_SERVER_URL: String = BuildConfig.PRESET_SERVER_URL

    /** 是否内置了公开服务器（决定登录页/配置弹窗是否提供「公开服务器」档位） */
    val HAS_PRESET_SERVER: Boolean get() = PRESET_SERVER_URL.isNotBlank()

    /** 播放中断点上报间隔 */
    const val PROGRESS_REPORT_INTERVAL_MS = 10_000L

    /** position < 3s 不上报（对齐 web 端 reportNow） */
    const val PROGRESS_MIN_POSITION_MS = 3_000L

    /** 续播判定：positionMs > 30s 且 < duration - 30s */
    const val RESUME_THRESHOLD_MS = 30_000L

    /** 客户端"已看完"角标展示阈值（服务端在 duration-30s 自动置 finished） */
    const val NEAR_END_RATIO = 0.95

    /** 本地判过期提前量（401 兜底） */
    const val TOKEN_EXPIRY_BUFFER_MS = 300_000L

    /** 网络/5xx 自动重试次数与间隔 */
    const val OFFLINE_RETRY_TIMES = 2
    const val OFFLINE_RETRY_DELAY_MS = 1_500L

    /** 连播延迟（"即将播放下一集"角标，期间可取消） */
    const val PLAYER_AUTO_NEXT_DELAY_MS = 1_500L

    /** 搜索输入防抖 */
    const val SEARCH_DEBOUNCE_MS = 300L

    /** 分页大小 */
    const val PAGE_SIZE = 30

    /** 历史记录条数上限（服务端上限 50） */
    const val HISTORY_LIMIT = 50

    // ---- App 自更新（契约 §3.11）----

    /** 启动主动检查更新的节流间隔：距上次成功检查 24h 内不再主动查（手动检查不受限） */
    const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600_000L
}
