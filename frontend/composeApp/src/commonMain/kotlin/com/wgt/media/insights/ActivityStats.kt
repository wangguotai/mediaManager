package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_info


/**
 * 上传延迟分析卡片 —— 调 [MediaService.getMediaTimeAnalysis] 展示拍摄→上传延迟。
 *
 * 后端 `GET /api/media/media-time-analysis` 基于全部未软删媒体的 taken_at（拍摄时间）
 * 与 created_at（上传时间）之差统计延迟分布。本卡片三段展示：
 * 1. 平均延迟 / 最大延迟（秒按量级换算为 分钟/小时/天）。
 * 2. 同日上传：sameDayCount / total（拍摄与上传落在同一 UTC 日期）。
 * 3. 延迟分布四档：<1h / 1-24h / 1-7d / >7d，每档计数 + 占总比。
 *
 * 抽成独立顶级 @Composable，避免 [SettingsScreen] 主函数体过大。自取数据
 * （[LaunchedEffect] 拉取一次），三态渲染：加载中 / 失败（null）/ 数据。
 * total=0 时后端约定返回 null，归入失败空态提示。
 */
@Composable
fun UploadDelayCard() {
    var data by remember { mutableStateOf<MediaService.MediaTimeAnalysis?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = MediaService.getMediaTimeAnalysis()
        loading = false
    }
    SectionTitle("⏱️ 上传延迟分析", iconRes = Res.drawable.ic_info)
    when {
        loading -> Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        data == null -> Text(
            "无法获取上传延迟分析",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        else -> {
            val a = data!!
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 平均延迟 / 最大延迟
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("平均延迟", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatDelaySeconds(a.avgDelaySeconds), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("最大延迟", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    Text(formatDelaySeconds(a.maxDelaySeconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 同日上传
                Text(
                    "同日上传 ${a.sameDayCount} / ${a.total} 项（${formatPercent(a.sameDayRatio)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(4.dp))
                Text("延迟分布", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                val b = a.buckets
                val total = a.total
                delayRow("<1 小时", b.under1h, total)
                delayRow("1-24 小时", b.h1To24, total)
                delayRow("1-7 天", b.d1To7, total)
                delayRow(">7 天", b.over7d, total)
            }
        }
    }
}



/**
 * 上传延迟分布单行：标签 + 计数 + 占总比（与 [UploadDelayCard] 配套）。
 * commonMain 无 `String.format`/`%.2f`，比值用整数除法 + 四舍五入辅助。
 */
@Composable
internal fun delayRow(label: String, count: Int, total: Int) {
    val pct = if (total > 0) (count * 100.0 / total).toInt() else 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        Text("$count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("· $pct%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}



/**
 * 把秒级延迟按量级换算为人类可读文本：
 * - <60s → "N 秒"
 * - <3600s → "N 分钟"（四舍五入）
 * - <86400s → "N 小时"
 * - 否则 → "N 天"
 * 供 [UploadDelayCard] 展示平均/最大延迟。commonMain 无 `String.format`，手动拼接。
 */
internal fun formatDelaySeconds(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
    return when {
        s < 60.0 -> "${s.toInt()} 秒"
        s < 3600.0 -> "${(s / 60.0).toInt()} 分钟"
        s < 86400.0 -> "${(s / 3600.0).toInt()} 小时"
        else -> "${(s / 86400.0).toInt()} 天"
    }
}



/**
 * 使用习惯分析卡片（GET /api/media/media-session-stats）—— 会话维度统计。
 *
 * 独立顶级 @Composable，自取数据（[LaunchedEffect] 拉取一次），三态渲染：
 * 加载中 / 失败（null，含后端尚未实现该端点的情况）/ 数据。
 *
 * 展示四行：
 * - 总会话数 N · 平均操作 X 次/会话
 * - 平均时长 X 分钟（后端 avg_duration 为秒，÷60 转分钟）
 * - 最常见首操作: action
 * - 最长会话: N 次操作 / X 分钟（longest_session 为 null 时不展示该行）
 *
 * 与 [UploadDelayCard] 同款结构：SectionTitle + when 三态 + Column 内容。
 */
@Composable
fun SessionStatsCard() {
    var data by remember { mutableStateOf<MediaService.MediaSessionStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = MediaService.getMediaSessionStats()
        loading = false
    }
    SectionTitle("🧭 使用习惯分析", iconRes = Res.drawable.ic_info)
    when {
        loading -> Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        data == null -> Text(
            "无法获取使用习惯分析",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
        else -> {
            val s = data!!
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 总会话数 · 平均操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("总会话数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${s.totalSessions} · 平均 ${formatActions(s.avgActions)} 次/会话",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // 平均时长
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "平均时长",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${formatMinutes(s.avgDuration)} 分钟",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                // 最常见首操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最常见首操作",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        s.commonFirstAction.ifEmpty { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                // 最长会话（可能为 null）
                val ls = s.longestSession
                if (ls != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "最长会话",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${ls.actions} 次操作 / ${formatMinutes(ls.duration)} 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}



/**
 * 把秒级时长格式化为分钟整数字符串（四舍五入）。
 * 后端 avg_duration / longest_session.duration 均为秒，UI 按分钟展示。
 * commonMain 无 `String.format`，手动拼接。负值/NaN 视为 0。
 */
internal fun formatMinutes(seconds: Double): String {
    val s = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
    return "${(s / 60.0).toInt()}".let { mins ->
        // 不足 1 分钟按 1 分钟展示，避免“0 分钟”无信息量
        if (s > 0.0 && mins == "0") "1" else mins
    }
}



/**
 * 格式化平均操作次数：整数直接显示，非整数保留 1 位小数（手动拼接，commonMain 无 String.format）。
 */
internal fun formatActions(actions: Double): String {
    val rounded = (actions * 10.0).toInt() / 10.0
    val intPart = rounded.toInt()
    val frac = ((actions * 10.0).toInt() - intPart * 10)
    return if (frac == 0) "$intPart" else "$intPart.$frac"
}



/** V8：批量导出筛选 Dialog */
@Composable
fun BulkExportFilterDialog(
    type: String,
    tag: String,
    dateFrom: String,
    dateTo: String,
    onTypeChange: (String) -> Unit,
    onTagChange: (String) -> Unit,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    onCancel: () -> Unit,
    isExporting: Boolean,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("批量导出筛选") },
        text = {
            Column {
                Text("类型: $type", fontSize = 13.sp)
                Text("标签: $tag", fontSize = 13.sp)
                Text("起始日期: $dateFrom", fontSize = 13.sp)
                Text("结束日期: $dateTo", fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isExporting) {
                Text(if (isExporting) "导出中..." else "确认导出")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

