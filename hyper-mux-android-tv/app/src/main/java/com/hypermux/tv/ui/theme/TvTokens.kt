package com.hypermux.tv.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV（10-foot UI）规格集中定义。
 *
 * 设计原则（48-tv-app-detailed.md §5.1）：**不用系统 fontScale 机械缩放**，
 * 而是按 1080p 基准给出固定 `sp`/`dp` 值 —— 低端盒子（多为 720p/1080p，density 被厂商改过）
 * 不会把字放得过大，4K 盒子也不会过小。
 */
object TvType {
    /** 详情/登录页主标题 */
    val hero = 40.sp
    /** 二级页顶栏标题 */
    val pageTitle = 30.sp
    /** 区段标题（首页 rail 标题） */
    val section = 28.sp
    /** 卡片标题 */
    val cardTitle = 22.sp
    /** 按钮 / 正文 */
    val body = 20.sp
    /** 辅助信息 / 元数据 */
    val meta = 18.sp
    /** 角标（集数 / 评分） */
    val badge = 16.sp
    /** 左侧导航项 */
    val nav = 18.sp
}

/** TV 尺寸规格 */
object TvSize {
    /** 左侧导航栏宽度 */
    val navRailWidth = 240.dp
    /** 导航项行高 */
    val navItemHeight = 64.dp
    /** 焦点描边宽度（1080p 下清晰可见） */
    val focusBorder = 3.dp
    /** 焦点放大比例 */
    const val focusScale = 1.06f

    /** 首页横向 rail 卡片宽（16:9 横版剧照） */
    val cardLandscape = 240.dp
    /** 首页横向 rail 卡片宽（2:3 竖版海报） */
    val cardPortrait = 160.dp
    /** 继续观看横幅（横版剧照）高度 */
    val continueCardHeight = 180.dp
    /** 详情页左侧竖版海报宽（2:3 → 高约 360dp，约占 1080p 屏高 2/3） */
    val detailPoster = 240.dp
    /** 剧集方形格边长 */
    val episodeCell = 72.dp

    /** 按钮高度 */
    val buttonHeight = 56.dp
    /** 图标按钮边长 / 图标尺寸 */
    val iconButton = 52.dp
    val iconSize = 28.dp
    /** 设置项行高 */
    val infoRowHeight = 64.dp

    /** 分类/搜索网格列数（1080p 基准；4K 由 BoxWithConstraints 放宽） */
    const val gridColumnsHd = 5
    const val gridColumnsFhd = 6
    /** 详情页剧集网格列数 */
    const val episodeColumns = 9

    /** 弹窗宽度上限（TV 视距远，弹窗可以更宽） */
    val dialogMaxWidth = 720.dp
}

/** 焦点遍历相关的常量与顺序（供 KeyBindings/焦点落点决策复用，便于单测） */
object TvFocus {
    /** 左侧导航与内容区之间的分隔：导航在左，内容在右 */
    const val NAV_TO_CONTENT = "nav→content"
}
