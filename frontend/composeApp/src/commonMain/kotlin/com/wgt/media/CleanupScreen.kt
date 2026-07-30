package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 存储清理建议页面（V7 §2.4）。
 *
 * 分析云端媒体列表，展示三类清理建议：
 * - 疑似重复（filename + size 相同）
 * - 大文件 Top 10（> 10MB）
 * - 老照片（> 365 天前）
 *
 * 支持多选+一键删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit
) {
    val suggestions = remember { mutableStateOf<List<MediaViewModel.CleanupSuggestion>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    // 首次进入时分析
    LaunchedEffect(Unit) {
        suggestions.value = viewModel.analyzeCleanupSuggestions()
        loaded = true
        viewModel.loadDuplicates()  // V7：后端 SHA256 精确重复检测
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储清理") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.deleteCleanupItems(selectedIds.toList()) {
                                // 删除后重新分析
                                suggestions.value = viewModel.analyzeCleanupSuggestions()
                                selectedIds.clear()
                            }
                        }) {
                            Text("删除选中 (${selectedIds.size})")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!loaded) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Text("分析中...", modifier = Modifier.padding(top = 16.dp))
            }
        } else if (suggestions.value.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("没有需要清理的项目", style = MaterialTheme.typography.titleMedium)
                Text(
                    "您的媒体库很整洁！",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // V7：即使本地分析无结果，也显示后端精确重复检测
                viewModel.duplicates?.let { dup ->
                    if (dup.groupCount > 0) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("⚠ SHA256 精确重复检测", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.padding(4.dp))
                                Text("${dup.groupCount} 组重复 · ${dup.totalDupes} 个文件", style = MaterialTheme.typography.bodyMedium)
                                val mbStr = dup.wastedMB.let {
                                    val i = it.toInt()
                                    val frac = ((it - i) * 100).toInt()
                                    "$i.${frac.toString().padStart(2, '0')}"
                                }
                                Text(
                                    "可回收 ${mbStr} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                dup.groups.take(3).forEach { g ->
                                    Text(
                                        "  · ${g.media.firstOrNull()?.filename ?: "?"} ×${g.count}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                if (dup.groups.size > 3) {
                                    Text("  ...等 ${dup.groups.size} 组", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 按类别分组
            val duplicates = suggestions.value.filter { it.category == MediaViewModel.CleanupCategory.DUPLICATE }
            val largeFiles = suggestions.value.filter { it.category == MediaViewModel.CleanupCategory.LARGE_FILE }
            val oldPhotos = suggestions.value.filter { it.category == MediaViewModel.CleanupCategory.OLD_PHOTO }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 概览卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("清理建议概览", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("疑似重复: ${duplicates.size} 项", style = MaterialTheme.typography.bodyMedium)
                            Text("大文件: ${largeFiles.size} 项", style = MaterialTheme.typography.bodyMedium)
                            Text("老照片: ${oldPhotos.size} 项", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "总计可选清理: ${suggestions.value.size} 项",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // V7：后端 SHA256 精确重复检测
                viewModel.duplicates?.let { dup ->
                    if (dup.groupCount > 0) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("⚠ SHA256 精确重复检测", style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.padding(4.dp))
                                    Text("${dup.groupCount} 组重复 · ${dup.totalDupes} 个文件", style = MaterialTheme.typography.bodyMedium)
                                    val mbStr = dup.wastedMB.let { 
                                        val i = it.toInt()
                                        val frac = ((it - i) * 100).toInt()
                                        "$i.${frac.toString().padStart(2, '0')}"
                                    }
                                    Text(
                                        "可回收 ${mbStr} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    dup.groups.take(3).forEach { g ->
                                        Text(
                                            "  · ${g.media.firstOrNull()?.filename ?: "?"} ×${g.count}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    if (dup.groups.size > 3) {
                                        Text("  ...等 ${dup.groups.size} 组", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                if (duplicates.isNotEmpty()) {
                    item {
                        CategoryHeader("疑似重复 (${duplicates.size})")
                    }
                    items(duplicates) { suggestion ->
                        CleanupItemRow(
                            suggestion = suggestion,
                            isSelected = suggestion.media.id in selectedIds,
                            onToggle = { id ->
                                if (id in selectedIds) selectedIds.remove(id)
                                else selectedIds.add(id)
                            }
                        )
                    }
                }

                if (largeFiles.isNotEmpty()) {
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CategoryHeader("大文件 (${largeFiles.size})")
                    }
                    items(largeFiles) { suggestion ->
                        CleanupItemRow(
                            suggestion = suggestion,
                            isSelected = suggestion.media.id in selectedIds,
                            onToggle = { id ->
                                if (id in selectedIds) selectedIds.remove(id)
                                else selectedIds.add(id)
                            }
                        )
                    }
                }

                if (oldPhotos.isNotEmpty()) {
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CategoryHeader("老照片 (${oldPhotos.size})")
                    }
                    items(oldPhotos) { suggestion ->
                        CleanupItemRow(
                            suggestion = suggestion,
                            isSelected = suggestion.media.id in selectedIds,
                            onToggle = { id ->
                                if (id in selectedIds) selectedIds.remove(id)
                                else selectedIds.add(id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CleanupItemRow(
    suggestion: MediaViewModel.CleanupSuggestion,
    isSelected: Boolean,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle(suggestion.media.id) }
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                suggestion.media.filename,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (suggestion.category == MediaViewModel.CleanupCategory.LARGE_FILE) {
            Text(
                "⚠",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun Spacer(modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier = modifier)
}
