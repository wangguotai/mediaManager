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
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.text.style.TextAlign
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
internal fun BatchDownloadUrlsDialog(
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
 * "加入相册"相册选择对话框。
 *
 * 弹出相册列表供用户选择目标相册，选中即触发加入操作。
 * 列表为空时提示用户先创建相册。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun AddToAlbumDialog(
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
 * 分享链接结果对话框：显示生成的 URL，支持复制到剪贴板。
 *
 * @param url 分享链接 URL
 * @param expiresAt 过期时间戳（epoch ms）
 * @param onDismiss 关闭回调
 */
@Composable
internal fun ShareLinkDialog(
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
internal fun ShareLinkConfigDialog(
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
 * PRD-v12 H: 改一刻相册式 ModalBottomSheet(沉浸式从底滑出)。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // PRD-v12 H: 改一刻相册式 ModalBottomSheet(沉浸式从底滑出),原 AlertDialog。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text("媒体详情", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Column(modifier = Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
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
                    // PRD-v12 §3.2：AI 注解区（照片故事）——caption/scene/物体/情绪 + 手动编辑。
                    // 未索引时展示"生成 AI 注解"按钮，触发 /api/ai/index 单张索引。
                    AIAnnotationSection(mediaId)
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
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("关闭") }
        }
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



