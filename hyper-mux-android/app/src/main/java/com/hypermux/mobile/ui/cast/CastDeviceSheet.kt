package com.hypermux.mobile.ui.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hypermux.mobile.data.model.CastDeviceVo
import com.hypermux.mobile.ui.common.HyperBottomSheet
import com.hypermux.mobile.ui.common.HyperChip
import com.hypermux.mobile.ui.theme.Spacing
import com.hypermux.mobile.ui.theme.hyperColors

/**
 * 投屏目标选择弹层（计划书 80-cast-detailed.md §8.4）。
 *
 * 只列**同账号**的其它设备（接收端 = 电视 / 网页端 / 电脑端 / 另一台手机）：在线可点，离线灰显不可点
 * （点了也没意义 —— 服务端会直接 409）。
 * 本期手机端只投送、不遥控，所以这里选完就发请求，没有后续控制面。
 */
@Composable
fun CastDeviceSheet(
    vm: CastViewModel,
    movieId: Long,
    videoId: Long,
    positionMs: Long,
    onDismiss: () -> Unit
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val loading by vm.loadingDevices.collectAsStateWithLifecycle()
    val lastTarget by vm.lastTargetId.collectAsStateWithLifecycle()

    // sideWhenWide：横屏（宽扁窗口）自动改挂右缘抽屉，竖屏仍是底部弹层
    HyperBottomSheet(onDismissRequest = onDismiss, sideWhenWide = true) {
        SheetHeader(onRefresh = { vm.refreshDevices() }, loading = loading)

        if (devices.isEmpty()) {
            EmptyHint(loading = loading)
        } else {
            // ★ 这里不能用 LazyColumn ★
            // 懒列表在弹层内不会收缩到内容高，而是铺满剩余视口：弹层被顶到 HyperBottomSheet 的
            // 90% 窗口上限，设备行与底部说明之间空出一大段。横屏窗口矮（可用高仅 ~400dp），
            // 弹层直接吃掉大半个屏幕（真机反馈"横屏投屏弹窗异常"）。
            // 同账号的电视 / 网页端 / 电脑端设备只有几台，普通 Column 足够；多设备时内部滚动，仍不撑破弹层上限。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .verticalScroll(rememberScrollState())
            ) {
                devices.forEach { d ->
                    DeviceRow(
                        device = d,
                        isLastUsed = d.deviceId == lastTarget,
                        onClick = {
                            vm.castTo(d, movieId, videoId, positionMs)
                            onDismiss()
                        }
                    )
                }
                Spacer(Modifier.height(Spacing.md))
            }
        }

        Text(
            text = "投屏后目标端会从手机当前的位置接着播；送达后手机端不再跟踪这条投屏，结束请在目标端上操作。",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
    }
}

@Composable
private fun ColumnScope.SheetHeader(onRefresh: () -> Unit, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "投屏目标",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "选择同一账号下已登录的电视 / 网页端 / 电脑端 / 手机端",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable(enabled = !loading) { onRefresh() },
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(text = "↻", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun DeviceRow(device: CastDeviceVo, isLastUsed: Boolean, onClick: () -> Unit) {
    val hc = hyperColors()
    val enabled = device.online
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            )
            .border(1.dp, hc.line, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (device.online) MaterialTheme.colorScheme.primary else hc.lineStrong)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = castTargetName(device.platform, device.deviceName),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            // 在线整条跳品牌绿（点 + 文案同一色系），一眼能看出这台能投；离线保持次要灰
            Text(
                text = if (device.online) "在线" else "离线 · 请先在目标端打开 HyperMux",
                fontSize = 11.5.sp,
                fontWeight = if (device.online) FontWeight.SemiBold else FontWeight.Normal,
                color = if (device.online) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isLastUsed && enabled) {
            HyperChip(text = "上次使用", highlight = true)
        }
        if (device.casting) {
            HyperChip(text = "正在被投屏")
        }
    }
}

@Composable
private fun EmptyHint(loading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (loading) "正在查找设备…" else "没有发现同账号的投屏目标设备",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "请先在电视 / 网页端 / 电脑端 / 手机端打开 HyperMux 并登录与手机相同的账号，再点右上角刷新。",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
    }
}
