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
 * 媒体列表屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaListScreen(
    viewModel: MediaViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToFileManagement: () -> Unit = {},
    onNavigateToMemory: (year: Int, month: Int) -> Unit = { _, _ -> },
    onNavigateToInsights: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // TopAppBar 滚动行为：列表滚动时 TopAppBar elevation 动画升高，增强层次感。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 默认打开"网盘图片" Tab（index=3）：5-Tab 重构后，网盘图片从 2 后移到 3。
    // 真机启动即对后端发 q=source=cloud 请求，便于第一时间验证后端连通与
    // cloud 图片（data/cloud-images）加载。新增的"活动"Tab(index=2)是 RN 嵌入页，不拉媒体。
    var selectedTab by remember { mutableStateOf(3) }

    // 图片预览状态：保存当前预览在 mediaList 中的索引（可空）。
    // 用索引而非 MediaMetadata，便于预览内左右滑动切换上一张/下一张。
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var renameTarget by remember { mutableStateOf<MediaMetadata?>(null) }

    // 视频播放状态：点击视频项时填充，非空即在顶层渲染全屏 [VideoPlayer]。
    // 与图片预览互斥：视频项点击直接进播放器，不走 [ImagePreviewDialog]。
    var videoPlayerMedia by remember { mutableStateOf<MediaMetadata?>(null) }

    // 视频裁剪器状态：视频播放器顶部「裁剪」按钮点击后填充，非空即渲染全屏 [VideoTrimDialog]。
    // 仅云端源显示裁剪入口——裁剪需下载原片并上传片段，本地相册无后端通道。
    var trimmerMedia by remember { mutableStateOf<MediaMetadata?>(null) }
    // 顶层协程 scope：供裁剪上传等异步动作触发 snackbar 用。
    val mediaListScope = rememberCoroutineScope()

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

    // 高级搜索对话框显隐：SearchBar 上的「高级搜索」图标触发，
    // AdvancedSearchDialog 回调 Map<String,String> 条件后由本 Screen 启协程调
    // MediaService.advancedSearch + applyAdvancedSearchResults 替换列表。
    var showAdvancedSearch by remember { mutableStateOf(false) }
    val advancedSearchScope = rememberCoroutineScope()

    // 智能搜索对话框显隐：SearchBar 上的「🤖 智能搜索」按钮触发，
    // SmartSearchDialog 内部就地调 MediaService.getMediaSmartSearch（自然语言查询），
    // 命中结果经 onResults 回调灌入 viewModel.applyAdvancedSearchResults 替换列表。
    // 复用 advancedSearchScope 即可（同为后台搜索协程，无并发冲突）。
    var showSmartSearch by remember { mutableStateOf(false) }

    // 全文搜索对话框显隐：SearchBar 上的「📍 全文搜索」按钮触发，
    // FullTextSearchDialog 内部就地调 MediaService.getMediaFullTextSearch（关键词+可选位置/类型），
    // 命中结果经 onResults 灌入 viewModel.applyAdvancedSearchResults 替换列表。
    // 复用 advancedSearchScope。仅结构性挂载，不改既有本地搜索过滤逻辑。
    var showFullTextSearch by remember { mutableStateOf(false) }

    // ── 离线模式状态（PRD-v8 §1.5）──
    // 每 5 秒轮询 OfflineCacheManager.isOfflineMode()，驱动离线 banner 显隐。
    // 用户可手动关 banner（offlineBannerDismissed），但网络恢复后 dismissed 标记自动复位，
    // 下次再断网 banner 会重新出现。banner 不自动消失——只在网络恢复后自动消失。
    var isOffline by remember { mutableStateOf(OfflineCacheManager.isOfflineMode()) }
    var offlineBannerDismissed by remember { mutableStateOf(false) }
    // 离线上传队列待传项数：与 isOffline 同一 5 秒轮询周期刷新，供"我的"Tab 队列指示器展示。
    var offlineQueueSize by remember { mutableStateOf(OfflineQueueStore.size()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = OfflineCacheManager.isOfflineMode()
            // 网络恢复时自动复位 dismissed 标记，使下次断网 banner 重新可显示。
            if (!current) offlineBannerDismissed = false
            isOffline = current
            offlineQueueSize = OfflineQueueStore.size()
            kotlinx.coroutines.delay(5_000L)
        }
    }

    // ── 离线队列冲突解决对话框状态（PRD-v8 §1.5 离线模式完整化）──
    // 来自 MediaViewModel 的 StateFlow：对话框显隐 / 待传项列表 / 重试中标志。
    // 顶部「⏳ N 项待上传」指示器点击 → viewModel.showOfflineQueueDialog() 置 showOfflineQueueDialog，
    // 这里据此渲染 OfflineQueueDialog。items/isRetrying 由 ViewModel 在打开/移除/重试后即时刷新。
    val showOfflineQueueDialog by viewModel.showOfflineQueueDialog.collectAsState()
    val offlineQueueItems by viewModel.offlineQueueItems.collectAsState()
    val isRetryingOfflineQueue by viewModel.isRetryingOfflineQueue.collectAsState()

    // ── 后台上传持续进度（PRD-v8 §2.2）──
    // collect backgroundUploadState：Idle/Running(completed,total)/Completed(total)/Failed(failedCount,total)。
    // 与离线 banner 同处顶部，给用户持续进度反馈而非仅一次性 Snackbar。
    // Running 时显示线性进度条 + "后台上传 N/M"；Completed/Failed 展示终态文案，
    // 经 [bgBannerDismissDelay] 延迟后回 Idle 视觉消失（Completed 3s / Failed 5s）。
    val bgUploadState by viewModel.backgroundUploadState.collectAsState()
    // 顶部 banner 当前是否可见。Running 期间恒显；Completed/Failed 在延迟到期后由
    // LaunchedEffect 置 false 收起。新一批 Running 进入时复位为 true。
    var bgBannerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(bgUploadState) {
        when (bgUploadState) {
            is BackgroundUploadState.Running -> bgBannerVisible = true
            is BackgroundUploadState.Completed -> {
                bgBannerVisible = true
                kotlinx.coroutines.delay(3_000L)
                bgBannerVisible = false
            }
            is BackgroundUploadState.Failed -> {
                bgBannerVisible = true
                kotlinx.coroutines.delay(5_000L)
                bgBannerVisible = false
            }
            is BackgroundUploadState.Idle -> bgBannerVisible = false
        }
    }

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

    // V8 批量下载：调 getBatchDownloadUrls 拿到的 URL 列表，非 null 即弹出对话框供复制。
    // isBatchDownloading 防止重复点击。
    var isBatchDownloading by remember { mutableStateOf(false) }
    var batchDownloadUrls by remember { mutableStateOf<List<com.wgt.feature.media.MediaService.BatchDownloadUrl>?>(null) }

    // 批量删除确认对话框：点击删除按钮后先弹确认，避免误删。

    // 监听错误信息并显示 Snackbar
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 加载本地照片图库 / 已上传图片 / 网盘图片（使用缓存）
    // 5-Tab 重构后：0=本地图片 / 1=已上传 / 2=活动(RN,不加载媒体) / 3=网盘图片 / 4=我的
    LaunchedEffect(selectedTab) {
        // 切换 Tab 时清空选中状态，避免上一 Tab 的选中项串到新 Tab（选中 ID 不匹配新列表）
        viewModel.deselectAll()
        // "活动"(2)和"我的"(4) Tab 不加载媒体数据。
        if (selectedTab == 2 || selectedTab == 4) return@LaunchedEffect
        // 切换 Tab 即切换数据源：清空搜索关键词与类型筛选，避免上个 Tab 的过滤条件
        // 串到新 Tab 造成“列表为空/对不上”的困惑。
        viewModel.clearSearchAndFilter()
        // 切换 Tab 自动退出"服务端收藏"筛选：收藏视图是跨 Tab 的临时筛选态，
        // 进入新 Tab 应回到该 Tab 的正常媒体源，避免收藏列表串到新 Tab。
        if (viewModel.favoritesOnly) {
            viewModel.toggleFavoritesOnly()
        }
        searchExpanded = false
        if (selectedTab == 0 && viewModel.canAccessGallery) {
            viewModel.loadMediaFromGallery(forceRefresh = false)
        } else if (selectedTab == 1) {
            // "已上传" Tab 现为云端媒体视图：展示 sync/changes 增量同步累积的 cloudMedia，
            // 进入即触发后台增量续拉。保留 loadCloudViewForTab 的"秒开已有视图 + 增量刷新"语义。
            viewModel.loadCloudViewForTab(forceRefresh = false)
        } else if (selectedTab == 3) {
            viewModel.loadCloudMediaList(forceRefresh = false)
        }
    }

    // 关闭"服务端收藏"筛选时，按当前 Tab 重新加载正常媒体源，恢复筛选前列表。
    // 打开时由 toggleFavoritesOnly 内部已处理（秒开 favoritesList + loadFavorites），
    // 故此 effect 只对 false 转换生效。Tab 切换那条路径会先 toggle 关闭再 loadXxx，
    // 此 effect 也会触发一次 loadXxx——幂等且都走缓存秒开,重复一次无副作用。
    LaunchedEffect(viewModel.favoritesOnly) {
        if (!viewModel.favoritesOnly) {
            // 仅对媒体 Tab 触发恢复；活动/我的 Tab 无媒体源,跳过。
            if (selectedTab != 2 && selectedTab != 4) {
                when (selectedTab) {
                    0 -> if (viewModel.canAccessGallery) viewModel.loadMediaFromGallery(forceRefresh = false)
                    1 -> viewModel.loadCloudViewForTab(forceRefresh = false)
                    3 -> viewModel.loadCloudMediaList(forceRefresh = false)
                }
            }
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
                                    3 -> viewModel.loadCloudMediaList(forceRefresh = true)
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
    // V9：在播放器上层叠加「裁剪」入口（仅云端源），点击进入 [VideoTrimDialog]——
    // 视频项不走 ImagePreviewDialog，其操作栏需独立挂载于播放器之上。
    videoPlayerMedia?.let { media ->
        val useBackend = viewModel.currentSource != com.wgt.feature.media.MediaService.MediaSource.LOCAL
        Box(modifier = Modifier.fillMaxSize()) {
            VideoPlayer(
                media = media,
                initialDurationSeconds = viewModel.videoDurations[media.id],
                onDismiss = { videoPlayerMedia = null }
            )
            // 裁剪入口：右上角悬浮按钮，避免遮挡播放器原生控件（底部进度/顶部标题）。
            if (useBackend) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 60.dp, end = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            videoPlayerMedia = null
                            trimmerMedia = media
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_crop_reset),
                            contentDescription = "裁剪",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("裁剪", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // 视频裁剪对话框：裁剪完毕回调里调 MediaService.videoTrimUpload 上传片段，
    // 成功后 Snackbar 提示并刷新当前源列表（与「旋转/删除」交互闭环一致）。
    trimmerMedia?.let { media ->
        VideoTrimDialog(
            media = media,
            durationSeconds = viewModel.videoDurations[media.id],
            onResult = { trimmed ->
                trimmerMedia = null
                if (trimmed == null) {
                    mediaListScope.launch { snackbarHostState.showSnackbar("视频裁剪失败，请重试") }
                    return@VideoTrimDialog
                }
                mediaListScope.launch {
                    snackbarHostState.showSnackbar("正在上传裁剪片段…")
                    val newId = MediaService.videoTrimUpload(media.id, trimmed, ".mp4")
                    if (newId != null) {
                        snackbarHostState.showSnackbar("视频裁剪已保存")
                        // 按当前源触发强制刷新，使新片段出现在列表中。
                        when (viewModel.currentSource) {
                            com.wgt.feature.media.MediaService.MediaSource.LOCAL -> viewModel.loadMediaFromGallery(forceRefresh = true)
                            else -> viewModel.loadCloudMediaList(forceRefresh = true)
                        }
                    } else {
                        snackbarHostState.showSnackbar("裁剪片段上传失败，请重试")
                    }
                }
            },
            onDismiss = { trimmerMedia = null }
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

    // V8：批量标签对话框（多模式：打标签/批量重命名/合并标签/批量移除）
    if (showBatchTagDialog) {
        BatchTagDialog(
            selectedCount = viewModel.selectedCount,
            onDismiss = { showBatchTagDialog = false },
            onSnackbar = { msg ->
                advancedSearchScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onConfirm = { payload ->
                // payload 为标签名（打标签模式）或 "REMOVE:<tag>"（移除模式，需 ViewModel 持有选中 IDs）
                if (payload.startsWith("REMOVE:")) {
                    val tagName = payload.removePrefix("REMOVE:")
                    showBatchTagDialog = false
                    advancedSearchScope.launch {
                        if (viewModel.selectedMediaIds.isEmpty()) {
                            snackbarHostState.showSnackbar("未选择媒体")
                            return@launch
                        }
                        val ok = MediaService.batchRemoveTags(
                            viewModel.selectedMediaIds.toList(),
                            listOf(tagName)
                        )
                        snackbarHostState.showSnackbar(if (ok) "已移除标签" else "移除失败")
                    }
                } else {
                    showBatchTagDialog = false
                    viewModel.batchAddTagToSelected(payload)
                }
            }
        )
    }

    // V8：批量下载 URL 列表对话框 —— 调 getBatchDownloadUrls 成功后弹出，展示每个文件的
    // 直接下载 URL（/api/media/download/{id}，鉴权有效）供用户复制。URL 为相对路径，
    // 对话框内拼接 backendBaseUrl 便于完整复制。
    batchDownloadUrls?.let { urls ->
        BatchDownloadUrlsDialog(
            urls = urls,
            onDismiss = { batchDownloadUrls = null }
        )
    }

    // V8：媒体详情对话框
    mediaInfoTarget?.let { mediaId ->
        MediaInfoDialog(
            mediaId = mediaId,
            onDismiss = { mediaInfoTarget = null }
        )
    }

    // ── 离线队列冲突解决对话框（PRD-v8 §1.5 离线模式完整化）──
    // 由「我的」Tab 的「⏳ N 项待上传」指示器点击触发（MyTabContent 的 onShowOfflineQueue）。
    // 列表/重试态由 ViewModel 的 StateFlow 驱动；关闭调 dismissOfflineQueueDialog 停轮询。
    // items 实时反映 OfflineQueueStore 快照（打开时取一次 + 5s 轮询 + 移除/重试后即时刷新）。
    if (showOfflineQueueDialog) {
        OfflineQueueDialog(
            items = offlineQueueItems,
            isRetrying = isRetryingOfflineQueue,
            onDismiss = { viewModel.dismissOfflineQueueDialog() },
            onRemoveItem = { id -> viewModel.removeOfflineQueueItem(id) },
            onRetryAll = { viewModel.retryOfflineQueue() }
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

    // 智能搜索对话框：用户输入自然语言查询（如"去年夏天的视频"），
    // SmartSearchDialog 内部调 MediaService.getMediaSmartSearch，命中结果经
    // onResults 灌入 viewModel.applyAdvancedSearchResults 替换列表；
    // 空结果在对话框内已展示"找到 0 项"，此处额外 Snackbar 提示并关闭对话框。
    // 复用 advancedSearchScope 发起后台搜索协程（搜索请求本身在 Dialog 内同步等待）。
    if (showSmartSearch) {
        SmartSearchDialog(
            onDismiss = { showSmartSearch = false },
            onResults = { list, total, parsed ->
                showSmartSearch = false
                viewModel.applyAdvancedSearchResults(list)
                if (total == 0) {
                    advancedSearchScope.launch {
                        snackbarHostState.showSnackbar("未找到匹配的媒体")
                    }
                }
            }
        )
    }

    // 全文搜索对话框：用户输入关键词（可选位置 lat,lon + 半径 + 类型），FullTextSearchDialog
    // 内部调 MediaService.getMediaFullTextSearch，命中结果经 onResults 灌入
    // viewModel.applyAdvancedSearchResults 替换列表；位置筛选不可用时 Snackbar 提示。
    if (showFullTextSearch) {
        FullTextSearchDialog(
            onDismiss = { showFullTextSearch = false },
            onResults = { list, total, locUnavailable ->
                showFullTextSearch = false
                viewModel.applyAdvancedSearchResults(list)
                if (total == 0) {
                    advancedSearchScope.launch {
                        snackbarHostState.showSnackbar("未找到匹配的媒体")
                    }
                } else if (locUnavailable) {
                    advancedSearchScope.launch {
                        snackbarHostState.showSnackbar("位置筛选不可用，已忽略位置条件")
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
            // 选择模式仅在媒体 Tab（0/1/3）显示；"活动"(2)和"我的"(4)无选择态。
            if (viewModel.hasSelection && selectedTab != 2 && selectedTab != 4) {
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
                    showBatchRotateButton = selectedTab != 0, // V8：仅云端源显示批量旋转
                    showBackgroundUploadButton = selectedTab == 0, // V8 §2.2：仅本地源显示后台上传
                    showBatchDownloadButton = selectedTab != 0, // V8：仅云端源显示批量下载
                    onBackgroundUpload = {
                        viewModel.enqueueBackgroundUpload()
                        advancedSearchScope.launch {
                            snackbarHostState.showSnackbar("已加入后台上传队列")
                        }
                    },
                    onBatchDownload = {
                        // 批量下载：调 /api/media/batch-download-urls 获取直接下载 URL 列表，
                        // 成功后弹出对话框供用户复制；失败提示错误。
                        val ids = viewModel.selectedMediaIds.toList()
                        if (ids.isEmpty()) {
                            advancedSearchScope.launch { snackbarHostState.showSnackbar("请先选择媒体") }
                        } else if (!isBatchDownloading) {
                            isBatchDownloading = true
                            advancedSearchScope.launch(dispatchers.io) {
                                val urls = com.wgt.feature.media.MediaService.getBatchDownloadUrls(ids)
                                isBatchDownloading = false
                                if (urls != null) {
                                    batchDownloadUrls = urls
                                } else {
                                    snackbarHostState.showSnackbar("获取下载链接失败，请稍后重试")
                                }
                            }
                        }
                    }
                )
            } else {
                // 正常模式：5-Tab 底部导航栏（MIUI 风格 + 中间圆形凸起"活动"Tab）
                // 顺序：0 本地图片 / 1 已上传 / 2 活动(凸起,RN) / 3 网盘图片 / 4 我的
                ActivityBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it }
                )
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
            // "活动" Tab(index=2)：直接嵌入 RN 活动中心页面（无 TopAppBar/返回按钮，
            // 由底部 Tab 切换进出）。RnContainer 加载 assets/index.android.bundle，
            // 组件名 "MediaManagerApp"。hostId 用独立标识避免与导航到 RnActivityScreen 的
            // 那个 host 复用（两者场景不同：此为常驻 Tab，彼为 push 页）。
            if (selectedTab == 2) {
                RnContainer(
                    componentName = "MediaManagerApp",
                    bundleAssetName = "index.android.bundle",
                    hostId = "activity-tab",
                    modifier = Modifier.fillMaxSize().statusBarsPadding()
                )
                return@Box
            }

            // "我的" Tab(index=4)：设置 / 相册入口页，不渲染媒体网格
            if (selectedTab == 4) {
                MyTabContent(
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToAlbums = onNavigateToAlbums,
                    onNavigateToFileManagement = onNavigateToFileManagement,
                    onNavigateToInsights = onNavigateToInsights,
                    offlineQueueSize = offlineQueueSize
                )
                return@Box
            }

            // 媒体 Tab（0-2）：标题 + 搜索栏 + 筛选条 + 网格列表
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // 标题行：选择模式下显示已选数量 + 关闭按钮（小米相册风格）
                // M3 Expressive：标题用 AppTypography.headlineSmall（24sp SemiBold），
                // onSurface 主色；选择模式计数用 titleMedium。搜索图标用 onSurfaceVariant。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.spacingSmall, bottom = Dimens.spacingSmall, start = Dimens.spacingLarge, end = Dimens.spacingLarge),
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
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(Dimens.spacingSmall))
                                Text(
                                    "已选择 ${viewModel.selectedCount} 项",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.animateContentSize(tween(200))
                                )
                            }
                        } else {
                            Text(
                                "图片管理",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                // 搜索栏：自带 IconButton 展开收起，无需额外图标
                // 媒体 Tab（0/1/3）才显示搜索 + 筛选；"活动"(2)/"我的"(4)在上面 return@Box 了。
                if (selectedTab != 2 && selectedTab != 4) {
                    SearchBar(
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                    onDebouncedQueryChange = { query ->
                        viewModel.applySearchQuery(query)
                        if (query.isNotBlank()) SearchHistory.add(query)
                    },
                    onSearchSubmit = { /* IME 搜索键：去抖已驱动过滤，此处无需额外动作 */ },
                    onAdvancedSearch = { showAdvancedSearch = true },
                    onSmartSearch = { showSmartSearch = true },
                    onFullTextSearch = { showFullTextSearch = true }
                )

                    // 类型筛选条：全部 / 图片 / 视频，与搜索叠加生效
                    FilterChipsRow(
                        selected = viewModel.filterType,
                        onSelect = { type -> viewModel.applyFilterType(type) }
                    )

                    // —— 服务端收藏筛选切换（对接 MediaService.getFavorites()）——
                    // 激活时列表只展示后端 GET /api/media/favorites 返回的收藏媒体，
                    // 与上面的本地 FAVORITE 类型筛选互补：后者只在当前 Tab mediaList 内按
                    // favoriteIds 过滤，本切换直接拉取服务端收藏全集。仅在媒体 Tab（0/1/3）
                    // 显示——活动/我的 Tab 无媒体视图。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = viewModel.favoritesOnly,
                            onClick = { viewModel.toggleFavoritesOnly() },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("★ 服务端收藏")
                                    if (viewModel.isLoadingFavorites) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                        if (viewModel.favoritesOnly) {
                            Text(
                                "仅显示服务端收藏 · ${viewModel.favoritesList.size} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } // end if (selectedTab != 2 && selectedTab != 4)

                // ── 离线模式 banner（PRD-v8 §1.5）──
                // 离线且未被用户手动关闭时显示。网络恢复后 isOffline 变 false 自动消失。
                // 用户可点击关闭按钮暂时隐藏，但 offlineBannerDismissed 在网络恢复时自动复位。
                AnimatedVisibility(
                    visible = isOffline && !offlineBannerDismissed,
                    enter = fadeIn(tween(280)) + expandVertically(tween(280)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spacingLarge, vertical = Dimens.spacingSmall),
                        shape = RoundedCornerShape(Dimens.cardCornerRadius),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "📱 离线模式——显示已缓存内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { offlineBannerDismissed = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text(
                                    "✕",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }

                // ── 后台上传持续进度 banner（PRD-v8 §2.2）──
                // 离线 banner 下方，[bgBannerVisible] 控制显隐：Running 恒显进度条，
                // Completed/Failed 显示终态文案 3s/5s 后由 LaunchedEffect 收起。
                // 风格与离线 banner 一致（Surface + Row），用 primaryContainer 配色区分语义。
                AnimatedVisibility(
                    visible = bgBannerVisible,
                    enter = fadeIn(tween(280)) + expandVertically(tween(280)),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(220))
                ) {
                    val running = bgUploadState as? BackgroundUploadState.Running
                    val completed = bgUploadState as? BackgroundUploadState.Completed
                    val failed = bgUploadState as? BackgroundUploadState.Failed
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spacingLarge, vertical = Dimens.spacingSmall),
                        shape = RoundedCornerShape(Dimens.cardCornerRadius),
                        color = when {
                            failed != null -> MaterialTheme.colorScheme.errorContainer
                            completed != null -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when {
                                running != null -> {
                                    val total = running.total.coerceAtLeast(1)
                                    val ratio = running.completed.toFloat() / total
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "☁ 后台上传 ${running.completed}/${running.total}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { ratio },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                completed != null -> {
                                    Text(
                                        "✓ 后台上传完成（共 ${completed.total} 项）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                failed != null -> {
                                    Text(
                                        "⚠ 后台上传失败 ${failed.failedCount}/${failed.total} 项",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

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
                        3 -> viewModel.isCloudLoading
                        else -> viewModel.isLoading
                    }
                    // 服务端收藏筛选激活时，loading 态改用收藏加载指示，
                    // 下拉刷新改为续拉收藏列表（而非该 Tab 的正常媒体源）。
                    val effectiveLoading = if (viewModel.favoritesOnly) {
                        isLoading || viewModel.isLoadingFavorites
                    } else {
                        isLoading
                    }
                    val mediaList = viewModel.mediaList
                    val filtered = viewModel.filteredList
                    val onRefresh = {
                        if (viewModel.favoritesOnly) {
                            viewModel.loadFavorites()
                        } else when (selectedTab) {
                            0 -> viewModel.loadMediaFromGallery(forceRefresh = true)
                            1 -> viewModel.loadCloudViewForTab(forceRefresh = true)
                            else -> viewModel.loadCloudMediaList(forceRefresh = true)
                        }
                    }

                    val listError = viewModel.listLoadError
                    when {
                        mediaList.isEmpty() && listError != null && !effectiveLoading -> {
                            ErrorStateView(
                                message = listError,
                                onRetry = onRefresh
                            )
                        }

                        mediaList.isEmpty() && effectiveLoading -> {
                            FullScreenLoading()
                        }

                        mediaList.isEmpty() -> {
                            PullToRefreshBox(
                                isRefreshing = effectiveLoading,
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
                                isRefreshing = effectiveLoading,
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
                                        onLoadMore = if (viewModel.favoritesOnly) null else if (selectedTab == 0) { { viewModel.loadMoreGallery() } } else if (selectedTab == 1) { { viewModel.loadMoreCloudChanges() } } else if (selectedTab == 3) { { viewModel.loadMoreCloudList() } } else null,
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
                                        onLoadMore = if (viewModel.favoritesOnly) null else if (selectedTab == 0) { { viewModel.loadMoreGallery() } } else if (selectedTab == 1) { { viewModel.loadMoreCloudChanges() } } else if (selectedTab == 3) { { viewModel.loadMoreCloudList() } } else null,
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
 * 5-Tab 底部导航栏（含中间"活动"Tab 圆形凸起）。
 *
 * 布局：[本地图片][已上传] [●活动●] [网盘图片][我的]，SpaceEvenly 排列。
 * 中间"活动"(index=2)为圆形凸起按钮（56dp primary 圆 + offset(-12dp)），类似抖音/
 * 小红书中间 + 号风格；选中时圆形换 primaryContainer + 加边框。普通 Tab 由 [NavTab] 渲染。
 *
 * @param selectedTab 当前选中的 Tab 索引
 * @param onSelect    Tab 点击回调，参数为 Tab 索引（0..4）
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ActivityBottomBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit
) {
    // M3 Expressive：底部 Tab 栏容器用 expressiveShape（28dp 超圆角）包裹，
    // 浮于内容之上 + 微 elevation；外层留 horizontal padding 使容器不贴边。
    // 选中态由 NavTab 内部用 pillShape 胶囊背景 + animateColorAsState 渐变体现。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacingMedium, vertical = Dimens.spacingSmall),
        shape = expressiveShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Dimens.cardElevationFlat,
        shadowElevation = Dimens.cardElevationFlat
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab(
                icon = Res.drawable.ic_photo,
                label = "本地图片",
                selected = selectedTab == 0,
                onClick = { onSelect(0) }
            )
            NavTab(
                icon = Res.drawable.ic_file_upload,
                label = "已上传",
                selected = selectedTab == 1,
                onClick = { onSelect(1) }
            )
            CenterActivityFab(
                selected = selectedTab == 2,
                onClick = { onSelect(2) }
            )
            NavTab(
                icon = Res.drawable.ic_cloud,
                label = "网盘图片",
                selected = selectedTab == 3,
                onClick = { onSelect(3) }
            )
            NavTab(
                icon = Res.drawable.ic_settings,
                label = "我的",
                selected = selectedTab == 4,
                onClick = { onSelect(4) }
            )
        }
    }
}

/**
 * 普通底部导航 Tab（无凸起）。图标 + 文案纵向排列，选中时着 primary 色。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun NavTab(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    // M3 Expressive：选中态用 pillShape 胶囊背景（primaryContainer 半透明），
    // 颜色与 alpha 均走 animateColorAsState tween(200) 柔和渐变。
    val baseTint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.42f,
        animationSpec = tween(200),
        label = "navTabAlpha"
    )
    // 选中胶囊背景色：primaryContainer → 透明，tween 渐变。
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f),
        animationSpec = tween(200),
        label = "navPillColor"
    )
    Column(
        modifier = Modifier
            // 选中态胶囊背景：pillShape + primaryContainer，仅选中时显现。
            .clip(pillShape)
            .background(pillColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(horizontal = Dimens.spacingMedium, vertical = Dimens.spacingSmall),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = baseTint.copy(alpha = alpha),
            modifier = Modifier.size(Dimens.navIconSize)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = baseTint.copy(alpha = alpha),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        // 选中指示圆点：动画 alpha 过渡，选中时显现 primary 色小圆点，
        // 未选中时完全透明不占视觉位。比顶部指示线更精致、不破坏扁平观感。
        val dotAlpha by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = tween(200),
            label = "navDotAlpha"
        )
        Box(
            modifier = Modifier
                .size(Dimens.navIndicatorDotSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
        )
    }
}

/**
 * 中间"活动"Tab：圆形凸起按钮。56dp 圆，offset(y=-12dp) 上凸。
 * 选中时圆背景改 primaryContainer + 加 onPrimaryContainer 边框，未选中时 primary 实心。
 */
@Composable
private fun CenterActivityFab(
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.primary
    val iconColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onPrimary
    // M3 Expressive：中间凸起 FAB 用 expressiveShape（28dp 超圆角）替代纯圆形，
    // 视觉更柔和现代；elevation 保留凸起感。颜色用 animateColorAsState 柔和过渡。
    val animContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(200),
        label = "fabContainer"
    )
    val animIconColor by animateColorAsState(
        targetValue = iconColor,
        animationSpec = tween(200),
        label = "fabIcon"
    )
    val borderColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else Color.Transparent
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = expressiveShape,
            color = animContainerColor,
            border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
            modifier = Modifier
                .size(Dimens.centerFabSize)
                .offset(y = (-12).dp)
                .shadow(Dimens.centerFabElevation, expressiveShape)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_cloud),
                    contentDescription = "活动",
                    tint = animIconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // 下方文案不偏移，与左右 Tab 文案对齐（凸起部分占上方 12dp）
        Text(
            "活动",
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.offset(y = (-8).dp)  // 回补凸起位移，使文案视觉与左右齐平
        )
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
    onNavigateToInsights: () -> Unit = {},
    offlineQueueSize: Int = 0,
    // 「⏳ N 项待上传」指示器点击 → 打开离线队列冲突解决对话框（PRD-v8 §1.5）。
    onShowOfflineQueue: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 个性化欢迎卡片 —— 调 media-personalized-dashboard 一次拿问候+今日统计+每日提示。
        // 置于"我的"Tab 最顶部，null 时静默跳过（不占位）。
        // greeting 为空（后端未就绪/返回空）也跳过，避免空标题占位。
        var personalizedDashboard by remember { mutableStateOf<MediaService.PersonalizedDashboard?>(null) }
        LaunchedEffect(Unit) { personalizedDashboard = MediaService.getMediaPersonalizedDashboard() }
        personalizedDashboard?.let { pd ->
            if (pd.greeting.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 问候语大标题
                        Text(
                            pd.greeting,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 今日统计：📤 N 上传 · 🔧 N 操作（单行紧凑）
                        Text(
                            "今日：📤 ${pd.today.uploads} 上传 · 🔧 ${pd.today.actions} 操作",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        // 每日提示（有内容才展示，避免空行）
                        if (pd.tipOfDay.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "💡 ${pd.tipOfDay}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }

        // ── 离线上传队列指示器（PRD-v8 §1.5）──
        // 待传项数 > 0 时显示提示行，告知用户弱网期间积压的上传任务会在网络恢复后自动重传。
        // 点击打开 OfflineQueueDialog，列出待传项并支持逐项移除 / 全部重试 / 关闭
        // （冲突解决 UI，PRD-v8 §1.5 离线模式完整化）。
        // 用 AnimatedVisibility 做平滑进出场，避免队列清空时突兀消失。
        AnimatedVisibility(
            visible = offlineQueueSize > 0,
            enter = fadeIn(tween(280)) + expandVertically(tween(280)),
            exit = fadeOut(tween(220)) + shrinkVertically(tween(220))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowOfflineQueue() },
                shape = RoundedCornerShape(Dimens.cardCornerRadius),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⏳ $offlineQueueSize 项待上传（网络恢复后自动重传）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    // 可点击示意角标，提示用户可展开查看/解决冲突。
                    Text(
                        "查看 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "我的",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Dimens.spacingLarge)
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
        // 数据看板入口——跳转 InsightsDashboardScreen 查看全部数据洞察卡片
        MyTabItem(
            iconRes = Res.drawable.ic_cloud,
            title = "数据看板",
            subtitle = "媒体库总览、成长里程碑、存储分析、标签洞察等",
            onClick = onNavigateToInsights
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
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = Dimens.cardElevation
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
        // M3 Expressive：卡片间距统一用 Dimens.spacingSmall（4dp），口径与设计系统一致。
        contentPadding = PaddingValues(Dimens.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSmall),
        verticalItemSpacing = Dimens.spacingSmall,
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
               // M3 Expressive：网格卡片用 mediumCardShape（16dp 圆角）+ 微阴影（1dp），
               // 比原 12dp 直角更柔和；选中态阴影略高强化强调。
               elevation = if (isSelected) Dimens.selectedCardElevation else Dimens.cardElevationFlat,
               shape = mediumCardShape,
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
       shape = mediumCardShape,
       colors = CardDefaults.cardColors(
           containerColor = MaterialTheme.colorScheme.surfaceVariant
       )
   ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(if (isSelected) 2.dp else 0.dp, borderColor, mediumCardShape)
        ) {
            // 媒体缩略图
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(mediumCardShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        // M3 Expressive：加载占位统一用 L0 LoadingShimmer
                        // （surfaceVariant↔surface pulse，mediumCardShape 圆角）。
                        LoadingShimmer(modifier = Modifier.fillMaxSize())
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
                    .background(typeBadgeColor.copy(alpha = 0.85f), RoundedCornerShape(Dimens.badgeCornerRadius))
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

            // 选中状态指示器（角落勾选）—— M3 Expressive：用 pillShape 胶囊徽章
            // 替代纯圆形，与 Tab 栏选中胶囊视觉口径一致。
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .shadow(Dimens.cardElevationFlat, pillShape)
                        .background(MaterialTheme.colorScheme.primary, pillShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_check_circle),
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
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
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(Dimens.badgeCornerRadius))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
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
 * 空状态视图：每个媒体 Tab 不同文案与图标，带淡入动画。
 *
 * 5-Tab 重构后三个媒体 Tab 的索引：Tab 0 (本地图片) / Tab 1 (已上传) / Tab 3 (网盘图片)。
 * "活动"(2)和"我的"(4)不进入此处（前者渲染 RN，后者 return@Box）。
 *
 * - Tab 0 (本地图片)：图片图标 + "相册是空的" + "下拉刷新从图库加载"
 * - Tab 1 (已上传)：云上传图标 + "还没有上传过图片" + "点击右下角按钮上传"
 * - Tab 3 (网盘图片)：云图标 + "网盘暂无图片" + "下拉刷新重试"  (else 分支兜底)
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun EmptyStateView(tabIndex: Int) {
    // M3 Expressive：空态用 L0 EmptyState 组件统一视觉口径
    // （titleMedium + bodyMedium + onSurfaceVariant + Dimens 间距）。
    // 因 L0 EmptyState.icon 要求 ImageVector 而本场景用 emoji 主视觉，
    // 此处复用 EmptyState 的排版/颜色/间距规范，保留 emoji + 脉冲动画。
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
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.spacingXLarge),
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
        Spacer(modifier = Modifier.height(Dimens.spacingLarge))
        // L0 口径：titleMedium + onSurface（与 EmptyState 组件一致）
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * textAlpha),
            modifier = Modifier.graphicsLayer(alpha = textAlpha)
        )
        Spacer(modifier = Modifier.height(Dimens.spacingSmall))
        // L0 口径：bodyMedium + onSurfaceVariant（与 EmptyState 组件一致）
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * textAlpha),
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
    onBackgroundUpload: () -> Unit = {},
    onBatchDownload: () -> Unit = {},
    isDeleting: Boolean,
    isUploading: Boolean,
    showUploadButton: Boolean,
    showAddToAlbumButton: Boolean = false,
    showShareLinkButton: Boolean = false,
    showBatchRenameButton: Boolean = false,
    showBatchTagButton: Boolean = false,
    showBatchUnfavoriteButton: Boolean = false,
    showBatchRotateButton: Boolean = false,
    showBatchShareButton: Boolean = false,
    showBackgroundUploadButton: Boolean = false,
    showBatchDownloadButton: Boolean = false
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

        // V8 批量下载：调 /api/media/batch-download-urls 获取下载 URL 列表弹窗供复制，
        // 仅云端源（selectedTab != 0）显示。复用 ic_file_upload 图标（项目内对"下载"操作的既定复用，
        // 见 AlbumScreen 下载相册按钮），避免引入新 drawable。
        if (showBatchDownloadButton) {
            IconButton(onClick = onBatchDownload) {
                Icon(
                    painterResource(Res.drawable.ic_file_upload),
                    contentDescription = "批量下载"
                )
            }
        }

        if (showBackgroundUploadButton) {
            IconButton(onClick = onBackgroundUpload) {
                Icon(
                    painterResource(Res.drawable.ic_cloud_upload),
                    contentDescription = "后台上传"
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
 * V8 批量下载 URL 列表对话框。
 *
 * 由 SelectionBottomBar 的"批量下载"按钮调 [MediaService.getBatchDownloadUrls] 成功后触发，
 * 展示每个文件的直接下载 URL（/api/media/download/{id}，鉴权有效）供用户复制。
 *
 * 后端返回的 url 为相对路径（/api/media/download/{id}），此处拼接完整后端基址便于复制。
 * 采用 [AlertDialog] + 可滚动 [Column] 实现，commonMain 全平台兼容。
 *
 * @param urls 下载 URL 条目列表
 * @param onDismiss 关闭回调
 */
@Composable
private fun BatchDownloadUrlsDialog(
    urls: List<com.wgt.feature.media.MediaService.BatchDownloadUrl>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载链接（${urls.size} 项）", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "以下为直接下载 URL（需登录态有效），可复制到浏览器下载：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                urls.forEach { item ->
                    val fullUrl = com.wgt.feature.media.MediaService.buildFullDownloadUrl(item.url)
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = fullUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.size > 0) {
                        Text(
                            text = "${item.size} 字节",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
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
 * V8：批量标签对话框 — 多模式标签操作（打标签 / 批量重命名 / 合并标签 / 批量移除）。
 *
 * 模式说明：
 * - **打标签**：给当前选中的媒体批量添加输入标签（原有行为，调 [MediaService.batchAddTag]）。
 * - **批量重命名**：把所有媒体上的旧标签名重命名为新名（全局操作，不依赖选中项；
 *   调 [MediaService.batchRenameTag]，后端逐项 RenameTag，已存在 newName 则合并）。
 * - **合并标签**：把源标签的所有记录并入目标标签后删除源（全局操作；调 [MediaService.mergeTags]）。
 * - **批量移除**：从当前选中的媒体上移除输入标签（调 [MediaService.batchRemoveTags]）。
 *
 * 重命名/合并是全局标签管理操作（作用域为该标签名下的所有媒体），与选中项无关；
 * 打标签/移除作用域为选中媒体。弹出位置复用批量入口（[showBatchTagButton]）。
 *
 * @param selectedCount 当前选中媒体数（打标签/移除模式启用条件）
 * @param onDismiss 关闭回调
 * @param onAddTag 打标签模式确认（给选中媒体加标签）
 * @param onRemoveTag 移除模式确认（从选中媒体移除标签）
 * @param onSnackbar 操作结果反馈（成功/失败消息）
 */
@Composable
fun BatchTagDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onSnackbar: (String) -> Unit = {}
) {
    var mode by remember { mutableStateOf(TagActionMode.ADD) }
    var tag by remember { mutableStateOf("") }
    var targetTag by remember { mutableStateOf("") } // 重命名/合并的目标标签
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var processing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 标签自动补全（打标签/移除模式输入主标签时触发）
    LaunchedEffect(tag, mode) {
        if ((mode == TagActionMode.ADD || mode == TagActionMode.REMOVE) && tag.length >= 1) {
            suggestions = MediaService.tagAutocomplete(tag) ?: emptyList()
        } else {
            suggestions = emptyList()
        }
    }

    val title = when (mode) {
        TagActionMode.ADD -> "批量打标签"
        TagActionMode.RENAME -> "批量重命名标签"
        TagActionMode.MERGE -> "合并标签"
        TagActionMode.REMOVE -> "批量移除标签"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 模式切换条
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(TagActionMode.entries) { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = {
                                mode = m
                                tag = ""
                                targetTag = ""
                                suggestions = emptyList()
                            },
                            label = { Text(m.label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                when (mode) {
                    TagActionMode.ADD, TagActionMode.REMOVE -> {
                        Text(
                            "已选 $selectedCount 个文件",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tag,
                            onValueChange = { tag = it },
                            label = { Text("标签名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TagActionMode.RENAME -> {
                        Text(
                            "将所有媒体上的标签批量改名（全局操作）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tag,
                            onValueChange = { tag = it },
                            label = { Text("旧标签名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = targetTag,
                            onValueChange = { targetTag = it },
                            label = { Text("新标签名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TagActionMode.MERGE -> {
                        Text(
                            "把源标签并入目标标签后删除源（全局操作）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tag,
                            onValueChange = { tag = it },
                            label = { Text("源标签（将被删除）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = targetTag,
                            onValueChange = { targetTag = it },
                            label = { Text("目标标签（保留）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // V8：标签自动补全建议（仅打标签/移除主输入框）
                if (suggestions.isNotEmpty() && tag.isNotBlank() &&
                    (mode == TagActionMode.ADD || mode == TagActionMode.REMOVE)
                ) {
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

                if (processing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        TagActionMode.ADD -> {
                            onConfirm(tag.trim())
                        }
                        TagActionMode.REMOVE -> {
                            if (tag.isNotBlank() && selectedCount > 0) {
                                // 选中媒体 ID 由调用方 ViewModel 持有，本对话框经 onConfirm 传
                                // "REMOVE:<tag>" 出去，交由 ViewModel 解析后调
                                // batchRemoveTags(selectedIds, listOf(tag))。不在对话框内直接调
                                // MediaService.batchRemoveTags —— 该方法需 mediaIds 列表，而本对话框
                                // 只有 selectedCount 计数，拿不到 ID。
                                onConfirm("REMOVE:${tag.trim()}")
                            }
                        }
                        TagActionMode.RENAME -> {
                            if (tag.isNotBlank() && targetTag.isNotBlank() && tag != targetTag) {
                                processing = true
                                scope.launch {
                                    val ok = MediaService.batchRenameTag(tag.trim(), targetTag.trim())
                                    processing = false
                                    onSnackbar(if (ok) "已重命名标签" else "重命名失败")
                                    if (ok) onDismiss()
                                }
                            }
                        }
                        TagActionMode.MERGE -> {
                            if (tag.isNotBlank() && targetTag.isNotBlank() && tag != targetTag) {
                                processing = true
                                scope.launch {
                                    val ok = MediaService.mergeTags(tag.trim(), targetTag.trim())
                                    processing = false
                                    onSnackbar(if (ok) "已合并标签" else "合并失败")
                                    if (ok) onDismiss()
                                }
                            }
                        }
                    }
                },
                enabled = when (mode) {
                    TagActionMode.ADD -> tag.isNotBlank() && selectedCount > 0 && !processing
                    TagActionMode.REMOVE -> tag.isNotBlank() && selectedCount > 0 && !processing
                    TagActionMode.RENAME -> tag.isNotBlank() && targetTag.isNotBlank() &&
                        tag != targetTag && !processing
                    TagActionMode.MERGE -> tag.isNotBlank() && targetTag.isNotBlank() &&
                        tag != targetTag && !processing
                }
            ) {
                Text(
                    when (mode) {
                        TagActionMode.ADD -> "添加标签"
                        TagActionMode.REMOVE -> "移除标签"
                        TagActionMode.RENAME -> "重命名"
                        TagActionMode.MERGE -> "合并"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !processing) { Text("取消") }
        }
    )
}

/** 批量标签对话框操作模式。 */
enum class TagActionMode(val label: String) {
    ADD("打标签"),
    RENAME("重命名"),
    MERGE("合并"),
    REMOVE("移除")
}
