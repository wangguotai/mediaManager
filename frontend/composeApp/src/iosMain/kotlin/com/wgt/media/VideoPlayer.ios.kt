package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerStatus
import platform.AVFoundation.AVPlayerStatusReadyToPlay
import platform.AVFoundation.currentTime
import platform.AVFoundation.currentItem
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIView
import platform.UIKit.UIColor
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * iOS 实现：AVPlayer + AVPlayerLayer（包在 UIView 中，经 [UIKitView] 嵌入 Compose）。
 *
 * 用 `AVPlayer(url:)` 直接构造（避开 AVPlayerItem/AVURLAsset 的 KVO 管理复杂度）。
 * 控件用 Compose overlay 与 Android 同构：播放/暂停、可拖拽进度条、当前/总时长、关闭。
 * 进度靠 Compose 协程每 200ms 轮询 [AVPlayer.currentTime]（K/N 安全，不依赖 NSTimer block
 * 签名）；总时长从 `player.duration()` 在 [AVPlayerStatus] 就绪后读取。
 *
 * 生命周期：[DisposableEffect] 中 pause，移除播放器引用，防止离开后音频泄漏。
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
@Composable
internal actual fun VideoPlayer(
    media: MediaMetadata,
    initialDurationSeconds: Double?,
    onDismiss: () -> Unit
) {
    val player = remember { AVPlayer(uRL = NSURL.URLWithString(backendStreamUrl(media.id))!!) }
    val playerLayer = remember { AVPlayerLayer() }

    var isReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationSec by remember {
        mutableFloatStateOf((initialDurationSeconds?.toFloat() ?: 0f))
    }
    var positionSec by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }

    LaunchedEffect(media.id) {
        playerLayer.player = player
        player.play()
        isPlaying = true
    }

    // 进度轮询 + 就绪态探测：用协程 delay 循环，避免 NSTimer block 签名不确定。
    LaunchedEffect(media.id) {
        while (isActive) {
            // AVPlayer 自身无 duration（该属性属 AVPlayerItem），经 currentItem 取；
            // 就绪前 currentItem 可能为 null 或 duration 为 indefinite，cmToSeconds 会兜底 0。
            val durCm = player.currentItem()?.duration
            val dur = if (durCm != null) cmToSeconds(durCm) else 0.0
            if (dur > 0 && !dur.isNaN() && !dur.isInfinite() && durationSec <= 0f) {
                durationSec = dur.toFloat()
            }
            if (!isReady && player.status() == AVPlayerStatusReadyToPlay) {
                isReady = true
            }
            if (!seeking) {
                positionSec = cmToSeconds(player.currentTime()).toFloat().coerceAtLeast(0f)
            }
            delay(200)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.pause()
            playerLayer.player = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        UIKitView(
            factory = {
                UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
                    backgroundColor = UIColor.blackColor()
                    layer.addSublayer(playerLayer)
                }
            },
            onResize = { _, rect ->
                playerLayer.frame = rect
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 顶部：关闭 + 文件名
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

        // 底部控件
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.play()
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
                    formatDuration(positionSec.toDouble()),
                    color = Color.White,
                    fontSize = 12.sp
                )
                Slider(
                    value = positionSec,
                    onValueChange = {
                        seeking = true
                        positionSec = it
                    },
                    onValueChangeFinished = {
                        player.seekToTime(CMTimeMakeWithSeconds(positionSec.toDouble(), 600))
                        seeking = false
                    },
                    valueRange = 0f..(durationSec.coerceAtLeast(1f)),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    formatDuration(durationSec.toDouble()),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * CMTime → 秒。CMTime 在 K/N 为值类型（CValue<CMTime>），value/timescale 经
 * [useContents] 在块内访问；对 indefinite/NaN 返回 0，避免污染进度条。
 */
@OptIn(ExperimentalForeignApi::class)
private fun cmToSeconds(cm: CValue<platform.CoreMedia.CMTime>): Double {
    val (ts, v) = cm.useContents { timescale.toDouble() to value.toDouble() }
    if (ts <= 0.0) return 0.0
    val sec = v / ts
    return if (sec.isNaN() || sec.isInfinite()) 0.0 else sec
}
