package com.hypermux.mobile.ui.navigation

import android.widget.Toast
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.hypermux.mobile.ui.AuthViewModel
import com.hypermux.mobile.ui.home.HomeScreen
import com.hypermux.mobile.ui.history.HistoryScreen
import com.hypermux.mobile.ui.login.LoginScreen
import com.hypermux.mobile.ui.me.MeScreen
import com.hypermux.mobile.ui.category.CategoryScreen
import com.hypermux.mobile.ui.downloads.DownloadDetailScreen
import com.hypermux.mobile.ui.downloads.MyDownloadsScreen
import com.hypermux.mobile.ui.search.SearchScreen
import com.hypermux.mobile.ui.movie.MovieDetailScreen
import com.hypermux.mobile.ui.player.PlayerScreen
import com.hypermux.mobile.ui.theme.Motion
import com.hypermux.mobile.data.update.UpdateDownloadState
import com.hypermux.mobile.ui.update.UpdateDialog
import com.hypermux.mobile.ui.update.UpdateViewModel
import kotlinx.coroutines.delay

/* ============================ 页面转场 ============================
 * 按「目的地类型」区分转场，而不是所有页面共用一种生硬位移：
 *  - 底部 Tab（首页 / 历史 / 我的）：淡入淡出 + 按 Tab 顺序的方向性位移（屏宽 11%）+ 96% 缩放起步，
 *    位移用 spring 带一点回弹；方向由 route 顺序（Home=0 / History=1 / Me=2）判断；
 *  - 二级页（分类 / 搜索 / 详情）：卡片栈——新页整屏自右滑入，旧页淡出并压到 94%；
 *    返回时被露出的页淡入 + 由 94% 弹回，退出的页整屏向右滑出；
 *  - 播放页：自底部上滑 1/3 屏进入、下滑退出，点开更跟手。
 * 时长 / 缓动统一走 Motion.*（150~300ms）与 spring，不引入任何依赖。
 */

/** 底部 Tab 顺序（判断 Tab 之间的位移方向）：首页 0 → 历史 1 → 我的 2 */
private fun tabOrder(route: String?): Int = when (route) {
    Route.Home -> 0
    Route.History -> 1
    Route.Me -> 2
    else -> 0
}

private fun isTabRoute(route: String?): Boolean =
    route == Route.Home || route == Route.History || route == Route.Me

/** 本次转场是否朝「下一个 Tab」推进（首页→历史→我的 为正方向，内容自右向左推进） */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isForwardTab(): Boolean =
    tabOrder(targetState.destination.route) >= tabOrder(initialState.destination.route)

/** Tab 位移/淡入共用的弹性 spring（dampingRatio 0.86 / stiffness 380，回弹更明显） */
private val FloatTabSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)
private val OffsetTabSpring = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 380f)

/** 二级页整屏滑入的 spring（约 260ms 量级，几乎不回弹） */
private val FloatPageSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 300f)
private val OffsetPageSpring = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 300f)

/** 播放页上滑的 spring（跟手、平稳落位） */
private val FloatPlaySpring = spring<Float>(dampingRatio = 0.92f, stiffness = 320f)
private val OffsetPlaySpring = spring<IntOffset>(dampingRatio = 0.88f, stiffness = 320f)

/** 底部 Tab：淡入 + 96% 缩放 + 按方向滑入（11% 屏宽），spring 更弹 */
private val TabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    val dir = if (isForwardTab()) 1f else -1f
    fadeIn(FloatTabSpring) +
        scaleIn(initialScale = 0.96f, animationSpec = FloatTabSpring) +
        slideInHorizontally(animationSpec = OffsetTabSpring) { (it * 0.11f * dir).toInt() }
}

/** Tab 退出：Tab→Tab 时反向滑出 + 淡出；Tab→二级页 时淡出 + 压到 94%（卡片栈） */
private val TabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabRoute(targetState.destination.route)) {
        val dir = if (isForwardTab()) 1f else -1f
        fadeOut(tween(Motion.FAST, easing = Motion.Ease)) +
            slideOutHorizontally(animationSpec = OffsetTabSpring) { (-it * 0.11f * dir).toInt() }
    } else {
        fadeOut(tween(120, easing = Motion.Ease)) +
            scaleOut(targetScale = 0.94f, animationSpec = tween(Motion.BASE, easing = Motion.Ease))
    }
}

/** 从二级页返回 Tab：淡入 + 由 98% 弹回（配合旧页向右滑出，像卡片被抽走） */
private val TabPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) +
        scaleIn(
            initialScale = 0.98f,
            animationSpec = tween(Motion.BASE, easing = Motion.Emphasized)
        )
}

private val TabPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

/** 二级页进入：整屏自右滑入 + 淡入（卡片栈上层被推入） */
private val PageEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(animationSpec = OffsetPageSpring) { it } +
        fadeIn(FloatPageSpring)
}

/** 二级页被更深一层页面盖住：淡出 + 压到 94%（像被推到后面） */
private val PageExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(120, easing = Motion.Ease)) +
        scaleOut(targetScale = 0.94f, animationSpec = tween(Motion.BASE, easing = Motion.Ease))
}

/** 二级页返回：被露出的页淡入 + 由 94% 弹回 */
private val PagePopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) +
        scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(Motion.BASE, easing = Motion.Emphasized)
        )
}

/** 二级页返回：退出的页整屏向右滑出 + 淡出 */
private val PagePopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(Motion.BASE, easing = Motion.Emphasized)) { it } +
        fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

/** 播放页进入：自底部上滑 1/3 屏 + 淡入 */
private val PlayEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInVertically(animationSpec = OffsetPlaySpring) { it / 3 } +
        fadeIn(FloatPlaySpring)
}

/** 播放页返回：向下滑出 + 淡出 */
private val PlayPopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutVertically(tween(Motion.BASE, easing = Motion.Emphasized)) { it / 3 } +
        fadeOut(tween(Motion.FAST, easing = Motion.Ease))
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val authVm: AuthViewModel = hiltViewModel()
    val session by authVm.session.collectAsStateWithLifecycle(initialValue = null)
    val updateVm: UpdateViewModel = hiltViewModel()

    // App 自更新（§3.11）：登录后 3s 的"启动空闲"时机做一次被动检查（让位首屏加载；
    // 24h 节流、静默失败、是否弹窗都在 UpdateViewModel 内决策）
    LaunchedEffect(session != null) {
        if (session != null) {
            delay(3_000)
            updateVm.autoCheckIfDue()
        }
    }

    val updateInfo by updateVm.dialog.collectAsStateWithLifecycle(initialValue = null)
    val updateDown by updateVm.down.collectAsStateWithLifecycle(initialValue = UpdateDownloadState.Down.Idle)

    NavHost(
        navController = navController,
        startDestination = Route.Login,
        // Android 15（targetSdk 35）强制 edge-to-edge：内容会画到系统状态栏下面，
        // 这里统一给状态栏留出安全区，避免顶栏/Logo 被状态栏遮挡。
        // （全屏播放时系统栏被隐藏，insets 自动为 0，不影响沉浸式播放）
        // background 加在内缩之前：状态栏那条安全区也铺页面底色（否则会露出窗口
        // windowBackground 的深色，浅色主题下顶栏就是"黑底"，与内容割裂）。
        // 系统栏图标明暗由 HyperMuxTheme 跟随 App 主题设置（不只跟随系统深色开关）。
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
        // NavHost 级默认转场：只给登录页兜底（其余目的地都在 composable(...) 里显式指定）
        enterTransition = { fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) },
        exitTransition = { fadeOut(tween(Motion.FAST, easing = Motion.Ease)) },
        popEnterTransition = { fadeIn(tween(Motion.BASE, easing = Motion.Emphasized)) },
        popExitTransition = { fadeOut(tween(Motion.BASE, easing = Motion.Ease)) }
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

        // ---- 首页（底部导航，Tab 转场） ----
        composable(
            Route.Home,
            enterTransition = TabEnterTransition,
            exitTransition = TabExitTransition,
            popEnterTransition = TabPopEnterTransition,
            popExitTransition = TabPopExitTransition
        ) {
            RequireSession(session, navController) {
                HomeScreen(
                    navController = navController,
                    onOpenCategory = { id -> navController.navigateCategory(id) },
                    onOpenMovie = { id -> navController.navigateMovie(id) },
                    onOpenSearch = { navController.navigateSearch() },
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

        // ---- 播放页（更短时长，跟手） ----
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

        // ---- 历史（底部导航，Tab 转场） ----
        composable(
            Route.History,
            enterTransition = TabEnterTransition,
            exitTransition = TabExitTransition,
            popEnterTransition = TabPopEnterTransition,
            popExitTransition = TabPopExitTransition
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

        // ---- 我的（底部导航，Tab 转场） ----
        composable(
            Route.Me,
            enterTransition = TabEnterTransition,
            exitTransition = TabExitTransition,
            popEnterTransition = TabPopEnterTransition,
            popExitTransition = TabPopExitTransition
        ) {
            RequireSession(session, navController) {
                MeScreen(navController = navController)
            }
        }
    }

    // 更新弹窗挂在窗口层（不受 NavHost 影响）：Tab / 二级页切换不打断它；
    // 强制更新由 UpdateDialog 内部去掉「稍后」并禁用蒙层 / 返回键，未更新前挡在 App 最上层
    updateInfo?.let { info ->
        val ctx = LocalContext.current
        UpdateDialog(
            info = info,
            down = updateDown,
            onDismiss = { updateVm.dismissUpdate() },
            onUpdate = { updateVm.startUpdate(it) },
            onInstall = {
                // 用户还没允许「安装未知应用」时，已跳系统设置页，这里补一句提示
                if (!updateVm.installNow()) {
                    Toast.makeText(ctx, "请在刚打开的设置页允许安装，再回来点「立即安装」", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
