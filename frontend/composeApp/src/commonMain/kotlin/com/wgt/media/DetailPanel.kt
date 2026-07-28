package com.wgt.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.MediaMetadata

/** 一天对应的毫秒数（UTC），用于把 epoch 毫秒折算为"日"边界。 */
private const val MILLIS_PER_DAY = 86_400_000L
/** 一小时对应的毫秒数。 */
private const val MILLIS_PER_HOUR = 3_600_000L
/** 一分钟对应的毫秒数。 */
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * 图片详情面板：在全屏预览底部上滑展开、下滑收起的 bottom sheet。
 *
 * 展开度数进度：0f = 完全收起（仅露指示器条 + 简要信息）；
 * 1f = 完全展开（覆盖约 60% 屏高，可滚动查看完整 EXIF 等）。
 * 手势：垂直拖拽增减 [expandProgress]（向上拖减小偏移 → 展开，向下拖增大偏移 → 收起），
 * 松手后用 [Animatable] spring 吸附到 0f/1f 最近的端点，避免卡在中间态。
 *
 * 内容列：文件名 / 大小 / 分辨率 / 拍摄日期（created_at）/ 修改日期（updated_at）/
 * EXIF 信息（exif_data map）/ 文件来源标签（[sourceLabel]）。
 *
 * 日期工具自包含复刻 Howard Hinnant civil_from_days（commonMain 无 java.time，
 * 详见 MediaViewModel 中同算法），不依赖该类的 private 成员。
 *
 * @param media 当前预览的媒体
 * @param sourceLabel 文件来源的人类可读标签，如"本地相册"/"已上传"/"网盘图片"
 * @param maxHeight 展开态面板的最大高度（dp）
 */
@Composable
fun DetailPanel(
    media: MediaMetadata,
    sourceLabel: String,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 360.dp
) {
    // expandProgress：0f 收起 / 1f 展开。用 Animatable 让松手后平滑吸附到端点。
    val progress = remember(media.id) { Animatable(0f) }
    // 切换图片时复位为收起，避免上一张的展开态带到新图。
    LaunchedEffect(media.id) { progress.snapTo(0f) }
    val scope = rememberCoroutineScope()

    // 把 pixels 偏移直接乘在高度压缩上：收起态高度 = peekHeight，展开态高度 = maxHeight。
    // 用 progress 作为 fillMaxHeight 的比例不可行（需固定 dp），故用 heightIn + offset 思路：
    // 直接以 progress 在 [peekHeight, maxHeight] 之间插值得到当前高度。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val peekPx = with(density) { 64.dp.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }
    // 当前面板高度（px）：收起 peekPx，展开 maxPx，按 progress 线性插值。
    val currentHeightPx = peekPx + (maxPx - peekPx) * progress.value
    val currentHeightDp = with(density) { currentHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 半透明遮罩渐隐：展开度越高背景越实，从近乎透明到较强遮罩，
            // 既保证收起态不挡图片，又让展开态内容可读。圆角顶部。
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.Black.copy(alpha = 0.35f + 0.55f * progress.value))
    ) {
        // 上层：固定高度容器，手势区在顶部指示器一带；内容可滚动。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentHeightDp)
        ) {
            // 顶部抓取区：指示器条 + 简要信息（始终可见），整个抓取条响应拖拽。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .pointerInput(media.id) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                // 松手吸附到最近端点：>0.5f → 展开，否则收起。
                                val target = if (progress.value > 0.5f) 1f else 0f
                                scope.launch {
                                    progress.animateTo(
                                        targetValue = target,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        ) { _, dragAmount ->
                            // 向上拖 dragAmount<0 → progress 增大（展开）；向下拖 → 减小（收起）。
                            // dragAmount 为 px，按可用行程 (maxPx-peekPx) 归一化。
                            val range = (maxPx - peekPx).coerceAtLeast(1f)
                            val delta = -dragAmount / range
                            scope.launch {
                                progress.snapTo((progress.value + delta).coerceIn(0f, 1f))
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 小指示器条（横线）：表示可上滑。展开态变窄变淡给出视觉反馈。
                    Box(
                        modifier = Modifier
                            .width(if (progress.value > 0.5f) 32.dp else 44.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.7f - 0.25f * progress.value))
                    )
                    // 收起态简要信息：文件名 + 尺寸，给"上滑看更多"的预期。
                    if (progress.value < 0.5f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = media.filename,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Text(
                            text = "${formatFileSizeForDetail(media.size)} • ${media.width}x${media.height}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // 内容区：随展开度淡入，可滚动。收起态高度不足以展示，故只在接近展开时滚动有意义；
            // 这里始终渲染但用 alpha 控制可见性，避免状态切换重排。
            if (progress.value > 0.02f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 44.dp)
                        .heightIn(max = maxHeight - 44.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    DetailSection(media = media, sourceLabel = sourceLabel)
                }
            }
        }
    }
}

/**
 * 详情内容区：分组信息条目。每条左标签右值，分隔线分隔，键值用一致字号与灰阶。
 */
@Composable
private fun DetailSection(media: MediaMetadata, sourceLabel: String) {
    InfoRow(label = "文件名", value = media.filename)
    InfoRow(label = "大小", value = formatFileSizeForDetail(media.size))
    InfoRow(
        label = "分辨率",
        value = if (media.width > 0 && media.height > 0) "${media.width} × ${media.height}" else "—"
    )
    InfoRow(label = "类型", value = media.mime_type.ifEmpty { media.type.name })
    InfoRow(label = "拍摄日期", value = formatEpochMillis(media.created_at))
    InfoRow(label = "修改日期", value = formatEpochMillis(media.updated_at))
    InfoRow(label = "文件来源", value = sourceLabel)
    if (media.is_live_photo) {
        InfoRow(label = "Live Photo", value = "是")
    }

    // EXIF 信息：map 非空时逐条列出。
    if (media.exif_data.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "EXIF 信息",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        media.exif_data.forEach { (key, value) ->
            InfoRow(label = key, value = value)
        }
    } else {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "无 EXIF 信息",
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 单条键值信息：左标签灰色、右值白色，[HorizontalDivider] 分隔。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(88.dp)
            )
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
    }
}

/**
 * 格式化文件大小：B / KB / MB 自适应，与 [MediaListScreen.formatFileSize] 口径一致。
 */
private fun formatFileSizeForDetail(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${(size / (1024.0 * 1024.0)).formatOneDecimal()} MB"
    }
}

/**
 * epoch 毫秒 → "YYYY年MM月DD日 HH:mm"（按本机时区），无 java.time 依赖。
 *
 * 用 Howard Hinnant civil_from_days 拆年月日（与 MediaViewModel 同算法）；
 * 拆分前先用 [systemTimeZoneOffsetMillis] 把 UTC 毫秒平移到本地，时分由
 * 本地当日内余量折算，与日期分组口径一致（本地午夜为日界）。
 * 0L 视为无值返回 "—"。
 */
private fun formatEpochMillis(epochMillis: Long): String {
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
 * 与 [MediaViewModel] 中 private 同名实现一致，此处为详情面板自包含副本。
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

/**
 * 十进制两位补零（1 → "01"）。commonMain 无 `String.format`，纯 Kotlin 实现。
 */
private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()

/**
 * 保留一位小数的十进制格式化（3.1416 → "3.1"）。commonMain 无 `String.format`，
 * 纯 Kotlin 实现：乘 10 四舍五入取整，再手动拼小数点。
 */
private fun Double.formatOneDecimal(): String {
    val rounded = (this * 10).let { r ->
        if (r >= 0) (r + 0.5).toLong() else (r - 0.5).toLong()
    }
    val intPart = rounded / 10
    val fracPart = kotlin.math.abs(rounded % 10)
    return "$intPart.$fracPart"
}
