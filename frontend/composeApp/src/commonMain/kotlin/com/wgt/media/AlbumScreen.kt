package com.wgt.media

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.MediaSource
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata
import media.MediaType
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 相册屏幕 —— 支持两层视图：
 *
 * 1. **相册列表页**：网格展示所有相册卡片（封面 + 名称 + 数量），点击进入详情；
 *    右下 FAB 打开新建相册对话框（AlertDialog 输入名称）。
 * 2. **相册详情页**：展示相册内媒体网格，顶栏带返回按钮与相册名称。
 *
 * 状态由 [MediaViewModel] 统一持有（albumList / albumDetailMedia 等），
 * 本 Composable 仅负责 UI 渲染与事件回调。
 *
 * @param viewModel 媒体视图模型（提供相册数据与操作）
 * @param onBack 返回到主界面的回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun AlbumScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    // null=列表页；非空=详情页（值为相册 id）
    var detailAlbumId by remember { mutableStateOf<String?>(null) }
    var detailAlbumName by remember { mutableStateOf("") }
    // 长按删除确认：非空时弹出确认对话框
    var pendingDeleteAlbum by remember { mutableStateOf<MediaService.Album?>(null) }

    // 监听错误信息
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 进入屏幕时加载相册列表
    LaunchedEffect(Unit) {
        viewModel.loadAlbums()
    }

    // 新建相册对话框
    if (showCreateDialog) {
        CreateAlbumDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createAlbum(name)
            }
        )
    }

    // 删除相册确认对话框
    pendingDeleteAlbum?.let { album ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAlbum = null },
            title = { Text("删除相册", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除相册「${album.name}」吗？相册内的媒体不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAlbum(album.id)
                        pendingDeleteAlbum = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAlbum = null }) {
                    Text("取消")
                }
            }
        )
    }

    Crossfade(
        targetState = detailAlbumId,
        animationSpec = tween(280),
        label = "albumScreenSwitch"
    ) { albumId ->
        if (albumId == null) {
            // ---- 相册列表页 ----
            AlbumListPage(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = onBack,
                onCreateAlbum = { showCreateDialog = true },
                onAlbumClick = { album ->
                    detailAlbumId = album.id
                    detailAlbumName = album.name
                    viewModel.loadAlbumDetail(album.id)
                },
                onAlbumLongClick = { album -> pendingDeleteAlbum = album }
            )
        } else {
            // ---- 相册详情页 ----
            AlbumDetailPage(
                viewModel = viewModel,
                albumId = albumId,
                albumName = detailAlbumName,
                onBack = { detailAlbumId = null }
            )
        }
    }
}

// ---- 相册列表页 ----

/**
 * 相册列表页：网格展示相册卡片。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
private fun AlbumListPage(
    viewModel: MediaViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onCreateAlbum: () -> Unit,
    onAlbumClick: (MediaService.Album) -> Unit,
    onAlbumLongClick: (MediaService.Album) -> Unit
) {
    val albums = viewModel.albumList
    val isLoading = viewModel.isAlbumLoading

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("相册", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadAlbums(forceRefresh = true) },
                        enabled = !isLoading
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_refresh),
                            contentDescription = "刷新"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateAlbum) {
                Icon(
                    painterResource(Res.drawable.ic_file_upload),
                    contentDescription = "新建相册"
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                albums.isEmpty() && isLoading -> {
                    FullScreenLoadingAlbum()
                }
                albums.isEmpty() -> {
                    // 空状态
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_photo),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无相册",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "点击右下角按钮创建相册",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = albums,
                            key = { it.id },
                            contentType = { "album_card" }
                        ) { album ->
                            AlbumCard(
                                album = album,
                                onClick = { onAlbumClick(album) },
                                onLongClick = { onAlbumLongClick(album) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 相册卡片：封面缩略图 + 名称 + 数量徽标。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AlbumCard(
    album: MediaService.Album,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    // 封面缩略图状态
    var coverBitmap by remember(album.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoadingCover by remember(album.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // 异步加载封面缩略图：优先用 coverMediaId；若为空但相册有媒体（mediaCount>0），
    // 则拉取相册首张媒体作为封面 fallback，避免空白占位。
    LaunchedEffect(album.id, album.coverMediaId) {
        val coverId = album.coverMediaId
        if (coverId != null) {
            scope.launch(dispatchers.io) {
                try {
                    val bmp = BackendImageLoader.loadThumbnail(coverId)
                    coverBitmap = bmp
                } catch (e: Exception) {
                    // 静默
                } finally {
                    isLoadingCover = false
                }
            }
        } else if (album.mediaCount > 0) {
            // coverMediaId 为空但相册有媒体：拉取首张媒体作为封面 fallback。
            scope.launch(dispatchers.io) {
                try {
                    val media = MediaService.getMediaList(source = MediaSource.BACKEND)
                    val first = media.firstOrNull()
                    if (first != null) {
                        val bmp = BackendImageLoader.loadThumbnail(first.id)
                        coverBitmap = bmp
                    }
                } catch (e: Exception) {
                    // 静默
                } finally {
                    isLoadingCover = false
                }
            }
        } else {
            isLoadingCover = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
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
            modifier = Modifier.fillMaxSize()
        ) {
            // 封面图或占位
            if (coverBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverBitmap!!,
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 无封面占位：大图标居中
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_photo),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            // 底部渐变信息条
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(8.dp)
            ) {
                Text(
                    album.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${album.mediaCount} 项",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ---- 相册详情页 ----

/**
 * 相册详情页：展示相册内媒体网格。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
private fun AlbumDetailPage(
    viewModel: MediaViewModel,
    albumId: String,
    albumName: String,
    onBack: () -> Unit
) {
    val mediaList = viewModel.albumDetailMedia
    val isLoading = viewModel.isAlbumDetailLoading
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // 图片预览
    previewIndex?.let { index ->
        if (index in mediaList.indices) {
            ImagePreviewDialog(
                mediaList = mediaList,
                initialIndex = index,
                useBackendLoader = true,
                sourceLabel = albumName,
                onDismiss = { previewIndex = null },
                onEdit = {},
                onDelete = { media ->
                    previewIndex = null
                    viewModel.deleteSingleMedia(media.id)
                }
            )
        } else {
            previewIndex = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        albumName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "返回相册列表"
                        )
                    }
                },
                actions = {
                    // V7 §2.3：分享相册按钮
                    var showShareDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showShareDialog = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_share),
                            contentDescription = "共享相册"
                        )
                    }
                    if (showShareDialog) {
                        var shareError by remember { mutableStateOf<String?>(null) }
                        ShareAlbumDialog(
                            onDismiss = { showShareDialog = false },
                            onShare = { username ->
                                viewModel.shareAlbum(
                                    albumId = albumId,
                                    username = username,
                                    onSuccess = { showShareDialog = false },
                                    onError = { msg ->
                                        shareError = msg
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                mediaList.isEmpty() && isLoading -> {
                    FullScreenLoadingAlbum()
                }
                mediaList.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_image_placeholder),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "相册暂无内容",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    // 复用 MediaGrid 展示相册内媒体
                    MediaGrid(
                        mediaList = mediaList,
                        selectedMediaIds = emptyList(),
                        onMediaClick = { media ->
                            if (media.type == MediaType.VIDEO) {
                                // 视频不在此页播放，简单处理为预览
                                previewIndex = mediaList.indexOf(media)
                            } else {
                                previewIndex = mediaList.indexOf(media)
                            }
                        },
                        onMediaLongClick = { media ->
                            previewIndex = mediaList.indexOf(media)
                        },
                        useBackendLoader = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ---- 新建相册对话框 ----

/**
 * 新建相册对话框：输入相册名称，确认后创建。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun CreateAlbumDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建相册") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("输入相册名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
                enabled = name.trim().isNotEmpty()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---- V7 §2.3 共享相册对话框 ----

/**
 * 共享相册对话框：输入用户名邀请共享。
 *
 * @param onDismiss 取消
 * @param onShare 确认共享，传入被邀请用户名
 */
@Composable
private fun ShareAlbumDialog(
    onDismiss: () -> Unit,
    onShare: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("共享相册") },
        text = {
            Column {
                Text(
                    "输入要邀请的用户名，共享后对方可查看和添加图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onShare(username.trim()) },
                enabled = username.trim().isNotEmpty()
            ) { Text("共享") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---- 加载占位 ----

/**
 * 全屏加载占位（相册专用，避免与 MediaListScreen 的 FullScreenLoading 耦合）。
 */
@Composable
private fun FullScreenLoadingAlbum() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
