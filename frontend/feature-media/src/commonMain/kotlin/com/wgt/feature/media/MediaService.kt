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
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * 后端 REST gateway 地址 —— 运行时可变，由上层（composeApp 的 [SettingsState]）经
 * [setBackendUrl] 注入，不再硬编码。
 *
 * 历史上这里写死 `http://localhost:8080`，导致设置页保存的地址从不生效（P0-1）。
 * 现改为单一可变源：feature-media 不反向依赖 composeApp，故不直接读 SettingsState，
 * 而由 composeApp 在启动与设置变更时把地址推入此处。见 MediaService.setBackendUrl。
 *
 * 默认值与既有行为保持一致（`http://localhost:8080`），首次未配置时仍可命中
 * adb reverse / 本机回环场景；用户在设置页配置后即时覆盖。
 */
@Volatile
private var backendUrl: String = DEFAULT_BACKEND_URL

/** 后端地址默认值——本机回环，配合 adb reverse 可在真机访问开发机后端。 */
private const val DEFAULT_BACKEND_URL = "http://localhost:8080"

/**
 * 归一化后端基址：去首尾空白、去尾斜杠；空串时回退 [DEFAULT_BACKEND_URL]，
 * 保证请求拼接不出空 host。补 http 前缀逻辑留给各端网络层（与 pingBackend 同款）。
 */
private fun backendBaseUrl(): String {
    val trimmed = backendUrl.trim().trimEnd('/')
    return trimmed.ifEmpty { DEFAULT_BACKEND_URL }
}

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
     * 由上层注入运行时后端地址（来自用户设置）。feature-media 不依赖 composeApp，
     * 故采用推模型：composeApp 在启动及 [SettingsState.backendUrl] 变更后调用本方法。
     * 标记 `@Volatile` 保证跨线程可见性（请求协程与设置页 UI 线程并发读写）。
     *
     * 本方法作为 [MediaService] 的成员声明（而非顶层函数），以便上层以
     * `MediaService.setBackendUrl(...)` 调用——与 [App] 的注入点口径一致。
     */
    fun setBackendUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized == backendUrl) return
        backendUrl = normalized
        logger.info("MediaService", "backend url updated: $normalized")
    }

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
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/list") {
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
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/stream/$mediaId")
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
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/thumbnail/$mediaId") {
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
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/delete") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("media_ids", Json.encodeToJsonElement(mediaIds)) })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "deleteMedia FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * 上传媒体
     *
     * 协议：POST /api/media/upload，body = 文件原始字节流（raw bytes），
     * filename 通过 query param 传递。与后端 handleMediaUpload 的读取方式对齐
     * （io.ReadAll 读取 raw body）。
     *
     * @param fileData 文件字节
     * @param filename 原始文件名（用于后端取扩展名与 metadata sidecar）
     * @param isLivePhoto 是否为 Live Photo（通过 query param 传递）
     */
    suspend fun uploadMedia(fileData: ByteArray, filename: String, isLivePhoto: Boolean = false): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/upload") {
                parameter("filename", filename)
                if (isLivePhoto) parameter("is_live_photo", "true")
                contentType(ContentType.Application.OctetStream)
                setBody(fileData)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "uploadMedia FAILED filename=$filename: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * 切换媒体收藏状态。
     *
     * 调用后端 POST /api/media/favorite，传递 media_id 与 favorite 布尔值。
     * 后端负责持久化收藏状态；前端同时通过 [FavoriteStore] 做本地缓存，
     * 以便离线时仍能显示收藏标记。
     *
     * @param mediaId 目标媒体 ID
     * @param favorite true=收藏，false=取消收藏
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun toggleFavorite(mediaId: String, favorite: Boolean): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/favorite") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_id", mediaId)
                    put("favorite", favorite)
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "toggleFavorite id=$mediaId fav=$favorite status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "toggleFavorite FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * 获取收藏列表（从后端）。
     *
     * 调用 GET /api/media/favorites，返回后端记录的收藏媒体元数据列表。
     * 用于在仅显示收藏项时从后端拉取完整收藏数据。
     * 失败时返回空列表，调用方可回退到本地缓存的 favoriteIds 做客户端过滤。
     */
    suspend fun getFavorites(): List<MediaMetadata> {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/favorites")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val parsed = parseMediaList(body)
                logger.info("MediaService", "getFavorites status=${response.status} parsed=${parsed.size}")
                parsed
            } else {
                logger.info("MediaService", "getFavorites status=${response.status} (non-OK)")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getFavorites FAILED: ${e::class.simpleName} ${e.message}")
            emptyList()
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
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/openclaw/command") {
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
