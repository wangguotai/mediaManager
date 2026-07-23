package com.wgt.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 媒体列表屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaListScreen(viewModel: MediaViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    // 默认打开"网盘图片" Tab（index=2）：真机启动即对后端发 q=source=cloud 请求，
    // 便于第一时间验证后端连通与 cloud 图片（data/cloud-images）加载。
    var selectedTab by remember { mutableStateOf(2) }

    // 图片预览状态：保存当前预览在 mediaList 中的索引（可空）。
    // 用索引而非 MediaMetadata，便于预览内左右滑动切换上一张/下一张。
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // OpenClaw 桥梁对话框状态 + 视图模型（与媒体列表同生命周期，复用即可）
    var showOpenClawDialog by remember { mutableStateOf(false) }
    val openClawViewModel = remember { OpenClawViewModel() }

    // 监听错误信息并显示 Snackbar
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 加载本地照片图库 / 已上传图片 / 网盘图片（使用缓存）
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && viewModel.canAccessGallery) {
            viewModel.loadMediaFromGallery(forceRefresh = false)
        } else if (selectedTab == 1) {
            viewModel.loadUploadedMediaList(forceRefresh = false)
        } else if (selectedTab == 2) {
            viewModel.loadCloudMediaList(forceRefresh = false)
        }
    }

    // 图片预览对话框：基于当前 mediaList 的索引，支持预览内左右滑动切换。
    previewIndex?.let { index ->
        val list = viewModel.mediaList
        if (index in list.indices) {
            ImagePreviewDialog(
                mediaList = list,
                initialIndex = index,
                useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
                onDismiss = { previewIndex = null }
            )
        } else {
            // 列表刷新后索引越界，直接关闭
            previewIndex = null
        }
    }

    // OpenClaw 桥梁命令对话框
    if (showOpenClawDialog) {
        OpenClawCommandDialog(
            viewModel = openClawViewModel,
            onDismiss = { showOpenClawDialog = false }
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
                        // OpenClaw 桥梁入口：点击弹出命令输入对话框，经后端 /api/openclaw/command 转发。
                        IconButton(onClick = { showOpenClawDialog = true }) {
                            Icon(
                                painterResource(Res.drawable.ic_openclaw),
                                contentDescription = "OpenClaw 桥梁"
                            )
                        }
                        IconButton(
                            onClick = {
                                when (selectedTab) {
                                    0 -> viewModel.loadMediaFromGallery(forceRefresh = true)
                                    1 -> viewModel.loadUploadedMediaList(forceRefresh = true)
                                    else -> viewModel.loadCloudMediaList(forceRefresh = true)
                                }
                            },
                            enabled = !viewModel.isLoading && !viewModel.isGalleryLoading && !viewModel.isCloudLoading
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
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                "网盘图片",
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        },
        bottomBar = {
            if (viewModel.hasSelection && selectedTab != 2) {
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
            val isLoading = when (selectedTab) {
                0 -> viewModel.isGalleryLoading
                2 -> viewModel.isCloudLoading
                else -> viewModel.isLoading
            }
            val mediaList = viewModel.mediaList
            // 下拉刷新：网格为空时走全屏加载/空状态占位，无法也不必下拉；
            // 有内容后下拉即触发对应 Tab 的强制刷新（forceRefresh=true，绕过缓存真正请求后端）。
            // isRefreshing 直接复用各 Tab 的 loading 状态，刷新指示器会随请求开始/结束自动显隐。
            val onRefresh = {
                when (selectedTab) {
                    0 -> viewModel.loadMediaFromGallery(forceRefresh = true)
                    1 -> viewModel.loadUploadedMediaList(forceRefresh = true)
                    else -> viewModel.loadCloudMediaList(forceRefresh = true)
                }
            }

            if (mediaList.isEmpty()) {
                if (isLoading) {
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
                } else {
                    // 空状态：仍包一层 PullToRefreshBox，便于在有数据前下拉重试请求。
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                when (selectedTab) {
                                    0 -> "暂无本地图片"
                                    2 -> "暂无网盘图片"
                                    else -> "暂无已上传图片"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "下拉刷新",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            } else {
                // 媒体网格列表：下拉刷新包住网格，手势到达阈值触发 onRefresh。
                // 网盘图片 Tab：点击直接进全屏预览（该 Tab 无选择/批量操作，点击预览更自然）。
                // 其余 Tab 保持原有交互：短按选中，长按预览。
                PullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    MediaGrid(
                        mediaList = mediaList,
                        selectedMediaIds = viewModel.selectedMediaIds,
                        onMediaClick = { media ->
                            if (selectedTab == 2) {
                                previewIndex = mediaList.indexOf(media)
                            } else {
                                viewModel.toggleMediaSelection(media.id)
                            }
                        },
                        onMediaLongClick = { media -> previewIndex = mediaList.indexOf(media) },
                        useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 图片预览对话框
 *
 * 基于 [androidx.compose.foundation.pager.HorizontalPager] 实现左右滑动切换上一张/下一张；
 * 每页是一个可双指缩放/平移的 [ZoomableImage]。点击空白或关闭按钮退出。
 *
 * @param mediaList 当前 Tab 的完整媒体列表
 * @param initialIndex 进入预览时聚焦的媒体在 [mediaList] 中的索引
 * @param useBackendLoader true 走 [BackendImageLoader]（后端 HTTP），false 走平台相册加载器
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImagePreviewDialog(
    mediaList: List<MediaMetadata>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    useBackendLoader: Boolean = false
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, mediaList.lastIndex)) {
        mediaList.size
    }
    val currentIndex by remember { derivedStateOf { pagerState.currentPage } }
    val currentMedia = mediaList[currentIndex]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            // 可滑动切换的图片页
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(
                    media = mediaList[page],
                    useBackendLoader = useBackendLoader,
                    onTapClose = onDismiss
                )
            }

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

            // 当前图片信息 + 页码
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    currentMedia.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(currentMedia.size)} • ${currentMedia.width}x${currentMedia.height}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
                if (mediaList.size > 1) {
                    Text(
                        "${currentIndex + 1} / ${mediaList.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 操作提示
            Text(
                "左右滑动切换 • 双击重置 • 捏合缩放",
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
 * 单张可缩放图片。
 *
 * 缩放交互：双指捏合缩放 + 单指拖动平移（缩放>1 时生效）；双击在 1x/2x 间切换；
 * 单击（无拖动）触发关闭回调。缩放>1 时消费手势，避免误触发 pager 滑动——
 * 这里用 `pointerInput(scale)` 的 key 随缩放重建，使 `detectTransformGestures`
 * 与 pager 的水平滑动在缩放态下互不抢占（缩放态下主要由 transform 手势消费平移）。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ZoomableImage(
    media: MediaMetadata,
    useBackendLoader: Boolean,
    onTapClose: () -> Unit
) {
    var fullImageBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    var scale by remember(media.id) { mutableStateOf(1f) }
    var offsetX by remember(media.id) { mutableStateOf(0f) }
    var offsetY by remember(media.id) { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 加载完整图片：本地相册走平台加载器；后端图片走 BackendImageLoader（HTTP stream）。
    LaunchedEffect(media.id, useBackendLoader) {
        scope.launch(dispatchers.io) {
            try {
                val image = if (useBackendLoader) {
                    BackendImageLoader.loadFullImage(media.id)
                } else {
                    loadFullImage(media.id)
                }
                fullImageBitmap = image
            } catch (e: Exception) {
                // 加载失败
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(scale) {
                detectTapGestures(
                    onTap = { onTapClose() },
                    onDoubleTap = {
                        // 双击在 1x 与 2x 间切换，并复位平移
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
            .pointerInput(scale) {
                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    // 仅在已放大时累加平移，避免 1x 下平移把图片拖出视口
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                    scale = newScale
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_image_placeholder),
                        contentDescription = "加载失败",
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("图片加载失败", color = Color.Gray)
                }
            }
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
    useBackendLoader: Boolean = false,
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
                useBackendLoader = useBackendLoader,
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
    useBackendLoader: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 缩略图状态
    var thumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // 异步加载缩略图：本地相册走平台 MediaStore/PHAsset；后端图片走 BackendImageLoader（HTTP）。
    LaunchedEffect(media.id, useBackendLoader) {
        scope.launch(dispatchers.io) {
            try {
                val thumbnail = if (useBackendLoader) {
                    BackendImageLoader.loadThumbnail(media.id)
                } else {
                    loadThumbnail(media.id)
                }
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
                        // shimmer 呼吸占位：比固定小圆圈更顺滑，覆盖整格、视觉连贯。
                        ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
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
 * Shimmer 占位：在缩略图加载期间显示一段平滑的明暗呼吸渐变，比单个固定 loading
 * 小圆圈更连贯、信息量更足（覆盖整格，视觉上明确"正在填充此处"）。
 * 使用 [rememberInfiniteTransition] 做无限循环，frame 开销低。
 */
@Composable
private fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier.background(Color.Gray.copy(alpha = alpha))
    )
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
