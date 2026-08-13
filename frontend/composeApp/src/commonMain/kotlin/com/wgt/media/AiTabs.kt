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

        // 一键创建相册入口 —— 对标一刻相册「一键创建相册 >」
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenAlbums() },
                shape = RoundedCornerShape(RadiusCard),
                colors = CardDefaults.cardColors(containerColor = CardWhite)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(SPCardPad)
                ) {
                    Text("＋", fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("一键创建相册", fontWeight = FontWeight.Medium, color = TextPrimary,
                        modifier = Modifier.weight(1f))
                    Text("查看 ›", fontSize = T_Label, color = TextSecondary)
                }
            }
        }

        // 筛选 pill（全部 / 我的 / 共享）—— 对标一刻相册相册页筛选标签
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("全部", "我的", "共享").forEach { tag ->
                    val selected = tag == "全部"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(RadiusPill))
                            .background(if (selected) Primary else Color(0xFFEDF0F8))
                            .clickable { }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(tag, fontSize = T_Label, color = if (selected) Color.White else TextSecondary)
                    }
                }
            }
        }

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
                // 人物 —— 一刻相册风格：横排圆头像 + 右侧「更多 >」
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("人物", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (persons.isNotEmpty()) {
                            Text("更多 ›", fontSize = T_Label, color = TextSecondary)
                        }
                    }
                }
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
                // 足迹 —— 一刻相册风格：大地图统计卡 + 可点击地点
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("足迹", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (geos.isEmpty()) {
                    item { EmptyHint("暂无位置数据（照片需含 GPS）") }
                } else {
                    item { FootprintSummaryCard(geos) }
                    items(geos.take(6)) { g ->
                        FootprintRow(g)
                    }
                }
            }
        }
    }
}

/** 创意工具项（对标一刻相册创意 Tab） */
private data class CreativeTool(val icon: String, val label: String, val action: () -> Unit)

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

    // 工具项（对标一刻相册创意 Tab 的 2 行工具图标）
    val tools = listOf(
        CreativeTool("🎬", "视频专区", {
            scope.launch {
                videos = MediaService.getMediaListPaged(cloud = true, pageSize = 40)
                    .list.filter { it.type == MediaType.VIDEO }
            }
        }),
        CreativeTool("🖼", "照片编辑", {}),
        CreativeTool("📽", "幻灯片", onSlideshow),
        CreativeTool("🧠", "AI 注解", {}),
        CreativeTool("🧹", "文件清理", {}),
        CreativeTool("⭐", "收藏夹", {})
    )

    LazyColumn(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()
        .padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SPGap)) {
        item { SectionTitle("创意") }

        // 两个大主按钮（对标"Ai改图/导入图片"）
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryActionButton(
                    label = "AI 改图 / 增强",
                    icon = "⚡",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    container = Primary
                )
                PrimaryActionButton(
                    label = "导入图片",
                    icon = "＋",
                    onClick = { },
                    modifier = Modifier.weight(1f),
                    container = Color(0xFFF0B429)
                )
            }
        }

        // 2 行工具网格（每行 3 个）
        item {
            Column(verticalArrangement = Arrangement.spacedBy(SPGap)) {
                tools.chunked(3).forEach { rowTools ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SPGap)
                    ) {
                        rowTools.forEach { t ->
                            ToolIconCell(icon = t.icon, label = t.label, onClick = t.action,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 视频列表（点"视频专区"后加载）
        if (videos.isNotEmpty()) {
            item { Spacer(Modifier.height(8.dp)); Text("视频", fontWeight = FontWeight.SemiBold, color = TextPrimary) }
            items(videos.take(20)) { v ->
                VideoRow(v)
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(RadiusPill),
        colors = ButtonDefaults.buttonColors(containerColor = container)
    ) {
        Spacer(Modifier.width(4.dp))
        Text("$icon $label", fontSize = T_Body, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun ToolIconCell(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onClick() }.padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFEDF0F8)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 26.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = TextPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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

/**
 * 足迹「点亮地图」统计大卡 —— 对标一刻相册查找页的地图统计卡。
 * 数据层面我们只有 GPS 聚类点（无反向地理编码的城市/国家），故用
 * 「地点数 + 总照片数」作为旅程/城市/国家的映射，视觉上与一刻相册对齐。
 */
@Composable
private fun FootprintSummaryCard(geos: List<MediaService.GeoCluster>) {
    val locCount = geos.size
    val photoCount = geos.sumOf { it.count }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Column(Modifier.padding(SPCardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗺", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Text("点亮地图", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SPGap)) {
                FootprintStat("地点", locCount.toString(), Modifier.weight(1f))
                FootprintStat("照片", photoCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text("照片含 GPS 方可聚类定位", fontSize = T_Label, color = TextSecondary)
        }
    }
}

@Composable
private fun FootprintStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusCard))
            .background(Color(0xFFEDF0F8))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = T_Label, color = TextSecondary)
    }
}

/** 单个足迹地点行 —— 卡片化，替代扁平文本。 */
@Composable
private fun FootprintRow(g: MediaService.GeoCluster) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { },
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = SPCardPad, vertical = 10.dp)
        ) {
            Text("📍", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("%.3f, %.3f".format(g.lat, g.lng),
                    fontSize = T_Body, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("${g.count} 张照片", fontSize = T_Label, color = TextSecondary)
            }
        }
    }
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