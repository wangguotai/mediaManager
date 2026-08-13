// PRD-v12 UI：对标一刻相册 4-Tab 的「相册 / 查找 / 创意」三个 Tab 页。
// 复用既有 MediaService AI 全套 API（人物聚类 / 自动场景相册 / geo 聚类 / AI 检索）。
package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.wgt.feature.media.MediaService.AutoAlbum
import com.wgt.feature.media.MediaService.PersonCluster
import media.MediaMetadata
import media.MediaType
import kotlinx.coroutines.launch

/**
 * 相册 Tab：自动场景相册 + 人物聚类 + 自建相册。
 * 对标一刻相册相册页的智能相册结构。
 */
@Composable
fun AlbumTabScreen(
    onDismiss: () -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    onOpenAlbums: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<AutoAlbum>>(emptyList()) }
    var persons by remember { mutableStateOf<List<PersonCluster>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        albums = MediaService.getAutoAlbums()
        persons = MediaService.getPersons()
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AppBackground).statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SPGap)
    ) {
        item { SectionTitle("相册") }

        if (loading) {
            item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
        } else {
            // 智能相册（场景）— 一刻相册风格：横排圆圈封面卡
            item { Text("智能相册 · 按场景", fontWeight = FontWeight.SemiBold) }
            if (albums.isEmpty()) {
                item { EmptyHint("暂无场景分类，先到照片页索引你的照片") }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums.take(12)) { album ->
                            SceneCard(
                                title = album.scene,
                                count = album.count,
                                onClick = { onOpenAlbum(album.scene) }
                            )
                        }
                    }
                }
            }

            // 人物（长相聚类）—— 一刻相册风格：横向圆形头像
            item {
                Spacer(Modifier.height(8.dp))
                Text("人物 · 按长相分组", fontWeight = FontWeight.SemiBold)
            }
            if (persons.isEmpty()) {
                item { EmptyHint("暂无人物聚类，索引后可在 AI 中心点重聚类") }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(persons.take(8)) { pc ->
                            PersonAvatar(
                                cluster = pc,
                                onClick = { onOpenPerson(pc.id) },
                                onRename = { name ->
                                    scope.launch { MediaService.renamePerson(pc.id, name) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneCard(title: String, count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(150.dp).clickable { onClick() },
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Column(Modifier.padding(SPCardPad)) {
            Text("🖼", fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("$count 张", fontSize = T_Label,
                color = TextSecondary)
        }
    }
}

@Composable
private fun PersonAvatar(
    cluster: PersonCluster,
    onClick: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(cluster.id) { mutableStateOf(cluster.name) }
    var editing by remember(cluster.id) { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { if (!editing) onClick() }
    ) {
        // 圆形头像(此刻用 emoji 占位,后续可接人脸缩略图)
        Box(
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(32.dp))
                .background(Color(0xFFEDF0F8)),
            contentAlignment = Alignment.Center
        ) {
            Text("🙂", fontSize = 30.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (editing) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                singleLine = true, modifier = Modifier.width(96.dp))
            TextButton(onClick = { onRename(name); editing = false }) { Text("保存") }
        } else {
            Text(if (cluster.name.isEmpty()) "未命名" else cluster.name,
                fontSize = T_Label, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${cluster.faceCount} 张", fontSize = 12.sp,
                color = TextSecondary)
            TextButton(onClick = { editing = true }) { Text("命名") }
        }
    }
}

/**
 * 查找 Tab：AI 语义搜索 + 人物 / 场景 / 足迹聚合。
 */
@Composable
fun SearchTabScreen(
    onSemanticSearch: (String) -> Unit = {},
    onOpenScene: (String) -> Unit = {},
    onOpenPerson: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var albums by remember { mutableStateOf<List<AutoAlbum>>(emptyList()) }
    var persons by remember { mutableStateOf<List<PersonCluster>>(emptyList()) }
    var geos by remember { mutableStateOf<List<MediaService.GeoCluster>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        albums = MediaService.getAutoAlbums()
        persons = MediaService.getPersons()
        geos = MediaService.getGeoClusters() ?: emptyList()
        loading = false
    }

    Column(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()
        .padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SPGap)) {
        SectionTitle("查找")
        // AI 语义搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("自然语言描述，如：穿汉服的照片") },
            leadingIcon = { Text("🧠") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                if (query.isNotBlank()) onSemanticSearch(query)
            }),
            modifier = Modifier.fillMaxWidth()
        )
        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)) {
                // 场景 —— 一刻相册风格：横排卡片
                item { Text("场景", fontWeight = FontWeight.SemiBold) }
                if (albums.isEmpty()) {
                    item { EmptyHint("暂无场景分类") }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(albums.take(10)) { a ->
                                SceneCard(
                                    title = a.scene,
                                    count = a.count,
                                    onClick = { onOpenScene(a.scene) }
                                )
                            }
                        }
                    }
                }
                // 人物 —— 一刻相册风格：横排圆头像
                item { Spacer(Modifier.height(8.dp)); Text("人物") }
                if (persons.isEmpty()) {
                    item { EmptyHint("暂无人物聚类") }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(persons.take(6)) { pc ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { onOpenPerson(pc.id) }
                                ) {
                                    Box(
                                        modifier = Modifier.size(56.dp)
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) { Text("🙂", fontSize = 26.sp) }
                                    Spacer(Modifier.height(4.dp))
                                    Text(if (pc.name.isEmpty()) "未命名" else pc.name,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                // 足迹
                item { Spacer(Modifier.height(8.dp)); Text("足迹", fontWeight = FontWeight.SemiBold) }
                if (geos.isEmpty()) {
                    item { EmptyHint("暂无足迹聚类") }
                } else {
                    items(geos.take(8)) { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📍", fontSize = 20.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("${g.lat}, ${g.lng} · ${g.count} 张", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 创意 Tab —— 视频专区 / 照片编辑器 / 幻灯片入口。
 */
@Composable
fun CreativeTabScreen(
    onClose: () -> Unit = {},
    onOpenEditor: (media: MediaMetadata) -> Unit = {},
    onSlideshow: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("创意", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            ToolCard("🎬 视频专区", "精选你的视频片段", onClick = {
                scope.launch {
                    videos = MediaService.getMediaListPaged(cloud = true, pageSize = 40)
                        .list.filter { it.type == MediaType.VIDEO }
                }
            })
        }
        item { ToolCard("🖼 照片编辑", "裁剪 / 旋转 / 滤镜", onClick = {}) }
        item { ToolCard("📽 幻灯片", "时间线全屏放映", onClick = onSlideshow) }
        item { ToolCard("🧠 AI 注解", "长按照片查看 AI 照片故事", onClick = {}) }

        // 视频列表（点"视频专区"后加载）
        if (videos.isNotEmpty()) {
            item { Text("视频", fontWeight = FontWeight.SemiBold) }
            items(videos.take(20)) { v ->
                VideoRow(v)
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(Dimens.cardCornerRadius)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VideoRow(v: MediaMetadata) {
    ListItem(
        headlineContent = { Text(v.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("视频 · ${v.size} 字节") },
        leadingContent = { Text("🎬", fontSize = 22.sp) }
    )
}

@Composable
private fun EmptyHint(text: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}