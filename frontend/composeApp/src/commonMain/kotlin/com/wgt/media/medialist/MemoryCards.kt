package com.wgt.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.wgt.media.AuthState
import com.wgt.feature.media.MediaService
import com.wgt.media.ui.EmptyState
import com.wgt.media.ui.LoadingShimmer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.common.util.formatBytesToMB
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import media.MediaMetadata
import media.MediaType
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource


/**
 * 回忆卡片横滚列表（PRD-v7 §1.4）。
 *
 * 在「已上传」Tab 顶部以 [LazyRow] 横向滚动展示各月份回忆卡片 [MemoryCard]，
 * 点击单张卡片经 [onClick] 回调跳转 [MemoryDetailScreen]。
 *
 * 顶端带「回忆」小标题（左对齐，低强调色），与筛选条/网格留出间距。
 * 数据源为 [MediaViewModel.memoryMonths]（cloudMedia 按月聚合），空列表时不应到此
 * Composable（外层 [AnimatedVisibility] 已隐藏），但仍以空态兜底防 NPE。
 *
 * @param months 月份回忆列表（按年月倒序，最近月份在先）
 * @param onClick 点击单月卡片的回调，参数为该月 [MemoryMonth]
 */
@Composable
internal fun MemoryCardRow(
    months: List<MemoryMonth>,
    onClick: (MemoryMonth) -> Unit
) {
    if (months.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        // 「回忆」标题：低强调、左对齐，标示下方横滚卡片语义
        Text(
            "回忆",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                items = months,
                key = { "${it.year}-${it.month}" }
            ) { month ->
                MemoryCard(month = month, onClick = { onClick(month) })
            }
        }
    }
}



/**
 * 单张回忆卡片。
 *
 * 视觉：圆角卡片，顶部 2×2 封面缩略图网格（4 张云端缩略图），底部叠加「YYYY年M月」
 * 标题 + 张数角标。点击整卡触发 [onClick]。
 *
 * 缩略图经 [BackendImageLoader.loadThumbnail] 异步加载（与「已上传」Tab 网格同口径，
 * 均为云端源）；加载中显示占位色块，失败留空。每个缩略图独立 [remember(mediaId)]
 * 持有状态，避免 LazyRow 复用 slot 时封面错位。
 *
 * @param month 月份模型（含封面 items 与标题）
 * @param onClick 点击回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun MemoryCard(
    month: MemoryMonth,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(MemoryCardWidth)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Dimens.cardElevation)
    ) {
        // 封面区：2×2 网格缩略图，固定高度，clip 到卡片圆角
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(topStart = Dimens.cardCornerRadius, topEnd = Dimens.cardCornerRadius))
        ) {
            // 2×2 封面网格
            Column(modifier = Modifier.fillMaxSize()) {
                month.coverItems.take(4).chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        rowItems.forEach { media ->
                            MemoryCoverThumb(
                                mediaId = media.id,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                        // 不足 2 张的行补齐占位
                        if (rowItems.size < 2) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
                // 不足 2 行（<3 张）补齐空行
                val rowsFilled = (month.coverItems.size + 1) / 2
                repeat(2 - rowsFilled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }
            // 底部渐变遮罩 + 标题（叠加在封面图上，增强可读性）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        month.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    // 张数角标
                    Text(
                        "${month.totalCount}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}



/**
 * 回忆卡片单张封面缩略图。
 *
 * 异步经 [BackendImageLoader.loadThumbnail] 加载云端缩略图；加载中显示 surfaceVariant
 * 占位，失败留同色占位（不报错——回忆卡片是入口，单张加载失败不应阻塞整卡）。
 *
 * @param mediaId 媒体 id（云端）
 * @param modifier 布局修饰（由 2×2 网格分配 weight + fillMaxHeight）
 */
@Composable
internal fun MemoryCoverThumb(
    mediaId: String,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(mediaId) { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mediaId) {
        scope.launch(dispatchers.io) {
            try {
                bitmap = BackendImageLoader.loadThumbnail(mediaId)
            } catch (_: Exception) {
                // 加载失败留占位，不阻断回忆卡片渲染
            }
        }
    }

    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier.background(placeholderColor)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}



