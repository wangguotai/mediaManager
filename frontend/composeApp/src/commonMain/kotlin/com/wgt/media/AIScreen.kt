// PRD-v12：AI 视觉检索 UI。
//
// 含三个组件：
//   - [AISearchDialog]：自然语言语义搜索弹窗（"穿汉服的照片"），结果灌入 MediaListScreen
//     复用 applyAdvancedSearchResults，与 SmartSearchDialog 平级。
//   - [AICenterScreen]：AI 中心页（底部 Tab 入口），展示索引状态、自动相册、人物聚类。
//   - [AnnotationSheet]：单张照片的 AI 注解 BottomSheet（caption/scene/物体/手动编辑）。
//
// 集成见 SearchBar.kt 的 onAiSearch 回调与 MediaListScreen 的 Dialog 挂载。
package com.wgt.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.AISearchResult
import com.wgt.feature.media.MediaService.AIIndexProgress
import com.wgt.feature.media.MediaService.AutoAlbum
import com.wgt.feature.media.MediaService.PersonCluster
import media.MediaMetadata
import kotlinx.coroutines.launch

/**
 * AI 语义搜索弹窗。复用 SmartSearchDialog 的交互模式：
 * 输入框 + 搜索按钮 + 结果摘要。区别：调 [MediaService.getAISearch]，结果灌入列表。
 *
 * @param onResults (mediaList, total) 搜索命中回调，调用方灌入 viewModel
 * @param onDismiss 关闭
 */
@Composable
fun AISearchDialog(
    onResults: (List<MediaMetadata>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<Pair<Int, String>?>(null) } // (total, error)
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧠 AI 图像搜索") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "用自然语言描述，AI 按视觉语义检索。如：穿汉服的照片、海边大笑、蛋糕",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("描述你要找的照片") },
                    placeholder = { Text("例如：穿汉服的照片") },
                    singleLine = true,
                    enabled = !searching,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        triggerAiSearch(query, scope, { searching = it }, { summary = it }, onResults)
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                summary?.let { (total, error) ->
                    if (error.isNotEmpty()) {
                        Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(
                            "找到 $total 项匹配${if (total > 0) "（已展示）" else ""}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !searching && query.trim().isNotEmpty(),
                onClick = {
                    triggerAiSearch(query, scope, { searching = it }, { summary = it }, onResults)
                }
            ) { Text(if (searching) "搜索中…" else "搜索") }
        },
        dismissButton = {
            TextButton(enabled = !searching, onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun triggerAiSearch(
    rawQuery: String,
    scope: kotlinx.coroutines.CoroutineScope,
    searching: (Boolean) -> Unit,
    summary: (Pair<Int, String>?) -> Unit,
    onResults: (List<MediaMetadata>, Int) -> Unit
) {
    val q = rawQuery.trim()
    if (q.isEmpty()) return
    searching(true)
    scope.launch {
        try {
            val res: AISearchResult? = MediaService.getAISearch(q)
            if (res == null) {
                summary(Pair(0, "AI 搜索失败：请确认 AI 索引服务已启动"))
            } else {
                summary(Pair(res.total, ""))
                onResults(res.results.map { it.media }, res.total)
            }
        } catch (e: Exception) {
            summary(Pair(0, "错误：${e.message ?: "未知"}"))
        } finally {
            searching(false)
        }
    }
}

/**
 * AI 注解区（PRD-v12 §3.2 照片故事）——嵌入 MediaInfoDialog。
 *
 * 并发拉 [MediaService.getAnnotation]，展示 AI 生成的 caption/scene/物体/情绪，
 * 并提供 manual_note 编辑（用户补充"这是我和妈妈在苏州"）。
 * 索引未完成时 annotation=null，展示"AI 注解生成中"提示 + 触发索引按钮。
 *
 * @param mediaId 媒体 ID
 */
@Composable
fun AIAnnotationSection(mediaId: String) {
    val scope = rememberCoroutineScope()
    var annotation by remember(mediaId) { mutableStateOf<MediaService.Annotation?>(null) }
    var loading by remember(mediaId) { mutableStateOf(true) }
    var editing by remember(mediaId) { mutableStateOf(false) }
    var noteDraft by remember(mediaId) { mutableStateOf("") }
    var triggering by remember(mediaId) { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        loading = true
        annotation = MediaService.getAnnotation(mediaId)
        loading = false
        noteDraft = annotation?.manualNote ?: ""
    }

    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    Text("🧠 AI 注解", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))

    if (loading) {
        Text("加载中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (annotation == null) {
        // 未索引：提示 + 触发索引按钮
        Text("该照片尚未生成 AI 注解", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !triggering,
                onClick = {
                    triggering = true
                    scope.launch {
                        MediaService.triggerAIIndex(1)
                        annotation = MediaService.getAnnotation(mediaId)
                        noteDraft = annotation?.manualNote ?: ""
                        triggering = false
                    }
                }
            ) { Text(if (triggering) "生成中…" else "生成 AI 注解") }
        }
    } else {
        val ann = annotation!!
        // AI caption
        if (ann.caption.isNotEmpty()) {
            Text(ann.caption, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        // scene / mood 标签
        val metaLabels = listOfNotNull(
            ann.scene.takeIf { it.isNotEmpty() }?.let { "场景: $it" },
            ann.mood.takeIf { it.isNotEmpty() }?.let { "情绪: $it" }
        )
        if (metaLabels.isNotEmpty()) {
            Text(metaLabels.joinToString("  ·  "), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 物体 chip（物体≤6，横向滚动 Row 即可，避免 FlowRow 的实验 API opt-in）
        if (ann.objects.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ann.objects.forEach { obj ->
                    AssistChip(
                        onClick = {},
                        label = { Text(obj, fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
        // manual_note 编辑区
        if (editing) {
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                label = { Text("补充描述（在做什么/和谁/关于什么）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        MediaService.updateAnnotation(mediaId, noteDraft)
                        annotation = MediaService.getAnnotation(mediaId)
                        editing = false
                    }
                }) { Text("保存") }
                TextButton(onClick = { editing = false; noteDraft = ann.manualNote }) { Text("取消") }
            }
        } else {
            if (ann.manualNote.isNotEmpty()) {
                Text("📝 ${ann.manualNote}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { editing = true; noteDraft = ann.manualNote }) {
                Text(if (ann.manualNote.isEmpty()) "添加描述" else "编辑描述")
            }
        }
    }
}

/**
 * AI 中心页：作为底部 Tab 之一。展示：
 *   - AI 索引进度（未索引→触发索引按钮）
 *   - 自动相册（按场景聚合，点击看该场景照片）
 *   - 人物聚类（"我/妈妈"等，可命名）
 */
@Composable
fun AICenterScreen(
    onOpenAlbum: (String) -> Unit,   // scene 名 → 跳转该场景媒体
    onOpenPerson: (String) -> Unit   // clusterId → 跳转该人物媒体
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<AIIndexProgress?>(null) }
    var albums by remember { mutableStateOf<List<AutoAlbum>>(emptyList()) }
    var persons by remember { mutableStateOf<List<PersonCluster>>(emptyList()) }
    var indexing by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            progress = MediaService.getAIStatus()
            albums = MediaService.getAutoAlbums()
            persons = MediaService.getPersons()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("🧠 AI 智能管理", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        // 索引进度卡片
        item {
            val p = progress
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("索引状态", fontWeight = FontWeight.SemiBold)
                    if (p == null) {
                        Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("已索引 ${p.indexed}/${p.total}  ·  注解 ${p.annotated}  ·  人物 ${p.persons}",
                            fontSize = 14.sp)
                        if (p.pending > 0) {
                            LinearProgressIndicator(
                                progress = { if (p.total > 0) p.indexed.toFloat() / p.total else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("待索引 ${p.pending} 张", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!p.featureSvcReachable) {
                            Text("⚠ AI 特征服务未就绪", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !indexing && p.featureSvcReachable,
                                onClick = {
                                    indexing = true
                                    scope.launch {
                                        MediaService.triggerAIIndex(20)
                                        indexing = false
                                        refresh()
                                    }
                                }
                            ) { Text(if (indexing) "索引中…" else "索引 ${p.pending.coerceAtMost(20)} 张") }
                            OutlinedButton(onClick = { refresh() }) { Text("刷新") }
                        }
                    }
                }
            }
        }
        // 自动相册
        item {
            Text("📁 自动相册（按场景）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (albums.isEmpty()) {
            item { Text("暂无自动相册，先索引照片", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(albums) { album ->
                ListItem(
                    headlineContent = { Text(album.scene) },
                    supportingContent = { Text("${album.count} 张照片") },
                    leadingContent = { Text("🖼", fontSize = 24.sp) },
                    modifier = Modifier.clickable { onOpenAlbum(album.scene) }
                )
                HorizontalDivider()
            }
        }
        // 人物聚类
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("👤 人物（按长相分组）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = {
                    scope.launch { MediaService.reclusterPersons(); refresh() }
                }) { Text("重聚类") }
            }
        }
        if (persons.isEmpty()) {
            item { Text("暂无人物，索引后点重聚类", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(persons) { pc ->
                var name by remember(pc.id) { mutableStateOf(pc.name) }
                var editing by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = {
                        if (editing) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it },
                                singleLine = true, modifier = Modifier.fillMaxWidth(0.6f)
                            )
                        } else {
                            Text(if (pc.name.isEmpty()) "未命名人物" else pc.name)
                        }
                    },
                    supportingContent = { Text("${pc.faceCount} 张照片") },
                    leadingContent = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text("🙂", fontSize = 24.sp)
                        }
                    },
                    trailingContent = {
                        if (editing) {
                            TextButton(onClick = {
                                scope.launch { MediaService.renamePerson(pc.id, name); editing = false; refresh() }
                            }) { Text("保存") }
                        } else {
                            TextButton(onClick = { editing = true }) { Text("命名") }
                        }
                    },
                    modifier = Modifier.clickable { onOpenPerson(pc.id) }
                )
                HorizontalDivider()
            }
        }
    }
}
