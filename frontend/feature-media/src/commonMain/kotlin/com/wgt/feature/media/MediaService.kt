package com.wgt.feature.media

import media.MediaMetadata
import media.MediaType
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay
import com.wgt.platform.logger.logger
import kotlin.time.Clock

// 后端 REST gateway 地址。必须用运行后端的机器在局域网内的 IP，而非 localhost：
// Android 真机的 localhost 指向真机自身，访问不到开发机的服务。
// 此处为本机局域网 IP（确认方式：ifconfig 看 en0/wlan 的 inet）。
private const val BASE_URL = "http://192.168.31.251:8080"

private val jsonClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
}

/**
 * 媒体服务 — 连接后端 REST gateway
 */
object MediaService {

    private var mediaCache: List<MediaMetadata>? = null

    /**
     * 媒体来源 —— 用于前端区分缩略图/原图该走本地相册还是后端 HTTP。
     * 网盘图片与已上传图片都来自后端，缩略图/原图需通过 REST 端点加载；
     * 本地图片来自设备相册，走平台 MediaStore/PHAsset。
     */
    enum class MediaSource { LOCAL, BACKEND }

    /**
     * 从后端获取媒体列表（分页）。
     *
     * @param source 当为 [MediaSource.BACKEND] 且 [cloud] 为 true 时，附加 `q=source=cloud`
     *               查询参数，命中后端网盘图片源（LocalCloudSource）。默认查 uploads 目录。
     * @param cloud 是否查询网盘图片源（仅对 BACKEND 有意义）。
     *
     * 注意：网盘场景（cloud=true）出错时**不**回退 mock 数据，直接抛出——避免假数据
     * 污染网盘 Tab；调用方捕获异常后展示空状态/错误提示。其余场景保持原有 mock 容错。
     */
    suspend fun getMediaList(
        page: Int = 1,
        pageSize: Int = 20,
        source: MediaSource = MediaSource.BACKEND,
        cloud: Boolean = false
    ): List<MediaMetadata> {
        return try {
            val response: HttpResponse = jsonClient.get("$BASE_URL/api/media/list") {
                parameter("page", page)
                parameter("page_size", pageSize)
                if (cloud) parameter("q", "source=cloud")
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val parsed = parseMediaList(body)
                logger.info(
                    "MediaService",
                    "getMediaList cloud=$cloud status=${response.status} parsed=${parsed.size}"
                )
                parsed
            } else {
                // 网盘场景不回退 mock，保证 UI 真实性
                if (cloud) throw RuntimeException("后端返回 ${response.status}")
                mockMediaList(page)
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaList FAILED cloud=$cloud: ${e::class.simpleName} ${e.message}")
            if (cloud) throw e
            mockMediaList(page)
        }
    }

    /**
     * 获取图片原图字节流 (通过后端 REST proxy → gRPC GetMediaStream)
     */
    suspend fun getMediaStream(mediaId: String): ByteArray? {
        return try {
            val response: HttpResponse = jsonClient.get("$BASE_URL/api/media/stream/$mediaId")
            val bytes = if (response.status == HttpStatusCode.OK) response.body<ByteArray>() else null
            if (bytes != null) {
                logger.info("MediaService", "getMediaStream id=$mediaId status=${response.status} bytes=${bytes.size}")
            } else {
                logger.info("MediaService", "getMediaStream id=$mediaId status=${response.status} (no body)")
            }
            bytes
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaStream FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 获取缩略图字节
     */
    suspend fun getThumbnail(mediaId: String, size: String = "medium"): ByteArray? {
        return try {
            val response: HttpResponse = jsonClient.get("$BASE_URL/api/media/thumbnail/$mediaId") {
                parameter("size", size)
            }
            val bytes = if (response.status == HttpStatusCode.OK) response.body<ByteArray>() else null
            if (bytes != null) {
                logger.info("MediaService", "getThumbnail id=$mediaId status=${response.status} bytes=${bytes.size}")
            } else {
                logger.info("MediaService", "getThumbnail id=$mediaId status=${response.status} (no body)")
            }
            bytes
        } catch (e: Exception) {
            logger.error("MediaService", "getThumbnail FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 批量删除媒体
     */
    suspend fun deleteMedia(mediaIds: List<String>): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("$BASE_URL/api/media/delete") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("media_ids", Json.encodeToJsonElement(mediaIds)) })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            delay(300)
            true
        }
    }

    /**
     * 上传媒体
     */
    suspend fun uploadMedia(fileData: ByteArray, filename: String, isLivePhoto: Boolean = false): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("$BASE_URL/api/media/upload") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("filename", filename)
                    put("is_live_photo", isLivePhoto)
                    put("data", Json.encodeToJsonElement(fileData.toList()))
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            delay(1000)
            true
        }
    }

    /**
     * 发送命令到 OpenClaw (通过后端桥梁)
     *
     * @param path OpenClaw gateway 上的路径，必须以 '/' 开头
     * @param method HTTP method，默认 POST；后端白名单 GET/POST/PUT/PATCH/DELETE
     * @param body 请求体 JSON，可选
     */
    suspend fun sendOpenClawCommand(
        path: String,
        method: String = "POST",
        body: JsonObject? = null
    ): JsonObject? {
        return try {
            val response: HttpResponse = jsonClient.post("$BASE_URL/api/openclaw/command") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("path", path)
                    put("method", method)
                    if (body != null) put("body", body)
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                Json.decodeFromString(JsonObject.serializer(), respBody)
            } else null
        } catch (e: Exception) { null }
    }

    // ---- 解析 ----

    private fun parseMediaList(json: String): List<MediaMetadata> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["media_list"]?.jsonArray?.map { item ->
            val m = item.jsonObject
            MediaMetadata(
                id = m["id"]?.jsonPrimitive?.content ?: "",
                filename = m["filename"]?.jsonPrimitive?.content ?: "",
                type = MediaType.valueOf(m["type"]?.jsonPrimitive?.content?.uppercase() ?: "IMAGE"),
                size = m["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                mime_type = m["mime_type"]?.jsonPrimitive?.content ?: "",
                created_at = m["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                updated_at = m["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                is_live_photo = m["is_live_photo"]?.jsonPrimitive?.booleanOrNull ?: false,
                live_photo_video_id = m["live_photo_video_id"]?.jsonPrimitive?.content ?: "",
                width = m["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = m["height"]?.jsonPrimitive?.intOrNull ?: 0
            )
        } ?: emptyList()
    }

    // ---- MOCK fallback ----

    private fun mockMediaList(page: Int): List<MediaMetadata> {
        return when (page) {
            1 -> listOf(
                MediaMetadata(
                    id = "1", filename = "photo1.jpg", type = MediaType.IMAGE,
                    size = 2048576, mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 86400000,
                    updated_at = Clock.System.now().toEpochMilliseconds(),
                    is_live_photo = false
                ),
                MediaMetadata(
                    id = "2", filename = "live_photo1.jpg", type = MediaType.LIVE_PHOTO,
                    size = 5048576, mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 172800000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 172800000,
                    is_live_photo = true, live_photo_video_id = "video1"
                ),
                MediaMetadata(
                    id = "3", filename = "photo2.png", type = MediaType.IMAGE,
                    size = 1048576, mime_type = "image/png",
                    created_at = Clock.System.now().toEpochMilliseconds() - 259200000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 259200000,
                    is_live_photo = false
                ),
                MediaMetadata(
                    id = "4", filename = "live_photo2.jpg", type = MediaType.LIVE_PHOTO,
                    size = 6048576, mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 345600000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 345600000,
                    is_live_photo = true, live_photo_video_id = "video2"
                ),
                MediaMetadata(
                    id = "5", filename = "photo3.jpg", type = MediaType.IMAGE,
                    size = 3048576, mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 432000000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 432000000,
                    is_live_photo = false
                )
            )
            else -> emptyList()
        }
    }
}
