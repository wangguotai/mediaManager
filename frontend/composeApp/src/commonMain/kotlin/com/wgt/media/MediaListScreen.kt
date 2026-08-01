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

    // V9：待应用的标签搜索——从"我的"标签云点击 chip 触发。切到媒体 Tab 时
    // LaunchedEffect(selectedTab) 会 clearSearchAndFilter，故先记下标签名，
    // 由下方 LaunchedEffect(pendingTagSearch) 在 Tab 切换完成后设入 searchQuery。
    var pendingTagSearch by remember { mutableStateOf<String?>(null) }

    // 高级搜索对话框显隐：SearchBar 上的「高级搜索」图标触发，
    // AdvancedSearchDialog 回调 Map<String,String> 条件后由本 Screen 启协程调
    // MediaService.advancedSearch + applyAdvancedSearchResults 替换列表。
    var showAdvancedSearch by remember { mutableStateOf(false) }
    val advancedSearchScope = rememberCoroutineScope()

    // 长按上下文菜单：非空时弹出 DropdownMenu，值为触发的 MediaMetadata。
    // 仅在非选择模式下使用——选择模式下长按直接选中/预览，不走此菜单。

    // "加入相册"选择对话框：非空时弹出相册列表供用户选择目标相册。
    var addToAlbumMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // V7 §1.2：分享链接结果对话框状态
    var shareLinkResult by remember { mutableStateOf<ShareLinkResult?>(null) }
    var shareLinkError by remember { mutableStateOf<String?>(null) }
    var showShareLinkConfig by remember { mutableStateOf(false) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    // V8：批量标签对话框
    var showBatchTagDialog by remember { mutableStateOf(false) }
    // V8：媒体详情对话框
    var mediaInfoTarget by remember { mutableStateOf<String?>(null) }

    // 批量分享进行中标记：防止重复点击，由 onBatchShare 协程读写。
    var isBatchSharing by remember { mutableStateOf(false) }

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

    // V9：标签云 chip 点击搜索——从"我的"切到媒体 Tab 后，上面的 LaunchedEffect(selectedTab)
    // 会先 clearSearchAndFilter。这里在 pendingTagSearch 变化时（Tab 已切完）把 query 设为 #tag，
    // 并展开搜索栏让用户看见。延迟一帧避开 clear 的竞争。
    LaunchedEffect(pendingTagSearch) {
        val tag = pendingTagSearch ?: return@LaunchedEffect
        pendingTagSearch = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.delay(50)  // 等 selectedTab 的 clear 落定
            viewModel.applySearchQuery("#$tag")
            searchExpanded = true
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
                    onRename = { media -> renameTarget = media },
                    onShowInfo = { id -> mediaInfoTarget = id },
                    onRotate = { media ->
                        // V8：调后端 /api/media/rotate 旋转 90°，成功后按当前 Tab
                        // 强制刷新对应缓存列表（拿到新 orientation 重新渲染）。
                        // 仅云端源走到这里（按钮仅 useBackendLoader 时显示）。
                        advancedSearchScope.launch(dispatchers.io) {
                            val ok = MediaService.rotateMedia(media.id, 90)
                            if (ok) {
                                // 清缓存后按 Tab 触发强制刷新；预览对话框读取的
                                // imageOnlyList 派生自 viewModel.filteredList，
                                // 列表刷新后预览内的图片方向也会跟着更新。
                                when (selectedTab) {
                                    1 -> viewModel.loadCloudViewForTab(forceRefresh = true)
                                    2 -> viewModel.loadCloudMediaList(forceRefresh = true)
                                }
                            }
                        }
                    }
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

    // V8：批量重命名对话框
    if (showBatchRenameDialog) {
        BatchRenameDialog(
            selectedCount = viewModel.selectedCount,
            onDismiss = { showBatchRenameDialog = false },
            onConfirm = { prefix, startIndex ->
                showBatchRenameDialog = false
                viewModel.batchRenameSelected(prefix, startIndex)
            }
        )
    }

    // V8：批量标签对话框
    if (showBatchTagDialog) {
        BatchTagDialog(
            selectedCount = viewModel.selectedCount,
            onDismiss = { showBatchTagDialog = false },
            onConfirm = { tag ->
                showBatchTagDialog = false
                viewModel.batchAddTagToSelected(tag)
            }
        )
    }

    // V8：媒体详情对话框
    mediaInfoTarget?.let { mediaId ->
        MediaInfoDialog(
            mediaId = mediaId,
            onDismiss = { mediaInfoTarget = null }
        )
    }

    // V8：高级搜索对话框——条件确权后启协程调后端 /api/media/advanced-search，
    // 命中结果灌入 ViewModel.replace 列表并关对话框；空结果给 Snackbar 提示。
    // 不走 ViewModel 方法（ViewModel 仅暴露 applyAdvancedSearchResults 结果灌入），
    // 故网络调用在本 Screen 内用 advancedSearchScope.launch 发起。
    if (showAdvancedSearch) {
        AdvancedSearchDialog(
            onDismiss = { showAdvancedSearch = false },
            onSearch = { opts ->
                showAdvancedSearch = false
                advancedSearchScope.launch {
                    val results = com.wgt.feature.media.MediaService.advancedSearch(opts)
                    if (results == null) {
                        snackbarHostState.showSnackbar("高级搜索失败，请稍后重试")
                    } else if (results.isEmpty()) {
                        viewModel.applyAdvancedSearchResults(results)
                        snackbarHostState.showSnackbar("未找到匹配的媒体")
                    } else {
                        viewModel.applyAdvancedSearchResults(results)
                    }
                }
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
                    showBatchRenameButton = selectedTab != 0, // V8：仅云端源显示批量重命名
                    showBatchTagButton = selectedTab != 0, // V8：仅云端源显示批量标签
                    showBatchUnfavoriteButton = selectedTab != 0, // V8：仅云端源显示批量取消收藏
                    showBatchShareButton = selectedTab != 0, // 仅云端源显示批量分享
                    onCreateShareLink = {
                        // V7 §1.2：打开配置对话框（密码可选 + 有效期选择）
                        shareLinkError = null
                        showShareLinkConfig = true
                    },
                    onBatchRename = {
                        // V8：打开批量重命名对话框
                        showBatchRenameDialog = true
                    },
                    onBatchTag = {
                        // V8：打开批量标签对话框
                        showBatchTagDialog = true
                    },
                    onBatchUnfavorite = {
                        // V8：批量取消收藏选中项
                        viewModel.batchRemoveFavoritesFromSelected()
                    },
                    onBatchRotate = {
                        // V8：批量旋转选中项（顺时针 90°）
                        viewModel.batchRotateSelectedMedia(90)
                    },
                    onBatchShare = {
                        // 批量分享：调 /api/media/batch-share 为每个选中项各生成一条分享链接。
                        // 成功后 snackbar 提示创建数量并清空选择；失败提示错误。
                        val ids = viewModel.selectedMediaIds.toList()
                        if (ids.isEmpty()) {
                            advancedSearchScope.launch { snackbarHostState.showSnackbar("请先选择媒体") }
                        } else if (!isBatchSharing) {
                            isBatchSharing = true
                            advancedSearchScope.launch(dispatchers.io) {
                                val results = MediaService.batchShare(ids)
                                isBatchSharing = false
                                val msg = when {
                                    results == null -> "批量分享失败，请稍后重试"
                                    results.isEmpty() -> "未创建分享链接"
                                    else -> {
                                        viewModel.deselectAll()
                                        "已创建 ${results.size} 个分享链接"
                                    }
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    showBatchRotateButton = selectedTab != 0 // V8：仅云端源显示批量旋转
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
                    onNavigateToFileManagement = onNavigateToFileManagement,
                    // V9：标签云 chip 点击搜索——切到"网盘图片" Tab 并把搜索 query 设为 #tag。
                    // MyTabContent 在"我的" Tab 渲染，搜索栏仅 Tab 0-2 显示，故需切 Tab。
                    // 因 LaunchedEffect(selectedTab) 会 clearSearchAndFilter，这里只切 Tab +
                    // 记下待搜索标签，由下方 pendingTagSearch 的 LaunchedEffect 在 Tab 切换后应用。
                    onTagSearch = { tag ->
                        pendingTagSearch = tag
                        selectedTab = 2
                    },
                    onShowSnackbar = { msg ->
                        advancedSearchScope.launch { snackbarHostState.showSnackbar(msg) }
                    }
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
                    onSearchSubmit = { /* IME 搜索键：去抖已驱动过滤，此处无需额外动作 */ },
                    onAdvancedSearch = { showAdvancedSearch = true }
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
    onNavigateToFileManagement: () -> Unit,
    // V9：标签云 chip 点击搜索回调——传入标签名，由 Screen 切 Tab + 设 query
    onTagSearch: (String) -> Unit = {},
    // 清理空标签等操作成功后的 Snackbar 提示回调（Screen 层的 snackbarHostState 不可达 MyTabContent）
    onShowSnackbar: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                    // V8：配额进度条
                    var quota by remember { mutableStateOf<MediaService.UserQuota?>(null) }
                    LaunchedEffect(Unit) { quota = MediaService.getUserQuota() }
                    quota?.let { q ->
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (q.usagePercent / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (q.usagePercent > 90) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val usedStr = q.usedMB.toString().let { it.take(it.indexOf('.') + 2) }
                        Text(
                            "$usedStr MB / ${q.quotaGB} GB (${q.usagePercent.toString().let { it.take(it.indexOf('.') + 2) }}%)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // V9：用户活跃度卡片——在仪表盘卡片（存储概览）之后展示活跃度评分。
        // 调 GET /api/media/user-activity-score，大字显示 score + level（按等级着色），
        // 下方分维度明细（action + count + points），最多 5 行。
        // 后端返回 null（未登录/异常）时静默跳过，与其他统计卡片一致的 null 容错。
        var activityScore by remember { mutableStateOf<MediaService.UserActivityScore?>(null) }
        LaunchedEffect(Unit) { activityScore = MediaService.getUserActivityScore() }
        activityScore?.let { act ->
            // 等级颜色：新手灰 / 活跃蓝 / 达人橙 / 专家绿
            val levelColor = when (act.level) {
                "专家" -> Color(0xFF43A047)
                "达人" -> Color(0xFFFF9800)
                "活跃" -> Color(0xFF1E88E5)
                else -> Color(0xFF9E9E9E)  // 新手 / 未知
            }
            // 维度中文名映射，便于用户理解 action 字段
            fun actionLabel(action: String): String = when (action) {
                "upload" -> "上传"
                "favorite" -> "收藏"
                "share" -> "分享"
                "tag" -> "标签"
                "rename" -> "重命名"
                "rotate" -> "旋转"
                else -> action
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "活跃度",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 大字号显示 score + level
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "${act.score}",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = levelColor
                        )
                        Text(
                            act.level,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = levelColor,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "共 ${act.totalActions} 次操作",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 分维度明细（最多 5 行）
                    act.breakdown.take(5).forEach { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                actionLabel(b.action),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${b.count} 次",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.width(60.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${b.points} 分",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = levelColor,
                                modifier = Modifier.width(56.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }
                }
            }
        }

        // V9：存储预测卡片——在配额进度条卡片之后，展示月均增长/未来用量/预计满配额月数。
        // 后端尚未铺量到生产时 getStorageForecast 返回 null，此处静默跳过不渲染占位。
        var storageForecast by remember { mutableStateOf<MediaService.StorageForecast?>(null) }
        LaunchedEffect(Unit) { storageForecast = MediaService.getStorageForecast() }
        storageForecast?.let { sf ->
            // 月均增长 <=0 说明样本不足（后端样本月数<2 置 0），不展示预测卡片以免误导。
            if (sf.monthlyAverageBytes > 0L) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "存储预测",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 月均增长 MB（一位小数，commonMain 无 String.format，沿用 take 截断）
                        val mbStr = sf.monthlyAverageMB.toString()
                        val mbStr1 = mbStr.take(mbStr.indexOf('.') + 2)
                        Text(
                            "月均增长 $mbStr1 MB",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 预测 1/3/6 个月后用量——只展示存在的预测点，缺失跳过
                        val predictions = listOf(1, 3, 6).mapNotNull { m ->
                            sf.predictedBytes(m)?.let { m to it }
                        }
                        if (predictions.isNotEmpty()) {
                            val parts = predictions.joinToString(" · ") { (m, b) ->
                                val mb = b.toDouble() / (1024.0 * 1024.0)
                                val s = mb.toString()
                                "${m}月后 ${s.take(s.indexOf('.') + 2)} MB"
                            }
                            Text(
                                parts,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        // 预估满配额时间：monthsUntilFull 非 null 时展示，否则提示样本不足/已超配额。
                        sf.monthsUntilFull?.let { n ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "预计 $n 个月后用满",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (n <= 2) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // V9：增长报告卡片——在存储预测之后，展示周/月环比与本年累计。
        // 后端 GET /api/media/growth-report 返回 null（未铺量/异常）时静默跳过。
        var growthReport by remember { mutableStateOf<MediaService.GrowthReport?>(null) }
        LaunchedEffect(Unit) { growthReport = MediaService.getGrowthReport() }
        growthReport?.let { gr ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "增长报告",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 本周环比
                    GrowthReportRow(
                        label = "本周",
                        count = gr.thisWeek.count,
                        changePercent = gr.weekChangePercent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 本月环比
                    GrowthReportRow(
                        label = "本月",
                        count = gr.thisMonth.count,
                        changePercent = gr.monthChangePercent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 本年累计
                    val yearMb = gr.thisYear.mb
                    val yearMbStr = yearMb.toString()
                    Text(
                        "本年累计 ${gr.thisYear.count} 项 · " +
                            "${yearMbStr.take(yearMbStr.indexOf('.') + 2)} MB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // V9：年度回顾卡片——在增长报告之后，展示该年总上传量 + 12 月柱状图 + 最忙的一天。
        // 后端 GET /api/media/yearly-review 返回 null（未铺量/异常）时静默跳过。
        var yearlyReview by remember { mutableStateOf<MediaService.YearlyReview?>(null) }
        LaunchedEffect(Unit) { yearlyReview = MediaService.getYearlyReview() }
        yearlyReview?.let { yr ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "${yr.year} 年度回顾",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 总项数 + 收藏数
                    val yrMbStr = yr.totalMB.toString()
                    Text(
                        "共 ${yr.totalCount} 项 · " +
                            "${yrMbStr.take(yrMbStr.indexOf('.') + 2)} MB" +
                            (if (yr.favorites > 0) " · ❤ ${yr.favorites}" else ""),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 12 个月柱状图：FlowRow 方块深浅表示当月上传统计强度。
                    val maxMonthCount = yr.byMonth.maxOf { it.count }.coerceAtLeast(1)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        yr.byMonth.forEach { mc ->
                            // 无上传的月份用极淡色（保持 12 格占位连续性），有上传则按强度着色。
                            val intensity = if (mc.count == 0) 0.08f
                            else (mc.count.toFloat() / maxMonthCount).coerceIn(0.15f, 1f)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                                        )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${mc.month}月",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    // 最忙的一天
                    if (yr.topDay.date.isNotEmpty() && yr.topDay.count > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "最忙的一天：${yr.topDay.date}（${yr.topDay.count} 项）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
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

        // V7：存储增长趋势图
        var trend by remember { mutableStateOf<List<MediaService.TrendPoint>?>(null) }
        LaunchedEffect(Unit) { trend = MediaService.getStorageTrend() }
        trend?.let { points ->
            if (points.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "存储趋势",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // 简易柱状图：每月新增 MB
                        val maxMB = points.maxOf { it.addedMB }.coerceAtLeast(0.1)
                        points.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    p.month,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(60.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth((p.addedMB / maxMB).toFloat())
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "${(p.addedMB).let { mb -> val s = mb.toString(); s.take(s.indexOf('.') + 2) }} MB",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(60.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val cumStr = points.last().cumMB.toString()
                        Text(
                            "累计 ${cumStr.take(cumStr.indexOf('.') + 2)} MB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V8：增长趋势详细卡片（环比+同比）
        var trendExt by remember { mutableStateOf<List<MediaService.StorageTrendExtended>?>(null) }
        LaunchedEffect(Unit) { trendExt = MediaService.getStorageTrendExtended(6) }
        trendExt?.let { months ->
            if (months.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "增长趋势",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        months.takeLast(6).forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    m.month,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${m.count} 项",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val arrow = if (m.momGrowth >= 0) "↑" else "↓"
                                val color = if (m.momGrowth >= 0)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                                val growthStr = kotlin.math.round(m.momGrowth * 10.0) / 10.0
                                Text(
                                    "$arrow ${growthStr}%",
                                    fontSize = 11.sp,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }

        // V8：存储洞察卡片（最老+最大）
        var extreme by remember { mutableStateOf<MediaService.ExtremeMedia?>(null) }
        LaunchedEffect(Unit) { extreme = MediaService.getExtremeMedia() }
        extreme?.let { ex ->
            if (ex.oldest != null || ex.largest != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "存储洞察",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ex.oldest?.let { o ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("📅 最早", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${o.filename} (${o.createdAt.take(10)})",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        ex.largest?.let { l ->
                            val sizeStr = (l.size.toDouble() / (1024.0 * 1024.0)).toString().let { it.take(it.indexOf('.') + 2) }
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("📦 最大", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${l.filename} ($sizeStr MB)",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // V8：上传日历热力图
        var calendar by remember { mutableStateOf<List<MediaService.UploadDay>?>(null) }
        LaunchedEffect(Unit) { calendar = MediaService.getUploadCalendar() }
        calendar?.let { days ->
            if (days.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("上传日历", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        val maxCount = days.maxOf { it.count }.coerceAtLeast(1)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            days.forEach { d ->
                                val intensity = (d.count.toFloat() / maxCount).coerceIn(0.1f, 1f)
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                                        )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "最近 ${days.size} 天有上传记录",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V9：整年热力图卡片——在上传日历热力图之后展示全年上传活动（GitHub 贡献图风格）。
        // 调 GET /api/media/media-calendar-year?year=2026，渲染 12 个月 × 天的方块矩阵，
        // 颜色深浅按 count：0=灰、1-3=浅、4+=深。后端只返回非零天，前端按月补 0。
        var yearCalendar by remember { mutableStateOf<List<MediaService.CalendarDayData>?>(null) }
        LaunchedEffect(Unit) { yearCalendar = MediaService.getMediaCalendarYear(2026) }
        yearCalendar?.let { days ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("整年热力图 · 2026", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 按日期建索引，便于按月补 0。
                    val countByDate = remember(days) {
                        days.associate { it.date to it.count }
                    }
                    val yearTotal = days.size
                    val yearItemCount = days.sumOf { it.count }
                    // 12 个月逐月渲染，每月一列（横向 Row），31 天逐行（纵向 Column）。
                    val monthLabels = listOf("1","2","3","4","5","6","7","8","9","10","11","12")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        monthLabels.forEachIndexed { monthIdx, label ->
                            // 月份从 1 开始；后端日期格式 YYYY-MM-DD。
                            val monthNum = monthIdx + 1
                            val daysInMonth = remember(monthNum) {
                                when (monthNum) {
                                    1, 3, 5, 7, 8, 10, 12 -> 31
                                    4, 6, 9, 11 -> 30
                                    2 -> 29 // 2026 非闰年应为 28，取 29 容错；多出的方块 count=0 显示为灰，无害
                                    else -> 30
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    label,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                for (day in 1..daysInMonth) {
                                    val dateStr = "2026-${monthNum.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                                    val count = countByDate[dateStr] ?: 0
                                    // 颜色深浅：0=灰，1-3=浅，4+=深。
                                    val cellColor = when {
                                        count == 0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                        count in 1..3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(cellColor)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "全年 $yearItemCount 项 · 活跃 $yearTotal 天",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // V9：连续上传天数卡片——在上传日历热力图之后展示激励统计。
        // 后端 GET /api/media/upload-streak 返回 null（未铺量/异常）时静默跳过，
        // 与其他统计卡片保持一致的 null 容错策略。
        var uploadStreak by remember { mutableStateOf<MediaService.UploadStreak?>(null) }
        LaunchedEffect(Unit) { uploadStreak = MediaService.getUploadStreak() }
        uploadStreak?.let { s ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("连续上传", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 🔥 当前连续天数：今天已上传时高亮 primary 色激励，否则按普通色显示。
                    // current_streak>0 即视作有效 streak（含今天 +1 或昨天截止的连续段）。
                    val streakActive = s.currentStreak > 0
                    val streakColor = if (streakActive) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "🔥 当前连续 ${s.currentStreak} 天",
                            fontSize = 14.sp,
                            fontWeight = if (streakActive) FontWeight.Bold else FontWeight.Normal,
                            color = streakColor
                        )
                        if (s.todayCount > 0) {
                            Text(
                                "今日 ${s.todayCount} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 🏆 最长连续记录
                    Text(
                        "🏆 最长连续 ${s.longestStreak} 天",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 📅 累计活跃总天数 + 最近上传日期
                    Text(
                        "📅 活跃总天数 ${s.totalActiveDays} 天" +
                            (if (s.lastUploadDate.isNotEmpty()) " · 最近 ${s.lastUploadDate}" else ""),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // V9：本周摘要卡片——在连续上传卡片之后展示本周活动概览。
        // 后端 GET /api/media/weekly-summary 返回 null（未铺量/异常）时静默跳过，
        // 与其他统计卡片保持一致的 null 容错策略。
        var weeklySummary by remember { mutableStateOf<MediaService.WeeklySummary?>(null) }
        LaunchedEffect(Unit) { weeklySummary = MediaService.getWeeklySummary() }
        weeklySummary?.let { w ->
            // 本周无任何上传（count=0 且无新标签/相册）时仍展示一周空状态柱状图，
            // 仅当后端整体返回 null 时才跳过（与上方 null 容错一致）。
            // 英文星期缩写 → 中文星期。
            fun weekdayZh(abbr: String): String = when (abbr) {
                "Mon" -> "周一"; "Tue" -> "周二"; "Wed" -> "周三"; "Thu" -> "周四"
                "Fri" -> "周五"; "Sat" -> "周六"; "Sun" -> "周日"; else -> abbr
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("本周摘要", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 上传 N 项 · X MB
                    val mbText = formatBytesToMB(w.uploadedBytes)
                    Text(
                        "上传 ${w.uploadedCount} 项 · $mbText",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 最活跃: 周X (N 项) —— mostActiveDay.day 为空串表示本周全 0
                    val mostActiveText = if (w.mostActiveDay.day.isNotEmpty() && w.mostActiveDay.count > 0) {
                        "最活跃: ${weekdayZh(w.mostActiveDay.day)} (${w.mostActiveDay.count} 项)"
                    } else {
                        "最活跃: 暂无"
                    }
                    Text(
                        mostActiveText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 新标签 N 个 · 新相册 N 个
                    Text(
                        "新标签 ${w.newTagsCount} 个 · 新相册 ${w.newAlbumsCount} 个",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 7 天迷你柱状图：每天一根竖条，高度按当日 count / 最大 count 比例。
                    // 固定顺序保证周一…周日稳定排列；缺失的 byDay 补 0。
                    val orderedDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    val countMap = w.byDay.associate { it.day to it.count }
                    val maxCount = orderedDays.maxOf { countMap[it] ?: 0 }.coerceAtLeast(1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        orderedDays.forEach { abbr ->
                            val count = countMap[abbr] ?: 0
                            // 柱高 6…56dp，count=0 时给最小 6dp 占位以便看到“空柱”。
                            val barHeight = (6 + (count.toFloat() / maxCount) * 50).toInt().dp
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    if (count > 0) count.toString() else "",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (count > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    weekdayZh(abbr).take(1),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V8：拍摄日历（按拍摄日期分组的媒体统计，调 timeline-calendar）
        var timelineCalendar by remember { mutableStateOf<List<MediaService.TimelineCalendarDay>?>(null) }
        LaunchedEffect(Unit) { timelineCalendar = MediaService.getTimelineCalendar() }
        timelineCalendar?.let { days ->
            if (days.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("拍摄日历", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            days.take(30).forEach { d ->
                                // 按 type 着色：图片蓝 / 视频红 / Live 绿
                                val chipColor = when (d.type.lowercase()) {
                                    "video" -> Color(0xFFE53935)
                                    "live", "live_photo", "livephoto" -> Color(0xFF43A047)
                                    else -> Color(0xFF1E88E5)
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(chipColor.copy(alpha = 0.15f))
                                        .border(1.dp, chipColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // 日期取 MM/dd：后端 date 形如 "2026-07-30"
                                    val mmdd = d.date.takeLast(5)
                                    Text(mmdd, fontSize = 11.sp, color = chipColor, fontWeight = FontWeight.Medium)
                                    Text("×${d.count}", fontSize = 11.sp, color = chipColor.copy(alpha = 0.8f))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "最近 ${days.size} 天有拍摄记录",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V8：拍摄时段（按拍摄时段统计媒体数量，调 time-distribution）
        var timeDist by remember { mutableStateOf<Map<String, Int>?>(null) }
        LaunchedEffect(Unit) { timeDist = MediaService.getTimeDistribution() }
        timeDist?.let { dist ->
            if (dist.values.sum() > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("拍摄时段", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 固定时段顺序 + emoji，后端只返回其中部分键也能稳定渲染；
                        // 未返回的时段跳过，不强行展示 0 项避免噪音。
                        val timeSlots = listOf(
                            "早晨" to "🌅",
                            "上午" to "☀️",
                            "下午" to "🌇",
                            "晚上" to "🌙",
                            "深夜" to "🌌"
                        )
                        val total = dist.values.sum().coerceAtLeast(1)
                        timeSlots.forEach { (name, emoji) ->
                            val count = dist[name] ?: return@forEach
                            if (count <= 0) return@forEach
                            val ratio = (count.toFloat() / total).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("$emoji $name", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp))
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }
                    }
                }
            }
        }

        // V8：拍摄设备分布卡片（调 media-camera-stats，显示设备分布 top5）。
        // 后端按文件名前缀推断设备（Apple/Samsung/Pixel/截图/微信相机 等）并倒序返回；
        // 此处取前 5 行渲染。请求失败或无数据时 getMediaCameraStats 返回 null，静默跳过。
        var cameraStats by remember { mutableStateOf<List<MediaService.CameraStat>?>(null) }
        LaunchedEffect(Unit) { cameraStats = MediaService.getMediaCameraStats() }
        cameraStats?.let { cams ->
            if (cams.isNotEmpty() && cams.sumOf { it.count } > 0) {
                CameraStatsCard(cams)
            }
        }

        // V8：文件名模式分布卡片（调 media-filename-pattern，显示前缀分布 top5 + 示例文件名）。
        // 后端按文件名前缀分组（取首个分隔符 _ - 空格 . 之前部分）统计 count/percentage/example，
        // 按 count 倒序返回；此处取前 5 行渲染。请求失败或无数据时返回 null，静默跳过。
        var filenamePatterns by remember { mutableStateOf<List<MediaService.FilenamePattern>?>(null) }
        LaunchedEffect(Unit) { filenamePatterns = MediaService.getMediaFilenamePattern() }
        filenamePatterns?.let { patterns ->
            if (patterns.isNotEmpty() && patterns.sumOf { it.count } > 0) {
                FilenamePatternCard(patterns)
            }
        }

        // V9：年代分布卡片（调 media-decade-distribution，按上传年份按年代分桶统计）。
        // 后端按 created_at 的 UTC 年份将未软删媒体分为 2020s/2010s/2000s/更早 四档，
        // 每档返回 count/bytes/percentage，新→旧顺序固定。全部展示（不截断 top-N）。
        // 请求失败或无数据时 getMediaDecadeDistribution 返回 null，静默跳过。
        var decadeStats by remember { mutableStateOf<List<MediaService.DecadeStat>?>(null) }
        LaunchedEffect(Unit) { decadeStats = MediaService.getMediaDecadeDistribution() }
        decadeStats?.let { decades ->
            if (decades.isNotEmpty() && decades.sumOf { it.count } > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("年代分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        decades.forEach { stat ->
                            // 📅 年代 (N 项 · X MB · X%) —— 全部年代显示，按后端固定顺序（新→旧）。
                            if (stat.count > 0) {
                                Text(
                                    "📅 ${stat.decade} (${stat.count} 项 · ${formatBytesToMB(stat.bytes)} · ${formatPercent(stat.percentage)})",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V9：星期分布卡片（调 media-weekday-analysis，按上传时间星期几分布统计）。
        // 后端按 created_at 的 UTC 星期几分 7 档（周日→周六顺序返回），每档 count/percentage，
        // 另返回 most_active {weekday,count}（total=0 时为 null）与 total。
        // 前端重排为"周一→周日"展示，最活跃日高亮 primary 色加粗。
        // total=0 或请求失败时 getMediaWeekdayAnalysis 返回 null，静默跳过。
        var weekdayAnalysis by remember { mutableStateOf<MediaService.WeekdayAnalysis?>(null) }
        LaunchedEffect(Unit) { weekdayAnalysis = MediaService.getMediaWeekdayAnalysis() }
        weekdayAnalysis?.let { wa ->
            if (wa.total > 0) {
                // 后端按"周日→周六"返回，前端重排为"周一→周日"（周一=索引1..周六=6, 周日=0 放末尾）。
                val mondayFirst = wa.weekdays.sortedBy { wd ->
                    when (wd.weekday) {
                        "周一" -> 1; "周二" -> 2; "周三" -> 3; "周四" -> 4
                        "周五" -> 5; "周六" -> 6; "周日" -> 7
                        else -> 99
                    }
                }
                val maxCount = mondayFirst.maxOf { it.count }.coerceAtLeast(1)
                val mostActiveWeekday = wa.mostActive?.weekday
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("星期分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 最活跃日摘要
                        wa.mostActive?.let { ma ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("🔥 最活跃", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${ma.weekday}（${ma.count} 项）",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // 7 行：周一/.../周日 各 N 项 · X%，最活跃日高亮
                        mondayFirst.forEach { wd ->
                            val isMostActive = wd.weekday == mostActiveWeekday && wd.count > 0
                            val barColor = if (isMostActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            val ratio = (wd.count.toFloat() / maxCount).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    wd.weekday,
                                    fontSize = 12.sp,
                                    fontWeight = if (isMostActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isMostActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(36.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    "${wd.count} 项 · ${formatPercent(wd.percentage)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(80.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "基于 ${wa.total} 项记录（按上传时间）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V9：季节分布卡片（调 media-season-analysis，按上传时间月份分四季统计）。
        // 后端按 created_at 的 UTC 月份分到春(3-5)/夏(6-8)/秋(9-11)/冬(12-2)，
        // 每季 count/bytes/percentage，另返回 most_active_season（total=0 时为 null）与 total。
        // 前端按固定春→夏→秋→冬顺序展示，最活跃季高亮 primary 色加粗。
        // total=0 或请求失败时 getMediaSeasonAnalysis 返回 null，静默跳过。
        var seasonAnalysis by remember { mutableStateOf<MediaService.SeasonAnalysis?>(null) }
        LaunchedEffect(Unit) { seasonAnalysis = MediaService.getMediaSeasonAnalysis() }
        seasonAnalysis?.let { sa ->
            if (sa.total > 0) {
                // 后端已按春→夏→秋→冬固定顺序返回，无需重排；防御性兜底。
                val ordered = sa.seasons.sortedBy { s ->
                    when (s.season) { "春" -> 0; "夏" -> 1; "秋" -> 2; "冬" -> 3; else -> 99 }
                }
                val maxCount = ordered.maxOf { it.count }.coerceAtLeast(1)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("季节分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 最活跃季摘要
                        sa.mostActiveSeason?.let { mas ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("🔥 最活跃", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$mas（${ordered.find { it.season == mas }?.count ?: 0} 项）",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // 4 行：🌸春/☀️夏/🍂秋/❄️冬 各 N 项 · X%，最活跃季高亮
                        ordered.forEach { s ->
                            val isMostActive = s.season == sa.mostActiveSeason && s.count > 0
                            val barColor = if (isMostActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            val ratio = (s.count.toFloat() / maxCount).coerceIn(0f, 1f)
                            val emoji = when (s.season) {
                                "春" -> "🌸"; "夏" -> "☀️"; "秋" -> "🍂"; "冬" -> "❄️"; else -> "📅"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "$emoji${s.season}",
                                    fontSize = 12.sp,
                                    fontWeight = if (isMostActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isMostActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(48.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    "${s.count} 项 · ${formatPercent(s.percentage)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(80.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "基于 ${sa.total} 项记录（按上传时间）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 时间线大事件卡片（调 media-timeline-events，显示媒体库里程碑节点）。
        // 后端按 created_at 升序扫描挑出 first_upload/busiest_day/longest_gap/
        // milestone_100/milestone_500 五类节点，每条 {type, date, detail}。
        // 前端按 emoji + 类型 + 日期 + 详情逐行展示，请求失败或空列表静默跳过。
        var timelineEvents by remember { mutableStateOf<List<MediaService.TimelineEvent>?>(null) }
        LaunchedEffect(Unit) { timelineEvents = MediaService.getMediaTimelineEvents() }
        timelineEvents?.let { events ->
            if (events.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("时间线大事件", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        events.forEach { ev ->
                            // 类型 → emoji 映射（与任务约定一致），未知类型回退 📌。
                            val emoji = when (ev.type) {
                                "first_upload" -> "📤"
                                "busiest_day" -> "🔥"
                                "longest_gap" -> "⏰"
                                "milestone_100" -> "💯"
                                "milestone_500" -> "🏆"
                                else -> "📌"
                            }
                            // type → 中文标签映射，未知类型原样展示，不崩溃。
                            val typeLabel = when (ev.type) {
                                "first_upload" -> "首次上传"
                                "busiest_day" -> "最忙一天"
                                "longest_gap" -> "最长间隔"
                                "milestone_100" -> "第 100 个"
                                "milestone_500" -> "第 500 个"
                                else -> ev.type
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(emoji, fontSize = 14.sp)
                                Text(typeLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(64.dp))
                                Text(ev.date, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.weight(1f))
                                Text(ev.detail, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "共 ${events.size} 个里程碑",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V21：媒体大小百分位卡片（调 media-size-percentile，显示 P10-P90 分布）。
        // 后端 nearest-rank 法对未软删媒体 Size 升序计算 P10/P25/P50(中位数)/P75/P90，
        // 另返回 total/min_size/max_size/mean_size。total<2 时各百分位回退 0。
        // 前端展示 5 行百分位 + 底部最小/平均/最大 MB 摘要；请求失败或 total=0 静默跳过。
        var sizePercentile by remember { mutableStateOf<MediaService.SizePercentile?>(null) }
        LaunchedEffect(Unit) { sizePercentile = MediaService.getMediaSizePercentile() }
        sizePercentile?.let { sp ->
            if (sp.total > 0) {
                // bytes → MB 可读串：<1MB 用 KB，否则 MB 一位小数。
                // commonMain 无 String.format，沿用 toString().take 截断一位小数（与媒体年龄卡片同款）。
                fun fmtBytes(bytes: Long): String {
                    if (bytes <= 0L) return "0"
                    if (bytes < 1024L * 1024L) {
                        val kb = (bytes.toDouble() / 1024.0).toString()
                        return "${kb.take(kb.indexOf('.') + 2)} KB"
                    }
                    val mb = (bytes.toDouble() / (1024.0 * 1024.0)).toString()
                    return "${mb.take(mb.indexOf('.') + 2)} MB"
                }
                // 5 档固定顺序：P10/P25/P50(中位数)/P75/P90。后端 key 与此对齐；
                // 缺失 key 回退 0L（百分位为 0 时显示 0，仍渲染行，保持卡片稳定）。
                val rows = listOf(
                    "p10" to "P10",
                    "p25" to "P25",
                    "p50" to "P50 · 中位数",
                    "p75" to "P75",
                    "p90" to "P90"
                )
                // 进度条按各百分位相对 P90 归一化（maxSize 兜底避免除零）。
                val refMax = (sp.percentiles["p90"] ?: 0L).coerceAtLeast(1L).toFloat()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("大小分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        rows.forEach { (key, label) ->
                            val value = sp.percentiles[key] ?: 0L
                            // 中位数行高亮 primary，其余 onSurfaceVariant。
                            val isMedian = key == "p50"
                            val rowColor = if (isMedian) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            val barColor = if (isMedian) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, fontSize = 12.sp,
                                    fontWeight = if (isMedian) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor)
                                Text(fmtBytes(value), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                            }
                            // 比例条：各百分位相对 P90 的占比，中位数档着色更深。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((value.toFloat() / refMax).coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：最小 · 平均 · 最大（三段式，与视频时长卡片底部摘要同款布局）。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("⬇ 最小 ${fmtBytes(sp.minSize)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Text("Ø 平均 ${fmtBytes(sp.meanSize)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Text("⬆ 最大 ${fmtBytes(sp.maxSize)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        }
                        Text(
                            "基于 ${sp.total} 项（按文件大小 nearest-rank 百分位）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 色温分布卡片（调 media-color-temperature，按拍摄时段推断暖/冷/自然三档分布）。
        // 后端按 taken_at（缺失回退 created_at）的 UTC 小时分桶：18-22h→warm(暖)、
        // 6-10h→cool(冷)、其他→natural(自然)；返回 distribution/total/dominant
        //（total=0 时 dominant 为 null）。请求失败或 total=0 静默跳过，与大小百分位卡片同款。
        var colorTemp by remember { mutableStateOf<MediaService.ColorTemperature?>(null) }
        LaunchedEffect(Unit) { colorTemp = MediaService.getMediaColorTemperature() }
        colorTemp?.let { ct ->
            if (ct.total > 0) {
                // 三档固定顺序 warm→cool→natural；emoji 与标签随任务要求逐字映射。
                val rows = listOf(
                    "warm" to "🔥 暖色调",
                    "cool" to "❄️ 冷色调",
                    "natural" to "🌿 自然光"
                )
                // 进度条按各档计数相对最大档归一化（coerceAtLeast(1) 避免除零）。
                val maxCount = ct.distribution.values.max().coerceAtLeast(1)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("色温分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        rows.forEach { (key, label) ->
                            val count = ct.distribution[key] ?: 0
                            // 主调（dominant）高亮 primary + bold，其余 onSurfaceVariant。
                            val isDominant = ct.dominant != null && key == ct.dominant
                            val rowColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            val barColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            val ratio = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, fontSize = 12.sp,
                                    fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor)
                                Text("$count 个 · ${formatPercent(count.toDouble() / ct.total * 100.0)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            // 比例条：主调档着色更深（与大小百分位卡片同款 Box 比例条）。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：主调高亮 + 总数。
                        val dominantLabel = ct.dominant?.let { d ->
                            rows.firstOrNull { it.first == d }?.second ?: d
                        }
                        if (dominantLabel != null) {
                            Text(
                                "🏆 主调 $dominantLabel · 共 ${ct.total} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                "共 ${ct.total} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            "按拍摄时段（taken_at / created_at UTC 小时）推断色温基调",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 上传速度卡片（调 media-upload-velocity?days=7，展示最近 7 天的平均/最高/高峰日）。
        // 后端按 UTC 日期分桶最近 N 天上传量，返回 avg_per_day / max_day / peak_days 等。
        // 请求失败或 total=0（窗口内无上传）时静默跳过，与色温分布等卡片同语义。
        var uploadVelocity by remember { mutableStateOf<MediaService.UploadVelocity?>(null) }
        LaunchedEffect(Unit) { uploadVelocity = MediaService.getMediaUploadVelocity(days = 7) }
        uploadVelocity?.let { v ->
            if (v.total > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "上传速度",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 平均：X 项/天（一位小数，commonMain 无 String.format，用 take 截断）。
                        val avgStr = v.avgPerDay.toString().let { it.take(it.indexOf('.') + 2) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("平均", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "$avgStr 项/天",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 最高：date (N 项)；max_day 全 0 时（total>0 不会发生，但防御）跳过。
                        if (v.maxDay.date.isNotEmpty() && v.maxDay.count > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("最高", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${v.maxDay.date} (${v.maxDay.count} 项)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // 高峰日列表（每行: date · N 项）；空列表不渲染该区块。
                        if (v.peakDays.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "高峰日（超过均值 1.5 倍）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            v.peakDays.take(5).forEach { pd ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        pd.date,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${pd.count} 项",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "基于 ${v.days} 天 · 共 ${v.total} 项（${v.windowStart} ~ ${v.windowEnd}）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 拍摄地点估算卡片（调 media-gps-estimate，按文件名模式+拍摄时间推断室内/户外/办公/未知分布）。
        // 后端对未软删媒体启发式推断（Media 无 GPS 字段，故为估算非精确坐标）：
        //   截屏/微信相机 → indoor；IMG_ 周末 → outdoor；IMG_ 工作日 9-18h → office；
        //   IMG_ 工作日非核心时段 → indoor；其他文件名 → unknown。
        // 时间口径：优先 taken_at，缺失回退 created_at，统一 UTC。
        // 返回 locations{indoor,outdoor,office,unknown}/total/dominant（total=0 时 dominant 为 null）。
        // 请求失败或 total=0 静默跳过，与色温分布等卡片同语义。
        var gpsEstimate by remember { mutableStateOf<MediaService.GpsEstimate?>(null) }
        LaunchedEffect(Unit) { gpsEstimate = MediaService.getMediaGpsEstimate() }
        gpsEstimate?.let { g ->
            if (g.total > 0) {
                // 四档固定顺序 indoor→outdoor→office→unknown；emoji 与标签随任务要求逐字映射。
                val rows = listOf(
                    "indoor" to "🏠 室内",
                    "outdoor" to "🌳 户外",
                    "office" to "💼 办公",
                    "unknown" to "❓ 未知"
                )
                // 比例条按各档计数相对最大档归一化（coerceAtLeast(1) 避免除零）。
                val maxCount = g.locations.values.max().coerceAtLeast(1)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("拍摄地点", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        rows.forEach { (key, label) ->
                            val count = g.locations[key] ?: 0
                            // 主调（dominant）高亮 primary + bold，其余 onSurfaceVariant。
                            val isDominant = g.dominant != null && key == g.dominant
                            val rowColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            val barColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            val ratio = (count.toFloat() / maxCount).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, fontSize = 12.sp,
                                    fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor)
                                Text("$count 个 · ${formatPercent(count.toDouble() / g.total * 100.0)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            // 比例条：主调档着色更深（与色温分布/大小百分位卡片同款 Box 比例条）。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：主调高亮 + 总数。
                        val dominantLabel = g.dominant?.let { d ->
                            rows.firstOrNull { it.first == d }?.second ?: d
                        }
                        if (dominantLabel != null) {
                            Text(
                                "🏆 主调 $dominantLabel · 共 ${g.total} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                "共 ${g.total} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            "按文件名模式 + 拍摄时间（taken_at / created_at UTC）启发式估算",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 照片心情卡片（调 media-mood-analysis，按拍摄时段推断清晨/午后/黄昏/夜晚四类心情分布）。
        // 后端按 taken_at（缺失回退 created_at）UTC 小时分桶：6-10h 清晨清新、10-16h 午后活力、
        // 16-19h 黄昏浪漫、19-6h 夜晚神秘；返回 moods[{mood,count,percentage,emoji}]/total/dominant_mood。
        // 后端按 count 降序输出，故列表首位即主调（dominant_mood），其余顺位。
        // 请求失败或 total=0 静默跳过，与色温分布/拍摄地点等卡片同语义。
        var moodAnalysis by remember { mutableStateOf<List<MediaService.MoodItem>?>(null) }
        LaunchedEffect(Unit) { moodAnalysis = MediaService.getMediaMoodAnalysis() }
        moodAnalysis?.let { moods ->
            // total>0 才渲染（total=0 时后端仍返回四条 count=0，无展示意义）。
            val totalMood = moods.sumOf { it.count }
            if (totalMood > 0) {
                // 主调 = count 最大者（后端已降序，首位即主调；此处再取一次最大值以稳健）。
                val dominantMood = moods.maxByOrNull { it.count }?.mood
                // 进度条归一化基准：四档中最大 count（coerceAtLeast(1) 避免除零）。
                val maxMoodCount = moods.maxOf { it.count }.coerceAtLeast(1)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("照片心情", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        moods.forEach { m ->
                            val isDominant = m.mood == dominantMood
                            val rowColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            val barColor = if (isDominant) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            val ratio = (m.count.toFloat() / maxMoodCount).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${m.emoji} ${m.mood}", fontSize = 12.sp,
                                    fontWeight = if (isDominant) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor)
                                Text("${m.count} 项 · ${formatPercent(m.percentage)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            // 比例条：主调档着色更深（与拍摄地点/色温分布卡片同款 Box 比例条）。
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：主调高亮 + 总数。
                        if (dominantMood != null) {
                            val dominantEmoji = moods.firstOrNull { it.mood == dominantMood }?.emoji ?: ""
                            Text(
                                "🏆 主调 ${dominantEmoji} ${dominantMood} · 共 ${totalMood} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                "共 ${totalMood} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        Text(
                            "按拍摄时段（taken_at / created_at UTC 小时）推断心情基调",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // 内容多样性卡片（调 media-content-diversity，Shannon 熵四维度归一化评分）。
        // 后端对全部未软删媒体按 type / mime / hour / tag 四维度计算 Shannon 熵并归一化
        // (entropy / ln(k))，综合多样性 = 四维度均值；等级 A(>=0.8)/B(>=0.6)/C(>=0.4)/D(<0.4)。
        // 返回 diversity_score(0-1) / grade / breakdown{type,mime,hour,tag:{entropy,categories,distribution}} / total。
        // 请求失败或 total=0 静默跳过，与照片心情/色温分布等卡片同语义。
        var contentDiversity by remember { mutableStateOf<MediaService.ContentDiversity?>(null) }
        LaunchedEffect(Unit) { contentDiversity = MediaService.getMediaContentDiversity() }
        contentDiversity?.let { cd ->
            if (cd.total > 0) {
                ContentDiversityCard(cd)
            }
        }

        // 月度亮点卡片（调 media-monthly-highlights，显示每月首上传/最大文件/末上传）。
        // 后端返回最近 N 个月（默认 6）每月 first/largest/last 三条媒体引用；某月无媒体
        // 时对应字段为 null。此处取前 3 个月渲染，每行：📤 首个 / 📦 最大(X MB) / 📌 末个。
        // 任一月三字段全 null 时跳过该行；列表空或请求失败时静默跳过整张卡（与其他统计卡一致）。
        var monthlyHighlights by remember { mutableStateOf<List<MediaService.MonthlyHighlight>?>(null) }
        LaunchedEffect(Unit) { monthlyHighlights = MediaService.getMediaMonthlyHighlights(months = 6) }
        monthlyHighlights?.let { highlights ->
            // 取前 3 个月，并过滤掉三字段全空的月份（无任何媒体）。
            val rows = highlights.take(3).filter { it.first != null || it.largest != null || it.last != null }
            if (rows.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "月度亮点",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        rows.forEach { hl ->
                            // 月份标题行
                            Text(
                                hl.month,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            // 📤 首个
                            hl.first?.let { f ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("📤", fontSize = 12.sp)
                                    Text(
                                        "首个: ${f.filename}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // 📦 最大（附文件大小 MB）
                            hl.largest?.let { lg ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("📦", fontSize = 12.sp)
                                    Text(
                                        "最大: ${lg.filename} (${lg.sizeMB} MB)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // 📌 末个
                            hl.last?.let { l ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("📌", fontSize = 12.sp)
                                    Text(
                                        "末个: ${l.filename}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (rows.indexOf(hl) < rows.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 上传排行卡片（调 media-upload-ranking，显示 top 5 最忙上传日 + 奖牌 emoji）。
        // 后端按 created_at 的 UTC 日期分组、count 倒序取 top N。此处取前 5 渲染：
        // 🥇🥈🥉 前三名，4./5. 数字序号；每行 date · N 项 · X MB。列表空或请求失败时静默跳过
        // 整张卡（与月度亮点等统计卡同款降级策略）。
        var uploadRanking by remember { mutableStateOf<MediaService.UploadRanking?>(null) }
        LaunchedEffect(Unit) { uploadRanking = MediaService.getMediaUploadRanking(limit = 10) }
        uploadRanking?.let { ur ->
            val rows = ur.ranking.take(5)
            if (rows.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "上传排行",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        rows.forEach { item ->
                            // 奖牌 emoji：rank 1/2/3 用 🥇🥈🥉，其余用"N."序号。
                            val medal = when (item.rank) {
                                1 -> "🥇"
                                2 -> "🥈"
                                3 -> "🥉"
                                else -> "${item.rank}."
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(medal, fontSize = 13.sp)
                                Text(
                                    "${item.date} · ${item.count} 项 · ${item.bytesMB} MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        // 总天数 footnote（冠军日已在 rank 1 展示，此处补总活跃天数）。
                        if (ur.totalDays > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "共 ${ur.totalDays} 天有上传记录",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        // 年度对比卡片（调 media-yearly-comparison，今年 vs 去年同期 + 增长率）。
        // 后端对 created_at 的 UTC 年份分两组聚合：今年 count/bytes/by_type +
        // 去年同口径，增长率 = (今年-去年)/去年*100（去年同期为 0 返回 null → NaN，
        // 不显示箭头）。今年/去年均为 0 时视为"无数据"跳过整张卡。
        // 增长率展示沿用 GrowthReportRow / 媒体量报告的 ↑/↓ + 绿红口径。
        var yearlyComparison by remember { mutableStateOf<MediaService.YearlyComparison?>(null) }
        LaunchedEffect(Unit) { yearlyComparison = MediaService.getMediaYearlyComparison() }
        yearlyComparison?.let { yc ->
            // 今年+去年都为 0 → 无年度数据，静默跳过卡片。
            if (yc.thisYear.count > 0 || yc.lastYear.count > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "年度对比",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 今年：N 项 · X MB
                        Text(
                            "今年 ${yc.thisYear.count} 项 · ${formatBytesToMB(yc.thisYear.bytes)}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 去年：N 项 · X MB
                        Text(
                            "去年 ${yc.lastYear.count} 项 · ${formatBytesToMB(yc.lastYear.bytes)}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 增长：↑/↓ X% 项 · ↑/↓ X% MB（去年同期为 0 → NaN → 该维度不显箭头，显"—"占位）
                        if (!yc.growth.countPct.isNaN() || !yc.growth.bytesPct.isNaN()) {
                            Text(
                                buildAnnotatedString {
                                    append("增长  ")
                                    // 项数增长
                                    if (yc.growth.countPct.isNaN()) {
                                        withStyle(SpanStyle(color = Color(0xFF9E9E9E))) { append("— 项") }
                                    } else {
                                        val isUp = yc.growth.countPct >= 0
                                        val raw = yc.growth.countPct.toString()
                                        val pct1 = if (raw.indexOf('.') >= 0) raw.take(raw.indexOf('.') + 2) else raw
                                        withStyle(SpanStyle(color = if (isUp) Color(0xFF2E7D32) else Color(0xFFC62828))) {
                                            append("${if (isUp) "↑+" else "↓"}${pct1}% 项")
                                        }
                                    }
                                    append("  ·  ")
                                    // 字节量增长
                                    if (yc.growth.bytesPct.isNaN()) {
                                        withStyle(SpanStyle(color = Color(0xFF9E9E9E))) { append("— MB") }
                                    } else {
                                        val isUp = yc.growth.bytesPct >= 0
                                        val raw = yc.growth.bytesPct.toString()
                                        val pct1 = if (raw.indexOf('.') >= 0) raw.take(raw.indexOf('.') + 2) else raw
                                        withStyle(SpanStyle(color = if (isUp) Color(0xFF2E7D32) else Color(0xFFC62828))) {
                                            append("${if (isUp) "↑+" else "↓"}${pct1}% MB")
                                        }
                                    }
                                },
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // V9：视频时长分析卡片（调 media-duration-analysis，按视频时长分 5 档统计）。
        // 后端对 VIDEO 类型媒体逐条 ffprobe 取时长，归入 <30s / 30s-2min / 2-5min /
        // 5-15min / >15min 五档，每档 count/percentage，另返回 total_videos /
        // avg_duration / max_duration（秒）。无视频或请求失败时返回 null，静默跳过。
        var durationAnalysis by remember { mutableStateOf<MediaService.DurationAnalysis?>(null) }
        LaunchedEffect(Unit) { durationAnalysis = MediaService.getMediaDurationAnalysis() }
        durationAnalysis?.let { da ->
            if (da.totalVideos > 0) {
                // 时长档位最大计数，用于进度条归一化（coerceAtLeast(1) 避免除零）。
                val maxTierCount = da.tiers.maxOf { it.count }.coerceAtLeast(1)
                // 秒→可读单位：<60s 秒、<3600s 分钟、否则小时。commonMain 无 String.format，
                // 沿用 toString().take 截断一位小数（与上传延迟卡片同款）。
                fun fmtDur(sec: Double): String = when {
                    sec < 60.0 -> "${sec.toInt()} 秒"
                    sec < 3600.0 -> {
                        val s = (sec / 60.0).toString()
                        "${s.take(s.indexOf('.') + 2)} 分钟"
                    }
                    else -> {
                        val s = (sec / 3600.0).toString()
                        "${s.take(s.indexOf('.') + 2)} 小时"
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("视频时长", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 5 行：每档 N 个 · X%，进度条按相对最大档归一化。
                        da.tiers.forEach { t ->
                            val ratio = (t.count.toFloat() / maxTierCount).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    t.tier,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(72.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    "${t.count} 个 · ${formatPercent(t.percentage)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(80.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：平均时长 · 最长时长。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("⏱ 平均 ${fmtDur(da.avgDuration)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Text("📏 最长 ${fmtDur(da.maxDuration)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        }
                        Text(
                            "基于 ${da.totalVideos} 个视频（按 ffprobe 时长）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V9：宽高比分析卡片（调 media-aspect-ratio，按 w/h 分横向/纵向/方形/全景 4 档）。
        // 后端按 width/height 比值归入 panorama(w/h>2)/landscape(>1.2)/portrait(h/w>1.2)/
        // square(其余) 四档，每档 count/percentage，另返回 total 与 most_common（最常见档 type）。
        // 无尺寸媒体不计入 total；total=0 或请求失败时返回 null，静默跳过。
        var aspectRatioAnalysis by remember { mutableStateOf<MediaService.AspectRatioAnalysis?>(null) }
        LaunchedEffect(Unit) { aspectRatioAnalysis = MediaService.getMediaAspectRatio() }
        aspectRatioAnalysis?.let { ar ->
            if (ar.total > 0 && ar.ratios.isNotEmpty()) {
                // 最常见档高亮（most_common 可空，为空时不高亮任何行）。
                val mostCommon = ar.mostCommon
                // 档位 → emoji + 中文标签映射（与后端 4 档 type 对齐，emoji 与任务约定一致）。
                fun emojiLabel(type: String): String = when (type) {
                    "landscape" -> "📐横向"
                    "portrait" -> "📏纵向"
                    "square" -> "⬜方形"
                    "panorama" -> "🌅全景"
                    else -> type // 防御：后端将来新增档位时原样展示，不崩溃
                }
                // 最大档计数，用于进度条归一化（coerceAtLeast(1) 避免除零）。
                val maxRatioCount = ar.ratios.maxOf { it.count }.coerceAtLeast(1)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("宽高比", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 4 行：每档 emoji+标签 + 进度条 + count · percentage，最常见档高亮 primary。
                        ar.ratios.forEach { r ->
                            val ratio = (r.count.toFloat() / maxRatioCount).coerceIn(0f, 1f)
                            val isMostCommon = mostCommon != null && r.type == mostCommon
                            val rowColor = if (isMostCommon) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            val barColor = if (isMostCommon) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    emojiLabel(r.type),
                                    fontSize = 12.sp,
                                    fontWeight = if (isMostCommon) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor,
                                    modifier = Modifier.width(72.dp)
                                )
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    "${r.count} 个 · ${formatPercent(r.percentage)}",
                                    fontSize = 11.sp,
                                    fontWeight = if (isMostCommon) FontWeight.Bold else FontWeight.Normal,
                                    color = rowColor.copy(alpha = if (isMostCommon) 1f else 0.7f),
                                    modifier = Modifier.width(80.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        // 底部摘要：最常见档 + 总样本数。
                        val mostCommonLabel = if (mostCommon != null) emojiLabel(mostCommon) else "—"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🏆 最常见 $mostCommonLabel",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                            Text("共 ${ar.total} 项",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        }
                        Text(
                            "基于 ${ar.total} 项有尺寸媒体（按 width/height 比值）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V9：上传时段 24h 柱状图（调 media-by-hour）
        var mediaByHour by remember { mutableStateOf<List<MediaService.HourCount>?>(null) }
        LaunchedEffect(Unit) { mediaByHour = MediaService.getMediaByHour() }
        mediaByHour?.let { hours ->
            if (hours.isNotEmpty() && hours.sumOf { it.count } > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("上传时段", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 最高峰 3 个时段高亮 primary，其余 onSurfaceVariant
                        val peakHourValues = hours.sortedByDescending { it.count }
                            .take(3).filter { it.count > 0 }.map { it.hour }.toSet()
                        val maxCount = hours.maxOf { it.count }.coerceAtLeast(1)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(hours.size) { idx ->
                                val item = hours[idx]
                                val isPeak = item.hour in peakHourValues
                                val barColor = if (isPeak) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                val barHeight = ((item.count.toFloat() / maxCount) * 80f).coerceAtLeast(2f)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        if (item.count > 0) "${item.count}" else "",
                                        fontSize = 9.sp,
                                        color = barColor
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(8.dp)
                                            .height(barHeight.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(barColor)
                                    )
                                    Text(
                                        "${item.hour}",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "0h - 23h（上传时间分布）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V22：上传延迟分析卡片——在上传时段之后，展示拍摄到上传的延迟分布。
        // 调 GET /api/media/media-time-analysis（后端待实现，404/异常时静默跳过）。
        // total=0（无样本）或请求失败时 MediaService 返回 null，不渲染占位卡片。
        var timeAnalysis by remember { mutableStateOf<MediaService.MediaTimeAnalysis?>(null) }
        LaunchedEffect(Unit) { timeAnalysis = MediaService.getMediaTimeAnalysis() }
        timeAnalysis?.let { ta ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("上传延迟", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    // 平均延迟：秒→可读单位。<60s 秒、<3600s 分钟、<86400s 小时、否则天。
                    // commonMain 无 String.format，沿用 toString().take 截断一位小数。
                    val avgSec = ta.avgDelaySeconds
                    val avgStr = when {
                        avgSec < 60.0 -> "${avgSec.toInt()} 秒"
                        avgSec < 3600.0 -> {
                            val s = (avgSec / 60.0).toString()
                            "${s.take(s.indexOf('.') + 2)} 分钟"
                        }
                        avgSec < 86400.0 -> {
                            val s = (avgSec / 3600.0).toString()
                            "${s.take(s.indexOf('.') + 2)} 小时"
                        }
                        else -> {
                            val s = (avgSec / 86400.0).toString()
                            "${s.take(s.indexOf('.') + 2)} 天"
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⏱ 平均延迟", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(avgStr,
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📅 同日上传", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 同日比例按百分比展示一位小数（commonMain 无 String.format，take 截断）。
                        val ratioStr = (ta.sameDayRatio * 100.0).toString()
                        Text("${ta.sameDayCount} 项（${ratioStr.take(ratioStr.indexOf('.') + 2)}%）",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("延迟分布", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(6.dp))
                    // 四档延迟分布：固定顺序 + 比例条，每档展示项数与占比条（按最大档归一）。
                    val bucketList = listOf(
                        "<1h" to ta.buckets.under1h,
                        "1-24h" to ta.buckets.h1To24,
                        "1-7d" to ta.buckets.d1To7,
                        ">7d" to ta.buckets.over7d
                    )
                    val maxBucket = bucketList.maxOf { it.second }.coerceAtLeast(1)
                    bucketList.forEach { (label, count) ->
                        val ratio = (count.toFloat() / maxBucket).coerceIn(0f, 1f)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp))
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface
                            )
                            Text("$count", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "基于 ${ta.total} 项记录（拍摄 → 上传）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // V20：上传习惯卡片——在上传时段之后，展示最常上传的类型/大小范围/时段/星期。
        // 调 GET /api/media/upload-pattern-analysis，total=0 或异常时静默跳过（不渲染占位）。
        var uploadPattern by remember { mutableStateOf<MediaService.UploadPattern?>(null) }
        LaunchedEffect(Unit) { uploadPattern = MediaService.getUploadPatternAnalysis() }
        uploadPattern?.let { p ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("上传习惯", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📷 最常上传", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.dominantType.label}（${p.dominantType.count} 次）",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📦 大小范围", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.dominantSizeRange.label}（${p.dominantSizeRange.count} 次）",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⏰ 上传时段", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.dominantTimePeriod.label}（${p.dominantTimePeriod.count} 次）",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📅 最多在", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.dominantWeekday.label}（${p.dominantWeekday.count} 次）",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "基于 ${p.total} 项上传记录",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // V21：媒体年龄分布卡片——在上传习惯之后，展示各年龄档的项数/字节/占比。
        // 调 GET /api/media/media-age-distribution，total=0 或异常时静默跳过（不渲染占位）。
        var ageRanges by remember { mutableStateOf<List<MediaService.AgeRange>?>(null) }
        LaunchedEffect(Unit) { ageRanges = MediaService.getMediaAgeDistribution() }
        ageRanges?.let { ranges ->
            val totalItems = ranges.sumOf { it.count }
            if (totalItems > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("媒体年龄", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        val maxCount = ranges.maxOf { it.count }.coerceAtLeast(1)
                        ranges.forEach { ar ->
                            val pct = (ar.count.toFloat() / maxCount).coerceIn(0f, 1f)
                            val mbStr = if (ar.bytes > 0L) {
                                (ar.bytes.toDouble() / (1024.0 * 1024.0)).toString()
                                    .let { it.take(it.indexOf('.') + 2) }
                            } else "0"
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(ar.range, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${ar.count} 项 · ${mbStr} MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            // 比例条
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .padding(vertical = 0.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(pct)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 $totalItems 项（按上传时间到现在的年龄分档）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V21：数据温度卡片——在媒体年龄之后，展示热/温/冷数据分布。
        // 调 GET /api/media/media-archive-status，total=0 或异常时静默跳过（与媒体年龄同款）。
        // 三档与后端口径一致：hot 30 天内 / warm 30-180 天 / cold 180 天+。
        var archiveStatus by remember { mutableStateOf<MediaService.ArchiveStatus?>(null) }
        LaunchedEffect(Unit) { archiveStatus = MediaService.getMediaArchiveStatus() }
        archiveStatus?.let { st ->
            if (st.total > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("数据温度", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 比例条按 count 占 total 的比例绘制；用 max(count,1) 避免除零。
                        val totalCount = st.total.coerceAtLeast(1)
                        // 三档三元组：emoji·标签·颜色·TierInfo，顺序固定（热→温→冷）。
                        val tiers = listOf(
                            Triple("🔥 热数据 (30天内)", Color(0xFFEF5350), st.hot),
                            Triple("🌡️ 温数据 (30-180天)", Color(0xFFFFA726), st.warm),
                            Triple("❄️ 冷数据 (180天+)", Color(0xFF42A5F5), st.cold)
                        )
                        tiers.forEach { (label, color, tier) ->
                            val ratio = (tier.count.toFloat() / totalCount).coerceIn(0f, 1f)
                            val mbStr = if (tier.bytes > 0L) {
                                (tier.bytes.toDouble() / (1024.0 * 1024.0)).toString()
                                    .let { it.take(it.indexOf('.') + 2) }
                            } else "0"
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${tier.count} 项 · ${mbStr} MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            // 比例条（按各档占总数的百分比着色，与媒体年龄卡片样式一致）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(color.copy(alpha = 0.85f))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 ${st.total} 项（按上传时间到现在的温度分类）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V21：标签详情统计卡片——在数据温度之后，展示每个标签的媒体类型分布与占用大小。
        // 调 GET /api/media/tag-stat-detailed（前端合并 tag/stat-by-type 取类型分布）。
        // 后端返回 null（未铺量/异常）或空列表时静默跳过，与其他统计卡片一致。
        // 仅展示 total 最高的前 5 个标签，避免长列表挤占仪表盘。
        var tagDetailedStats by remember { mutableStateOf<List<MediaService.TagDetailedStat>?>(null) }
        LaunchedEffect(Unit) { tagDetailedStats = MediaService.getTagStatDetailed() }
        tagDetailedStats?.let { stats ->
            if (stats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "标签详情统计",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        stats.take(5).forEach { ts ->
                            // 组装类型分布文本：仅展示 count>0 的类型，避免 "图片0 视频0" 噪音。
                            // Live 若有则追加展示（Live Photo 较少见，有则点出）。
                            val distParts = mutableListOf<String>()
                            if (ts.imageCount > 0) distParts.add("图片${ts.imageCount}")
                            if (ts.videoCount > 0) distParts.add("视频${ts.videoCount}")
                            if (ts.liveCount > 0) distParts.add("Live${ts.liveCount}")
                            val distText = if (distParts.isEmpty()) "无媒体" else distParts.joinToString(" ")
                            // 大小：totalMB 取一位小数（commonMain 无 String.format，沿用 take 截断）。
                            val mbStr = ts.totalMB.toString()
                            val mbStr1 = mbStr.take(mbStr.indexOf('.') + 2)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "#${ts.tagName}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "${ts.total} 项 · $distText · $mbStr1 MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 ${stats.size} 个标签（按关联媒体数排序，展示前 5）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V9：拍摄热力图（GitHub 风格贡献图，调 media-heatmap）
        var heatmapDays by remember { mutableStateOf<List<MediaService.HeatmapDay>?>(null) }
        LaunchedEffect(Unit) { heatmapDays = MediaService.getMediaHeatmap() }
        heatmapDays?.let { days ->
            if (days.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("拍摄热力图", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // GitHub 风格热力图：按 ISO 周（周一起始）排成列，每列 7 行（周一~周日）。
                        // date 形如 "2026-07-31"，用 Howard Hinnant days_from_civil / civil_from_days
                        // 纯整数算术算 epoch day——不依赖 java.time / kotlinx-datetime（commonMain 不可用）。
                        // 注意：days_from_civil 在 Kotlin/Native commonMain 下若用 Int 算术会把 * / 推断为
                        // BigInteger 操作，故全程用 Long 做运算避免类型推断陷阱。
                        val dateCountMap = days.associate { it.date to it.count }
                        val sortedDates = days.map { it.date }.sorted()
                        val firstDate = sortedDates.first()
                        // days_from_civil(y, m, d) -> epoch day（Long）
                        fun civilToEpoch(y: Int, m: Int, d: Int): Long {
                            val yy: Long = (if (m <= 2) y - 1 else y).toLong()
                            val mm: Long = (if (m <= 2) m + 12 else m).toLong()
                            val dd: Long = d.toLong()
                            return 365L * yy + yy / 4L - yy / 100L + yy / 400L + (153L * (mm - 3L) + 2L) / 5L + dd - 719468L
                        }
                        // civil_from_days(epoch day) -> Triple(year, month, day)
                        fun epochToCivil(z: Long): Triple<Int, Int, Int> {
                            val zz: Long = z + 719468L
                            val era: Long = (if (zz >= 0L) zz else zz - 146096L) / 146097L
                            val doe: Long = zz - era * 146097L
                            val yoe: Long = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
                            val y: Long = yoe + era * 400L
                            val doy: Long = doe - (365L * yoe + yoe / 4L - yoe / 100L)
                            val mp: Long = (5L * doy + 2L) / 153L
                            val d: Int = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
                            val m: Int = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
                            val yr: Int = (if (m <= 2) y + 1L else y).toInt()
                            return Triple(yr, m, d)
                        }
                        fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
                        fun dateKey(epochDay: Long): String {
                            val (yr, m, d) = epochToCivil(epochDay)
                            return "$yr-${pad2(m)}-${pad2(d)}"
                        }
                        val parts0 = firstDate.split("-")
                        val epochFirst = civilToEpoch(parts0[0].toInt(), parts0[1].toInt(), parts0[2].toInt())
                        // 1970-01-01 是周四；epochDay=3 是第一个周一（1970-01-05）。
                        // firstDate 所在周的周一 = epochFirst - ((epochFirst - 3) % 7 + 7) % 7
                        val weekStartDay = epochFirst - (((epochFirst - 3L) % 7L + 7L) % 7L)
                        val parts1 = sortedDates.last().split("-")
                        val epochLast = civilToEpoch(parts1[0].toInt(), parts1[1].toInt(), parts1[2].toInt())
                        val totalWeeks = ((epochLast - weekStartDay) / 7L + 1L).toInt().coerceAtLeast(1)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(totalWeeks) { weekIdx ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (dow in 0 until 7) {
                                        val cellDay = weekStartDay + weekIdx * 7 + dow
                                        val key = dateKey(cellDay)
                                        val count = dateCountMap[key] ?: 0
                                        val cellColor = when {
                                            count == 0 -> MaterialTheme.colorScheme.surface
                                            count <= 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                            count <= 5 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(cellColor)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val daysWithPhotos = days.count { it.count > 0 }
                        Text(
                            "共 $daysWithPhotos 天有照片",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V8：文件类型分布
        var fileTypes by remember { mutableStateOf<List<MediaService.FileTypeStat>?>(null) }
        LaunchedEffect(Unit) { fileTypes = MediaService.getFileTypes() }
        fileTypes?.let { types ->
            if (types.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("文件类型", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        types.take(5).forEach { ft ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ft.mime, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${ft.count} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }

        // V8：MIME 详细统计（调 /api/media/mime-type-stats，比文件类型分布更细：
        // 含 avg_bytes/earliest/latest）。后端未铺量或异常时 getMimeTypeStats 返回 null，
        // 此处静默跳过不渲染占位。仅展示数量最多的 top 5，避免长列表撑满"我的"页。
        var mimeStats by remember { mutableStateOf<List<MediaService.MimeStat>?>(null) }
        LaunchedEffect(Unit) { mimeStats = MediaService.getMimeTypeStats() }
        mimeStats?.let { stats ->
            if (stats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("MIME 统计", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        stats.take(5).forEach { ms ->
                            val avgMb = formatBytesToMB(ms.avgBytes)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    ms.mime,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "${ms.count} 项 · 均 $avgMb",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V8：分辨率分布
        var resolutions by remember { mutableStateOf<Map<String, Int>?>(null) }
        LaunchedEffect(Unit) { resolutions = MediaService.getByResolution() }
        resolutions?.let { res ->
            if (res.values.sum() > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("分辨率分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        res.forEach { (label, count) ->
                            if (count > 0) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$count 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // V21：分辨率分布增强卡片——在基础分辨率分布之后，调
        // GET /api/media/media-resolution-distribution 展示像素总量四档 + 方向 + 极值。
        // total=0 或异常时静默跳过（与基础分辨率分布同款，?.let + total 守卫）。
        var resolutionDist by remember { mutableStateOf<MediaService.ResolutionDist?>(null) }
        LaunchedEffect(Unit) { resolutionDist = MediaService.getResolutionDistribution() }
        resolutionDist?.let { dist ->
            if (dist.total > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("分辨率分布（增强）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        // 四档：低清 / 标清·高清 / 超清 / 4K+（后端按像素总量分档，顺序固定）
                        val maxTierCount = dist.tiers.maxOf { it.count }.coerceAtLeast(1)
                        dist.tiers.forEach { t ->
                            val pct = (t.count.toFloat() / maxTierCount).coerceIn(0f, 1f)
                            val mbStr = if (t.bytes > 0L) {
                                (t.bytes.toDouble() / (1024.0 * 1024.0)).toString()
                                    .let { it.take(it.indexOf('.') + 2) }
                            } else "0"
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(t.tier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${t.count} 项 · ${mbStr} MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            // 比例条（与媒体年龄卡片同款）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(pct)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // 方向统计：横向 / 纵向 / 正方形
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("方向", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "横向 ${dist.orientation.landscape}  ·  纵向 ${dist.orientation.portrait}  ·  方形 ${dist.orientation.square}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        // 极值分辨率（max/min，无有效分辨率媒体时后端返 null → maxRes/minRes 为 null）
                        dist.maxResolution?.let { mx ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("最高分辨率", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${mx.width}×${mx.height}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        dist.minResolution?.let { mn ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("最低分辨率", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${mn.width}×${mn.height}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 ${dist.total} 项（按像素总量分档：低清<307200 · 标清·高清<2073600 · 超清<8294400 · 4K+≥8294400）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // V8：文件大小分布
        var sizeRange by remember { mutableStateOf<MediaService.SizeRangeStat?>(null) }
        LaunchedEffect(Unit) { sizeRange = MediaService.getBySizeRange() }
        sizeRange?.let { sr ->
            if (sr.counts.values.sum() > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("文件大小分布", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        sr.counts.forEach { (label, count) ->
                            if (count > 0) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("$count 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // V7：收藏快速统计（复用 summary.favoriteCount，无需单独请求）
        mediaSummary?.let { summary ->
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
                        "${summary.favoriteCount}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // V9：收藏时间线——最近收藏的 5 项（调 GET /api/media/favorite-timeline）
        var favoriteTimeline by remember { mutableStateOf<List<MediaService.FavoriteTimelineItem>?>(null) }
        LaunchedEffect(Unit) { favoriteTimeline = MediaService.getFavoriteTimeline(5) }
        favoriteTimeline?.let { timeline ->
            if (timeline.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "收藏时间线",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        timeline.take(5).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (item.type.equals("video", ignoreCase = true)) "🎬" else "📷",
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    item.filename.ifEmpty { item.mediaId },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (item.favoritedAt.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        item.favoritedAt.take(10),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
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

        // 活动流卡片（GET /api/media/activity-feed?limit=20）
        // 与上方"最近活动"卡片互补：最近活动只覆盖 upload/share/favorite 三类且仅 5 条，
        // 活动流聚合全库近期操作（含 delete/rename/tag/restore/rotate）为统一时间线，
        // 最多展示 10 条，每条带 action emoji + detail + 相对时间（如"3分钟前"）。
        // 后端尚未上线时 getActivityFeed 返回 null，本卡片整体跳过（null-skip），
        // 不影响其它卡片渲染——与同级 stat 卡片失败语义一致。
        var activityFeed by remember { mutableStateOf<List<MediaService.ActivityFeedItem>?>(null) }
        LaunchedEffect(Unit) { activityFeed = MediaService.getActivityFeed() }
        activityFeed?.let { feed ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "活动流",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (feed.isEmpty()) {
                        Text(
                            "暂无活动",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        feed.take(10).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    when (item.action.lowercase()) {
                                        "upload" -> "📤"
                                        "delete" -> "🗑️"
                                        "share" -> "🔗"
                                        "rename" -> "✏️"
                                        "favorite" -> "⭐"
                                        "tag" -> "🏷️"
                                        "restore" -> "♻️"
                                        "rotate" -> "🔄"
                                        else -> "📋"
                                    },
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    item.detail,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    relativeTime(item.timestamp),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V22：仪表盘概览卡片——在活动流后，2x3 网格合并显示6关键指标。
        // 前端组合已有数据源（getDashboardOverview 并发拉 summary+streak+tagCloud），
        // 不依赖后端 dashboard 端点。六格：📁总媒体 / ⭐收藏 / 📁相册 / 🔗分享 / 🔥Streak / 🏷️标签。
        // 与其它卡片的 null-skip 不同：本卡片即使全 0 也渲染（让用户看到骨架），
        // 数据未到齐时显示占位"—"。
        var dashboardOverview by remember { mutableStateOf<MediaService.DashboardOverview?>(null) }
        LaunchedEffect(Unit) { dashboardOverview = MediaService.getDashboardOverview() }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "仪表盘概览",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 2x3 网格：2列3行，每格 emoji+大数字+小标签。
                // 用两行 FlowRow 不便控制列数，改用 Column 套两行 Row（固定2列）。
                val ov = dashboardOverview
                // 六格数据：emoji / 数值（未到齐显示"—"）/ 标签
                val cells = listOf(
                    Triple("📁", ov?.totalMedia, "总媒体"),
                    Triple("⭐", ov?.favoriteCount, "收藏"),
                    Triple("📂", ov?.albumCount, "相册"),
                    Triple("🔗", ov?.shareCount, "分享"),
                    Triple("🔥", ov?.currentStreak, "Streak"),
                    Triple("🏷️", ov?.tagCount, "标签")
                )
                // 分3行，每行2格
                for (rowIdx in 0 until 3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (colIdx in 0 until 2) {
                            val idx = rowIdx * 2 + colIdx
                            val (emoji, value, label) = cells[idx]
                            DashboardMetricCell(
                                emoji = emoji,
                                value = value?.toString() ?: "—",
                                label = label,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (rowIdx < 2) Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // V9：媒体量报告卡片——在仪表盘概览之后展示全年汇总视角：
        // 总量（项数+MB）/ 本月增量（环比↑↓%）/ 日均上传统量 / 按当前趋势预测的年底总量。
        // 后端 handleMediaVolumeReport 返回 {total_media,total_bytes,this_month,last_month,
        // mom_growth,avg_daily_uploads,projected_year_end}。mom_growth 在上月为 0 时后端返
        // null → parseNullablePercent 映射为 NaN，此处按"无对比数据"不显示箭头。
        var mediaVolumeReport by remember { mutableStateOf<MediaService.MediaVolumeReport?>(null) }
        LaunchedEffect(Unit) { mediaVolumeReport = MediaService.getMediaVolumeReport() }
        mediaVolumeReport?.let { vr ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "媒体量报告",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 总量：N 项 · X MB
                    Text(
                        "总量 ${vr.totalMedia} 项 · ${formatBytesToMB(vr.totalBytes)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 本月：N 项 (环比 ↑/↓ X%) / 无对比数据时不显示括号
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "本月 ${vr.thisMonth} 项",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!vr.momGrowth.isNaN()) {
                            val isUp = vr.momGrowth >= 0
                            val pctStr = vr.momGrowth.toString()
                            val pct1 = pctStr.take(pctStr.indexOf('.') + 2)
                            val arrow = if (isUp) "↑" else "↓"
                            val sign = if (isUp) "+" else ""
                            Text(
                                "  ($arrow$sign$pct1%)",
                                fontSize = 12.sp,
                                color = if (isUp) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 日均：X 项/天（一位小数截断）
                    val avgStr = vr.avgDaily.toString()
                    val avg1 = avgStr.take(avgStr.indexOf('.') + 2)
                    Text(
                        "日均 $avg1 项/天",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 预测年底：~N 项
                    Text(
                        "预测年底 ~${vr.projectedYearEnd} 项",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
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

        // V8：相册概览卡片（GET /api/media/album/all-summary）
        // 在设备列表后、分享列表前展示所有相册摘要：相册名 + N 项 + 封面信息，最多 5 个。
        var albumSummary by remember { mutableStateOf<List<MediaService.AlbumSummaryItem>?>(null) }
        LaunchedEffect(Unit) { albumSummary = MediaService.getAllAlbumsSummary() }
        albumSummary?.let { albums ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "相册概览",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (albums.isEmpty()) {
                        Text(
                            "暂无相册",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        // 按创建时间倒序（新的在前），最多展示 5 个
                        albums
                            .sortedByDescending { it.createdAt }
                            .take(5)
                            .forEach { album ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("🖼️", fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                album.name.ifEmpty { "未命名相册" },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${album.mediaCount} 项" +
                                                    (album.coverMediaId?.let { " · 封面 ${it.take(8)}…" }
                                                        ?: " · 无封面"),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Text(
                                        if (album.createdAt > 0) formatPreviewDate(album.createdAt * 1000) else "",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        if (albums.size > 5) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "共 ${albums.size} 个相册，仅显示前 5 个",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // V9：相册排行卡片（GET /api/media/album/count-ranking）
        // 在相册概览后展示照片最多的 top 5 相册，带奖牌 emoji 与相对最大值的进度条。
        var albumRanking by remember { mutableStateOf<List<MediaService.AlbumRankItem>?>(null) }
        LaunchedEffect(Unit) { albumRanking = MediaService.getAlbumCountRanking() }
        albumRanking?.let { ranking ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "相册排行",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (ranking.isEmpty()) {
                        Text(
                            "暂无相册",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        val top5 = ranking.take(5)
                        val maxCount = top5.maxOf { it.count }.coerceAtLeast(1)
                        top5.forEachIndexed { idx, item ->
                            val medal = when (idx) {
                                0 -> "🥇"
                                1 -> "🥈"
                                2 -> "🥉"
                                else -> "${idx + 1}"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    medal,
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    item.name.ifEmpty { "未命名相册" },
                                    fontSize = 13.sp,
                                    fontWeight = if (idx < 3) FontWeight.Medium else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                LinearProgressIndicator(
                                    progress = { (item.count.toFloat() / maxCount).coerceIn(0f, 1f) },
                                    modifier = Modifier.width(72.dp).height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    "${item.count} 项",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.width(44.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }

        // V23：照片组织建议卡片（GET /api/media/album-organize-suggest）
        // 在相册排行后展示后端按月份/类型分组产出的"可一键创建"相册建议，
        // 每条携带完整 media_ids，点"创建"即调 createAlbum + batchAddMediaToAlbum 落地。
        // 后端未铺量/异常返回 null 时静默跳过（与同级统计卡片同款 null 容错）。
        var organizeSuggestions by remember { mutableStateOf<List<MediaService.AlbumOrganizeSuggestion>?>(null) }
        // 已创建成功的建议名集合——创建后从列表移除该条，避免重复创建。
        var createdSuggestionNames by remember { mutableStateOf<Set<String>>(emptySet()) }
        LaunchedEffect(Unit) { organizeSuggestions = MediaService.getAlbumOrganizeSuggest() }
        organizeSuggestions?.let { allSuggestions ->
            // 过滤掉已创建的，最多展示 3 条
            val visible = allSuggestions.filter { it.name !in createdSuggestionNames }.take(3)
            // type → 短标签 icon 映射，便于用户一眼分辨建议来源。
            fun typeLabel(t: String): String = when (t) {
                "by_month" -> "📅 按月份"
                "by_type" -> "🎞️ 按类型"
                else -> "📁 分组"
            }
            if (visible.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "照片组织建议",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        visible.forEach { suggestion ->
                            // 每条创建按钮的独立 loading 态，避免一个建议创建时禁用全部按钮。
                            var isCreating by remember(suggestion.name) { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("📁", fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "${suggestion.name.ifEmpty { "未命名分组" }}（${suggestion.mediaIds.size} 项）",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // 创建按钮：调 createAlbum + batchAddMediaToAlbum。
                                    // 禁用态：创建中或该条无 media_ids（后端不应给空，防御式禁用）。
                                    TextButton(
                                        enabled = !isCreating && suggestion.mediaIds.isNotEmpty(),
                                        onClick = {
                                            if (isCreating) return@TextButton
                                            isCreating = true
                                            scope.launch {
                                                val album = MediaService.createAlbum(suggestion.name)
                                                if (album != null && album.id.isNotEmpty()) {
                                                    val added = MediaService.batchAddMediaToAlbum(
                                                        album.id, suggestion.mediaIds
                                                    )
                                                    if (added != null) {
                                                        // 标记已创建并从可视列表移除，刷新相册概览/排行。
                                                        createdSuggestionNames =
                                                            createdSuggestionNames + suggestion.name
                                                        onShowSnackbar("已创建相册「${suggestion.name}」，添加 $added 项")
                                                    } else {
                                                        onShowSnackbar("相册已创建，但批量添加失败")
                                                    }
                                                } else {
                                                    onShowSnackbar("创建相册失败")
                                                }
                                                isCreating = false
                                            }
                                        }
                                    ) {
                                        Text(if (isCreating) "创建中…" else "创建")
                                    }
                                }
                                if (suggestion.reason.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        suggestion.reason,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    typeLabel(suggestion.type),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // V19：相册统计卡片（GET /api/media/album/stats-summary）
        // 在相册排行后展示聚合统计：总相册数/平均项数/最多最少相册。
        var albumStats by remember { mutableStateOf<MediaService.AlbumStatsSummary?>(null) }
        LaunchedEffect(Unit) { albumStats = MediaService.getAlbumStatsSummary() }
        albumStats?.let { stats ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "相册统计",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 总相册数 + 平均项数（一位小数，commonMain 无 String.format，沿用 take 截断）
                    val avgStr = stats.avgPerAlbum.toString()
                    Text(
                        "总 ${stats.totalAlbums} 个相册 · 平均 ${avgStr.take(avgStr.indexOf('.') + 2)} 项/相册",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    stats.maxAlbum?.let { max ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📈 最多: ${max.name.ifEmpty { "未命名相册" }} (${max.count} 项)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    stats.minAlbum?.let { min ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📉 最少: ${min.name.ifEmpty { "未命名相册" }} (${min.count} 项)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // V23：相册综合卡片（GET /api/media/album-stats-comprehensive）
        // 在相册统计卡片后，一次请求合并展示 汇总(总相册/总照片/平均) + 分享(已分享/未分享) +
        // 排行 top3（🥇🥈🥉 name (count)）。后端返回 null（未登录/异常）时静默跳过，与其他卡片一致。
        var albumStatsComprehensive by remember { mutableStateOf<MediaService.AlbumStatsComprehensive?>(null) }
        LaunchedEffect(Unit) { albumStatsComprehensive = MediaService.getAlbumStatsComprehensive() }
        albumStatsComprehensive?.let { comp ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "相册综合",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 汇总: N相册 · M照片 · 平均X张（avgPhotos 取一位小数，commonMain 无 String.format，沿用 take 截断）
                    val avgStr = comp.summary.avgPhotos.toString()
                    Text(
                        "汇总: ${comp.summary.totalAlbums}相册 · ${comp.summary.totalPhotos}照片 · " +
                            "平均${avgStr.take(avgStr.indexOf('.') + 2)}张",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 分享: 已分享N · 未分享M
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "分享: 已分享${comp.sharing.shared} · 未分享${comp.sharing.unshared}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 排行 top3: 🥇🥈🥉 name (count)
                    if (comp.ranking.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        val medals = listOf("🥇", "🥈", "🥉")
                        comp.ranking.take(3).forEachIndexed { idx, item ->
                            val medal = medals.getOrElse(idx) { "•" }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(medal, fontSize = 14.sp)
                                Text(
                                    item.name.ifEmpty { "未命名相册" },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    "(${item.count})",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
                            var showDeleteConfirm by remember { mutableStateOf(false) }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (share.hasPassword) {
                                        Text("🔒", fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    // V7：撤销分享按钮
                                    Text(
                                        "撤销",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable { showDeleteConfirm = true }
                                    )
                                }
                            }
                            if (showDeleteConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteConfirm = false },
                                    title = { Text("撤销分享") },
                                    text = { Text("确定撤销此分享链接？撤销后链接将立即失效。") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showDeleteConfirm = false
                                            scope.launch {
                                                if (MediaService.deleteShare(share.token)) {
                                                    shares = MediaService.listShares()
                                                }
                                            }
                                        }) { Text("撤销", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // V8：分享分析卡片（调 /api/media/share-analytics 展示活跃/过期/密码保护统计）
        var shareAnalytics by remember { mutableStateOf<MediaService.ShareAnalytics?>(null) }
        LaunchedEffect(Unit) { shareAnalytics = MediaService.getShareAnalytics() }
        shareAnalytics?.let { sa ->
            if (sa.total > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "分享分析",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "📊 总分享 ${sa.total} 个",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "✅ 活跃 ${sa.active} 个 (${sa.activePercentage}%)",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "⏰ 即将过期 ${sa.expiringSoon} 个",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "🔒 密码保护 ${sa.passwordProtected} 个",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // V?:即将过期分享卡片（调 /api/media/share-expiring 展示即将过期分享列表）
        var shareExpiring by remember { mutableStateOf<List<MediaService.ShareExpiringItem>?>(null) }
        LaunchedEffect(Unit) { shareExpiring = MediaService.getShareExpiring() }
        shareExpiring?.let { expiring ->
            if (expiring.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "即将过期分享",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        expiring.take(5).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔗", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        item.token.take(8),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${item.daysLeft}天后过期",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V9：分享活动趋势卡片（调 /api/media/media-share-activity?days=30 展示
        // 趋势方向 + 总/活跃/过期 + 最近 7 天柱状图）。与上方分享分析/即将过期两卡
        // 同属分享主题分组，故紧随其后。None-skip on fetch failure（getMediaShareActivity
        // 返回 null 时不渲染卡片，不崩溃"我的"Tab）；total_shares==0 也跳过（零分享用户
        // 不需要空趋势图）。注：仅取 activity 序列末尾 7 个槽位作为"最近 7 天"柱状图，
        // 因为后端 activity 已是按日期升序、含零创建日的完整窗口序列。
        var shareActivity by remember { mutableStateOf<MediaService.ShareActivity?>(null) }
        LaunchedEffect(Unit) { shareActivity = MediaService.getMediaShareActivity() }
        shareActivity?.let { act ->
            if (act.totalShares > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 标题行：分享活动趋势 + 趋势箭头（up↑ onPrimary / down↓ error / stable→ neutral）
                        val (trendArrow, trendColor, trendLabel) = when (act.trend) {
                            "up" -> Triple("↑", MaterialTheme.colorScheme.primary, "上升")
                            "down" -> Triple("↓", MaterialTheme.colorScheme.error, "下降")
                            else -> Triple("→", MaterialTheme.colorScheme.onSurfaceVariant, "平稳")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "分享活动趋势",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "$trendArrow $trendLabel",
                                fontSize = 12.sp,
                                color = trendColor
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // 汇总行：总分享 N · 活跃 N · 过期 N
                        Text(
                            "📤 总分享 ${act.totalShares} · ✅ 活跃 ${act.activeShares} · ⏰ 过期 ${act.expiredShares}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // 最近 7 天柱状图：取 activity 末尾 7 个槽位（升序含零日）。
                        val last7 = act.activity.takeLast(7)
                        if (last7.isNotEmpty()) {
                            val maxCount = last7.maxOf { it.count }.coerceAtLeast(1)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                last7.forEach { day ->
                                    val barHeight = (6 + (day.count.toFloat() / maxCount) * 50).toInt().dp
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            if (day.count > 0) day.count.toString() else "",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(18.dp)
                                                .height(barHeight)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(
                                                    if (day.count > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                                )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        // 日期取 MM-DD 末五字符（YYYY-MM-DD → MM-DD）
                                        Text(
                                            day.date.takeLast(5),
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // V8：最近上传卡片
        var recentUploads by remember { mutableStateOf<List<MediaService.RecentUpload>?>(null) }
        LaunchedEffect(Unit) { recentUploads = MediaService.getRecentUploads() }
        recentUploads?.let { uploads ->
            if (uploads.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "最近上传",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        uploads.forEach { u ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (u.type == "VIDEO") "🎬" else "📷",
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        u.filename,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Text(
                                    u.createdAt.take(10),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // V9：标签云卡片（V8 演进：chip 点击搜索 + 长按弹重命名/删除菜单）
        // V9 升级：改用 cloud-data 端点（带每标签封面缩略图 URL），chip 渲染
        // 24dp 圆角缩略图 + 按媒体数量动态调整字号/留白，接近真标签云观感。
        var tagCloud by remember { mutableStateOf<List<MediaService.TagCloudItem>?>(null) }
        LaunchedEffect(Unit) { tagCloud = MediaService.getTagCloudData() }
        tagCloud?.let { stats ->
            if (stats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // V9：标签云卡片标题行——标题 + "管理"按钮（弹标签管理面板）
                        var showTagManage by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "标签",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { showTagManage = true }) {
                                Text("管理", fontSize = 13.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // V9：长按 chip 弹出操作菜单的锚点标签 + 菜单展开态
                        var tagMenuAnchor by remember { mutableStateOf<String?>(null) }
                        var showTagMenu by remember { mutableStateOf(false) }
                        // V9：重命名对话框目标 + 输入文本
                        var renameTarget by remember { mutableStateOf<String?>(null) }
                        var renameText by remember { mutableStateOf("") }
                        // V8：删除确认对话框目标
                        var deleteTagTarget by remember { mutableStateOf<String?>(null) }
                        // V9：标签云 chip——按 count 动态调整字号（count 越大字号越大），
                        // 有封面缩略图的标签在 chip 前显示 24dp 圆角缩略图。
                        // 先算各 count 的基准：maxCount 用于归一化字号区间。
                        val maxCount = stats.maxOf { it.count }.coerceAtLeast(1)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            stats.forEach { s ->
                                // 字号映射：12sp 起，随 count 归一化线性增至 18sp。
                                val ratio = (s.count.toFloat() / maxCount).coerceIn(0f, 1f)
                                val chipFontSize = (12 + (18 - 12) * ratio).sp
                                // 简化版本：每个 chip 负责加载并缓存自己的封面缩略图。
                                TagCloudChip(
                                    item = s,
                                    fontSize = chipFontSize,
                                    onTagSearch = onTagSearch,
                                    onLongClick = { tag ->
                                        tagMenuAnchor = tag
                                        showTagMenu = true
                                    }
                                )
                                // DropdownMenu 需附着在 composable 上，为当前 tag 锚定显示
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showTagMenu && tagMenuAnchor == s.tagName,
                                    onDismissRequest = { if (tagMenuAnchor == s.tagName) showTagMenu = false }
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            showTagMenu = false
                                            renameTarget = s.tagName
                                            renameText = s.tagName
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            showTagMenu = false
                                            deleteTagTarget = s.tagName
                                        }
                                    )
                                }
                            }
                        }
                        // V9：重命名对话框
                        renameTarget?.let { oldName ->
                            AlertDialog(
                                onDismissRequest = { renameTarget = null },
                                title = { Text("重命名标签") },
                                text = {
                                    OutlinedTextField(
                                        value = renameText,
                                        onValueChange = { renameText = it.trim() },
                                        label = { Text("新标签名") },
                                        singleLine = true
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = renameText.isNotBlank() && renameText != oldName,
                                        onClick = {
                                            val old = oldName
                                            val new = renameText
                                            renameTarget = null
                                            scope.launch {
                                                val ok = MediaService.renameTag(old, new)
                                                if (ok) tagCloud = MediaService.getTagCloudData()
                                            }
                                        }
                                    ) { Text("确定") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { renameTarget = null }) { Text("取消") }
                                }
                            )
                        }
                        // V8：删除确认对话框
                        deleteTagTarget?.let { tag ->
                            AlertDialog(
                                onDismissRequest = { deleteTagTarget = null },
                                title = { Text("删除标签") },
                                text = { Text("确定删除标签 #$tag？将移除所有相关媒体的此标签。") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val t = tag
                                        deleteTagTarget = null
                                        scope.launch {
                                            MediaService.deleteTag(t)
                                            tagCloud = MediaService.getTagCloudData()
                                        }
                                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { deleteTagTarget = null }) { Text("取消") }
                                }
                            )
                        }
                        // V9：标签管理面板——"管理"按钮弹出，列出全部标签 (tag+count)，
                        // 每行配重命名/删除操作（复用上方 renameTarget/deleteTagTarget 对话框流程），
                        // 操作完成后刷新 tagCloud。底部配"导出"按钮调 GET /api/media/tag/export，
                        // 导出结果复制到剪贴板并提示。
                        if (showTagManage) {
                            // 导出反馈消息 + 剪贴板管理器
                            var exportMessage by remember { mutableStateOf<String?>(null) }
                            var isExporting by remember { mutableStateOf(false) }
                            // V9：标签共现对（调 GET /api/media/tag-co-occurrence），用于"常一起出现"区。
                            // 后端只返回 count>=2 的对，且未按 count 排序，此处取 top 5 前先倒序。
                            var tagPairs by remember { mutableStateOf<List<MediaService.TagPair>?>(null) }
                            LaunchedEffect(Unit) { tagPairs = MediaService.getTagCoOccurrence() }
                            // V10：最常用标签排行（调 GET /api/media/tag/most-used），用于"最常用标签"区。
                            // 后端已按 count DESC 返回，此处取 top 5 渲染奖牌排行。null/空静默跳过。
                            var mostUsedTags by remember { mutableStateOf<List<MediaService.MostUsedTag>?>(null) }
                            LaunchedEffect(Unit) { mostUsedTags = MediaService.getMostUsedTags(limit = 5) }
                            // V23：标签影响力排行（调 GET /api/media/tag-power-score），用于"标签影响力"区。
                            // 后端已按 power_score DESC 返回，此处取 top 5。null/空静默跳过。
                            var tagPower by remember { mutableStateOf<List<MediaService.TagPowerItem>?>(null) }
                            LaunchedEffect(Unit) { tagPower = MediaService.getTagPowerScore() }
                            // V24：标签关联矩阵（调 GET /api/media/media-tag-correlation），用于"关联矩阵"区。
                            // 后端返 top N 标签的 N×N 共现矩阵（含对角线=self count）；前端取 top 5
                            // 标签、仅渲染 i<j 且 count>0 的共现对，避免重复与零共现。null（请求失败/
                            // 未铺量）或矩阵维度不足则静默跳过，不影响既有区域。
                            var tagCorrelation by remember { mutableStateOf<MediaService.TagCorrelation?>(null) }
                            LaunchedEffect(Unit) { tagCorrelation = MediaService.getMediaTagCorrelation(limit = 10) }
                            // V23：标签演化趋势（调 GET /api/media/media-tag-evolution），用于"标签演化"区。
                            // 后端按 total 降序返回每标签月度计数 + trend(up/down/stable)；
                            // null（请求失败）或空则静默跳过，不影响既有区域。
                            var tagEvolution by remember { mutableStateOf<List<MediaService.TagEvolution>?>(null) }
                            LaunchedEffect(Unit) { tagEvolution = MediaService.getMediaTagEvolution(months = 6) }
                            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                            AlertDialog(
                                onDismissRequest = { showTagManage = false },
                                title = { Text("标签管理") },
                                text = {
                                    val list = tagCloud
                                    if (list.isNullOrEmpty()) {
                                        Text("暂无标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            // V10：最常用标签排行区——展示关联媒体数最多的 5 个标签，
                                            // 前 3 名配 🥇🥈🥉 奖牌、4-5 名配 🏅。数据来自
                                            // getMostUsedTags(limit=5)（后端已按 count DESC 返回）；
                                            // null（请求失败/未铺量）或空则静默跳过，不影响下方标签列表。
                                            mostUsedTags?.let { tops ->
                                                if (tops.isNotEmpty()) {
                                                    Text(
                                                        "最常用标签",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    tops.take(5).forEachIndexed { idx, t ->
                                                        val medal = when (idx) {
                                                            0 -> "🥇"
                                                            1 -> "🥈"
                                                            2 -> "🥉"
                                                            else -> "🏅"
                                                        }
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 2.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                "$medal #${t.tagName} (${t.count} 项 · ${formatBytesToMB(t.totalBytes)})",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                }
                                            }
                                            // V23：标签影响力排行区——展示 power_score 最高的 5 个标签，
                                            // 每行 ⚡ #tag (score N · N 项 · X%)。数据来自 getTagPowerScore()
                                            // （后端已按 power_score DESC 返回）；null（请求失败/未铺量）或
                                            // 空则静默跳过，不影响下方标签列表。评分一位小数沿用 take 截断约定
                                            // （commonMain 无 String.format）。满覆盖率/整数分直接取整显示。
                                            tagPower?.let { powers ->
                                                if (powers.isNotEmpty()) {
                                                    Text(
                                                        "标签影响力",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    powers.take(5).forEach { p ->
                                                        // power_score 取一位小数（commonMain 无 String.format，
                                                        // 沿用 take 截断约定，与月均增长/totalMB 等处一致）。
                                                        // 整数分值（indexOf('.')<0）则原样显示，避免空截断。
                                                        val rawScore = p.powerScore.toString()
                                                        val scoreStr = if (rawScore.indexOf('.') >= 0) {
                                                            rawScore.take(rawScore.indexOf('.') + 2)
                                                        } else rawScore
                                                        // coverage_percent 同理一位小数。
                                                        val rawCov = p.coveragePercent.toString()
                                                        val covStr = if (rawCov.indexOf('.') >= 0) {
                                                            rawCov.take(rawCov.indexOf('.') + 2)
                                                        } else rawCov
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 2.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                "⚡ #${p.tagName} (score $scoreStr · ${p.mediaCount} 项 · ${covStr}%)",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                }
                                            }
                                            list.forEach { s ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "#${s.tagName} (${s.count})",
                                                        fontSize = 14.sp,
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    TextButton(
                                                        onClick = {
                                                            showTagManage = false
                                                            renameTarget = s.tagName
                                                            renameText = s.tagName
                                                        }
                                                    ) { Text("重命名", fontSize = 12.sp) }
                                                    TextButton(
                                                        onClick = {
                                                            showTagManage = false
                                                            deleteTagTarget = s.tagName
                                                        }
                                                    ) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                                                }
                                            }
                                            // V9：标签关联区——"常一起出现"，展示共现次数最多的 5 对标签。
                                            // 数据来自 getTagCoOccurrence()；null（请求失败/未铺量）或空则静默跳过。
                                            tagPairs?.let { pairs ->
                                                if (pairs.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        "常一起出现",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    pairs.sortedByDescending { it.count }.take(5).forEach { p ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 2.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                "#${p.tagA} + #${p.tagB}",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                "${p.count} 次",
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            // V24：标签关联矩阵区——调 getMediaTagCorrelation，文字版渲染 top 5
                                            // 标签的共现对（i<j 且 count>0）。不画表格，仅显示有共现的标签对：
                                            // 每行 "#tag1 ↔ #tag2 (count)"。矩阵对角线为标签自身 count（跳过），
                                            // 利用对称性只取上三角避免重复。null（请求失败/未铺量）或维度不足（tags
                                            // 或 matrix 行列不匹配/不足 2 个标签）则静默跳过，不影响既有区域。
                                            tagCorrelation?.let { corr ->
                                                val topN = 5
                                                // 取前 topN 标签及对应矩阵行/列，行/列与 tags 同序。
                                                val viewTags = corr.tags.take(topN)
                                                val n = viewTags.size
                                                // 矩阵行数与列数需对齐 viewTags 维度，否则跳过（防御后端异常返回）。
                                                val matrixOk = n >= 2 && corr.matrix.size >= n &&
                                                    corr.matrix.subList(0, n).all { it.size >= n }
                                                if (matrixOk) {
                                                    // 收集 i<j 且 count>0 的共现对，按 count 倒序取 top 5。
                                                    val pairList = mutableListOf<Triple<String, String, Int>>()
                                                    for (i in 0 until n) {
                                                        for (j in (i + 1) until n) {
                                                            val c = corr.matrix[i][j]
                                                            if (c > 0) {
                                                                pairList.add(Triple(viewTags[i], viewTags[j], c))
                                                            }
                                                        }
                                                    }
                                                    if (pairList.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.height(12.dp))
                                                        Text(
                                                            "关联矩阵",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        // 副标题：与"常一起出现"同源但取自矩阵视角，标注 top 标签范围。
                                                        Text(
                                                            "共 ${corr.totalTags} 个标签，矩阵视角（top $n）",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        pairList.sortedByDescending { it.third }.take(5).forEach { (a, b, c) ->
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(vertical = 2.dp),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    "#$a ↔ #$b",
                                                                    fontSize = 13.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.weight(1f),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    "$c",
                                                                    fontSize = 12.sp,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            // V23：标签演化区——调 getMediaTagEvolution(months=6)，展示使用趋势
                                            // 方向最鲜明的 5 个标签：每行 trend emoji + #tag + "共 N 次"。
                                            // trend 后端为 "up"|"down"|"stable" → 前端映射 ↑增长/↓减少/→稳定。
                                            // null（请求失败/未铺量）或空则静默跳过，不影响既有区域。
                                            tagEvolution?.let { evo ->
                                                if (evo.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Text(
                                                        "标签演化",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        "近 6 个月共 ${evo.size} 个标签有活动",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    evo.take(5).forEach { t ->
                                                        val (emoji, label) = when (t.trend) {
                                                            "up" -> "↑" to "增长"
                                                            "down" -> "↓" to "减少"
                                                            else -> "→" to "稳定"
                                                        }
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 2.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                "$emoji #${'$'}{t.tag}（共 ${'$'}{t.total} 次）",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.weight(1f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                label,
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // 导出反馈提示
                                    exportMessage?.let { msg ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            msg,
                                            fontSize = 12.sp,
                                            color = if (msg.startsWith("导出失败")) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                        )
                                    }
                                    // 清理空标签按钮 + 反馈——调 POST /api/media/tag/cleanup-unused。
                                    // 后端端点可能尚未部署，失败时返回 null → 提示"清理失败"。
                                    var isCleaning by remember { mutableStateOf(false) }
                                    var cleanupMessage by remember { mutableStateOf<String?>(null) }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        enabled = !isCleaning && !tagCloud.isNullOrEmpty(),
                                        onClick = {
                                            isCleaning = true
                                            cleanupMessage = null
                                            scope.launch {
                                                val result = MediaService.cleanupUnusedTags()
                                                isCleaning = false
                                                if (result != null) {
                                                    tagCloud = MediaService.getTagCloudData()
                                                    val msg = if (result.removedCount > 0) {
                                                        "已清理 ${result.removedCount} 个空标签"
                                                    } else {
                                                        "没有空标签可清理"
                                                    }
                                                    cleanupMessage = msg
                                                    onShowSnackbar(msg)
                                                } else {
                                                    cleanupMessage = "清理失败，请检查后端连接"
                                                    onShowSnackbar("清理空标签失败")
                                                }
                                            }
                                        }
                                    ) { Text(if (isCleaning) "清理中…" else "清理空标签") }
                                    cleanupMessage?.let { msg ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            msg,
                                            fontSize = 12.sp,
                                            color = if (msg.contains("失败")) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                        )
                                    }
                                    // 智能合并相似标签按钮 + 反馈——调 POST /api/media/tag/merge-smart。
                                    // 自动检测大小写/简繁/中英对应相似标签并合并，成功后弹出合并详情 Dialog。
                                    var isMerging by remember { mutableStateOf(false) }
                                    var mergeMessage by remember { mutableStateOf<String?>(null) }
                                    var mergeDetail by remember { mutableStateOf<MediaService.MergeSmartResult?>(null) }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        enabled = !isMerging && !tagCloud.isNullOrEmpty(),
                                        onClick = {
                                            isMerging = true
                                            mergeMessage = null
                                            scope.launch {
                                                val result = MediaService.mergeSmartTags()
                                                isMerging = false
                                                if (result != null) {
                                                    tagCloud = MediaService.getTagCloudData()
                                                    val msg = if (result.mergedCount > 0) {
                                                        "已合并 ${result.mergedCount} 组相似标签"
                                                    } else {
                                                        "没有相似标签可合并"
                                                    }
                                                    mergeMessage = msg
                                                    if (result.mergedCount > 0) {
                                                        mergeDetail = result
                                                    }
                                                    onShowSnackbar(msg)
                                                } else {
                                                    mergeMessage = "合并失败，请检查后端连接"
                                                    onShowSnackbar("智能合并失败")
                                                }
                                            }
                                        }
                                    ) { Text(if (isMerging) "合并中…" else "智能合并相似标签") }
                                    mergeMessage?.let { msg ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            msg,
                                            fontSize = 12.sp,
                                            color = if (msg.contains("失败")) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                        )
                                    }
                                    // 合并详情 Dialog：展示 from → to, N 项 + 前后总数。
                                    mergeDetail?.let { detail ->
                                        AlertDialog(
                                            onDismissRequest = { mergeDetail = null },
                                            title = { Text("合并详情") },
                                            text = {
                                                Column {
                                                    Text(
                                                        "共合并 ${detail.mergedCount} 组相似标签",
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        "标签数：${detail.totalTagsBefore} → ${detail.totalTagsAfter}",
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    detail.merges.forEachIndexed { idx, pair ->
                                                        Text(
                                                            "${idx + 1}. ${pair.from} → ${pair.to}（${pair.count} 项）",
                                                            fontSize = 13.sp,
                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(onClick = { mergeDetail = null }) { Text("知道了") }
                                            }
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showTagManage = false }) { Text("关闭") }
                                },
                                dismissButton = {
                                    TextButton(
                                        enabled = !isExporting && !tagCloud.isNullOrEmpty(),
                                        onClick = {
                                            isExporting = true
                                            val statList = tagCloud
                                            scope.launch {
                                                val json = MediaService.exportTags()
                                                isExporting = false
                                                if (json != null) {
                                                    clipboard.setText(
                                                        androidx.compose.ui.text.AnnotatedString(json)
                                                    )
                                                    val count = statList?.size ?: 0
                                                    exportMessage = "已导出 $count 个标签（已复制到剪贴板）"
                                                } else {
                                                    exportMessage = "导出失败，请检查后端连接"
                                                }
                                            }
                                        }
                                    ) { Text(if (isExporting) "导出中…" else "导出标签") }
                                }
                            )
                        }
                    }
                }
            }
        }

        // V9：标签云卡片结束
        Spacer(modifier = Modifier.height(8.dp))

        // V22：标签趋势卡片——调 getTagTrend 显示近6月每月新增标签数柱状图。
        // 月份窗口 [本月前推5个月 .. 本月]，升序，空月补0。按最大值缩放柱宽。
        // null（请求失败/未铺量）静默跳过，不破坏下方卡片。
        var tagTrend by remember { mutableStateOf<List<MediaService.TagTrendPoint>?>(null) }
        LaunchedEffect(Unit) { tagTrend = MediaService.getTagTrend() }
        tagTrend?.let { trend ->
            if (trend.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "标签趋势",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 按最大新增数缩放柱宽；全0时 coerceAtLeast(1) 防除零。
                        val maxNew = trend.maxOf { it.newTags }.coerceAtLeast(1)
                        // 仅展示最近6个月（后端默认已返回6条，此处再 take 兜底）
                        trend.take(6).forEach { point ->
                            // 柱宽比例：0..1，至少留 4% 让0月也可见。
                            val ratio = (point.newTags.toFloat() / maxNew).coerceIn(0.04f, 1f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 月份标签（固定宽，右对齐）
                                Text(
                                    point.month,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(56.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // 柱状条容器：占满剩余宽，内部按 ratio 填充强调色
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ratio)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // 新增数（固定宽，右对齐）
                                Text(
                                    "${point.newTags}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }

        // V9：标签关联卡片——调 getTagNetwork 显示标签关联对（简化文字列表，最多5对）。
        // 后端 edges 未按 weight 排序，前端取 top5 前自行按 weight 倒序。
        var tagNetwork by remember { mutableStateOf<MediaService.TagNetwork?>(null) }
        LaunchedEffect(Unit) { tagNetwork = MediaService.getTagNetwork() }
        tagNetwork?.let { net ->
            if (net.edges.isNotEmpty()) {
                val topEdges = net.edges.sortedByDescending { it.weight }.take(5)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "标签关联",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 ${net.totalEdges} 对关联，显示前 ${topEdges.size} 对",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        topEdges.forEach { edge ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "#${edge.source} ↔ #${edge.target}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "weight ${edge.weight}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // V9：标签层级卡片——调 getTagHierarchy 显示标签父子关系（按标签名分隔符
        // - / : 推断的单层父子树）。每个根一行 📁 #tag (N)，子标签缩进 📎 #child (N)。
        // 获取失败(hierarchy=null)静默跳过；最多展示 5 个根、每根最多 3 个子，避免长列表撑满。
        var tagHierarchy by remember { mutableStateOf<List<MediaService.TagHierarchyNode>?>(null) }
        LaunchedEffect(Unit) { tagHierarchy = MediaService.getTagHierarchy() }
        tagHierarchy?.let { roots ->
            if (roots.isNotEmpty()) {
                val topRoots = roots.take(5)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "标签层级",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "共 ${roots.size} 个根标签，显示前 ${topRoots.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        topRoots.forEach { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "📁 #${node.tag}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${node.count}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            node.children.take(3).forEach { child ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, top = 1.dp, bottom = 1.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "📎 #${child.tag}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        "${child.count}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
 * V9：标签云 chip——单条标签的渲染单元。
 *
 * 调用 [MediaService.getTagCloudData] 返回的 [MediaService.TagCloudItem]：
 * - [item.thumbnailUrl] 非空时，从中解析 media_id（路径形如
 *   `/api/media/thumbnail/{media_id}`），经 [BackendImageLoader.loadThumbnail] 异步加载
 *   封面缩略图并在 chip 前显示 24dp 圆角缩略图；加载中/失败显示占位色块。
 * - 字号由调用方按 count 动态计算（[fontSize]），count 越大字号越大，模拟真标签云观感。
 *
 * 点击触发标签搜索，长按回调（用于弹重命名/删除菜单）。chip 用 [Surface] +
 * Row 手绘（而非 AssistChip）以承载 thumbnail + Text 两段内容并控制字号。
 *
 * @param item 标签云条目
 * @param fontSize chip 文字字号（调用方按 count 归一化计算）
 * @param onTagSearch 点击 chip → 触发标签搜索
 * @param onLongClick 长按 chip → 回传标签名供弹菜单
 */
@Composable
private fun TagCloudChip(
    item: MediaService.TagCloudItem,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onTagSearch: (String) -> Unit,
    onLongClick: (String) -> Unit
) {
    // 解析 thumbnail_url 末段作为 media_id（形如 /api/media/thumbnail/{id}）。
    val mediaId = item.thumbnailUrl?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    var thumbBitmap by remember(item.tagName) { mutableStateOf<ImageBitmap?>(null) }
    var thumbLoaded by remember(item.tagName) { mutableStateOf(false) }
    val chipScope = rememberCoroutineScope()

    // 异步加载封面缩略图（命中 BackendImageLoader 的 LRU 缓存，回滑不重复请求）。
    if (mediaId != null) {
        LaunchedEffect(mediaId) {
            chipScope.launch(dispatchers.io) {
                thumbBitmap = try { BackendImageLoader.loadThumbnail(mediaId) } catch (_: Exception) { null }
                thumbLoaded = true
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
        modifier = Modifier.combinedClickable(
            onClick = { onTagSearch(item.tagName) },
            onLongClick = { onLongClick(item.tagName) }
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
        ) {
            // 24dp 封面缩略图：加载中/失败显示占位色块，保持布局稳定不跳动。
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bm = thumbBitmap
                if (bm != null) {
                    Image(
                        bitmap = bm,
                        contentDescription = item.tagName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (!thumbLoaded && mediaId != null) {
                    // 加载中：留空占位（背景色已填充）。
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "#${item.tagName} (${item.count})",
                fontSize = fontSize,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * V9：增长报告单行——"本周/本月 N 项 (↑环比%)"。
 *
 * 环比正数绿色↑、负数红色↓；[Double.NaN]（后端上期为 0 无法计算）时不显示箭头，
 * 仅展示数量。百分比一位小数（commonMain 无 String.format，沿用 take 截断约定）。
 */
@Composable
private fun GrowthReportRow(
    label: String,
    count: Int,
    changePercent: Double
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label $count 项",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!changePercent.isNaN()) {
            val isUp = changePercent >= 0
            // 一位小数截断（与存储预测卡片同款 take 约定）
            val pctStr = changePercent.toString()
            val pct1 = pctStr.take(pctStr.indexOf('.') + 2)
            val arrow = if (isUp) "↑" else "↓"
            val sign = if (isUp) "+" else ""
            Text(
                "  ($arrow$sign$pct1%)",
                fontSize = 12.sp,
                color = if (isUp) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
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

/**
 * 内容多样性评分卡片 —— 「我的」Tab 中展示 Shannon 熵四维度归一化多样性。
 *
 * 后端 [/api/media/media-content-diversity] 对全部未软删媒体按 type / mime / hour / tag
 * 四维度计算 Shannon 熵并归一化（entropy / ln(k)），综合多样性 = 四维度均值；
 * 等级 A(>=0.8) / B(>=0.6) / C(>=0.4) / D(<0.4)。本卡片展示综合评分（百分制）+ 等级 +
 * 四维度归一化熵（百分制）比例条，与照片心情/色温分布等卡片的 Box 比例条样式一致。
 *
 * 抽取为独立 [Composable] 以保持 [MyTabContent] 可读性。
 * 调用方负责 null/空态过滤，本函数假定 [cd.total] > 0。
 *
 * @param cd 多样性评分聚合（综合多样性 0-1 + 等级 + 四维度归一化熵 0-1 + 媒体总数）
 */
@Composable
private fun ContentDiversityCard(cd: MediaService.ContentDiversity) {
    // 四维度固定顺序与中文标签（与后端 breakdown 键一致）。
    val dims = listOf(
        "type" to "类型熵",
        "mime" to "MIME 熵",
        "hour" to "时段熵",
        "tag" to "标签熵"
    )
    // 综合评分百分制（0-100，整数；commonMain 无 String.format，用toDouble后toInt取整）。
    val scorePct = (cd.diversityScore * 100).toInt().coerceIn(0, 100)
    // 等级配色：A 绿、B 蓝、C 橙、D 灰（直观体现多样性高低）。
    val gradeColor = when (cd.grade) {
        "A" -> MaterialTheme.colorScheme.primary
        "B" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        "C" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    // 最高熵维度高亮（多样性最丰富的维度），与照片心情主调档同款高亮约定。
    val topDim = cd.breakdown.maxByOrNull { it.value }?.key
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("内容多样性", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$scorePct/100", fontWeight = FontWeight.Bold, color = gradeColor)
                    Spacer(modifier = Modifier.width(6.dp))
                    // 等级徽章：圆角着色小块，色随等级变化。
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(gradeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(cd.grade, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = gradeColor)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            dims.forEach { (key, label) ->
                val entropy = cd.breakdown[key] ?: 0.0
                val isTop = key == topDim && entropy > 0.0
                val rowColor = if (isTop) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                val barColor = if (isTop) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                // 归一化熵已是 0-1，直接作比例条宽度；百分制显示（整数）。
                val ratio = entropy.coerceIn(0.0, 1.0).toFloat()
                val entropyPct = (entropy * 100).toInt().coerceIn(0, 100)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, fontSize = 12.sp,
                        fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal,
                        color = rowColor)
                    Text("$entropyPct%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                // 比例条：最高熵维度着色更深（与照片心情/色温分布卡片同款 Box 比例条）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "🌈 综合 ${scorePct}/100 · 等级 ${cd.grade} · 共 ${cd.total} 项",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Text(
                "按类型 / MIME / 时段 / 标签四维度 Shannon 熵归一化（entropy / ln(k)）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * V8：拍摄设备分布卡片 —— 「我的」Tab 中展示拍摄设备/来源分布 top5。
 *
 * 后端 [/api/media/media-camera-stats] 按文件名前缀推断设备（IMG_→Apple、IMG-→Samsung、
 * PXL_→Pixel、Screenshot→截图、WXCam_→微信相机 等）并按数量倒序返回；本卡片取前 5 行渲染。
 * 每行格式：`📱 <camera> (<N> 项 · <X>%)`，与「拍摄时段」卡片的左标签 + 进度条 + 右数值布局一致，
 * 便于「我的」Tab 统计卡的视觉统一。
 *
 * 抽取为独立 [Composable] 以保持 [MyTabContent] 可读性（该函数已是超长函数）。
 * 调用方负责 null/空态过滤，本函数假定 [cameras] 非空且总数 > 0。
 *
 * @param cameras 已按数量倒序的设备统计列表（最多取前 5 行）
 */
@Composable
private fun CameraStatsCard(cameras: List<MediaService.CameraStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("拍摄设备", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            // 后端已按 count 倒序，取前 5 行；总占比用作进度条分母（取已返回明细之和，
            // 与后端 percentage 口径一致：count/total*100）。
            val top = cameras.take(5).filter { it.count > 0 }
            val maxCount = top.maxOf { it.count }.coerceAtLeast(1)
            top.forEach { stat ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "📱 ${stat.camera}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { (stat.count.toFloat() / maxCount).coerceIn(0f, 1f) },
                        modifier = Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Text(
                        "${stat.count} 项 · ${formatPercent(stat.percentage)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.width(76.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

/**
 * V8：文件名模式分布卡片 —— 「我的」Tab 中展示文件名前缀分布 top5 + 示例文件名。
 *
 * 后端 [/api/media/media-filename-pattern] 按文件名前缀（取首个分隔符 _ - 空格 . 之前部分；
 * 无分隔符取前 4 rune）分组，统计 count / percentage / example，按 count 倒序返回。
 * 本卡片取前 5 行渲染，每行格式：`<prefix> (<N> 项 · <X>%) · 示例: <example>`，
 * 与 [CameraStatsCard] 的左标签 + 进度条 + 右数值布局一致，便于「我的」Tab 统计卡视觉统一。
 *
 * 抽取为独立 [Composable] 以保持 [MyTabContent] 可读性。
 * 调用方负责 null/空态过滤，本函数假定 [patterns] 非空且总数 > 0。
 *
 * @param patterns 已按数量倒序的前缀模式列表（最多取前 5 行）
 */
@Composable
private fun FilenamePatternCard(patterns: List<MediaService.FilenamePattern>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("文件名模式", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            // 后端已按 count 倒序，取前 5 行；总占比用作进度条分母。
            val top = patterns.take(5).filter { it.count > 0 }
            val maxCount = top.maxOf { it.count }.coerceAtLeast(1)
            top.forEach { stat ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stat.prefix,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = { (stat.count.toFloat() / maxCount).coerceIn(0f, 1f) },
                            modifier = Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Text(
                            "${stat.count} 项 · ${formatPercent(stat.percentage)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.width(76.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    // 示例文件名：次要色 + 小字体，超长省略。
                    if (stat.example.isNotEmpty()) {
                        Text(
                            "示例: ${stat.example}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 0.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * V8：百分比格式化——保留一位小数（commonMain 无 String.format，沿用 take 截断约定，
 * 与标签影响力 coverage_percent / power_score 等处一致）。整数百分比省略小数（如 50% / 33.3%）。
 */
private fun formatPercent(pct: Double): String {
    if (pct % 1.0 == 0.0) return "${pct.toInt()}%"
    val raw = pct.toString()
    return if (raw.indexOf('.') >= 0) "${raw.take(raw.indexOf('.') + 2)}%" else "$raw%"
}

/**
 * V22：仪表盘概览单项——2x3 网格的一格。60dp 圆角方块，emoji + 大数字 + 小标签。
 * 背景 primaryContainer 突出"概览"语义，与 surfaceVariant 区分层次。
 */
@Composable
private fun DashboardMetricCell(
    emoji: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(60.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
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
    // V7：设为相册封面。仅在相册上下文（albumId != null）时显示按钮；
    // 调用方负责传入 albumId 与 onSetCover 实现（通常调 MediaService.setAlbumCover）。
    albumId: String? = null,
    onSetCover: (MediaMetadata) -> Unit = {},
    onShowInfo: (String) -> Unit = {},
    onRotate: (MediaMetadata) -> Unit = {}
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
                            // 长按图片：如果是 Live Photo，播放关联视频；否则弹详情
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
                            } else {
                                // V8：非 Live Photo 长按 → 弹媒体详情
                                if (useBackendLoader) {
                                    onShowInfo(m.id)
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

                    // 相关照片推荐区：调用后端 /api/media/media-related/{id} 获取与当前
                    // 媒体相关的推荐（相同标签 / 相同类型+相近日期），横向滚动展示缩略图 +
                    // 文件名，点击切换到该媒体（在当前 mediaList 中找到索引并滚动 pager）。
                    // 仅后端源（useBackendLoader=true）时展示——本地相册无后端推荐端点可调。
                    // 无相关照片（拉取失败或返回空列表）时不显示该区域。
                    if (useBackendLoader) {
                        RelatedMediaStrip(
                            currentMediaId = currentMedia.id,
                            mediaList = mediaList,
                            onRelatedClick = { relatedMediaId ->
                                // 在当前列表中找到目标媒体的索引，滚动 Pager 切换；
                                // 若不在列表中（理论上不会发生，相关媒体均为当前用户资产）
                                // 静默忽略，避免越界。
                                val targetIndex = mediaList.indexOfFirst { it.id == relatedMediaId }
                                if (targetIndex >= 0) {
                                    scope.launch { pagerState.animateScrollToPage(targetIndex) }
                                }
                            }
                        )
                    }

                    // 相似照片推荐区：调用后端 /api/media/media-similar/{id} 获取与当前
                    // 媒体"看起来像"的推荐（同类型 + size 差距 <20% + 分辨率差距 <30%），
                    // 横向滚动展示缩略图 + 文件名 + 相似度%，点击切换到该媒体。
                    // 与上面的"相关照片"互补：related 用标签/日期，similar 用物理特征。
                    // 仅后端源（useBackendLoader=true）时展示；无相似照片（拉取失败或空列表）时不显示。
                    if (useBackendLoader) {
                        SimilarMediaStrip(
                            currentMediaId = currentMedia.id,
                            mediaList = mediaList,
                            onSimilarClick = { similarMediaId ->
                                val targetIndex = mediaList.indexOfFirst { it.id == similarMediaId }
                                if (targetIndex >= 0) {
                                    scope.launch { pagerState.animateScrollToPage(targetIndex) }
                                }
                            }
                        )
                    }

                    if (showDetails) {
                        DetailPanel(
                            media = currentMedia,
                            sourceLabel = sourceLabel
                        )
                        // V8：显示当前媒体的标签
                        if (useBackendLoader) {
                            var tags by remember(currentMedia.id) { mutableStateOf<List<String>?>(null) }
                            LaunchedEffect(currentMedia.id) { tags = MediaService.listMediaTags(currentMedia.id) }
                            tags?.takeIf { it.isNotEmpty() }?.let { tagList ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    tagList.take(5).forEach { tag ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "#$tag",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 上一张导航：底栏最左侧。第一张禁用。
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_arrow_back,
                                label = "上一张",
                                enabled = currentIndex > 0,
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            )
                            // 中间操作按钮组（编辑/重命名/收藏/删除/详情 等）
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                            // V7：设为相册封面——仅在相册上下文（albumId != null）显示。
                            // 非相册场景（主媒体列表 Tab）不显示，避免无目标的空操作。
                            if (albumId != null) {
                                PreviewActionButton(
                                    iconRes = Res.drawable.ic_image_placeholder,
                                    label = "封面",
                                    onClick = { onSetCover(currentMedia) }
                                )
                            }
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
                            // V8：旋转按钮——仅当 useBackendLoader（云端源）时显示。
                            // 本地相册图片不经过后端，无法通过 /api/media/rotate 旋转。
                            if (useBackendLoader) {
                                PreviewActionButton(
                                    iconRes = Res.drawable.ic_rotate_right,
                                    label = "旋转",
                                    onClick = { onRotate(currentMedia) }
                                )
                            }
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
                            } // 中间操作按钮组 Row
                            // 下一张导航：底栏最右侧。最后一张禁用。
                            PreviewActionButton(
                                iconRes = Res.drawable.ic_arrow_forward,
                                label = "下一张",
                                enabled = currentIndex < mediaList.lastIndex,
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
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
                .border(3.dp, borderColor, RoundedCornerShape(16.dp))
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

                // 选中遮罩：半透明蓝色覆盖，与边框、左上角勾选圆共同构成选中视觉反馈。
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
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

            // 媒体类型徽章：左上角半透明圆角标签，区分 图片(IMG/蓝) / 视频(VID/红) / Live(LIVE/绿)。
            // 优先级 Live > Video > Image：Live Photo 即使 type=IMAGE 也标 LIVE。
            // 选中态下水平右移 32dp 避开左上角的勾选圆圈，未选中时贴左上角显示；
            // 渲染先于勾选圆，z-order 在下层，二者不重叠且互不遮挡。
            val (typeBadgeText, typeBadgeColor) = when {
                media.is_live_photo -> "LIVE" to Color(0xFF43A047)
                isVideo -> "VID" to Color(0xFFE53935)
                else -> "IMG" to Color(0xFF2196F3)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = if (isSelected) 32.dp else 6.dp, top = 6.dp)
                    .background(typeBadgeColor.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    typeBadgeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
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

            // 已收藏星标指示器：右上角，仅在媒体已收藏时才显示。
            // 20dp 半透明圆形背景 + 金色实心星标，点击可快速取消收藏。
            // 未收藏时不渲染，保持网格干净；与左上角的选中勾选徽标互不干扰。
            if (isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable { onFavoriteToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_star_filled),
                        contentDescription = "已收藏",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(14.dp)
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
 * 把活动流时间戳字符串转成相对中文描述（"刚刚"/"3分钟前"/"2小时前"/"昨天"/"5天前"），
 * 超过 30 天或解析失败则回退到 `YYYY/MM/DD` 日期展示（复用 [formatPreviewDate]）。
 *
 * 输入兼容两种后端时间戳口径：
 * 1. **纯数字串**：先按 epoch 秒（10 位）再按 epoch 毫秒（13 位）尝试 [toLongOrNull]，
 *   秒值 *1000 统一到毫秒。这样既支持 Go `Unix()`（秒）也支持 `UnixMilli()`（毫秒）。
 * 2. **RFC3339 字符串**（如 `"2026-08-01T12:34:56Z"` 或带时区偏移 `"2026-08-01T12:34:56+08:00"`）：
 *    手写解析日期/时间分量，用 calendar-to-days 算法（同 [civilFromDaysPreview] 的逆运算）
 *    把 (年,月,日,时,分,秒) 转回 epoch 毫秒。commonMain 无 java.time，且本仓库未引
 *    kotlinx-datetime，故不做更严格的时区归一化——把"本地分量直接当 UTC 分量"算 epoch，
 *    再 [systemTimeZoneOffsetMillis] 回补偏移，使相对差值在数小时/数天尺度上足够准确
 *    （活动流是展示性时间线，不需要分钟级时区精确）。
 *
 * 设计动机：活动流后端时间戳字段格式尚未固化（[MediaService.ActivityFeedItem].timestamp
 * 是 String），故前端宽容解析、失败回退日期，避免因格式不匹配而整列显示空。
 */
private fun relativeTime(timestamp: String): String {
    val ts = timestamp.trim()
    if (ts.isEmpty()) return ""

    // —— 路径 1：纯数字（epoch 秒 / 毫秒）——
    val asLong = ts.toLongOrNull()
    if (asLong != null) {
        val millis = if (asLong < 1_000_000_000_000L) asLong * 1000L else asLong
        return relativeFromMillis(millis)
    }

    // —— 路径 2：ISO8601/RFC3339 手写解析 ——
    val parsed = parseIso8601ToEpochMillis(ts) ?: return ""
    return relativeFromMillis(parsed)
}

/** 由 epoch 毫秒算相对描述（内部共用）。 */
private fun relativeFromMillis(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = nowEpochMillis()
    val diff = now - epochMillis
    if (diff < 0L) {
        // 时间戳在未来（时钟偏移/后端时区导致）—— 显示日期兜底，不编造"未来"措辞。
        return formatPreviewDate(epochMillis)
    }
    val sec = diff / 1000L
    if (sec < 60L) return "刚刚"
    val min = sec / 60L
    if (min < 60L) return "${min}分钟前"
    val hour = min / 60L
    if (hour < 24L) return "${hour}小时前"
    val day = hour / 24L
    if (day == 1L) return "昨天"
    if (day < 30L) return "${day}天前"
    return formatPreviewDate(epochMillis)
}

/**
 * 手写 ISO8601/RFC3339 解析：`YYYY-MM-DDThh:mm:ss[.frac][Z|±hh:mm]` → epoch 毫秒。
 *
 * 仅取日期 + 秒级时间分量，小数秒丢弃（活动流时间线不需要亚秒精度）。
 * 时区处理策略：把解析出的"年月日时分秒"先按 UTC 求 epoch（天用 calendar-to-days
 * 算法），若带 `Z`/`+00:00` 则不加偏移；若带非零 `±hh:mm` 偏移，则减去该偏移得到 UTC
 * 毫秒，再加 [systemTimeZoneOffsetMillis] 转本地——这样相对"现在"的差值与本地时钟一致。
 * 解析失败返回 null，调用方回退空串/日期。
 *
 * 算法参考 [civilFromDaysPreview] 的逆：把 (年,月,日) 经 Howard Hinnant 的
 * days_from_civil 公式得到自 1970-01-01 起的天数，*86400000 + 时分秒毫秒即 epoch。
 */
private fun parseIso8601ToEpochMillis(s: String): Long? {
    // 把 ISO8601/RFC3339 串拆成「日期」「时间」「时区」三段，避免 indexOfFirst 里
    // 混用 Char/Int 比较（曾在 `s.indexOf('-') < it` 处触发 Int<Char 类型歧义编译错）。
    // 形如 "2026-08-01T12:34:56Z" / "2026-08-01 12:34:56+08:00" / "2026-08-01"。
    val normalized = s.trim().replace(' ', 'T')
    // 日期段：取首个 'T' 之前；无 'T' 则整串当日期。
    val tIdx = normalized.indexOf('T')
    val (datePart, restAfterDate) = if (tIdx >= 0) {
        normalized.substring(0, tIdx) to normalized.substring(tIdx + 1)
    } else {
        normalized to ""
    }
    val d = datePart.split('-')
    if (d.size < 3) return null
    val year = d[0].toIntOrNull() ?: return null
    val month = d[1].toIntOrNull() ?: return null
    val day = d[2].toIntOrNull() ?: return null

    // 时间段 + 时区段：restAfterDate 形如 "12:34:56Z" / "12:34:56.789+08:00" / ""（仅日期）
    // 时区起始字符为 'Z' / 'z' 或最后一个 '+' / '-'（时间分量里不会出现 +/-，故末个即偏移号）。
    val timeClean = restAfterDate.substringBefore('.')
    val tzStart = timeClean.indexOfFirst { ch -> ch == 'Z' || ch == 'z' }
        .let { zIdx -> if (zIdx >= 0) zIdx else timeClean.indexOfLast { ch -> ch == '+' || ch == '-' } }
    val (timePart, tzPart) = if (tzStart >= 0 && tzStart < timeClean.length) {
        timeClean.substring(0, tzStart) to timeClean.substring(tzStart)
    } else {
        timeClean to ""
    }
    val t = if (timePart.isEmpty()) listOf("0", "0", "0") else timePart.split(':')
    val hour = t.getOrNull(0)?.toIntOrNull() ?: 0
    val minute = t.getOrNull(1)?.toIntOrNull() ?: 0
    val second = t.getOrNull(2)?.toIntOrNull() ?: 0

    // —— (年,月,日) → 自 1970-01-01 的天数（Howard Hinnant days_from_civil）——
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val daysSinceEpoch = era * 146097L + doe - 719468L

    var epochMillis = daysSinceEpoch * 86_400_000L +
        hour * 3_600_000L + minute * 60_000L + second * 1_000L

    // —— 时区归一化：结果必须是纯 UTC epoch 毫秒 ——
    // days_from_civil 给的是 UTC 天数，故上面已是"按 UTC 分量"算的 epoch。
    // [nowEpochMillis] 返回的也是 UTC epoch（[System.currentTimeMillis] / NSDate 自 1970 UTC），
    // 两数相减才得真实经过时长——相对时间差与时区无关。
    //   - `Z` / 空（无偏移）：分量已是 UTC，无需调整。
    //   - `±hh:mm`：分量是当地时间，减去该偏移得 UTC。
    // ⚠️ 切勿在此加 [systemTimeZoneOffsetMillis]：会把 UTC epoch 推到"未来"，
    // 使相对差变负→提前触发日期兜底（曾经的 bug，ad-hoc 算法验证发现）。日期兜底
    // [formatPreviewDate] 自己加偏移，这里不重复。
    val tz = tzPart.trim()
    if (tz.length >= 3 && (tz[0] == '+' || tz[0] == '-')) {
        val sign = if (tz[0] == '-') -1 else 1
        val rest = tz.substring(1).replace(":", "")
        val tzH = rest.substring(0, 2).toIntOrNull() ?: 0
        val tzM = rest.drop(2).take(2).toIntOrNull() ?: 0
        val offsetMillis = sign * (tzH * 3_600_000L + tzM * 60_000L)
        epochMillis = epochMillis - offsetMillis   // 当地分量 → UTC
    }
    return epochMillis
}

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
 * 相关照片推荐区（横向滚动）。
 *
 * 调用 [MediaService.getRelatedMedia] 获取与 [currentMediaId] 相关的推荐列表，横向滚动展示
 * 每项的缩略图（经 [BackendImageLoader.loadThumbnail]）+ 文件名。点击某项触发 [onRelatedClick]
 * （由父组件滚动 Pager 切换到该媒体）。
 *
 * 加载策略：[LaunchedEffect] 绑定 [currentMediaId]——用户左右滑动切换当前图片时自动重新拉取
 * 相关推荐。加载中不显示该区域（避免占位闪烁）；加载失败或返回空列表也不显示（无痕降级），
 * 满足"如果无相关照片则不显示"的需求。
 *
 * 注意：相关推荐的缩略图走后端 [BackendImageLoader]，故本区域仅在 useBackendLoader 场景
 * 由调用方条件渲染（本地相册源不展示）。
 *
 * @param currentMediaId 当前预览的媒体 ID（推荐以此为中心）
 * @param mediaList 当前预览列表（用于判断点击目标是否在列表内，由父组件处理跳转）
 * @param onRelatedClick 点击相关照片回调，参数为相关媒体的 ID
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun RelatedMediaStrip(
    currentMediaId: String,
    mediaList: List<MediaMetadata>,
    onRelatedClick: (String) -> Unit
) {
    // 相关媒体列表：null=加载中/失败，empty=无相关推荐（均不渲染）。
    var relatedItems by remember(currentMediaId) {
        mutableStateOf<List<com.wgt.feature.media.MediaService.RelatedMedia>?>(null)
    }
    val scope = rememberCoroutineScope()

    // 切换当前图片即重新拉取相关推荐。
    LaunchedEffect(currentMediaId) {
        scope.launch(dispatchers.io) {
            relatedItems = com.wgt.feature.media.MediaService.getRelatedMedia(currentMediaId)
        }
    }

    // 仅在有相关推荐时渲染；加载中（null）与空列表均不显示。
    val items = relatedItems ?: return
    if (items.isEmpty()) return

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "相关照片",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(
                    items = items,
                    key = { it.mediaId },
                    contentType = { "related_media_item" }
                ) { related ->
                    RelatedMediaItem(
                        related = related,
                        onClick = { onRelatedClick(related.mediaId) }
                    )
                }
            }
        }
    }
}

/**
 * 相关照片单项：72×72 缩略图 + 文件名（单行省略），点击触发跳转。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun RelatedMediaItem(
    related: com.wgt.feature.media.MediaService.RelatedMedia,
    onClick: () -> Unit
) {
    var thumbnailBitmap by remember(related.mediaId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(related.mediaId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(related.mediaId) {
        scope.launch(dispatchers.io) {
            try {
                thumbnailBitmap = BackendImageLoader.loadThumbnail(related.mediaId)
            } catch (e: Exception) {
                // 加载失败静默
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }
                thumbnailBitmap != null -> {
                    Image(
                        bitmap = thumbnailBitmap!!,
                        contentDescription = related.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                else -> {
                    // 截断后的视频类型也可能出现在相关推荐里——用占位图标兜底。
                    Icon(
                        painter = painterResource(Res.drawable.ic_image_placeholder),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color.Gray
                    )
                }
            }
        }
        Text(
            text = related.filename,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
    }
}

/**
 * 相似照片推荐区（横向滚动）。
 *
 * 调用 [MediaService.getSimilarMedia] 获取与 [currentMediaId] "看起来像"的推荐列表
 * （后端按同类型 + size 差距 <20% + 分辨率差距 <30% 筛选），横向滚动展示每项的缩略图
 * （经 [BackendImageLoader.loadThumbnail]）+ 文件名 + 相似度%。点击某项触发 [onSimilarClick]
 * （由父组件滚动 Pager 切换到该媒体）。
 *
 * 加载策略、空态处理与 [RelatedMediaStrip] 一致：[LaunchedEffect] 绑定 [currentMediaId]，
 * 加载中（null）与空列表均不渲染（composable 提前 return），满足"如果无相似照片则不显示"。
 * 与"相关照片"互补：related 用标签/日期判断关联，similar 用物理特征（size/类型/尺寸）。
 *
 * @param currentMediaId 当前预览的媒体 ID（推荐以此为中心）
 * @param mediaList 当前预览列表（由父组件用于索引查找，本函数不直接使用）
 * @param onSimilarClick 点击相似照片回调，参数为相似媒体的 ID
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SimilarMediaStrip(
    currentMediaId: String,
    mediaList: List<MediaMetadata>,
    onSimilarClick: (String) -> Unit
) {
    // 相似媒体列表：null=加载中/失败，empty=无相似推荐（均不渲染）。
    var similarItems by remember(currentMediaId) {
        mutableStateOf<List<com.wgt.feature.media.MediaService.SimilarMedia>?>(null)
    }
    val scope = rememberCoroutineScope()

    // 切换当前图片即重新拉取相似推荐。
    LaunchedEffect(currentMediaId) {
        scope.launch(dispatchers.io) {
            similarItems = com.wgt.feature.media.MediaService.getSimilarMedia(currentMediaId)
        }
    }

    // 仅在有相似推荐时渲染；加载中（null）与空列表均不显示。
    val items = similarItems ?: return
    if (items.isEmpty()) return

    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "相似照片",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(
                    items = items,
                    key = { it.mediaId },
                    contentType = { "similar_media_item" }
                ) { similar ->
                    SimilarMediaItem(
                        similar = similar,
                        onClick = { onSimilarClick(similar.mediaId) }
                    )
                }
            }
        }
    }
}

/**
 * 相似照片单项：72×72 缩略图 + 文件名（单行省略）+ 相似度%，点击触发跳转。
 *
 * 相似度按 [similar.similarityScore]（0~100，100 最像）取整展示为 "NN%"。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SimilarMediaItem(
    similar: com.wgt.feature.media.MediaService.SimilarMedia,
    onClick: () -> Unit
) {
    var thumbnailBitmap by remember(similar.mediaId) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(similar.mediaId) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(similar.mediaId) {
        scope.launch(dispatchers.io) {
            try {
                thumbnailBitmap = BackendImageLoader.loadThumbnail(similar.mediaId)
            } catch (e: Exception) {
                // 加载失败静默
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    ShimmerPlaceholder(modifier = Modifier.fillMaxSize())
                }
                thumbnailBitmap != null -> {
                    Image(
                        bitmap = thumbnailBitmap!!,
                        contentDescription = similar.filename,
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
        }
        Text(
            text = similar.filename,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
        // 相似度百分比：取整显示，区分于相关照片单项（无此行）。
        Text(
            text = "${similar.similarityScore.toInt()}%",
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
            fontSize = 9.sp,
            maxLines = 1
        )
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
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        label = "navBtnAlpha"
    )
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
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(alpha)
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

    val emoji = when (tabIndex) {
        0 -> "📸"
        1 -> "📤"
        else -> "☁️"
    }
    val title = when (tabIndex) {
        0 -> "还没有媒体文件"
        1 -> "还没有上传过媒体"
        else -> "网盘还没有媒体"
    }
    val subtitle = when (tabIndex) {
        0 -> "下拉刷新从图库加载照片和视频"
        1 -> "上传你的第一张照片或视频来开始"
        else -> "下拉刷新从网盘加载"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 大 emoji 主视觉 + 脉冲动画，比静态图标更友好亲切。
        Text(
            emoji,
            fontSize = 64.sp,
            modifier = Modifier.graphicsLayer(
                scaleX = pulseScale,
                scaleY = pulseScale,
                alpha = iconAlpha
            )
        )
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
    onBatchRename: () -> Unit = {},
    onBatchTag: () -> Unit = {},
    onBatchUnfavorite: () -> Unit = {},
    onBatchRotate: () -> Unit = {},
    onBatchShare: () -> Unit = {},
    isDeleting: Boolean,
    isUploading: Boolean,
    showUploadButton: Boolean,
    showAddToAlbumButton: Boolean = false,
    showShareLinkButton: Boolean = false,
    showBatchRenameButton: Boolean = false,
    showBatchTagButton: Boolean = false,
    showBatchUnfavoriteButton: Boolean = false,
    showBatchRotateButton: Boolean = false,
    showBatchShareButton: Boolean = false
) {
    val isAllSelected = selectedCount == totalCount && totalCount > 0

    BottomAppBar(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 全选 / 取消全选（文字按钮，更直观——参照 Gallery App 风格）
        TextButton(
            onClick = { if (isAllSelected) onDeselectAll() else onSelectAll() },
            enabled = totalCount > 0
        ) {
            Icon(
                painterResource(Res.drawable.ic_check_circle),
                contentDescription = if (isAllSelected) "取消全选" else "全选",
                tint = if (isAllSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (isAllSelected) "取消全选" else "全选",
                color = if (isAllSelected) MaterialTheme.colorScheme.primary
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

        // 批量分享（调 /api/media/batch-share，为每个选中项各生成一条分享链接，仅云端源显示）
        if (showBatchShareButton) {
            IconButton(onClick = onBatchShare) {
                Icon(
                    painterResource(Res.drawable.ic_cloud_upload),
                    contentDescription = "批量分享"
                )
            }
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

        // V8：批量重命名（仅云端源显示）
        if (showBatchRenameButton) {
            IconButton(onClick = onBatchRename) {
                Icon(
                    painterResource(Res.drawable.ic_edit),
                    contentDescription = "批量重命名"
                )
            }
        }

        // V8：批量打标签（仅云端源显示）
        if (showBatchTagButton) {
            IconButton(onClick = onBatchTag) {
                Icon(
                    painterResource(Res.drawable.ic_tag),
                    contentDescription = "批量打标签"
                )
            }
        }

        // V8：批量旋转（仅云端源显示，顺时针 90°）
        if (showBatchRotateButton) {
            IconButton(onClick = onBatchRotate) {
                Icon(
                    painterResource(Res.drawable.ic_refresh),
                    contentDescription = "批量旋转"
                )
            }
        }

        // V8：批量取消收藏（仅云端源显示，用空心星标图标 ☆）
        if (showBatchUnfavoriteButton) {
            IconButton(onClick = onBatchUnfavorite) {
                Icon(
                    painterResource(Res.drawable.ic_star_outline),
                    contentDescription = "批量取消收藏"
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

/**
 * V8：批量重命名对话框 — 输入 prefix + 起始序号，自动递增生成新文件名。
 * 「预览」按钮调 media-batch-rename-suggest 显示 old→new 列表（最多 10 条），
 * 确认后执行 batchRename 落盘。
 */
@Composable
fun BatchRenameDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (prefix: String, startIndex: Int) -> Unit
) {
    var prefix by remember { mutableStateOf("photo_") }
    // 文本输入框保留字符串形式，便于编辑（避免解析报错吃掉字符）。
    var startIndexText by remember { mutableStateOf("1") }
    val startIndex = startIndexText.trim().toIntOrNull()
    val valid = prefix.isNotBlank() && startIndex != null && startIndex > 0 && selectedCount > 0

    // V8：预览建议列表（调 media-batch-rename-suggest，只读）。
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<MediaService.RenameSuggestion>?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量重命名") },
        text = {
            Column {
                Text("已选 $selectedCount 个文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("文件名前缀") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = startIndexText,
                    onValueChange = { s ->
                        // 仅保留数字，避免非数字输入导致 toIntOrNull 反复失败
                        startIndexText = s.filter { it.isDigit() }.take(6)
                    },
                    label = { Text("起始序号") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "序号从起始值开始递增，最终文件名为「前缀+序号」",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                if (!valid && prefix.isNotBlank() && startIndex == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "⚠ 起始序号须为正整数",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 预览按钮：调后端只读接口拉 old→new 建议列表。
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // enabled 已保证 valid && startIndex != null；非空断言安全。
                        val start = startIndex ?: return@OutlinedButton
                        previewLoading = true
                        previewError = null
                        suggestions = null
                        // 取 min(selectedCount, 10) 条建议，与 UI 展示上限一致。
                        val limit = minOf(selectedCount, 10)
                        coroutineScope.launch {
                            val result = MediaService.getBatchRenameSuggest(prefix.trim(), start, limit)
                            previewLoading = false
                            if (result != null) {
                                suggestions = result
                                if (result.isEmpty()) previewError = "暂无可预览的媒体"
                            } else {
                                previewError = "预览失败，请稍后重试"
                            }
                        }
                    },
                    enabled = valid && !previewLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (previewLoading) "预览中…" else "预览")
                }

                // 预览结果：old → new 列表（最多 10 条，可滚动）。
                previewError?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                suggestions?.let { list ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "预览 (${list.size}):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        list.forEach { s ->
                            Text(
                                "${s.oldName}  →  ${s.suggestedName}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(prefix.trim(), startIndex ?: 1) },
                enabled = valid
            ) { Text("重命名") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * V8：媒体详情对话框 — 调 /api/media/info/{id} 显示完整信息。
 */
@Composable
fun MediaInfoDialog(
    mediaId: String,
    onDismiss: () -> Unit
) {
    var info by remember { mutableStateOf<MediaService.MediaInfo?>(null) }
    // V8：视频时长由独立 ffprobe 端点提供（/api/media/info/{id} 不含 duration），
    // 仅对 VIDEO 类型并发拉取，失败/非视频时为 null，时长行静默跳过。
    var videoInfo by remember { mutableStateOf<MediaService.VideoInfo?>(null) }
    // V9：EXIF 详情（GET /api/media/exif/{id}），含原始拍摄时间 DateTimeOriginal。
    // 与 info 并发拉取；失败/无 EXIF 时为 null，"原始拍摄时间"行静默跳过。
    var exifData by remember { mutableStateOf<MediaService.ExifData?>(null) }
    LaunchedEffect(mediaId) { info = MediaService.getMediaInfo(mediaId) }
    LaunchedEffect(mediaId) { exifData = MediaService.getExifData(mediaId) }
    LaunchedEffect(mediaId, info?.type) {
        if (info?.type?.equals("VIDEO", ignoreCase = true) == true) {
            videoInfo = MediaService.getVideoInfo(mediaId)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("媒体详情") },
        text = {
            info?.let { i ->
                Column {
                    InfoRow("文件名", i.filename)
                    InfoRow("类型", i.type)
                    // 分辨率：width/height 都 >0 时展示 WxH，否则缺省（部分网盘图可能无尺寸）。
                    if (i.width > 0 && i.height > 0) {
                        InfoRow("分辨率", "${i.width} × ${i.height}")
                    }
                    val sizeStr = if (i.sizeMB >= 1) {
                        val s = i.sizeMB.toString(); s.take(s.indexOf('.') + 3) + " MB"
                    } else {
                        val s = i.sizeKB.toString(); s.take(s.indexOf('.') + 3) + " KB"
                    }
                    InfoRow("大小", sizeStr)
                    InfoRow("MIME", i.mime)
                    // 时长：仅视频且 ffprobe 解析成功（duration>0）展示，单位秒，保留 1 位小数。
                    videoInfo?.let { v ->
                        if (v.durationSeconds > 0) {
                            InfoRow("时长", "${v.durationSeconds.toInt()}.${((v.durationSeconds * 10) % 10).toInt()} 秒")
                        }
                    }
                    if (i.sha256.isNotEmpty()) {
                        InfoRow("SHA256", i.sha256.take(16) + "…")
                    }
                    InfoRow("上传时间", i.createdAt)
                    if (i.takenAt > 0) {
                        InfoRow("拍摄时间", formatPreviewDate(i.takenAt * 1000))
                    }
                    // V9：原始拍摄时间 — 来自 EXIF DateTimeOriginal（GET /api/media/exif/{id}）。
                    // 后端 parseTIFFExif 提取的格式通常为 "YYYY:MM:DD HH:MM:SS"，
                    // 此处把日期部分的冒号归一化为 "-" 便于阅读；缺失时不显示该行。
                    exifData?.dateTimeOriginal?.let { raw ->
                        val display = raw.trim().let { s ->
                            // 仅转换日期段前 10 个字符的冒号（YYYY:MM:DD → YYYY-MM-DD），时间段保持原样。
                            if (s.length >= 10) {
                                s.substring(0, 10).replace(":", "-") + s.substring(10)
                            } else s
                        }
                        if (display.isNotEmpty()) {
                            InfoRow("原始拍摄时间", display)
                        }
                    }
                    // V8：标签区域
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("标签:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    var tags by remember(mediaId) { mutableStateOf<List<String>?>(null) }
                    var newTag by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(mediaId) { tags = MediaService.listMediaTags(mediaId) }
                    tags?.let { tagList ->
                        if (tagList.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                tagList.forEach { tag ->
                                    AssistChip(
                                        onClick = {
                                            scope.launch {
                                                if (MediaService.removeMediaTag(mediaId, tag)) {
                                                    tags = MediaService.listMediaTags(mediaId)
                                                }
                                            }
                                        },
                                        label = { Text(tag, fontSize = 11.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newTag,
                                onValueChange = { newTag = it },
                                label = { Text("新标签", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(onClick = {
                                if (newTag.isNotBlank()) {
                                    val t = newTag.trim()
                                    scope.launch {
                                        if (MediaService.addMediaTag(mediaId, t)) {
                                            newTag = ""
                                            tags = MediaService.listMediaTags(mediaId)
                                        }
                                    }
                                }
                            }) { Text("添加", fontSize = 12.sp) }
                        }
                    }
                    // V8：操作历史区域 — 调 /api/media/audit-log/by-media 展示该媒体的操作记录
                    var auditLogs by remember(mediaId) { mutableStateOf<List<MediaService.AuditLogEntry>?>(null) }
                    LaunchedEffect(mediaId) { auditLogs = MediaService.getAuditLogsByMedia(mediaId) }
                    auditLogs?.let { logList ->
                        if (logList.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("操作历史:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            logList.take(10).forEach { entry ->
                                val emoji = when (entry.action) {
                                    "upload" -> "📤"
                                    "delete" -> "🗑️"
                                    "share" -> "🔗"
                                    "rename" -> "✏️"
                                    "favorite" -> "⭐"
                                    "tag" -> "🏷️"
                                    "restore" -> "♻️"
                                    else -> "•"
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(emoji, fontSize = 12.sp)
                                    Text(
                                        entry.detail.ifEmpty { entry.action },
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.createdAt,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            } ?: run {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * V8：批量标签对话框 — 输入标签名，给选中媒体批量打标签。
 */
@Composable
fun BatchTagDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var tag by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tag) {
        if (tag.length >= 1) {
            suggestions = MediaService.tagAutocomplete(tag) ?: emptyList()
        } else {
            suggestions = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量打标签") },
        text = {
            Column {
                Text("已选 $selectedCount 个文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // V8：标签自动补全建议
                if (suggestions.isNotEmpty() && tag.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(suggestions.size) { i ->
                            AssistChip(
                                onClick = { tag = suggestions[i] },
                                label = { Text(suggestions[i], fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(tag.trim()) },
                enabled = tag.isNotBlank() && selectedCount > 0
            ) { Text("添加标签") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
