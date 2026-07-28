package com.wgt.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import media.MediaMetadata

/**
 * 按日期分组展示媒体的网格列表。
 *
 * - 用 [LazyVerticalGrid]（[GridCells.Adaptive]，与原 [MediaGrid] 一致的列宽）承载整体滚动；
 * - 每个日期分组由两部分组成，均为同一 LazyVerticalGrid 的 item：
 *     1. [stickyHeader]：占满整行（[GridItemSpan.maxLineSpan]），滚动时吸顶，
 *        文案来自 [DateGroup.title]（"今天"/"昨天"/"YYYY年MM月DD日"）；
 *     2. 组内媒体项：以 [MediaGridItem] 平铺，自动按列自适应换行，组内仍是网格。
 *
 * stickyHeader 在 `androidx.compose.foundation.lazy.grid` 包中已稳定（本项目 Compose 1.10）。
 *
 * Header 进入时有淡入 + 轻微下移动画（见 [DateGroupHeader]），呼吸感更自然。
 *
 * 搜索态下 UI 走平铺 [MediaGrid] 不经此组件，故这里始终按分组渲染。
 *
 * @param groups 已按日期倒序排好的分组（见 [MediaViewModel.groupedMediaList]）
 * @param selectedMediaIds 当前选中的媒体 id，透传给 [MediaGridItem]
 * @param onMediaClick / onMediaLongClick 媒体交互回调
 * @param useBackendLoader 缩略图是否走后端加载器（与 [MediaGrid] 同义）
 * @param videoDurations 视频时长缓存，透传给 [MediaGridItem] 用于时长徽标
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DateGroupedGrid(
    groups: List<DateGroup>,
    selectedMediaIds: List<String>,
    onMediaClick: (MediaMetadata) -> Unit,
    onMediaLongClick: (MediaMetadata) -> Unit,
    useBackendLoader: Boolean = false,
    videoDurations: Map<String, Double> = emptyMap(),
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        // 与 MediaGrid 保持一致的外边距与间距，分组视觉与原网格无缝衔接。
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        groups.forEach { group ->
            // 吸顶标题：占满整行，滚动时浮于内容之上。key 含组内首项 id 以保证各分组唯一。
            stickyHeader(key = "header_${group.title}_${group.items.firstOrNull()?.id ?: ""}") {
                DateGroupHeader(title = group.title)
            }

            // 组内媒体项：每个 item 自适应占一格，GridCells.Adaptive 自动换行成网格。
            items(
                items = group.items,
                key = { it.id },
                contentType = { "media_item" }
            ) { media ->
                MediaGridItem(
                    media = media,
                    isSelected = selectedMediaIds.contains(media.id),
                    onClick = { onMediaClick(media) },
                    onLongClick = { onMediaLongClick(media) },
                    useBackendLoader = useBackendLoader,
                    videoDurationSeconds = videoDurations[media.id],
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }
}

/**
 * 日期分组的吸顶标题条。
 *
 * 视觉：左对齐文案 + 背景纵向渐变（surface → surface 半透明），让标题在吸顶时
 * 与下方内容有柔和分界，不至于硬切。
 *
 * 进入动画：整体淡入 + 轻微下落（16dp → 0），呼应"新的一组日期浮现"的节奏感，
 * 避免标题瞬贴显得突兀。用 [animateFloatAsState] 驱动 alpha 与 translationY：
 * 首次组合时 visible 由 false 翻 true 触发一次过渡；滚动复用同一 Composable 实例，
 * 不再重放，性能稳定。
 */
@Composable
private fun DateGroupHeader(title: String) {
    // 首帧后翻 true，触发一次淡入+下落过渡。
    var visible by remember { mutableStateOf(false) }
    visible = true

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(320),
        label = "headerAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (visible) 0f else 16f,
        animationSpec = tween(320),
        label = "headerY"
    )

    val surface = MaterialTheme.colorScheme.surface
    val bgBrush = Brush.verticalGradient(
        colors = listOf(surface, surface.copy(alpha = 0.85f))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(
                alpha = alpha,
                translationY = translationY
            )
            .background(bgBrush)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}
