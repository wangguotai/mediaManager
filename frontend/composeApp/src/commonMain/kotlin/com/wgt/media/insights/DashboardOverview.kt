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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wgt.common.util.formatBytesToMB
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_info


/**
 * V24：仪表盘概览卡片 —— 一次调 [MediaService.getUserDashboardV2] 拿全部6维度数据，
 * 聚合展示健康度/活跃度/覆盖率/连续上传天数/Top1洞察。
 *
 * 抽成独立 @Composable 而非内联进 [SettingsScreen]，避免主函数体过大触发 JVM 方法
 * 64KB 上限（MethodTooLargeException）。加载中/失败/成功三态自洽，调用方无需传参。
 *
 * 渲染项（对齐任务规格）：
 * - 健康度：score/100 + grade（A绿/B蓝/C橙/D红，配色与进度条联动）
 * - 活跃度：score + level（新手/活跃/达人/专家）
 * - 覆盖率：tagged% + favorited%
 * - 连续：🔥 N 天（附最长记录）
 * - 洞察：top 1 insight 的 title + detail
 */
@Composable
internal fun DashboardOverviewV2Card() {
    var dashboardV2 by remember { mutableStateOf<MediaService.UserDashboardV2?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        dashboardV2 = MediaService.getUserDashboardV2()
        loading = false
    }
    SectionTitle("📊 仪表盘概览", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (dashboardV2 == null) {
        Text(
            "无法获取仪表盘数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val db = dashboardV2!!
        // 健康度：score/100 + grade（A绿色/B蓝色/C橙色/D红色）
        val gradeColor = when (db.health.grade) {
            "A" -> Color(0xFF4CAF50)
            "B" -> Color(0xFF2196F3)
            "C" -> Color(0xFFFF9800)
            else -> Color(0xFFF44336)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("健康度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${db.health.score}/100  ${db.health.grade}级",
                style = MaterialTheme.typography.bodyLarge,
                color = gradeColor,
                fontWeight = FontWeight.Medium
            )
        }
        LinearProgressIndicator(
            progress = { (db.health.score / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = gradeColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        // 活跃度：score + level
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("活跃度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${db.activity.score} 分  ${db.activity.level}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 覆盖率：tagged% + favorited%
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("覆盖率", style = MaterialTheme.typography.bodyLarge)
            Text(
                "标签 ${db.coverage.taggedPercent}% · 收藏 ${db.coverage.favoritedPercent}%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 连续上传天数
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("连续上传", style = MaterialTheme.typography.bodyLarge)
            Text(
                "🔥 ${db.streak.currentStreak} 天" +
                    if (db.streak.longestStreak > 0) "（最长 ${db.streak.longestStreak}）" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 洞察：top 1 insight
        db.insights.firstOrNull()?.let { ins ->
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("💡", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        ins.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ins.detail.isNotEmpty()) {
                        Text(
                            ins.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}



/**
 * 存储分析的单行：彩色小圆点 + 类型名 + 数量与 MB 占用（V8）。
 *
 * 纯展示行，无交互。圆点用 [Box] + [CircleShape] 着色，与 MaterialTheme 色板取色
 * （图片蓝=primary，视频红=error，Live 绿=tertiary），便于主题切换时随色板联动。
 */
@Composable
internal fun StorageBreakdownRow(
    dotColor: Color,
    label: String,
    count: Int,
    bytes: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "$count 个 · ${formatBytesToMB(bytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}



/**
 * 数据概览卡片（V9）的单格：标签在上、数值在下，居中对齐。
 *
 * 用于把 stat-summary 返回的多组计数并排展示（图片/视频/Live/收藏/分享/相册/回收站/配额），
 * 一个 [Row] 内放若干个 [StatCell] + `Modifier.weight(1f)` 即可均分多列。
 *
 * [value] 为 Int 整数；需要展示非整数文本（如配额百分比）时改用 [valueText]，
 * 此时 [value] 留默认 0（仅占位，不参与显示）。
 */
@Composable
internal fun StatCell(
    label: String,
    value: Int = 0,
    valueText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valueText ?: value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}



/**
 * 存储健康度卡片（V22）的比例条：标签 + 百分比在上一行，下方一条灰底满宽槽内
 * 按 [fraction] 填充 [barColor] 色条。用于重复率/配额使用/数据温度三项 0.0-1.0 指标。
 *
 * [fraction] 会被 [coerceIn] 限制到 0..1，避免后端越界值撑破布局。
 */
@Composable
internal fun HealthRatioBar(
    label: String,
    fraction: Float,
    barColor: Color
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(clamped * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
    }
}

