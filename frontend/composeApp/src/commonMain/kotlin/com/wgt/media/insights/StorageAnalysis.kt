package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_info


/**
 * 存储效率卡片 —— 调 `/api/media/media-storage-efficiency` 显示评分+等级+密度+建议。
 *
 * 与"存储健康度"卡片互补：健康度关注重复/配额/冷数据四维加权，本卡片聚焦"空间利用率"——
 * 以每 MB 媒体密度（[StorageEfficiency.mediaPerMb]）与平均大小（[StorageEfficiency.avgBytesPerMedia]）
 * 评估是否被大文件拖累。独立 @Composable，自取数据（LaunchedEffect(Unit)），加载/失败态自洽。
 *
 * 渲染（与"存储健康度"同款风格，便于两卡片视觉对齐）：
 * - SectionTitle「存储效率」
 * - 大字号评分 + 等级徽章（A绿/B蓝/C橙/D红）
 * - 四项指标行：平均大小、媒体密度、重复率、总媒体数
 * - 建议列表（每条 📌 + 文本）
 *
 * 字段缺失（HTTP 非 200 或网络异常 → null）时显示"无法获取存储效率"错误提示，不崩溃设置页。
 */
@Composable
internal fun StorageEfficiencyCard() {
    var efficiency by remember { mutableStateOf<MediaService.StorageEfficiency?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        efficiency = MediaService.getMediaStorageEfficiency()
        loading = false
    }
    SectionTitle("存储效率", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (efficiency == null) {
        Text(
            "无法获取存储效率",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val eff = efficiency!!
        // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红（默认灰），与"存储健康度"卡片口径一致。
        val gradeColor = when (eff.grade.firstOrNull()?.uppercaseChar()) {
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
                "${eff.efficiencyScore}",
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
                    eff.grade.ifEmpty { "-" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "分 / 100",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        // 四项指标行：平均大小 / 媒体密度 / 重复率 / 总媒体数。
        // 平均大小用 formatBytesToMB 转 MB（与"数据概览"同款）；密度保留 2 位小数（项/MB）；
        // 重复率转百分比；总媒体数直出。
        EfficiencyMetricRow(
            label = "平均大小",
            value = "${formatBytesToMB(eff.avgBytesPerMedia.toDouble())} MB/项"
        )
        EfficiencyMetricRow(
            label = "媒体密度",
            value = "${kotlin.math.round(eff.mediaPerMb * 100.0) / 100.0} 项/MB"
        )
        EfficiencyMetricRow(
            label = "重复率",
            value = "${kotlin.math.round(eff.duplicateRate * 1000.0) / 10.0}%"
        )
        EfficiencyMetricRow(
            label = "总媒体",
            value = "${eff.totalMedia} 项"
        )
        // 建议列表：每条一行 📌 + 文字
        if (eff.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            eff.suggestions.forEach { tip ->
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
}



/**
 * 存储效率卡片的单行指标：左侧标签 + 右侧值，两端对齐。私有，仅供 [StorageEfficiencyCard] 复用。
 */
@Composable
internal fun EfficiencyMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}



/**
 * 媒体覆盖率卡片（V23）的单行：emoji + 维度名 + "count/total (percent%)" 在上一行，
 * 下方一条 [LinearProgressIndicator] 按 percent/100 填充。
 *
 * [percent] 为 0-100 的百分比数值（后端给定），内部转 0.0-1.0 的进度小数并 [coerceIn]
 * 兜底，避免后端越界（>100 或 <0）撑破指示器。total<=0 时进度置 0，避免除零。
 */
@Composable
internal fun CoverageRow(
    icon: String,
    label: String,
    count: Int,
    total: Int,
    percent: Double
) {
    val progress = if (total > 0) (percent / 100.0).toFloat().coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$icon $label", style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count/$total (${percent.toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}



/** V8：存储矩阵卡片 —— 调 storage-breakdown-v2 显示类型×年份表格。 */
@Composable
fun StorageMatrixCard() {
    var data by remember { mutableStateOf<MediaService.StorageBreakdownV2?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = MediaService.getStorageBreakdownV2()
        loading = false
    }
    SectionTitle("🧮 存储矩阵", iconRes = Res.drawable.ic_info)
    when {
        loading -> Text("加载中...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        data == null -> Text("无法获取存储矩阵", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        data!!.matrix.isEmpty() -> Text("暂无媒体数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        else -> {
            val m = data!!
            val years = m.matrix.values.flatMap { it.keys }.toSet().sortedDescending().take(3)
            fun cellCount(type: String, year: String): Int = m.matrix[type]?.get(year)?.count ?: 0
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("年份", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(52.dp))
                    Text("图片", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("视频", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                years.forEach { year ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$year", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(52.dp))
                        Text("${cellCount("IMAGE", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("${cellCount("VIDEO", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("${cellCount("LIVE_PHOTO", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("合计 ${m.totalCount} 项 · ${formatBytesToMB(m.totalBytes.toDouble())} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}



/**
 * V14：存储增长预测卡片 —— 调 [MediaService.getStorageGrowthPrediction]
 * （GET /api/media/storage-growth-prediction）展示未来 3/6/12 个月存储用量预测。
 *
 * 后端基于最近 6 个月上传趋势线性外推，并估算 10GB 配额耗尽日期。本卡片展示：
 * - 当前用量（MB）
 * - 月均增长（MB）
 * - 预测：3 月 / 6 月 / 12 月 后的用量（MB）
 * - 预计充满日期（YYYY-MM-DD；无趋势/增长率为 0 时显示"暂无预测"）
 *
 * 抽成独立顶级 @Composable，与 [StorageMatrixCard] 同款结构：SectionTitle + when 三态
 * （加载中 / 失败 null / 数据）。自取数据（[LaunchedEffect] 拉取一次），不依赖外层。
 * 后端不可用/出错时返回 null，归入失败空态提示。
 */
@Composable
fun StoragePredictionCard() {
    var data by remember { mutableStateOf<MediaService.StorageGrowthPrediction?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = MediaService.getStorageGrowthPrediction()
        loading = false
    }
    SectionTitle("📈 存储增长预测", iconRes = Res.drawable.ic_info)
    when {
        loading -> Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        data == null -> Text(
            "无法获取存储预测",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        else -> {
            val p = data!!
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 当前用量
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${formatBytesToMB(p.currentBytes)} MB",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                // 月均增长
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("月均增长", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${formatBytesToMB(p.avgMonthlyGrowthBytes)} MB",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                // 预测：3 月 / 6 月 / 12 月
                Text(
                    "预测",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                listOf(
                    "3_months" to "3 月",
                    "6_months" to "6 月",
                    "12_months" to "12 月"
                ).forEach { (key, label) ->
                    val bytes = p.predictedBytes(key)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("→ $label", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (bytes != null) "${formatBytesToMB(bytes)} MB" else "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                // 预计充满日期
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("预计充满", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        p.estimatedFullDate ?: "暂无预测",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p.estimatedFullDate != null)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}



/**
 * 存储审计卡片（[SettingsScreen] 设置页）。
 *
 * 调 `GET /api/media/media-storage-audit` 显示综合审计评分（0-100）+ A/B/C/D 等级 +
 * 四维度（孤立 / 错误 / 重复组 / 近似重复对）+ 针对性建议列表。后端单次遍历合并四类检查
 * （orphan + error + duplicate + near-duplicate）并同时算分，替代前端并发拉取
 * cleanup-orphan/orphan-check/error-check/duplicates/duplicates-similar 五个端点各自重扫磁盘。
 *
 * 与上方"完整性报告"卡片互补：那个用 [MediaService.getMediaIntegrityReport]（另起端点，
 * 含 samples 明细 + 一键删除重复）；这个是后端聚合的"一站式评分 + 建议"视图，无 sample 展示、
 * 无操作按钮，专为快速了解存储健康全貌而设。
 *
 * 三态自洽（loading / null-错误 / data）。字段缺失（非 200 或异常 → null）时显示"无法获取
 * 存储审计"错误提示，不崩溃设置页。独立顶级 @Composable，自取数据（[LaunchedEffect] 拉取一次）。
 */
@Composable
internal fun StorageAuditCard() {
    var audit by remember { mutableStateOf<MediaService.StorageAudit?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        audit = MediaService.getMediaStorageAudit()
        loading = false
    }
    SectionTitle("🧮 存储审计", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (audit == null) {
        Text(
            "无法获取存储审计报告",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val a = audit!!
        // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红（与完整性报告卡片同色板）
        val gradeColor = when (a.grade) {
            "A" -> Color(0xFF2E7D32)
            "B" -> Color(0xFF1565C0)
            "C" -> Color(0xFFE65100)
            else -> MaterialTheme.colorScheme.error
        }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // 大字号：审计评分 / 100 + 等级徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    "${a.auditScore}",
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
                    a.grade,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = gradeColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 进度条：评分 / 100
            LinearProgressIndicator(
                progress = { (a.auditScore / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = gradeColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 四维度统计行：孤立 / 错误 / 重复组 / 近似重复对
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.orphans.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("孤立", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.errors.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("错误", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.duplicates.groups}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("重复组", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.nearDuplicates.pairs}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("近似重复", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            // 建议列表（非空时展示，每条前缀 •）
            if (a.recommendations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "建议",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                a.recommendations.forEach { rec ->
                    Text(
                        "• $rec",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(8.dp))
}

