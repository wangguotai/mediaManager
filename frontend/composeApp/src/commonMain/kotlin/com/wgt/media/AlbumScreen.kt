package com.wgt.media

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
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
    var sortByName by remember { mutableStateOf(false) }
    // V8：批量选择模式
    var selectionMode by remember { mutableStateOf(false) }
    val selectedAlbumIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val batchScope = rememberCoroutineScope()
    // V20：智能建议区用的协程作用域（拉取建议 + 一键创建相册并批量加图）
    val suggestionScope = rememberCoroutineScope()

    // V9：置顶相册 id 集合（从后端 /api/media/album/pinned 拉取）。空集合表示无置顶
    // 或拉取失败（UI 降级为不显示置顶标记）。长按菜单据此显示"置顶/取消置顶"。
    var pinnedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 长按动作菜单：非空时弹出操作对话框（置顶/取消置顶/删除）
    var pendingActionAlbum by remember { mutableStateOf<MediaService.Album?>(null) }
    val actionScope = rememberCoroutineScope()

    // 进入列表页时拉取置顶相册 id 集合，用于渲染 📌 标记与决定长按菜单动作。
    LaunchedEffect(Unit) {
        pinnedIds = MediaService.getPinnedAlbumIds()
    }

    // 排序后的相册列表：置顶相册 (pinnedIds 命中) 优先排前，再按名称/时间原序。
    val sortedAlbums = remember(albums, sortByName, pinnedIds) {
        val base = if (sortByName) albums.sortedBy { it.name.lowercase() } else albums
        // 稳定分区：置顶在前，保持区内原序
        base.partition { it.id in pinnedIds }.let { (pinned, rest) -> pinned + rest }
    }

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
                    // V8：批量删除模式
                    if (selectionMode) {
                        TextButton(onClick = {
                            if (selectedAlbumIds.isNotEmpty()) {
                                val ids = selectedAlbumIds.toList()
                                batchScope.launch {
                                    var okCount = 0
                                    for (id in ids) {
                                        if (MediaService.pinAlbum(id)) okCount++
                                    }
                                    if (okCount > 0) {
                                        pinnedIds = MediaService.getPinnedAlbumIds()
                                        viewModel.loadAlbums(forceRefresh = true)
                                        selectionMode = false
                                        selectedAlbumIds.clear()
                                        viewModel.showErrorMessage("已置顶 $okCount/${ids.size} 个相册")
                                    } else {
                                        viewModel.showErrorMessage("置顶失败")
                                    }
                                }
                            }
                        }) {
                            Text("置顶(${selectedAlbumIds.size})")
                        }
                        TextButton(onClick = {
                            if (selectedAlbumIds.isNotEmpty()) {
                                showBatchDeleteConfirm = true
                            }
                        }) {
                            Text("删除(${selectedAlbumIds.size})", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = {
                            selectionMode = false
                            selectedAlbumIds.clear()
                        }) { Text("取消") }
                    } else {
                        // V7：排序切换按钮
                        IconButton(onClick = { sortByName = !sortByName }) {
                            Icon(
                                painterResource(Res.drawable.ic_sort),
                                contentDescription = if (sortByName) "按名称排序" else "按时间排序",
                                tint = if (sortByName) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // V7 §2.3：我的相册 / 共享相册 Tab 切换
            val sharedAlbums = viewModel.sharedAlbumList
            var selectedTab by remember { mutableStateOf(0) }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("我的相册") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.loadSharedAlbums()
                    },
                    text = { Text("共享相册 (${sharedAlbums.size})") }
                )
            }

            val displayAlbums = if (selectedTab == 0) sortedAlbums else sharedAlbums
            val displayLoading = if (selectedTab == 0) isLoading else false

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    displayAlbums.isEmpty() && displayLoading -> {
                        FullScreenLoadingAlbum()
                    }
                    displayAlbums.isEmpty() -> {
                        // 空状态
                        if (selectedTab == 0) {
                            AlbumEmptyStateWithSuggestions(
                                viewModel = viewModel,
                                suggestionScope = suggestionScope,
                                onSuggestionCreated = { viewModel.loadAlbums(forceRefresh = true) }
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("📷", fontSize = 64.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "还没有共享相册",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "其他用户共享给你的相册会显示在这里",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
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
                                items = displayAlbums,
                                key = { it.id },
                                contentType = { "album_card" }
                            ) { album ->
                                AlbumCard(
                                    album = album,
                                    isPinned = album.id in pinnedIds,
                                    onClick = {
                                        if (selectionMode) {
                                            if (selectedAlbumIds.contains(album.id)) {
                                                selectedAlbumIds.remove(album.id)
                                            } else {
                                                selectedAlbumIds.add(album.id)
                                            }
                                        } else {
                                            onAlbumClick(album)
                                        }
                                    },
                                onLongClick = {
                                    if (selectionMode) {
                                        // 批量选择途中：保持原行为，切中选
                                        if (selectedAlbumIds.contains(album.id)) {
                                            selectedAlbumIds.remove(album.id)
                                        } else {
                                            selectedAlbumIds.add(album.id)
                                        }
                                    } else {
                                        // 非批量模式：弹动作菜单（置顶/取消置顶/删除/批量选择）
                                        pendingActionAlbum = album
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // V8：批量删除确认对话框
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("批量删除相册") },
            text = { Text("确定删除选中的 ${selectedAlbumIds.size} 个相册？相册内的照片不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedAlbumIds.toList()
                    showBatchDeleteConfirm = false
                    batchScope.launch {
                        val count = MediaService.deleteAlbumsBatch(ids)
                        if (count > 0) {
                            selectionMode = false
                            selectedAlbumIds.clear()
                            viewModel.loadAlbums(forceRefresh = true)
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // V9：长按弹动作对话框 —— 置顶 / 取消置顶 / 删除 / 批量选择
    pendingActionAlbum?.let { album ->
        val isPinnedNow = album.id in pinnedIds
        AlertDialog(
            onDismissRequest = { pendingActionAlbum = null },
            title = { Text("相册操作", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("「${album.name}」", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(12.dp))
                    // 置顶 / 取消置顶
                    TextButton(
                        onClick = {
                            pendingActionAlbum = null
                            actionScope.launch {
                                val ok = if (isPinnedNow) {
                                    MediaService.unpinAlbum(album.id)
                                } else {
                                    MediaService.pinAlbum(album.id)
                                }
                                if (ok) {
                                    // 刷新置顶 id 集合；列表按 pinnedIds 重排，📌 标记随之变化
                                    pinnedIds = MediaService.getPinnedAlbumIds()
                                    viewModel.showErrorMessage(
                                        if (isPinnedNow) "已取消置顶" else "已置顶"
                                    )
                                } else {
                                    viewModel.showErrorMessage("操作失败")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isPinnedNow) "📌 取消置顶" else "📌 置顶相册")
                    }
                    // 删除单个相册（复用 pendingDeleteAlbum 流程）
                    TextButton(
                        onClick = {
                            pendingActionAlbum = null
                            onAlbumLongClick(album) // 触发上层 pendingDeleteAlbum 确认对话框
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除相册", color = MaterialTheme.colorScheme.error)
                    }
                    // 进入批量选择模式
                    TextButton(
                        onClick = {
                            pendingActionAlbum = null
                            selectionMode = true
                            selectedAlbumIds.add(album.id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("多选...")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingActionAlbum = null }) { Text("关闭") }
            }
        )
    }
}

/**
 * V20：相册列表空状态 —— 带智能建议。
 *
 * 在"还没有相册"提示下方，调 [MediaService.getAlbumSuggestions] 获取基于未分类媒体
 * 生成的推荐相册（按月份/类型/标签分组），每个建议显示名称 + 媒体数 + "创建"按钮。
 *
 * 点击"创建"：调 [MediaService.createAlbum] 建相册，再调 [MediaService.batchAddMediaToAlbum]
 * 把建议的 [MediaService.AlbumSuggestion.previewIds] 一次性加入新相册，成功后回调
 * [onSuggestionCreated]（上层刷新相册列表，空状态退出）。
 *
 * 建议拉取失败（返回 null）或为空时，仅显示原空状态提示，不渲染推荐区——
 * 避免网络异常时给用户造成"加载中"卡死观感。
 *
 * @param viewModel 媒体视图模型（用于错误提示 [MediaViewModel.showErrorMessage]）
 * @param suggestionScope 协程作用域（由 [AlbumListPage] 提供，与其生命周期一致）
 * @param onSuggestionCreated 一键创建成功后的刷新回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AlbumEmptyStateWithSuggestions(
    viewModel: MediaViewModel,
    suggestionScope: kotlinx.coroutines.CoroutineScope,
    onSuggestionCreated: () -> Unit
) {
    // suggestions: null=未加载或失败（不显示推荐区）；空 list=已加载但无建议；非空=有建议
    var suggestions by remember { mutableStateOf<List<MediaService.AlbumSuggestion>?>(null) }
    var suggestionsLoading by remember { mutableStateOf(true) }
    // 正在创建中的建议名集合，用于禁用对应"创建"按钮防重复点击
    val creatingNames = remember { mutableStateListOf<String>() }

    // 进入空状态时拉取智能建议（仅一次）。失败置 suggestions=null 静默降级。
    LaunchedEffect(Unit) {
        suggestionsLoading = true
        suggestions = try {
            MediaService.getAlbumSuggestions()
        } catch (e: Exception) {
            null
        } finally {
            suggestionsLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- 原空状态提示 ----
        Text("📷", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "还没有相册",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "点击右下角创建你的第一个相册",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        // ---- 智能推荐区 ----
        // 仅在建议非空时渲染：加载中显示小提示，失败/为空不显示。
        if (suggestionsLoading) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "正在分析推荐相册…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        } else if (!suggestions.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                "✨ 推荐创建的相册",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "基于未分类照片自动生成，点击创建即整理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 建议列表：每条一个 Card，含名称 + N 项 + 创建按钮
            suggestions!!.forEach { suggestion ->
                val isCreating = suggestion.name in creatingNames
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 类型图标：by_month 📅 / by_type 🎬 / by_tag 🏷，其余用 📁
                        Text(
                            when (suggestion.type) {
                                "by_month" -> "📅"
                                "by_type" -> "🎬"
                                "by_tag" -> "🏷️"
                                else -> "📁"
                            },
                            fontSize = 22.sp
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                suggestion.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${suggestion.mediaCount} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        TextButton(
                            onClick = {
                                if (isCreating) return@TextButton
                                creatingNames.add(suggestion.name)
                                suggestionScope.launch {
                                    try {
                                        // 1. 创建相册
                                        val album = MediaService.createAlbum(suggestion.name)
                                        if (album == null) {
                                            viewModel.showErrorMessage("创建相册失败")
                                            creatingNames.remove(suggestion.name)
                                            return@launch
                                        }
                                        // 2. 批量加入建议的预览媒体（previewIds 最多 4 个，
                                        //    但后端 batch-add 接受任意数量，这里全量加入）
                                        if (suggestion.previewIds.isNotEmpty()) {
                                            MediaService.batchAddMediaToAlbum(
                                                album.id,
                                                suggestion.previewIds
                                            )
                                        }
                                        viewModel.showErrorMessage("已创建「${album.name}」并加入 ${suggestion.previewIds.size} 项")
                                        // 3. 刷新相册列表 —— 空状态将退出，列表显示新相册
                                        onSuggestionCreated()
                                    } catch (e: Exception) {
                                        viewModel.showErrorMessage("创建失败：${e.message}")
                                    } finally {
                                        creatingNames.remove(suggestion.name)
                                    }
                                }
                            },
                            enabled = !isCreating
                        ) {
                            Text(if (isCreating) "创建中…" else "创建")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 相册卡片：封面缩略图 + 名称 + 数量徽标。已置顶时右上角显示 📌 标记。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AlbumCard(
    album: MediaService.Album,
    isPinned: Boolean = false,
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

            // V9：置顶标记 —— 右上角 📌，仅当相册已置顶时显示。
            // 背景半透明圆角胶囊，保证在任意封面上可见。
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("📌", fontSize = 13.sp)
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
    var showAddMediaDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) } // V7：长按移除目标 media id

    // V9：一键公开共享状态。null=未知（首态，按"未共享"着色），true=已公开共享，false=未共享。
    // share-toggle 为幂等翻转、无独立只读查询端点，故不在进入时探测（探测会副作用翻转状态），
    // 首次点击后再据返回值精确化。
    var isShared by remember { mutableStateOf<Boolean?>(null) }
    var shareToggleLoading by remember { mutableStateOf(false) }
    val shareToggleScope = rememberCoroutineScope()

    // V7：进入相册详情时预加载云端媒体列表（用于添加照片对话框）
    LaunchedEffect(albumId) {
        if (viewModel.cloudMedia.isEmpty() && viewModel.mediaList.isEmpty()) {
            viewModel.loadCloudMediaList()
        }
    }

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
                },
                onShare = {},
                onFavoriteToggle = { media -> viewModel.toggleFavorite(media.id) },
                isFavorite = { media -> viewModel.isFavorite(media.id) },
                albumId = albumId,
                onSetCover = { media ->
                    viewModel.setAlbumCover(albumId, media.id) { success ->
                        if (success) viewModel.showErrorMessage("已设为封面")
                        else viewModel.showErrorMessage("设置失败")
                    }
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
                    // V8：重命名相册按钮
                    var showRenameDialog by remember { mutableStateOf(false) }
                    var renameError by remember { mutableStateOf<String?>(null) }
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_edit),
                            contentDescription = "重命名相册"
                        )
                    }
                    if (showRenameDialog) {
                        var newName by remember { mutableStateOf(albumName) }
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false; renameError = null },
                            title = { Text("重命名相册") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = newName,
                                        onValueChange = { newName = it; renameError = null },
                                        label = { Text("相册名称") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    renameError?.let { e ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(e, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            confirmButton = {
                                val scope = rememberCoroutineScope()
                                TextButton(onClick = {
                                    if (newName.isBlank()) {
                                        renameError = "名称不能为空"
                                        return@TextButton
                                    }
                                    scope.launch {
                                        if (MediaService.renameAlbum(albumId, newName.trim())) {
                                            showRenameDialog = false
                                        } else {
                                            renameError = "重命名失败"
                                        }
                                    }
                                }) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false; renameError = null }) { Text("取消") }
                            }
                        )
                    }
                    // V8：复制相册按钮
                    var showCloneDialog by remember { mutableStateOf(false) }
                    var cloneError by remember { mutableStateOf<String?>(null) }
                    val cloneScope = rememberCoroutineScope()
                    IconButton(onClick = { showCloneDialog = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_copy),
                            contentDescription = "复制相册"
                        )
                    }
                    if (showCloneDialog) {
                        var cloneName by remember { mutableStateOf("$albumName (副本)") }
                        AlertDialog(
                            onDismissRequest = { showCloneDialog = false; cloneError = null },
                            title = { Text("复制相册") },
                            text = {
                                Column {
                                    Text("将创建一个新相册并复制所有照片")
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = cloneName,
                                        onValueChange = { cloneName = it; cloneError = null },
                                        label = { Text("新相册名称") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    cloneError?.let { e ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(e, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (cloneName.isBlank()) {
                                        cloneError = "名称不能为空"
                                        return@TextButton
                                    }
                                    cloneScope.launch {
                                        val newId = MediaService.cloneAlbum(albumId, cloneName.trim())
                                        if (newId != null) {
                                            showCloneDialog = false
                                            onBack()
                                        } else {
                                            cloneError = "复制失败"
                                        }
                                    }
                                }) { Text("复制") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCloneDialog = false; cloneError = null }) { Text("取消") }
                            }
                        )
                    }
                    // V8：排序下拉菜单（按日期↓ / 按日期↑）
                    val sortScope = rememberCoroutineScope()
                    var showSortMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            painterResource(Res.drawable.ic_sort),
                            contentDescription = "排序"
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("按日期 ↓（新→旧）") },
                            onClick = {
                                showSortMenu = false
                                sortScope.launch {
                                    if (MediaService.sortAlbumByDate(albumId, "desc")) {
                                        viewModel.loadAlbumDetail(albumId)
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("按日期 ↑（旧→新）") },
                            onClick = {
                                showSortMenu = false
                                sortScope.launch {
                                    if (MediaService.sortAlbumByDate(albumId, "asc")) {
                                        viewModel.loadAlbumDetail(albumId)
                                    }
                                }
                            }
                        )
                    }
                    // V9 §一键共享切换：一键翻转相册公开共享状态。
                    // isShared==true 显示"取消共享"(primary tint)；否则显示"共享"(灰色)。
                    // 点击调 MediaService.toggleAlbumShare，成功后刷新 isShared 并提示。
                    val sharedNow = isShared == true
                    // V10：下载整个相册按钮。
                    // 后端 GET /api/media/album/download?album_id=xxx 返回 ZIP 下载流。
                    // KMP 跨端打开浏览器/系统下载需平台特定实现（expected/actual），
                    // 此处仅用 Snackbar 提示"下载链接已生成"，避免引入跨端依赖与超出本文件改动范围。
                    val downloadScope = rememberCoroutineScope()
                    IconButton(onClick = {
                        downloadScope.launch {
                            viewModel.showErrorMessage("下载链接已生成：/api/media/album/download?album_id=$albumId")
                        }
                    }) {
                        Icon(
                            painterResource(Res.drawable.ic_file_upload),
                            contentDescription = "下载相册"
                        )
                    }
                    // V11：智能选封面按钮 🎯 — 调 cover-auto-pick，成功后 Snackbar 提示并刷新列表。
                    var coverPicking by remember { mutableStateOf(false) }
                    val coverPickScope = rememberCoroutineScope()
                    IconButton(
                        onClick = {
                            if (coverPicking) return@IconButton
                            coverPicking = true
                            coverPickScope.launch {
                                val result = MediaService.autoPickAlbumCover(albumId)
                                coverPicking = false
                                if (result != null) {
                                    viewModel.showErrorMessage("已智能选择封面")
                                    // 刷新相册列表与当前详情，使新封面立即可见
                                    viewModel.loadAlbums(forceRefresh = true)
                                    viewModel.loadAlbumDetail(albumId)
                                } else {
                                    viewModel.showErrorMessage("智能选封面失败")
                                }
                            }
                        },
                        enabled = !coverPicking
                    ) {
                        if (coverPicking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("🎯", fontSize = 20.sp)
                        }
                    }
                    // V12：给相册打标签按钮 🏷️ — 弹出对话框输入标签名，
                    // 调 batch-tag-album 给整个相册所有媒体批量打标签，成功后 Snackbar 提示数量。
                    var showTagAlbumDialog by remember { mutableStateOf(false) }
                    var tagAlbumLoading by remember { mutableStateOf(false) }
                    val tagAlbumScope = rememberCoroutineScope()
                    IconButton(
                        onClick = { showTagAlbumDialog = true },
                        enabled = !tagAlbumLoading
                    ) {
                        if (tagAlbumLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("🏷️", fontSize = 20.sp)
                        }
                    }
                    if (showTagAlbumDialog) {
                        var tagName by remember { mutableStateOf("") }
                        var tagError by remember { mutableStateOf<String?>(null) }
                        AlertDialog(
                            onDismissRequest = {
                                if (!tagAlbumLoading) { showTagAlbumDialog = false; tagError = null }
                            },
                            title = { Text("给相册打标签") },
                            text = {
                                Column {
                                    Text(
                                        "将为相册 \"$albumName\" 内所有媒体添加该标签。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = tagName,
                                        onValueChange = { tagName = it; tagError = null },
                                        label = { Text("标签名") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    tagError?.let { e ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(e, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !tagAlbumLoading && tagName.trim().isNotEmpty(),
                                    onClick = {
                                        val trimmed = tagName.trim()
                                        if (trimmed.isEmpty()) {
                                            tagError = "标签名不能为空"
                                            return@TextButton
                                        }
                                        if (tagAlbumLoading) return@TextButton
                                        tagAlbumLoading = true
                                        tagAlbumScope.launch {
                                            val count = MediaService.batchTagAlbum(albumId, trimmed)
                                            tagAlbumLoading = false
                                            if (count != null) {
                                                showTagAlbumDialog = false
                                                viewModel.showErrorMessage("已为 $count 项媒体添加标签")
                                                // 打标签不改变相册封面/内容，无需 loadAlbums/loadAlbumDetail；
                                                // 但刷新详情可让后续操作看到最新标签态（保守刷新）。
                                            } else {
                                                tagError = "打标签失败，请稍后重试"
                                            }
                                        }
                                    }
                                ) {
                                    if (tagAlbumLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("确定")
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !tagAlbumLoading,
                                    onClick = { showTagAlbumDialog = false; tagError = null }
                                ) { Text("取消") }
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            if (shareToggleLoading) return@IconButton
                            shareToggleLoading = true
                            shareToggleScope.launch {
                                val result = MediaService.toggleAlbumShare(albumId)
                                shareToggleLoading = false
                                if (result != null) {
                                    val (shared, url) = result
                                    isShared = shared
                                    viewModel.showErrorMessage(
                                        if (shared) {
                                            if (!url.isNullOrEmpty()) "已开启共享：$url"
                                            else "已开启共享"
                                        } else {
                                            "已取消共享"
                                        }
                                    )
                                } else {
                                    viewModel.showErrorMessage("共享切换失败")
                                }
                            }
                        },
                        enabled = !shareToggleLoading
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_share),
                            contentDescription = if (sharedNow) "取消共享" else "共享",
                            tint = if (sharedNow) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
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
        },
        // V7：相册详情页 FAB — 添加照片
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddMediaDialog = true }) {
                Icon(
                    painterResource(Res.drawable.ic_file_upload),
                    contentDescription = "添加照片"
                )
            }
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
                        Spacer(modifier = Modifier.height(16.dp))
                        AssistChip(
                            onClick = { showAddMediaDialog = true },
                            label = { Text("添加照片") },
                            leadingIcon = {
                                Icon(
                                    painterResource(Res.drawable.ic_file_upload),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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
                            removeTarget = media.id
                        },
                        useBackendLoader = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // V7：长按照片 → 从相册移除确认对话框
        removeTarget?.let { mediaId ->
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("从相册移除") },
                text = { Text("确定将此照片从 \"$albumName\" 相册中移除？照片本身不会被删除。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeFromAlbum(albumId, mediaId) { success ->
                            if (success) {
                                viewModel.loadAlbumDetail(albumId)
                            }
                        }
                        removeTarget = null
                    }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { removeTarget = null }) { Text("取消") }
                }
            )
        }

        // V7：添加照片到相册对话框
        if (showAddMediaDialog) {
            AddMediaToAlbumDialog(
                viewModel = viewModel,
                albumId = albumId,
                existingMediaIds = mediaList.map { it.id }.toSet(),
                onDismiss = { showAddMediaDialog = false },
                onAdded = {
                    showAddMediaDialog = false
                    viewModel.loadAlbumDetail(albumId)
                }
            )
        }
    }
}

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

/**
 * V7：添加照片到相册对话框
 *
 * 列出云端媒体（排除已在相册中的），多选后批量调 addMediaToAlbum。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun AddMediaToAlbumDialog(
    viewModel: MediaViewModel,
    albumId: String,
    existingMediaIds: Set<String>,
    onDismiss: () -> Unit,
    onAdded: () -> Unit
) {
    val cloudMedia = viewModel.cloudMedia.ifEmpty { viewModel.mediaList }
    val available = cloudMedia.filter { it.id !in existingMediaIds }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加照片到相册") },
        text = {
            Column {
                if (available.isEmpty()) {
                    Text("没有可添加的照片（所有云端媒体已在此相册中）")
                } else {
                    Text("可选 ${available.size} 张照片", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(available, key = { it.id }) { media ->
                            val isSelected = media.id in selected
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelected) selected.remove(media.id)
                                            else selected.add(media.id)
                                        }
                                    )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(Res.drawable.ic_photo),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "✓",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    // V7：改用批量端点（一次请求完成）
                    val ids = selected.toList()
                    viewModel.batchAddMediaToAlbum(albumId, ids) { added ->
                        if (added != null) {
                            onAdded()
                        }
                    }
                    selected.clear()
                }
            ) {
                Text("添加 ${selected.size} 张")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
