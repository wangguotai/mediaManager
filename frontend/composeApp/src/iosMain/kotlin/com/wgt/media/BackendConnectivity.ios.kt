package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume

/**
 * iOS 端连通性测试 —— NSURLSession 探测请求。
 *
 * 用默认 session、请求超时 3s，直接对输入地址发 dataTaskWithURL 探测响应码
 * （无需构造可变 NSMutableURLRequest 即可达到连通性测试目的）。成功判定同 Android：
 * 响应码 < 500 视为可达。任务在协程中挂起，完成回调后 resume；协程取消时
 * 取消 dataTask，避免悬挂的网络任务。
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun pingBackend(backendUrl: String): String? {
    val normalized = backendUrl.trim().trimEnd('/')
    if (normalized.isEmpty()) return "地址为空"
    val base = normalized.ensureHttpScheme()
    val url = NSURL.URLWithString(base) ?: return "无效地址"

    val config = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        timeoutIntervalForRequest = 3.0
    }
    val session = NSURLSession.sessionWithConfiguration(config)
    return try {
        suspendCancellableCoroutine { cont ->
            // 直接用 url dataTask 即可测连通性；响应码 < 500 视为后端可达。
            val task: NSURLSessionDataTask = session.dataTaskWithURL(url) { _, response, error ->
                if (cont.isCompleted) return@dataTaskWithURL
                if (error != null) {
                    cont.resume("${error::class.simpleName}: 连接失败")
                } else {
                    val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
                    if (code != null) {
                        cont.resume(if (code in 200..499) null else "HTTP $code")
                    } else {
                        cont.resume("无响应")
                    }
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    } finally {
        session.finishTasksAndInvalidate()
    }
}

/**
 * 补齐 http(s) 前缀：用户可能只输入 `10.0.2.2:8080`，统一补成 `http://...`。
 */
private fun String.ensureHttpScheme(): String {
    val lower = this.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> this
        lower.startsWith("://") -> "http$this"
        else -> "http://$this"
    }
}
