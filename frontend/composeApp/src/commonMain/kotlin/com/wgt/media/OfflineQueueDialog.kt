package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val TAG = "OfflineQueueDialog"

/** 一天 / 一小时 / 一分钟的毫秒数，用于把 epoch 毫秒折算为本地日期分量。 */
private const val MILLIS_PER_DAY = 86_400_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * 离线上传队列冲突解决对话框（PRD-v8 §1.5 离线模式完整化）。
 *
 * 当 [SyncManager.replayOfflineQueue] 重放失败项积压在 [OfflineQueueStore] 时（弱网期间
 * 入队、重放因网络抖动/后端 5xx/源文件已删等未撤离），用户需要一个可观察、可干预的入口。
 * 本对话框由"我的"Tab 的「⏳ N 项待上传」指示器点击触发（见 [MediaListScreen] 的
 * [MyTabContent]），列出全部待重传项并暴露三种动作：
 *
 * - **逐项移除**（[onRemoveItem]）：用户确认某项不需再传（如已在他端上传、或主动放弃）时，
 *   直接从 [OfflineQueueStore] 撤离，避免无谓重试。`localMediaId` 作为幂等键。
 * - **全部重试**（[onRetryAll]）：手动触发一次 [MediaViewModel.retryOfflineQueue] →
 *   [SyncManager.replayOfflineQueue]，网络已恢复但自动备份轮询尚未到达时让用户即时续传。
 *   重试期间展示 loading（[isRetrying]），完成后由 ViewModel 刷新 items 驱动本对话框重组。
 * - **关闭**（[onDismiss]）：仅隐藏对话框，队列不变，自动备份轮询照旧后续重放。
 *
 * 列表项展示 `filename` + `takenAt`（拍摄时间，经 [formatTakenAt] 转本机时区可读串；
 * 0/未知时降级为"—"）。`localMediaId` 不直接展示（它是本地图库 mediaId，
 * 对用户无意义），仅作为移除回调的入参。
 *
 * 用 [AlertDialog]（M3）实现，commonMain 全平台兼容，与同文件域 [AddToAlbumDialog] 等
 * 既有对话框风格一致：title 加粗、列表置 `text` 槽并限高 320dp + 可滚、底部双按钮。
 *
 * 空列表保护：[items] 为空时仍可正常渲染（显示"暂无待上传项"占位），避免 ViewModel 异步
 * 刷新瞬间把对话框清空造成闪烁——调用方（MediaListScreen）在 `offlineQueueItems` 变空时
 * 会顺带关对话框，这里仅做兜底。
 *
 * @param items 当前待重传项快照（来自 [OfflineQueueStore.snapshot]，经 ViewModel 暴露）
 * @param isRetrying 是否正在执行"全部重试"（驱动列表区 loading 蒙层 + 禁用按钮）
 * @param onDismiss 关闭对话框（不改动队列）
 * @param onRemoveItem 移除单项，入参为该项 [OfflineQueueItem.localMediaId]
 * @param onRetryAll 触发一次全量重放（ViewModel 负责刷新 items）
 */
@Composable
fun OfflineQueueDialog(
    items: List<OfflineQueueItem>,
    isRetrying: Boolean = false,
    onDismiss: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onRetryAll: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "离线上传队列",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                when {
                    isRetrying -> {
                        // 重试中：展示 loading，避免用户在重放期间重复点移除/重试造成竞态。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Text(
                                "重试上传中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items.isEmpty() -> {
                        // 兜底空态：正常流程下调用方会在 items 清空时关对话框，这里防闪烁。
                        Text(
                            "暂无待上传项",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            items.forEachIndexed { index, item ->
                                OfflineQueueRow(
                                    item = item,
                                    // 重试期间禁用逐项移除，避免与重放并发改队列。
                                    enabled = !isRetrying,
                                    onRemove = { onRemoveItem(item.localMediaId) }
                                )
                                // 项间分隔：最后一项不画分隔（避免底部多余线）。
                                if (index < items.lastIndex) {
                                    Spacer(modifier = Modifier.size(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // "全部重试"放 confirm 侧（主动作）。重试中或队列空时禁用，避免无意义触发。
            TextButton(
                onClick = onRetryAll,
                enabled = !isRetrying && items.isNotEmpty()
            ) {
                Text("全部重试")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

/**
 * 单条待上传项行：文件名 + 拍摄时间 + 移除按钮。
 *
 * 文件名取 [OfflineQueueItem.filename]（原文件名，单行省略号防超长撑爆行宽）。
 * 拍摄时间 [OfflineQueueItem.takenAt] 为 epoch 毫秒，0 表未知（重放路径里旧项可能未带），
 * 经 [formatTakenAt] 转本机时区可读串；0 时降级展示"—"，避免误显示 1970。
 */
@Composable
private fun OfflineQueueRow(
    item: OfflineQueueItem,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.filename.ifBlank { "未知文件名" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                val timeText = formatTakenAt(item.takenAt)
                Text(
                    "拍摄时间：$timeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            TextButton(
                onClick = onRemove,
                enabled = enabled
            ) {
                Text("移除")
            }
        }
    }
}

/**
 * epoch 毫秒 → "YYYY年MM月DD日 HH:mm"（按本机时区），无 java.time 依赖。
 *
 * 与 [DetailPanel.formatEpochMillis] / [MediaListScreen.formatPreviewDate] 同口径：
 * 先用 [systemTimeZoneOffsetMillis] 把 UTC 毫秒平移到本地，再以 Howard Hinnant
 * civil_from_days 拆年月日，时分由本地当日内余量折算。`<= 0` 视为无值返回 "—"，
 * 避免误显示 1970。本文件自包含副本，与既有文件私有实现各自独立，避免跨文件耦合。
 */
private fun formatTakenAt(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val localMillis = epochMillis + systemTimeZoneOffsetMillis()
    val days = localMillis.floorDiv(MILLIS_PER_DAY)
    val (y, m, d) = civilFromDays(days)
    val millisOfDay = localMillis - days * MILLIS_PER_DAY
    val hour = (millisOfDay / MILLIS_PER_HOUR).toInt()
    val minute = ((millisOfDay % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE).toInt()
    return "${y}年${m.pad2()}月${d.pad2()}日 ${hour.pad2()}:${minute.pad2()}"
}

/**
 * Howard Hinnant civil_from_days：自 1970-01-01 起的天数 → (年, 月, 日)。
 * 纯整数运算，无平台依赖。详见 http://howardhinnant.github.io/date_algorithms.html
 * 与 [DetailPanel.civilFromDays] / [MediaListScreen.civilFromDaysPreview] 一致，此处为
 * 本对话框自包含副本。
 */
private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
    val z0 = z + 719468L
    val era = if (z0 >= 0) z0 / 146097 else (z0 - 146096) / 146097
    val doe = z0 - era * 146097                       // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365  // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)  // [0, 365]
    val mp = (5 * doy + 2) / 153                       // [0, 11]
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()      // [1, 31]
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()   // [1, 12]
    val year = if (m <= 2) y + 1 else y
    return Triple(year.toInt(), m, d)
}

/** 两位前补零（年月日时分分量用）。 */
private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()
