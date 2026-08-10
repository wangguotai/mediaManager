package com.wgt.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.platform.architecture.dispatchers.dispatchers
import com.wgt.platform.logger.logger
import kotlinx.coroutines.launch
import media.MediaMetadata
import media.MediaType

private const val TAG = "FavoritesScreen"

/**
 * 收藏夹页面。
 *
 * 从后端拉取用户收藏的媒体列表（GET /api/media/favorites），以 2 列网格展示缩略图。
 *
 * - 点击照片 → [onMediaClick]（携带完整收藏列表与点击索引，供调用方打开
 *   ImagePreviewDialog 做全屏预览）。
 * - 长按照片 → 取消收藏（POST /api/media/favorite favorite=false）并即时从列表移除，
 *   触感反馈 + Snackbar 提示。
 * - 加载态：居中 [CircularProgressIndicator]。
 * - 空态：emoji + 文案「还没有收藏」+ 引导「点击照片上的星标收藏」。
 *
 * @param viewModel 共享 ViewModel，用于同步 [MediaViewModel.favoriteIds] 与本地缓存
 * @param onBack 返回上一级（设置页）
 * @param onMediaClick 点击缩略图回调，参数为 (收藏列表, 点击索引)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit,
    onMediaClick: (List<MediaMetadata>, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 收藏列表：mutableStateOf 以便加载完成与取消收藏后触发重组刷新网格。
    var favorites by remember { mutableStateOf<List<MediaMetadata>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    // 首次进入拉取服务端收藏列表。
    LaunchedEffect(Unit) {
        loading = true
        try {
            val list = MediaService.getFavorites()
            favorites = list
        } catch (e: Exception) {
            logger.error(TAG, "load favorites failed: ${e.message}")
            scope.launch { snackbarHostState.showSnackbar("加载收藏失败") }
        } finally {
            loaded = true
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            // 加载中：居中转圈。
            !loaded || loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text("加载中…", modifier = Modifier.padding(top = 16.dp))
                }
            }
            // 空态。
            favorites.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("⭐", fontSize = 56.sp)
                    Text(
                        "还没有收藏",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "点击照片上的星标即可收藏",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            // 网格展示。
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    items(
                        items = favorites,
                        key = { it.id }
                    ) { media ->
                        FavoriteGridItem(
                            media = media,
                            onClick = {
                                val idx = favorites.indexOfFirst { it.id == media.id }
                                if (idx >= 0) onMediaClick(favorites, idx)
                            },
                            onLongClick = {
                                // 取消收藏：即时从列表移除（UI 响应），再异步同步后端。
                                favorites = favorites.filterNot { it.id == media.id }
                                // 同步 ViewModel 的 favoriteIds 与后端。
                                viewModel.toggleFavorite(media.id)
                                scope.launch { snackbarHostState.showSnackbar("已取消收藏") }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 收藏网格单项：2 列等宽，正方形缩略图（aspectRatio 1f）。
 *
 * 缩略图经 [BackendImageLoader.loadThumbnail] 异步加载（收藏来自后端，必须走 HTTP 加载器）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteGridItem(
    media: MediaMetadata,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    var thumbnail by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(media.id) { mutableStateOf(true) }
    val itemScope = rememberCoroutineScope()

    LaunchedEffect(media.id) {
        itemScope.launch(dispatchers.io) {
            try {
                thumbnail = BackendImageLoader.loadThumbnail(media.id)
            } catch (e: Exception) {
                logger.error(TAG, "thumbnail load failed ${media.id}: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    val isVideo = media.type == MediaType.VIDEO

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                thumbnail != null -> {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = media.filename,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                else -> {
                    Text("📷", fontSize = 32.sp)
                }
            }

            // 视频标识：居中播放图标。
            if (isVideo && !loading && thumbnail != null) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.BottomEnd)
                        .size(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
