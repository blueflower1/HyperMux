package com.hypermux.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hypermux.tv.ui.navigation.Route
import com.hypermux.tv.ui.theme.Spacing
import com.hypermux.tv.ui.theme.TvSize
import com.hypermux.tv.ui.theme.TvType
import com.hypermux.tv.ui.theme.hyperColors

/**
 * 左侧导航栏（48-tv-app-detailed.md §7.1）：
 * 手机端的**底部 Tab（首页/历史/我的）**，在 TV 上合并为左侧竖排导航；
 * 搜索已并入首页（首页 chip 行「搜索」胶囊 → 页内搜索模式），不再占导航项。
 *
 * 焦点规则（§6.2）：
 *  - 导航项之间 `UP/DOWN` 移动；
 *  - 从导航项 `RIGHT` → 内容区（由内容区的首个可聚焦元素自然接管）；
 *  - 从内容区 `LEFT` → 回到导航（`focusRestorer` 记住上次的导航项）。
 */
@Composable
fun TvNavRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val hc = hyperColors()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(TvSize.navRailWidth)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        BrandHeader()
        Spacer(Modifier.height(Spacing.xxl))

        RailItem(
            label = "首页",
            icon = Icons.Filled.Home,
            selected = currentRoute == Route.Home,
            focusRequester = focusRequester,
            onClick = { onNavigate(Route.Home) }
        )
        RailItem(
            label = "历史",
            icon = Icons.Filled.History,
            selected = currentRoute == Route.History,
            onClick = { onNavigate(Route.History) }
        )
        RailItem(
            label = "我的",
            icon = Icons.Filled.Person,
            selected = currentRoute == Route.Me,
            onClick = { onNavigate(Route.Me) }
        )
        // 搜索已并入首页（chip 行「搜索」胶囊 → 页内搜索模式），不再占导航项

        Spacer(Modifier.weight(1f))
        Text(
            text = "HyperMux TV",
            fontSize = TvType.badge,
            color = hc.text3,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun BrandHeader() {
    val hc = hyperColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(hc.primaryGradient)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "H",
                color = Color.White,
                fontSize = TvType.cardTitle,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = "HyperMux TV",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = TvType.cardTitle,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * 导航项：选中态 = 左侧品牌绿竖条 + 品牌绿文字 + 12% 品牌绿底；焦点态 = tvClickable 的放大 + 描边。
 */
@Composable
private fun RailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TvSize.navItemHeight)
            .background(if (selected) primary.copy(alpha = 0.12f) else Color.Transparent, shape)
            .focusRequesterIf(focusRequester)
            .tvClickable(shape = shape, focusedScale = 1.02f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) primary else Color.Transparent)
        )
        Spacer(Modifier.width(Spacing.md))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TvSize.iconSize)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = label,
            color = tint,
            fontSize = TvType.nav,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 导航栏是否在该路由显示（登录页与播放页为沉浸式，不显示） */
fun shouldShowNavRail(route: String?): Boolean =
    route != null && route != Route.Login && route != Route.Play
