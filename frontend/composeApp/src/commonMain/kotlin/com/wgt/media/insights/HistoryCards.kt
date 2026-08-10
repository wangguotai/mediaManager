package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_info


/**
 * 重命名历史卡片 —— 调 [MediaService.getMediaRenameHistory] 展示最近媒体重命名记录。
 *
 * 每行：✏️ old_name → new_name (date)。最多渲染 10 条（后端默认返回 50 条，前端 take(10)）。
 * 三态自洽（loading / null-错误 / data）。独立顶级 @Composable，自取数据（[LaunchedEffect] 拉取一次），
 * 与 [StorageAuditCard] / [SessionStatsCard] 同款结构，避免主函数体过大（method size limit）。
 *
 * [renamedAt] 为后端 RFC3339 字符串；此处取前 10 字符（"2026-07-31"）作日期展示，
 * 解析失败或空串时回退原值，保证宽容。
 */
@Composable
internal fun RenameHistoryCard() {
    var history by remember { mutableStateOf<List<MediaService.RenameHistoryItem>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        history = MediaService.getMediaRenameHistory(50)
        loading = false
    }
    SectionTitle("✏️ 重命名历史", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (history == null) {
        Text(
            "无法获取重命名历史",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val items = history!!
        if (items.isEmpty()) {
            Text(
                "暂无重命名记录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        } else {
            items.take(10).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✏️", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.oldName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text("→", style = MaterialTheme.typography.bodySmall)
                    Text(
                        entry.newName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // renamedAt 形如 "2026-07-31T12:34:56Z"，取前 10 字符作日期
                    val dateStr = entry.renamedAt.take(10)
                    Text(
                        if (dateStr.isNotEmpty()) "($dateStr)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
            // 还有更多记录时提示总数
            if (items.size > 10) {
                Text(
                    "共 ${items.size} 条记录",
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
 * 分享历史卡片 —— 调 [MediaService.getMediaShareHistory] 展示最近媒体分享记录。
 *
 * 每行：🔗 detail (date)。最多渲染 10 条（后端默认返回 50 条，前端 take(10)）。
 * 四态自洽（loading / null-错误 / 空列表 / data）。独立顶级 @Composable，自取数据
 * （[LaunchedEffect] 拉取一次），与 [RenameHistoryCard] / [StorageAuditCard] 同款结构，
 * 避免主函数体过大（method size limit）。
 *
 * [sharedAt] 为后端 RFC3339 字符串；此处取前 10 字符（"2026-07-31"）作日期展示，
 * 解析失败或空串时回退原值，保证宽容。
 */
@Composable
internal fun ShareHistoryCard() {
    var history by remember { mutableStateOf<List<MediaService.ShareHistoryItem>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        history = MediaService.getMediaShareHistory(50)
        loading = false
    }
    SectionTitle("🔗 分享历史", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (history == null) {
        Text(
            "无法获取分享历史",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val items = history!!
        if (items.isEmpty()) {
            Text(
                "暂无分享记录",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        } else {
            items.take(10).forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔗", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // sharedAt 形如 "2026-07-31T12:34:56Z"，取前 10 字符作日期
                    val dateStr = entry.sharedAt.take(10)
                    Text(
                        if (dateStr.isNotEmpty()) "($dateStr)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
            // 还有更多记录时提示总数
            if (items.size > 10) {
                Text(
                    "共 ${items.size} 条记录",
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

