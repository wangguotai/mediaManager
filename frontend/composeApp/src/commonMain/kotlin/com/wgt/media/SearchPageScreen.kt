// PRD-v12 UI：一刻相册式搜索页 —— 顶部返回+灰底搜索框 + 分区(回忆/人物/地点/场景)。
// 输入自然语言走 AI 语义检索(CLIP)，否则展示分区聚合(人物/地点/场景)。独立全屏页。
package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.PersonCluster
import com.wgt.feature.media.MediaService.GeoCluster
import com.wgt.feature.media.MediaService.AutoAlbum
import kotlinx.coroutines.launch
import media.MediaMetadata

/**
 * 一刻相册式搜索页(全屏)。
 * - 顶部：返回 + 灰底圆角搜索框(用户可输入自然语言，走 AI 视觉检索)。
 * - 未输入时分区展示聚合入口：回忆 / 人物(横排圆头像) / 地点(统计卡) / 场景(横排卡)。
 * - 输入后回车：AI 语义搜索，结果通过 [onResults] 灌入主列表并关闭本页。
 */
@Composable
fun SearchPageScreen(
    onClose: () -> Unit,
    onResults: (List<MediaMetadata>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var persons by remember { mutableStateOf<List<PersonCluster>>(emptyList()) }
    var geos by remember { mutableStateOf<List<GeoCluster>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AutoAlbum>>(emptyList()) }

    LaunchedEffect(Unit) {
        val personsJob = launch { persons = MediaService.getPersons().orEmpty() }
        val geosJob = launch { geos = MediaService.getGeoClusters().orEmpty() }
        val albumsJob = launch { albums = MediaService.getAutoAlbums() }
        personsJob.join(); geosJob.join(); albumsJob.join()
    }

    Surface(
        color = AppBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = PagePadding),
        ) {
            // 顶部：返回 + 灰底圆角搜索框(一刻相册式)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                IconButton(onClick = onClose) {
                    Text("←", fontSize = 22.sp, color = TextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索照片，如：海边、汉服、蛋糕") },
                    leadingIcon = { Text("🔍", fontSize = 16.sp) },
                    singleLine = true,
                    enabled = !searching,
                    shape = RoundedCornerShape(RadiusPill),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        if (query.trim().isNotEmpty() && !searching) {
                            searching = true
                            scope.launch {
                                val res = MediaService.getAISearch(query.trim(), 60)
                                searching = false
                                if (res?.results?.isNotEmpty() == true) {
                                    onResults(res.results.map { it.media })
                                }
                            }
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            if (searching) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Spacer(Modifier.height(8.dp))
                // 回忆：月份聚合入口(一刻相册"回忆破卡片")
                SectionTitle3("回忆")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(listOf("近期", "宝贝", "旅程")) { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(RadiusCard))
                                .background(CardWhite)
                                .clickable { query = tag }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (tag == "近期") "🕰" else if (tag == "旅程") "✈️" else "👶",
                                    fontSize = 24.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(tag, fontSize = T_Label, color = TextPrimary)
                            }
                        }
                    }
                }

                // 人物：横排圆头像
                SectionTitle3("人物")
                if (persons.isEmpty()) {
                    EmptyState("暂无人物聚类")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(persons.take(8)) { pc ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { query = pc.name.ifEmpty { "人物" } }
                            ) {
                                Box(
                                    modifier = Modifier.size(56.dp)
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(Color(0xFFF8F6FE)),
                                    contentAlignment = Alignment.Center
                                ) { Text("🙂", fontSize = 26.sp) }
                                Spacer(Modifier.height(4.dp))
                                Text(if (pc.name.isEmpty()) "未命名" else pc.name,
                                    fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                            }
                        }
                    }
                }

                // 地点：横向缩略/统计卡
                SectionTitle3("地点")
                if (geos.isEmpty()) {
                    EmptyState("暂无位置数据")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(geos.take(8)) { g ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(RadiusCard))
                                    .background(CardWhite)
                                    .clickable { }
                                    .padding(12.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📍", fontSize = 22.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${g.count} 张", fontSize = T_Label, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                // 场景：横排卡(点开即 AI 检索该场景)
                SectionTitle3("场景")
                if (albums.isEmpty()) {
                    EmptyState("暂无场景分类")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums.take(10)) { a ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(RadiusCard))
                                    .background(CardWhite)
                                    .clickable { query = a.scene }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🖼", fontSize = 22.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(a.scene, fontSize = T_Label, color = TextPrimary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle3(title: String) {
    Text(
        title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary,
        modifier = Modifier.padding(top = SPSection, bottom = 8.dp)
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = TextSecondary)
    }
}

