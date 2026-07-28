package com.wgt.media

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wgt.platform.logger.logger
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private const val TAG = "VideoPlayer"

/**
 * Android 实现：原生 [VideoView] + Compose 自绘控件。
 *
 * 选 VideoView 而非 ExoPlayer(Media3)：项目未引入 media3 依赖，按约束先用原生组件
 * 实现基础功能。VideoView 内部用 MediaPlayer，对 HTTP `Range` 请求原生支持，
 * 可边下边播、可 seekTo 拖拽。
 *
 * 生命周期：在 [DisposableEffect] 中停止释放，避免离开播放器后音频继续。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun VideoPlayer(
    media: MediaMetadata,
    initialDurationSeconds: Double?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val videoView = remember { VideoView(context) }

    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember {
        mutableFloatStateOf((initialDurationSeconds ?: 0.0).toFloat() * 1000f)
    }
    var positionMs by remember { mutableFloatStateOf(0f) }
    // 用户拖拽进度条时暂时停止回写，避免手俱与播放进度互相抢占。
    var seeking by remember { mutableStateOf(false) }

    // 仅初始化一次：挂监听 + 设置视频源。VideoView 自带 prepare/播放。
    LaunchedEffect(media.id) {
        videoView.setOnPreparedListener { mp ->
            isPrepared = true
            if (durationMs <= 0f) durationMs = mp.duration.toFloat().coerceAtLeast(0f)
            // 自动起播
            videoView.start()
            isPlaying = true
        }
        videoView.setOnCompletionListener {
            isPlaying = false
        }
        videoView.setOnErrorListener { _, what, extra ->
            logger.error(TAG, "VideoView error what=$what extra=$extra id=${media.id}")
            isPrepared = false
            true // 已处理，避免弹原生错误框
        }
        videoView.setVideoURI(Uri.parse(backendStreamUrl(media.id)))
    }

    // 进度轮询：每 200ms 取当前位置更新进度条，拖拽中暂停回写。
    LaunchedEffect(media.id, isPrepared) {
        while (isPrepared) {
            if (!seeking && videoView.isPlaying) {
                positionMs = videoView.currentPosition.toFloat()
                isPlaying = true
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // 离开时停止释放，防止音频泄漏。
    DisposableEffect(Unit) {
        onDispose {
            videoView.stopPlayback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 视频画面
        AndroidView(
            factory = { videoView },
            modifier = Modifier.fillMaxSize()
        )

        // 加载中遮罩
        if (!isPrepared) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 顶部：文件名 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                media.filename,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // 底部控件：播放/暂停 + 进度条 + 时长
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        isPlaying = false
                    } else {
                        videoView.start()
                        isPlaying = true
                    }
                }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_play_arrow),
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    formatDuration(positionMs / 1000.0),
                    color = Color.White,
                    fontSize = 12.sp
                )
                Slider(
                    value = positionMs,
                    onValueChange = {
                        seeking = true
                        positionMs = it
                    },
                    onValueChangeFinished = {
                        videoView.seekTo(positionMs.toInt())
                        seeking = false
                    },
                    valueRange = 0f..(durationMs.coerceAtLeast(1f)),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    formatDuration(durationMs / 1000.0),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
