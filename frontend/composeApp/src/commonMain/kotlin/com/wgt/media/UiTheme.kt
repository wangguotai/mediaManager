package com.wgt.media

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.wgt.AppTypography

/**
 * UI 视觉口径常量 —— 统一管理圆角 / 间距 / 阴影 / 尺寸。
 *
 * 各组件引用这里的 [Dimens] 与 [AppShapes]，避免散落各处的魔法数字
 * 造成视觉不统一（同一个"卡片圆角"在不同文件里取值 8/12/16dp）。
 *
 * 设计口径（对标百度网盘 / 小米相册的"精致"观感）：
 * - 卡片圆角统一 [cardCornerRadius] = 16dp（与 M3 medium 组件持平）。
 * - 网格图片圆角 [gridThumbCornerRadius] = 12dp，比旧的 16dp 略小，
 *   更贴合瀑布流密集排列，但保留明显圆角感。
 * - 徽标 pill 圆角 [badgeCornerRadius] = 8dp（胶囊感，比旧的 4dp 更柔和）。
 * - 卡片默认 elevation = [cardElevation] = 2dp（兼顾层次与克制）。
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

    // ── 间距规范（M3 Expressive 4/8/16/24 节奏）──
    /** 紧凑间距：图标与紧邻文本、徽章内边距等。 */
    val spacingSmall = 4.dp
    /** 标准小间距：行内元素分隔、CardScaffold header↔content。 */
    val spacingMedium = 8.dp
    /** 标准间距：卡片内边距、空态元素分隔。 */
    val spacingLarge = 16.dp
    /** 宽松间距：空态外边距、大分区留白。 */
    val spacingXLarge = 24.dp

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
    /** 扁平卡片 elevation（CardScaffold 等克制层次场景，1dp）。 */
    val cardElevationFlat = 1.dp
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
    /** 设置项行高（SettingsRow / SwitchRow 统一高度）。 */
    val listItemHeight = 56.dp
    /** 设置项行水平内边距（与 [screenHorizontalPadding] 对齐，语义独立）。 */
    val listItemHorizontalPadding = 16.dp
    /** 设置项行 / 分区标题里的图标尺寸。 */
    val settingsRowIconSize = 24.dp
    /** 空态主图标尺寸（比行内图标大一档，强调空态视觉权重）。 */
    val emptyStateIconSize = 48.dp
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

// ── Expressive 扩展形状（独立于 [AppShapes] 的 5 档，用于特定组件）──

/**
 * 中型卡片形状 —— [CardScaffold] 等统一卡片骨架使用。
 * 等价 [AppShapes.medium]（圆角 [Dimens.cardCornerRadius]），独立命名以表达「卡片」语义。
 * 放在顶层 val 而非 [AppShapes] 内，是因为 [AppShapes] 是 M3 [Shapes] 类型，
 * 无法携带额外命名成员；组件按名引用此 val 即可。
 */
val mediumCardShape = RoundedCornerShape(Dimens.cardCornerRadius)

/**
 * Expressive 超圆角形状（28dp）—— 用于大卡片 / FAB / 强调容器。
 * M3 Expressive 风格相比标准 M3 更倾向大圆角，此形状供需要「 expressive 」观感的组件使用。
 */
val expressiveShape = RoundedCornerShape(28.dp)

/**
 * 全圆角 pill 形状（50%）—— 用于按钮 / 徽章 / 胶囊标签。
 * 比 [Dimens.badgeCornerRadius]（8dp）更彻底的胶囊形态，适用于主按钮、筛选 chip 选中态等。
 */
val pillShape = RoundedCornerShape(50)

/**
 * 应用主题 Composable —— 同时注入 colorScheme + typography + shapes。
 *
 * 现状背景：[App] 此前直接调用 `MaterialTheme(colorScheme = ..., shapes = ...)`，
 * 未传 typography（沿用 M3 默认紫色 era 排版）。本 [AppTheme] 封装三要素统一入口，
 * 默认把 [AppTypography]（M3 Expressive 定制）注入，调用方只需提供 colorScheme。
 *
 * 使用方式（[App] 及其他根 Composable）：
 * ```
 * AppTheme(colorScheme = colors) {
 *     // ... 内容
 * }
 * ```
 * 内部子树 `MaterialTheme.typography.xxx` 即取到 [AppTypography]，
 * `MaterialTheme.colorScheme` / `MaterialTheme.shapes` 同理。
 *
 * @param colorScheme 主题色板（由 [com.wgt.resolveColorScheme] / AMOLED 覆盖产生）
 * @param typography 排版规范，默认 [AppTypography]；测试时可覆盖
 * @param shapes 形状规范，默认 [AppShapes]
 * @param content 主题作用域内容
 */
@Composable
fun AppTheme(
    colorScheme: androidx.compose.material3.ColorScheme,
    typography: Typography = AppTypography,
    shapes: Shapes = AppShapes,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
        content = content
    )
}
