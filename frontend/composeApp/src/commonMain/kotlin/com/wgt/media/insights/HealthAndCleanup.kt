package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.platform.logger.logger
import com.wgt.common.util.formatBytesToMB
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_delete
import mediamanager.composeapp.generated.resources.ic_info


private const val TAG = "InsightsDashboardScreen"

/**
 * V8：媒体库总健康卡片 —— 调 [MediaService.getMediaCollectionHealth] 一次拿五维度综合评分。
 *
 * 后端 `/api/media/media-collection-health` 合并 health/integrity/efficiency/coverage/activity
 * 五个维度的评分为综合 0-100 评分 + A/B/C/D 等级 + 汇总建议清单。本卡片是其前端展示：
 *
 * 渲染（与 [StorageEfficiencyCard] 同款风格，便于卡片视觉对齐）：
 * - SectionTitle「媒体库总健康」
 * - 大字号综合评分 + 等级徽章（A绿/B蓝/C橙/D红）
 * - 五维度迷你条：健康/完整性/效率/覆盖/活跃（每条标签 + LinearProgressIndicator +分数）
 * - 建议列表前三条（每条 📌 + 文本，超出显示“共 N 条建议”汇总）
 *
 * 字段缺失（HTTP 非 200 或网络异常 → null）时显示“无法获取媒体库健康”错误提示，不崩溃设置页。
 * 独立顶级 @Composable，自取数据（[LaunchedEffect] 拉取一次），三态自洽。
 */
@Composable
internal fun CollectionHealthCard() {
    var health by remember { mutableStateOf<MediaService.CollectionHealth?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        health = MediaService.getMediaCollectionHealth()
        loading = false
    }
    SectionTitle("媒体库总健康", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (health == null) {
        Text(
            "无法获取媒体库健康",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val h = health!!
        // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红（默认灰），与 StorageEfficiencyCard 口径一致。
        val gradeColor = when (h.grade.firstOrNull()?.uppercaseChar()) {
            'A' -> Color(0xFF4CAF50)
            'B' -> Color(0xFF2196F3)
            'C' -> Color(0xFFFF9800)
            'D' -> Color(0xFFF44336)
            else -> MaterialTheme.colorScheme.outline
        }
        // 大字号综合评分 + 等级徽章
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${h.overallScore}",
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
                    h.grade.ifEmpty { "-" },
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
        // 五维度迷你条：健康/完整性/效率/覆盖/活跃。
        // 键名与后端 subscores 对齐（health/integrity/efficiency/coverage/activity）。
        // 缺失键按 0 渲染（minOf 保护进度条 0~1 区间）。
        val dims = listOf(
            "health" to "健康",
            "integrity" to "完整性",
            "efficiency" to "效率",
            "coverage" to "覆盖",
            "activity" to "活跃"
        )
        dims.forEach { (key, label) ->
            val score = h.subscores[key] ?: 0
            val ratio = (score.coerceIn(0, 100)) / 100f
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(56.dp)
                )
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = gradeColorFor(score),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "$score",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.End
                )
            }
        }
        // 建议列表前三条（每条 📌 + 文本），超出显示“共 N 条建议”汇总。
        if (h.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            h.suggestions.take(3).forEach { tip ->
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
            if (h.suggestions.size > 3) {
                Text(
                    "共 ${h.suggestions.size} 条建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp)
                )
            }
        }
    }
}



/**
 * 按 0-100 评分映射迷你条颜色：≥80 绿 / ≥60 蓝 / ≥40 橙 / 否则红。
 * 与等级徽章 A/B/C/D 配色同源，使维度条与综合徽章视觉一致。私有，仅供 [CollectionHealthCard] 复用。
 */
internal fun gradeColorFor(score: Int): Color = when {
    score >= 80 -> Color(0xFF4CAF50)
    score >= 60 -> Color(0xFF2196F3)
    score >= 40 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}



/**
 * V26：归档建议V2卡片 —— 调 GET /api/media/media-archive-recommend-v2 展示多维度评分归档推荐。
 *
 * 后端按 5 维度（年龄>180天 / 无标签 / 不在相册 / 大小>10MB / 非收藏）累加 archive_score，
 * 分数越高越建议归档。本卡片取 top 5，每条显示 filename (score 分) + 命中原因列表，
 * 底部汇总潜在可释放空间（MB）。
 *
 * 数据获取走 [MediaService.getMediaArchiveRecommendV2]（返回原始 JSON 字符串），此处用
 * kotlinx.serialization 运行时 JSON API 解析——与 feature-media 层无 serialization 编译器
 * 插件口径一致。失败/空列表静默降级为提示文本，不阻塞设置页其余卡片。
 *
 * 独立 @Composable，自管 loading / error / 空态，与 [StoragePredictionCard] 同模式。
 */
@Composable
internal fun ArchiveRecommendV2Card() {
    // rawJson: null=加载中或失败；非 null=已获取后端原始 JSON 字符串
    // （getMediaArchiveRecommendV2 失败返回 null，成功返回字符串）
    var rawJson by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = MediaService.getMediaArchiveRecommendV2(limit = 20)
        if (result == null) {
            failed = true
        } else {
            rawJson = result
        }
        loading = false
    }

    SectionTitle("🏷️ 归档建议V2", iconRes = Res.drawable.ic_info)

    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }
    if (failed) {
        Text(
            "无法获取归档建议V2",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    // 解析后端原始 JSON：{recommendations:[{media_id,filename,score,size,age_days,type,reasons:[...]}],
    // total, potential_savings_bytes, potential_savings_mb, scored_count, ...}
    val parsed: JsonObject = try {
        Json.parseToJsonElement(rawJson!!).jsonObject
    } catch (e: Exception) {
        logger.error(TAG, "ArchiveRecommendV2Card parse FAILED: ${e::class.simpleName} ${e.message}")
        Text(
            "归档建议V2数据解析失败",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    val recs: JsonArray = parsed["recommendations"]?.jsonArray ?: JsonArray(emptyList())
    val totalCnt = parsed["total"]?.jsonPrimitive?.intOrNull ?: recs.size
    val savingsBytes = parsed["potential_savings_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
    val savingsMb = parsed["potential_savings_mb"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        ?: (savingsBytes / (1024.0 * 1024.0))
    val scoredCount = parsed["scored_count"]?.jsonPrimitive?.intOrNull ?: totalCnt

    if (recs.isEmpty()) {
        Text(
            "暂无归档建议（未发现冷数据/低价值媒体）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 汇总行：共 N 项进入评分，展示 top 5，可释放 X MB
        Text(
            "🏷️ 共 $scoredCount 项进入评分（展示 top ${minOf(5, recs.size)}，可释放 ${formatDouble2(savingsMb)} MB）",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // top 5 推荐条目：filename (score 分) + 原因列表
        recs.take(5).forEach { el ->
            val item = el.jsonObject
            val filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: ""
            val mediaId = item["media_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val score = item["score"]?.jsonPrimitive?.intOrNull ?: 0
            val reasons: List<String> = item["reasons"]?.jsonArray?.mapNotNull { r ->
                r.jsonPrimitive.contentOrNull
            } ?: emptyList()

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "• ${filename.ifEmpty { mediaId }}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$score 分",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 原因列表（后端已给出人类可读文本，逐条展示）
                if (reasons.isNotEmpty()) {
                    reasons.forEach { reason ->
                        Text(
                            "  · $reason",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 12.dp, top = 1.dp)
                        )
                    }
                }
            }
        }

        // 潜在节省汇总（底部强调）
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "💾 潜在节省空间：${formatDouble2(savingsMb)} MB",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}



/**
 * V27：一键清理计划卡片 —— 调 GET /api/media/media-cleanup-plan 展示按优先级（score 倒序）
 * 排序的合并清理清单（完全重复 / 孤儿文件 / 近似重复 / 错误文件 / 旧大文件归档）。
 *
 * 后端把 5 大清理来源合并为单一 plan，每条含 type/media_id/filename/score/action/
 * reclaimable_bytes；total_items/total_reclaimable_bytes 为截断前全量汇总。
 * 本卡片展示 top 5 优先级条目 + 顶部汇总（N 项建议 · 可回收 X MB）。
 *
 * 数据获取走 [MediaService.getMediaCleanupPlan]（返回原始 JSON 字符串），此处用
 * kotlinx.serialization 运行时 JSON API 解析——与 [ArchiveRecommendV2Card] 同款口径。
 * 失败/空列表静默降级为提示文本，不阻塞设置页其余卡片。
 *
 * 独立 @Composable，自管 loading / error / 空态，与 [ArchiveRecommendV2Card] 同模式。
 *
 * type → emoji 映射（按任务规范）：
 * - exact_duplicates / near_duplicates → 🔁 duplicate
 * - orphan_files                       → 🔗 orphan
 * - error_files                        → ⚠️ error
 * - archive_candidates                 → 📦 archive
 * 未知 type 回退 🔁，保证行不破。
 */
@Composable
internal fun CleanupPlanCard() {
    // rawJson: null=加载中或失败；非 null=已获取后端原始 JSON 字符串
    // （getMediaCleanupPlan 失败返回 null，成功返回字符串）
    var rawJson by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = MediaService.getMediaCleanupPlan(limit = 20)
        if (result == null) {
            failed = true
        } else {
            rawJson = result
        }
        loading = false
    }

    SectionTitle("🧹 一键清理计划", iconRes = Res.drawable.ic_info)

    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }
    if (failed) {
        Text(
            "无法获取清理计划",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    // 解析后端原始 JSON：{plan:[{type,media_id,filename,score,action,reclaimable_bytes,...}],
    // total_items, total_reclaimable_bytes, estimated_time_min, source_counts:{...}, ...}
    val parsed: JsonObject = try {
        Json.parseToJsonElement(rawJson!!).jsonObject
    } catch (e: Exception) {
        logger.error(TAG, "CleanupPlanCard parse FAILED: ${e::class.simpleName} ${e.message}")
        Text(
            "清理计划数据解析失败",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    val plan: JsonArray = parsed["plan"]?.jsonArray ?: JsonArray(emptyList())
    val totalItems = parsed["total_items"]?.jsonPrimitive?.intOrNull ?: plan.size
    val totalReclaimableBytes = parsed["total_reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L

    if (plan.isEmpty() && totalItems == 0) {
        Text(
            "🎉 暂无清理建议，媒体库很整洁",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 顶部汇总：N 项建议 · 可回收 X MB
        Text(
            "🧹 共 $totalItems 项建议 · 可回收 ${formatBytesToMB(totalReclaimableBytes)}",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // top 5 优先级条目：type emoji + filename + score + action
        plan.take(5).forEach { el ->
            val item = el.jsonObject
            val type = item["type"]?.jsonPrimitive?.contentOrNull ?: ""
            val filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: ""
            val mediaId = item["media_id"]?.jsonPrimitive?.contentOrNull ?: ""
            val score = item["score"]?.jsonPrimitive?.intOrNull ?: 0
            val action = item["action"]?.jsonPrimitive?.contentOrNull ?: ""
            val reclaimable = item["reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L

            // type → emoji 映射（按任务规范）
            val emoji = when (type) {
                "exact_duplicates", "near_duplicates" -> "🔁"
                "orphan_files" -> "🔗"
                "error_files" -> "⚠️"
                "archive_candidates" -> "📦"
                else -> "🔁"
            }

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(emoji, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        filename.ifEmpty { mediaId },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$score 分",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // action 行：建议执行动作 + 可回收字节
                if (action.isNotEmpty()) {
                    Text(
                        "  · $action · 可回收 ${formatBytesToMB(reclaimable)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 12.dp, top = 1.dp)
                    )
                }
            }
        }

        // 底部强调可回收总量
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "💾 预计可回收空间：${formatBytesToMB(totalReclaimableBytes)}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}



/**
 * AI 洞察报告卡片 —— 调 [MediaService.getMediaAIInsights] 展示个性化建议清单。
 *
 * 后端 GET /api/media/media-ai-insights 综合重复/孤儿/归档/标签覆盖/活跃度等多维度，
 * 返回优先级排序的建议列表（`{insights:[{category,text,priority,actionable,action_url}],
 * total, generated_at}`），此处仅渲染 `insights` 数组。
 *
 * 每条洞察：前置 emoji（按 [MediaService.AIInsight.category] 映射）+ 分类 + 文案，
 * 优先级以颜色区分（high=红 / medium=橙 / low=蓝）。最多展示 8 条（[take] 截断，
 * 后端通常返回 ≤8 条，cap 以防极端库产出长列表）。四态自洽（loading / null-错误 /
 * 空列表 / data），与 [RenameHistoryCard] / [ShareHistoryCard] 同款结构。独立顶级
 * @Composable，自取数据（[LaunchedEffect] 拉取一次），避免主函数体过大（method size limit）。
 *
 * [actionable] 为 true 且 [actionUrl] 非空时，行尾附"可操作"标记（绿色对勾），提示
 * 用户该建议可一键跟进；具体跳转动作由后端 action_url 承载，前端暂仅展示标记不发起跳转。
 */
@Composable
internal fun AIInsightsCard() {
    var insights by remember { mutableStateOf<List<MediaService.AIInsight>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        insights = MediaService.getMediaAIInsights()
        loading = false
    }
    SectionTitle("🤖 AI 洞察报告", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (insights == null) {
        Text(
            "无法获取 AI 洞察",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val items = insights!!
        if (items.isEmpty()) {
            Text(
                "暂无 AI 洞察建议",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        } else {
            items.take(8).forEach { insight ->
                // 优先级颜色：high=红 / medium=橙 / low=蓝
                val priorityColor = when (insight.priority.lowercase()) {
                    "high" -> Color(0xFFF44336)
                    "medium" -> Color(0xFFFF9800)
                    "low" -> Color(0xFF2196F3)
                    else -> MaterialTheme.colorScheme.outline
                }
                // 分类 emoji 映射：未知分类回退 💡
                val categoryEmoji = when (insight.category.lowercase()) {
                    "duplicate", "duplicates" -> "🔁"
                    "archive" -> "📦"
                    "orphan", "orphans" -> "🔗"
                    "tag", "tags", "label", "labels" -> "🏷️"
                    "storage", "disk", "space" -> "💾"
                    "activity" -> "📊"
                    "error", "errors" -> "⚠️"
                    "coverage" -> "📐"
                    else -> "💡"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(categoryEmoji, style = MaterialTheme.typography.bodyMedium)
                    Column(modifier = Modifier.weight(1f)) {
                        // 第一行：分类标签（着色）+ 文案
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                insight.category,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(priorityColor)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                insight.text,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 第二行：优先级文字 + 可操作标记
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "优先级：${insight.priority}",
                                fontSize = 11.sp,
                                color = priorityColor,
                                fontWeight = FontWeight.Medium
                            )
                            if (insight.actionable) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "✓ 可操作",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4CAF50),
                                )
                            }
                        }
                    }
                }
            }
            // 还有更多洞察时提示总数
            if (items.size > 8) {
                Text(
                    "共 ${items.size} 条洞察",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(8.dp))
}



/**
 * V8 §1.4：AI 清理建议卡片。
 *
 * 调 GET /api/media/media-ai-cleanup-suggestions 获取基于媒体元数据启发式分析的
 * 五类可清理候选（低分辨率/截图/超大图/极小文件/缺拍摄时间），每类显示数量 +
 * 可回收字节数 + 前几条示例。四态自洽（loading/error/empty/data），与
 * [AIInsightsCard] / [CleanupPlanCard] 同款结构。独立 @Composable，自取数据。
 */
@Composable
internal fun AiCleanupSuggestionsCard() {
    var suggestions by remember { mutableStateOf<MediaService.AiCleanupSuggestions?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        suggestions = MediaService.getAiCleanupSuggestions()
        loading = false
    }
    SectionTitle("🧹 AI 清理建议", iconRes = Res.drawable.ic_delete)

    if (loading) {
        Text(
            "分析中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (suggestions == null) {
        Text(
            "无法获取清理建议",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val s = suggestions!!
        if (s.totalSuggestionCount == 0) {
            Text(
                "🎉 未发现可清理的低质量图片",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        } else {
            // 五类建议，每类一行：emoji + 名称 + 数量 + 可回收空间
            val categories = listOf(
                Triple("🖼️", "低分辨率", s.lowResolution),
                Triple("📱", "截图", s.screenshots),
                Triple("📦", "超大图片", s.largeImages),
                Triple("📄", "极小文件", s.tinyFiles),
                Triple("🕐", "缺拍摄时间", s.noTakenAt)
            )
            categories.forEach { (emoji, label, cat) ->
                if (cat.count > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(emoji, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${cat.count} 项",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cat.reclaimableBytes > 0) {
                            Text(
                                formatBytesToMB(cat.reclaimableBytes),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 显示前 3 条示例
                    cat.items.take(3).forEach { item ->
                        Text(
                            "  $emoji ${item.filename} — ${item.reason}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 38.dp, bottom = 1.dp)
                        )
                    }
                    if (cat.count > 3) {
                        Text(
                            "  ...还有 ${cat.count - 3} 项",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 38.dp, bottom = 2.dp)
                        )
                    }
                }
            }
            // 总计
            val totalBytes = s.lowResolution.reclaimableBytes +
                s.screenshots.reclaimableBytes +
                s.largeImages.reclaimableBytes +
                s.tinyFiles.reclaimableBytes +
                s.noTakenAt.reclaimableBytes
            Text(
                "共 ${s.totalSuggestionCount} 项建议，可回收 ${formatBytesToMB(totalBytes)} MB",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(8.dp))
}

