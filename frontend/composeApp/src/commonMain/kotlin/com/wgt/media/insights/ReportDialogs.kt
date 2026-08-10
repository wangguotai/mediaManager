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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.common.util.formatBytesToMB
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.feature.media.MediaService


/**
 * 年度报告全屏 Dialog——调 [MediaService.getMediaSummaryReport]（media-summary-report 端点）
 * 展示 8 大维度精美年度报告：统计概览 / 月度分布 / 标签 Top5 / 健康度 / 活跃度 /
 * 多样性 / 月度亮点 / 上传排行。
 *
 * 采用 [Dialog] + [DialogProperties]`usePlatformDefaultWidth = false` 实现全屏，与
 * [ImageEditor] 同款全屏模式。内容区垂直可滚动（[verticalScroll]），顶部带标题栏 +
 * 关闭按钮，底部带\"分享\"/\"导出\"按钮。
 *
 * 三态：
 * - [report] == null 且 [failed] == false：加载中（CircularProgressIndicator）
 * - [failed] == true：加载失败提示 + 重试
 * - [report] != null：8 维度精美展示
 *
 * @param year 年度（标题展示用）
 * @param report 已加载的报告；null 表示未加载/加载中
 * @param failed 是否加载失败
 * @param onShare 分享回调（提示用户截图分享）
 * @param onExport 导出回调（打开 full-report JSON 导出 Dialog）
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun YearlyReportDialog(
    year: Int,
    report: MediaService.SummaryReport?,
    failed: Boolean,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // —— 顶部标题栏 ——
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📸 $year 年度报告",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                HorizontalDivider()

                // —— 内容区 ——
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        failed -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "加载年度报告失败 请检查后端连接后重试",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = onDismiss) { Text("关闭") }
                            }
                        }
                        report == null -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("正在生成你的年度报告…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SummaryReportContent(report)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // —— 底部操作栏：分享 / 导出 ——
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        enabled = report != null
                    ) { Text("📤 分享") }
                    Button(
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                        enabled = report != null
                    ) { Text("📋 导出 JSON") }
                }
            }
        }
    }
}



/**
 * 年度报告内容——8 维度精美展示，仅 [report] 非 null 时调用。
 * 每个维度一个 [ReportSection] 卡片，带 emoji 标题 + 数据行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryReportContent(report: MediaService.SummaryReport) {
    // —— 维度 1：统计概览 ——
    ReportSection(title = "📊 统计概览") {
        ReportRow("媒体总数", "${report.stats.totalMedia}")
        ReportRow("图片", "${report.stats.imageCount}")
        ReportRow("视频", "${report.stats.videoCount}")
        if (report.stats.liveCount > 0) ReportRow("Live Photo", "${report.stats.liveCount}")
        ReportRow("相册", "${report.stats.albumCount}")
        ReportRow("收藏", "${report.stats.favoriteCount}")
        ReportRow("总占用", "${kotlin.math.round(report.stats.totalMB * 10.0) / 10.0} MB")
    }

    // —— 维度 2：月度分布 ——
    ReportSection(title = "📅 月度分布") {
        ReportRow("年度上传", "${report.yearly.totalCount} 项")
        ReportRow("年度体积", "${kotlin.math.round(report.yearly.totalMB * 10.0) / 10.0} MB")
        if (report.yearly.topDay.date.isNotEmpty() && report.yearly.topDay.count > 0) {
            ReportRow("最忙的一天", "${report.yearly.topDay.date}（${report.yearly.topDay.count} 项）")
        }
        if (report.yearly.firstUpload.isNotEmpty()) {
            ReportRow("首次上传", report.yearly.firstUpload.take(10))
        }
        if (report.yearly.lastUpload.isNotEmpty()) {
            ReportRow("末次上传", report.yearly.lastUpload.take(10))
        }
        // 12 月柱状图（复用 YearStatsChart 的简版渲染）
        Spacer(modifier = Modifier.height(4.dp))
        val maxCount = report.yearly.byMonth.maxOf { it.count }.coerceAtLeast(1)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            report.yearly.byMonth.forEach { mc ->
                val ratio = if (mc.count == 0) 0f
                else (mc.count.toFloat() / maxCount).coerceIn(0.1f, 1f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.width(24.dp)
                ) {
                    Text(
                        if (mc.count > 0) "${mc.count}" else "·",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height((56f * ratio + 2f).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (mc.count == 0) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${mc.month}月", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }

    // —— 维度 3：标签 Top5 ——
    ReportSection(title = "🏷 标签 Top5") {
        if (report.tags.isEmpty()) {
            ReportEmpty("暂无标签数据")
        } else {
            report.tags.forEachIndexed { i, tag ->
                ReportRow("#${i + 1} ${tag.name}", "${tag.count} 项")
            }
        }
    }

    // —— 维度 4：健康度 ——
    ReportSection(title = "💚 媒体库健康度") {
        val gradeColor = when (report.health.grade.firstOrNull()) {
            'A' -> Color(0xFF4CAF50)
            'B' -> Color(0xFF8BC34A)
            'C' -> Color(0xFFFFC107)
            'D' -> Color(0xFFFF5722)
            else -> MaterialTheme.colorScheme.primary
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("综合评分", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${report.health.score} / 100  ${report.health.grade}级",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = gradeColor
            )
        }
        // 三条比例条
        ReportBar("重复率", report.health.duplicateRate.toFloat())
        ReportBar("配额占用", report.health.quotaUsage.toFloat())
        ReportBar("数据温度", report.health.ageScore.toFloat())
        if (report.health.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "优化建议",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            report.health.suggestions.take(3).forEach { s ->
                Text("• $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }

    // —— 维度 5：活跃度 ——
    ReportSection(title = "🔥 上传活跃度") {
        ReportRow("活跃天数", "${report.activity.activeDays} 天")
        ReportRow("总上传", "${report.activity.totalUploads} 次")
        ReportRow("日均上传", "${kotlin.math.round(report.activity.avgPerDay * 10.0) / 10.0} 项/天")
        ReportRow("连续上传", "${report.activity.streak} 天（最长 ${report.activity.maxStreak} 天）")
        if (report.activity.mostActiveMonth > 0) {
            ReportRow("最活跃月份", "${report.activity.mostActiveMonth} 月")
        }
    }

    // —— 维度 6：多样性 ——
    ReportSection(title = "🌈 内容多样性") {
        ReportRow("类型数", "${report.diversity.typeCount}")
        ReportRow("标签数", "${report.diversity.tagCount}")
        ReportRow("相册数", "${report.diversity.albumCount}")
        ReportRow("来源数", "${report.diversity.sourceCount}")
        ReportBar("多样性评分", report.diversity.diversityScore / 100f)
    }

    // —— 维度 7：月度亮点 ——
    ReportSection(title = "✨ 月度亮点") {
        if (report.highlights.isEmpty()) {
            ReportEmpty("暂无亮点数据")
        } else {
            report.highlights.forEach { hl ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${hl.month}月 · ${hl.highlight}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${hl.count} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    // —— 维度 8：上传排行 ——
    ReportSection(title = "🏆 上传排行") {
        if (report.ranking.isEmpty()) {
            ReportEmpty("暂无排行数据")
        } else {
            report.ranking.forEach { rk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "#${rk.rank} ${rk.name.ifEmpty { "未知" }}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${rk.count} 项 · ${formatBytesToMB(rk.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}



/** 年度报告单维度卡片：标题 + 内容。 */
@Composable
internal fun ReportSection(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 2.dp)
            )
            content()
        }
    }
}



/** 报告键值行：左标签右值。 */
@Composable
internal fun ReportRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}



/** 报告比例条：标签 + LinearProgressIndicator + 百分比。 */
@Composable
internal fun ReportBar(label: String, ratio: Float) {
    val clamped = ratio.coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text("${(clamped * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { clamped },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    }
}



/** 报告空态占位。 */
@Composable
internal fun ReportEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 6.dp)
    )
}



/**
 * V9：年度回顾对话框——展示该年上传统计详情。
 *
 * 内容：
 * - 总 N 项 + 图片/视频拆分
 * - 最忙的一天（日期 + N 项）
 * - 第一个 / 最后一个上传日期
 * - 12 个月柱状图（FlowRow 方块，深浅按强度着色）
 *
 * [review] 为 null 时显示加载中；拉取失败（仍为 null）显示错误提示。
 * 对话框内容垂直可滚动，避免小屏溢出。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun YearlyReviewDialog(
    year: Int,
    review: MediaService.YearlyReview?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "$year 年度回顾",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (review == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 总数 + 图片/视频/Live 拆分
                    Text(
                        "共 ${review.totalCount} 项 · " +
                            "图片 ${review.byType.image} · " +
                            "视频 ${review.byType.video}" +
                            (if (review.byType.live > 0) " · Live ${review.byType.live}" else ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // 最忙的一天
                    if (review.topDay.date.isNotEmpty() && review.topDay.count > 0) {
                        Text(
                            "最忙的一天：${review.topDay.date}（${review.topDay.count} 项）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // 第一个 / 最后一个上传日期
                    if (review.firstUpload.isNotEmpty() || review.lastUpload.isNotEmpty()) {
                        Text(
                            buildString {
                                if (review.firstUpload.isNotEmpty()) append("首次上传：${review.firstUpload.take(10)}")
                                if (review.firstUpload.isNotEmpty() && review.lastUpload.isNotEmpty()) append("\n")
                                if (review.lastUpload.isNotEmpty()) append("末次上传：${review.lastUpload.take(10)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // 月度分布柱状图（调 media-year-stats 端点，独立 Composable 自取数据）
                    YearStatsChart(year = year)
                    // 收藏数（如果 >0）
                    if (review.favorites > 0) {
                        Text(
                            "收藏 ${review.favorites} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}



/**
 * 月度分布柱状图——调 [MediaService.getMediaYearStats]（media-year-stats 端点）。
 *
 * 独立 @Composable，自取数据（[LaunchedEffect] 按 year 拉取），不依赖外层
 * [YearlyReviewDialog] 已加载的 yearly-review 数据——二者数据源不同但语义互补：
 * - 这里的柱状条高度 ∝ 当月 count / 全年峰值，直观体现月度分布
 * - 每条上方标注当月数量，便于精确读数
 *
 * 三态：加载中（小 spinner）→ 失败/空（占位文案）→ 成功（12 列柱状图）。
 * 函数提出来避免 [YearlyReviewDialog] 进一步膨胀（任务要求提取为独立函数）。
 */
@Composable
internal fun YearStatsChart(year: Int) {
    var stats by remember { mutableStateOf<MediaService.MediaYearStats?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(year) {
        stats = null
        loaded = false
        stats = MediaService.getMediaYearStats(year)
        loaded = true
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "月度分布",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        when {
            !loaded || stats == null -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载月度数据...", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            stats!!.totalCount == 0 -> {
                Text(
                    "该年暂无媒体",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            else -> {
                val byMonth = stats!!.byMonth
                val maxCount = byMonth.maxOf { it.count }.coerceAtLeast(1)
                val maxBarHeight = 64.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    byMonth.forEach { mc ->
                        val ratio = if (mc.count == 0) 0f
                        else (mc.count.toFloat() / maxCount).coerceIn(0.08f, 1f)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                if (mc.count > 0) "${mc.count}" else "",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height((maxBarHeight.value * ratio + 2f).dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(
                                        if (mc.count == 0) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "${mc.month}月",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}



/**
 * V16：重复文件详情对话框。
 *
 * 调 [MediaService.getDupReport] 展示按 SHA256 分组的重复文件列表。每组显示
 * SHA256 前 8 位 + 重复数 + 可回收空间，列出前 3 个文件名；底部汇总总可回收空间。
 * [report] 为 null 时显示加载中；拉取失败仍为 null 时显示错误提示。内容垂直可滚动。
 */
@Composable
internal fun DuplicateReportDialog(
    report: MediaService.DupReport?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("重复文件详情", fontWeight = FontWeight.Bold)
        },
        text = {
            if (report == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (report.duplicates.isEmpty()) {
                Text(
                    "暂无重复文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    report.duplicates.forEach { group ->
                        // 每组：SHA256 前 8 位 · N 重复 · 可回收 MB
                        Text(
                            "${group.sha256.take(8)} · ${group.count} 个重复 ·" +
                                " 可回收 ${formatBytesToMB(group.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 前 3 个文件名 + 大小
                        group.media.take(3).forEach { m ->
                            Text(
                                "  ${m.filename.ifEmpty { "(未命名)" }} · ${formatBytesToMB(m.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                        if (group.media.size > 3) {
                            Text(
                                "  …及其余 ${group.media.size - 3} 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    // 底部汇总
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "总可回收",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            formatBytesToMB(report.totalReclaimableBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}



/**
 * V21：数据导出对话框——展示 full-report 综合报告的 JSON 文本，可滚动，带"复制到剪贴板"按钮。
 *
 * - [json] 为 null：加载中（CircularProgressIndicator）
 * - [json] 为空串 ""：拉取失败，显示错误提示
 * - [json] 非空：左侧 monospace 文本纵向滚动展示，用户可全选手动复制或点按钮一键复制
 *
 * 用 [AlertDialog] 而非自定义 Dialog，保持与 [YearlyReviewDialog]/[DuplicateReportDialog] 同款。
 * 确认按钮为"复制到剪贴板"，取消按钮为"关闭"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportReportDialog(
    json: String?,
    isExporting: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("数据导出", fontWeight = FontWeight.Bold)
        },
        text = {
            when {
                isExporting || json == null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("加载综合报告…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                json.isEmpty() -> {
                    Text(
                        "导出失败，请检查后端连接后重试。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> {
                    // JSON 文本区：限定高度 + 纵向滚动，monospace 字体便于阅读键值
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp)
                    ) {
                        Text(
                            text = json,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCopy,
                enabled = !json.isNullOrEmpty()
            ) { Text("复制到剪贴板") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

