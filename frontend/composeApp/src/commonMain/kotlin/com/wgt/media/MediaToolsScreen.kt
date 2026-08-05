package com.wgt.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.wgt.feature.media.MediaService
import com.wgt.media.ui.CardScaffold
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SettingsRow
import com.wgt.platform.logger.logger
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 媒体工具页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「媒体维护」相关离散入口与工具操作：
 * - 回收站入口（→ onNavigateToTrash）
 * - 孤立文件检查 + 一键清理
 * - 自动打标签
 * - 存储清理入口（→ onNavigateToCleanup）
 * - RN 活动中心入口（→ onNavigateToRnActivity）
 *
 * 用 L0 [SettingsRow] 做入口行、[CardScaffold] 包裹带状态的检查结果区，替代原内联 `Row + TextButton`。
 *
 * @param onBack 返回设置枢纽页
 * @param viewModel 备用（保留口径，当前工具操作直接走 [MediaService] 静态方法）
 * @param onNavigateToTrash 跳转回收站页
 * @param onNavigateToCleanup 跳转存储清理页
 * @param onNavigateToRnActivity 跳转 RN 活动中心页
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaToolsScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel,
    onNavigateToTrash: () -> Unit = {},
    onNavigateToCleanup: () -> Unit = {},
    onNavigateToRnActivity: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 孤立文件检查状态
    var orphanResult by remember { mutableStateOf<MediaService.OrphanCheckResult?>(null) }
    var orphanChecking by remember { mutableStateOf(false) }
    // 自动打标签状态
    var autoTagResult by remember { mutableStateOf<String?>(null) }
    var autoTagging by remember { mutableStateOf(false) }

    // 标签管理状态
    var tagStats by remember { mutableStateOf<List<MediaService.TagStat>?>(null) }
    var tagStatsLoading by remember { mutableStateOf(false) }
    var tagStatsError by remember { mutableStateOf<String?>(null) }
    // 重命名 Dialog
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    // 删除确认 Dialog
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    // 清理无用标签
    var cleaningUp by remember { mutableStateOf(false) }
    // 智能合并相似标签（中英映射 / 简繁 / 大小写）
    var merging by remember { mutableStateOf(false) }
    var mergeResult by remember { mutableStateOf<MediaService.MergeSmartResult?>(null) }
    // 导出 / 导入 Dialog
    var exportDialogVisible by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var importDialogVisible by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    // 标签搜索状态
    var searchTag by remember { mutableStateOf<String?>(null) }
    var searchResult by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // 进入标签区段自动拉取一次统计
    LaunchedEffect(Unit) {
        tagStatsLoading = true
        tagStats = MediaService.getTagStats()
        tagStatsLoading = false
        if (tagStats == null) tagStatsError = "加载标签统计失败"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "媒体工具",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader("入口", icon = null)

            // 回收站入口（V7 §1.1）
            SettingsRow(
                title = "回收站",
                subtitle = "查看已删除文件",
                onClick = onNavigateToTrash
            )

            // 存储清理入口（V7 §2.4）
            SettingsRow(
                title = "存储清理",
                subtitle = "查看清理建议",
                onClick = onNavigateToCleanup
            )

            // RN 活动中心入口（V7 §3.1）
            SettingsRow(
                title = "活动中心",
                subtitle = "打开 React Native 模块",
                onClick = onNavigateToRnActivity
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("维护工具", icon = null)

            // V8：孤立文件检查
            CardScaffold(title = "孤立文件检查", content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("检查数据库外的孤立媒体文件", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        orphanChecking = true
                        scope.launch {
                            orphanResult = MediaService.orphanCheck()
                            orphanChecking = false
                        }
                    }) {
                        if (orphanChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("检查")
                        }
                    }
                }
                orphanResult?.let { r ->
                    Text(
                        "检查 ${r.checked} 个文件，发现 ${r.orphanCount} 个孤立文件",
                        fontSize = 12.sp,
                        color = if (r.orphanCount > 0) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                    if (r.orphanCount > 0) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = {
                                scope.launch {
                                    val result = MediaService.cleanupOrphan()
                                    if (result != null) {
                                        orphanResult = MediaService.orphanCheck()
                                    }
                                }
                            }) { Text("一键清理", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            })

            // V8：自动打标签
            CardScaffold(title = "自动打标签", content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("为未打标签的媒体自动生成标签", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        autoTagging = true
                        scope.launch {
                            val count = MediaService.autoTag()
                            autoTagResult = "已为 $count 个媒体添加标签"
                            autoTagging = false
                        }
                    }) {
                        if (autoTagging) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("执行")
                        }
                    }
                }
                autoTagResult?.let { msg ->
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            })

            // V8：标签管理（删除/重命名/清理/导入/导出，对接 MediaService）
            CardScaffold(title = "标签管理", content = {
                // 顶部 action 行：清理无用标签 + 导出 + 导入
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 智能合并：自动合并相似标签（中英映射/简繁/大小写）
                    TextButton(onClick = {
                        if (merging) return@TextButton
                        merging = true
                        scope.launch {
                            val result = MediaService.mergeSmartTags()
                            merging = false
                            if (result == null) {
                                snackbarHostState.showSnackbar("智能合并失败")
                            } else {
                                mergeResult = result
                                // 成功后刷新标签列表，使合并反映到 UI
                                tagStatsLoading = true
                                tagStats = MediaService.getTagStats()
                                tagStatsLoading = false
                            }
                        }
                    }) {
                        if (merging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("智能合并")
                        }
                    }
                    TextButton(onClick = {
                        if (cleaningUp) return@TextButton
                        cleaningUp = true
                        scope.launch {
                            val result = MediaService.cleanupUnusedTags()
                            cleaningUp = false
                            if (result == null) {
                                snackbarHostState.showSnackbar("清理失败")
                            } else {
                                snackbarHostState.showSnackbar(
                                    "已清理 ${result.removedCount} 个无用标签"
                                )
                                tagStatsLoading = true
                                tagStats = MediaService.getTagStats()
                                tagStatsLoading = false
                            }
                        }
                    }) {
                        if (cleaningUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("清理无用标签")
                        }
                    }
                    TextButton(onClick = {
                        if (exporting) return@TextButton
                        exporting = true
                        scope.launch {
                            val data = MediaService.exportTags()
                            exporting = false
                            if (data == null) {
                                scope.launch { snackbarHostState.showSnackbar("导出失败") }
                            } else {
                                exportJson = data
                                exportDialogVisible = true
                            }
                        }
                    }) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("导出标签")
                        }
                    }
                    TextButton(onClick = {
                        importText = ""
                        importDialogVisible = true
                    }) { Text("导入标签") }
                }

                // 标签列表
                if (tagStatsLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (tagStatsError != null && tagStats == null) {
                    Text(
                        tagStatsError!!,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (tagStats.isNullOrEmpty()) {
                    Text(
                        "暂无标签",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    tagStats!!.forEach { stat ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${stat.tag}（${stat.count}）",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (searching) return@clickable
                                        searchTag = stat.tag
                                        searchResult = null
                                        searchError = null
                                        searching = true
                                        scope.launch {
                                            val ids = MediaService.searchByTag(stat.tag)
                                            searching = false
                                            if (ids != null) {
                                                searchResult = ids
                                            } else {
                                                searchError = "搜索 #$${stat.tag} 失败"
                                            }
                                        }
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Box {
                                TextButton(onClick = { menuExpanded = true }) { Text("操作") }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuExpanded = false
                                            renameTarget = stat.tag
                                            renameNewName = stat.tag
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "删除",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            deleteTarget = stat.tag
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            })

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    // 重命名 Dialog
    renameTarget?.let { oldName ->
        AlertDialog(
            onDismissRequest = {
                if (!renaming) renameTarget = null
            },
            title = { Text("重命名标签") },
            text = {
                Column {
                    Text("原标签：#$oldName", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameNewName,
                        onValueChange = { renameNewName = it },
                        label = { Text("新标签名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !renaming && renameNewName.isNotBlank() && renameNewName != oldName,
                    onClick = {
                        renaming = true
                        scope.launch {
                            val ok = MediaService.renameTag(oldName, renameNewName.trim())
                            renaming = false
                            if (ok) {
                                renameTarget = null
                                snackbarHostState.showSnackbar("已重命名")
                                tagStatsLoading = true
                                tagStats = MediaService.getTagStats()
                                tagStatsLoading = false
                            } else {
                                snackbarHostState.showSnackbar("重命名失败")
                            }
                        }
                    }
                ) {
                    if (renaming) {
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
                    enabled = !renaming,
                    onClick = { renameTarget = null }
                ) { Text("取消") }
            }
        )
    }

    // 删除确认 Dialog
    deleteTarget?.let { tagName ->
        AlertDialog(
            onDismissRequest = {
                if (!deleting) deleteTarget = null
            },
            title = { Text("删除标签") },
            text = {
                Text("确定要删除标签 #$tagName 吗？该标签将从所有媒体上移除。")
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            val count = MediaService.deleteTag(tagName)
                            deleting = false
                            deleteTarget = null
                            if (count > 0) {
                                snackbarHostState.showSnackbar("已删除 #$tagName（$count）")
                                tagStatsLoading = true
                                tagStats = MediaService.getTagStats()
                                tagStatsLoading = false
                            } else {
                                snackbarHostState.showSnackbar("删除失败")
                            }
                        }
                    }
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { deleteTarget = null }
                ) { Text("取消") }
            }
        )
    }

    // 导出 Dialog：显示 JSON 文本供复制
    if (exportDialogVisible) {
        AlertDialog(
            onDismissRequest = { exportDialogVisible = false },
            title = { Text("导出标签") },
            text = {
                Column {
                    Text(
                        "复制以下 JSON：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        exportJson ?: "",
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { exportDialogVisible = false }) { Text("关闭") }
            }
        )
    }

    // 导入 Dialog：粘贴 JSON
    if (importDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!importing) importDialogVisible = false
            },
            title = { Text("导入标签") },
            text = {
                Column {
                    Text(
                        "贴入 {\"tags\":[...]} 格式 JSON：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("JSON") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importing && importText.isNotBlank(),
                    onClick = {
                        importing = true
                        scope.launch {
                            val ok = MediaService.importTags(importText.trim())
                            importing = false
                            importDialogVisible = false
                            if (ok) {
                                snackbarHostState.showSnackbar("导入成功")
                                tagStatsLoading = true
                                tagStats = MediaService.getTagStats()
                                tagStatsLoading = false
                            } else {
                                snackbarHostState.showSnackbar("导入失败")
                            }
                        }
                    }
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("导入")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !importing,
                    onClick = { importDialogVisible = false }
                ) { Text("取消") }
            }
        )
    }

    // 智能合并结果 Dialog
    mergeResult?.let { r ->
        AlertDialog(
            onDismissRequest = { mergeResult = null },
            title = { Text("智能合并结果") },
            text = {
                Column {
                    if (r.mergedCount == 0) {
                        Text(
                            "没有需要合并的相似标签",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "合并了 ${r.mergedCount} 对相似标签",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            "标签总数：${r.totalTagsBefore} → ${r.totalTagsAfter}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        if (r.merges.isNotEmpty()) {
                            Text(
                                "合并明细（前 ${minOf(r.merges.size, 5)} 对）：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                r.merges.take(5).forEach { pair ->
                                    Text(
                                        "#${pair.from} → #${pair.to}（${pair.count}）${pair.reason.ifEmpty { "" }}",
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                if (r.merges.size > 5) {
                                    Text(
                                        "… 其余 ${r.merges.size - 5} 对未显示",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mergeResult = null }) { Text("关闭") }
            }
        )
    }

    // 标签搜索结果 Dialog
    searchTag?.let { tagName ->
        AlertDialog(
            onDismissRequest = {
                if (!searching) searchTag = null
            },
            title = { Text("搜索标签 #$tagName") },
            text = {
                Column {
                    when {
                        searching -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("搜索中…", fontSize = 13.sp)
                            }
                        }
                        searchError != null -> {
                            Text(
                                searchError!!,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        else -> {
                            val ids = searchResult.orEmpty()
                            Text(
                                "找到 ${ids.size} 个匹配媒体",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            if (ids.isEmpty()) {
                                Text(
                                    "该标签下暂无媒体",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "媒体 ID（前 ${minOf(ids.size, 10)} 个）：",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    ids.take(10).forEachIndexed { index, id ->
                                        Text(
                                            "${index + 1}. $id",
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                    if (ids.size > 10) {
                                        Text(
                                            "… 其余 ${ids.size - 10} 个未显示",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !searching,
                    onClick = { searchTag = null }
                ) { Text("关闭") }
            }
        )
    }
}
