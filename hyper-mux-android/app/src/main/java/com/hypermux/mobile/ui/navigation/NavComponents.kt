package com.hypermux.mobile.ui.navigation

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hypermux.mobile.data.model.SessionUserVo
import com.hypermux.mobile.ui.common.bounceClick

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
        androidx.compose.runtime.LaunchedEffect(Unit) {
            navController.navigate(Route.Login) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
        }
    } else {
        content()
    }
}

/**
 * 底部 Tab 统一跳转（幂等、可预测）：
 * - 已在该 Tab：直接忽略（避免重复入栈 / 无意义的重建）
 * - 否则：弹到起始 Tab（首页）后再进入目标 Tab，保证栈深度始终是「首页 + 当前 Tab」
 *
 * 不用 popUpTo+saveState/restoreState 那套：它与「从首页右上角图标 push 进历史」这类
 * 入口混用时会出现点底栏无反应的问题。
 */
fun NavController.navigateTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Route.Home) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * 「播放页」统一入口（防连点）：详情页「从第 N 集继续」/ 剧集格、首页「继续观看」、历史、下载页都走这里。
 *
 * 播放页转场持续数百毫秒，期间来源页仍在组合中、按钮**仍可点**：连点两下会 push 出两个
 * PlayerScreen（每个都会自建一个 ExoPlayer 实例），返回一次还会落在第二个播放页上——就是
 * 「快速点两下弹出两个窗口」的根因。
 *
 * 用 `launchSingleTop`：栈顶已经是播放页时不再入栈，第二次点击直接被忽略（导航状态是同步
 * 更新的，所以哪怕两次点击只差几毫秒也能挡住）。播放页内切集走「同屏换 MediaItem」不经导航，
 * 不受影响。
 */
fun NavController.navigatePlay(movieId: Long, videoId: Long) {
    navigate(Route.play(movieId, videoId)) { launchSingleTop = true }
}

/**
 * 「二级页」防连点通用实现（所有 push 型入口都走它）。
 *
 * 根因同 [navigatePlay]：转场期间来源页仍在组合中、可点，连点两下会 push 出两个同目的地页面
 * （顶上叠两个「影片详情」/「我的下载」/「搜索」…，返回要按两次）。
 * 判据：栈顶已是**同一目的地**（[pattern] 相同）**且参数一致**（[sameArgs] 成立）→ 忽略本次。
 * 点的是另一个目标（另一部片、另一个分类）仍照常入栈，不会吞掉用户的后一次点击。
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

/**
 * 「影片详情页」统一入口（防连点）：首页/分类/搜索/历史点卡片都走这里。
 * 栈顶已是**同一部片** → 忽略；换一部片正常入栈。
 */
fun NavController.navigateMovie(movieId: Long) =
    navigateOnce(Route.Movie, Route.movie(movieId)) { it.getLong("movieId") == movieId }

/** 「分类列表」统一入口（防连点）：同一分类重复点 → 忽略。 */
fun NavController.navigateCategory(categoryId: Long, name: String? = null) =
    navigateOnce(Route.Category, Route.category(categoryId, name)) {
        it.getLong("categoryId") == categoryId
    }

/** 「全局搜索」统一入口（防连点）。 */
fun NavController.navigateSearch() = navigateOnce(Route.Search, Route.Search)

/** 「历史」统一入口（防连点）：首页右上角图标 push 进历史也走这里。 */
fun NavController.navigateHistory() = navigateOnce(Route.History, Route.History)

/** 「我的下载」统一入口（防连点）。 */
fun NavController.navigateDownloads() = navigateOnce(Route.Downloads, Route.Downloads)

/** 「单片下载详情」统一入口（防连点）：同一部片重复点 → 忽略。 */
fun NavController.navigateDownloadDetail(movieId: Long) =
    navigateOnce(Route.DownloadDetail, Route.downloadDetail(movieId)) {
        it.getLong("movieId") == movieId
    }

/** 底部导航：首页 / 历史 / 我的（对齐爱奇艺：无胶囊底、选中品牌绿、图标 23dp / 文字 11sp） */
@Composable
fun BottomNavBar(navController: NavController) {
    val current by navController.currentBackStackEntryAsState()
    val currentRoute = current?.destination?.route
    // 未选中统一灰（爱奇艺风格）
    val inactiveColor = Color(0xFF8A8F99)

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // 注意：这里**不再**画顶部 1dp 发丝线（用户明确不要这条线），
        // 也不改用阴影/描边替代 —— 底栏只保留 surface 纯色背景，与页面背景自然相接。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = listOf(
                BottomItem(Route.Home, "首页", Icons.Filled.Home),
                BottomItem(Route.History, "历史", Icons.Filled.History),
                BottomItem(Route.Me, "我的", Icons.Filled.Person)
            )
            items.forEach { item ->
                val selected = currentRoute == item.route
                // 选中：图标轻微放大 + 上移 1dp（柔和，不夸张）
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.06f else 1f,
                    label = "navIconScale"
                )
                val offsetY by animateDpAsState(
                    targetValue = if (selected) (-1).dp else 0.dp,
                    label = "navIconOffset"
                )
                val tint = if (selected) MaterialTheme.colorScheme.primary else inactiveColor
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .bounceClick(pressedScale = 0.93f) { navController.navigateTab(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier
                            .size(23.dp)
                            .offset(y = offsetY)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint
                    )
                }
            }
        }
    }
}

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)
