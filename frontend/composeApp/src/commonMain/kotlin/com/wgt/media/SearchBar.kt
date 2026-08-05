package com.wgt.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import media.MediaMetadata
import kotlin.time.Clock
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_search
import mediamanager.composeapp.generated.resources.ic_sort
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.TextButton

private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * 高级搜索条件面板 —— 由搜索栏的"高级搜索"图标按钮触发，弹出 [AlertDialog]。
 *
 * 收集多条件：类型（全部/图片/视频/Live）/ 最小大小（MB）/ 标签 / 日期范围（YYYY-MM-DD），
 * 点击"搜索"以 [Map] 形式回调 [onSearch]，调用方据此调用 `MediaService.advancedSearch(opts)`
 * 并把结果灌入 ViewModel。点击"取消"或外部返回关闭，不影响列表。
 *
 * 保持简单：四个 [OutlinedTextField] + 一个类型下拉，全部本地状态，无外部分页。
 * 与搜索栏本地搜索语义独立：本地搜索对当前列表做即时过滤，高级搜索请求后端命中全集后
 * 替换当前列表内容（调用方负责清空 searchQuery，避免双重过滤）。
 *
 * @param onSearch 用户点击"搜索"时回调，参数为已清洗的条件 map（空值过滤掉，日期已补 RFC3339）。
 *                 可能的键：type / min_size / max_size / tag / date_from / date_to / limit。
 * @param onDismiss 关闭回调（"取消"或对话框外部 dismiss 都触发）。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun AdvancedSearchDialog(
    onSearch: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    // 类型选项：与后端 AdvancedSearchOpts.Type 字符串口径一致（IMAGE/VIDEO/LIVE_PHOTO）。
    // "全部"不传 type 参数。
    val typeOptions = listOf("" to "全部", "IMAGE" to "图片", "VIDEO" to "视频", "LIVE_PHOTO" to "Live")
    var selectedType by remember { mutableStateOf("") }
    var minSizeMb by remember { mutableStateOf("") }
    var maxSizeMb by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级搜索") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 类型下拉：用暴露式下拉菜单（点击文本+箭头展开列表）。保持实现简单，无 ExposedDropdownBox 的繁复状态。
                Text("类型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedTextField(
                        value = typeOptions.firstOrNull { it.first == selectedType }?.second ?: "全部",
                        onValueChange = { /* 只读，点击触发下拉 */ },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeMenuExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { typeMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_sort),
                                    contentDescription = "选择类型",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        singleLine = true
                    )
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        typeOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedType = value
                                    typeMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // 大小范围（MB）：两个并行输入框。留空表示不施加该侧条件。
                Text("大小范围（MB）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSizeMb,
                        onValueChange = { v -> minSizeMb = v.filter { it.isDigit() } },
                        label = { Text("最小") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxSizeMb,
                        onValueChange = { v -> maxSizeMb = v.filter { it.isDigit() } },
                        label = { Text("最大") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // 标签：后端按精确 tag_name 匹配（media_tags 表 EXISTS 子查询）。
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 日期范围（YYYY-MM-DD）：OutlinedTextField 直接接受；提交前由调用方
                // 或 MediaService.advancedSearch 补成 RFC3339。
                Text("日期范围（YYYY-MM-DD）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateFrom,
                        onValueChange = { dateFrom = it },
                        label = { Text("从") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dateTo,
                        onValueChange = { dateTo = it },
                        label = { Text("到") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !searching,
                onClick = {
                    // 组装条件 map：仅纳入非空项；大小 MB → 字节字符串。
                    val opts = LinkedHashMap<String, String>()
                    if (selectedType.isNotEmpty()) opts["type"] = selectedType
                    if (minSizeMb.isNotEmpty()) opts["min_size"] = (minSizeMb.toLong() * 1024L * 1024L).toString()
                    if (maxSizeMb.isNotEmpty()) opts["max_size"] = (maxSizeMb.toLong() * 1024L * 1024L).toString()
                    if (tag.trim().isNotEmpty()) opts["tag"] = tag.trim()
                    if (dateFrom.trim().isNotEmpty()) opts["date_from"] = dateFrom.trim()
                    if (dateTo.trim().isNotEmpty()) opts["date_to"] = dateTo.trim()
                    searching = true
                    onSearch(opts)
                    onDismiss()
                }
            ) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 智能搜索对话框 —— 由搜索栏的"🤖 智能搜索"按钮触发。
 *
 * 与 [AdvancedSearchDialog] 区别：用户输入**自然语言**查询（如"去年夏天的视频"、
 * "大于 10MB 的图片"），由后端 `media-smart-search` 解析为结构化条件后查库，
 * 无需前端逐项拼参数。对话框内就地调 [MediaService.getMediaSmartSearch]，
 * 成功后把命中的媒体列表经 [onResults] 回传给调用方（灌入 ViewModel 替换列表），
 * 并展示后端 [parsed_query][MediaService.SmartSearchResult.parsedQuery] 让用户确认
 * "理解为：xxx"，增加自然语言搜索的透明度。
 *
 * 状态机：idle → searching → done（带结果摘要）/ error。空结果不关对话框，
 * 留在原地提示"未找到"，便于用户改写查询重试。
 *
 * @param onResults 搜索成功回调：命中的媒体列表 + 总数 + 后端解析的查询描述。
 *                  调用方据此 `applyAdvancedSearchResults(list)` 替换列表。
 * @param onDismiss 关闭回调（取消/对话框外部 dismiss 都触发）。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SmartSearchDialog(
    onResults: (List<MediaMetadata>, Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // 搜索完成后展示的摘要：parsedQuery / total / 错误提示。null 表示尚未搜索或已重置。
    var summary by remember {
        mutableStateOf<Triple<String, Int, String>?>(null)
    }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🤖 智能搜索") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "用自然语言描述你想找的媒体，如：去年夏天的视频、大于 10MB 的图片、带海边标签的照片",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("描述你要找的内容") },
                    placeholder = { Text("例如：去年夏天的视频") },
                    singleLine = true,
                    enabled = !searching,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        triggerSmartSearch(query, scope, searching = { searching = it },
                            summary = { summary = it }, onResults = { list, total, parsed ->
                                onResults(list, total, parsed)
                            })
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                // 搜索结果摘要区：展示后端解析说明 + 命中数量，或错误提示。
                summary?.let { (parsed, total, error) ->
                    if (error.isNotEmpty()) {
                        Text(
                            error,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        if (parsed.isNotEmpty()) {
                            Text(
                                "理解为：$parsed",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "找到 $total 项匹配媒体${if (total > 0) "（已展示）" else ""}",
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
                    triggerSmartSearch(query, scope, searching = { searching = it },
                        summary = { summary = it }, onResults = { list, total, parsed ->
                            onResults(list, total, parsed)
                        })
                }
            ) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        },
        dismissButton = {
            TextButton(enabled = !searching, onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * [SmartSearchDialog] 的搜索触发逻辑：trim 校验 → 调 [MediaService.getMediaSmartSearch]
 * → 回写 summary/onResults。提取为顶层私有函数避免 confirmButton 与 IME 回调重复。
 */
private fun triggerSmartSearch(
    rawQuery: String,
    scope: kotlinx.coroutines.CoroutineScope,
    searching: (Boolean) -> Unit,
    summary: (Triple<String, Int, String>?) -> Unit,
    onResults: (List<MediaMetadata>, Int, String) -> Unit
) {
    val q = rawQuery.trim()
    if (q.isEmpty()) return
    searching(true)
    summary(null)
    scope.launch {
        val result = MediaService.getMediaSmartSearch(q)
        if (result == null) {
            searching(false)
            summary(Triple("", 0, "智能搜索失败，请稍后重试"))
        } else {
            searching(false)
            summary(Triple(result.parsedQuery, result.total, ""))
            if (result.results.isNotEmpty()) {
                onResults(result.results, result.total, result.parsedQuery)
            }
        }
    }
}

/**
 * 全文搜索对话框 —— 调 [MediaService.getMediaFullTextSearch] 对文件名/描述/标签等文本做全文检索。
 *
 * 与 [SmartSearchDialog]（自然语言）区别：本对话框接受关键词 + 可选位置（lat,lon）+ 半径 +
 * 类型（图片/视频），命中文本匹配的媒体。后端 [MediaService.QueryInfo.locationFilterUnavailable]
 * 为 true 时（无地理位置索引），对话框显式提示“位置筛选不可用”，避免用户以为位置已生效。
 *
 * 状态机：idle → searching → done（带结果摘要）/ error。空结果不关对话框，留在原地提示
 * “未找到”，便于用户改写关键词重试。结果经 [onResults] 回调灌入调用方列表。
 *
 * @param onResults 搜索成功回调：命中的媒体列表 + 总数 + 是否位置筛选不可用（true 时调用方可 Snackbar 提示）。
 * @param onDismiss 关闭回调。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun FullTextSearchDialog(
    onResults: (List<MediaMetadata>, Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("") }
    // 类型选项：与后端 type 参数口径一致（IMAGE/VIDEO）；空串=不限类型。
    val typeOptions = listOf("" to "不限", "IMAGE" to "图片", "VIDEO" to "视频")
    var selectedType by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    // 搜索完成后的摘要：total / 位置不可用标志 / 错误提示。null 表示尚未搜索。
    var summary by remember { mutableStateOf<Triple<Int, Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📍 全文搜索") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "按关键词检索媒体文件名、描述、标签等文本字段，可叠加位置与类型筛选",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("关键词") },
                    placeholder = { Text("例如：海边 旅行的照片") },
                    singleLine = true,
                    enabled = !searching,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        triggerFullTextSearch(query, location, radius, selectedType, scope,
                            searching = { searching = it }, summary = { summary = it },
                            onResults = { list, total, locUnavailable -> onResults(list, total, locUnavailable) })
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                // 类型下拉：沿用 AdvancedSearchDialog 的简易下拉实现。
                Text("类型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedTextField(
                        value = typeOptions.firstOrNull { it.first == selectedType }?.second ?: "不限",
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeMenuExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { typeMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_sort),
                                    contentDescription = "选择类型",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        singleLine = true
                    )
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        typeOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedType = value
                                    typeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                // 位置筛选（可选）：lat,lon。半径单位米。同时填了位置才生效。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("位置 lat,lon") },
                        placeholder = { Text("31.23,121.47") },
                        singleLine = true,
                        enabled = !searching,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = radius,
                        onValueChange = { radius = it.filter { c -> c.isDigit() } },
                        label = { Text("半径(米)") },
                        singleLine = true,
                        enabled = !searching,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.width(110.dp)
                    )
                }
                // 搜索结果摘要区：命中数量 / 位置不可用提示 / 错误。
                summary?.let { (total, locUnavailable, error) ->
                    if (error.isNotEmpty()) {
                        Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        if (locUnavailable) {
                            Text(
                                "⚠️ 位置筛选不可用（后端未启用地理位置索引），已忽略位置条件",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "找到 $total 项匹配媒体${if (total > 0) "（已展示）" else ""}",
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
                    triggerFullTextSearch(query, location, radius, selectedType, scope,
                        searching = { searching = it }, summary = { summary = it },
                        onResults = { list, total, locUnavailable -> onResults(list, total, locUnavailable) })
                }
            ) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        },
        dismissButton = {
            TextButton(enabled = !searching, onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * [FullTextSearchDialog] 的搜索触发逻辑：trim 校验 → 调 [MediaService.getMediaFullTextSearch]
 * → 回写 summary/onResults。提取为顶层私有函数避免 confirmButton 与 IME 回调重复。
 */
private fun triggerFullTextSearch(
    rawQuery: String,
    rawLocation: String,
    rawRadius: String,
    rawType: String,
    scope: kotlinx.coroutines.CoroutineScope,
    searching: (Boolean) -> Unit,
    summary: (Triple<Int, Boolean, String>?) -> Unit,
    onResults: (List<MediaMetadata>, Int, Boolean) -> Unit
) {
    val q = rawQuery.trim()
    if (q.isEmpty()) return
    val loc = rawLocation.trim().takeIf { it.isNotEmpty() }
    val radiusInt = rawRadius.trim().toIntOrNull()
    val type = rawType.trim().takeIf { it.isNotEmpty() }
    searching(true)
    summary(null)
    scope.launch {
        val result = MediaService.getMediaFullTextSearch(q, location = loc, radius = radiusInt, type = type)
        if (result == null) {
            searching(false)
            summary(Triple(0, false, "全文搜索失败，请稍后重试"))
        } else {
            searching(false)
            summary(Triple(result.total, result.queryInfo.locationFilterUnavailable == true, ""))
            if (result.results.isNotEmpty()) {
                onResults(result.results, result.total, result.queryInfo.locationFilterUnavailable == true)
            }
        }
    }
}

/**
 * 可展开搜索栏。
 *
 * 收起态：仅一个搜索图标按钮 + 灰色"搜索"提示，占用空间小，不干扰浏览。
 * 展开态：返回箭头 + 输入框 + 清除按钮一行，输入框自动聚焦并唤起键盘实时过滤。
 *
 * 输入文案由本地 [BasicTextField] 持有；为满足"debounce 300ms 实时过滤"，[onDebouncedQueryChange]
 * 在输入停止 300ms 后才上抛，避免每个字符都触发 ViewModel 的 list 过滤重组。清除/收起时立即
 * 上抛空串，让列表即时恢复无过滤态，不必等去抖窗口。
 *
 * @param expanded 当前是否展开（由外部持有，便于与筛选条联动）
 * @param onExpandedChange 展开/收起回调
 * @param onDebouncedQueryChange debounce 300ms 后的查询文案回调（驱动 ViewModel 过滤）
 * @param onSearchSubmit IME 搜索键提交时的回执，可立即触发一次过滤
 * @param modifier
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SearchBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDebouncedQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    onAdvancedSearch: () -> Unit = {},
    onSmartSearch: () -> Unit = {},
    onFullTextSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 输入框本地文案：即时跟随键盘输入。
    var queryText by remember { mutableStateOf("") }
    // 控制清除按钮显隐（仅在有文案时淡入），避免空文案时误触清除。
    var queryVisible by remember { mutableStateOf(false) }
    // 展开时聚焦输入框，自动唤起键盘，省去用户二次点击。
    val focusRequester = remember { FocusRequester() }
    // 用于推荐标签点击采纳（POST /api/media/auto-tag 批量打标签）发起协程。
    // SearchBar 自身无 ViewModel，故就地持有一个 scope 调 MediaService 挂起方法。
    val tagScope = rememberCoroutineScope()

    // 纯字符串搜索建议：对接 MediaService.getSearchSuggestions(q)（GET
    // /api/media/search-suggestions），返回 List<String>。与 V24 增强建议
    // (EnhancedSuggestion，带 source) 和 V26 前缀补全（带完整文件名）互补，
    // 本状态驱动 DropdownMenu 下拉式建议。search-suggestions 后端按文件名
    // 前缀匹配返回去扩展名的纯文本片段，适合下拉快速选取。
    var simpleSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    // debounce：监听 queryText 变化，停顿 300ms 后上抛去抖后的查询。
    // distinctUntilChanged 避免相同值重复触发过滤重组。
    LaunchedEffect(Unit) {
        snapshotFlow { queryText }
            .distinctUntilChanged()
            .collect {
                queryVisible = it.isNotEmpty()
                delay(SEARCH_DEBOUNCE_MS)
                onDebouncedQueryChange(it.trim())
            }
    }

    // 纯字符串建议去抖拉取：queryText 变化即重启协程（取消上一次 delay），
    // 天然实现 300ms 去抖。仅展开态 + 非空输入时请求；其余清空避免无谓请求。
    // 与下方 V24/V26 的 LaunchedEffect(queryText) 并行，三者 endpoint 不同、互不干扰。
    LaunchedEffect(queryText) {
        val trimmed = queryText.trim()
        if (expanded && trimmed.isNotEmpty()) {
            delay(SEARCH_DEBOUNCE_MS)
            simpleSuggestions = MediaService.getSearchSuggestions(trimmed) ?: emptyList()
        } else {
            simpleSuggestions = emptyList()
        }
    }

    // 展开瞬间请求焦点，让输入框立即可输入、键盘自动弹出。
    LaunchedEffect(expanded) {
        if (expanded) {
            // 延后一帧等输入框挂载完成，再请求焦点更稳妥。
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 用 Column 自上而下垂直堆叠"搜索框"与"搜索历史"，历史永远在搜索框下方，
    // 不会因 align/Box 叠层遮盖输入框。
    Column(modifier = modifier.fillMaxWidth()) {
        // 搜索框：始终填满父宽，避免收起态 CenterEnd 偏移造成的历史标签错位。
        // 胶囊圆角 + surfaceVariant 浅底，对标百度网盘/小米相册的圆角搜索栏，
        // 比旧的常平背景更精致，且与下方的筛选 chip 在视觉上区分开。
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenHorizontalPadding, vertical = 4.dp),
            shape = RoundedCornerShape(Dimens.searchBarCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 收起态：搜索图标按钮，点击展开。
                // 展开态：返回箭头，点击收起并清空查询（收起即退出搜索，恢复完整列表）。
                IconButton(
                    onClick = {
                        if (expanded) {
                            // 收起：清空输入与去抖查询，恢复无过滤态。
                            queryText = ""
                            queryVisible = false
                            onDebouncedQueryChange("")
                            onExpandedChange(false)
                        } else {
                            onExpandedChange(true)
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (expanded) Res.drawable.ic_arrow_back else Res.drawable.ic_search
                        ),
                        contentDescription = if (expanded) "收起搜索" else "搜索",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (expanded) {
                    Spacer(modifier = Modifier.width(4.dp))

                    // 输入框 + 建议下拉：用 Box 包裹 BasicTextField，DropdownMenu 锚定在
                    // Box 下方，随 simpleSuggestions 变化显隐。点击建议 → 填入输入框、
                    // 立即上抛去抖查询、写入搜索历史、触发 onSearchSubmit 提交一次搜索。
                    // weight 放在 Box 上（RowScope），BasicTextField 仅 fillMaxWidth——
                    // 这样 Box 在行内占满剩余空间，输入框又受 DropdownMenu 锚点正确约束。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        BasicTextField(
                            value = queryText,
                            onValueChange = { queryText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    // 仅清空查询，不自动收起——收起由 IconButton 返回箭头控制
                                    if (!state.isFocused && queryText.isEmpty()) {
                                        onDebouncedQueryChange("")
                                    }
                                },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                onSearchSubmit()
                            }),
                            decorationBox = { innerTextField ->
                                // placeholder：无文案时显示灰色提示。
                                if (queryText.isEmpty()) {
                                    Text(
                                        "搜索媒体名称",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 15.sp
                                    )
                                }
                                innerTextField()
                            }
                        )

                        // 建议下拉：仅在有建议且输入非空时展开。DropdownMenu 内部自管
                        // popup 位置与 dismiss（外部点击/IME backtrack 自动关闭）。
                        DropdownMenu(
                            expanded = simpleSuggestions.isNotEmpty() && queryText.isNotEmpty(),
                            onDismissRequest = { /* 建议由 queryText 控制，不主动清空 */ }
                        ) {
                            simpleSuggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion, fontSize = 14.sp, maxLines = 1) },
                                    onClick = {
                                        queryText = suggestion
                                        queryVisible = true
                                        onDebouncedQueryChange(suggestion)
                                        SearchHistory.add(suggestion)
                                        onSearchSubmit()
                                    }
                                )
                            }
                        }
                    }

                    // 清除按钮：仅在有文案时淡入显示；点击清空输入并立即上抛空串。
                    AnimatedVisibility(
                        visible = queryVisible,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(120))
                    ) {
                        IconButton(
                            onClick = {
                                queryText = ""
                                queryVisible = false
                                onDebouncedQueryChange("")
                            }
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "清除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 高级搜索入口：点击弹出高级搜索面板（类型/大小/标签/日期多条件），
                    // 走后端 /api/media/advanced-search 命中全集后替换列表。
                    // 无论展开/收起态均可见，便于在任何状态下都能进入高级搜索。
                    IconButton(onClick = onAdvancedSearch) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_sort),
                            contentDescription = "高级搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 智能搜索入口：用自然语言提问（如"去年夏天的视频"），后端
                    // media-smart-search 解析后查库。用文本按钮承载，便于识别入口语义。
                    TextButton(onClick = onSmartSearch) {
                        Text(
                            "🤖 智能搜索",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 全文搜索入口：按关键词检索文件名/描述/标签等文本字段，可叠加位置与类型筛选，
                    // 走后端 /api/media/media-full-text-search。与智能搜索（自然语言）互补。
                    TextButton(onClick = onFullTextSearch) {
                        Text(
                            "📍 全文搜索",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 收起态占位文案：提示可点击搜索图标开始过滤。
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "搜索",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    // 收起态同样提供高级搜索入口，右对齐（占位文案后用 weight spacer 推到行尾）。
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onAdvancedSearch) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_sort),
                            contentDescription = "高级搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 收起态同样提供智能搜索入口，紧跟高级搜索图标，任意状态下均可进入。
                    TextButton(onClick = onSmartSearch) {
                        Text(
                            "🤖",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 收起态同样提供全文搜索入口，紧跟智能搜索，任意状态下均可进入。
                    TextButton(onClick = onFullTextSearch) {
                        Text(
                            "📍",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 搜索历史：仅展开态且输入为空时显示，位于搜索框正下方，用 Spacer 明确分隔。
        // remember 缓存避免每次重组都重读 SettingsStorage。
        if (expanded && queryText.isEmpty()) {
            // 用 var + by remember 让清空操作能触发重组，隐藏历史区。
            var history by remember { mutableStateOf(SearchHistory.load()) }
            if (history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                // 历史标题行："搜索历史"文案 + 右侧"清空"按钮。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "搜索历史",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    TextButton(
                        onClick = {
                            SearchHistory.clear()
                            history = emptyList()
                        }
                    ) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(history.size) { index ->
                        val historicQuery = history[index]
                        AssistChip(
                            onClick = {
                                queryText = historicQuery
                                queryVisible = true
                                onDebouncedQueryChange(historicQuery)
                                SearchHistory.add(historicQuery)
                            },
                            label = { Text(historicQuery, fontSize = 13.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
        // 最近操作区：展开态且输入为空时，从后端 GET /api/media/search-history 拉取
        // 最近操作（搜索/删除/重命名/旋转/上传等），在本地搜索历史下方展示最近 5 条。
        // 与本地 SearchHistory 区分：此处展示后端视角的全量操作流水。
        if (expanded && queryText.isEmpty()) {
            var recentOps by remember { mutableStateOf<List<MediaService.SearchHistoryItem>>(emptyList()) }
            LaunchedEffect(expanded) {
                if (expanded) {
                    recentOps = MediaService.getSearchHistoryFromBackend()?.take(5) ?: emptyList()
                }
            }
            if (recentOps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                // 标题行。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最近操作",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                // 操作列表：每条一行，emoji + detail + 相对时间。
                recentOps.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 点击若 detail 是搜索词则触发搜索；仅 search 类操作有可搜索词。
                                // 其余删除/重命名等动作的 detail 多为文件名，点击亦尝试搜索。
                                val query = item.detail.trim()
                                if (query.isNotEmpty()) {
                                    queryText = query
                                    queryVisible = true
                                    onDebouncedQueryChange(query)
                                    SearchHistory.add(query)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            actionEmoji(item.action),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            item.detail.ifEmpty { item.action },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            relativeTime(item.createdAt),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
        // V25：热门搜索区——展开态且输入为空时，调 GET /api/media/media-query-stats
        // 拉取搜索热词统计，仅在 totalSearches > 0（后端有搜索记录）时显示。
        // 位置：排在"最近操作"区之后、"标签快捷区"之前，作为搜索建议的一部分。
        // 每个 chip 显示热词 + 🔥 + 次数，点击触发本地搜索（与搜索历史 chip 行为一致）。
        if (expanded && queryText.isEmpty()) {
            var queryStats by remember {
                mutableStateOf<MediaService.MediaQueryStats?>(null)
            }
            LaunchedEffect(expanded) {
                if (expanded) {
                    queryStats = MediaService.getMediaQueryStats()
                }
            }
            // 仅在 totalSearches > 0 时展示：避免无搜索记录时出现空区。
            if (queryStats != null && queryStats!!.totalSearches > 0) {
                // top_keywords 后端已排序（按 count 倒序），前端取前 5。
                val top5 = queryStats!!.topKeywords.take(5)
                if (top5.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    // 标题行：\"热门搜索\"文案，标识此区为热搜词聚合。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "热门搜索",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(top5.size) { index ->
                            val hotWord = top5[index]
                            AssistChip(
                                onClick = {
                                    // 点击热词触发本地搜索，与搜索历史 chip 行为一致。
                                    queryText = hotWord.keyword
                                    queryVisible = true
                                    onDebouncedQueryChange(hotWord.keyword)
                                    SearchHistory.add(hotWord.keyword)
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🔥", fontSize = 13.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(hotWord.keyword, fontSize = 13.sp, maxLines = 1)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "${hotWord.count}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }
        }
        // V8：标签快捷区——展开态且输入为空时显示用户所有标签，点击触发标签搜索
        if (expanded && queryText.isEmpty()) {
            var allTags by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(expanded) {
                if (expanded) allTags = MediaService.listAllTags() ?: emptyList()
            }
            if (allTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(allTags.size) { index ->
                        val tag = allTags[index]
                        AssistChip(
                            onClick = {
                                queryText = "#$tag"
                                queryVisible = true
                                onDebouncedQueryChange("#$tag")
                            },
                            label = { Text("#$tag", fontSize = 13.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        }
        // V22：推荐标签区——展开态且输入为空时，调 GET /api/media/tag-recommendations
        // 拉取智能标签推荐（按文件名模式 IMG_/VID_/Screenshot 等映射），在用户已有标签
        // 快捷区下方展示。每个 chip 显示 #tag + "(N 项可标记)"；点击采纳 → 调
        // POST /api/media/auto-tag 批量打标签（auto-tag 按 IMG_→照片 等前缀规则幂等标记，
        // 语义上覆盖推荐区列出的全部匹配媒体）。
        //
        // 与并列展示的 [listAllTags] 快捷区互补：快捷区是"已有标签，点击搜索"，
        // 推荐区是"还没打的标签，点击采纳"。采纳后该推荐会被后端跳过（用户已拥有该标签），
        // 下次刷新自然消失。
        if (expanded && queryText.isEmpty()) {
            var recommendations by remember {
                mutableStateOf<List<MediaService.TagRecommendation>>(emptyList())
            }
            // 推荐采纳后置 1：已采纳状态——点击后变灰禁用，避免重复打标签。
            var adoptedTag by remember { mutableStateOf<String?>(null) }
            // 推荐采纳后置 2：采纳结果提示（"已标记 N 项"或失败），短暂Toast式展示。
            var adoptMsg by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(expanded) {
                if (expanded) {
                    recommendations = MediaService.getTagRecommendations() ?: emptyList()
                }
            }
            if (recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                // 标题行："推荐标签"文案，标识此区与上方已有标签快捷区区分。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "推荐标签",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(recommendations.size) { index ->
                        val rec = recommendations[index]
                        val isAdopted = adoptedTag == rec.tagName
                        // 用 AssistChip 采纳按钮：chip 标题显示 "#tag (N 可标记)"；
                        // 辅助文本（reason）通过次行小字展示，受 chip 单行约束故以 maxLines=1 截断。
                        // 点击 → tagScope.launch 调 MediaService.autoTag()，成功记 taggedCount。
                        AssistChip(
                            onClick = {
                                if (isAdopted) return@AssistChip
                                adoptedTag = rec.tagName
                                tagScope.launch {
                                    val tagged = MediaService.autoTag()
                                    adoptMsg = if (tagged > 0) "已为 $tagged 项打标签" else "未发现可标记的媒体"
                                }
                            },
                            label = {
                                Column {
                                    // 主行：#tag + (N 项可标记)
                                    Text(
                                        "#${rec.tagName} (${rec.suggestedMediaCount} 项可标记)",
                                        fontSize = 13.sp
                                    )
                                    // 次行：推荐理由（后端 reason，如"文件名以 IMG_ 开头"）。
                                    if (rec.reason.isNotEmpty()) {
                                        Text(
                                            rec.reason,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                // 已采纳时降饱和度提示已完成；未采纳用 secondaryContainer 与上方
                                // 标签快捷区（tertiaryContainer）区分色相。
                                containerColor = if (isAdopted) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                }
                            )
                        )
                    }
                }
                // V23：一键应用全部推荐——底部 TextButton，调 POST /api/media/apply-tag-recommendations。
                // 成功后用 adoptMsg（本区已有的轻量提示状态）展示"已为 N 个媒体添加推荐标签"，
                // 并刷新推荐列表（已应用的标签会被后端跳过，自然从列表消失）。
                var applying by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        enabled = !applying,
                        onClick = {
                            if (applying) return@TextButton
                            applying = true
                            tagScope.launch {
                                val count = MediaService.applyTagRecommendations()
                                applying = false
                                adoptMsg = if (count != null) {
                                    if (count > 0) "已为 $count 个媒体添加推荐标签"
                                    else "没有新的可标记媒体"
                                } else {
                                    "应用推荐失败，请稍后重试"
                                }
                                if (count != null && count > 0) {
                                    // 刷新推荐列表：已应用的标签会被后端跳过，列表更新反映新状态。
                                    recommendations = MediaService.getTagRecommendations() ?: emptyList()
                                }
                            }
                        }
                    ) {
                        Text(if (applying) "应用中…" else "一键应用全部推荐", fontSize = 13.sp)
                    }
                }
                // 采纳结果提示：非空时以小字展示在推荐区底部，点过后短暂可见。
                adoptMsg?.let { msg ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        msg,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
        // V24：增强搜索建议——展开态且输入非空时，从后端 GET /api/media/search-suggestions-enhanced
        // 拉取多源（文件名/标签/相册名）合并去重后的建议，每条带 source 标签，
        // 以 emoji 区分来源（📄文件名 / 🏷️标签 / 📷相册）。点击建议触发搜索。
        var suggestions by remember { mutableStateOf<List<MediaService.EnhancedSuggestion>>(emptyList()) }
        LaunchedEffect(queryText) {
            if (expanded && queryText.length >= 2) {
                delay(SEARCH_DEBOUNCE_MS)
                suggestions = MediaService.getEnhancedSearchSuggestions(queryText.trim()) ?: emptyList()
            } else {
                suggestions = emptyList()
            }
        }

        if (expanded && queryText.isNotEmpty() && suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(suggestions.size) { index ->
                    val sug = suggestions[index]
                    // 来源 emoji：filename→📄、tag→🏷️、album→📷，未知回退 •。
                    val sourceIcon = when (sug.source) {
                        "filename" -> "📄"
                        "tag" -> "🏷️"
                        "album" -> "📷"
                        else -> "•"
                    }
                    AssistChip(
                        onClick = {
                            // 相册标签来源搜索时加 # 前缀命中标签搜索语义，
                            // 文件名直接用原文。相册名同样直接用原文。
                            val query = if (sug.source == "tag") "#${sug.text}" else sug.text
                            queryText = query
                            queryVisible = true
                            onDebouncedQueryChange(query)
                            SearchHistory.add(query)
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sourceIcon, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(sug.text, fontSize = 13.sp, maxLines = 1)
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // V26：文件名前缀补全区——展开态且输入非空时，调
        // GET /api/media/media-search-suggestions?q=xxx&limit=5 拉取文件名前缀补全建议。
        // 与上面 V24 增强建议区并列（均随输入实时刷新、debounce 300ms），
        // 区别：本区聚焦"补全"语义——后端仅大小写不敏感前缀匹配，保留完整文件名
        // （含扩展名），每条 📄 + 完整文件名。点击触发本地搜索（与搜索历史 chip 一致）。
        var prefixSuggestions by remember {
            mutableStateOf<List<MediaService.MediaSearchSuggestion>>(emptyList())
        }
        LaunchedEffect(queryText) {
            if (expanded && queryText.length >= 2) {
                delay(SEARCH_DEBOUNCE_MS)
                prefixSuggestions =
                    MediaService.getMediaSearchSuggestions(queryText.trim(), 5) ?: emptyList()
            } else {
                prefixSuggestions = emptyList()
            }
        }

        if (expanded && queryText.isNotEmpty() && prefixSuggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(prefixSuggestions.size) { index ->
                    val ps = prefixSuggestions[index]
                    AssistChip(
                        onClick = {
                            val query = ps.text
                            queryText = query
                            queryVisible = true
                            onDebouncedQueryChange(query)
                            SearchHistory.add(query)
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📄", fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                // 文件名含扩展名，可能较长，单行截断避免撑爆 chip。
                                Text(ps.text, fontSize = 13.sp, maxLines = 1)
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    }
}

/**
 * 后端操作类型 → 展示 emoji 映射。未知类型回退为圆点，保证不空白。
 */
private fun actionEmoji(action: String): String = when (action.lowercase()) {
    "search" -> "🔍"
    "delete" -> "🗑"
    "rename" -> "✏️"
    "rotate" -> "🔄"
    "upload" -> "⬆️"
    "share" -> "🔗"
    "tag", "add_tag", "remove_tag" -> "🏷"
    "download" -> "⬇️"
    "move" -> "📦"
    else -> "•"
}

/**
 * 后端 created_at（RFC3339，如 2026-07-31T12:34:56Z）→ 相对时间文案（"刚刚"/"3分钟前"等）。
 *
 * 纯 Kotlin 解析：提取 `YYYY-MM-DDTHH:MM:SS` 部分拼成 epoch 秒近似值，与当前秒数比较。
 * 解析失败回退截断原串（去掉 T 后的时分），避免可见技术细节。容错优先——展示用途，不追求精度。
 */
private fun relativeTime(createdAt: String): String {
    if (createdAt.isBlank()) return ""
    // 提取 YYYY-MM-DDTHH:MM:SS（兼容带/不带时区后缀）。
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(createdAt)
        ?: return createdAt.substringBefore('T').ifBlank { createdAt }
    val (y, mo, d, h, mi, s) = match.destructured
    // 粗略转 epoch 秒：用 UTC 日历近似（忽略闰秒/时区偏移，相对展示足够）。
    val epochSec = try {
        approxEpochSeconds(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
    } catch (e: Exception) {
        return createdAt.substringBefore('T')
    }
    // KMP 可移植取当前时间（kotlin.time.Clock，Kotlin 2.1+）。
    val nowSec = Clock.System.now().toEpochMilliseconds() / 1000
    val delta = nowSec - epochSec
    return when {
        delta < 0 -> "刚刚"
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60}分钟前"
        delta < 86400 -> "${delta / 3600}小时前"
        delta < 2592000 -> "${delta / 86400}天前"
        else -> "${delta / 2592000}个月前"
    }
}

/**
 * UTC 日历近似 epoch 秒（1970-01-01 起算）。仅用于相对时间展示，不追求历法精确。
 * 算法：逐年累加天数（闰年按 366）×86400，加当年已过天数与日内秒数。
 */
private fun approxEpochSeconds(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
    val daysPerMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var days: Long = 0L
    for (y in 1970 until year) {
        days += if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
    }
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    for (m in 1 until month) {
        days += if (m == 2 && leap) 29 else daysPerMonth[m - 1]
    }
    days += (day - 1)
    return days * 86400L + hour * 3600L + minute * 60L + second
}
