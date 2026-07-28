package com.wgt.media

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端连通性测试 —— HttpURLConnection HEAD 请求。
 *
 * 在 IO 线程执行；连接超时 3s、读取超时 3s，避免设置页"测试连通性"长时间转圈。
 * 成功判定：连接建立且响应码 < 500 即视为可达（后端存在但返回 4xx 也算"通"，
 * 因为只关心网络层面能否到达后端进程）。任何异常转为可读失败描述返回。
 */
actual suspend fun pingBackend(backendUrl: String): String? = withContext(Dispatchers.IO) {
    val normalized = backendUrl.trim().trimEnd('/')
    if (normalized.isEmpty()) return@withContext "地址为空"
    val base = normalized.ensureHttpScheme()
    try {
        val url = URL(base)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 3000
            readTimeout = 3000
            instanceFollowRedirects = true
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code in 200..499) null else "HTTP $code"
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        e::class.simpleName + ": " + (e.message ?: "连接失败")
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
