package com.wgt.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wgt.feature.media.MediaService
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * 回收站页面（V7 §1.1 前端）。
 *
 * 功能：
 * - 拉取已软删 media 列表
 * - 多选 + 恢复（POST /api/media/restore）
 * - 多选 + 彻底删除（POST /api/media/purge）
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MediaService.TrashItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // V8：搜索过滤 + 排序 state
    var searchText by remember { mutableStateOf("") }
    // 排序模式：true=按删除时间(updatedAt) DESC，false=按文件名升序
    var sortByDeletedTime by remember { mutableStateOf(true) }

    // 拉取回收站列表
    LaunchedEffect(Unit) {
        loading = true
        items = MediaService.getTrash()
        loading = false
    }

    // 恢复选中项
    fun restore() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            val count = MediaService.restoreMedia(ids)
            snackbarMsg = "已恢复 $count 项"
            items = MediaService.getTrash()
            selectedIds = emptySet()
        }
    }

    // 彻底删除选中项
    fun purge() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            val count = MediaService.purgeMedia(ids)
            snackbarMsg = "已彻底删除 $count 项"
            items = MediaService.getTrash()
            selectedIds = emptySet()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        TextButton(onClick = { restore() }) { Text("恢复") }
                        TextButton(onClick = { purge() }) { Text("彻底删除") }
                    } else if (items.isNotEmpty()) {
                        // V7：恢复全部
                        TextButton(onClick = {
                            scope.launch {
                                val count = MediaService.restoreMedia(items.map { it.id })
                                snackbarMsg = "已恢复 $count 项"
                                items = emptyList()
                            }
                        }) { Text("恢复全部") }
                        // V8：清空回收站（改用 empty-trash 端点，无需传 id 列表）
                        TextButton(onClick = {
                            scope.launch {
                                val count = MediaService.emptyTrash()
                                snackbarMsg = "已清空 $count 项"
                                items = emptyList()
                            }
                        }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                items.isEmpty() -> Text(
                    "回收站为空",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    // V8：过滤 + 排序后的列表
                    val filteredItems = remember(items, searchText, sortByDeletedTime) {
                        val filtered = if (searchText.isBlank()) {
                            items
                        } else {
                            items.filter { it.filename.contains(searchText, ignoreCase = true) }
                        }
                        if (sortByDeletedTime) {
                            filtered.sortedByDescending { it.updatedAt }
                        } else {
                            filtered.sortedBy { it.filename }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // V8：搜索栏 + 排序切换
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                singleLine = true,
                                placeholder = { Text("搜索文件名") },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { sortByDeletedTime = !sortByDeletedTime }) {
                                Icon(
                                    painterResource(Res.drawable.ic_sort),
                                    contentDescription = if (sortByDeletedTime) "当前：按删除时间，点击切到文件名" else "当前：按文件名，点击切到删除时间"
                                )
                            }
                        }
                        // V8：计数提示（搜索时显示过滤后数量）
                        if (searchText.isNotBlank()) {
                            Text(
                                "共 ${items.size} 项（过滤后 ${filteredItems.size} 项）",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            items(filteredItems, key = { it.id }) { item ->
                                val isSelected = item.id in selectedIds
                                ListItem(
                                    headlineContent = { Text(item.filename) },
                                    supportingContent = {
                                        Text("${formatSize(item.size)} · ${item.type}")
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) {
                                                    selectedIds + item.id
                                                } else {
                                                    selectedIds - item.id
                                                }
                                            }
                                        )
                                    },
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    LaunchedEffect(snackbarMsg) {
                        snackbarMsg?.let {
                            snackbar.showSnackbar(it)
                            snackbarMsg = null
                        }
                    }
                }
            }
        }
    }
}

/** 格式化文件大小。 */
private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        bytes < 1024 -> "${bytes}B"
        kb < 1024 -> "${kb.toInt()}." + ((kb * 10).toInt() % 10) + "KB"
        mb < 1024 -> "${mb.toInt()}." + ((mb * 10).toInt() % 10) + "MB"
        else -> "${gb.toInt()}." + ((gb * 10).toInt() % 10) + "GB"
    }
}
