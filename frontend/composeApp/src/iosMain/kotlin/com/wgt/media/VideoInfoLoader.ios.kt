package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSJSONReadingOptions
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.dataTaskWithRequest
import kotlin.coroutines.resume

private const val TAG = "VideoInfoLoader"

/**
 * iOS 端：NSURLSession GET `/api/media/video-info/{id}`，用 NSJSONSerialization 解析。
 *
 * 与 [pingBackend] 同款原生网络（composeApp 未引入 ktor）。失败/非视频返回 null 降级。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun loadVideoInfo(backendUrl: String, mediaId: String): VideoInfo? {
    val base = backendUrl.trim().trimEnd('/').ensureHttpScheme()
    if (base.isEmpty()) return null
    val url = NSURL.URLWithString("$base/api/media/video-info/$mediaId") ?: return null

    val config = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        timeoutIntervalForRequest = 8.0
    }
    val session = NSURLSession.sessionWithConfiguration(config)
    return try {
        suspendCancellableCoroutine<VideoInfo?> { cont ->
            val request = NSMutableURLRequest.requestWithURL(url).apply {
                HTTPMethod = "GET"
            }
            val task: NSURLSessionDataTask = session.dataTaskWithRequest(request) { data, response, error ->
                if (cont.isCompleted) return@dataTaskWithRequest
                if (error != null) {
                    logger.error(TAG, "video-info error: $error")
                    cont.resume(null)
                    return@dataTaskWithRequest
                }
                val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
                if (code == null || code !in 200..299) {
                    logger.info(TAG, "video-info $mediaId code=$code")
                    cont.resume(null)
                    return@dataTaskWithRequest
                }
                val info = parseVideoInfo(data)
                cont.resume(info)
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    } finally {
        session.finishTasksAndInvalidate()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun parseVideoInfo(data: NSData?): VideoInfo? {
    if (data == null || data.length == 0UL) return null
    return try {
        val obj = NSJSONSerialization.JSONObjectWithData(
            data,
            NSJSONReadingOptions(0L),
            null
        ) as? Map<*, *> ?: return null
        val dur = (obj["duration_seconds"] as? Number)?.toDouble() ?: 0.0
        val w = (obj["width"] as? Number)?.toInt() ?: 0
        val h = (obj["height"] as? Number)?.toInt() ?: 0
        VideoInfo(dur, w, h)
    } catch (e: Exception) {
        logger.error(TAG, "parse video-info failed: ${e.message}")
        null
    }
}

private fun String.ensureHttpScheme(): String {
    val lower = this.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> this
        lower.startsWith("://") -> "http$this"
        else -> "http://$this"
    }
}
