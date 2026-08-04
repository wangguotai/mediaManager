package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.platform.architecture.dispatchers.dispatchers
import com.wgt.platform.logger.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import org.jetbrains.compose.resources.painterResource

/**
 * 视频裁剪（按时间段）— 跨平台 expect 声明。
 *
 * Android: android.media.MediaExtractor + android.media.MediaMuxer
 * iOS: TODO（暂未实现）
 *
 * @param inputPath 源视频文件绝对路径
 * @param outputPath 输出视频文件绝对路径（由调用方管理目录与命名）
 * @param startMs 起始时间（毫秒），≥0
 * @param endMs 结束时间（毫秒），＞startMs
 * @return [VideoTrimResult]，成功时 outputPath 非空、durationMs 为实际写入片段时长
 */
expect fun trimVideo(
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long
): VideoTrimResult

/**
 * 平台删除指定路径文件（忽略不存在）。供 [VideoTrimDialog] 临时文件清理使用。
 * commonMain 不能依赖 `java.io.File`，故收敛为 expect。
 */
expect fun platformDeleteFile(path: String)

/**
 * 视频裁剪结果。
 *
 * @param success 是否成功
 * @param outputPath 输出文件路径，失败时为 null
 * @param durationMs 实际裁剪出的片段时长（毫秒），失败时 0
 * @param errorMessage 失败原因，成功时 null
 */
data class VideoTrimResult(
    val success: Boolean,
    val outputPath: String?,
    val durationMs: Long,
    val errorMessage: String?
)

private const val TAG = "VideoTrimDialog"

/**
 * 全屏视频裁剪对话框。
 *
 * 流程：
 * 1. 进入时按 [durationSeconds] 初始化 start/end 滑块（默认 0 ~ 末段 1s）。
 * 2. 点击「开始裁剪」：先通过 [MediaService.getMediaStream] 下载原片字节到临时文件
 *    （composeApp 无 ktor，复用 feature-media 通道），再调跨平台 [trimVideo] 裁剪，
 *    最后读回裁剪片段字节，经 [onResult] 回传给调用方上传后端。
 * 3. 出错时 [onResult] 回传 null，由调用方 Snackbar 提示。
 *
 * 本对话框不直接上传——上传走 [MediaService.videoTrimUpload]，由 MediaListScreen
 * 统一处理（Snackbar + 列表刷新），保持与「旋转/删除」等操作一致的交互闭环。
 *
 * @param media 待裁剪视频（取 id / filename）
 * @param durationSeconds 视频总时长（秒），作为裁剪上界；为空则尝试按 0 处理并提示
 * @param onResult 裁剪完成回调：成功返回裁剪片段 ByteArray，失败返回 null
 * @param onDismiss 关闭对话框
 */
@Composable
fun VideoTrimDialog(
    media: MediaMetadata,
    durationSeconds: Double?,
    onResult: (ByteArray?) -> Unit,
    onDismiss: () -> Unit
) {
    val totalMs = ((durationSeconds ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    // 默认裁掉最后 1s（若时长 >2s），否则整段
    var startMs by remember { mutableStateOf(0L) }
    var endMs by remember { mutableStateOf(if (totalMs > 2000) totalMs - 1000 else totalMs) }

    // 下载/裁剪阶段状态：null=待命; "downloading"/"trimming"=进行中; "failed"=失败原因
    var phase by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部栏：标题 + 关闭
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
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "裁剪视频",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp
                )
            }

            // 中部：滑块控制
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (totalMs <= 0) {
                    Text(
                        "无法获取视频时长，请稍后重试",
                        color = Color.White,
                        fontSize = 15.sp
                    )
                } else {
                    Text(
                        media.filename,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    // 起始滑块
                    Column {
                        Text(
                            "起始：${formatTrim(startMs)}",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Slider(
                            value = startMs.toFloat(),
                            onValueChange = {
                                startMs = it.toLong().coerceIn(0, (endMs - 200).coerceAtLeast(0))
                            },
                            valueRange = 0f..totalMs.toFloat(),
                            enabled = phase == null
                        )
                    }
                    // 结束滑块
                    Column {
                        Text(
                            "结束：${formatTrim(endMs)}",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Slider(
                            value = endMs.toFloat(),
                            onValueChange = {
                                endMs = it.toLong().coerceIn((startMs + 200).coerceAtLeast(0), totalMs)
                            },
                            valueRange = 0f..totalMs.toFloat(),
                            enabled = phase == null
                        )
                    }
                    Text(
                        "片段时长：${formatTrim(endMs - startMs)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }

            // 底部：动作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = phase == null) {
                    Text("取消", color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = if (phase == null && endMs > startMs) MaterialTheme.colorScheme.primary
                            else Color.Gray,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.clip(RoundedCornerShape(24.dp))
                ) {
                    TextButton(
                        onClick = {
                            if (phase != null || endMs <= startMs || totalMs <= 0) return@TextButton
                            scope.launch {
                                phase = "downloading"
                                val bytes = withContext(dispatchers.io) {
                                    MediaService.getMediaStream(media.id)
                                }
                                if (bytes == null || bytes.isEmpty()) {
                                    logger.error(TAG, "download original failed id=${media.id}")
                                    phase = null
                                    onResult(null)
                                    return@launch
                                }
                                phase = "trimming"
                                val trimmed = withContext(dispatchers.io) {
                                    trimToBytes(bytes, startMs, endMs, media.id)
                                }
                                phase = null
                                onResult(trimmed)
                            }
                        },
                        enabled = phase == null && endMs > startMs && totalMs > 0
                    ) {
                        if (phase != null) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .height(16.dp)
                                    .width(16.dp)
                            )
                        }
                        Text(
                            when (phase) {
                                "downloading" -> "下载原片…"
                                "trimming" -> "裁剪中…"
                                else -> "开始裁剪"
                            },
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把下载到内存的原片字节写入临时文件，调 [trimVideo] 裁剪，再读回片段字节。
 * 在 IO 线程执行。
 *
 * @param original 原片字节
 * @param startMs 起始毫秒
 * @param endMs 结束毫秒
 * @param mediaId 用于命名临时文件
 * @return 裁剪片段字节；失败 null
 */
private fun trimToBytes(
    original: ByteArray,
    startMs: Long,
    endMs: Long,
    mediaId: String
): ByteArray? {
    val safeId = mediaId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val dir = getOfflineCacheDir()
    val inPath = "$dir/vt_src_$safeId.mp4"
    val outPath = "$dir/vt_out_$safeId.mp4"
    return try {
        platformWriteBytes(inPath, original)
        val res = trimVideo(inPath, outPath, startMs, endMs)
        if (!res.success || res.outputPath == null) {
            logger.error(TAG, "trim failed: ${res.errorMessage}")
            return null
        }
        val out = platformReadBytes(res.outputPath)
        if (out == null || out.isEmpty()) {
            logger.error(TAG, "read trimmed output failed: ${res.outputPath}")
            null
        } else out
    } catch (e: Exception) {
        logger.error(TAG, "trimToBytes failed: ${e::class.simpleName} ${e.message}")
        null
    } finally {
        // 清理临时文件
        try { platformDeleteFile(inPath) } catch (_: Exception) {}
        try { platformDeleteFile(outPath) } catch (_: Exception) {}
    }
}

/** ms → m:ss 显示。 */
private fun formatTrim(ms: Long): String {
    if (ms < 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "$m:${if (s < 10) "0$s" else s.toString()}"
}
