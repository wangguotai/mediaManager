package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource

private const val TAG = "PhotoMapScreen"

/**
 * 照片地图页（PRD-v11 §2.1）。
 *
 * 展示照片 GPS 位置聚类。KMP 无内建地图库（不引入 Google Maps / OSM 依赖），
 * 以聚类列表+缩略图卡片代替：每个聚类点显示坐标/数量/缩略图，点击展开该位置照片。
 *
 * 后端 GET /api/media/geo-clusters 返回 haversine 500m 聚类点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMapScreen(onBack: () -> Unit) {
    var clusters by remember { mutableStateOf<List<MediaService.GeoCluster>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var expandedCluster by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        clusters = MediaService.getGeoClusters()
        loading = false
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("照片地图", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text("加载位置...", modifier = Modifier.padding(top = 16.dp))
                }
            }
            clusters == null || clusters!!.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    YkLocationPinIcon(size = 48, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("暂无位置数据", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "照片需要包含 GPS 信息才能在地图上展示",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    item {
                        Text(
                            "${clusters!!.size} 个位置 · ${clusters!!.sumOf { it.count }} 张照片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(clusters!!.withIndex().toList()) { (index, cluster) ->
                        GeoClusterCard(
                            cluster = cluster,
                            isExpanded = expandedCluster == index,
                            onClick = { expandedCluster = if (expandedCluster == index) null else index }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeoClusterCard(
    cluster: MediaService.GeoCluster,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 位置图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                YkLocationPinIcon(size = 24, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(modifier = Modifier.size(12.dp))
            // 位置信息
            Column(modifier = Modifier.weight(1f)) {
                val latStr = "${cluster.lat.toInt()}.${((cluster.lat % 1) * 10000).toInt()}"
                    val lngStr = "${cluster.lng.toInt()}.${((cluster.lng % 1) * 10000).toInt()}"
                    Text(
                        "$latStr, $lngStr",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${cluster.count} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isExpanded) {
                Text("▲", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isExpanded && cluster.thumbUrl.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "缩略图预览: ${cluster.thumbMediaId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
