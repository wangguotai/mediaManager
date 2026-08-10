package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.platform.logger.logger
import com.wgt.common.util.formatBytesToMB
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_delete
import mediamanager.composeapp.generated.resources.ic_info
import mediamanager.composeapp.generated.resources.ic_photo
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi


private const val TAG = "InsightsDashboardScreen"

/**
 * 数据看板屏幕 —— 从 [SettingsScreen] 抽离的所有数据洞察卡片入口。
 *
 * 原本嵌在 SettingsScreen 主函数体内的 16+ 数据洞察卡片（仪表盘/健康度/智能洞察/活跃度/
 * 存储健康/错误检查/完整性/重复检测/深度存储/归档/组织/数据概览/生命周期/时间线/覆盖率/
 * 存储分析/清理建议/年度报告等）在此集中展示。SettingsScreen 仅保留一个"数据看板"入口按钮，
 * 点击跳转到本屏幕。
 *
 * 用 [verticalScroll] 承载长内容（与原 SettingsScreen 同款滚动模式，保持视觉一致）。
 * 所有 state/dialog 与原 SettingsScreen 一一对应，行为不变。
 *
 * @param onBack 返回 SettingsScreen
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class, ExperimentalLayoutApi::class)
@Composable
fun InsightsDashboardScreen(
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 部分洞见卡片（存储分析/清理建议/媒体生命周期等）原在 SettingsScreen 中复用 orphanScope，
    // 此处独立声明一份等价的协程作用域，行为不变。
    val orphanScope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // V9：年度回顾对话框状态
    var showYearlyReview by remember { mutableStateOf(false) }
    var yearlyReview by remember { mutableStateOf<MediaService.YearlyReview?>(null) }
    var yearlyReviewYear by remember { mutableStateOf(2026) }

    // V16：重复文件详情对话框状态
    var showDuplicateReport by remember { mutableStateOf(false) }
    var duplicateReport by remember { mutableStateOf<MediaService.DupReport?>(null) }

    // V21：数据导出（full-report）对话框状态
    var showExportDialog by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }   // null=未加载, ""=失败占位
    var isExporting by remember { mutableStateOf(false) }

    // V26：批量导出（media-bulk-export）状态。
    // showBulkFilterDialog 控制筛选输入对话框（type/tag/日期范围）；showBulkResultDialog
    // 控制结果展示对话框（与 ExportReportDialog 同款 JSON 滚动+复制）。bulkExportJson 缓存
    // 结果 JSON，null=未加载/""=失败占位。isBulkExporting 控制按钮 loading。
    var showBulkFilterDialog by remember { mutableStateOf(false) }
    var showBulkResultDialog by remember { mutableStateOf(false) }
    var bulkExportJson by remember { mutableStateOf<String?>(null) }
    var isBulkExporting by remember { mutableStateOf(false) }
    // 筛选条件输入态（仅在筛选 Dialog 内编辑，确认后冻结为本次请求的实际参数）
    var bulkType by remember { mutableStateOf("") }
    var bulkTag by remember { mutableStateOf("") }
    var bulkDateFrom by remember { mutableStateOf("") }
    var bulkDateTo by remember { mutableStateOf("") }

    // 一键删除重复确认对话框 + 执行状态（完整性报告卡片触发）。
    // showBatchDeleteDialog 控制弹窗；batchDeleting 控制按钮 loading；
    // 用 integrityReport.duplicates 的 count/reclaimableBytes 作确认提示文案。
    // integrityReport/integrityLoading 提升到此处（原在完整性报告卡片内部声明），
    // 以便上方确认 Dialog 与下方卡片都能访问——Dialog 显示前需要读 duplicates 数据。
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var batchDeleting by remember { mutableStateOf(false) }
    var integrityReport by remember { mutableStateOf<MediaService.MediaIntegrityReport?>(null) }
    var integrityLoading by remember { mutableStateOf(true) }

    // V：年度报告全屏 Dialog 状态（调 media-summary-report，8 维度精美报告）。
    // showSummaryReport 控制全屏 Dialog 开关；summaryReport 缓存拉取结果，
    // null=未加载/"失败"，关闭时清空以便下次重新拉取。year 固定 2026（可扩展年度切换）。
    var showSummaryReport by remember { mutableStateOf(false) }
    var summaryReport by remember { mutableStateOf<MediaService.SummaryReport?>(null) }
    var summaryReportFailed by remember { mutableStateOf(false) }
    // V9：年度回顾对话框——打开时加载数据，关闭时清空以便下次重新拉取
    if (showYearlyReview) {
        LaunchedEffect(Unit) {
            if (yearlyReview == null) {
                yearlyReview = MediaService.getYearlyReview(yearlyReviewYear)
            }
        }
        YearlyReviewDialog(
            year = yearlyReviewYear,
            review = yearlyReview,
            onDismiss = { showYearlyReview = false }
        )
    }

    // 年度报告全屏 Dialog——打开时调 media-summary-report 拉取 8 维度数据，
    // 失败时标记 summaryReportFailed 以展示错误态。关闭时清空缓存以便下次重新拉取。
    if (showSummaryReport) {
        LaunchedEffect(Unit) {
            if (summaryReport == null && !summaryReportFailed) {
                val r = MediaService.getMediaSummaryReport(2026)
                if (r != null) {
                    summaryReport = r
                } else {
                    summaryReportFailed = true
                }
            }
        }
        YearlyReportDialog(
            year = 2026,
            report = summaryReport,
            failed = summaryReportFailed,
            onShare = {
                scope.launch {
                    snackbarHostState.showSnackbar("年度报告已生成，可截图分享 📸")
                }
            },
            onExport = {
                // 复用 full-report JSON 导出：打开 ExportReportDialog 展示可复制的综合报告 JSON
                exportJson = null
                showExportDialog = true
                showSummaryReport = false
            },
            onDismiss = {
                showSummaryReport = false
                summaryReport = null
                summaryReportFailed = false
            }
        )
    }

    // V16：重复文件详情对话框——打开时加载，关闭时清空以便下次重新拉取
    if (showDuplicateReport) {
        LaunchedEffect(Unit) {
            if (duplicateReport == null) {
                duplicateReport = MediaService.getDupReport()
            }
        }
        DuplicateReportDialog(
            report = duplicateReport,
            onDismiss = { showDuplicateReport = false }
        )
    }

    // V21：数据导出对话框——打开时加载 full-report JSON，展示后可复制到剪贴板
    if (showExportDialog) {
        LaunchedEffect(Unit) {
            if (exportJson == null) {
                isExporting = true
                exportJson = MediaService.getFullReport(2026) ?: ""
                isExporting = false
            }
        }
        ExportReportDialog(
            json = exportJson,
            isExporting = isExporting,
            onCopy = {
                val text = exportJson
                if (!text.isNullOrEmpty()) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
                }
            },
            onDismiss = {
                showExportDialog = false
                exportJson = null  // 关闭时清空，下次重新拉取
            }
        )
    }

    // V26：批量导出筛选 Dialog —— 编辑 type/tag/日期范围，确认后触发请求并打开结果对话框。
    if (showBulkFilterDialog) {
        BulkExportFilterDialog(
            type = bulkType,
            tag = bulkTag,
            dateFrom = bulkDateFrom,
            dateTo = bulkDateTo,
            onTypeChange = { bulkType = it },
            onTagChange = { bulkTag = it },
            onDateFromChange = { bulkDateFrom = it },
            onDateToChange = { bulkDateTo = it },
            onCancel = { showBulkFilterDialog = false },
            isExporting = isBulkExporting,
            onConfirm = {
                isBulkExporting = true
                scope.launch {
                    val result = MediaService.getMediaBulkExport(
                        type = bulkType,
                        tag = bulkTag,
                        dateFrom = bulkDateFrom,
                        dateTo = bulkDateTo
                    )
                    isBulkExporting = false
                    showBulkFilterDialog = false
                    bulkExportJson = result ?: ""
                    showBulkResultDialog = true
                }
            }
        )
    }

    // V26：批量导出结果 Dialog —— 展示筛选后导出的 JSON，结构同 ExportReportDialog
    if (showBulkResultDialog) {
        ExportReportDialog(
            json = bulkExportJson,
            isExporting = false,
            onCopy = {
                val text = bulkExportJson
                if (!text.isNullOrEmpty()) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
                }
            },
            onDismiss = {
                showBulkResultDialog = false
                bulkExportJson = null  // 关闭清空，下次重新拉取
            }
        )
    }

    // 一键删除重复确认对话框——基于完整性报告卡片的 duplicates 数据。
    // 文案动态展示将删除的重复份数（count）与可回收空间（reclaimableBytes）。
    // 确认后调 MediaService.batchDeleteDuplicates()，成功 Snackbar 汇报结果并刷新
    // 完整性报告（重新拉取，使卡片上的重复计数实时归零/下降）。
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!batchDeleting) showBatchDeleteDialog = false
            },
            title = { Text("确认删除重复文件") },
            text = {
                val rep = integrityReport
                if (rep != null && rep.duplicates.count > 0) {
                    Text(
                        "将删除 ${rep.duplicates.count} 个重复文件，" +
                            "释放约 ${formatBytesToMB(rep.duplicates.reclaimableBytes)} MB。\n\n" +
                            "每组重复仅保留最早上传的原始件，其余移入回收站，可随时恢复。"
                    )
                } else {
                    Text("没有检测到重复文件。")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !batchDeleting && (integrityReport?.duplicates?.count ?: 0) > 0,
                    onClick = {
                        batchDeleting = true
                        scope.launch {
                            val result = MediaService.batchDeleteDuplicates()
                            batchDeleting = false
                            if (result != null) {
                                showBatchDeleteDialog = false
                                snackbarHostState.showSnackbar(
                                    "已删除 ${result.deletedCount} 个，释放 ${formatBytesToMB(result.freedBytes)}"
                                )
                                // 刷新完整性报告，使卡片重复计数实时更新
                                integrityReport = MediaService.getMediaIntegrityReport()
                            } else {
                                snackbarHostState.showSnackbar("删除失败，请检查后端连接")
                            }
                        }
                    }
                ) {
                    if (batchDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !batchDeleting,
                    onClick = { showBatchDeleteDialog = false }
                ) { Text("取消") }
            }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "数据看板",
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
            // V24：仪表盘概览卡片 —— 一次调 /api/media/user-dashboard-v2 拿全部6维度数据。
            // 实现（含加载/空态/六维度渲染）抽到 [DashboardOverviewV2Card] 独立 @Composable，
            // 避免 SettingsScreen 主函数体过大触发 JVM 方法 64KB 上限（MethodTooLargeException）。
            DashboardOverviewV2Card()
            Spacer(modifier = Modifier.height(8.dp))

            // V8：媒体库总健康卡片 —— 调 /api/media/media-collection-health 一次拿五维度综合评分。
            // 独立 @Composable（自取数据），避免主函数体过大；放在仪表盘后、智能洞察前，
            // 作为媒体库健康的综合总览（A绿/B蓝/C橙/D红 + 五维度迷你条 + 建议前3）。
            CollectionHealthCard()
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V23：智能洞察卡片 —— 调 /api/media/insights 显示自动分析建议（重复/存储/习惯/未标签/相册/健康度）。
            // 放在"存储健康度"前，作为首屏可操作洞察汇总；后端即将提供该端点（端点未上线时按空态提示）。
            var mediaInsights by remember { mutableStateOf<List<MediaService.Insight>?>(null) }
            var mediaInsightsLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                mediaInsights = MediaService.getMediaInsights()
                mediaInsightsLoading = false
            }
            SectionTitle("💡 智能洞察", iconRes = Res.drawable.ic_info)
            if (mediaInsightsLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaInsights == null) {
                Text(
                    "无法获取智能洞察",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaInsights!!.isEmpty()) {
                Text(
                    "暂无洞察建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                mediaInsights!!.forEach { ins ->
                    // type → emoji 映射：duplicate→🔄, storage→📦, habit→⏰, untagged→🏷️, album→📁, health→❤️
                    val emoji = when (ins.type) {
                        "duplicate" -> "🔄"
                        "storage" -> "📦"
                        "habit" -> "⏰"
                        "untagged" -> "🏷️"
                        "album" -> "📁"
                        "health" -> "❤️"
                        else -> "💡"
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                ins.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (ins.detail.isNotEmpty()) {
                            Text(
                                ins.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V24：活跃度评分卡片 —— 调 /api/media/user-activity-score 显示
            // 加权总分 + 等级徽章 + 总进度条 + 分维度明细（每行 action emoji + count + points）。
            // 紧跟智能洞察之后，作为用户活跃度的量化总览；失败显示错误态。
            var activityScore by remember { mutableStateOf<MediaService.UserActivityScore?>(null) }
            var activityScoreLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                activityScore = MediaService.getUserActivityScore()
                activityScoreLoading = false
            }
            SectionTitle("📊 活跃度评分", iconRes = Res.drawable.ic_info)
            if (activityScoreLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (activityScore == null) {
                Text(
                    "无法获取活跃度评分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val a = activityScore!!
                // 等级颜色：新手灰 / 活跃蓝 / 达人橙 / 专家绿（默认灰）
                val levelColor = when (a.level) {
                    "新手" -> MaterialTheme.colorScheme.outline
                    "活跃" -> Color(0xFF2196F3)
                    "达人" -> Color(0xFFFF9800)
                    "专家" -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.outline
                }
                // 大字号评分 + 等级徽章
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${a.score}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = levelColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(levelColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            a.level.ifEmpty { "-" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "共 ${a.totalActions} 次操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 总进度条：score / 100（任务明确要求进度条，用 LinearProgressIndicator）
                LinearProgressIndicator(
                    progress = { (a.score / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = levelColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "分维度明细",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // 分维度明细：每行 action emoji + 名称 + 次数 + 贡献分
                a.breakdown.forEach { b ->
                    val emoji = when (b.action) {
                        "upload" -> "📤"
                        "favorite" -> "⭐"
                        "share" -> "🔗"
                        "tag" -> "🏷️"
                        "rename" -> "✏️"
                        "rotate" -> "🔄"
                        else -> "•"
                    }
                    val name = when (b.action) {
                        "upload" -> "上传"
                        "favorite" -> "收藏"
                        "share" -> "分享"
                        "tag" -> "打标签"
                        "rename" -> "重命名"
                        "rotate" -> "旋转"
                        else -> b.action
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$emoji $name", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${b.count} 次 · +${b.points} 分",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V22：存储健康度卡片 —— 调 /api/media/storage-health 显示评分+等级+比例条+建议。
            // 放在"数据概览"前，作为首屏健康度总览；后端即将提供该端点。
            var storageHealth by remember { mutableStateOf<MediaService.StorageHealth?>(null) }
            var storageHealthLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                storageHealth = MediaService.getStorageHealth()
                storageHealthLoading = false
            }
            SectionTitle("存储健康度", iconRes = Res.drawable.ic_info)
            if (storageHealthLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageHealth == null) {
                Text(
                    "无法获取存储健康度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val sh = storageHealth!!
                // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红（默认灰）
                val gradeColor = when (sh.grade.firstOrNull()?.uppercaseChar()) {
                    'A' -> Color(0xFF4CAF50)
                    'B' -> Color(0xFF2196F3)
                    'C' -> Color(0xFFFF9800)
                    'D' -> Color(0xFFF44336)
                    else -> MaterialTheme.colorScheme.outline
                }
                // 大字号评分 + 等级徽章
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${sh.score}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(gradeColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            sh.grade.ifEmpty { "-" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 3 条比例条：重复率 / 配额使用 / 数据温度
                HealthRatioBar(
                    label = "重复率",
                    fraction = sh.duplicateRate.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFFEF5350)
                )
                HealthRatioBar(
                    label = "配额使用",
                    fraction = sh.quotaUsage.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFF42A5F5)
                )
                HealthRatioBar(
                    label = "数据温度",
                    fraction = sh.ageScore.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFFFFA726)
                )
                // 建议列表：每条一行 📌 + 文字
                if (sh.suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    sh.suggestions.forEach { tip ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("📌", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                tip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 存储效率卡片 —— 调 /api/media/media-storage-efficiency 显示评分+等级+密度+建议。
            // 与上方"存储健康度"互补：健康度关注重复/配额/冷数据，本卡片聚焦"每 MB 媒体密度"与
            // 平均大小是否拖累空间利用率。独立 @Composable，自取数据（见文件末尾 [StorageEfficiencyCard]）。
            StorageEfficiencyCard()

            // V25：媒体错误检查卡片 —— 调 /api/media/media-error-check 显示损坏文件列表。
            // 放在"存储健康度"之后、"数据概览"之前。展示检查项数 + 错误数 + 错误列表（仅
            // totalErrors>0 时显示列表），并提供"重新检查"按钮触发重新拉取。
            var mediaErrorReport by remember { mutableStateOf<MediaService.MediaErrorReport?>(null) }
            var mediaErrorLoading by remember { mutableStateOf(true) }
            val mediaErrorScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                mediaErrorReport = MediaService.getMediaErrorCheck()
                mediaErrorLoading = false
            }
            SectionTitle("🔍 媒体错误检查", iconRes = Res.drawable.ic_info)
            if (mediaErrorLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaErrorReport == null) {
                Text(
                    "无法获取媒体错误检查结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val report = mediaErrorReport!!
                // 汇总行：检查 N 项，发现 M 个错误
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "检查 ${report.totalChecked} 项，发现 ${report.totalErrors} 个错误",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            mediaErrorLoading = true
                            mediaErrorScope.launch {
                                mediaErrorReport = MediaService.getMediaErrorCheck()
                                mediaErrorLoading = false
                            }
                        }
                    ) { Text("重新检查", fontSize = 13.sp) }
                }
                // 错误列表：仅 totalErrors > 0 时显示，每行 filename + error_type
                if (report.totalErrors > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    report.errors.forEach { err ->
                        val errorTypeText = when (err.errorType) {
                            "zero_size" -> "数据损坏"
                            "missing_file" -> "文件缺失"
                            "size_mismatch" -> "大小不符"
                            else -> err.errorType.ifEmpty { "未知" }
                        }
                        val errorColor = when (err.errorType) {
                            "missing_file" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "⚠️",
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                err.filename.ifEmpty { err.mediaId },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                errorTypeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V25：完整性报告卡片 —— 调 /api/media/media-integrity-report 显示综合完整性
            // 评分 + A/B/C/D 等级 + 四维度（孤立/错误/重复/总媒体）统计。放在"媒体错误检查"
            // 之后、"数据概览"之前，作为存储健康类卡片的总结收尾。
            // 注：integrityReport/integrityLoading 提升到 Composable 顶部声明（供确认 Dialog 访问）。
            LaunchedEffect(Unit) {
                integrityReport = MediaService.getMediaIntegrityReport()
                integrityLoading = false
            }
            SectionTitle("🛡️ 完整性报告", iconRes = Res.drawable.ic_info)
            if (integrityLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (integrityReport == null) {
                Text(
                    "无法获取完整性报告",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val rep = integrityReport!!
                // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红
                val gradeColor = when (rep.grade) {
                    "A" -> Color(0xFF2E7D32)
                    "B" -> Color(0xFF1565C0)
                    "C" -> Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.error
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // 大字号：评分 + 等级 徽章
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "${rep.integrityScore}",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            "/ 100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            rep.grade,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // 进度条：score / 100
                    LinearProgressIndicator(
                        progress = { (rep.integrityScore / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = gradeColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 四维度统计行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.orphans.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("孤立", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.errors.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("错误", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.duplicates.groups}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("重复组", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.totalMedia}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("总媒体", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    // 重复可回收空间（仅有重复时显示）
                    if (rep.duplicates.reclaimableBytes > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "重复可回收 ${formatBytesToMB(rep.duplicates.reclaimableBytes)} MB（${rep.duplicates.count} 份）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    // 一键删除重复按钮：存在重复组时展示。点击弹确认 Dialog（由上方
                    // showBatchDeleteDialog 控制），后端按 SHA256 分组保留最早原件、其余软删。
                    if (rep.duplicates.count > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showBatchDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) { Text("一键删除重复") }
                        Text(
                            "保留每组最早原件，其余移入回收站可恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 近似重复检测卡片 —— 调 /api/media/media-duplicates-similar 显示近似重复文件对
            // （SHA256 不同但同类型+大小相近+同分辨率），与完整性报告/精确重复互补。
            var dupSimilarPairs by remember { mutableStateOf<List<MediaService.DupSimilarPair>?>(null) }
            var dupSimilarLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                dupSimilarPairs = MediaService.getMediaDuplicatesSimilar()
                dupSimilarLoading = false
            }
            SectionTitle("🔍 近似重复检测", iconRes = Res.drawable.ic_info)
            if (dupSimilarLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (dupSimilarPairs == null) {
                Text(
                    "无法获取近似重复检测",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (dupSimilarPairs!!.isEmpty()) {
                Text(
                    "未发现近似重复文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val pairs = dupSimilarPairs!!
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "发现 ${pairs.size} 对近似重复",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 最多展示 5 对
                    pairs.take(5).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                pair.filenameA.ifEmpty { pair.mediaAId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text("↔", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text(
                                pair.filenameB.ifEmpty { pair.mediaBId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            "${formatBytesToMB(pair.size)} MB · ${pair.resolution}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 深度存储分析卡片 —— 调 /api/media/storage-deep-analysis 的 by_year_type 矩阵，
            // 按"年份 → 图片 N MB + 视频 N MB"展示最近 3 年的存储分布。与上方"近似重复"互补：
            // 那个找冗余，这个看时间维度分布。与完整性报告/存储健康度同款 LaunchedEffect 拉取。
            var storageDeep by remember { mutableStateOf<MediaService.StorageDeepAnalysis?>(null) }
            var storageDeepLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                storageDeep = MediaService.getStorageDeepAnalysis()
                storageDeepLoading = false
            }
            SectionTitle("📊 深度存储分析", iconRes = Res.drawable.ic_info)
            if (storageDeepLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageDeep == null) {
                Text(
                    "无法获取深度存储分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageDeep!!.byYearType.isEmpty()) {
                Text(
                    "暂无媒体数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                // 年份按字符串降序（4 位年份同字典序），取最近 3 年。
                val years = storageDeep!!.byYearType.keys.sortedDescending().take(3)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    years.forEach { year ->
                        val typeMap = storageDeep!!.byYearType[year].orEmpty()
                        val img = typeMap["IMAGE"]
                        val vid = typeMap["VIDEO"]
                        val lp = typeMap["LIVE_PHOTO"]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$year 年",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                "图片 ${img?.count ?: 0} · ${formatBytesToMB((img?.bytes ?: 0L).toDouble())} MB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "视频 ${vid?.count ?: 0} · ${formatBytesToMB((vid?.bytes ?: 0L).toDouble())} MB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 动态照片若该年有数据则补一行（与图片/视频同口径，次要展示）。
                        if (lp != null && (lp.count > 0 || lp.bytes > 0L)) {
                            Text(
                                "　　动态照片 ${lp.count} · ${formatBytesToMB(lp.bytes.toDouble())} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "合计 ${storageDeep!!.totalCount} 个 · ${formatBytesToMB(storageDeep!!.totalBytes.toDouble())} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            StorageMatrixCard()

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V14：存储增长预测卡片（GET /api/media/storage-growth-prediction）——
            // 未来 3/6/12 个月用量预测 + 预计配额耗尽日期。独立 @Composable，自取数据。
            StoragePredictionCard()

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V22：归档建议卡片 —— 调 /api/media/archive-suggest 显示冷数据 + 大视频归档候选，
            // 列出每项文件名 / 大小 / 归档年龄，并给出可释放空间汇总。
            // 放在"完整性报告"之后、\"数据概览\"之前。
            var archiveSuggest by remember { mutableStateOf<MediaService.ArchiveSuggest?>(null) }
            var archiveSuggestLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                archiveSuggest = MediaService.getArchiveSuggest()
                archiveSuggestLoading = false
            }
            SectionTitle("📦 归档建议", iconRes = Res.drawable.ic_info)
            if (archiveSuggestLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (archiveSuggest == null) {
                Text(
                    "无法获取归档建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (archiveSuggest!!.shouldArchive.not()) {
                Text(
                    "暂无需归档的冷数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val sug = archiveSuggest!!
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "📦 建议归档 ${sug.totalCount} 项冷数据（可释放 ${formatDouble2(sug.potentialSavingsMb)} MB）",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    sug.mediaToArchive.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                item.filename.ifEmpty { item.mediaId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${formatBytesToMB(item.size)} MB · ${item.ageDays} 天",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V26：归档建议V2卡片 —— 调 /api/media/media-archive-recommend-v2 显示多维度评分
            // 的归档推荐（评分 + 命中原因 + 潜在节省空间）。独立 @Composable，自取数据并解析
            // 后端返回的 JSON 字符串（MediaService.getMediaArchiveRecommendV2 返回原始字符串）。
            // 放在 V22 归档建议之后、V25 照片组织建议之前。最多展示 top 5 评分条目。
            ArchiveRecommendV2Card()

            // V27：一键清理计划卡片 —— 调 /api/media/media-cleanup-plan 显示按优先级（score 倒序）
            // 合并的清理清单（重复/孤儿/错误/归档），顶部汇总 N 项建议 · 可回收 X MB，
            // top 5 优先级条目（type emoji + filename + score + action）。
            // 独立 @Composable，自取数据；紧跟归档建议后，作为"减少存量"系列的操作入口。
            CleanupPlanCard()

            // AI 洞察报告卡片 —— 调 /api/media/media-ai-insights 显示个性化建议清单
            // （重复/归档/标签/存储/活跃度多维度综合），按优先级排序，high=红/medium=橙/low=蓝
            // 着色。独立 @Composable，自取数据；紧跟清理计划后，作为"智能建议"系列收尾。
            AIInsightsCard()

            // V8 §1.4：AI 清理建议卡片 —— 调 /api/media/media-ai-cleanup-suggestions 显示
            // 低分辨率/截图/超大图/极小文件/缺拍摄时间五类可清理候选，每类显示数量+可回收空间。
            AiCleanupSuggestionsCard()

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V25：照片组织建议卡片 —— 调 /api/media/photo-organize-suggest 显示按
            // 月份/类型/未标签维度产出的组织建议，每条附"创建相册"一键按钮
            // （调 createAlbum + batchAddMediaToAlbum(previewIds)）。
            // 放在"归档建议"之后、"数据概览"之前。最多展示 5 条（后端阈值保守，
            // 通常条目不多，仍 cap 以防极端库产出长列表）。
            var photoOrganize by remember { mutableStateOf<List<MediaService.OrganizeSuggestion>?>(null) }
            var photoOrganizeLoading by remember { mutableStateOf(true) }
            // 记录正在创建相册的建议 name（按钮 loading 态），空串=空闲。同一时刻仅一条在创建。
            var creatingAlbumFor by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                photoOrganize = MediaService.getPhotoOrganizeSuggest()
                photoOrganizeLoading = false
            }
            SectionTitle("📁 照片组织建议", iconRes = Res.drawable.ic_info)
            if (photoOrganizeLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (photoOrganize == null) {
                Text(
                    "无法获取照片组织建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (photoOrganize!!.isEmpty()) {
                Text(
                    "暂无组织建议，媒体库已井井有条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                photoOrganize!!.take(5).forEach { sug ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 📁 建议名 (N 项)
                            Text(
                                "📁 ${sug.name}（${sug.mediaCount} 项）",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            sug.reason,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 一键创建相册：createAlbum(name) → batchAddMediaToAlbum(albumId, previewIds)。
                        // previewIds 为后端给出的预览子集（≤4），作为相册初始成员；
                        // 创建中禁用按钮防重复点击；结果经 Snackbar 反馈。
                        OutlinedButton(
                            onClick = {
                                if (creatingAlbumFor.isNotEmpty()) return@OutlinedButton
                                val ids = sug.previewIds
                                if (ids.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("该建议无可加入相册的预览媒体") }
                                    return@OutlinedButton
                                }
                                creatingAlbumFor = sug.name
                                scope.launch {
                                    val albumName = sug.name.ifEmpty { "新建相册" }
                                    val album = MediaService.createAlbum(albumName)
                                    if (album == null) {
                                        creatingAlbumFor = ""
                                        snackbarHostState.showSnackbar("创建相册失败")
                                        return@launch
                                    }
                                    val added = MediaService.batchAddMediaToAlbum(album.id, ids)
                                    creatingAlbumFor = ""
                                    if (added == null) {
                                        snackbarHostState.showSnackbar("相册已创建，但加入媒体失败")
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            "已创建相册「${album.name}」，加入 $added 项"
                                        )
                                    }
                                }
                            },
                            enabled = creatingAlbumFor.isEmpty() || creatingAlbumFor == sug.name
                        ) {
                            if (creatingAlbumFor == sug.name) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("创建中…")
                            } else {
                                Text("创建相册")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 存储审计卡片 —— 调 /api/media/media-storage-audit 显示综合审计评分 + 四维度（孤立/
            // 错误/重复组/近似重复对）+ 建议列表。后端单次遍历合并四类检查 + 评分，替代前端并发
            // 拉 cleanup-orphan/orphan-check/error-check/duplicates/duplicates-similar 五个端点。
            StorageAuditCard()

            // V9：数据概览卡片 —— 一次调 /api/media/stat-summary 拿多组汇总数据
            // （媒体总数 / 图片·视频·Live 计数 / 收藏 / 分享 / 相册 / 回收站），
            // 替代为分散统计多次请求。后端 best-effort：子统计失败回退零值，前端据此渲染。
            var statSummary by remember { mutableStateOf<MediaService.StatSummary?>(null) }
            var statSummaryLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                statSummary = MediaService.getStatSummary()
                statSummaryLoading = false
            }
            SectionTitle("数据概览", iconRes = Res.drawable.ic_info)
            if (statSummaryLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (statSummary == null) {
                Text(
                    "无法获取数据概览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val s = statSummary!!.summary
                // 第一行：媒体总数 + 图片/视频/Live 计数
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("媒体总数", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${s.totalCount} 项",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 按类型拆分 + 收藏/分享/相册/回收站：多列 Row 布局展示
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "图片",
                        value = s.imageCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "视频",
                        value = s.videoCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Live",
                        value = s.liveCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "收藏",
                        value = statSummary!!.favorites,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "分享",
                        value = statSummary!!.shares,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "相册",
                        value = statSummary!!.albums,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "回收站",
                        value = statSummary!!.trash,
                        modifier = Modifier.weight(1f)
                    )
                    // 配额百分比：占位单元格，使本行与前一行对齐为四列
                    StatCell(
                        label = "配额",
                        valueText = "${statSummary!!.quota.usagePercent.toInt()}%",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // V8：媒体生命周期卡片
            var lifecycleData by remember { mutableStateOf<List<MediaService.LifecycleStage>?>(null) }
            LaunchedEffect(Unit) {
                orphanScope.launch { lifecycleData = MediaService.getMediaLifecycle() }
            }
            SectionTitle("媒体生命周期", iconRes = Res.drawable.ic_info)
            lifecycleData?.let { stages ->
                if (stages.isNotEmpty()) {
                    stages.take(8).forEach { stg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${stg.stage} (${stg.action})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${stg.count} 次 · ${stg.percentage.toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Text("暂无操作记录", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                }
            }
            // V9：年度回顾入口——点击弹 Dialog 展示该年上传统计详情
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("年度回顾", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    yearlyReview = null  // 清空以触发重新拉取
                    showYearlyReview = true
                }) {
                    Text("查看年度回顾")
                }
            }
            // 年度报告入口——点击弹全屏 Dialog，调 media-summary-report 展示 8 维度精美报告
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("年度报告", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "8 大维度精美年度报告",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Button(onClick = {
                    summaryReport = null
                    summaryReportFailed = false
                    showSummaryReport = true
                }) {
                    Text("📸 查看年度报告")
                }
            }
            // V21：数据导出——一键拉取 full-report 综合报告 JSON，弹窗展示供复制/分享
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("数据导出", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "导出年度综合报告（JSON）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            TextButton(
                onClick = {
                    exportJson = null  // 清空以触发重新拉取
                    showExportDialog = true
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出中…")
                } else {
                    Text("导出报告")
                }
            }
            // V8：操作历史统计卡片（GET /api/media/audit-log/stats）
            var auditStats by remember { mutableStateOf<List<MediaService.AuditLogStat>?>(null) }
            LaunchedEffect(Unit) { auditStats = MediaService.getAuditLogStats() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("操作历史", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        auditStats == null -> "加载中..."
                        auditStats!!.isEmpty() -> "暂无记录"
                        else -> "${auditStats!!.sumOf { it.count }} 次操作"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            auditStats?.forEach { stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stat.action, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${stat.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            // V8：操作时间线卡片（GET /api/media/audit-timeline?limit=N）
            // 逐条展示最近操作，每行：emoji + detail + 相对时间；最多渲染 15 条，
            // 底部"加载更多"按钮按 +50 递增 limit 重新拉取。
            var auditTimeline by remember { mutableStateOf<List<MediaService.AuditTimelineItem>?>(null) }
            var auditTimelineLimit by remember { mutableStateOf(50) }
            var auditTimelineLoading by remember { mutableStateOf(true) }
            LaunchedEffect(auditTimelineLimit) {
                auditTimelineLoading = true
                auditTimeline = MediaService.getAuditTimeline(auditTimelineLimit)
                auditTimelineLoading = false
            }
            SectionTitle("操作时间线", iconRes = Res.drawable.ic_info)
            auditTimeline?.let { items ->
                if (items.isNotEmpty()) {
                    items.take(15).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                auditActionEmoji(entry.action),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                entry.detail.ifEmpty { entry.action },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.relativeTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // 已加载但未展示完（items > 15）或已达 limit 上限时可继续加载更多
                    if (items.size >= 15) {
                        TextButton(
                            onClick = { auditTimelineLimit += 50 },
                            enabled = !auditTimelineLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (auditTimelineLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("加载中…")
                            } else {
                                Text("加载更多")
                            }
                        }
                    }
                } else {
                    Text(
                        "暂无操作记录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            } ?: if (auditTimelineLoading) {
                Text(
                    "加载中...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            } else {
                Text(
                    "加载失败",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }
            // 重命名历史卡片（GET /api/media/media-rename-history?limit=50）
            // 展示最近媒体重命名记录，每行 ✏️ old → new (date)，最多 10 条。
            // 独立 [RenameHistoryCard] @Composable，自取数据，见文件末尾定义。
            RenameHistoryCard()
            // 分享历史卡片（GET /api/media/media-share-history?limit=50）
            // 展示最近媒体分享记录，每行 🔗 detail (date)，最多 10 条。
            // 独立 [ShareHistoryCard] @Composable，自取数据，见文件末尾定义。
            ShareHistoryCard()
            // 上传延迟分析卡片（GET /api/media/media-time-analysis）—— 拍摄→上传延迟分布。
            // 抽成独立 [UploadDelayCard] @Composable，避免主函数体过大（见文件末尾定义）。
            UploadDelayCard()
            // 使用习惯分析卡片（GET /api/media/media-session-stats）—— 会话维度统计。
            // 独立 [SessionStatsCard] @Composable，自取数据，见文件末尾定义。
            SessionStatsCard()
            // V23：媒体覆盖率卡片（GET /api/media/media-coverage）
            // 显示标签/收藏/分享/相册 4 个维度的覆盖率，每行带 LinearProgressIndicator。
            var mediaCoverage by remember { mutableStateOf<MediaService.MediaCoverage?>(null) }
            var mediaCoverageLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                mediaCoverage = MediaService.getMediaCoverage()
                mediaCoverageLoading = false
            }
            SectionTitle("媒体覆盖率", iconRes = Res.drawable.ic_info)
            if (mediaCoverageLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaCoverage == null) {
                Text(
                    "无法获取媒体覆盖率",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val mc = mediaCoverage!!
                val total = mc.total
                val rows = listOf(
                    Triple("🏷️", "已标签", mc.tagged),
                    Triple("⭐", "已收藏", mc.favorited),
                    Triple("🔗", "已分享", mc.shared),
                    Triple("📁", "在相册", mc.inAlbum)
                )
                rows.forEach { (icon, label, item) ->
                    CoverageRow(icon, label, item.count, total, item.percent)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            // V8：存储分析卡片（GET /api/media/storage-breakdown）
            var storageBreakdown by remember { mutableStateOf<MediaService.StorageBreakdown?>(null) }
            var storageLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                orphanScope.launch {
                    storageBreakdown = MediaService.getStorageBreakdown()
                    storageLoading = false
                }
            }
            SectionTitle("存储分析", iconRes = Res.drawable.ic_photo)
            if (storageLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageBreakdown == null) {
                Text(
                    "无法获取存储统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val b = storageBreakdown!!
                // 图片 / 视频 / Live 三行：彩色小圆点 + 名称 + 数量与占用
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.primary,          // 蓝
                    label = "图片",
                    count = b.imageCount,
                    bytes = b.imageBytes
                )
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.error,            // 红
                    label = "视频",
                    count = b.videoCount,
                    bytes = b.videoBytes
                )
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.tertiary,         // 绿
                    label = "Live",
                    count = b.liveCount,
                    bytes = b.liveBytes
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "总计",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${b.totalCount} 项 · ${formatBytesToMB(b.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            // V15：清理建议卡片（GET /api/media/storage-recommendations + POST duplicate-cleanup）
            var recs by remember { mutableStateOf<MediaService.StorageRecommendations?>(null) }
            var recsLoading by remember { mutableStateOf(true) }
            var cleaning by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                orphanScope.launch {
                    recs = MediaService.getStorageRecommendations()
                    recsLoading = false
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 6.dp)
            )
            SectionTitle("清理建议", iconRes = Res.drawable.ic_delete)
            if (recsLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (recs == null) {
                Text(
                    "无法获取清理建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val r = recs!!
                // 🔄 重复文件
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔄 重复文件", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.duplicates.count} 个 · 可回收 ${formatBytesToMB(r.totalReclaimableBytes.toDouble())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // V16：查看重复文件详情按钮——弹 Dialog 列出每组重复的文件名/大小/可回收空间
                TextButton(
                    onClick = {
                        duplicateReport = null  // 清空以触发重新拉取
                        showDuplicateReport = true
                    },
                    enabled = r.duplicates.count > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("查看重复详情") }
                // 📦 大文件（top 3，后端已按 size 倒序，本地取前 3）
                if (r.largeFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📦 大文件 · top ${minOf(3, r.largeFiles.size)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    r.largeFiles.take(3).forEach { lf ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                                .padding(start = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lf.filename.ifEmpty { "(未命名)" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                formatBytesToMB(lf.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                // 📅 旧文件
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📅 旧文件", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.oldFiles.count} 个 · ${formatBytesToMB(r.oldFiles.bytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 总计可回收
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "总计可回收",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        formatBytesToMB(r.totalReclaimableBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 一键清理重复按钮（仅在有重复时启用）
                Button(
                    onClick = {
                        if (cleaning) return@Button
                        cleaning = true
                        orphanScope.launch {
                            val deleted = MediaService.cleanupDuplicates()
                            cleaning = false
                            if (deleted != null) {
                                if (deleted > 0) {
                                    snackbarHostState.showSnackbar("已清理 $deleted 个重复文件")
                                } else {
                                    snackbarHostState.showSnackbar("无重复文件可清理")
                                }
                                // 刷新建议数据（清理后重复数应降为 0）
                                recs = MediaService.getStorageRecommendations()
                            } else {
                                snackbarHostState.showSnackbar("清理失败，请重试")
                            }
                        }
                    },
                    enabled = !cleaning && r.duplicates.count > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    if (cleaning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("清理中...")
                    } else {
                        Text("一键清理重复（${r.duplicates.count}）")
                    }
                }
            }
        }
    }
}



