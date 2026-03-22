package com.wgt.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.common.util.formatBytesToMB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import kotlin.math.max
import kotlin.math.min

/**
 * 媒体列表屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaListScreen(viewModel: MediaViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(0) }
    
    // 图片预览状态
    var previewMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // 监听错误信息并显示 Snackbar
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 加载本地照片图库或已上传图片（使用缓存）
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && viewModel.canAccessGallery) {
            viewModel.loadMediaFromGallery(forceRefresh = false)
        } else if (selectedTab == 1) {
            viewModel.loadUploadedMediaList(forceRefresh = false)
        }
    }

    // 图片预览对话框
    previewMedia?.let { media ->
        ImagePreviewDialog(
            media = media,
            onDismiss = { previewMedia = null }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "图片管理",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (selectedTab == 0) {
                                    viewModel.loadMediaFromGallery()
                                } else {
                                    viewModel.loadUploadedMediaList()
                                }
                            },
                            enabled = !viewModel.isLoading && !viewModel.isGalleryLoading
                        ) {
                            Icon(
                                painterResource(Res.drawable.ic_refresh),
                                contentDescription = "刷新"
                            )
                        }
                    }
                )

                // 标签栏
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "本地图片",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "已上传",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        },
        bottomBar = {
            if (viewModel.hasSelection) {
                SelectionBottomBar(
                    selectedCount = viewModel.selectedCount,
                    onDelete = { viewModel.deleteSelectedMedia() },
                    onUpload = { if (selectedTab == 0) viewModel.uploadSelectedLocalMedia() },
                    isDeleting = viewModel.isDeleting,
                    isUploading = viewModel.isUploading,
                    showUploadButton = selectedTab == 0
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                UploadFab(
                    onUploadClick = {
                        viewModel.uploadSelectedLocalMedia()
                    },
                    isUploading = viewModel.isUploading
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            val isLoading = if (selectedTab == 0) viewModel.isGalleryLoading else viewModel.isLoading
            val mediaList = viewModel.mediaList

            if (isLoading && mediaList.isEmpty()) {
                // 加载中状态
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("加载中...")
                }
            } else if (mediaList.isEmpty()) {
                // 空状态
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        when (selectedTab) {
                            0 -> "暂无本地图片"
                            else -> "暂无已上传图片"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                // 媒体网格列表
                MediaGrid(
                    mediaList = mediaList,
                    selectedMediaIds = viewModel.selectedMediaIds,
                    onMediaClick = { media -> viewModel.toggleMediaSelection(media.id) },
                    onMediaLongClick = { media -> previewMedia = media },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 图片预览对话框
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ImagePreviewDialog(
    media: MediaMetadata,
    onDismiss: () -> Unit
) {
    var fullImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 加载完整图片
    LaunchedEffect(media.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = loadFullImage(media.id)
                fullImageBitmap = image
            } catch (e: Exception) {
                // 加载失败
            } finally {
                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            // 双击重置缩放
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                }
        ) {
            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // 图片信息
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Text(
                    media.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(media.size)} • ${media.width}x${media.height}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 图片内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = max(0.5f, min(5f, scale * zoom))
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(color = Color.White)
                    }
                    fullImageBitmap != null -> {
                        androidx.compose.foundation.Image(
                            bitmap = fullImageBitmap!!,
                            contentDescription = media.filename,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_image_placeholder),
                                contentDescription = "加载失败",
                                modifier = Modifier.size(64.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "图片加载失败",
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            // 缩放提示
            Text(
                "双击重置 • 捏合缩放 • 点击关闭",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

/**
 * 媒体网格布局
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalResourceApi::class)
@Composable
fun MediaGrid(
    mediaList: List<MediaMetadata>,
    selectedMediaIds: List<String>,
    onMediaClick: (MediaMetadata) -> Unit,
    onMediaLongClick: (MediaMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = modifier
    ) {
        items(
            items = mediaList,
            key = { it.id },
            contentType = { "media_item" }
        ) { media ->
            MediaGridItem(
                media = media,
                isSelected = selectedMediaIds.contains(media.id),
                onClick = { onMediaClick(media) },
                onLongClick = { onMediaLongClick(media) },
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

/**
 * 媒体网格项
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    media: MediaMetadata,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 缩略图状态
    var thumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // 异步加载缩略图
    LaunchedEffect(media.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val thumbnail = loadThumbnail(media.id)
                thumbnailBitmap = thumbnail
            } catch (e: Exception) {
                // 加载失败
            } finally {
                isLoading = false
            }
        }
    }

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box {
            // 媒体缩略图
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    thumbnailBitmap != null -> {
                        androidx.compose.foundation.Image(
                            bitmap = thumbnailBitmap!!,
                            contentDescription = media.filename,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    else -> {
                        // 显示占位图
                        Icon(
                            painter = painterResource(Res.drawable.ic_image_placeholder),
                            contentDescription = "占位图",
                            modifier = Modifier.size(48.dp),
                            tint = Color.Gray
                        )
                    }
                }

                // Live 图标识
                if (media.is_live_photo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_play_arrow),
                            contentDescription = "Live Photo",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 选中状态指示器
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_check_circle),
                        contentDescription = "已选中",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 媒体信息
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(4.dp)
            ) {
                Text(
                    media.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(media.size),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> formatBytesToMB(size)
    }
}

/**
 * 选择状态底部栏
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    isDeleting: Boolean,
    isUploading: Boolean,
    showUploadButton: Boolean
) {
    BottomAppBar(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "已选择 $selectedCount 项",
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )

        if (showUploadButton) {
            IconButton(
                onClick = onUpload,
                enabled = !isUploading
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        painterResource(Res.drawable.ic_file_upload),
                        contentDescription = "上传选中项"
                    )
                }
            }
        }

        IconButton(
            onClick = onDelete,
            enabled = !isDeleting
        ) {
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    painterResource(Res.drawable.ic_delete),
                    contentDescription = "删除选中项"
                )
            }
        }
    }
}

/**
 * 上传浮动按钮
 */
@Composable
fun UploadFab(
    onUploadClick: () -> Unit,
    isUploading: Boolean
) {
    FloatingActionButton(
        onClick = onUploadClick,
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White
            )
        } else {
            Icon(
                painterResource(Res.drawable.ic_file_upload),
                contentDescription = "上传媒体"
            )
        }
    }
}
