package com.wgt.media.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wgt.media.Dimens
import com.wgt.media.mediumCardShape

/**
 * 可复用 UI 组件库 —— M3 Expressive 设计系统基础件。
 *
 * 背景：SettingsRow / SwitchRow / CardScaffold 等此前散落在各 Screen 内联实现
 * （魔法数字、圆角/间距不统一）。本文件抽出统一版本，全部引用
 * [MaterialTheme.typography] / [MaterialTheme.colorScheme] / [Dimens] /
 * [com.wgt.media.AppShapes]，杜绝内联颜色与字号，保证视觉口径一致。
 *
 * 边界：仅 commonMain 通用件，不包含任何 Screen 业务逻辑；现有 Screen 的迁移
 * 由各自归属的 agent 负责，本文件只提供「可用」的组件。
 *
 * KMP 守则：
 * - 每个组件为独立 [Composable] 顶层函数（防 MethodTooLargeException）；
 * - 不引入新依赖，仅用 compose.material3 + compose.ui + compose.animation.core；
 * - 不用 String.format 等 JVM-only API。
 */

// ─────────────────────────────────────────────────────────────────────────────
// SettingsRow / SwitchRow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一设置项行。
 *
 * 视觉口径：高 [Dimens.listItemHeight]（56dp），水平内边距 [Dimens.listItemHorizontalPadding]
 * （16dp），圆角 [Dimens.cardCornerRadius]（16dp），点击涟漪用 M3 默认 ripple。
 * 左侧 [icon] + [title]（[androidx.compose.material3.Typography.titleMedium]），
 * 可选 [subtitle]（bodySmall + onSurfaceVariant），右侧 [trailing] 自由内容
 * （Switch / 文本 / 箭头等）。
 *
 * @param icon 左侧引导图标（可选）
 * @param title 主标题
 * @param subtitle 副标题（可选，灰色辅文）
 * @param trailing 右侧尾控件（可选）
 * @param onClick 点击回调（可选；为 null 时无点击涟漪，纯展示行）
 * @param modifier 调用方修饰
 */
@Composable
fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .height(Dimens.listItemHeight)
        .let { base ->
            if (onClick != null) {
                base.clickable(onClick = onClick)
            } else {
                base
            }
        }
        .padding(horizontal = Dimens.listItemHorizontalPadding)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.settingsRowIconSize)
            )
            // 图标与文本之间留 spacingMedium 间距（无魔法数字）
            SpacerHorizontal(width = Dimens.spacingMedium)
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trailing != null) {
            SpacerHorizontal(width = Dimens.spacingMedium)
            trailing()
        }
    }
}

/**
 * [SettingsRow] 的 Switch 变体 —— 末尾固定一个 M3 [Switch]。
 *
 * 复用 [SettingsRow] 的排版/高度/圆角口径，仅把 [trailing] 替换为 Switch，
 * 并把点击行的行为绑定到 switch 翻转（整行可点切换）。
 *
 * @param icon 左侧引导图标（可选）
 * @param title 主标题
 * @param checked 当前开关状态
 * @param onCheckedChange 状态变更回调
 * @param subtitle 副标题（可选）
 * @param modifier 调用方修饰
 */
@Composable
fun SwitchRow(
    icon: ImageVector? = null,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        onClick = { onCheckedChange(!checked) },
        modifier = modifier
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// CardScaffold
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一卡片骨架。
 *
 * 视觉口径：[ElevatedCard] + [mediumCardShape]（= [Dimens.cardCornerRadius] 16dp）
 * + [Dimens.cardElevationFlat]（1dp，克制层次）+ 内边距 [Dimens.spacingLarge]（16dp）。
 * Header 区可选 [title]（titleMedium）/ [subtitle]（bodySmall）/ [headerTrailing]，
 * [content] 为卡片主体。
 *
 * 适用：设置分组卡片、相册信息卡、清理建议卡等。替代各 Screen 里手搓的
 * `Surface(shape = RoundedCornerShape(16.dp))` 散落实现。
 *
 * @param title 卡片标题（可选）
 * @param subtitle 卡片副标题（可选）
 * @param headerTrailing 标题行尾控件（可选，如「编辑」按钮）
 * @param content 卡片主体
 * @param modifier 调用方修饰
 */
@Composable
fun CardScaffold(
    title: String? = null,
    subtitle: String? = null,
    headerTrailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = mediumCardShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = Dimens.cardElevationFlat
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.spacingLarge)
        ) {
            if (title != null || headerTrailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (title != null) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (headerTrailing != null) {
                        headerTrailing()
                    }
                }
                // header 与 content 之间留 spacingMedium
                SpacerVertical(height = Dimens.spacingMedium)
            }
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SectionHeader
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 分区标题。
 *
 * 视觉口径：[Typography.titleSmall] + onSurfaceVariant 颜色，可选前导 [icon]。
 * 用于设置页/相册页的分组小标题（「通用」「备份」「相册分类」等），
 * 替代散落的 `Text(text, fontSize = 14.sp, color = Color.Gray)` 魔法数字写法。
 *
 * @param title 分区标题文本
 * @param icon 前导图标（可选）
 * @param modifier 调用方修饰
 */
@Composable
fun SectionHeader(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.listItemHorizontalPadding,
                vertical = Dimens.spacingMedium
            )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.settingsRowIconSize)
            )
            SpacerHorizontal(width = Dimens.spacingSmall)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EmptyState
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 空态占位。
 *
 * 视觉口径：垂直居中 [Column]，[icon]（大图标，48dp）+ [title]（titleMedium）
 * + [subtitle]（bodyMedium + onSurfaceVariant）+ 可选 [action] 按钮。
 * 用于空列表、无搜索结果、无网络等空态。
 *
 * @param icon 空态主图标
 * @param title 主标题（如「暂无媒体」）
 * @param subtitle 副标题（如「下拉刷新或检查网络」）
 * @param action 可选操作区（如「去上传」按钮）
 * @param modifier 调用方修饰
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(Dimens.spacingXLarge)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.emptyStateIconSize)
        )
        SpacerVertical(height = Dimens.spacingLarge)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        SpacerVertical(height = Dimens.spacingSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            SpacerVertical(height = Dimens.spacingLarge)
            action()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LoadingShimmer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 骨架屏占位。
 *
 * 视觉口径：一个全宽、[Dimens.listItemHeight] 高的圆角块，用无限 pulse 动画
 * 在 surfaceVariant ↔ surface 之间渐变，模拟内容加载占位。
 *
 * KMP 实现说明：不引入 shimmer 三方库（约束：不引入新依赖），用
 * [rememberInfiniteTransition] + [Brush.linearGradient] 实现基础 pulse；
 * 动画用 [tween] 1200ms + [RepeatMode.Reverse]，程度克制（alpha 0.4→1.0）。
 *
 * @param modifier 调用方修饰（默认全宽 + listItemHeight 高）
 */
@Composable
fun LoadingShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.surface
    val brush = Brush.linearGradient(
        colors = listOf(
            baseColor,
            highlightColor.copy(alpha = progress),
            baseColor
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.listItemHeight)
            .background(brush, shape = mediumCardShape)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 内部小工具：定宽/定高 Spacer，避免每次手写 Modifier.size/width/height
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SpacerHorizontal(width: androidx.compose.ui.unit.Dp) {
    Spacer(modifier = Modifier.width(width))
}

@Composable
private fun SpacerVertical(height: androidx.compose.ui.unit.Dp) {
    Spacer(modifier = Modifier.height(height))
}
