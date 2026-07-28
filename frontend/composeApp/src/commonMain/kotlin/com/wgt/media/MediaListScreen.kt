package com.wgt.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import media.MediaType
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 媒体列表屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaListScreen(viewModel: MediaViewModel, onNavigateToSettings: () -> Unit = {}) {
    val snackbarHostState = remember { SnackbarHostState() }
    // 默认打开"网盘图片" Tab（index=2）：真机启动即对后端发 q=source=cloud 请求，
    // 便于第一时间验证后端连通与 cloud 图片（data/cloud-images）加载。
    var selectedTab by remember { mutableStateOf(2) }

    // 图片预览状态：保存当前预览在 mediaList 中的索引（可空）。
    // 用索引而非 MediaMetadata，便于预览内左右滑动切换上一张/下一张。
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // 视频播放状态：点击视频项时填充，非空即在顶层渲染全屏 [VideoPlayer]。
    // 与图片预览互斥：视频项点击直接进播放器，不走 [ImagePreviewDialog]。
    var videoPlayerMedia by remember { mutableStateOf<MediaMetadata?>(null) }

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

    // 视频播放器：点击视频项时填充 videoPlayerMedia，全屏播放。
    // 初始时长取 ViewModel 预取缓存（若有），让进度条立即显示总时长；无则在播放器内按实际播放获取。
    videoPlayerMedia?.let { media ->
        VideoPlayer(
            media = media,
            initialDurationSeconds = viewModel.videoDurations[media.id],
            onDismiss = { videoPlayerMedia = null }
        )
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
                        // 设置入口：齿轮图标，点击切换到 SettingsScreen（后端地址 / 主题 / 关于）。
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                painterResource(Res.drawable.ic_settings),
                                contentDescription = "设置"
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
            // Tab 切换整体淡入淡出：Crossfade 以 selectedTab 为 key，切换瞬间旧内容淡出、新内容淡入。
            // lambda 参数故意取同名 selectedTab 借以遮蔽外层变量，使淡出阶段的旧内容按上一次 tab 渲染。
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(280),
                label = "tabSwitch"
            ) { selectedTab ->
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

            // 网络错误优先级最高：后端未启动 / 请求异常时 listLoadError 持续占位，
            // 驱动"加载失败 + 重试"页，避免落入"暂无 X"误导为白屏。
            val listError = viewModel.listLoadError
            when {
                mediaList.isEmpty() && listError != null && !isLoading -> {
                    ErrorStateView(
                        message = listError,
                        onRetry = onRefresh
                    )
                }

                mediaList.isEmpty() && isLoading -> {
                    // 加载中状态：圆圈 + 文案，背景留脉动 shimmer 条点缀，比纯圆圈更连贯。
                    FullScreenLoading()
                }

                mediaList.isEmpty() -> {
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
                                  0 -> "相册里还没有本地图片"
                                  2 -> "网盘里还没有图片"
                                  else -> "还没有上传过图片"
                              },
                              style = MaterialTheme.typography.titleMedium,
                              fontWeight = FontWeight.Medium,
                              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                          )
                          Spacer(modifier = Modifier.height(8.dp))
                          Text(
                              when (selectedTab) {
                                  0 -> "授权访问相册后，本地图片会出现在这里"
                                  2 -> "把图片放进 media/data/cloud-images 目录即可"
                                  else -> "选中本地图片后点上传，文件会出现在这里"
                              },
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center
                          )
                          Spacer(modifier = Modifier.height(16.dp))
                          Text(
                              "下拉刷新",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                          )
                      }
                  }
              }

                else -> {
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
                                    // 网盘 Tab：视频项打开全屏播放器，图片项进预览（且不影响选择态）。
                                    if (media.type == MediaType.VIDEO) {
                                        videoPlayerMedia = media
                                    } else {
                                        previewIndex = mediaList.indexOf(media)
                                    }
                                } else {
                                    viewModel.toggleMediaSelection(media.id)
                                }
                            },
                            onMediaLongClick = { media ->
                                // 长按预览：视频项同样进播放器，与网格点击一致。
                                if (media.type == MediaType.VIDEO) {
                                    videoPlayerMedia = media
                                } else {
                                    previewIndex = mediaList.indexOf(media)
                                }
                            },
                            useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
                            videoDurations = viewModel.videoDurations,
                           modifier = Modifier.fillMaxSize()
                       )
                   }
               }
           }
            }
        }
    }
}

/**
 * 图片预览对话框
 *
 * 基于 [androidx.compose.foundation.pager.HorizontalPager] 实现左右滑动切换上一张/下一张；
 * 每页是一个可双指缩放/平移的 [ZoomableImage]。点击空白或按钮退出。
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

    // 进入/退出动画：进入时整体淡入 + 轻微放大；退出时先淡出再真正回调关闭，
    // 让 Dialog 消失更自然而非瞬切。visible 初始 false，首帧后翻 true 触发 enter；
    // 关闭走 animateOutThenDismiss：先翻 false 播 exit，等动画时长结束后 onDismiss。
   var visible by remember { mutableStateOf(false) }
   LaunchedEffect(Unit) { visible = true }
    val scope = rememberCoroutineScope()
    // 关闭走 animateOutThenDismiss：先翻 visible=false 播 exit，等动画时长结束后再真正 onDismiss，
    // 让 Dialog 消失走淡出而非瞬切。
    val animateOutThenDismiss: () -> Unit = {
        visible = false
        // 与 exit 动画时长(300ms)匹配，播完再真正关闭。
        scope.launch { kotlinx.coroutines.delay(320); onDismiss() }
    }

   Dialog(
     onDismissRequest = animateOutThenDismiss,
     properties = DialogProperties(
         usePlatformDefaultWidth = false
     )
    ) {
        // 动画包裹层：进入 fadeIn+scaleIn，退出 fadeOut。背景黑色始终在，避免白闪。
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
            // 可滑动切换的图片页
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableImage(
                    media = mediaList[page],
                    useBackendLoader = useBackendLoader,
                    onTapClose = animateOutThenDismiss
                )
            }

            // 关闭按钮
            IconButton(
                onClick = animateOutThenDismiss,
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
           } // AnimatedVisibility inner Box
       }
    }
}

/**
 * 单张可缩放图片。
 *
 * 缩放交互：双指捏合缩放 + 单指拖动平移（缩放>1 时生效）；双击在 1x/2x 间切换；
 * 单击逻辑修正——缩放态下单击**先复位缩放与平移**而非直接关闭，避免放大浏览时
 * 单击误退预览；1x 下单击才触发关闭。缩放>1 时消费手势，避免误触发 pager 滑动——
 * 这里用 `pointerInput(scale)` 的 key 随缩放重建，使 `detectTransformGestures`
 * 与 pager 的水平滑动在缩放态下互不抢占（缩放态下主要由 transform 手势消费平移）。
 *
 * 复位用动画过渡，缩放/平移回落而非瞬切，手感更顺。
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

    // 缩放/平移直接用手势值（瞬切，与原双击行为一致），优先保证手势跟手与
    // 单击语义正确，避免动画与手势状态源互相干扰。放大态单击只复位不关闭。

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
                    onTap = {
                        if (scale > 1f) {
                            // 放大态单击：只复位缩放/平移，不关闭预览，避免误退。
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            onTapClose()
                        }
                    },
                    onDoubleTap = {
                        // 双击在 1x 与 2x 间切换，并复位平移。
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
                // 全屏加载占位：居中圆圈 + 下方 shimmer 条，比单一圆圈信息量更足。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    ShimmerPlaceholder(
                        modifier = Modifier
                            .width(140.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            fullImageBitmap != null -> {
                Image(
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
    videoDurations: Map<String, Double> = emptyMap(),
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        // 外边距 8dp，项间 6dp：密集但不挤压，圆角卡片间留呼吸缝。
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
               videoDurationSeconds = videoDurations[media.id],
                // animateItem：新增/删除/重排项时平滑滑动到目标位（取代旧 animateItemPlacement），
                // 配合 key={it.id} 让网格变更时已有项不闪烁、新项从插值位置滑入。
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
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
    videoDurationSeconds: Double? = null,
    modifier: Modifier = Modifier
) {
    val isVideo = media.type == MediaType.VIDEO
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

    // 选中时加 primary 色细边框，强化点击反馈。
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    // 选中缩放反馈：选中态轻微放大(1.04f)，用 spring 过渡而非瞬切，呼应"已选中"视觉强调。
    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "selectionScale"
    )
    Card(
       modifier = modifier
           .aspectRatio(1f)
            .graphicsLayer(
                scaleX = selectionScale,
                scaleY = selectionScale
            )
           .shadow(
               elevation = if (isSelected) 6.dp else 3.dp,
               shape = RoundedCornerShape(12.dp),
               clip = false
           )
           .combinedClickable(
               onClick = onClick,
               onLongClick = onLongClick
           ),
       shape = RoundedCornerShape(12.dp),
       colors = CardDefaults.cardColors(
           containerColor = MaterialTheme.colorScheme.surfaceVariant
       )
   ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
        ) {
            // 媒体缩略图
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        // 扫光 shimmer 占位：覆盖整格、左→右高光扫过，明确"正在填充此处"。
                        ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                    }

                    thumbnailBitmap != null -> {
                        Image(
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // 选中遮罩：轻微暗化 + 边框已突出选中态，双重视觉提示。
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    )
                }

                // Live 图标识
                if (media.is_live_photo) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
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

                // 视频项：居中播放图标，区分于图片，提示点击可播放。
                // 时长徽标单独放在外层右下（见信息栏之后），避免被底部文件名条遮挡。
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_play_arrow),
                            contentDescription = "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // 选中状态指示器（角落勾选）
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
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
                    .background(Color.Black.copy(alpha = 0.55f))
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

            // 视频时长徽标：置于外层右下、信息栏之后渲染，浮于文件名条之上。
            // 预取缓存命中即显示；未到（后端 video-info 尚未返回）则留空，不阻断网格渲染。
            if (isVideo) {
                videoDurationSeconds?.let { dur ->
                    if (dur > 0) {
                        Text(
                            formatDuration(dur),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shimmer 占位扫光：一段左→右移动的高光渐变扫过基色，模拟内容正在加载填充。
 *
 * - 宽高由 [modifier] 决定：网格项传 fillMaxSize() 覆盖整格；
 *   预览加载态传固定宽度窄条做进度点缀。
 * - 用 [rememberInfiniteTransition] 驱动高光水平位置（0f→1f 循环），
 *   [drawBehind] 按比例构建线性渐变 brush，frame 开销低。
 */
@Composable
private fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val baseLow = MaterialTheme.colorScheme.surfaceVariant
    val baseHigh = MaterialTheme.colorScheme.surface
    val highlight = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            // 高光中心从 -0.5w 移到 1.5w，覆盖整宽，循环无缝。
            val center = (progress * 2f - 0.5f) * w
            val sweep = w * 0.5f
            val brush = Brush.linearGradient(
                colors = listOf(baseLow, baseHigh, highlight, baseHigh, baseLow),
                start = Offset(center - sweep, 0f),
                end = Offset(center + sweep, h)
            )
            drawRect(brush)
        }
    )
}

/**
 * 全屏加载态：圆圈 + 文案 + shimmer 进度条，比单一圆圈更连贯。
 */
@Composable
private fun FullScreenLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        ShimmerPlaceholder(
            modifier = Modifier
                .width(180.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

/**
 * 列表加载失败占位：图标 + 错误文案 + 重试按钮。
 *
 * 用于后端未启动 / 网络异常导致 [MediaViewModel.listLoadError] 非空且 mediaList 为空时，
 * 替代"暂无 X"误导为白屏的场景。重试走对应 Tab 的强制刷新。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_image_placeholder),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "加载失败",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onRetry) {
            Icon(
                painterResource(Res.drawable.ic_refresh),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("重试")
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
