package com.hypermux.tv.ui.navigation

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.hypermux.tv.data.model.SessionUserVo

/**
 * 登录守卫：未登录 → 清栈回登录页。
 * （所有受保护目的地经此包装）
 */
@Composable
fun RequireSession(
    session: SessionUserVo?,
    navController: NavController,
    content: @Composable () -> Unit
) {
    if (session == null) {
        LaunchedEffect(Unit) {
            navController.navigate(Route.Login) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
        }
    } else {
        content()
    }
}

/**
 * 一级页（首页 / 历史 / 我的）统一跳转：
 * - 已在该页：忽略；
 * - 否则：弹回起始页（首页）后再进入目标页，保证栈深度固定为「首页 + 当前页」。
 */
fun NavController.navigateTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Route.Home) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * 「播放页」统一入口（防连点）：详情页主按钮/剧集格、首页「继续观看」、历史、下载页都走这里。
 *
 * TV 上遥控器连按比手机触屏更常见（一次按住会连发多次 `DPAD_CENTER`），因此这层防护更要保留：
 * 用 `launchSingleTop` 保证栈顶已是播放页时第二次点击直接被忽略
 * （播放页内切集走"同屏换 MediaItem"不经导航，不受影响）。
 */
fun NavController.navigatePlay(movieId: Long, videoId: Long) {
    navigate(Route.play(movieId, videoId)) { launchSingleTop = true }
}

/**
 * 投屏拉起播放（契约 §3.13）：带上手机端当前位置与来源设备名。
 *
 * 与 [navigatePlay] 一样用 `launchSingleTop`：TV 已在播放页时又收到投屏请求，
 * 直接把当前这一层换掉，不叠第二层播放页（ExoPlayer 实例会随目的地销毁）。
 */
fun NavController.navigatePlayCast(
    movieId: Long,
    videoId: Long,
    resumeMs: Long,
    senderName: String
) {
    navigate(Route.playCast(movieId, videoId, resumeMs, senderName)) { launchSingleTop = true }
}

/**
 * 「二级页」防连点通用实现（所有 push 型入口都走它）。
 *
 * 判据：栈顶已是**同一目的地**（[pattern] 相同）**且参数一致**（[sameArgs] 成立）→ 忽略本次。
 * 点的是另一个目标（另一部片、另一个分类）仍照常入栈。
 *
 * @param pattern 路由模板，即 `Route.Xxx` 常量（`currentDestination.route` 返回的是模板）。
 * @param route 实际要跳转的地址（带实参拼出来的）。
 * @param sameArgs 带参目的地用来判「是不是同一个」；无参目的地用默认 `{ true }`。
 */
private fun NavController.navigateOnce(
    pattern: String,
    route: String,
    sameArgs: (Bundle) -> Boolean = { true }
) {
    if (currentDestination?.route == pattern) {
        val args = currentBackStackEntry?.arguments
        if (args == null || sameArgs(args)) return
    }
    navigate(route)
}

/** 「影片详情页」统一入口（防连点）。 */
fun NavController.navigateMovie(movieId: Long) =
    navigateOnce(Route.Movie, Route.movie(movieId)) { it.getLong("movieId") == movieId }

/** 「分类列表」统一入口（防连点）：同一分类重复点 → 忽略。 */
fun NavController.navigateCategory(categoryId: Long, name: String? = null) =
    navigateOnce(Route.Category, Route.category(categoryId, name)) {
        it.getLong("categoryId") == categoryId
    }

/** 「历史」统一入口（防连点）。 */
fun NavController.navigateHistory() = navigateOnce(Route.History, Route.History)

/**
 * 返回上级（二级页 / 详情页顶栏的返回按钮与 BACK 键共用）。
 * 栈里没有上一级时（如直接从深层链进入）退到首页，避免"按返回没反应"。
 */
fun NavController.navigateUpOrHome() {
    if (!popBackStack()) {
        navigateTab(Route.Home)
    }
}
