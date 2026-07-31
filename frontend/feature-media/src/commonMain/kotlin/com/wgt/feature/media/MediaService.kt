package com.wgt.feature.media

import media.MediaMetadata
import media.MediaType
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

/**
 * 当前 JWT token —— 运行时可变，由 composeApp 的 [com.wgt.media.AuthState] 经
 * [MediaService.setAuthToken] 推入。`@Volatile` 保证请求协程与 UI 线程并发读写可见性。
 *
 * [jsonClient] 的 `defaultRequest` 块在每次请求执行时读取本变量，非空则附加
 * `Authorization: Bearer <token>` 头。空串时不附加，使登录/注册等豁免端点正常工作。
 */
@Volatile
private var authToken: String = ""

/** V7 §3.3：返回当前 auth token（供 RN 模块注入 initialProps）。 */
fun getAuthToken(): String = authToken

/**
 * 401 响应回调 —— 由 composeApp 注册（接到 [com.wgt.media.AuthState.clearSession]，
 * 进而触发 App 路由守卫回登录页）。仅记录指针，由 [MediaService.setUnauthorizedHandler] 设置。
 */
@Volatile
private var onUnauthorized: (() -> Unit)? = null

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

/**
 * 统一 HTTP 客户端。
 *
 * 装三样关键能力：
 * 1. [ContentNegotiation] + kotlinx.json —— 既有 JSON 解析。
 * 2. `defaultRequest` —— 闭包运行时读取 [authToken]，非空时给每个请求加
 *    `Authorization: Bearer` 头。这是"Ktor 统一加 token"的注入点；token 变更无需重建 client。
 * 3. [HttpCallValidator] `validateResponse` —— 捕获 401 Unauthorized：token 失效/被撤时，
 *    触发 [onUnauthorized] 回调，由上层清 token 并回登录页。注意此处**不抛异常**，
 *    仅触发副作用并放行响应——让原调用方按既有路径收到 401 状态自行降级（如返回空列表），
 *    避免 401 被包装成异常打断现有 catch 逻辑。
 *
 * `expectSuccess = false`（默认）保证 401 不被 [BadResponseStatus] 异常吞掉，
 * 而是按响应状态流经 validateResponse，便于稳定拦截。
 *
 * 注：Ktor 3.x 移除了 2.x 的 `handleResponse`；响应校验入口为 `validateResponse`，
 * 其 receiver 即 [HttpResponse]。见 [HttpCallValidatorConfig.validateResponse]。
 */
private val jsonClient = HttpClient {
    expectSuccess = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    defaultRequest {
        val t = authToken
        if (t.isNotEmpty()) {
            header(HttpHeaders.Authorization, "Bearer $t")
        }
    }
    install(HttpCallValidator) {
        // 响应到达后检查状态：401 → 清会话。不抛异常，放行响应由调用方按状态降级。
        validateResponse { response ->
            if (response.status == HttpStatusCode.Unauthorized) {
                logger.info("MediaService", "401 received — invoking unauthorized handler")
                // 防递归：若 handler 内部又发请求（clearSession 本不发请求，安全）
                onUnauthorized?.invoke()
            }
        }
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
     * 由上层注入当前 token（来自 [com.wgt.media.AuthState]）。`@Volatile` 保证跨线程可见，
     * [jsonClient] 的 defaultRequest 闭包会读取最新值作 Bearer 头。空串即"未登录"，
     * 请求不发 Authorization（登录/注册本身也走此 client 但无需 token）。
     */
    fun setAuthToken(token: String) {
        if (token == authToken) return
        authToken = token
        logger.info("MediaService", "auth token updated (len=${token.length})")
    }

    /**
     * 注册 401 处理器。App 启动时由 [com.wgt.App] 调用（`LaunchedEffect(Unit)` 内），
     * 传入 AuthState.clearSession——token 失效时清会话 → isLoggedIn 转 false → 回登录页。
     */
    fun setUnauthorizedHandler(handler: () -> Unit) {
        onUnauthorized = handler
    }

    /**
     * 媒体来源 —— 用于前端区分缩略图/原图该走本地相册还是后端 HTTP。
     * 网盘图片与已上传图片都来自后端，缩略图/原图需通过 REST 端点加载；
     * 本地图片来自设备相册，走平台 MediaStore/PHAsset。
     */
    enum class MediaSource { LOCAL, BACKEND }

    // ============ 认证 ============

    /**
     * 登录/注册成功响应（与后端 [auth.AuthResult] JSON 对齐）。
     *
     * 后端结构：`{ "token","expires_at","user":{"id","username","role","created_at"} }`。
     * 用普通 data class 承载——解析走运行时 [Json.parseToJsonElement]（与既有
     * [parseMediaList] 同款），不依赖 kotlinx.serialization 编译器插件（feature-media
     * 未启用该插件）。`expires_at` 是后端 time.Time 的 RFC3339 字符串，前端仅透传不解析。
     */
    data class AuthUser(
        val id: String = "",
        val username: String = ""
    )

    /** 登录/注册响应体。 */
    data class AuthResult(
        val token: String = "",
        val user: AuthUser = AuthUser()
    )

    /**
     * 登录/注册调用的包装结果。区分"网络/HTTP 错误"与"成功"，前者带可读 [error]，
     * 后者带 [result]。这样 UI 侧无需捕获异常，直接按 [success] 分支展示。
     *
     * @param success 是否成功
     * @param result 成功时的认证结果（token 等）
     * @param error 失败时的描述：后端返回的 `{error}` 或本地异常信息
     * @param httpStatus 失败时的 HTTP 状态码（成功为 0）；用于注册被关(403)/用户名占用(409)等区分
     */
    data class AuthOutcome(
        val success: Boolean,
        val result: AuthResult? = null,
        val error: String? = null,
        val httpStatus: Int = 0
    )

    /**
     * 登录。POST /api/auth/login `{username,password}` → 200 `{token,expires_at,user}`。
     *
     * 不依赖 [authToken]（登录端点豁免鉴权）；调用方拿到 [AuthOutcome]，成功时自行
     * 存 token（见 [com.wgt.media.AuthState.saveSession]）。
     *
     * 错误映射（后端 [writeAuthError]）：
     * - 400 凭据错误/空字段 → "用户名或密码错误"
     * - 其余 → 后端 `{error}` 文本或异常摘要
     */
    suspend fun login(username: String, password: String): AuthOutcome =
        authRequest("/api/auth/login", username, password)

    /**
     * 注册。POST /api/auth/register `{username,password}` → 201 同结构。
     *
     * allow_signup 由后端配置控制（off/first/open），**不通过任何端点暴露**，故前端无法
     * 预判是否可注册——直接尝试，错误时按状态码提示：403→"注册已关闭"、409→"用户名已存在"。
     */
    suspend fun register(username: String, password: String): AuthOutcome =
        authRequest("/api/auth/register", username, password)

    /**
     * login/register 共用请求体。两者结构一致，仅路径不同。
     *
     * 成功：解析 [AuthResult]（运行时 JSON 操作，无编译器插件）。失败：尝试从响应体读
     * `{error}` 文本，回退异常摘要。网络异常（连接失败）单列为可读提示，便于登录页
     * 区分"地址不通"与"密码错"。
     */
    private suspend fun authRequest(path: String, username: String, password: String): AuthOutcome {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}$path") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("username", username)
                    put("password", password)
                })
            }
            val code = response.status.value
            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                val body: String = response.body()
                val parsed = parseAuthResult(body)
                logger.info("MediaService", "auth $path OK user=${parsed.user.username}")
                AuthOutcome(success = true, result = parsed, httpStatus = code)
            } else {
                val err = readErrorBody(response)
                logger.info("MediaService", "auth $path failed status=$code err=$err")
                AuthOutcome(success = false, error = err ?: "请求失败（$code）", httpStatus = code)
            }
        } catch (e: Exception) {
            logger.error("MediaService", "auth $path exception: ${e::class.simpleName} ${e.message}")
            AuthOutcome(success = false, error = "无法连接服务器：${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 解析登录/注册成功响应为 [AuthResult]。
     *
     * 后端 `{token,expires_at,user{id,username,role,created_at}}`，前端仅取 token +
     * user.id + user.username。字段缺失回退空串（[contentOrNull]），保证宽容。
     */
    private fun parseAuthResult(json: String): AuthResult {
        val obj = Json.parseToJsonElement(json).jsonObject
        val userObj = obj["user"]?.jsonObject
        return AuthResult(
            token = obj["token"]?.jsonPrimitive?.contentOrNull ?: "",
            user = AuthUser(
                id = userObj?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
                username = userObj?.get("username")?.jsonPrimitive?.contentOrNull ?: ""
            )
        )
    }

    /**
     * 从失败响应体读 `{ "error": "..." }` 文本。后端错误统一此结构。
     * 解析失败或无 error 字段时返回 null，由调用方兜底。
     */
    private suspend fun readErrorBody(response: HttpResponse): String? = try {
        val body: String = response.body()
        val obj = Json.parseToJsonElement(body).jsonObject
        obj["error"]?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

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
     * V7：POST /api/media/rename — 重命名媒体文件。
     */
    suspend fun renameMedia(mediaId: String, filename: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/rename") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("media_id", JsonPrimitive(mediaId))
                    put("filename", JsonPrimitive(filename))
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "renameMedia FAILED: ${e::class.simpleName} ${e.message}")
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
     * 去重扩展参数同样经 query 传递（body 是 raw bytes，无法放 JSON）：
     *   - [sha256]：内容指纹。后端按 (user_id, sha256) 查库，命中则秒传不落盘，
     *     直接返回既有 media_id。这正是服务端权威去重——即便本端 Sha256Dedup 未命中，
     *     只要云端已有同内容即可省去落盘。留空则后端自行实测 sha256 落库（不去重查询）。
     *   - [clientId]：客户端幂等键，原样入库供多端冲突排查，留空不传。
     *   - [takenAt]：内容拍摄时间（ms），>0 时透传，0 表未知。
     *
     * @param fileData 文件字节
     * @param filename 原始文件名（用于后端取扩展名与 metadata sidecar）
     * @param isLivePhoto 是否为 Live Photo（通过 query param 传递）
     * @param sha256 内容指纹（hex），非空时透传供后端秒传去重
     * @param clientId 客户端幂等键，非空时透传
     * @param takenAt 拍摄时间 ms，>0 时透传
     */
    suspend fun uploadMedia(
        fileData: ByteArray,
        filename: String,
        isLivePhoto: Boolean = false,
        sha256: String = "",
        clientId: String = "",
        takenAt: Long = 0L
    ): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/upload") {
                parameter("filename", filename)
                if (isLivePhoto) parameter("is_live_photo", "true")
                if (sha256.isNotEmpty()) parameter("sha256", sha256)
                if (clientId.isNotEmpty()) parameter("client_id", clientId)
                if (takenAt > 0L) parameter("taken_at", takenAt.toString())
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

    // ---- 相册 API ----

    /**
     * 相册数据模型（前端用，commonMain 安全）。
     */
    data class Album(
        val id: String,
        val name: String,
        val coverMediaId: String? = null,
        val mediaCount: Int = 0
    )

    /**
     * 相册详情（含 media_ids 列表，用于详情页加载相册内媒体）。
     */
    data class AlbumDetail(
        val id: String,
        val name: String,
        val mediaIds: List<String>,
        val createdAt: Long = 0L
    )

    /**
     * 创建相册。
     *
     * POST /api/media/album  {"name":"xxx"}
     * 后端返回 {"id":"...","name":"..."}。
     */
    suspend fun createAlbum(name: String): Album? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("name", name) })
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                Album(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: name
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "createAlbum FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 获取相册列表。
     *
     * GET /api/media/albums → 后端返回 {"albums":[{"id","name","media_ids",...}]}
     */
    suspend fun getAlbums(): List<Album> {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/albums")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["albums"]?.jsonArray ?: JsonArray(emptyList())
                arr.map { item ->
                    val o = item.jsonObject
                    val mediaIds = o["media_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    Album(
                        id = o["id"]?.jsonPrimitive?.content ?: "",
                        name = o["name"]?.jsonPrimitive?.content ?: "",
                        coverMediaId = mediaIds.firstOrNull(),
                        mediaCount = mediaIds.size
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbums FAILED: ${e::class.simpleName} ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取相册详情（含 media_ids 列表）。
     *
     * GET /api/media/album/{id} → 后端返回 {"id","name","media_ids":[...],"created_at"}
     *
     * @return 相册详情对象；失败返回 null
     */
    suspend fun getAlbumDetail(albumId: String): AlbumDetail? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/$albumId")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val o = Json.parseToJsonElement(body).jsonObject
                val mediaIds = o["media_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                AlbumDetail(
                    id = o["id"]?.jsonPrimitive?.content ?: "",
                    name = o["name"]?.jsonPrimitive?.content ?: "",
                    mediaIds = mediaIds,
                    createdAt = o["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumDetail FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 将媒体加入相册。
     *
     * POST /api/media/album/add {"album_id":"x","media_id":"y"}
     */
    suspend fun addMediaToAlbum(albumId: String, mediaId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/add") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_id", mediaId)
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "addMediaToAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V7：POST /api/media/album/remove — 将媒体从相册中移除。
     */
    suspend fun removeMediaFromAlbum(albumId: String, mediaId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/remove") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_id", mediaId)
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "removeMediaFromAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V7：POST /api/media/album/batch-add — 批量添加媒体到相册。
     * 返回实际添加数量（已存在的跳过）。
     */
    suspend fun batchAddMediaToAlbum(albumId: String, mediaIds: List<String>): Int? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/batch-add") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_ids", JsonArray(mediaIds.map { JsonPrimitive(it) }))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["added_count"]?.jsonPrimitive?.intOrNull
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "batchAddMediaToAlbum FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }
    suspend fun setAlbumCover(albumId: String, mediaId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/cover") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_id", mediaId)
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "setAlbumCover FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * 删除相册。
     *
     * DELETE /api/media/album/{id}
     */
    suspend fun deleteAlbum(albumId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.delete("${backendBaseUrl()}/api/media/album/$albumId")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "deleteAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    // ============================================================
    // V7 §2.3 共享相册 API
    // ============================================================

    /**
     * 邀请用户共享相册。
     * @param albumId 相册 ID
     * @param username 被邀请用户的用户名
     */
    suspend fun shareAlbum(albumId: String, username: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/share") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("username", username)
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "shareAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /** 撤销共享。 */
    suspend fun unshareAlbum(albumId: String, username: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/unshare") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("username", username)
                })
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "unshareAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /** 获取被共享给当前用户的相册列表。 */
    suspend fun getSharedAlbums(): List<Album> {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/albums/shared")
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                val obj = Json.parseToJsonElement(respBody).jsonObject
                val arr = obj["albums"] as? JsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    Album(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        coverMediaId = o["cover_media_id"]?.jsonPrimitive?.contentOrNull,
                        mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getSharedAlbums FAILED: ${e::class.simpleName} ${e.message}")
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

    // ---- 增量同步 / 设备注册 ----

    /**
     * 单条增量变更项，对齐后端 [gateway.syncChangeItem]。
     *
     * 在 [MediaMetadata] 基础字段之外携带同步语义字段：
     * - [deleted]：墓碑标记，true 表示该媒体已被软删，客户端应本地移除。
     * - [sha256]：内容指纹，用于上传去重（与 [com.wgt.common.util.sha256] 比对）。
     * - [updatedAt]：毫秒，作为下次 [since] 游标推进依据。
     *
     * [toMediaMetadata] 把非删除项转成 UI 网格直接消费的标准元数据。
     */
    data class SyncChange(
        val id: String,
        val filename: String,
        val type: MediaType,
        val size: Long,
        val mimeType: String,
        val createdAt: Long,
        val updatedAt: Long,
        val width: Int,
        val height: Int,
        val deleted: Boolean,
        val sha256: String,
        val isLivePhoto: Boolean = false,
        val livePhotoVideoId: String = ""
    ) {
        /** 转为 UI 通用元数据（删除项不应调用，调用方需自行按 [deleted] 过滤）。 */
        fun toMediaMetadata(): MediaMetadata = MediaMetadata(
            id = id,
            filename = filename,
            type = type,
            size = size,
            mime_type = mimeType,
            created_at = createdAt,
            updated_at = updatedAt,
            is_live_photo = isLivePhoto,
            live_photo_video_id = livePhotoVideoId,
            width = width,
            height = height
        )
    }

    /**
     * /api/sync/changes 单页响应。客户端用 [nextCursor]+[nextCursorId] 组装复合游标续拉，
     * [hasMore] 为 false 即本次增量同步完成。空页且 hasMore=false 时游标回显原 since。
     *
     * V6 §2.7 复合游标：后端响应含 next_cursor（毫秒）+ next_cursor_id（末条 id），
     * 客户端下次 since 传 "ms|id" 使后端走 (updated_at, id) 复合严格大于分支，
     * 消除同时间戳边界的重/漏。
     */
    data class SyncChangesResult(
        val changes: List<SyncChange>,
        val nextCursor: Long,
        val nextCursorId: String = "",
        val hasMore: Boolean
    )

    /**
     * /api/sync/usage 响应：当前用户未软删媒体的存储总量与文件数。
     */
    data class SyncUsage(val totalBytes: Long, val fileCount: Int)

    /**
     * 增量拉取媒体变更。
     *
     * GET /api/sync/changes?since=<cursor>，返回 updated_at 严格晚于 since 的全部 media 行
     * （含软删墓碑）。since 为复合游标字符串 "ms|id"（V6 §2.7 复合游标），或纯毫秒数字
     * （向下兼容，后端 sinceID 为空退化纯时间戳）。since="" 或 "0" 表示从头全量拉取。
     * 客户端循环用本页 [SyncChangesResult.nextCursor]+[nextCursorId] 组装 "ms|id" 作下次 since，
     * 直至 [hasMore] = false。
     *
     * @param since 游标字符串（"ms|id" 或纯毫秒或空串）；空串/"0" 表示首拉
     * @param pageSize 单页大小（后端夹到 [1,500]，默认 100）
     * @return 该页变更 + 下一游标 + 是否还有更多；网络/HTTP 错误返回 null，调用方按
     *         "同步失败、保留旧游标下次重试" 处理（不前进游标避免丢增量）。
     */
    suspend fun getSyncChanges(since: String, pageSize: Int = 100): SyncChangesResult? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/sync/changes") {
                parameter("since", since)
                parameter("page_size", pageSize)
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val parsed = parseSyncChanges(body)
                logger.info("MediaService", "getSyncChanges since=$since count=${parsed.size} hasMore")
                val obj = Json.parseToJsonElement(body).jsonObject
                SyncChangesResult(
                    changes = parsed,
                    nextCursor = obj["next_cursor"]?.jsonPrimitive?.longOrNull ?: 0L,
                    nextCursorId = obj["next_cursor_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    hasMore = obj["has_more"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            } else {
                logger.info("MediaService", "getSyncChanges status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getSyncChanges FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** GET /api/sync/usage。失败返回 null，调用方可不展示用量或回退本地计数。 */
    suspend fun getSyncUsage(): SyncUsage? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/sync/usage")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                SyncUsage(
                    totalBytes = obj["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    fileCount = obj["file_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getSyncUsage FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：GET /api/media/storage-stats → 按类型分组的存储统计。
     *
     * 返回 image/video/live_photo 各自数量+字节数，以及总计。
     * 需要登录（Authorization header 自动注入）。
     */
    data class StorageStats(
        val imageCount: Int,
        val imageBytes: Long,
        val videoCount: Int,
        val videoBytes: Long,
        val livePhotoCount: Int,
        val livePhotoBytes: Long,
        val totalCount: Int,
        val totalBytes: Long
    ) {
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /** V7：重复文件检测结果 */
    data class DuplicateMedia(
        val id: String,
        val filename: String,
        val size: Long,
        val sha256: String,
        val type: String,
        val createdAt: Long
    )
    data class DuplicateGroup(
        val sha256: String,
        val count: Int,
        val size: Long,
        val media: List<DuplicateMedia>
    )
    data class DuplicateResult(
        val groups: List<DuplicateGroup>,
        val groupCount: Int,
        val totalDupes: Int,
        val wastedBytes: Long
    ) {
        val wastedMB: Double get() = wastedBytes.toDouble() / (1024.0 * 1024.0)
    }

    suspend fun getStorageStats(): StorageStats? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-stats")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val byType = obj["by_type"]?.jsonObject
                fun typeStats(key: String): Pair<Int, Long> {
                    val t = byType?.get(key)?.jsonObject
                    return (t?.get("count")?.jsonPrimitive?.intOrNull ?: 0) to
                        (t?.get("total_bytes")?.jsonPrimitive?.longOrNull ?: 0L)
                }
                val (img, imgB) = typeStats("image")
                val (vid, vidB) = typeStats("video")
                val (lp, lpB) = typeStats("live_photo")
                StorageStats(
                    imageCount = img, imageBytes = imgB,
                    videoCount = vid, videoBytes = vidB,
                    livePhotoCount = lp, livePhotoBytes = lpB,
                    totalCount = obj["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = obj["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageStats FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：GET /api/media/duplicates → 重复文件检测（按 SHA256 分组）。
     */
    suspend fun getDuplicates(): DuplicateResult? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/duplicates") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = response.bodyAsText().let { Json.parseToJsonElement(it).jsonObject }
                val groups = obj["groups"]?.jsonArray?.map { g ->
                    val go = g.jsonObject
                    val media = go["media"]?.jsonArray?.map { m ->
                        val mo = m.jsonObject
                        DuplicateMedia(
                            id = mo["id"]?.jsonPrimitive?.content ?: "",
                            filename = mo["filename"]?.jsonPrimitive?.content ?: "",
                            size = mo["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            sha256 = mo["sha256"]?.jsonPrimitive?.content ?: "",
                            type = mo["type"]?.jsonPrimitive?.content ?: "",
                            createdAt = mo["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    } ?: emptyList()
                    DuplicateGroup(
                        sha256 = go["sha256"]?.jsonPrimitive?.content ?: "",
                        count = go["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        size = go["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        media = media
                    )
                } ?: emptyList()
                DuplicateResult(
                    groups = groups,
                    groupCount = obj["group_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalDupes = obj["total_dupes"]?.jsonPrimitive?.intOrNull ?: 0,
                    wastedBytes = obj["wasted_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getDuplicates FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：GET /api/rn/manifest → RN bundle 版本信息。
     * 用于设置页"检查更新"功能。
     */
    suspend fun getRNManifest(): RNManifest? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/rn/manifest") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val bundles = obj["bundles"]?.jsonArray?.map { b ->
                    val bo = b.jsonObject
                    RNBundleInfo(
                        name = bo["name"]?.jsonPrimitive?.content ?: "",
                        version = bo["version"]?.jsonPrimitive?.content ?: "",
                        description = bo["description"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()
                RNManifest(bundles)
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getRNManifest FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    data class RNBundleInfo(
        val name: String,
        val version: String,
        val description: String
    )
    data class RNManifest(
        val bundles: List<RNBundleInfo>
    )

    /**
     * V7：GET /api/media/summary → 媒体库综合摘要。
     */
    suspend fun getMediaSummary(): MediaSummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/summary") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaSummary(
                    totalCount = obj["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = obj["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    imageCount = obj["image_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    videoCount = obj["video_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    liveCount = obj["live_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    earliestTs = obj["earliest_ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                    latestTs = obj["latest_ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                    favoriteCount = obj["favorite_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    albumCount = obj["album_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    shareCount = obj["share_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSummary FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    data class MediaSummary(
        val totalCount: Int,
        val totalBytes: Long,
        val imageCount: Int,
        val videoCount: Int,
        val liveCount: Int,
        val earliestTs: Long,
        val latestTs: Long,
        val favoriteCount: Int = 0,
        val albumCount: Int = 0,
        val shareCount: Int = 0
    ) {
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /**
     * V7：GET /api/healthz — 获取后端版本信息。
     */
    suspend fun getBackendInfo(): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/healthz") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["version"]?.jsonPrimitive?.contentOrNull
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getBackendInfo FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * POST /api/device/register {device_name, platform} → {device_id}。
     *
     * 为当前登录用户登记一台设备，返回后端分配的 device_id（uuid）。同一用户可多设备，
     * 不去重。客户端应持久化 device_id，避免每次启动重复注册。
     *
     * @return 新 device_id；未登录/未配置/网络错误返回 null。
     */
    suspend fun registerDevice(deviceName: String, platform: String): String? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/device/register") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("device_name", deviceName)
                    put("platform", platform)
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["device_id"]?.jsonPrimitive?.contentOrNull
            } else {
                logger.info("MediaService", "registerDevice status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "registerDevice FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：GET /api/device/list — 返回当前用户名下所有设备。
     */
    suspend fun listDevices(): List<DeviceInfo>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/device/list") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val arr = obj["devices"]?.jsonArray ?: return emptyList()
                arr.mapNotNull { item ->
                    val o = item.jsonObject
                    DeviceInfo(
                        deviceId = o["device_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        deviceName = o["device_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        platform = o["platform"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAtMs = o["created_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "listDevices FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：POST /api/media/album/batch-remove — 批量从相册移除媒体。
     * 返回实际移除数量。
     */
    suspend fun batchRemoveMediaFromAlbum(albumId: String, mediaIds: List<String>): Int? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/batch-remove") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_ids", JsonArray(mediaIds.map { JsonPrimitive(it) }))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["removed_count"]?.jsonPrimitive?.intOrNull
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "batchRemoveMediaFromAlbum FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V7：设备信息 */
    data class DeviceInfo(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
        val createdAtMs: Long
    )

    /**
     * V7：GET /api/share/list — 列出当前用户的分享链接。
     */
    suspend fun listShares(): List<ShareInfo>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/share/list") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val arr = obj["shares"]?.jsonArray ?: return emptyList()
                arr.mapNotNull { item ->
                    val o = item.jsonObject
                    ShareInfo(
                        token = o["token"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        expiresAt = o["expires_at"]?.jsonPrimitive?.contentOrNull ?: "永久",
                        hasPassword = o["has_password"]?.jsonPrimitive?.booleanOrNull ?: false,
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "listShares FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V7：分享链接信息 */
    data class ShareInfo(
        val token: String,
        val url: String,
        val expiresAt: String,
        val hasPassword: Boolean,
        val createdAt: String
    )

    /**
     * V7：GET /api/media/search-suggestions?q=xxx — 搜索建议
     */
    suspend fun getSearchSuggestions(q: String): List<String>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/search-suggestions?q=${q.encodeURLQueryComponent()}") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["suggestions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getSearchSuggestions FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V7：GET /api/media/recent-activity — 最近活动
     */
    suspend fun getRecentActivity(): List<ActivityInfo>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/recent-activity") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["activities"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    ActivityInfo(
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        timestamp = o["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getRecentActivity FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V7：活动信息 */
    data class ActivityInfo(
        val type: String,
        val mediaId: String,
        val filename: String,
        val timestamp: Long,
        val detail: String
    )

    /**
     * V7：DELETE /api/share/{token} — 撤销分享链接。
     */
    suspend fun deleteShare(token: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.delete("${backendBaseUrl()}/api/share/$token") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "deleteShare FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/album/rename — 重命名相册。
     */
    suspend fun renameAlbum(albumId: String, newName: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("album_id", albumId)
                put("name", newName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/rename") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "renameAlbum FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/tag/add — 给媒体打标签。
     */
    suspend fun addMediaTag(mediaId: String, tagName: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("media_id", mediaId)
                put("tag_name", tagName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/add") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "addMediaTag FAILED: ${e.message}")
            false
        }
    }

    /** V8：POST /api/media/tag/remove — 移除标签。 */
    suspend fun removeMediaTag(mediaId: String, tagName: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("media_id", mediaId)
                put("tag_name", tagName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/remove") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "removeMediaTag FAILED: ${e.message}")
            false
        }
    }

    /** V8：GET /api/media/tag/list?media_id=xxx — 列出媒体标签。 */
    suspend fun listMediaTags(mediaId: String): List<String>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/list?media_id=$mediaId") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "listMediaTags FAILED: ${e.message}")
            null
        }
    }

    /** V8：POST /api/media/tag/batch-add — 批量打标签，返回成功数。 */
    suspend fun batchAddTag(mediaIds: List<String>, tagName: String): Int {
        if (mediaIds.isEmpty() || tagName.isBlank()) return 0
        return try {
            val body = buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
                put("tag_name", tagName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/batch-add") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tagged_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "batchAddTag FAILED: ${e.message}")
            0
        }
    }

    /** V8：GET /api/media/tag/autocomplete?q=xxx — 标签自动补全。 */
    suspend fun tagAutocomplete(query: String): List<String>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/autocomplete?q=${query.encodeURLQueryComponent()}") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["suggestions"]?.jsonArray?.map { it.jsonPrimitive.content }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "tagAutocomplete FAILED: ${e.message}")
            null
        }
    }

    /** V8：GET /api/media/upload-calendar — 按天统计上传量（最近30天）。 */
    suspend fun getUploadCalendar(): List<UploadDay>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/upload-calendar") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["days"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    UploadDay(
                        date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getUploadCalendar FAILED: ${e.message}")
            null
        }
    }

    /** V8：上传日（按天统计） */
    data class UploadDay(val date: String, val count: Int, val bytes: Long)

    /** V8：GET /api/media/orphan-check — 孤立文件检查。 */
    suspend fun orphanCheck(): OrphanCheckResult? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/orphan-check") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                OrphanCheckResult(
                    checked = o["checked"]?.jsonPrimitive?.intOrNull ?: 0,
                    orphanCount = o["orphan_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    orphans = o["orphans"]?.jsonArray?.mapNotNull { item ->
                        val inst = item.jsonObject
                        OrphanItem(
                            mediaId = inst["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            filename = inst["filename"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    } ?: emptyList()
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "orphanCheck FAILED: ${e.message}")
            null
        }
    }

    /** V8：孤立文件检查结果 */
    data class OrphanCheckResult(val checked: Int, val orphanCount: Int, val orphans: List<OrphanItem>)
    data class OrphanItem(val mediaId: String, val filename: String)

    /** V8：GET /api/media/file-types — 按 MIME 统计。 */
    suspend fun getFileTypes(): List<FileTypeStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/file-types") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["types"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    FileTypeStat(
                        mime = o["mime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getFileTypes FAILED: ${e.message}")
            null
        }
    }

    /** V8：文件类型统计 */
    data class FileTypeStat(val mime: String, val count: Int, val bytes: Long)

    /** V8：GET /api/media/extreme-media — 最老和最大媒体。 */
    suspend fun getExtremeMedia(): ExtremeMedia? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/extreme-media") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                fun parseItem(key: String): ExtremeItem? {
                    val item = o[key]?.jsonObject ?: return null
                    return ExtremeItem(
                        id = item["id"]?.jsonPrimitive?.contentOrNull ?: return null,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = item["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        createdAt = item["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                ExtremeMedia(oldest = parseItem("oldest"), largest = parseItem("largest"))
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getExtremeMedia FAILED: ${e.message}")
            null
        }
    }

    /** V8：极端媒体 */
    data class ExtremeMedia(val oldest: ExtremeItem?, val largest: ExtremeItem?)
    data class ExtremeItem(
        val id: String,
        val filename: String,
        val type: String,
        val size: Long,
        val createdAt: String
    )

    /** V8：GET /api/media/recent-uploads — 最近上传的媒体。 */
    suspend fun getRecentUploads(): List<RecentUpload>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/recent-uploads") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["items"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    RecentUpload(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getRecentUploads FAILED: ${e.message}")
            null
        }
    }

    /** V8：最近上传项 */
    data class RecentUpload(
        val id: String,
        val filename: String,
        val type: String,
        val size: Long,
        val createdAt: String
    )

    /** V8：GET /api/media/user-quota — 用户存储配额信息。 */
    suspend fun getUserQuota(): UserQuota? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/user-quota") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                UserQuota(
                    quotaBytes = o["quota_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    usedBytes = o["used_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    freeBytes = o["free_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    usagePercent = o["usage_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getUserQuota FAILED: ${e.message}")
            null
        }
    }

    /** V8：用户存储配额 */
    data class UserQuota(
        val quotaBytes: Long,
        val usedBytes: Long,
        val freeBytes: Long,
        val usagePercent: Double
    ) {
        val usedMB: Double get() = usedBytes.toDouble() / (1024.0 * 1024.0)
        val quotaGB: Double get() = quotaBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    /** V8：POST /api/media/tag/delete — 删除标签，返回删除数。 */
    suspend fun deleteTag(tagName: String): Int {
        return try {
            val body = buildJsonObject {
                put("tag_name", tagName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/delete") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["deleted_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "deleteTag FAILED: ${e.message}")
            0
        }
    }

    /** V8：GET /api/media/tag/stats — 标签统计。 */
    suspend fun getTagStats(): List<TagStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/stats") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagStat(
                        tag = o["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagStats FAILED: ${e.message}")
            null
        }
    }

    /** V8：标签统计项 */
    data class TagStat(val tag: String, val count: Int)

    /** V8：GET /api/media/tag/search?tag=xxx — 按标签搜索 media_id 列表。 */
    suspend fun searchByTag(tag: String): List<String>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/search?tag=${tag.encodeURLQueryComponent()}") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["media_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "searchByTag FAILED: ${e.message}")
            null
        }
    }

    /** V8：GET /api/media/tag/all — 列出所有标签。 */
    suspend fun listAllTags(): List<String>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/all") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "listAllTags FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/info/{id} — 返回单个媒体详情。
     */
    suspend fun getMediaInfo(mediaId: String): MediaInfo? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/info/$mediaId") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaInfo(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                    type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                    size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    mime = o["mime"]?.jsonPrimitive?.contentOrNull ?: "",
                    width = o["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    sha256 = o["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
                    createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: "",
                    takenAt = o["taken_at"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaInfo FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V8：媒体详情 */
    data class MediaInfo(
        val id: String,
        val filename: String,
        val type: String,
        val size: Long,
        val mime: String,
        val width: Int,
        val height: Int,
        val sha256: String,
        val createdAt: String,
        val takenAt: Long
    ) {
        val sizeKB: Double get() = size.toDouble() / 1024.0
        val sizeMB: Double get() = sizeKB / 1024.0
    }

    /**
     * V8：POST /api/media/batch-rename — 批量重命名，返回结果。
     */
     suspend fun batchRename(
         mediaIds: List<String>,
         pattern: String,
         startSeq: Int = 1
     ): BatchRenameResult? {
         return try {
             val body = buildJsonObject {
                 putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
                 put("pattern", pattern)
                 put("start_seq", startSeq)
             }.toString()
             val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-rename") {
                 header("Authorization", "Bearer ${getAuthToken()}")
                 contentType(ContentType.Application.Json)
                 setBody(body)
             }
             if (response.status == HttpStatusCode.OK) {
                 val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                 BatchRenameResult(
                     renamedCount = obj["renamed_count"]?.jsonPrimitive?.intOrNull ?: 0,
                     renamed = obj["renamed"]?.jsonArray?.mapNotNull { item ->
                         val o = item.jsonObject
                         BatchRenameItem(
                             mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                             filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: ""
                         )
                     } ?: emptyList(),
                     failed = obj["failed"]?.jsonArray?.mapNotNull { item ->
                         val o = item.jsonObject
                         BatchRenameFailure(
                             id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                             reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                         )
                     } ?: emptyList()
                 )
             } else null
         } catch (e: Exception) {
             logger.error("MediaService", "batchRename FAILED: ${e::class.simpleName} ${e.message}")
             null
         }
     }

     /** V8：批量重命名结果 */
     data class BatchRenameResult(
         val renamedCount: Int,
         val renamed: List<BatchRenameItem>,
         val failed: List<BatchRenameFailure>
     )
     data class BatchRenameItem(val mediaId: String, val filename: String)
     data class BatchRenameFailure(val id: String, val reason: String)

     /**
      * V7：GET /api/media/storage-trend — 存储增长趋势
      */
     suspend fun getStorageTrend(): List<TrendPoint>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-trend") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["trends"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TrendPoint(
                        month = o["month"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        addedCount = o["added_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        addedBytes = o["added_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        cumBytes = o["cum_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageTrend FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V7：存储趋势数据点 */
    data class TrendPoint(
        val month: String,
        val addedCount: Int,
        val addedBytes: Long,
        val cumBytes: Long
    ) {
        val cumMB: Double get() = cumBytes.toDouble() / (1024.0 * 1024.0)
        val addedMB: Double get() = addedBytes.toDouble() / (1024.0 * 1024.0)
    }

    // ---- 解析 ----

    /**
     * parseMediaType converts the backend type field to [MediaType].
     *
     * The backend serialises the protobuf enum as an integer (0=IMAGE, 1=LIVE_PHOTO,
     * 2=VIDEO) via standard `json.Marshal`. Older code paths may also omit the field
     * entirely (IMAGE is the zero value with `omitempty`), in which case we default
     * to IMAGE.
     */
    private fun parseMediaType(raw: String?): MediaType = when (raw?.trim()?.uppercase()) {
        null, "", "0", "IMAGE" -> MediaType.IMAGE
        "1", "LIVE_PHOTO" -> MediaType.LIVE_PHOTO
        "2", "VIDEO" -> MediaType.VIDEO
        else -> MediaType.IMAGE // unknown types fall back to image
    }

    private fun parseMediaList(json: String): List<MediaMetadata> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["media_list"]?.jsonArray?.map { item ->
            val m = item.jsonObject
            MediaMetadata(
                id = m["id"]?.jsonPrimitive?.content ?: "",
                filename = m["filename"]?.jsonPrimitive?.content ?: "",
                type = parseMediaType(m["type"]?.jsonPrimitive?.content),
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

    /**
     * 解析 /api/sync/changes 的 `changes` 数组为 [SyncChange] 列表。
     *
     * 与 [parseMediaList] 同口径运行时 JSON 操作；额外取 `deleted` / `sha256` 同步字段，
     * 缺失时回退安全默认（deleted=false、sha256=""）。`taken_at`/`client_id` 等暂不消费，
     * 忽略（[Json] 已配置 ignoreUnknownKeys）。
     */
    private fun parseSyncChanges(json: String): List<SyncChange> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["changes"]?.jsonArray?.map { item ->
            val m = item.jsonObject
            SyncChange(
                id = m["id"]?.jsonPrimitive?.content ?: "",
                filename = m["filename"]?.jsonPrimitive?.content ?: "",
                type = parseMediaType(m["type"]?.jsonPrimitive?.content),
                size = m["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                mimeType = m["mime_type"]?.jsonPrimitive?.content ?: "",
                createdAt = m["created_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                updatedAt = m["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                width = m["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = m["height"]?.jsonPrimitive?.intOrNull ?: 0,
                deleted = m["deleted"]?.jsonPrimitive?.booleanOrNull ?: false,
                sha256 = m["sha256"]?.jsonPrimitive?.content ?: "",
                isLivePhoto = m["is_live_photo"]?.jsonPrimitive?.booleanOrNull ?: false,
                livePhotoVideoId = m["live_photo_video_id"]?.jsonPrimitive?.content ?: ""
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

    // ---- V7 §3.2 RN bundle 下载辅助 ----

    /**
     * 公开后端基址（V7 §3.2），供 RnBundleDownloader 拼接 URL。
     */
    fun rnBackendBaseUrl(): String = backendBaseUrl()

    /**
     * 通用 GET 请求返回原始 JSON 字符串（V7 §3.2），供 RN manifest 等非媒体端点使用。
     * 失败返回 null。
     */
    suspend fun getRawJson(url: String): String? {
        return try {
            val response: HttpResponse = jsonClient.get(url)
            if (response.status == HttpStatusCode.OK) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通用 GET 请求返回原始字节（V7 §3.2），供 RN bundle 下载使用。
     * 失败返回 null。
     */
    suspend fun getRawBytes(url: String): ByteArray? {
        return try {
            val response: HttpResponse = jsonClient.get(url)
            if (response.status == HttpStatusCode.OK) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    // ---- V7 §1.1 回收站 API ----

    /** 回收站条目（已软删的 media 元数据）。 */
    data class TrashItem(
        val id: String,
        val filename: String,
        val type: String,
        val size: Long,
        val updatedAt: Long
    )

    /** GET /api/media/trash — 返回回收站列表。失败返回空列表。 */
    suspend fun getTrash(): List<TrashItem> {
        return try {
            val response: HttpResponse = jsonClient.get("${rnBackendBaseUrl()}/api/media/trash")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["items"] as? JsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    TrashItem(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "IMAGE",
                        size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        updatedAt = o["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            logger.error("MediaService", "getTrash failed: ${e.message}")
            emptyList()
        }
    }

    /** POST /api/media/restore — 恢复指定 media。返回成功数。 */
    suspend fun restoreMedia(mediaIds: List<String>): Int {
        if (mediaIds.isEmpty()) return 0
        return try {
            val body = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
            })
            val response: HttpResponse = jsonClient.post("${rnBackendBaseUrl()}/api/media/restore") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                val obj = Json.parseToJsonElement(respBody).jsonObject
                obj["restored"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "restoreMedia failed: ${e.message}")
            0
        }
    }

    /** POST /api/media/purge — 彻底删除。返回成功数。 */
    suspend fun purgeMedia(mediaIds: List<String>): Int {
        if (mediaIds.isEmpty()) return 0
        return try {
            val body = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
            })
            val response: HttpResponse = jsonClient.post("${rnBackendBaseUrl()}/api/media/purge") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                val obj = Json.parseToJsonElement(respBody).jsonObject
                obj["purged"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "purgeMedia failed: ${e.message}")
            0
        }
    }

    /** V8：POST /api/media/empty-trash — 一键清空回收站，返回删除数量。 */
    suspend fun emptyTrash(): Int {
        return try {
            val response: HttpResponse = jsonClient.post("${rnBackendBaseUrl()}/api/media/empty-trash") {
                contentType(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["purged_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "emptyTrash failed: ${e.message}")
            0
        }
    }

    // ---- V7 §1.2 分享链接 API ----

    /** 分享链接信息。 */
    data class ShareLink(
        val token: String,
        val url: String,
        val expiresAt: Long,
        val mediaCount: Int
    )

    /** POST /api/share/create — 创建分享链接。返回 token + url。 */
    suspend fun createShareLink(
        mediaIds: List<String>,
        expiresInHours: Int = 24,
        password: String? = null
    ): ShareLink? {
        if (mediaIds.isEmpty()) return null
        return try {
            val body = buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
                put("expires_in_hours", expiresInHours)
                if (password != null) put("password", password)
            }
            val response: HttpResponse = jsonClient.post("${rnBackendBaseUrl()}/api/share/create") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                val obj = Json.parseToJsonElement(respBody).jsonObject
                ShareLink(
                    token = obj["token"]?.jsonPrimitive?.contentOrNull ?: return null,
                    url = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: "${rnBackendBaseUrl()}/s/${obj["token"]?.jsonPrimitive?.contentOrNull}",
                    expiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                    mediaCount = obj["media_count"]?.jsonPrimitive?.intOrNull ?: mediaIds.size
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "createShareLink failed: ${e.message}")
            null
        }
    }

    /** DELETE /api/share/{token} — 撤销分享链接。 */
    suspend fun revokeShareLink(token: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.delete("${rnBackendBaseUrl()}/api/share/$token")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "revokeShareLink failed: ${e.message}")
            false
        }
    }
}
