package com.hypermux.tv.ui.navigation

import android.net.Uri

/** 路由表（计划书 §3.3） */
object Route {
    const val Login = "login"
    const val Home = "home"
    const val Category = "category/{categoryId}"
    const val Movie = "movie/{movieId}"

    /**
     * 播放页。两个可选查询参数只服务于远程投屏（契约 §3.13）：
     * - `castResumeMs`：投屏起点（手机端当前的播放位置），-1 = 不是投屏进来的（走常规续播判定）；
     * - `castSender`：发起投屏的手机设备名，播放页顶栏徽标显示。
     * 两者都有 defaultValue，因此现有 `play/{movieId}/{videoId}` 调用点一行都不用改。
     */
    const val Play = "play/{movieId}/{videoId}?castResumeMs={castResumeMs}&castSender={castSender}"
    const val History = "history"
    const val Me = "me"

    /** 分类列表页；name 经查询参数透传（顶栏标题展示用，可空） */
    fun category(categoryId: Long, name: String? = null): String {
        val base = "category/$categoryId"
        return if (!name.isNullOrBlank()) {
            "$base?categoryName=${Uri.encode(name)}"
        } else {
            base
        }
    }

    fun movie(movieId: Long) = "movie/$movieId"

    fun play(movieId: Long, videoId: Long) = "play/$movieId/$videoId"

    /** 投屏拉起播放：带上起点与来源设备名 */
    fun playCast(movieId: Long, videoId: Long, resumeMs: Long, senderName: String): String =
        "play/$movieId/$videoId" +
            "?castResumeMs=$resumeMs" +
            "&castSender=${Uri.encode(senderName)}"
}
