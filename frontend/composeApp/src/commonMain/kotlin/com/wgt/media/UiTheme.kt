package com.wgt.media

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * UI 视觉口径常量 —— 统一管理圆角 / 间距 / 阴影 / 尺寸。
 *
 * 各组件引用这里的 [Dimens] 与 [AppShapes]，避免散落各处的魔法数字
 * 造成视觉不统一（同一个"卡片圆角"在不同文件里取值 8/12/16dp）。
 *
 * 设计口径（对标百度网盘 / 小米相册的"精致"观感）：
 * - 卡片圆角统一 [Dimens.cardCornerRadius] = 16dp（与 M3 medium 组件持平）。
 * - 网格图片圆角 [Dimens.gridThumbCornerRadius] = 12dp，比旧的 16dp 略小，
 *   更贴合瀑布流密集排列，但保留明显圆角感。
 * - 徽标 pill 圆角 [Dimens.badgeCornerRadius] = 8dp（胶囊感，比旧的 4dp 更柔和）。
 * - 卡片默认 elevation = [Dimens.cardElevation] = 2dp（兼顾层次与克制）。
 *
 * 色彩仍统一走 MaterialTheme.colorScheme，此处只管几何尺寸。
 */
object Dimens {
    // ── 圆角 ──
    /** 普通卡片 / 列表项圆角（我的-Tab 卡片、相册卡片等）。 */
    val cardCornerRadius = 16.dp
    /** 网格缩略图圆角。 */
    val gridThumbCornerRadius = 12.dp
    /** 徽标 / pill 圆角（类型徽章、时长标签、Live 标记）。 */
    val badgeCornerRadius = 8.dp
    /** 搜索栏胶囊圆角（完全圆角的 pill 形态）。 */
    val searchBarCornerRadius = 24.dp
    /** 全屏对话框 / 大面板圆角。 */
    val dialogCornerRadius = 20.dp

    // ── 间距 ──
    /** 网格横向间距。 */
    val gridHorizontalSpacing = 3.dp
    /** 网格纵向间距。 */
    val gridVerticalSpacing = 5.dp
    /** 网格外边距（contentPadding）。 */
    val gridContentPadding = 6.dp
    /** 标准内容水平内边距（标题行、搜索栏周围）。 */
    val screenHorizontalPadding = 16.dp

    // ── 阴影 / elevation ──
    /** 普通卡片默认 elevation。 */
    val cardElevation = 2.dp
    /** 选中态卡片 elevation（更高，强化选中强调）。 */
    val selectedCardElevation = 4.dp
    /** 底部导航栏 elevation（顶部细分割线感）。 */
    val bottomBarElevation = 0.dp
    /** 中间凸起按钮阴影。 */
    val centerFabElevation = 8.dp

    // ── 尺寸 ──
    /** 普通徽标高度（紧凑 pill）。 */
    val badgeHeight = 18.dp
    /** 底部导航选中指示圆点直径。 */
    val navIndicatorDotSize = 4.dp
    /** 底部导航图标尺寸。 */
    val navIconSize = 24.dp
    /** 中间凸起按钮直径。 */
    val centerFabSize = 56.dp
}

/**
 * 全局 [Shapes] —— 统一圆角口径，供 [androidx.compose.material3.MaterialTheme]
 * 默认使用。组件若直接传 shape 参数则引用 [Dimens] 中的常量，二者保持一致。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.badgeCornerRadius),
    small = RoundedCornerShape(Dimens.gridThumbCornerRadius),
    medium = RoundedCornerShape(Dimens.cardCornerRadius),
    large = RoundedCornerShape(Dimens.dialogCornerRadius),
    extraLarge = RoundedCornerShape(Dimens.searchBarCornerRadius)
)
