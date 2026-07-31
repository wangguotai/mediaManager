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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.wgt.media.AuthState
import com.wgt.feature.media.MediaService
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
import media.MediaMetadata
import media.MediaType
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 媒体列表屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaListScreen(
    viewModel: MediaViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToFileManagement: () -> Unit = {},
    onNavigateToMemory: (year: Int, month: Int) -> Unit = { _, _ -> }
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // TopAppBar 滚动行为：列表滚动时 TopAppBar elevation 动画升高，增强层次感。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 默认打开"网盘图片" Tab（index=2）：真机启动即对后端发 q=source=cloud 请求，
    // 便于第一时间验证后端连通与 cloud 图片（data/cloud-images）加载。
    var selectedTab by remember { mutableStateOf(2) }

    // 图片预览状态：保存当前预览在 mediaList 中的索引（可空）。
    // 用索引而非 MediaMetadata，便于预览内左右滑动切换上一张/下一张。
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<MediaMetadata?>(null) }

    // 视频播放状态：点击视频项时填充，非空即在顶层渲染全屏 [VideoPlayer]。
    // 与图片预览互斥：视频项点击直接进播放器，不走 [ImagePreviewDialog]。
    var videoPlayerMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // 图片编辑器状态：预览操作栏点「编辑」时填充，非空即渲染全屏 [ImageEditor]。
    var editorMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // 幻灯片播放状态：非空即渲染全屏 [SlideshowPlayer]，传入当前 filteredList。
    var slideshowActive by remember { mutableStateOf(false) }

    // 选择模式下按返回键退出选择模式（而非退出 App）
    PlatformBackHandler(enabled = viewModel.hasSelection) {
        viewModel.deselectAll()
    }

    // 搜索栏展开态：收起时只占一个图标位，展开时显示输入框 + 清除按钮。
    // 由 Screen 持有而非 ViewModel，便于与筛选条布局联动且不影响列表缓存。
    var searchExpanded by remember { mutableStateOf(false) }

    // 长按上下文菜单：非空时弹出 DropdownMenu，值为触发的 MediaMetadata。
    // 仅在非选择模式下使用——选择模式下长按直接选中/预览，不走此菜单。

    // "加入相册"选择对话框：非空时弹出相册列表供用户选择目标相册。
    var addToAlbumMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // V7 §1.2：分享链接结果对话框状态
    var shareLinkResult by remember { mutableStateOf<ShareLinkResult?>(null) }
    var shareLinkError by remember { mutableStateOf<String?>(null) }
    var showShareLinkConfig by remember { mutableStateOf(false) }

    // 批量删除确认对话框：点击删除按钮后先弹确认，避免误删。

    // 监听错误信息并显示 Snackbar
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 加载本地照片图库 / 已上传图片 / 网盘图片（使用缓存）
    // Tab 3 = "我的"，不加载媒体数据。
    LaunchedEffect(selectedTab) {
        // 切换 Tab 时清空选中状态，避免上一 Tab 的选中项串到新 Tab（选中 ID 不匹配新列表）
        viewModel.deselectAll()
        if (selectedTab == 3) return@LaunchedEffect
        // 切换 Tab 即切换数据源：清空搜索关键词与类型筛选，避免上个 Tab 的过滤条件
        // 串到新 Tab 造成“列表为空/对不上”的困惑。
        viewModel.clearSearchAndFilter()
        searchExpanded = false
        if (selectedTab == 0 && viewModel.canAccessGallery) {
            viewModel.loadMediaFromGallery(forceRefresh = false)
        } else if (selectedTab == 1) {
            // "已上传" Tab 现为云端媒体视图：展示 sync/changes 增量同步累积的 cloudMedia，
            // 进入即触发后台增量续拉。保留 loadCloudViewForTab 的"秒开已有视图 + 增量刷新"语义。
            viewModel.loadCloudViewForTab(forceRefresh = false)
        } else if (selectedTab == 2) {
            viewModel.loadCloudMediaList(forceRefresh = false)
        }
    }

    // 图片预览对话框：基于当前 filteredList 的索引，支持预览内左右滑动切换。
    // 用 filteredList（搜索/筛选后）而非 mediaList，使预览左右滑动只在当前可见结果集内切换，
    // 避免滑到被过滤掉的项。
    previewIndex?.let { index ->
        val list = viewModel.filteredList
        if (index in list.indices) {
            // 文件来源标签：依当前 Tab 语义——本地相册 / 已上传 / 网盘图片，
            // 与加载源（LOCAL vs BACKEND）一致，供详情面板展示。
            val sourceLabel = when (selectedTab) {
                0 -> "本地相册"
                1 -> "云端"
                else -> "网盘图片"
            }
            // 视频项通过 onMediaClick 直接走 VideoPlayer 路径（不设 previewIndex），
            // 但 filteredList 仍含视频项，图片预览 pager 左右滑动到视频位会尝试以图片方式加载失败。
            // 此处过滤掉视频项，使预览只在图片集内切换；视频一律通过 VideoPlayer 播放。
            val imageOnlyList = list.filter { it.type != MediaType.VIDEO }
            val clickedMedia = list[index]
            val imageIndex = imageOnlyList.indexOf(clickedMedia)
            if (imageIndex >= 0) {
                ImagePreviewDialog(
                    mediaList = imageOnlyList,
                    initialIndex = imageIndex,
                    useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
                    sourceLabel = sourceLabel,
                    onDismiss = { previewIndex = null },
                    onEdit = { media ->
                        editorMedia = media
                    },
                    onDelete = { media ->
                        previewIndex = null
                        viewModel.deleteSingleMedia(media.id)
                    },
                    onFavoriteToggle = { media ->
                        viewModel.toggleFavorite(media.id)
                    },
                    isFavorite = { media -> viewModel.isFavorite(media.id) },
                    onSlideshow = {
                        previewIndex = null
                        slideshowActive = true
                    },
                    onRename = { media -> renameTarget = media }
                )
            } else {
                // 点击的是视频但不知为何 previewIndex 被设置（理论上不会发生），关关闭
                previewIndex = null
            }
        } else {
            // 列表刷新/过滤变化后索引越界，直接关闭
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

    // 图片编辑器：预览操作栏点「编辑」进入，全屏编辑（裁剪/旋转/滤镜）后保存到相册。
    editorMedia?.let { media ->
        ImageEditor(
            media = media,
            useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
            onDismiss = { editorMedia = null }
        )
    }

    // V7：重命名对话框
    renameTarget?.let { media ->
        var newName by remember { mutableStateOf(media.filename) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("文件名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName != media.filename,
                    onClick = {
                        val target = media
                        renameTarget = null
                        viewModel.renameMedia(target.id, newName) { success ->
                            if (success) viewModel.showErrorMessage("重命名成功")
                            else viewModel.showErrorMessage("重命名失败")
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // 幻灯片播放器：预览操作栏点「幻灯片」进入，全屏自动播放当前 filteredList。
    if (slideshowActive) {
        val list = viewModel.filteredList
        if (list.isNotEmpty()) {
            SlideshowPlayer(
                mediaList = list,
                initialIndex = previewIndex ?: 0,
                useBackendLoader = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL,
                onDismiss = { slideshowActive = false }
            )
        } else {
            slideshowActive = false
        }
    }

    // "加入相册"相册选择对话框
    addToAlbumMedia?.let { media ->
        // 若处于选择模式，批量加入所有选中项；否则只加单张
        val mediaIdsToAdd = if (viewModel.hasSelection && viewModel.selectedMediaIds.isNotEmpty()) {
            viewModel.selectedMediaIds.toList()
        } else {
            listOf(media.id)
        }
        AddToAlbumDialog(
            albums = viewModel.albumList,
            isLoading = viewModel.isAlbumLoading,
            onPick = { album ->
                mediaIdsToAdd.forEach { id -> viewModel.addMediaToAlbum(album.id, id) }
                addToAlbumMedia = null
                viewModel.dismissAddToAlbumDialog()
            },
            onDismiss = {
                addToAlbumMedia = null
                viewModel.dismissAddToAlbumDialog()
            }
        )
    }

    // V7 §1.2：分享链接结果对话框——显示生成的 URL + 复制按钮
    shareLinkResult?.let { result ->
        ShareLinkDialog(
            url = result.url,
            expiresAt = result.expiresAt,
            onDismiss = { shareLinkResult = null }
        )
    }

    // V7 §1.2：分享链接错误提示
    LaunchedEffect(shareLinkError) {
        shareLinkError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            shareLinkError = null
        }
    }

    // V7 §1.2：分享链接配置对话框（密码可选 + 有效期选择）
    if (showShareLinkConfig) {
        ShareLinkConfigDialog(
            onDismiss = { showShareLinkConfig = false },
            onCreate = { password, hours ->
                showShareLinkConfig = false
                viewModel.createShareLinkForSelected(
                    expiresInHours = hours,
                    password = password?.takeIf { it.isNotBlank() },
                    onCreated = { url, expiresAt ->
                        shareLinkResult = ShareLinkResult(url, expiresAt)
                    },
                    onError = { msg ->
                        shareLinkError = msg
                    }
                )
            }
        )
    }

// 上传进度对话框：显示 "上传中 2/5..." + 进度条
    viewModel.uploadProgress?.let { (uploaded, total) ->
        UploadProgressDialog(
            uploaded = uploaded,
            total = total,
            onDismiss = { /* 上传中不可取消，等完成后自动消失 */ }
        )
    }

    Scaffold(
        // MIUI 修复：完全移除 topBar 槽位——MIUI 系统会拦截 Scaffold topBar 区域的触摸事件。
        // 标题 + 搜索图标改放到内容区第一行，确保 y 坐标足够低，绕过状态栏触摸拦截。
        bottomBar = {
            if (viewModel.hasSelection && selectedTab < 3) {
                // 选择模式：显示批量操作底栏（替换 NavigationBar）
                SelectionBottomBar(
                    selectedCount = viewModel.selectedCount,
                    totalCount = viewModel.filteredList.size,
                    onDelete = { viewModel.deleteSelectedMedia() },
                    onUpload = { if (selectedTab == 0) viewModel.uploadSelectedLocalMedia() },
                    onShare = { viewModel.shareSelectedMedia() },
                    onSelectAll = { viewModel.selectAll() },
                    onDeselectAll = { viewModel.deselectAll() },
                    onAddToAlbum = {
                        // 以选中项中第一个媒体作为 dialog 标识；
                        // 实际添加时在 ViewModel 中对全部选中项批量加入。
                        val firstId = viewModel.selectedMediaIds.firstOrNull()
                        val firstMedia = viewModel.filteredList.find { it.id == firstId }
                        if (firstMedia != null) {
                            addToAlbumMedia = firstMedia
                            viewModel.showAddToAlbumDialog(firstMedia.id)
                        }
                    },
                    isDeleting = viewModel.isDeleting,
                    isUploading = viewModel.isUploading,
                    showUploadButton = selectedTab == 0,
                    showAddToAlbumButton = selectedTab != 0, // 后端源（已上传/网盘）才显示
                    showShareLinkButton = selectedTab != 0, // V7 §1.2：仅云端源显示分享链接按钮
                    onCreateShareLink = {
                        // V7 §1.2：打开配置对话框（密码可选 + 有效期选择）
                        shareLinkError = null
                        showShareLinkConfig = true
                    }
                )
            } else {
                // 正常模式：底部导航栏（MIUI 风格）
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(painterResource(Res.drawable.ic_photo), contentDescription = "本地图片") },
                        label = { Text("本地图片") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(painterResource(Res.drawable.ic_file_upload), contentDescription = "已上传") },
                        label = { Text("已上传") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(painterResource(Res.drawable.ic_cloud), contentDescription = "网盘图片") },
                        label = { Text("网盘图片") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(painterResource(Res.drawable.ic_settings), contentDescription = "我的") },
                        label = { Text("我的") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0 && !viewModel.hasSelection) {
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
            // "我的" Tab：设置 / 相册入口页，不渲染媒体网格
            if (selectedTab == 3) {
                MyTabContent(
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToAlbums = onNavigateToAlbums,
                    onNavigateToFileManagement = onNavigateToFileManagement
                )
                return@Box
            }

            // 媒体 Tab（0-2）：标题 + 搜索栏 + 筛选条 + 网格列表
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // 标题行：选择模式下显示已选数量 + 关闭按钮（小米相册风格）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = viewModel.hasSelection,
                        transitionSpec = {
                            fadeIn(tween(200)).togetherWith(fadeOut(tween(200)))
                        },
                        label = "titleTransition"
                    ) { isSelecting ->
                        if (isSelecting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.deselectAll() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_close),
                                        contentDescription = "退出选择",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "已选择 ${viewModel.selectedCount} 项",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.animateContentSize(tween(200))
                                )
                            }
                        } else {
                            Text(
                                "图片管理",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                    }
                }
                // 搜索栏：自带 IconButton 展开收起，无需额外图标
                if (selectedTab < 3) {
                    SearchBar(
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                    onDebouncedQueryChange = { query ->
                        viewModel.applySearchQuery(query)
                        if (query.isNotBlank()) SearchHistory.add(query)
                    },
                    onSearchSubmit = { /* IME 搜索键：去抖已驱动过滤，此处无需额外动作 */ }
                )

                    // 类型筛选条：全部 / 图片 / 视频，与搜索叠加生效
                    FilterChipsRow(
                        selected = viewModel.filterType,
                        onSelect = { type -> viewModel.applyFilterType(type) }
                    )
                } // end if (selectedTab < 3)

                // ── 回忆卡片横滚区域（PRD-v7 §1.4 时光相册）──
                // 仅在「已上传」Tab 顶部、网格之上展示：基于 cloudMedia 按月份自动生成的
                // 回忆入口，点击跳转 MemoryDetailScreen。搜索/筛选态下仍保留（回忆是独立
                // 的月份聚合入口，不随网格过滤变化），但选择模式下隐藏（避免与批量操作冲突）。
                // cloudMedia 为空时 memoryMonths 为空列表，AnimatedVisibility 自动收起。
                val memoryMonths = viewModel.memoryMonths
                AnimatedVisibility(
                    visible = selectedTab == 1 && memoryMonths.isNotEmpty() && !viewModel.hasSelection,
                    enter = fadeIn(tween(280)),
                    exit = fadeOut(tween(220))
                ) {
                    MemoryCardRow(
                        months = memoryMonths,
                        onClick = { month -> onNavigateToMemory(month.year, month.month) }
                    )
                }

                // Tab 切换整体淡入淡出
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(280),
                    label = "tabSwitch",
                    modifier = Modifier.weight(1f)
                ) { selectedTab ->
                    val isLoading = when (selectedTab) {
                        0 -> viewModel.isGalleryLoading
                        // "已上传" Tab 的同步态由 isSyncing 驱动（loadCloudViewForTab 先秒开
                        // 已有视图，isSyncing 期间叠加刷新指示）。
                        1 -> viewModel.isSyncing
                        2 -> viewModel.isCloudLoading
                        else -> viewModel.isLoading
                    }
                    val mediaList = viewModel.mediaList
                    val filtered = viewModel.filteredList
                    val onRefresh = {
                        when (selectedTab) {
                            0 -> viewModel.loadMediaFromGallery(forceRefresh = true)
                            1 -> viewModel.loadCloudViewForTab(forceRefresh = true)
                            else -> viewModel.loadCloudMediaList(forceRefresh = true)
                        }
                    }

                    val listError = viewModel.listLoadError
                    when {
                        mediaList.isEmpty() && listError != null && !isLoading -> {
                            ErrorStateView(
                                message = listError,
                                onRetry = onRefresh
                            )
                        }

                        mediaList.isEmpty() && isLoading -> {
                            FullScreenLoading()
                        }

                        mediaList.isEmpty() -> {
                            PullToRefreshBox(
                                isRefreshing = isLoading,
                                onRefresh = onRefresh,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                EmptyStateView(tabIndex = selectedTab)
                            }
                        }

                        filtered.isEmpty() -> {
                            NoSearchResultView(
                                searchQuery = viewModel.searchQuery,
                                filterType = viewModel.filterType,
                                onClear = {
                                    searchExpanded = false
                                    viewModel.clearSearchAndFilter()
                                }
                            )
                        }

                        else -> {
                            PullToRefreshBox(
                                isRefreshing = isLoading,
                                onRefresh = onRefresh,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val isSearching = viewModel.searchQuery.isNotBlank() ||
                                    viewModel.filterType != MediaFilterType.ALL
                                // 小米相册交互：单击=预览（选择模式下=选中/取消）
                                val onMediaClick: (MediaMetadata) -> Unit = { media ->
                                    if (viewModel.hasSelection) {
                                        viewModel.toggleMediaSelection(media.id)
                                    } else {
                                        if (media.type == MediaType.VIDEO) {
                                            videoPlayerMedia = media
                                        } else {
                                            previewIndex = filtered.indexOf(media)
                                        }
                                    }
                                }
                                // 小米相册交互：长按=进入选择模式并选中当前项
                                val onMediaLongClick: (MediaMetadata) -> Unit = { media ->
                                    if (!viewModel.hasSelection) {
                                        viewModel.startSelection(media.id)
                                    } else {
                                        viewModel.toggleMediaSelection(media.id)
                                    }
                                }
                                val useBackend = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL
                                if (isSearching) {
                                    MediaGrid(
                                        mediaList = filtered,
                                        selectedMediaIds = viewModel.selectedMediaIds,
                                        onMediaClick = onMediaClick,
                                        onMediaLongClick = onMediaLongClick,
                                        useBackendLoader = useBackend,
                                        videoDurations = viewModel.videoDurations,
                                        searchQuery = viewModel.searchQuery,
                                        favoriteIds = viewModel.favoriteIds,
                                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                        onLoadMore = if (selectedTab == 0) { { viewModel.loadMoreGallery() } } else if (selectedTab == 1) { { viewModel.loadMoreCloudChanges() } } else null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    DateGroupedGrid(
                                        groups = viewModel.groupedMediaList,
                                        selectedMediaIds = viewModel.selectedMediaIds,
                                        onMediaClick = onMediaClick,
                                        onMediaLongClick = onMediaLongClick,
                                        useBackendLoader = useBackend,
                                        videoDurations = viewModel.videoDurations,
                                        searchQuery = viewModel.searchQuery,
                                        favoriteIds = viewModel.favoriteIds,
                                        onFavoriteToggle = { viewModel.toggleFavorite(it) },
                                        onLoadMore = if (selectedTab == 0) { { viewModel.loadMoreGallery() } } else if (selectedTab == 1) { { viewModel.loadMoreCloudChanges() } } else null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "我的" Tab 内容页（MIUI 风格）。
 *
 * 简洁的设置入口列表，包含：
 * - 相册管理入口
 * - 应用设置入口
 *
 * 避免在顶部放置任何可点击元素，所有按钮 y > 150dp，确保 MIUI 状态栏不拦截触摸。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun MyTabContent(
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbums: () -> Unit,
    onNavigateToFileManagement: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // V7：用户信息卡片（头像圆 + 用户名 + ID）
        val username = AuthState.currentUsername
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像圆（首字母）
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = username.takeIf { it.isNotEmpty() }?.first()?.uppercase() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        username.ifEmpty { "未登录" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "ID: ${AuthState.currentUserId.take(8)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // V7：存储统计卡片
        var storageStats by remember { mutableStateOf<MediaService.StorageStats?>(null) }
        LaunchedEffect(Unit) {
            storageStats = MediaService.getStorageStats()
        }
        storageStats?.let { stats ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "存储概览",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem("图片", stats.imageCount, stats.imageBytes)
                        StatItem("视频", stats.videoCount, stats.videoBytes)
                        StatItem("Live", stats.livePhotoCount, stats.livePhotoBytes)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "总计 ${stats.totalCount} 项 · " + (stats.totalMB).let { mb ->
                            val s = mb.toString()
                            s.take(s.indexOf('.') + 2)
                        } + " MB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // V7：媒体库综合摘要（时间跨度）
        var mediaSummary by remember { mutableStateOf<MediaService.MediaSummary?>(null) }
        LaunchedEffect(Unit) { mediaSummary = MediaService.getMediaSummary() }
        mediaSummary?.let { summary ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "媒体时间线",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "共 ${summary.totalCount} 项媒体",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (summary.earliestTs > 0) {
                        Text(
                            "最早: ${formatPreviewDate(summary.earliestTs * 1000)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (summary.latestTs > 0) {
                        Text(
                            "最新: ${formatPreviewDate(summary.latestTs * 1000)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // V7：收藏快速统计
        var favCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(Unit) {
            favCount = MediaService.getFavorites()?.size
        }
        favCount?.let { count ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⭐", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "收藏",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "$count",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // V7：最近活动卡片
        var activities by remember { mutableStateOf<List<MediaService.ActivityInfo>?>(null) }
        LaunchedEffect(Unit) { activities = MediaService.getRecentActivity() }
        activities?.let { activityList ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "最近活动",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (activityList.isEmpty()) {
                        Text(
                            "暂无活动",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        activityList.take(5).forEach { act ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    when (act.type) {
                                        "upload" -> "📤"
                                        "share" -> "🔗"
                                        "favorite" -> "⭐"
                                        else -> "📋"
                                    },
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    act.detail,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (act.timestamp > 0) formatPreviewDate(act.timestamp * 1000) else "",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V7：设备列表卡片
        var devices by remember { mutableStateOf<List<MediaService.DeviceInfo>?>(null) }
        LaunchedEffect(Unit) { devices = MediaService.listDevices() }
        devices?.let { deviceList ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "我的设备",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (deviceList.isEmpty()) {
                        Text(
                            "暂无已注册设备",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        deviceList.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (device.platform.lowercase()) {
                                            "android" -> "📱"
                                            "ios" -> "🍏"
                                            else -> "💻"
                                        },
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            device.deviceName.ifEmpty { "未命名设备" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            device.platform.ifEmpty { "unknown" },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                Text(
                                    if (device.createdAtMs > 0) {
                                        formatPreviewDate(device.createdAtMs)
                                    } else "",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // V7：分享链接列表卡片
        var shares by remember { mutableStateOf<List<MediaService.ShareInfo>?>(null) }
        LaunchedEffect(Unit) { shares = MediaService.listShares() }
        shares?.let { shareList ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "我的分享",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (shareList.isEmpty()) {
                        Text(
                            "暂无分享链接",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        shareList.forEach { share ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "🔗",
                                        fontSize = 18.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            share.token.take(8) + "…",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "过期: ${share.expiresAt}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                if (share.hasPassword) {
                                    Text(
                                        "🔒",
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "我的",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        MyTabItem(
            iconRes = Res.drawable.ic_photo,
            title = "相册管理",
            subtitle = "查看和管理相册",
            onClick = onNavigateToAlbums
        )
        MyTabItem(
            iconRes = Res.drawable.ic_cloud,
            title = "文件管理",
            subtitle = "云端媒体 · 筛选排序 · 批量删除 · 占用空间",
            onClick = onNavigateToFileManagement
        )
        MyTabItem(
            iconRes = Res.drawable.ic_settings,
            title = "应用设置",
            subtitle = "后端地址、主题、OpenClaw 等",
            onClick = onNavigateToSettings
        )
    }
}

/**
 * "我的" Tab 列表项。
 */
/** 存储统计单项（类型标签 + 数量 + 占用） */
@Composable
private fun StatItem(label: String, count: Int, bytes: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(formatBytesToMB(bytes), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun MyTabItem(
    iconRes: DrawableResource,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = title
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
 * 增强功能：
 * - 底部缩略图条：当前页高亮，点击跳转
 * - 顶部信息栏：文件名 + 大小 + 日期
 * - 毛玻璃背景：模糊当前图片作为背景
 * - 缩放态禁用翻页：避免放大时误触发 pager 滑动
 * - Live Photo 支持：底部"动态照片"按钮 + 长按图片播放关联视频
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
    useBackendLoader: Boolean = false,
    sourceLabel: String = "",
    onEdit: (MediaMetadata) -> Unit = {},
    onShare: (MediaMetadata) -> Unit = {},
    onDelete: (MediaMetadata) -> Unit = {},
    onFavoriteToggle: (MediaMetadata) -> Unit = {},
    isFavorite: (MediaMetadata) -> Boolean = { false },
    onSlideshow: () -> Unit = {},
    onRename: (MediaMetadata) -> Unit = {},
    onSetCover: (MediaMetadata) -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, mediaList.lastIndex)) {
        mediaList.size
    }
    val currentIndex by remember { derivedStateOf { pagerState.currentPage } }
    val currentMedia = mediaList[currentIndex]
    val scope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }

    // 跟踪当前页是否处于缩放态：缩放时禁用 pager 滑动，避免放大浏览时误翻页。
    var currentZoomed by remember { mutableStateOf(false) }

    // Live Photo 视频播放状态：非空时在预览上层渲染 VideoPlayer 播放关联视频。
    // 点击"动态照片"按钮或长按图片时设置，播放器关闭后清空。
    var livePhotoVideoMedia by remember { mutableStateOf<MediaMetadata?>(null) }
    // 本地 Live Photo 视频的 file:// URI（仅 LOCAL 源使用，后端源走 backendStreamUrl）
    var livePhotoVideoUrl by remember { mutableStateOf<String?>(null) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val animateOutThenDismiss: () -> Unit = {
        visible = false
        scope.launch { kotlinx.coroutines.delay(320); onDismiss() }
    }

    Dialog(
        onDismissRequest = animateOutThenDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(350)) + scaleIn(initialScale = 0.88f, animationSpec = tween(350)),
            exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.92f, animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // ── 毛玻璃背景层 ──
                // 用当前图片的低分辨率缩略图放大填充 + 大模糊半径模拟毛玻璃效果。
                // 叠加半透明黑色遮罩保证前景图片对比度。
                BlurredBackground(
                    media = currentMedia,
                    useBackendLoader = useBackendLoader
                )

                // 可滑动切换的图片页：缩放态禁用翻页
                // 内存优化：仅当前页加载原图，相邻页加载缩略图，
                // 避免 Pager 预渲染的多个全分辨率 ImageBitmap 同时驻留内存。
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !currentZoomed
                ) { page ->
                    val isCurrentPage = page == pagerState.currentPage
                    ZoomableImage(
                        media = mediaList[page],
                        useBackendLoader = useBackendLoader,
                        loadFullResolution = isCurrentPage,
                        onTapClose = animateOutThenDismiss,
                        onZoomChanged = { zoomed ->
                            // 仅当 page == currentIndex 时更新，避免预加载页干扰
                            if (page == pagerState.currentPage) {
                                currentZoomed = zoomed
                            }
                        },
                        onLongPress = {
                            // 长按图片：如果是 Live Photo，播放关联视频
                            val m = mediaList[page]
                            if (m.is_live_photo && m.live_photo_video_id.isNotEmpty()) {
                                if (useBackendLoader) {
                                    livePhotoVideoMedia = m.copy(
                                        id = m.live_photo_video_id,
                                        type = MediaType.VIDEO,
                                        filename = m.filename
                                    )
                                } else {
                                    // 本地 Live Photo：先提取嵌入视频到临时 file:// URI，
                                    // 成功后再渲染 VideoPlayer，避免播放器在 URL 就绪前用
                                    // 错误的后端流地址初始化导致无法播放。
                                    scope.launch {
                                        val url = extractLocalLivePhotoVideo(m.id)
                                        if (url != null) {
                                            livePhotoVideoUrl = url
                                            livePhotoVideoMedia = m.copy(
                                                id = m.live_photo_video_id,
                                                type = MediaType.VIDEO,
                                                filename = m.filename
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                // V7：左右导航箭头（非首张显示左箭头，非末张显示右箭头）
                if (pagerState.currentPage > 0) {
                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "上一张",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                if (pagerState.currentPage < mediaList.lastIndex) {
                    IconButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_forward),
                            contentDescription = "下一张",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                PreviewInfoBar(
                    media = currentMedia,
                    currentIndex = currentIndex,
                    totalCount = mediaList.size,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // 关闭按钮
                IconButton(
                    onClick = animateOutThenDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 40.dp, start = 16.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 分享按钮
                IconButton(
                    onClick = {
                        if (!isSharing) {
                            isSharing = true
                            scope.launch(dispatchers.io) {
                                try {
                                    val bytes = BackendImageLoader.loadFullImageBytes(currentMedia.id)
                                    if (bytes != null) {
                                        val mimeType = when (currentMedia.type) {
                                            MediaType.VIDEO -> "video/mp4"
                                            else -> "image/jpeg"
                                        }
                                        shareMedia(bytes, currentMedia.filename, mimeType)
                                    }
                                } catch (e: Exception) {
                                    // 分享失败静默
                                } finally {
                                    isSharing = false
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 16.dp)
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    } else {
                        Icon(
                            painterResource(Res.drawable.ic_share),
                            contentDescription = "分享",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // ── 底部区：缩略图条 + 详情面板 + 操作栏 ──
                var showDetails by remember { mutableStateOf(true) }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    // 缩略图条：水平滚动，当前项高亮，点击跳转
                    ThumbnailStrip(
                        mediaList = mediaList,
                        currentIndex = currentIndex,
                        useBackendLoader = useBackendLoader,
                        onThumbnailClick = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    )

                    if (showDetails) {
                        DetailPanel(
                            media = currentMedia,
                            sourceLabel = sourceLabel
                        )
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 动态照片按钮：仅当当前图片是 Live Photo 时显示。
                            // 点击播放关联的视频部分（live_photo_video_id）。
                            if (currentMedia.is_live_photo && currentMedia.live_photo_video_id.isNotEmpty()) {
                                PreviewActionButton(
                                    iconRes = Res.drawable.ic_play_arrow,
                                    label = "动态照片",
                                    onClick = {
                                        if (useBackendLoader) {
                                            livePhotoVideoMedia = currentMedia.copy(
                                                id = currentMedia.live_photo_video_id,
                                                type = MediaType.VIDEO,
                                                filename = currentMedia.filename
                                            )
                                        } else {
                                            // 本地 Live Photo：先提取视频到 file:// URI，
                                            // 成功后再渲染播放器，避免竞态导致播错源。
                                            scope.launch {
                                                val url = extractLocalLivePhotoVideo(currentMedia.id)
                                                if (url != null) {
                                                    livePhotoVideoUrl = url
                                                    livePhotoVideoMedia = currentMedia.copy(
                                                        id = currentMedia.live_photo_video_id,
                                                        type = MediaType.VIDEO,
                                                        filename = currentMedia.filename
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_edit,
                                label = "编辑",
                                onClick = { onEdit(currentMedia) }
                            )
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_edit,
                                label = "重命名",
                                onClick = { onRename(currentMedia) }
                            )
                            // V7：设为相册封面（仅在相册上下文有意义）
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_image_placeholder,
                                label = "封面",
                                onClick = { onSetCover(currentMedia) }
                            )
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_share,
                                label = "分享",
                                onClick = { onShare(currentMedia) }
                            )
                            PreviewActionButton(
                                iconRes = if (isFavorite(currentMedia)) Res.drawable.ic_star_filled
                                          else Res.drawable.ic_star_outline,
                                label = if (isFavorite(currentMedia)) "已收藏" else "收藏",
                                onClick = { onFavoriteToggle(currentMedia) }
                            )
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_delete,
                                label = "删除",
                                onClick = {
                                    animateOutThenDismiss()
                                    onDelete(currentMedia)
                                }
                            )
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_info,
                                label = "详情",
                                onClick = { showDetails = !showDetails }
                            )
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_slideshow,
                                label = "幻灯片",
                                onClick = onSlideshow
                            )
                        }
                    }
                }

                // Live Photo 视频播放器覆盖层：点击"动态照片"按钮或长按图片后显示。
                // 在预览对话框上层全屏播放关联视频，关闭后返回图片预览。
                livePhotoVideoMedia?.let { videoMedia ->
                    VideoPlayer(
                        media = videoMedia,
                        initialDurationSeconds = null,
                        onDismiss = {
                            livePhotoVideoMedia = null
                            livePhotoVideoUrl = null
                        },
                        videoUrl = livePhotoVideoUrl
                    )
                }
            } // AnimatedVisibility inner Box
        }
    }
}

/**
 * 单张可缩放图片。
 *
 * 缩放交互重写：
 * - 双指捏合缩放：max 5x，min 1x
 * - 双击在 1x → 2x → 4x 间循环，超过 4x 回到 1x
 * - 单指拖动平移：仅缩放>1 时生效
 * - 单击：缩放态先复位（不关闭），1x 下关闭预览
 * - 长按：触发 [onLongPress] 回调（用于 Live Photo 视频播放）
 * - 缩放态禁用 pager 翻页：通过 [onZoomChanged] 回调通知父组件
 *
 * 手势冲突处理：detectTransformGestures 与 HorizontalPager 共存时，
 * 缩放>1 的情况下 pager 的 userScrollEnabled 被关闭，
 * 平移手势在缩放态下由 transform 消费；1x 时 pager 正常响应水平滑动。
 *
 * @param loadFullResolution true 加载全尺寸原图；false 仅加载缩略图（低内存占位），
 *   用于非当前页，避免 Pager 预渲染的远页全图占用过多内存。
 *   当用户滑动到该页时 [loadFullResolution] 变为 true，重新触发 LaunchedEffect 加载原图。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ZoomableImage(
    media: MediaMetadata,
    useBackendLoader: Boolean,
    onTapClose: () -> Unit,
    onZoomChanged: (Boolean) -> Unit = {},
    onLongPress: () -> Unit = {},
    loadFullResolution: Boolean = true
) {
    // 先加载缩略图立即显示，再异步加载原图（降采样）替换。
    // 避免“点击大图 → 白屏等待 → 内存暴涨”的体验。
    var thumbnailBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var fullImageBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoadingFull by remember(media.id) { mutableStateOf(true) }
    var loadFailed by remember(media.id) { mutableStateOf(false) }
    var scale by remember(media.id) { mutableStateOf(1f) }
    var offsetX by remember(media.id) { mutableStateOf(0f) }
    var offsetY by remember(media.id) { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 双击缩放循环：1x → 2x → 4x → 1x
    val zoomSteps = listOf(1f, 2f, 4f)

    // 缩放状态变化时通知父组件（用于禁用/启用 pager 翻页）
    LaunchedEffect(scale) {
        onZoomChanged(scale > 1f)
    }

    // 加载图片：当前页加载原图，非当前页加载缩略图（低内存占位）
    LaunchedEffect(media.id, useBackendLoader, loadFullResolution) {
        if (!loadFullResolution && fullImageBitmap == null) {
            // 非当前页：仅加载缩略图作为低内存占位
            scope.launch(dispatchers.io) {
                try {
                    val thumb = if (useBackendLoader) {
                        BackendImageLoader.loadThumbnail(media.id)
                    } else {
                        loadThumbnail(media.id)
                    }
                    fullImageBitmap = thumb
                } catch (e: Exception) {
                    // 加载失败
                } finally {
                    isLoadingFull = false
                }
            }
        } else if (loadFullResolution) {
            // 当前页：加载全尺寸原图
            isLoadingFull = true
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
                    isLoadingFull = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点击手势：双击在 1x/2x/4x 间循环，单击缩放态复位/1x 关闭
            .pointerInput(media.id) {
                detectTapGestures(
                    onTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            onTapClose()
                        }
                    },
                    onDoubleTap = {
                        // 1x → 2x → 4x → 1x 循环
                        val currentStepIndex = zoomSteps.indexOfFirst { kotlin.math.abs(it - scale) < 0.1f }
                        val nextScale = zoomSteps[
                            ((currentStepIndex + 1) % zoomSteps.size).coerceAtLeast(0)
                        ]
                        scale = nextScale
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onLongPress = {
                        // 长按图片：触发 Live Photo 视频播放回调
                        onLongPress()
                    }
                )
            }
            // 双指缩放 + 单指平移：缩放态下消费手势
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        // 回到 1x 时复位平移
                        offsetX = 0f
                        offsetY = 0f
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
            // 原图加载中且无缩略图：显示 loading
            isLoadingFull && fullImageBitmap == null && thumbnailBitmap == null -> {
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

            // 原图已加载：显示原图（降采样后的）
            fullImageBitmap != null -> {
                Image(
                    bitmap = fullImageBitmap!!,
                    contentDescription = media.filename,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // 原图尚未加载但有缩略图：先显示缩略图（占位，避免白屏）
            thumbnailBitmap != null && isLoadingFull -> {
                Image(
                    bitmap = thumbnailBitmap!!,
                    contentDescription = media.filename,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // 加载失败
            loadFailed -> {
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

            // 兜底空状态
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/**
 * 预加载触发阈值：当最后一个可见 item 距离列表末尾 ≤ 此值时触发加载下一页。
 *
 * 与 [DateGroupedGrid] 共用同一阈值，保证搜索态与非搜索态预加载体验一致。
 */
private const val PRELOAD_THRESHOLD = 10

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
    searchQuery: String = "",
    favoriteIds: Set<String> = emptySet(),
    onFavoriteToggle: (String) -> Unit = {},
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyStaggeredGridState()

    // 预加载：滚动接近底部时自动触发加载下一页。
    // snapshotFlow 监听布局信息变化，当剩余可见距离 ≤ 阈值时触发 onLoadMore。
    // distinctUntilChanged 确保同一批次只触发一次，避免重复加载。
    if (onLoadMore != null) {
        val currentOnLoadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(gridState) {
            snapshotFlow {
                val layoutInfo = gridState.layoutInfo
                val total = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                total - lastVisible
            }
                .filter { it in 1..PRELOAD_THRESHOLD }
                .distinctUntilChanged()
                .collect { currentOnLoadMore() }
        }
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Adaptive(minSize = 110.dp),
        // 外边距 8dp，项间横向 4dp 纵向 6dp：更紧凑的瀑布流布局。
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 6.dp,
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
               searchQuery = searchQuery,
               isFavorite = favoriteIds.contains(media.id),
               onFavoriteToggle = { onFavoriteToggle(media.id) },
                modifier = Modifier
                    .fillMaxWidth()
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
    searchQuery: String = "",
    isFavorite: Boolean = false,
    onFavoriteToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isVideo = media.type == MediaType.VIDEO
    // 瀑布流高度：用图片实际宽高比，钳制在 0.6–1.8 防止极端值导致项过高/过矮。
    val aspectRatio = if (media.width > 0 && media.height > 0) {
        (media.width.toFloat() / media.height.toFloat()).coerceIn(0.6f, 1.8f)
    } else {
        1f
    }
    // 长按触感反馈：长按选中/预览时震动，提升交互确认感（cross-platform HapticFeedback）。
    val hapticFeedback = LocalHapticFeedback.current
    // InteractionSource tracks press state for the Card via combinedClickable,
    // replacing the old detectTapGestures onPress callback. This coexists properly
    // with child .clickable modifiers (e.g. favorite button) on Android real devices.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 缩略图状态：remember 以 media.id 为 key，确保 LazyGrid 复用 slot 渲染不同媒体时
    // 状态随 media 切换而重置，避免滚动时把上一项的 thumbnailBitmap/isLoading 串到新项
    // 造成错位（与上方 ZoomableImage 的 remember(media.id) 同口径）。
    var thumbnailBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
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
    // 点击按压反馈：按下时缩到 0.95，松开 spring 回弹到 1.0，
    // 与选中态缩放叠加（pressScale × selectionScale）共同作用于 graphicsLayer。
    // isPressed 由 interactionSource.collectIsPressedAsState() 驱动（见上方）。
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressScale"
    )
    val combinedScale = selectionScale * pressScale
    // 加载完成入场动画：spring 驱动缩放+透明度，缩略图出现时更丝滑。
    // isLoading=true 时 alpha=0、scale=0.92；加载完成 spring 到 alpha=1、scale=1。
    val loadAlpha by animateFloatAsState(
        targetValue = if (isLoading) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "loadAlpha"
    )
    val loadScale by animateFloatAsState(
        targetValue = if (isLoading) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "loadScale"
    )
    Card(
       modifier = modifier
           .aspectRatio(aspectRatio)
            .graphicsLayer(
                scaleX = combinedScale,
                scaleY = combinedScale
            )
           .shadow(
               elevation = if (isSelected) 4.dp else 2.dp,
               shape = RoundedCornerShape(16.dp),
               clip = false
           )
           .combinedClickable(
               interactionSource = interactionSource,
               indication = ripple(),
               onClick = { onClick() },
               onLongClick = {
                   hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                   onLongClick()
               }
           ),
       shape = RoundedCornerShape(16.dp),
       colors = CardDefaults.cardColors(
           containerColor = MaterialTheme.colorScheme.surfaceVariant
       )
   ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, borderColor, RoundedCornerShape(16.dp))
        ) {
            // 媒体缩略图
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
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
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    alpha = loadAlpha,
                                    scaleX = loadScale,
                                    scaleY = loadScale
                                ),
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

                // Live Photo 标识：左下角"Live"文字徽标（类似小米相册的"秒"标记），
                // 白色文字 + 半透明黑色背景圆角胶囊，清晰标识 Live Photo 身份。
                if (media.is_live_photo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(Res.drawable.ic_play_arrow),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                "Live",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
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

            // 收藏星标按钮：右上角，半透明背景圆形，点击切换收藏状态。
            // 不受选中状态影响，始终可点；与左上角的选中勾选徽标互不干扰。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable { onFavoriteToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) Res.drawable.ic_star_filled
                        else Res.drawable.ic_star_outline
                    ),
                    contentDescription = if (isFavorite) "取消收藏" else "收藏",
                    tint = if (isFavorite) Color(0xFFFFD700) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
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
                    highlightFilename(media.filename, searchQuery),
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
 * 预览顶部信息栏：文件名 + 大小 + 日期 + 页码，半透明黑色背景。
 */
@Composable
private fun PreviewInfoBar(
    media: MediaMetadata,
    currentIndex: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：文件名 + 副信息（大小 • 日期）
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = media.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(formatBytesToMB(media.size))
                        if (media.created_at > 0) {
                            append(" • ")
                            append(formatPreviewDate(media.created_at))
                        }
                        if (media.width > 0 && media.height > 0) {
                            append(" • ")
                            append("${media.width}×${media.height}")
                        }
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // 右侧：页码
            Text(
                text = "${currentIndex + 1} / $totalCount",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 格式化预览日期：epoch 毫秒 → "YYYY/MM/DD"（简短格式，无时分）。
 * 纯整数运算，无 java.time 依赖。
 */
private fun formatPreviewDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val localMillis = epochMillis + systemTimeZoneOffsetMillis()
    val days = localMillis.floorDiv(86_400_000L)
    val (y, m, d) = civilFromDaysPreview(days)
    return "$y/${m.pad2Preview()}/${d.pad2Preview()}"
}

private fun civilFromDaysPreview(z: Long): Triple<Int, Int, Int> {
    val z0 = z + 719468L
    val era = if (z0 >= 0) z0 / 146097 else (z0 - 146096) / 146097
    val doe = z0 - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = if (m <= 2) y + 1 else y
    return Triple(year.toInt(), m, d)
}

private fun Int.pad2Preview(): String = if (this < 10) "0$this" else this.toString()

/**
 * 预览底部缩略图条：水平滚动列表，当前项高亮，点击跳转。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ThumbnailStrip(
    mediaList: List<MediaMetadata>,
    currentIndex: Int,
    useBackendLoader: Boolean,
    onThumbnailClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 当前页变化时，将缩略图条滚动到当前项居中位置
    LaunchedEffect(currentIndex) {
        if (mediaList.size > 8) {
            scope.launch {
                lazyListState.animateScrollToItem(
                    index = currentIndex.coerceIn(0, mediaList.lastIndex),
                    scrollOffset = -200
                )
            }
        }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth()
    ) {
        LazyRow(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(
                items = mediaList,
                key = { it.id },
                contentType = { "thumbnail_strip_item" }
            ) { media ->
                val index = mediaList.indexOf(media)
                val isCurrent = index == currentIndex
                ThumbnailStripItem(
                    media = media,
                    isCurrent = isCurrent,
                    useBackendLoader = useBackendLoader,
                    onClick = { onThumbnailClick(index) }
                )
            }
        }
    }
}

/**
 * 缩略图条单项：48×48 dp 圆角缩略图，选中态加白色边框 + 轻微放大。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ThumbnailStripItem(
    media: MediaMetadata,
    isCurrent: Boolean,
    useBackendLoader: Boolean,
    onClick: () -> Unit
) {
    var thumbnailBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

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

    // 选中态缩放反馈
    val itemScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "thumbScale"
    )
    val borderColor = if (isCurrent) Color.White else Color.Transparent
    val borderWidth = if (isCurrent) 2.dp else 0.dp

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer(scaleX = itemScale, scaleY = itemScale)
            .clip(RoundedCornerShape(6.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
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
                Icon(
                    painter = painterResource(Res.drawable.ic_image_placeholder),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color.Gray
                )
            }
        }
        // 选中态遮罩
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.15f))
            )
        }
    }
}

/**
 * 毛玻璃背景层：用当前图片缩略图放大填充 + 暗色遮罩模拟模糊背景效果。
 *
 * 纯 commonMain 实现：不依赖平台 BlurEffect/RenderEffect。
 * 缩略图本身分辨率低，放大后天然带有模糊感，叠加暗色遮罩保证前景对比度。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun BlurredBackground(
    media: MediaMetadata,
    useBackendLoader: Boolean
) {
    var thumbnailBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

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

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (thumbnailBitmap != null) {
            // 缩略图放大填充全屏作为“模糊”背景
            Image(
                bitmap = thumbnailBitmap!!,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = 1.3f,
                        scaleY = 1.3f,
                        alpha = 0.25f
                    ),
                contentScale = ContentScale.Crop
            )
        }
        // 暗色遮罩：保证前景图片对比度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
        )
    }
}

/**
 * 预览底部操作栏按钮：图标 + 文字垂直排列，半透明背景，点击无涟漪（与暗色预览背景一致）。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun PreviewActionButton(
    iconRes: DrawableResource,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp
        )
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
 * 空状态视图：每个 Tab 不同文案与图标，带淡入动画。
 *
 * - Tab 0 (本地图片)：图片图标 + "相册是空的" + "下拉刷新从图库加载"
 * - Tab 1 (已上传)：云上传图标 + "还没有上传过图片" + "点击右下角按钮上传"
 * - Tab 2 (网盘图片)：云图标 + "网盘暂无图片" + "下拉刷新重试"
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun EmptyStateView(tabIndex: Int) {
    // 入场动画：图标与文案依次淡入+上移，比同时闪现更柔和。
    val iconAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(600, delayMillis = 100),
        label = "emptyIconAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(600, delayMillis = 250),
        label = "emptyTextAlpha"
    )

    // 脉冲动画：图标轻微缩放循环，赋予空状态生命力。
    val infiniteTransition = rememberInfiniteTransition(label = "emptyPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyPulseScale"
    )

    val iconRes = when (tabIndex) {
        0 -> Res.drawable.ic_photo
        1 -> Res.drawable.ic_cloud_upload
        else -> Res.drawable.ic_cloud
    }
    val title = when (tabIndex) {
        0 -> "相册是空的"
        1 -> "还没有上传过图片"
        else -> "网盘暂无图片"
    }
    val subtitle = when (tabIndex) {
        0 -> "下拉刷新从图库加载"
        1 -> "点击右下角按钮上传"
        else -> "下拉刷新重试"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 背景圆圈 + 图标，脉冲动画驱动缩放。
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        CircleShape
                    )
            )
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer(
                        scaleX = pulseScale,
                        scaleY = pulseScale,
                        alpha = iconAlpha
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * textAlpha),
            modifier = Modifier.graphicsLayer(alpha = textAlpha)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f * textAlpha),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.graphicsLayer(alpha = textAlpha)
        )
    }
}

/**
 * 全屏加载态：脉冲动画驱动 App logo 缩放与透明度循环，
 * 比单一 CircularProgressIndicator 更有品牌感且视觉柔和。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun FullScreenLoading() {
    // 脉冲过渡：scale 在 0.85→1.15 间循环，alpha 在 0.5→1.0 间同步呼吸。
    val infiniteTransition = rememberInfiniteTransition(label = "loadingPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    // 外层光晕：一圈半透明 primary 色圆环随脉冲缩放，营造呼吸光圈效果。
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloScale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center
        ) {
            // 外层呼吸光圈
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(
                        scaleX = haloScale,
                        scaleY = haloScale,
                        alpha = haloAlpha
                    )
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
            // 脉冲 App logo
            Icon(
                painter = painterResource(Res.drawable.ic_openclaw),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer(
                        scaleX = pulseScale,
                        scaleY = pulseScale,
                        alpha = pulseAlpha
                    ),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * 列表加载失败占位：动画错误图标 + 错误文案 + 重试按钮（带 loading 状态）。
 *
 * 用于后端未启动 / 网络异常导致 [MediaViewModel.listLoadError] 非空且 mediaList 为空时，
 * 替代"暂无 X"误导为白屏的场景。重试走对应 Tab 的强制刷新。
 * 错误图标做轻微据头+缩放动画，按钮点击后显示 CircularProgressIndicator。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ErrorStateView(
    message: String,
    onRetry: () -> Unit
) {
    // 错误图标动画：轻微缩放+据头，循环播放，吸引注意但不刺眼。
    val infiniteTransition = rememberInfiniteTransition(label = "errorShake")
    val shakeScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "errorScale"
    )
    val shakeRotation by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "errorRotation"
    )

    // 重试按钮 loading 状态：点击后显示圆圈，防止重复点击。
    var isRetrying by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 背景圆圈 + 错误图标，据头动画驱动 rotation+scale。
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        CircleShape
                    )
            )
            Icon(
                painter = painterResource(Res.drawable.ic_error),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer(
                        scaleX = shakeScale,
                        scaleY = shakeScale,
                        rotationZ = shakeRotation
                    ),
                tint = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "加载失败",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
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
        FilledTonalButton(
            onClick = {
                if (!isRetrying) {
                    isRetrying = true
                    onRetry()
                }
            },
            enabled = !isRetrying
        ) {
            if (isRetrying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
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
}

/**
 * 搜索 / 筛选无结果占位：数据源有内容但 [MediaViewModel.filteredList] 为空。
 *
 * 主文案固定"未找到匹配的媒体"，副文按当前过滤条件动态描述（仅关键词 / 仅类型 / 两者），
 * 并附"清除筛选"按钮一键回到完整列表。与 [ErrorStateView]（加载失败）和"暂无 X"（真无数据）
 * 三者互斥，由 [MediaListScreen] 的 when 优先级保证只显示其一。
 *
 * @param searchQuery 当前搜索关键词（空串表示未启用关键词）
 * @param filterType 当前类型筛选维度（ALL 表示未启用类型筛选）
 * @param onClear 清除所有过滤条件回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun NoSearchResultView(
    searchQuery: String,
    filterType: MediaFilterType,
    onClear: () -> Unit
) {
    val hasQuery = searchQuery.isNotBlank()
    val hasFilter = filterType != MediaFilterType.ALL
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_search_off),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "未找到匹配的媒体",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 副文按过滤来源组合描述：关键词 / 类型 / 二者皆有。
        val hint = when {
            hasQuery && hasFilter -> "关键词“$searchQuery”在“${filterType.label}”中无匹配"
            hasQuery -> "没有名称包含“$searchQuery”的媒体"
            hasFilter -> "“${filterType.label}”类型下暂无媒体"
            else -> "尝试更换关键词或筛选条件"
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onClear) {
            Icon(
                painterResource(Res.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("清除筛选")
        }
    }
}

/**
 * 构建带搜索高亮的文件名 AnnotatedString。
 *
 * 在 filename 中查找 query 的首个大小写不敏感匹配位置，将匹配段用黄色半透明背景标出。
 * query 为空或未匹配时返回普通文本，无额外样式开销。
 *
 * 纯 Kotlin 字符串操作，无 java 或 android 包依赖，commonMain 安全。
 */
private fun highlightFilename(filename: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(filename)
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(filename)

    val lowerFilename = filename.lowercase()
    val lowerQuery = q.lowercase()
    val index = lowerFilename.indexOf(lowerQuery)

    if (index < 0) return AnnotatedString(filename)

    return buildAnnotatedString {
        append(filename.substring(0, index))
        withStyle(SpanStyle(background = Color(0xFFFFD600).copy(alpha = 0.4f))) {
            append(filename.substring(index, index + q.length))
        }
        append(filename.substring(index + q.length))
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
 *
 * 包含：全选/取消全选、已选计数（带 animateContentSize 动画）、批量分享、上传、删除。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    onDelete: () -> Unit,
    onUpload: () -> Unit,
    onShare: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddToAlbum: () -> Unit = {},
    onCreateShareLink: () -> Unit = {},
    isDeleting: Boolean,
    isUploading: Boolean,
    showUploadButton: Boolean,
    showAddToAlbumButton: Boolean = false,
    showShareLinkButton: Boolean = false
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

    BottomAppBar(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 全选 / 取消全选
        IconButton(onClick = { if (isAllSelected) onDeselectAll() else onSelectAll() }) {
            Icon(
                painterResource(Res.drawable.ic_check_circle),
                contentDescription = if (isAllSelected) "取消全选" else "全选",
                tint = if (isAllSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 已选择计数器 —— animateContentSize 让数字变化时宽度平滑过渡
        Text(
            "已选 $selectedCount/$totalCount",
            modifier = Modifier
                .weight(1f)
                .animateContentSize(animationSpec = tween(200)),
            fontWeight = FontWeight.Medium
        )

        // 加入相册（仅后端源显示）
        if (showAddToAlbumButton) {
            IconButton(onClick = onAddToAlbum) {
                Icon(
                    painterResource(Res.drawable.ic_photo),
                    contentDescription = "加入相册"
                )
            }
        }

        // 批量分享
        IconButton(onClick = onShare) {
            Icon(
                painterResource(Res.drawable.ic_share),
                contentDescription = "分享选中项"
            )
        }

        // 生成分享链接（V7 §1.2，仅云端源显示）
        if (showShareLinkButton) {
            IconButton(onClick = onCreateShareLink) {
                Icon(
                    painterResource(Res.drawable.ic_link),
                    contentDescription = "生成分享链接"
                )
            }
        }

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
                    contentDescription = "删除选中项",
                    tint = MaterialTheme.colorScheme.error
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

/**
 * 媒体长按上下文菜单（底部弹层风格）。
 *
 * 提供两个选项：预览、加入相册。
 * 用 [AlertDialog] 实现，commonMain 全平台兼容。
 */

/**
 * "加入相册"相册选择对话框。
 *
 * 弹出相册列表供用户选择目标相册，选中即触发加入操作。
 * 列表为空时提示用户先创建相册。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AddToAlbumDialog(
    albums: List<com.wgt.feature.media.MediaService.Album>,
    isLoading: Boolean,
    onPick: (com.wgt.feature.media.MediaService.Album) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入相册", fontWeight = FontWeight.Bold) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(32.dp)
                        )
                    }
                    albums.isEmpty() -> {
                        Text(
                            "暂无相册，请先创建",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Column {
                            albums.forEach { album ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(album) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painterResource(Res.drawable.ic_photo),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.size(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            album.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${album.mediaCount} 项",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 上传进度对话框。
 *
 * 上传期间模态展示当前进度 "上传中 2/5..." + 线性进度条。
 * 上传中不可取消（onDismiss 为空实现），完成后 ViewModel 将 uploadProgress 置 null，对话框自动消失。
 *
 * @param uploaded 已上传文件数
 * @param total 总文件数
 * @param onDismiss 关闭回调（上传中无效）
 */
@Composable
private fun UploadProgressDialog(
    uploaded: Int,
    total: Int,
    onDismiss: () -> Unit
) {
    val progress = if (total > 0) uploaded.toFloat() / total.toFloat() else 0f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传中", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "$uploaded / $total",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "上传中 $uploaded/$total...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

// ──────────────────────────────────────────────────────────────────
// PRD-v7 §1.4 时光相册：回忆卡片横滚区域
// ──────────────────────────────────────────────────────────────────

/**
 * 单张回忆卡片宽度（dp）。约 160dp，在 360dp 屏宽下可同时露出 2 张完整卡片 + 第 3 张
 * 一部分，暗示可横滚；卡片高度按 2×2 封面网格 + 标题约 200dp。
 */
private val MemoryCardWidth = 160.dp

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
private fun MemoryCardRow(
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
private fun MemoryCard(
    month: MemoryMonth,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(MemoryCardWidth)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        // 封面区：2×2 网格缩略图，固定高度，clip 到卡片圆角
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
private fun MemoryCoverThumb(
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

// ============================================================
// V7 §1.2：分享链接结果对话框
// ============================================================

/** 分享链接结果数据。 */
private data class ShareLinkResult(
    val url: String,
    val expiresAt: Long
)

/**
 * 分享链接结果对话框：显示生成的 URL，支持复制到剪贴板。
 *
 * @param url 分享链接 URL
 * @param expiresAt 过期时间戳（epoch ms）
 * @param onDismiss 关闭回调
 */
@Composable
private fun ShareLinkDialog(
    url: String,
    expiresAt: Long,
    onDismiss: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享链接已生成") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "链接（$expiresAt 后过期）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (copied) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "已复制到剪贴板",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                copied = true
            }) {
                Text(if (copied) "已复制" else "复制链接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 分享链接配置对话框（V7 §1.2）。
 *
 * 允许用户在生成分享链接前选择：
 * - 有效期（1小时 / 24小时 / 7天 / 30天）
 * - 密码保护（可选）
 *
 * @param onDismiss 取消
 * @param onCreate 确认创建，password 可空，hours 有效期
 */
@Composable
private fun ShareLinkConfigDialog(
    onDismiss: () -> Unit,
    onCreate: (password: String?, hours: Int) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var selectedHours by remember { mutableStateOf(24) }

    val expiryOptions = listOf(1 to "1 小时", 24 to "24 小时", 168 to "7 天", 720 to "30 天")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成分享链接") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("有效期", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                expiryOptions.forEach { (hours, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedHours == hours,
                            onClick = { selectedHours = hours }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("密码保护（可选）", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("留空则无密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(password, selectedHours) }) {
                Text("创建链接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
