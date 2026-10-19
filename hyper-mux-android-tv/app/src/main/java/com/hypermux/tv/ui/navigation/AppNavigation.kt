@file:OptIn(ExperimentalComposeUiApi::class)

package com.hypermux.tv.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hypermux.tv.data.update.UpdateDownloadState
import com.hypermux.tv.ui.AuthViewModel
import com.hypermux.tv.ui.category.CategoryScreen
import com.hypermux.tv.ui.downloads.DownloadDetailScreen
import com.hypermux.tv.ui.downloads.MyDownloadsScreen
import com.hypermux.tv.ui.history.HistoryScreen
import com.hypermux.tv.ui.home.HomeScreen
import com.hypermux.tv.ui.login.LoginScreen
import com.hypermux.tv.ui.me.MeScreen
import com.hypermux.tv.ui.movie.MovieDetailScreen
import com.hypermux.tv.ui.player.PlayerScreen
import com.hypermux.tv.ui.search.SearchScreen
import com.hypermux.tv.ui.theme.Motion
import com.hypermux.tv.ui.tv.TvMessageOverlay
import com.hypermux.tv.ui.tv.TvNavRail
import com.hypermux.tv.ui.tv.shouldShowNavRail
import com.hypermux.tv.ui.update.UpdateDialog
import com.hypermux.tv.ui.update.UpdateViewModel
import kotlinx.coroutines.delay

/* ============================ 页面转场（TV 版） ============================
 * TV 的转场要**克制**：大屏 + 遥控器，位移大了会晃眼、还会让焦点落点看起来"漂移"。
 *  - 一级页（首页 / 历史 / 我的）：纯淡入淡出（左侧导航栏常驻，不参与转场）；
 *  - 二级页（分类 / 搜索 / 详情 / 下载）：整屏自右滑入 + 淡入（"推入"语义，与手机端一致）；
 *  - 播放页：快速淡入（全屏黑底，滑动位移没有意义）。
 * 时长统一走 Motion.*。
 */

private fun isTabRoute(route: String?): Boolean =
    route == Route.Home || route == Route.History || route == Route.Me

/** 一级页：淡入（无位移，避免大屏晃动） */
private val TabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.BASE, easing = Motion.Emphasized))
}

private val TabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

/** 二级页进入：整屏自右滑入 + 淡入 */
private val PageEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(Motion.BASE, easing = Motion.Emphasized)) { it / 4 } +
        fadeIn(tween(Motion.BASE, easing = Motion.Emphasized))
}

private val PageExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(120, easing = Motion.Ease))
}

private val PagePopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.BASE, easing = Motion.Emphasized))
}

private val PagePopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(Motion.BASE, easing = Motion.Emphasized)) { it / 4 } +
        fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

/** 播放页：快速淡入淡出（全屏） */
private val PlayEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.FAST, easing = Motion.Ease))
}

private val PlayPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val authVm: AuthViewModel = hiltViewModel()
    val session by authVm.session.collectAsStateWithLifecycle(initialValue = null)
    val updateVm: UpdateViewModel = hiltViewModel()

    // App 自更新：登录后 3s 的"启动空闲"时机做一次被动检查（24h 节流、静默失败、是否弹窗都在 VM 内决策）
    LaunchedEffect(session != null) {
        if (session != null) {
            delay(3_000)
            updateVm.autoCheckIfDue()
        }
    }

    val updateInfo by updateVm.dialog.collectAsStateWithLifecycle(initialValue = null)
    val updateDown by updateVm.down.collectAsStateWithLifecycle(initialValue = UpdateDownloadState.Down.Idle)
    // 安装权限提示：走 TV 提示层（系统 Toast 在电视上不可读）
    var message by remember { mutableStateOf<String?>(null) }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    // 左侧导航栏常驻（登录页 / 播放页除外）：TV 上底部导航反直觉，改左侧竖排（§7.1）
    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (shouldShowNavRail(currentRoute)) {
            TvNavRail(
                currentRoute = currentRoute,
                onNavigate = { navController.navigateTab(it) },
                onSearch = { navController.navigateSearch() }
            )
        }
        // focusRestorer：二级页返回后，焦点回到来源元素（§6.1 焦点恢复）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRestorer()
        ) {
            NavHost(
                navController = navController,
                startDestination = Route.Login,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) },
                exitTransition = { fadeOut(tween(Motion.FAST, easing = Motion.Ease)) },
                popEnterTransition = { fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) },
                popExitTransition = { fadeOut(tween(Motion.FAST, easing = Motion.Ease)) }
            ) {
                // ---- 登录 ----
                composable(Route.Login) {
                    if (session != null) {
                        LaunchedEffect(Unit) {
                            navController.navigate(Route.Home) {
                                popUpTo(Route.Login) { inclusive = true }
                            }
                        }
                    } else {
                        LoginScreen()
                    }
                }

                // ---- 首页（一级页） ----
                composable(
                    Route.Home,
                    enterTransition = TabEnterTransition,
                    exitTransition = TabExitTransition,
                    popEnterTransition = TabEnterTransition,
                    popExitTransition = TabExitTransition
                ) {
                    RequireSession(session, navController) {
                        HomeScreen(
                            navController = navController,
                            onOpenCategory = { id, name -> navController.navigateCategory(id, name) },
                            onOpenMovie = { id -> navController.navigateMovie(id) },
                            onPlay = { mid, vid -> navController.navigatePlay(mid, vid) }
                        )
                    }
                }

                // ---- 分类列表（二级页） ----
                composable(
                    Route.Category,
                    arguments = listOf(
                        navArgument("categoryId") { type = NavType.LongType },
                        navArgument("categoryName") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    ),
                    enterTransition = PageEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PagePopExitTransition
                ) {
                    val categoryId = it.arguments?.getLong("categoryId") ?: -1L
                    val categoryName = it.arguments?.getString("categoryName") ?: ""
                    RequireSession(session, navController) {
                        CategoryScreen(
                            navController = navController,
                            categoryId = categoryId,
                            categoryName = categoryName
                        )
                    }
                }

                // ---- 全局搜索（二级页） ----
                composable(
                    Route.Search,
                    enterTransition = PageEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PagePopExitTransition
                ) {
                    RequireSession(session, navController) {
                        SearchScreen(navController = navController)
                    }
                }

                // ---- 影片详情（二级页） ----
                composable(
                    Route.Movie,
                    arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
                    enterTransition = PageEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PagePopExitTransition
                ) {
                    val movieId = it.arguments?.getLong("movieId") ?: -1L
                    RequireSession(session, navController) {
                        MovieDetailScreen(
                            navController = navController,
                            movieId = movieId,
                            onPlay = { mid, vid -> navController.navigatePlay(mid, vid) }
                        )
                    }
                }

                // ---- 播放页（沉浸式全屏，无导航栏） ----
                composable(
                    Route.Play,
                    arguments = listOf(
                        navArgument("movieId") { type = NavType.LongType },
                        navArgument("videoId") { type = NavType.LongType }
                    ),
                    enterTransition = PlayEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PlayPopExitTransition
                ) {
                    val movieId = it.arguments?.getLong("movieId") ?: -1L
                    val videoId = it.arguments?.getLong("videoId") ?: -1L
                    RequireSession(session, navController) {
                        PlayerScreen(
                            navController = navController,
                            movieId = movieId,
                            videoId = videoId
                        )
                    }
                }

                // ---- 历史（一级页） ----
                composable(
                    Route.History,
                    enterTransition = TabEnterTransition,
                    exitTransition = TabExitTransition,
                    popEnterTransition = TabEnterTransition,
                    popExitTransition = TabExitTransition
                ) {
                    RequireSession(session, navController) {
                        HistoryScreen(
                            navController = navController,
                            onOpenMovie = { id -> navController.navigateMovie(id) },
                            onPlay = { mid, vid -> navController.navigatePlay(mid, vid) }
                        )
                    }
                }

                // ---- 我的下载（一级页：影片列表，二级页：单片集级管理） ----
                composable(
                    Route.Downloads,
                    enterTransition = PageEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PagePopExitTransition
                ) {
                    RequireSession(session, navController) {
                        MyDownloadsScreen(navController = navController)
                    }
                }

                composable(
                    Route.DownloadDetail,
                    arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
                    enterTransition = PageEnterTransition,
                    exitTransition = PageExitTransition,
                    popEnterTransition = PagePopEnterTransition,
                    popExitTransition = PagePopExitTransition
                ) {
                    // movieId 由 DownloadDetailViewModel 从导航参数（SavedStateHandle）读取
                    RequireSession(session, navController) {
                        DownloadDetailScreen(navController = navController)
                    }
                }

                // ---- 我的（一级页） ----
                composable(
                    Route.Me,
                    enterTransition = TabEnterTransition,
                    exitTransition = TabExitTransition,
                    popEnterTransition = TabEnterTransition,
                    popExitTransition = TabExitTransition
                ) {
                    RequireSession(session, navController) {
                        MeScreen(navController = navController)
                    }
                }
            }
        }
    }

    // 更新弹窗挂在窗口层（不受 NavHost 影响）：tab/二级页切换不打断它
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            down = updateDown,
            onDismiss = { updateVm.dismissUpdate() },
            onUpdate = { updateVm.startUpdate(it) },
            onInstall = {
                if (!updateVm.installNow()) {
                    message = "请在刚打开的设置页允许「安装未知应用」，再回来点「立即安装」"
                }
            }
        )
    }

    message?.let { TvMessageOverlay(message = it, onDismiss = { message = null }) }
}

/** 兼容保留：判断当前目的地是否属于"一级页"（供需要区分的调用方使用） */
@Suppress("unused")
private fun isTopLevel(route: String?) = isTabRoute(route)
