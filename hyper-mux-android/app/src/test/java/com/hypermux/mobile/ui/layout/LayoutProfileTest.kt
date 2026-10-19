package com.hypermux.mobile.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PAD 适配的**手机零回归**守卫。
 *
 * 手机侧已经过 10 轮真机修复（docs/build-and-acceptance.md），任何自适应改动都不允许改变
 * 360dp 手机上的列数与留白。这里把「手机不变」变成可执行断言，而不是靠人记。
 */
class LayoutProfileTest {

    /** 360dp（真机实测宽度）必须逐位复现 0.1.11 的取值 */
    @Test
    fun compactReproducesPhoneBaseline() {
        val p = profileFor(360.dp)
        assertEquals(LayoutTier.Compact, p.tier)
        assertEquals(Dp.Infinity, p.gridMaxWidth)
        assertEquals(Dp.Infinity, p.formMaxWidth)
        assertEquals(Dp.Infinity, p.sheetMaxWidth)
        assertEquals(0.dp, p.gridSideInset)
        assertEquals(2, p.homeColumns)
        assertEquals(3, p.tileColumns)
        assertEquals(5, p.episodeColumns)
        assertEquals(6, p.playerEpisodeColumns)
        assertEquals(4, p.playerPanelEpisodeColumns)
    }

    /** 小屏手机与手机横屏都必须是 Compact：档位按窗口最小边判定 */
    @Test
    fun phonesStayCompact() {
        assertEquals(LayoutTier.Compact, profileFor(320.dp).tier)
        // 手机横过来：窗口 740×360dp，最小边仍是 360dp → 仍 Compact（旋转不会改变手机布局）
        assertEquals(LayoutTier.Compact, profileFor(360.dp, 740.dp).tier)
        assertEquals(0.dp, profileFor(360.dp, 740.dp).gridSideInset)
    }

    /** 平板档位：同一台平板横竖过来档位不跳变 */
    @Test
    fun tabletTiers() {
        // 11" 平板 800×1280dp
        assertEquals(LayoutTier.Medium, profileFor(800.dp, 800.dp).tier)
        assertEquals(LayoutTier.Medium, profileFor(800.dp, 1280.dp).tier)
        // 12.9" 平板 888×1400dp
        assertEquals(LayoutTier.Expanded, profileFor(888.dp, 1400.dp).tier)
        // 7" 平板 640×400dp：最小边 400dp < 600 → 与平台 sw600 语义一致，走 Compact
        assertEquals(LayoutTier.Compact, profileFor(400.dp, 640.dp).tier)
    }

    /** 宽窗口把内容收窄并居中（gridSideInset = (窗口宽 − gridMaxWidth) / 2） */
    @Test
    fun gridSideInsetCentersWideWindows() {
        assertEquals(0.dp, profileFor(360.dp).gridSideInset)
        assertEquals(0.dp, profileFor(800.dp, 600.dp).gridSideInset)
        // 1280dp 宽、Expanded 档 gridMaxWidth 960 → 左右各 160dp
        assertEquals(160.dp, profileFor(888.dp, 1280.dp).gridSideInset)
    }

    /** 缓存弹层按实测宽度算列数：手机仍 5 列（今天就是 5） */
    @Test
    fun cacheSheetKeepsFiveColumnsOnPhone() {
        assertEquals(5, columnsFor(332.dp, 56.dp, 10.dp, 5))
        // 平板上弹层被 sheetMaxWidth 封顶到 640dp → 9 列、格子仍约 60dp
        assertEquals(9, columnsFor(612.dp, 56.dp, 10.dp, 12))
    }

    /** 播放页竖屏选集：手机仍 6 列 */
    @Test
    fun playerEpisodePickerKeepsSixColumnsOnPhone() {
        assertEquals(6, columnsFor(332.dp, 46.dp, 8.dp, 6))
        // 1280dp 宽窗口：按实测会算出 23 列，被档位上限 12 收住
        assertEquals(12, columnsFor(1252.dp, 46.dp, 8.dp, 12))
    }
}
