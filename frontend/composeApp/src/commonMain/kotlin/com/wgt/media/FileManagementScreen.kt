package com.wgt.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wgt.common.util.formatBytesToMB
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import mediamanager.composeapp.generated.resources.ic_close
import kotlinx.coroutines.flow.distinctUntilChanged
import media.MediaMetadata
import media.MediaType
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 文件管理页：列出云端媒体、筛选排序、批量删除、查看占用空间。
 *
 * 设计要点：
 * - 列表数据来自 [SyncManager.pullChanges]（云端增量），不下载到本地、不落盘。
 * - 删除复用现有 [MediaViewModel.deleteSelectedMedia]：删除前以 [MediaViewModel.setCurrentSourceBackend]
 *   把来源固定为云端分支（避免被当作本地相册 id 误删），再注入选中集合并触发批量删除，
 *   删除成功后从本页本地列表移除对应条目（viewModel.mediaList 与本页列表是分离的，互不影响）。
 * - 用量来自 [MediaService.getSyncUsage]，仅展示总量，失败时回退本地计数。
 * - 纯 Kotlin / commonMain，无 java/android 平台依赖。
 *
 * @param viewModel 主视图模型，复用其删除能力
 * @param onBack 返回上一级
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalResourceApi::class)
@Composable
fun FileManagementScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit
) {
    // 云端媒体列表（来自 SyncManager.pullChanges，纯展示，不落盘）
    var mediaList by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 用量信息
    var usage by remember { mutableStateOf<MediaService.SyncUsage?>(null) }

    // 筛选 / 排序维度
    var typeFilter by remember { mutableStateOf(UsageTypeFilter.ALL) }
    var sortOrder by remember { mutableStateOf(UsageSortOrder.DATE_DESC) }

    // 多选
    val selectedIds = remember { mutableStateListOf<String>() }
    val inSelectionMode = selectedIds.isNotEmpty()
    var isDeleting by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }

    // —— 拉取云端变更 ——
    LaunchedEffect(Unit) {
        loading = true
        try {
            val items = SyncManager.pullChanges()
            mediaList = items
            errorMessage = if (items.isEmpty()) "云端暂无媒体" else null
        } catch (e: Exception) {
            errorMessage = "加载失败: ${e.message}"
        } finally {
            loading = false
        }
        // 同时拉用量
        usage = try { MediaService.getSyncUsage() } catch (_: Exception) { null }
    }

    // —— 经筛选 + 排序后的展示列表 ——
    val visibleList by remember(mediaList, typeFilter, sortOrder) {
        derivedStateOf {
            val filtered = when (typeFilter) {
                UsageTypeFilter.ALL -> mediaList
                UsageTypeFilter.IMAGE -> mediaList.filter {
                    it.type == MediaType.IMAGE || it.type == MediaType.LIVE_PHOTO
                }
                UsageTypeFilter.VIDEO -> mediaList.filter { it.type == MediaType.VIDEO }
            }
            when (sortOrder) {
                UsageSortOrder.DATE_DESC -> filtered.sortedByDescending { it.created_at }
                UsageSortOrder.DATE_ASC -> filtered.sortedBy { it.created_at }
                UsageSortOrder.SIZE_DESC -> filtered.sortedByDescending { it.size }
                UsageSortOrder.SIZE_ASC -> filtered.sortedBy { it.size }
            }
        }
    }

    // —— 批量删除：复用 viewModel.deleteSelectedMedia ——
    // viewModel 删除完成后会把结果写入其 errorMessage（"已删除 N 项" / "删除媒体失败: ..."）。
    // 本页通过 snapshotFlow 监听该消息：发起删除时记录待删快照，消息变化且正处于删除中时，
    // 成功则从本页列表移除快照中的 id、失败则保留列表，统一清选中态与删除中标志。
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }

    fun toggleSelect(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty() || isDeleting) return
        // 固定走云端删除分支，再把本页选中集注入 viewModel 选中态后触发其批量删除。
        viewModel.setCurrentSourceBackend()
        viewModel.deselectAll()
        selectedIds.forEach { viewModel.toggleMediaSelection(it) }
        pendingDelete = selectedIds.toList()
        isDeleting = true
        viewModel.deleteSelectedMedia()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.errorMessage }
            .distinctUntilChanged()
            .collect { msg ->
                if (msg == null || !isDeleting) return@collect
                if (msg.startsWith("已删除")) {
                    mediaList = mediaList.filter { it.id !in pendingDelete.toSet() }
                    selectedIds.clear()
                    isDeleting = false
                    pendingDelete = emptyList()
                    snack = msg
                } else if (msg.contains("删除失败") || msg.contains("删除媒体失败")) {
                    selectedIds.clear()
                    isDeleting = false
                    pendingDelete = emptyList()
                    snack = msg
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inSelectionMode) "已选择 ${selectedIds.size} 项" else "文件管理",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (inSelectionMode) selectedIds.clear() else onBack()
                    }) {
                        Icon(
                            painterResource(
                                if (inSelectionMode) Res.drawable.ic_close else Res.drawable.ic_arrow_back
                            ),
                            contentDescription = if (inSelectionMode) "退出选择" else "返回"
                        )
                    }
                },
                actions = {
                    if (inSelectionMode) {
                        TextButton(
                            onClick = {
                                selectedIds.clear()
                                viewModel.deselectAll()
                            },
                            enabled = !isDeleting
                        ) { Text("取消") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { deleteSelected() },
                            enabled = !isDeleting
                        ) { Text(if (isDeleting) "删除中…" else "删除") }
                    } else if (mediaList.isNotEmpty()) {
                        TextButton(onClick = {
                            if (selectedIds.toSet() == visibleList.map { it.id }.toSet()) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(visibleList.map { it.id })
                            }
                        }) { Text("全选") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 用量信息条：展示云端占用总量；getSyncUsage 失败时回退本地计数。
            UsageSummaryRow(
                usageBytes = usage?.totalBytes,
                fileCount = usage?.fileCount ?: mediaList.size,
                listBytes = mediaList.sumOf { it.size }
            )

            // 筛选 + 排序工具条
            FilterSortBar(
                typeFilter = typeFilter,
                onTypeChange = { typeFilter = it },
                sortOrder = sortOrder,
                onSortChange = { sortOrder = it }
            )

            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null && mediaList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            errorMessage ?: "无数据",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleList, key = { it.id }) { media ->
                            FileManagementItem(
                                media = media,
                                selected = selectedIds.contains(media.id),
                                inSelectionMode = inSelectionMode,
                                onClick = {
                                    if (inSelectionMode) toggleSelect(media.id)
                                },
                                onLongClick = { if (!selectedIds.contains(media.id)) selectedIds.add(media.id) }
                            )
                        }
                    }
                }
            }

            // 简易消息提示：列于内容底部，点击即隐。轻量实现，不引入 Snackbar 宿主依赖。
            snack?.let { msg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { snack = null },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 6.dp
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }
    }
}

/**
 * 云端占用用量摘要行。
 *
 * @param usageBytes 后端 [MediaService.getSyncUsage] 返回的总量（bytes）；null 表示后端不可用，
 *                   回退展示本页列表累计大小。
 * @param fileCount  文件数：优先用后端计数，缺失时用本页列表条目数。
 * @param listBytes  本页列表累计大小，仅在后端用量不可用时作为回退来源。
 */
@Composable
private fun UsageSummaryRow(
    usageBytes: Long?,
    fileCount: Int,
    listBytes: Long
) {
    val displayBytes = usageBytes ?: listBytes
    val total = formatBytesToMB(displayBytes.toDouble())
    val source = if (usageBytes != null) "云端统计" else "本页合计"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "云端占用",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "$fileCount 个文件 · $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "$total MB",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 筛选 + 排序工具条：类型 FilterChip 行 + 排序下拉文本按钮。
 */
@Composable
private fun FilterSortBar(
    typeFilter: UsageTypeFilter,
    onTypeChange: (UsageTypeFilter) -> Unit,
    sortOrder: UsageSortOrder,
    onSortChange: (UsageSortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UsageTypeFilter.entries.forEach { t ->
            FilterChip(
                selected = typeFilter == t,
                onClick = { onTypeChange(t) },
                label = { Text(t.label) }
            )
        }
        Spacer(Modifier.weight(1f))
        // 排序：点击在四种顺序间循环切换（保持简单，不引入下拉菜单依赖）
        TextButton(onClick = {
            onSortChange(UsageSortOrder.entries.let { all ->
                all[(all.indexOf(sortOrder) + 1) % all.size]
            })
        }) {
            Text("排序: ${sortOrder.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 单条云端文件项：文件名 + 类型/大小/时间，长按进入多选，选中态高亮左核 + 勾选圆点。
 */
@Composable
private fun FileManagementItem(
    media: MediaMetadata,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics {
                role = Role.Button
                contentDescription = media.filename
            },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选中圆点 / 类型占位圆
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    typeInitial(media.type),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    media.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${typeLabel(media.type)} · ${formatBytesToMB(media.size.toDouble())} MB · ${
                        formatEpochMillis(media.created_at)
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (inSelectionMode) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (selected) "✓" else "",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** 类型 → 简短标签。 */
private fun typeLabel(type: MediaType): String = when (type) {
    MediaType.IMAGE -> "图片"
    MediaType.LIVE_PHOTO -> "动态照片"
    MediaType.VIDEO -> "视频"
}

/** 类型 → 圆点首字母占位。 */
private fun typeInitial(type: MediaType): String = when (type) {
    MediaType.IMAGE -> "图"
    MediaType.LIVE_PHOTO -> "动"
    MediaType.VIDEO -> "视"
}

/**
 * 把 epoch 毫秒格式化为 YYYY-MM-DD（纯 Kotlin，不依赖 java.time）。
 *
 * 用 UTC 日历字段计算，避免引入平台日期 API；精度到日，用于列表项时间展示。
 */
private fun formatEpochMillis(millis: Long): String {
    if (millis <= 0L) return "—"
    val seconds = millis / 1000
    val day = seconds / 86_400
    val rem = seconds % 86_400
    val hour = rem / 3_600
    val minute = (rem % 3_600) / 60
    // 1970-01-01 起算的 civil date（Howard Hinnant 算法）
    val z = day + 719_468
    val era = if (z >= 0) z / 146_097 else (z - 146_096) / 146_097
    val doe = (z - era * 146_097).toInt() // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    val year = if (m <= 2) y + 1 else y
    fun p(n: Int) = if (n < 10) "0$n" else "$n"
    return "$year-${p(m)}-${p(d)} ${p(hour.toInt())}:${p(minute.toInt())}"
}

/** 类型筛选维度（文件管理页专用，与主列表 MediaTypeFilter 解耦，保持简单）。 */
private enum class UsageTypeFilter(val label: String) {
    ALL("全部"), IMAGE("图片"), VIDEO("视频")
}

/** 排序维度：按时间或大小，升降序各一。 */
private enum class UsageSortOrder(val label: String) {
    DATE_DESC("时间↓"), DATE_ASC("时间↑"), SIZE_DESC("大小↓"), SIZE_ASC("大小↑")
}
