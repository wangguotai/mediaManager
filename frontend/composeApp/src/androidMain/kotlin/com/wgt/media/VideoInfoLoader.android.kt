package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "VideoInfoLoader"

/**
 * Android 端：HttpURLConnection GET `/api/media/video-info/{id}`。
 *
 * 与 [pingBackend] 同款原生 HTTP（composeApp 不引入 ktor）。
 * 非视频/后端未实现/不存在 → 后端返回 500/501，此处返回 null 降级。
 */
internal actual suspend fun loadVideoInfo(backendUrl: String, mediaId: String): VideoInfo? =
    withContext(Dispatchers.IO) {
        val base = backendUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        val url = URL("$base/api/media/video-info/$mediaId")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 8000
            instanceFollowRedirects = true
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                logger.info(TAG, "video-info $mediaId code=$code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj: JsonObject = Json.parseToJsonElement(body).jsonObject
            val dur = obj["duration_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val w = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val h = obj["height"]?.jsonPrimitive?.intOrNull ?: 0
            VideoInfo(dur, w, h)
        } catch (e: Exception) {
            logger.error(TAG, "video-info failed for $mediaId: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
