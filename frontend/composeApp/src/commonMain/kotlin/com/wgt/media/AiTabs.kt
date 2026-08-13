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

        // 活动 Banner —— 还原 spec Ad_Banner(361x172 bg #47B4F5): 营销横幅卡。
        item { AlbumPromoBanner() }

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
                    YkCreateAlbumIcon(size = 26)
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
            // 智能搜图成册 —— 一刻相册风格：浅蓝功能卡 + 白色胶囊场景标签。
            // 动态场景来自 /api/ai/albums(AI 自动分类),点击标签即 AI 语义搜索该场景。
            if (albums.isEmpty()) {
                item { EmptyHint("暂无场景分类，先到照片页索引你的照片") }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(RadiusCard),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE3EBFA)  // 一刻相册「智能搜图成册」浅天蓝底
                        )
                    ) {
                        Column(Modifier.padding(SPCardPad)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("✨", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("智能搜图成册", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                            Spacer(Modifier.height(12.dp))
                            // 白色胶囊场景标签(FlowRow 近似:用 chunked 换行排列)
                            albums.take(9).chunked(3).forEach { rowAlbums ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowAlbums.forEach { album ->
                                        ScenePill(
                                            scene = album.scene,
                                            count = album.count,
                                            onClick = { onOpenAlbum(album.scene) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // 补齐空位，保持每行3个视觉均等
                                    repeat(3 - rowAlbums.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
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

            // 云空间相册 —— 对标一刻相册「新建相册 + 按编辑时间 + 相册卡(封面+标题+N张)」
            // 整段收敛到一个 item 渲染,避免多 item 分支造成漏渲染。
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("云空间相册", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("新建 · 编辑时间", fontSize = T_Label, color = TextSecondary)
                    }
                    if (albums.isEmpty()) {
                        Text("暂无场景相册，先索引照片", fontSize = 13.sp, color = TextSecondary)
                    } else {
                        albums.take(6).chunked(2).forEach { rowAl ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SPGap)) {
                                rowAl.forEach { album ->
                                    AlbumCoverCard(
                                        scene = album.scene,
                                        count = album.count,
                                        onClick = { onOpenAlbum(album.scene) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(2 - rowAl.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(8.dp))
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

/**
 * 智能搜图成册的白色胶囊场景标签 —— 对标一刻相册相册页智能分类胶囊。
 */
@Composable
private fun ScenePill(scene: String, count: Int, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusPill))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(scene, fontSize = T_Label, fontWeight = FontWeight.Medium,
            color = TextPrimary, maxLines = 1)
        Text("$count 张", fontSize = 11.sp, color = TextSecondary)
    }
}

/** 云空间相册封面卡 —— 对标一刻相册相册卡(封面图+标题+N张)。封面暂用emoji占位,可后续接sampleId真图。 */
@Composable
private fun AlbumCoverCard(
    scene: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(72.dp)
                    .background(Color(0xFFF8F6FE)),
                contentAlignment = Alignment.Center
            ) { Text("🖼", fontSize = 28.sp) }
            Column(Modifier.padding(SPCardPad)) {
                Text(scene, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    fontSize = T_Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("$count 张", fontSize = T_Label, color = TextSecondary)
            }
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
                // 类型 —— 对标一刻相册查找页「类型」区。收敛单 item 渲染(多 item 曾漏渲染)。
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Text("类型", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SPGap)) {
                            TypeCard("截图", "📱", { onSemanticSearch("截图") }, Modifier.weight(1f))
                            TypeCard("视频", "🎬", { onSemanticSearch("视频") }, Modifier.weight(1f))
                            TypeCard("动态照片", "📷", { onSemanticSearch("动态照片") }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 查找页「类型」卡 —— 扁平白卡,一刻相册类型区风格。点击做语义搜索该类型。 */
@Composable
private fun TypeCard(label: String, icon: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = SPCardPad, vertical = 14.dp)
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = T_Body, fontWeight = FontWeight.Medium, color = TextPrimary)
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

    // 工具项 —— 对标一刻相册创意 Tab 2行4列共8工具,emoji按 icons_all 库 shape:
    // 时光足迹=时钟/人像美颜=人脸星星/AI消除=橡皮擦/AI动图=方形播放/创意拼图=交错矩形/
    // 一键成片=音符/朋友圈9图=九宫格/全部工具=四圆点。视频专区保留实际功能。
    val tools = listOf(
        CreativeTool("🕐", "时光足迹", onSlideshow),
        CreativeTool("💆", "人像美颜", {}),
        CreativeTool("🧽", "AI 消除", {}),
        CreativeTool("🎞", "AI 动图", {}),
        CreativeTool("🧩", "创意拼图", {}),
        CreativeTool("🎵", "一键成片", {}),
        CreativeTool("🔲", "朋友圈9图", {}),
        CreativeTool("🎬", "视频专区", {
            scope.launch {
                videos = MediaService.getMediaListPaged(cloud = true, pageSize = 40)
                    .list.filter { it.type == MediaType.VIDEO }
            }
        })
    )

    LazyColumn(Modifier.fillMaxSize().background(AppBackground).statusBarsPadding()
        .padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SPGap)) {
        item { SectionTitle("创意") }

        // 活动 Banner —— 还原 spec BannerAd(375x240 bg #E8F5E9): 创意页顶部活动区。
        item { CreativePromoBanner() }

        // 两个大主按钮（对标"Ai改图/导入图片"）—— 像素采样一刻相册创意页:
        // 主按钮为清淡浅色底(#F8F6FE)+深色字,而非白字深色CTA,按像素严格对齐。
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryActionButton(
                    label = "AI 改图 / 增强",
                    icon = "⚡",
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    container = Color(0xFFF8F6FE)
                )
                PrimaryActionButton(
                    label = "导入图片",
                    icon = "＋",
                    onClick = { },
                    modifier = Modifier.weight(1f),
                    container = Color(0xFFF7F8FE)
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
        // 一刻相册创意页主按钮是浅色底+深色字（像素采样#F8F6FE/#F7F8FE + #040B19字）
        Text("$icon $label", fontSize = T_Body, fontWeight = FontWeight.Bold,
            color = TextPrimary)
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
                .background(Color(0xFFF8F6FE)),  // 像素采样一刻相册创意页工具块底色
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
/** 相册Tab活动Banner —— 还原 spec Ad_Banner(bg#47B4F5,夏日出逃计划/赢2000元旅行金/CTA)。 */
@Composable
private fun AlbumPromoBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF47B4F5))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(SPCardPad)
        ) {
            Column(Modifier.weight(1f)) {
                Text("夏日出逃计划", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(2.dp))
                Text("赢2000元旅行金", color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) { Text("去看看", color = Color(0xFF47B4F5), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            Text("🏖", fontSize = 34.sp)
        }
    }
}

/** 创意Tab活动Banner —— 还原 spec BannerAd(bg #E8F5E9浅绿): 顶部活动入口。 */
@Composable
private fun CreativePromoBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadiusCard),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(SPCardPad)
        ) {
            Column(Modifier.weight(1f)) {
                Text("AI 创作大赛", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text("一键生成创意大片，赢创作奖励", color = TextSecondary, fontSize = 13.sp)
            }
            Text("🎨", fontSize = 30.sp)
        }
    }
}
