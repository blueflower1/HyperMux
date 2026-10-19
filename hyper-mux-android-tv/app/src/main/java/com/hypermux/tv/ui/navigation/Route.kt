package com.hypermux.tv.ui.navigation

import android.net.Uri

/** 路由表（计划书 §3.3） */
object Route {
    const val Login = "login"
    const val Home = "home"
    const val Category = "category/{categoryId}"
    const val Search = "search"
    const val Movie = "movie/{movieId}"
    const val Play = "play/{movieId}/{videoId}"
    const val History = "history"
    const val Me = "me"
    const val Downloads = "downloads"
    const val DownloadDetail = "downloads/{movieId}"

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

    /** 「我的下载」一级页（影片列表，入口在「我的」页） */
    fun downloads() = Downloads

    /** 「我的下载」二级页：单部影片的下载中/已下载集级列表 */
    fun downloadDetail(movieId: Long) = "downloads/$movieId"
}
