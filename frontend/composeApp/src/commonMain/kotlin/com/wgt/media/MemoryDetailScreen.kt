package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import media.MediaMetadata
import media.MediaType
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource

/**
 * 回忆详情页（PRD-v7 §1.4 时光相册）。
 *
 * 从「已上传」Tab 的月份回忆卡片点击进入，展示选中月份的全部云端图片：
 * - 接收 [year]+[month] 标识月份；
 * - 从 [MediaViewModel.cloudMedia] 过滤该月图片（[MediaViewModel.getGroupedMediaByMonth]），
 *   复用既有 [DateGroupedGrid] 按「今天/昨天/YYYY年MM月DD日」日内分组渲染，保留与主网格
 *   一致的浏览体验（照片多时可按日快速定位）；
 * - 顶栏标题「{year}年{month}月回忆」+ 返回按钮。
 *
 * 点击图片进入与主界面一致的 [ImagePreviewDialog] 全屏预览（含左右滑动、编辑、删除等），
 * 复用 MediaListScreen 内同款预览能力——此处直接持有 previewIndex 状态并调用
 * [ImagePreviewDialog]，避免重复实现。
 *
 * 纯 commonMain 实现，无平台依赖。数据源固定为云端（BACKEND），缩略图走
 * [BackendImageLoader.loadThumbnail]（与「已上传」Tab 同口径）。
 *
 * @param viewModel 媒体视图模型（提供 cloudMedia 月份过滤与预览所需上下文）
 * @param year 回忆卡片传入的年份（如 2026）
 * @param month 回忆卡片传入的月份（1-12）
 * @param onBack 返回「已上传」Tab 的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDetailScreen(
    viewModel: MediaViewModel,
    year: Int,
    month: Int,
    onBack: () -> Unit
) {
    // 按日分组的当月媒体（计算属性派生——cloudMedia 变化时自动刷新）。
    // remember(year, month) 确保切换不同月份卡片时重新计算，避免缓存上月数据。
    val groups = remember(year, month) { viewModel.getGroupedMediaByMonth(year, month) }
    val totalCount = remember(year, month) { groups.sumOf { it.items.size } }

    // 图片预览索引：与 MediaListScreen 同款逻辑，点击图片项后进入全屏预览。
    // 用整月扁平化的图片列表（仅图片，过滤视频——视频走独立 VideoPlayer 路径，
    // 与主预览一致），支持预览内左右滑动切换。
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // 视频播放状态：点击视频项时填充，全屏播放（与 MediaListScreen 行为一致）。
    var videoPlayerMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // 整月图片扁平列表（用于预览左右滑动），过滤视频——视频点击直接进 VideoPlayer。
    val flatImageList: List<MediaMetadata> = remember(groups) {
        groups.flatMap { it.items }.filter { it.type != MediaType.VIDEO }
    }

    // 返回键处理：详情页始终拦截返回键回到「已上传」Tab。
    PlatformBackHandler(enabled = true) { onBack() }

    // 图片预览对话框
    previewIndex?.let { index ->
        if (index in flatImageList.indices) {
            // 详情页固定为云端源预览。
            ImagePreviewDialog(
                mediaList = flatImageList,
                initialIndex = index,
                useBackendLoader = true,
                sourceLabel = "云端",
                onDismiss = { previewIndex = null },
                onEdit = { /* 详情页不开放编辑入口，保持只读回忆浏览 */ },
                onDelete = { media ->
                    previewIndex = null
                    viewModel.deleteSingleMedia(media.id)
                }
            )
        } else {
            previewIndex = null
        }
    }

    // 视频播放器
    videoPlayerMedia?.let { media ->
        VideoPlayer(
            media = media,
            initialDurationSeconds = viewModel.videoDurations[media.id],
            onDismiss = { videoPlayerMedia = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${year}年${month}月回忆",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        // 副标题：当月总张数，给用户整体规模感知。
                        if (totalCount > 0) {
                            Text(
                                "共 $totalCount 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (totalCount == 0) {
                // 当月无图片（理论上不会进入——卡片只在有图时生成，但兜底防白屏）。
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "该月份暂无回忆",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 复用 DateGroupedGrid：按日内分组渲染，与主网格体验一致。
                // 云端源固定 useBackendLoader=true；不支持选择/分页（回忆页是只读浏览）。
                DateGroupedGrid(
                    groups = groups,
                    selectedMediaIds = emptyList(),
                    onMediaClick = { media ->
                        if (media.type == MediaType.VIDEO) {
                            videoPlayerMedia = media
                        } else {
                            // 在扁平图片列表中定位索引，供预览 pager 左右滑动。
                            previewIndex = flatImageList.indexOf(media)
                        }
                    },
                    onMediaLongClick = { /* 回忆页不支持长按选中，保持只读浏览 */ },
                    useBackendLoader = true,
                    videoDurations = viewModel.videoDurations,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
