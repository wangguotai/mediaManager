package com.wgt.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private const val TAG = "SlideshowPlayer"

/** 每张图片自动播放时长（毫秒）。 */
private const val SLIDESHOW_INTERVAL_MS = 3000L

/** 淡入淡出过渡时长（毫秒）。 */
private const val FADE_DURATION_MS = 500

/** 手势滑动切换阈值（像素）。 */
private const val SWIPE_THRESHOLD = 100f

/**
 * 全屏幻灯片播放器。
 *
 * 功能：
 * - 自动每 [SLIDESHOW_INTERVAL_MS] 毫秒切换下一张，循环播放
 * - 淡入淡出过渡动画（[FADE_DURATION_MS] 毫秒）
 * - 底部进度指示器（圆点 + 进度条）
 * - 点击暂停/继续
 * - 左右手势滑动手动切换
 * - 退出按钮
 *
 * @param mediaList 图片列表
 * @param initialIndex 起始索引
 * @param useBackendLoader true 走 BackendImageLoader（后端 HTTP），false 走平台相册加载器
 * @param onDismiss 退出回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun SlideshowPlayer(
    mediaList: List<MediaMetadata>,
    initialIndex: Int = 0,
    useBackendLoader: Boolean = false,
    onDismiss: () -> Unit
) {
    if (mediaList.isEmpty()) {
        onDismiss()
        return
    }

    val scope = rememberCoroutineScope()

    // 暂停状态
    var isPaused by remember { mutableStateOf(false) }

    // 手势滑动累计偏移
    var totalDrag by remember { mutableStateOf(0f) }

    // 使用 HorizontalPager 实现图片切换
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, mediaList.lastIndex)) {
        mediaList.size
    }
    val currentIndex by remember { derivedStateOf { pagerState.currentPage } }
    val currentMedia = mediaList[currentIndex]

    // 自动播放进度（0f → 1f 循环，到达阈值时切换下一张）
    val infiniteTransition = rememberInfiniteTransition(label = "slideshowProgress")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SLIDESHOW_INTERVAL_MS.toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "autoProgress"
    )

    // 自动播放：progress 跨阈值时切换下一张。
    // snapshotFlow + distinctUntilChanged 保证每个周期只触发一次。
    LaunchedEffect(isPaused, mediaList.size) {
        if (!isPaused && mediaList.size > 1) {
            snapshotFlow { progress }
                .distinctUntilChanged()
                .collect { p ->
                    if (p >= 0.98f) {
                        val next = (pagerState.currentPage + 1) % mediaList.size
                        pagerState.animateScrollToPage(next)
                    }
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 图片页 —— HorizontalPager 自带滑动手势切换
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 单击暂停/继续（与 pager 的滑动手势共存：
                    // detectTapGestures 只消费轻点，不消费拖拽，pager 仍能接收滑动）
                    detectTapGestures(
                        onTap = { isPaused = !isPaused }
                    )
                }
        ) { page ->
            SlideshowImage(
                media = mediaList[page],
                useBackendLoader = useBackendLoader
            )
        }

        // 暂停遮罩
        if (isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_play_arrow),
                    contentDescription = "已暂停",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // 顶部退出按钮
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_close),
                contentDescription = "退出幻灯片",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // 顶部暂停/继续按钮
        IconButton(
            onClick = { isPaused = !isPaused },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_play_arrow),
                contentDescription = if (isPaused) "继续" else "暂停",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // 底部信息 + 进度指示器
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            // 当前图片文件名
            Text(
                text = currentMedia.filename,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            // 进度圆点指示器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                mediaList.forEachIndexed { index, _ ->
                    val isActive = index == currentIndex
                    val dotProgress = if (isActive) {
                        if (isPaused) 0f else progress
                    } else {
                        0f
                    }

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(
                                width = if (isActive) 24.dp else 8.dp,
                                height = 8.dp
                            )
                            .clip(CircleShape)
                            .background(
                                if (isActive) {
                                    Color.White.copy(alpha = 0.3f + 0.7f * dotProgress)
                                } else {
                                    Color.White.copy(alpha = 0.2f)
                                }
                            )
                    )
                }
            }

            // 底部线性进度条（跟随自动播放进度）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isPaused) 0f else progress)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/**
 * 幻灯片单张图片：加载全尺寸图片，淡入显示。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SlideshowImage(
    media: MediaMetadata,
    useBackendLoader: Boolean
) {
    var imageBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(media.id, useBackendLoader) {
        scope.launch(dispatchers.io) {
            try {
                val image = if (useBackendLoader) {
                    BackendImageLoader.loadFullImage(media.id)
                } else {
                    loadFullImage(media.id)
                }
                imageBitmap = image
            } catch (e: Exception) {
                // 加载失败静默
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(color = Color.White)
            }
            imageBitmap != null -> {
                // 淡入动画
                var visible by remember(media.id) { mutableStateOf(false) }
                LaunchedEffect(media.id) { visible = true }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(FADE_DURATION_MS)),
                    exit = fadeOut(tween(FADE_DURATION_MS))
                ) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = media.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_image_placeholder),
                        contentDescription = "加载失败",
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("图片加载失败", color = Color.Gray)
                }
            }
        }
    }
}
