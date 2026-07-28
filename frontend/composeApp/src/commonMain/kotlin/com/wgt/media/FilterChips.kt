package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 媒体类型筛选维度。
 *
 * - [ALL]      全部：不按类型过滤。
 * - [IMAGE]    图片：含普通图片与 Live Photo（LIVE_PHOTO 本质是带视频的图片，归入图片类浏览）。
 * - [VIDEO]    视频：仅 [media.MediaType.VIDEO]。
 * - [FAVORITE] 收藏：只显示被收藏的媒体，与前三个维度互斥。
 */
enum class MediaFilterType(val label: String) {
    ALL("全部"),
    IMAGE("图片"),
    VIDEO("视频"),
    FAVORITE("⭐ 收藏")
}

/**
 * 类型筛选条：全部 / 图片 / 视频 三个 [FilterChip]，横向排列，Material3 风格。
 *
 * 单选语义：同时只有一个 chip 处于 selected。选中态用 primary 色填充强调当前过滤维度，
 * 未选用 secondaryContainer 中性底，与项目已有的动态色调体系一致。
 *
 * @param selected 当前选中的筛选维度
 * @param onSelect 切换筛选维度回调（驱动 ViewModel.filterType）
 * @param modifier
 */
@Composable
fun FilterChipsRow(
    selected: MediaFilterType,
    onSelect: (MediaFilterType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MediaFilterType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(type.label) },
                colors = FilterChipDefaults.filterChipColors(
                    // 选中：primary 系填充，与选中态边框/图标强调一致。
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // 未选：secondaryContainer 中性底，弱化视觉权重。
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
    }
}
