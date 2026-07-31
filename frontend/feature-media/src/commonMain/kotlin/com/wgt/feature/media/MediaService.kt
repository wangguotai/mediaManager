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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
     * V8：POST /api/media/rotate — 旋转媒体（顺时针 90° 步进）。
     *
     * 后端仅持久化旋转标记（media.orientation 列，EXIF orientation 语义：
     * 0/90/180/270），不改底层文件；前端按 orientation 渲染显示旋转。
     * 故调用成功后需刷新列表以拿到新 orientation 重新渲染。
     *
     * @param mediaId 目标媒体 ID
     * @param rotation 旋转角度，后端仅接受 0/90/180/270，非法值返回 400
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun rotateMedia(mediaId: String, rotation: Int): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/rotate") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_id", JsonPrimitive(mediaId))
                    put("rotation", JsonPrimitive(rotation))
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "rotateMedia id=$mediaId rotation=$rotation status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "rotateMedia FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/batch-rotate — 批量旋转媒体（顺时针 90° 步进）。
     *
     * 与 [rotateMedia] 同语义，但一次提交多个 media_id；后端仅持久化各自旋转标记
     * （media.orientation），不改底层文件。调用成功后需刷新列表以拿到新 orientation。
     *
     * @param mediaIds 目标媒体 ID 列表
     * @param rotation 旋转角度，后端仅接受 0/90/180/270，非法值返回 400
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun batchRotateMedia(mediaIds: List<String>, rotation: Int): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-rotate") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_ids", Json.encodeToJsonElement(mediaIds))
                    put("rotation", JsonPrimitive(rotation))
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info(
                "MediaService",
                "batchRotateMedia count=${mediaIds.size} rotation=$rotation status=${response.status}"
            )
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "batchRotateMedia FAILED: ${e::class.simpleName} ${e.message}")
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

    // ---- 高级搜索 ----

    /**
     * V8：高级多条件搜索 —— GET /api/media/advanced-search。
     *
     * 与 [getMediaList] 走的 `/api/media/list` 不同，本端点由后端 `AdvancedSearchMedia` 直接
     * 序列化 `storage.Media` 结构体，因而 JSON 字段口径**不同于** [parseMediaList]：
     * - `mime` 而非 `mime_type`；
     * - `created_at` / `updated_at` 是 Go time.Time 序列化的 RFC3339 字符串，而非毫秒数；
     * - 多 `total` 字段；
     * - 顶层 `media` 数组（非 `media_list`）。
     * 故用专属 [parseAdvancedSearchList] 解析，不复用 [parseMediaList]。
     *
     * 日期参数：用户在 UI 输入 `YYYY-MM-DD`，本方法转成 RFC3339（`date_from` 取当天 00:00 UTC，
     * `date_to` 取当天 23:59:59 UTC），让后端 SQL 字符串比较正确覆盖全天的 created_at。
     *
     * @param opts 条件 map，可能键：type / mime / min_size / max_size / date_from / date_to / tag / limit；
     *             值均为字符串，空串/缺失表示不施加该条件。日期形如 `YYYY-MM-DD`。
     * @return 命中的媒体列表；HTTP 非 200 或网络异常返回 null（调用方据空态提示）。
     */
    suspend fun advancedSearch(opts: Map<String, String>): List<MediaMetadata>? {
        // 预处理：日期补全为 RFC3339，便于后端字符串比较覆盖整天。
        val params = LinkedHashMap<String, String>()
        for ((k, v) in opts) {
            val tv = v.trim()
            if (tv.isEmpty()) continue
            when (k) {
                "date_from" -> params[k] = rfc3339StartOfDay(tv)
                "date_to" -> params[k] = rfc3339EndOfDay(tv)
                else -> params[k] = tv
            }
        }
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/advanced-search") {
                params.forEach { (k, v) -> parameter(k, v) }
                // 未显式给 limit 时给 100，与后端默认一致；调用方传 smaller 也透传。
                if (!params.containsKey("limit")) parameter("limit", "100")
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val parsed = parseAdvancedSearchList(body)
                logger.info(
                    "MediaService",
                    "advancedSearch params=$params status=${response.status} parsed=${parsed.size}"
                )
                parsed
            } else {
                logger.info("MediaService", "advancedSearch params=$params status=${response.status} (non-OK)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "advancedSearch FAILED params=$params: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 把 `YYYY-MM-DD` 补成 RFC3339 当天起始：`YYYY-MM-DDT00:00:00Z`。
     * 非预期格式原样返回，交由后端兜底（不抛错）。
     */
    private fun rfc3339StartOfDay(date: String): String {
        if (date.length == 10 && date[4] == '-' && date[7] == '-') {
            return "${date}T00:00:00Z"
        }
        return date
    }

    /**
     * 把 `YYYY-MM-DD` 补成 RFC3339 当天结尾：`YYYY-MM-DDT23:59:59Z`，
     * 使后端 `created_at <= ?` 字符串比较覆盖当天全部记录。
     */
    private fun rfc3339EndOfDay(date: String): String {
        if (date.length == 10 && date[4] == '-' && date[7] == '-') {
            return "${date}T23:59:59Z"
        }
        return date
    }

    /**
     * 解析 `/api/media/advanced-search` 响应为 [MediaMetadata] 列表。
     *
     * 与 [parseMediaList] 区别：
     * - 顶层 `media` 数组（非 `media_list`）；
     * - MIME 取 `mime` 字段；
     * - `created_at` / `updated_at` 为 RFC3339 字符串 → 转为 epoch 毫秒存入 [MediaMetadata]，
     *   解析失败回退 0L（与既有 [parseMediaList] 缺字段时的回退一致）。
     */
    private fun parseAdvancedSearchList(json: String): List<MediaMetadata> {
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["media"]?.jsonArray?.map { item ->
            val m = item.jsonObject
            MediaMetadata(
                id = m["id"]?.jsonPrimitive?.content ?: "",
                filename = m["filename"]?.jsonPrimitive?.content ?: "",
                type = parseMediaType(m["type"]?.jsonPrimitive?.content),
                size = m["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                mime_type = m["mime"]?.jsonPrimitive?.contentOrNull
                    ?: m["mime_type"]?.jsonPrimitive?.contentOrNull ?: "",
                created_at = rfc3339ToMillis(m["created_at"]?.jsonPrimitive?.contentOrNull),
                updated_at = rfc3339ToMillis(m["updated_at"]?.jsonPrimitive?.contentOrNull),
                is_live_photo = m["is_live_photo"]?.jsonPrimitive?.booleanOrNull
                    ?: (m["type"]?.jsonPrimitive?.contentOrNull?.equals("LIVE_PHOTO", ignoreCase = true) == true),
                live_photo_video_id = m["live_photo_video_id"]?.jsonPrimitive?.content ?: "",
                width = m["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = m["height"]?.jsonPrimitive?.intOrNull ?: 0
            )
        } ?: emptyList()
    }

    /**
     * 把 RFC3339 字符串解析为 epoch 毫秒。失败/空串返回 0L。
     * 用纯字符串切片手工解析，避免引入 kotlinx-datetime 依赖（feature-media 未引入）。
     * 支持形如 `2026-07-31T08:30:00Z` 与 `2026-07-31T08:30:00.123456789Z`。
     */
    private fun rfc3339ToMillis(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        // 仅取 `YYYY-MM-DDTHH:MM:SS` 部分（前 19 字符），忽略秒以下精度与时区偏移。
        if (s.length < 19) return 0L
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()
            val hour = s.substring(11, 13).toInt()
            val minute = s.substring(14, 16).toInt()
            val second = s.substring(17, 19).toInt()
            // 校验通过则按民用历转 epoch 毫秒（UTC 假定；后端 timeToVal 已 UTC 落库，吻合）。
            civilToEpochMillis(year, month, day, hour, minute, second)
        } catch (e: Exception) {
            0L
        }
    }

    /** 公历 → epoch 毫秒（UTC），无外部依赖。范围 1970 起算。 */
    private fun civilToEpochMillis(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        // Howard Hinnant 的 days_from_civil 算法（无损整数转换，支持任意公历日）。
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era.toLong() * 146097L + doe - 719468L
        return days * 86400_000L + (h.toLong() * 3600L + mi.toLong() * 60L + s.toLong()) * 1000L
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

    /**
     * V9：一键切换相册的公开共享状态。
     *
     * 与 [shareAlbum]（按用户名邀请特定人）不同，这是相册级的公开共享开关：
     * 已共享 → 取消共享；未共享 → 开启共享并返回可分享的链接。后端端点
     * `POST /api/media/album/share-toggle { album_id }` 幂等地翻转相册的
     * `is_shared` 标记并返回新状态与 share_url（首次共享时生成）。
     *
     * @return `Pair(shared, shareUrl)`：
     *   - `shared` 切换后的共享状态（true=已共享，false=已取消）
     *   - `shareUrl` 共享链接（仅 shared=true 时非空）；网络/HTTP 错误时返回 null
     */
    suspend fun toggleAlbumShare(albumId: String): Pair<Boolean, String?>? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/share-toggle") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("album_id", albumId) })
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val shared = obj["shared"]?.jsonPrimitive?.booleanOrNull ?: false
                val url = obj["share_url"]?.jsonPrimitive?.contentOrNull
                logger.info("MediaService", "toggleAlbumShare id=$albumId shared=$shared url=$url")
                Pair(shared, url)
            } else {
                logger.info("MediaService", "toggleAlbumShare id=$albumId failed status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "toggleAlbumShare FAILED id=$albumId: ${e::class.simpleName} ${e.message}")
            null
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
     * V8：所有相册摘要项（GET /api/media/album/all-summary 的单条）。
     *
     * 与后端 [handleAlbumAllSummary] 返回字段对齐：
     * id / name / media_count / cover_media_id / created_at（unix 秒）。
     * 与 [Album] 的区别在于带 [createdAt]，用于"相册概览"卡片按创建时间排序展示。
     */
    data class AlbumSummaryItem(
        val id: String,
        val name: String,
        val mediaCount: Int = 0,
        val coverMediaId: String? = null,
        val createdAt: Long = 0L
    )

    /**
     * V8：GET /api/media/album/all-summary — 获取当前用户所有相册的摘要列表。
     *
     * 后端返回 `{"albums":[{id,name,media_count,cover_media_id,created_at}], "total":N}`。
     * 每条仅含计数与封面 ID（不含 media_ids 列表），比 [getAlbums] 更轻，
     * "我的"Tab 的"相册概览"卡片用它做概览展示。
     *
     * @return 相册摘要列表；后端不可用/出错时返回空列表（卡片据此显示空态）
     */
    suspend fun getAllAlbumsSummary(): List<AlbumSummaryItem> {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/all-summary")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["albums"]?.jsonArray ?: JsonArray(emptyList())
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    AlbumSummaryItem(
                        id = id,
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        coverMediaId = o["cover_media_id"]?.jsonPrimitive?.contentOrNull,
                        createdAt = o["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAllAlbumsSummary FAILED: ${e::class.simpleName} ${e.message}")
            emptyList()
        }
    }

    /**
     * 相册排单项——GET /api/media/album/count-ranking 返回的排行榜中的一条。
     *
     * 后端字段：album_id / name / count / cover_media_id（见 [handleAlbumCountRanking]）。
     * [count] 为该相册内的媒体项数，按降序排列；[coverMediaId] 可空（相册无封面时为 null）。
     */
    data class AlbumRankItem(
        val albumId: String,
        val name: String,
        val count: Int = 0,
        val coverMediaId: String? = null
    )

    /**
     * V9：GET /api/media/album/count-ranking — 获取按媒体项数降序排列的相册排行榜。
     *
     * 后端返回 `{"ranking":[{album_id,name,count,cover_media_id}], "total_albums":N}`。
     * ranking 已按 count 降序排好；本方法原样返回列表，"相册排行"卡片.take(5) 取前 5。
     *
     * 后端不可用/出错时返回 null（与 [getAllAlbumsSummary] 的 emptyList 语义不同——
     * 排行榜为空是合理的"暂无数据"状态，但网络失败应与"成功但空"区分，故用 null）。
     *
     * @return 排行榜列表（已按 count 降序），或 null（失败）
     */
    suspend fun getAlbumCountRanking(): List<AlbumRankItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/count-ranking")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["ranking"]?.jsonArray ?: JsonArray(emptyList())
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val albumId = o["album_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    AlbumRankItem(
                        albumId = albumId,
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        coverMediaId = o["cover_media_id"]?.jsonPrimitive?.contentOrNull
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumCountRanking FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V19：相册极值——stats-summary 返回的最多/最少相册单项。
     *
     * 后端字段：id / name / count（见 handleAlbumStatsSummary）。
     * [count] 为该相册内的媒体项数。
     */
    data class AlbumExtremum(
        val id: String,
        val name: String,
        val count: Int = 0
    )

    /**
     * V19：相册统计摘要——GET /api/media/album/stats-summary 返回的聚合数据。
     *
     * 后端返回 `{total_albums, total_media, avg_per_album, max_album{id,name,count}, min_album{id,name,count}}`。
     * [totalMedia] 是各相册媒体项数之和（同一媒体在多相册中重复计数，非去重，与
     * count-ranking/all-summary 的口径一致）。[maxAlbum]/[minAlbum] 为项数最多/最少的相册；
     * 单个相册时二者指向同一相册（正确行为）；无相册时为 null。
     */
    data class AlbumStatsSummary(
        val totalAlbums: Int = 0,
        val totalMedia: Int = 0,
        val avgPerAlbum: Double = 0.0,
        val maxAlbum: AlbumExtremum? = null,
        val minAlbum: AlbumExtremum? = null
    )

    /**
     * V19：GET /api/media/album/stats-summary — 获取相册统计摘要
     * （总相册数/总媒体项数/平均值/最多最少相册）。
     *
     * 后端返回 `{total_albums, total_media, avg_per_album, max_album{id,name,count}, min_album{id,name,count}}`。
     * max_album/min_album 在无相册时为 null（后端返回 null）。
     *
     * 后端不可用/出错时返回 null（与 [getAlbumCountRanking] 同语义——
     * 区分"成功但空"与"网络失败"，故用 null 表示失败）。
     *
     * @return 相册统计摘要，或 null（失败）
     */
    suspend fun getAlbumStatsSummary(): AlbumStatsSummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/stats-summary")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                AlbumStatsSummary(
                    totalAlbums = obj["total_albums"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalMedia = obj["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgPerAlbum = obj["avg_per_album"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    maxAlbum = parseAlbumExtremum(obj["max_album"]),
                    minAlbum = parseAlbumExtremum(obj["min_album"])
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumStatsSummary FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 解析 stats-summary 的 max_album/min_album 字段。后端在无相册时返回 null，
     * 此处返回 null；有相册时解析 id/name/count。
     */
    private fun parseAlbumExtremum(el: JsonElement?): AlbumExtremum? {
        if (el == null || el is JsonNull) return null
        val o = el.jsonObject
        return AlbumExtremum(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
        )
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
    data class DupReportMedia(
        val id: String,
        val filename: String,
        val size: Long,
        val sha256: String,
        val type: String,
        val createdAt: Long
    )
    data class DupReportGroup(
        val sha256: String,
        val count: Int,
        val size: Long,
        val media: List<DupReportMedia>
    )
    data class DuplicateResult(
        val groups: List<DupReportGroup>,
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
                        DupReportMedia(
                            id = mo["id"]?.jsonPrimitive?.content ?: "",
                            filename = mo["filename"]?.jsonPrimitive?.content ?: "",
                            size = mo["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            sha256 = mo["sha256"]?.jsonPrimitive?.content ?: "",
                            type = mo["type"]?.jsonPrimitive?.content ?: "",
                            createdAt = mo["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    } ?: emptyList()
                    DupReportGroup(
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
     * V22：仪表盘概览——前端组合已有多源数据（summary + uploadStreak + tagCloud），
     * 不调后端独立 dashboard 端点，避免新增后端工作量。六个关键字段对应 UI 2x3 网格：
     * 总媒体 / 收藏 / 相册 / 分享 / Streak / 标签数。
     *
     * 任一子源返回 null（后端未铺量/异常）时，对应字段回退 0，其余字段正常展示——
     * 与"我的"页其它卡片的 null-skip 策略互补：这里始终渲染（哪怕全 0），让用户
     * 看到完整六格骨架而非空白。
     *
     * 三路并发拉取（[coroutineScope] + async），减少串行 RTT；任一路失败不影响其余。
     */
    data class DashboardOverview(
        val totalMedia: Int = 0,
        val favoriteCount: Int = 0,
        val albumCount: Int = 0,
        val shareCount: Int = 0,
        val currentStreak: Int = 0,
        val tagCount: Int = 0
    )

    /**
     * V22：组合拉取仪表盘概览六指标。并发调 [getMediaSummary]/[getUploadStreak]/
     * [getTagCloudData]，任一失败回退 0 不抛。用 [coroutineScope] 保证三路 async
     * 在同一 scope 内并发等待，[awaitAll] 容错单路异常（try/catch 包裹单路提取）。
     */
    suspend fun getDashboardOverview(): DashboardOverview {
        return try {
            coroutineScope {
                val summaryDeferred = async { runCatching { getMediaSummary() }.getOrNull() }
                val streakDeferred = async { runCatching { getUploadStreak() }.getOrNull() }
                val tagDeferred = async { runCatching { getTagCloudData() }.getOrNull() }
                val summary = summaryDeferred.await()
                val streak = streakDeferred.await()
                val tags = tagDeferred.await()
                DashboardOverview(
                    totalMedia = summary?.totalCount ?: 0,
                    favoriteCount = summary?.favoriteCount ?: 0,
                    albumCount = summary?.albumCount ?: 0,
                    shareCount = summary?.shareCount ?: 0,
                    currentStreak = streak?.currentStreak ?: 0,
                    tagCount = tags?.size ?: 0
                )
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getDashboardOverview FAILED: ${e::class.simpleName} ${e.message}")
            DashboardOverview()
        }
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
     * V24：增强搜索建议单项。后端 searchSuggestion JSON 对齐：`{text, source}`。
     *
     * - [text] 建议文案（文件名去扩展名 / 标签名 / 相册名）。
     * - [source] 来源标识，后端返回 "filename" / "tag" / "album" 之一，
     *   前端据此渲染来源标签图标（📄文件名 / 🏷️标签 / 📷相册）。
     */
    data class EnhancedSuggestion(
        val text: String = "",
        val source: String = ""
    )

    /**
     * V24：GET /api/media/search-suggestions-enhanced?q=xxx — 多源增强搜索建议。
     *
     * 后端从文件名、标签、相册名三个来源各自做子串匹配，合并去重后返回带来源标记
     * 的建议列表（文件名最多 5、标签最多 3、相册名最多 3）。
     *
     * 响应：`{suggestions:[{text,source}], total}`。
     * 成功返回 [EnhancedSuggestion] 列表（可能为空）；失败/非 200 返回 null，
     * 调用方按 null 降级（隐藏建议区）。鉴权由 [jsonClient] defaultRequest 统一附加。
     */
    suspend fun getEnhancedSearchSuggestions(query: String): List<EnhancedSuggestion>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/search-suggestions-enhanced?q=${q.encodeURLQueryComponent()}") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["suggestions"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    EnhancedSuggestion(
                        text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        source = o["source"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getEnhancedSearchSuggestions status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getEnhancedSearchSuggestions FAILED: ${e::class.simpleName} ${e.message}")
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
     * 活动流条目 —— [getActivityFeed] 返回的单条记录。
     *
     * 与 V7 的 [ActivityInfo] 并存而非合并，因两者字段口径不同：活动流后端
     * （GET /api/media/activity-feed）额外带 [action]（用于 emoji 映射），且
     * [timestamp] 是字符串（后端 time.Time 的 RFC3339 串，或 epoch 数字串），
     * 而 [ActivityInfo].timestamp 是 [Long]。并发演进比强行抽象更稳。
     *
     * @param type 活动大类（upload/delete/share/...）—— 与 [action] 通常一致，
     *             保留二字以便后端区分"大类/具体动作"时仍可用。
     * @param action 具体动作关键字，用作 emoji 映射键（见活动流卡片 [MediaListScreen]）
     * @param detail 人类可读描述，原样展示
     * @param mediaId 关联媒体 ID，缺失为空串
     * @param timestamp 时间戳字符串 —— RFC3339（如 "2026-08-01T12:34:56Z"）或 epoch 秒/毫秒数字串
     */
    data class ActivityFeedItem(
        val type: String,
        val action: String,
        val detail: String,
        val mediaId: String,
        val timestamp: String
    )

    /**
     * 统一活动流：GET /api/media/activity-feed?limit=20。
     *
     * 后端返回 `{feed: [{type, action, detail, media_id, timestamp}], total}`，
     * 聚合全库近期操作（上传/删除/分享/重命名/收藏/打标签/恢复/旋转…）为一条统一时间线，
     * 按 timestamp 倒序。前端最多取 [limit] 条，UI 再 take(10) 展示。
     *
     * 解析沿用 [getRecentActivity]/[getFileTypes] 的运行时 JSON 操作（无 serialization
     * 编译器插件依赖）。[timestamp] 作为字符串原样透传，相对时间换算交给 UI 侧
     * （[MediaListScreen] 的 `relativeTime` 辅助函数，支持 RFC3339 与 epoch 数字串）。
     *
     * 失败后盾：return null（与同级 stat 方法一致），UI 侧 null-skip 不渲染该卡片，
     * 不抛异常——活动流是锦上添花的展示卡片，不应因后端未上线/暂时性网络错误搞挂"我的"Tab。
     *
     * @param limit 请求条数上限，默认 20（UI 侧再裁剪到 10 显示）
     * @return 活动流条目列表（后端原序，通常已倒序）；HTTP 非 200 或异常返回 null
     */
    suspend fun getActivityFeed(limit: Int = 20): List<ActivityFeedItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/activity-feed") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["feed"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    ActivityFeedItem(
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        action = o["action"]?.jsonPrimitive?.contentOrNull
                            ?: o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        timestamp = o["timestamp"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getActivityFeed FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 后端搜索历史条目 —— 对应 GET /api/media/search-history 返回的 history[] 元素。
     *
     * 后端结构：`{ "id": "...", "action": "...", "detail": "...", "created_at": "..." }`。
     * action 为动作类型（如 search/delete/rename/rotate/upload 等），detail 为动作详情
     * （搜索词、文件名等），created_at 为后端 time.Time 的 RFC3339 字符串，前端仅透传展示。
     *
     * 与本地 [com.wgt.media.SearchHistory] 区别：后者只记录本端键入的搜索词，本类记录
     * 后端视角的全量最近操作（含搜索、删除、重命名等），用于搜索栏"最近操作"区展示。
     */
    data class SearchHistoryItem(
        val id: String,
        val action: String,
        val detail: String,
        val createdAt: String
    )

    /**
     * V9：GET /api/media/search-history — 后端最近操作历史。
     *
     * 后端返回 `{ "history": [...], "total": N }`，此处仅取 history 数组解析为
     * [SearchHistoryItem] 列表。调用方（搜索栏"最近操作"区）按需取前 N 条展示。
     *
     * 鉴权由 [jsonClient] 的 defaultRequest 统一附加 Bearer token；失败/非 200 返回 null，
     * 调用方降级为不展示该区。
     */
    suspend fun getSearchHistoryFromBackend(): List<SearchHistoryItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/search-history") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["history"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    SearchHistoryItem(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getSearchHistoryFromBackend FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * V9：POST /api/media/album/pin — 置顶相册。
     *
     * 请求体 `{ "album_id": "x" }`，后端成功返回 200 `{ "status":"success","album_id":"x" }`。
     * 失败（网络异常/非 200）返回 false。
     *
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun pinAlbum(albumId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/pin") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("album_id", albumId) })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "pinAlbum id=$albumId status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "pinAlbum FAILED id=$albumId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V9：POST /api/media/album/unpin — 取消相册置顶。
     *
     * 请求体 `{ "album_id": "x" }`，后端成功返回 200 `{ "status":"success","album_id":"x" }`。
     * 失败（网络异常/非 200）返回 false。
     *
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun unpinAlbum(albumId: String): Boolean {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/unpin") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("album_id", albumId) })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "unpinAlbum id=$albumId status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "unpinAlbum FAILED id=$albumId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V9：GET /api/media/album/pinned — 获取当前用户置顶的相册 id 集合。
     *
     * 后端返回 `{ "albums":[...], "count":N }`。此处仅取每个相册的 `id`，用于
     * UI 判定渲染置顶标记 / 决定长按菜单的可执行动作（置顶或取消置顶）。
     * 失败时返回空集合，UI 降级为不显示置顶状态。
     */
    suspend fun getPinnedAlbumIds(): Set<String> {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/pinned")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val arr = obj["albums"]?.jsonArray ?: JsonArray(emptyList())
                arr.mapNotNull { item ->
                    val o = item.jsonObject
                    o["id"]?.jsonPrimitive?.contentOrNull
                }.toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getPinnedAlbumIds FAILED: ${e::class.simpleName} ${e.message}")
            emptySet()
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

    /** V8：POST /api/media/batch-favorite-remove — 批量取消收藏，返回成功数。 */
    suspend fun batchRemoveFavorites(mediaIds: List<String>): Int {
        if (mediaIds.isEmpty()) return 0
        return try {
            val body = buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-favorite-remove") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["removed_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "batchRemoveFavorites FAILED: ${e.message}")
            0
        }
    }

    /** V8：POST /api/media/album/sort-by-date — 按日期排序相册内媒体。 */
    suspend fun sortAlbumByDate(albumId: String, order: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("album_id", albumId)
                put("order", order)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/sort-by-date") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error("MediaService", "sortAlbumByDate FAILED: ${e.message}")
            false
        }
    }

    /** V8：POST /api/media/auto-tag — 按文件名自动打标签，返回标签数。 */
    suspend fun autoTag(): Int {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/auto-tag") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tagged_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "autoTag FAILED: ${e.message}")
            0
        }
    }

    /** V8：POST /api/media/album/clone — 复制相册，返回新相册 ID。 */
    suspend fun cloneAlbum(sourceAlbumId: String, newName: String): String? {
        return try {
            val body = buildJsonObject {
                put("source_album_id", sourceAlbumId)
                put("new_name", newName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/clone") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["new_album_id"]?.jsonPrimitive?.contentOrNull
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "cloneAlbum FAILED: ${e.message}")
            null
        }
    }

    /** V8：POST /api/media/album/delete-batch — 批量删除相册，返回成功数。 */
    suspend fun deleteAlbumsBatch(albumIds: List<String>): Int {
        if (albumIds.isEmpty()) return 0
        return try {
            val body = buildJsonObject {
                putJsonArray("album_ids") { albumIds.forEach { add(it) } }
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/delete-batch") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["deleted_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "deleteAlbumsBatch FAILED: ${e.message}")
            0
        }
    }

    /** V8：POST /api/media/cleanup-orphan — 清理孤立记录，返回清理数。 */
    suspend fun cleanupOrphan(): Pair<Int, List<String>>? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/cleanup-orphan") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val count = obj["cleaned_count"]?.jsonPrimitive?.intOrNull ?: 0
                val ids = obj["cleaned_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                Pair(count, ids)
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "cleanupOrphan FAILED: ${e.message}")
            null
        }
    }

    /** V8：GET /api/media/sync-status — 同步状态摘要。 */
    suspend fun getSyncStatus(): SyncStatus? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/sync-status") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                SyncStatus(
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    deletedMedia = o["deleted_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    lastUpdate = o["last_update"]?.jsonPrimitive?.contentOrNull ?: "",
                    serverTime = o["server_time"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getSyncStatus FAILED: ${e.message}")
            null
        }
    }

    /** V8：同步状态 */
    data class SyncStatus(
        val totalMedia: Int,
        val deletedMedia: Int,
        val totalBytes: Long,
        val lastUpdate: String,
        val serverTime: String
    )

    /** V8：GET /api/media/by-size-range — 按大小范围统计。 */
    suspend fun getBySizeRange(): SizeRangeStat? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/by-size-range") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val counts = obj["ranges"]?.jsonObject?.let { resObj ->
                    resObj.entries.associate { (k, v) ->
                        k to (v.jsonPrimitive.intOrNull ?: 0)
                    }
                } ?: emptyMap()
                SizeRangeStat(counts = counts)
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getBySizeRange FAILED: ${e.message}")
            null
        }
    }

    /** V8：大小范围统计 */
    data class SizeRangeStat(val counts: Map<String, Int>)

    /** V8：GET /api/media/by-resolution — 按分辨率统计。 */
    suspend fun getByResolution(): Map<String, Int>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/by-resolution") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["resolutions"]?.jsonObject?.let { resObj ->
                    resObj.entries.associate { (k, v) ->
                        k to (v.jsonPrimitive.intOrNull ?: 0)
                    }
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getByResolution FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/time-distribution — 按拍摄时段统计媒体数量。
     *
     * 后端返回 `{distribution: {"早晨":N,"下午":N,"晚上":N,"深夜":N}, total: N}`，
     * 本方法仅取 `distribution` 对象解析为 `Map<String, Int>`（键=时段名，值=数量）。
     * 失败返回 null，调用方按 null 展示空状态。`total` 字段前端不单独透传，
     * 由 `values.sum()` 推导即可（与 [getByResolution] 同款 Map 解析）。
     *
     * 注意：`(v.jsonPrimitive.intOrNull ?: 0)` 外层的括号不可省——Kotlin/Native
     * 下 `k to v ?: 0` 会把 `?:` 推宽成 `Serializable`，导致 `associate` 返回类型
     * 不匹配（见 kmp-platform-capabilities 技能「associate + ?: 类型推断」陷阱）。
     */
    suspend fun getTimeDistribution(): Map<String, Int>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/time-distribution") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["distribution"]?.jsonObject?.let { distObj ->
                    distObj.entries.associate { (k, v) ->
                        k to (v.jsonPrimitive.intOrNull ?: 0)
                    }
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTimeDistribution FAILED: ${e.message}")
            null
        }
    }

    /** V8：GET /api/media/disk-usage — 服务器磁盘使用情况。 */
    suspend fun getDiskUsage(): DiskUsage? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/disk-usage") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                DiskUsage(
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    usedBytes = o["used_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    freeBytes = o["free_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    usagePercent = o["usage_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getDiskUsage FAILED: ${e.message}")
            null
        }
    }

    /** V8：磁盘使用 */
    data class DiskUsage(
        val totalBytes: Long,
        val usedBytes: Long,
        val freeBytes: Long,
        val usagePercent: Double
    ) {
        val totalGB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val usedGB: Double get() = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val freeGB: Double get() = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
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

    /**
     * V9：连续上传天数信息——配合"我的"Tab 上传日历热力图后展示，激励用户保持上传习惯。
     *
     * 后端 GET /api/media/upload-streak 返回：
     * `{current_streak, longest_streak, total_active_days, last_upload_date, today_count}`。
     * - [currentStreak] 当前连续上传天数（含今天则 +1，今天未传则归零或保留至昨天的值，以后端为准）。
     * - [longestStreak] 历史最长连续天数。
     * - [totalActiveDays] 累计有上传记录的独立天数。
     * - [lastUploadDate] 最近一次上传日期（"YYYY-MM-DD"）。
     * - [todayCount] 今天已上传数量（0 表示今天尚未上传）。
     */
    data class UploadStreak(
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val totalActiveDays: Int = 0,
        val lastUploadDate: String = "",
        val todayCount: Int = 0
    )

    /**
     * V9：GET /api/media/upload-streak — 获取连续上传天数统计。
     *
     * 解析宽容：缺字段回退 0/空串（[contentOrNull]），保证后端字段增减不破坏前端。
     * 失败（HTTP 非 200 / 网络异常）返回 null，调用方按 null 静默跳过不渲染卡片。
     */
    suspend fun getUploadStreak(): UploadStreak? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/upload-streak") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                UploadStreak(
                    currentStreak = o["current_streak"]?.jsonPrimitive?.intOrNull ?: 0,
                    longestStreak = o["longest_streak"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalActiveDays = o["total_active_days"]?.jsonPrimitive?.intOrNull ?: 0,
                    lastUploadDate = o["last_upload_date"]?.jsonPrimitive?.contentOrNull ?: "",
                    todayCount = o["today_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getUploadStreak FAILED: ${e.message}")
            null
        }
    }

    /**
     * V9：本周上传摘要的一天计数（[WeeklySummary.byDay] 的元素）。
     *
     * [day] 为英文星期缩写（Mon/Tue/Wed/Thu/Fri/Sat/Sun），与后端 [handleWeeklySummary]
     * 的 by_day.day 字段一致；前端展示时再映射为“周一/周二/…”。
     */
    data class DayCount(
        val day: String = "",
        val count: Int = 0
    )

    /**
     * V9：本周上传摘要——配合"连续上传"卡片后展示本周活动概览。
     *
     * 后端 GET /api/media/weekly-summary 返回（滚动 7 天窗口，UTC）：
     * `{week_start, week_end, uploaded_count, uploaded_bytes,
     *   by_day:[{day,count}, ...7], most_active_day:{day,count},
     *   new_tags_count, new_albums_count}`。
     * - [weekStart]/[weekEnd] 窗口起止时间（RFC3339 字符串，原样透传展示）。
     * - [uploadedCount] 本周上传媒体数。
     * - [uploadedBytes] 本周上传媒体总字节。
     * - [byDay] 7 天逐日上传数，[DayCount.day] 为英文星期缩写。
     * - [mostActiveDay] 本周上传最多的一天；全 0 时 [DayCount.day] 为空串。
     * - [newTagsCount] 本周标签操作数（audit 口径）。
     * - [newAlbumsCount] 本周新建相册数。
     */
    data class WeeklySummary(
        val weekStart: String = "",
        val weekEnd: String = "",
        val uploadedCount: Int = 0,
        val uploadedBytes: Long = 0L,
        val byDay: List<DayCount> = emptyList(),
        val mostActiveDay: DayCount = DayCount(),
        val newTagsCount: Int = 0,
        val newAlbumsCount: Int = 0
    )

    /**
     * V9：GET /api/media/weekly-summary — 获取本周上传摘要。
     *
     * 解析宽容：by_day 缺失回退空列表，most_active_day 缺失回退空 [DayCount]，
     * 数值字段缺省 0/空串。失败（HTTP 非 200 / 网络异常）返回 null，
     * 调用方按 null 静默跳过不渲染卡片（与 [getUploadStreak] 同款 null 容错）。
     */
    suspend fun getWeeklySummary(): WeeklySummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/weekly-summary") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val byDay = o["by_day"]?.jsonArray?.map { el ->
                    val d = el.jsonObject
                    DayCount(
                        day = d["day"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = d["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                val mad = o["most_active_day"]?.jsonObject
                WeeklySummary(
                    weekStart = o["week_start"]?.jsonPrimitive?.contentOrNull ?: "",
                    weekEnd = o["week_end"]?.jsonPrimitive?.contentOrNull ?: "",
                    uploadedCount = o["uploaded_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    uploadedBytes = o["uploaded_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    byDay = byDay,
                    mostActiveDay = DayCount(
                        day = mad?.get("day")?.jsonPrimitive?.contentOrNull ?: "",
                        count = mad?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    newTagsCount = o["new_tags_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    newAlbumsCount = o["new_albums_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getWeeklySummary FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/timeline-calendar — 按拍摄日期分组的媒体统计。
     *
     * 后端返回 `{days: [{date, count, type}], total_days, total_media}`，
     * 本方法仅取 `days` 数组解析为 [TimelineCalendarDay]。`type` 字段用于
     * 前端按媒体类型着色（图片蓝 / 视频红 / Live 绿）。失败返回 null，
     * 调用方按 null 展示空状态。
     */
    suspend fun getTimelineCalendar(): List<TimelineCalendarDay>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/timeline-calendar") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["days"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TimelineCalendarDay(
                        date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "image"
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTimelineCalendar FAILED: ${e.message}")
            null
        }
    }

    /** V8：拍摄日历单日（按拍摄日期分组：日期 + 条数 + 类型）。 */
    data class TimelineCalendarDay(val date: String, val count: Int, val type: String)

    /**
     * V9：GET /api/media/media-heatmap — 拍摄热力图数据。
     *
     * 后端返回 `{days: [{date, count}], total_days, total_media}`，
     * 本方法仅取 `days` 数组解析为 [HeatmapDay]。`date` 形如 "2026-07-31"，
     * `count` 为当天拍摄数量。失败返回 null，调用方按 null 展示空状态。
     */
    suspend fun getMediaHeatmap(): List<HeatmapDay>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-heatmap") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["days"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    HeatmapDay(
                        date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaHeatmap FAILED: ${e.message}")
            null
        }
    }

    /** V9：拍摄热力图单日（日期 + 当天拍摄数量）。 */
    data class HeatmapDay(val date: String, val count: Int)

    /**
     * V9：GET /api/media/media-by-hour — 按 24 小时分布统计上传时段。
     *
     * 后端返回 `{hours: [{hour, count}], total}`，24 个小时槽全返回（含 count=0）。
     * 本方法取 `hours` 数组解析为 [HourCount]。失败返回 null，调用方按 null 展示空状态。
     */
    suspend fun getMediaByHour(): List<HourCount>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-by-hour") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["hours"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    HourCount(
                        hour = o["hour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaByHour FAILED: ${e.message}")
            null
        }
    }

    /** V9：上传时段单小时（小时 0-23 + 上传数量）。 */
    data class HourCount(val hour: Int, val count: Int)

    /**
     * V21：GET /api/media/media-age-distribution — 媒体年龄分布。
     *
     * 后端按 created_at（上传时间）到 now 的时间差将所有未软删媒体分入 6 个年龄档：
     *   <1天 / 1-7天 / 7-30天 / 30-90天 / 90-365天 / >365天
     * 返回 `{ranges: [{range, count, bytes}], total}`，6 档顺序固定、含 count=0 档。
     * count/bytes 均为后端 int64（永不为 JSON null），故用 `?: 0` / `?: 0L` 安全默认。
     * 本方法取 `ranges` 数组解析为 [AgeRange]。失败返回 null，调用方按 null 展示空状态。
     */
    suspend fun getMediaAgeDistribution(): List<AgeRange>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-age-distribution") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["ranges"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AgeRange(
                        range = o["range"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaAgeDistribution FAILED: ${e.message}")
            null
        }
    }

    /** V21：媒体年龄分布单档（年龄范围标签 + 该档媒体数 + 累计字节）。 */
    data class AgeRange(val range: String, val count: Int, val bytes: Long)

    /**
     * V21：GET /api/media/media-archive-status — 媒体归档状态（热/温/冷数据温度分布）。
     *
     * 后端按 created_at（上传时间）到 now 的时间差将所有未软删媒体分入 3 个归档温度档：
     *   - hot  热数据（最近 30 天内上传）
     *   - warm 温数据（30-180 天内上传）
     *   - cold 冷数据（上传超过 180 天）
     * 每档统计 count 与 bytes（累计该档媒体的 Size）。
     *
     * 响应结构（与 [handleMediaArchiveStatus] 对齐）：
     *   `{hot:{count,bytes}, warm:{count,bytes}, cold:{count,bytes}, total:N}`
     * 注意 `total` 是参与分类的未软删媒体总数（整数），不是 TierInfo 对象；
     * 三档 count 之和恒等于 total。
     *
     * count/bytes 均为后端 int64（永不为 JSON null），用 `?: 0` / `?: 0L` 安全默认。
     * 失败返回 null，调用方按 null 静默跳过不渲染占位（与 [getMediaAgeDistribution] 同款）。
     */
    suspend fun getMediaArchiveStatus(): ArchiveStatus? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-archive-status") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // 解析单个温度档对象为 TierInfo；缺失或非对象时返回 0 档，保证三行稳定渲染。
                fun tier(key: String): TierInfo {
                    val o = obj[key]?.jsonObject ?: return TierInfo(0, 0L)
                    return TierInfo(
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
                ArchiveStatus(
                    hot = tier("hot"),
                    warm = tier("warm"),
                    cold = tier("cold"),
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaArchiveStatus FAILED: ${e.message}")
            null
        }
    }

    /** V21：归档温度档统计（该档媒体数 + 累计字节）。 */
    data class TierInfo(val count: Int, val bytes: Long)

    /** V21：媒体归档状态（热/温/冷三档 + 参与分类的未软删媒体总数）。 */
    data class ArchiveStatus(
        val hot: TierInfo,
        val warm: TierInfo,
        val cold: TierInfo,
        val total: Int
    )

    /**
     * V20：GET /api/media/upload-pattern-analysis — 上传模式分析。
     *
     * 后端基于当前用户全部未删除媒体的 created_at/size/type 统计最常上传的：
     *   - 类型（IMAGE / VIDEO / LIVE_PHOTO）
     *   - 大小范围（<1MB / 1-10MB / 10-50MB / 50-100MB / >100MB）
     *   - 时段（早晨 6-11 / 下午 12-17 / 晚上 18-23 / 深夜 0-5）
     *   - 星期（Sunday..Saturday，后端用 time.Weekday().String() 全称）
     *
     * 返回结构：`{dominant_type, dominant_size_range, dominant_time_period,
     * dominant_weekday, total}`，每个 `dominant_*` 形如 `{key, count}`。
     * 本方法将后端原始 key 映射为前端可读 label（IMAGE→图片、Saturday→周六 等），
     * 解析为 [UploadPattern]。total=0（无数据）或请求异常时返回 null，调用方静默跳过。
     */
    suspend fun getUploadPatternAnalysis(): UploadPattern? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/upload-pattern-analysis")
            if (response.status != HttpStatusCode.OK) return null
            val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
            val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
            if (total == 0) return null
            // 解析单个 dominant_* 对象为 PatternItem，mapper 将原始 key 转为可读 label。
            fun item(field: String, mapper: (String) -> String = { it }): PatternItem? {
                val o = obj[field]?.jsonObject ?: return null
                val rawKey = o["key"]?.jsonPrimitive?.contentOrNull ?: return null
                val count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                return PatternItem(label = mapper(rawKey), count = count)
            }
            UploadPattern(
                dominantType = item("dominant_type") { mapTypeLabel(it) } ?: return null,
                dominantSizeRange = item("dominant_size_range") ?: return null,
                dominantTimePeriod = item("dominant_time_period") ?: return null,
                dominantWeekday = item("dominant_weekday") { mapWeekdayLabel(it) } ?: return null,
                total = total
            )
        } catch (e: Exception) {
            logger.error("MediaService", "getUploadPatternAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** 后端类型 key → 前端中文 label。未知类型原样返回。 */
    private fun mapTypeLabel(key: String): String = when (key) {
        "IMAGE" -> "图片"
        "VIDEO" -> "视频"
        "LIVE_PHOTO" -> "Live Photo"
        else -> key
    }

    /** 后端 weekday 全称（time.Weekday().String()） → 中文周X。未知原样返回。 */
    private fun mapWeekdayLabel(key: String): String = when (key) {
        "Sunday" -> "周日"
        "Monday" -> "周一"
        "Tuesday" -> "周二"
        "Wednesday" -> "周三"
        "Thursday" -> "周四"
        "Friday" -> "周五"
        "Saturday" -> "周六"
        else -> key
    }

    /** V20：上传模式分析结果（最常上传的类型/大小范围/时段/星期 + 总数）。 */
    data class UploadPattern(
        val dominantType: PatternItem,
        val dominantSizeRange: PatternItem,
        val dominantTimePeriod: PatternItem,
        val dominantWeekday: PatternItem,
        val total: Int
    )

    /** V20：单维度众数项（前端可读 label + 次数）。 */
    data class PatternItem(val label: String, val count: Int)

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

    /**
     * V9：GET /api/media/tag-co-occurrence — 标签共现分析。
     *
     * 对每对标签 (A, B) 统计同时拥有这两个标签的媒体数量，后端只返回 count >= 2
     * 的标签对。响应: `{pairs: [{tag_a, tag_b, count}], total_pairs}`。
     *
     * 注意：后端 pairs 按标签遍历顺序（i<j）输出，**未按 count 排序**，故前端取
     * top-N 前需自行按 count 倒序（见 [MediaListScreen] 标签管理面板的 `sortedByDescending`）。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（无 serialization 编译器插件依赖）。
     *
     * @return 标签共现对列表（原序）；HTTP 非 200 或网络异常返回 null（调用方按空态跳过）。
     */
    suspend fun getTagCoOccurrence(): List<TagPair>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-co-occurrence") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["pairs"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagPair(
                        tagA = o["tag_a"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        tagB = o["tag_b"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagCoOccurrence FAILED: ${e.message}")
            null
        }
    }

    /** V9：标签共现对 */
    data class TagPair(val tagA: String, val tagB: String, val count: Int)

    /**
     * V9：GET /api/media/tag-network — 标签网络图数据（节点+边）。
     *
     * 与 [getTagCoOccurrence] 同源数据但输出图结构：标签作为节点（[TagNode.count]
     * =关联媒体数），共现关系作为边（[TagEdge.weight] =同时拥有两标签的 media 数）。
     * 响应: `{nodes: [{id, count}], edges: [{source, target, weight}], total_nodes, total_edges}`。
     *
     * 注意：后端 edges 按标签遍历顺序（i<j）输出，**未按 weight 排序**，故前端
     * 取 top-N 前需自行按 weight 倒序（见 [MediaListScreen] 标签关联卡片）。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（无 serialization 编译器插件依赖）。
     *
     * @return 标签网络（节点+边+汇总数）；HTTP 非 200 或网络异常返回 null（调用方按空态跳过）。
     */
    suspend fun getTagNetwork(): TagNetwork? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-network") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val nodes = o["nodes"]?.jsonArray?.mapNotNull { item ->
                    val n = item.jsonObject
                    TagNode(
                        id = n["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = n["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                val edges = o["edges"]?.jsonArray?.mapNotNull { item ->
                    val e = item.jsonObject
                    TagEdge(
                        source = e["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        target = e["target"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        weight = e["weight"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                TagNetwork(
                    nodes = nodes,
                    edges = edges,
                    totalNodes = o["total_nodes"]?.jsonPrimitive?.intOrNull ?: nodes.size,
                    totalEdges = o["total_edges"]?.jsonPrimitive?.intOrNull ?: edges.size
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagNetwork FAILED: ${e.message}")
            null
        }
    }

    /** V9：标签网络节点。 */
    data class TagNode(val id: String, val count: Int)

    /** V9：标签网络边（关联对）。 */
    data class TagEdge(val source: String, val target: String, val weight: Int)

    /** V9：标签网络图数据（GET /api/media/tag-network）。 */
    data class TagNetwork(
        val nodes: List<TagNode>,
        val edges: List<TagEdge>,
        val totalNodes: Int,
        val totalEdges: Int
    )

    /**
     * V8：GET /api/media/mime-type-stats — 按 MIME 类型详细统计。
     *
     * 与 [getFileTypes]（`/api/media/file-types`）的区别：本端点额外提供
     * `avg_bytes`（平均大小）与 `earliest`/`latest`（该 MIME 最早/最晚上传时间，
     * RFC3339 字符串），供前端按 MIME 粒度展示完整大小与时间维度。
     *
     * 响应结构：`{mimes: [{mime, count, total_bytes, avg_bytes, earliest, latest}], total}`，
     * `mimes` 数组按 count 倒序（同序并列按 MIME 字典序），故前端取前若干即数量最多的。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（无 serialization 编译器插件依赖），
     * `earliest`/`latest` 作为字符串原样透传，不在此处解析为毫秒（前端按需展示）。
     *
     * @return 按 count 倒序的统计列表；HTTP 非 200 或网络异常返回 null（调用方按空态跳过）。
     */
    suspend fun getMimeTypeStats(): List<MimeStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/mime-type-stats") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["mimes"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    MimeStat(
                        mime = o["mime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        avgBytes = o["avg_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        earliest = o["earliest"]?.jsonPrimitive?.contentOrNull ?: "",
                        latest = o["latest"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMimeTypeStats FAILED: ${e.message}")
            null
        }
    }

    /** V8：MIME 详细统计项（对齐后端 mime-type-stats 响应字段）。 */
    data class MimeStat(
        val mime: String,
        val count: Int,
        val totalBytes: Long,
        val avgBytes: Long,
        val earliest: String,
        val latest: String
    )

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

    /**
     * V9：POST /api/media/tag/rename — 重命名标签，返回是否成功。
     *
     * 后端按 `{ old_name, new_name }` 接收，将所有媒体的 `old_name` 标签替换为
     * `new_name`（若 `new_name` 已存在则合并）。调用成功后需刷新 tagStats 以反映新名。
     *
     * @param oldName 旧标签名
     * @param newName 新标签名
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun renameTag(oldName: String, newName: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("old_name", oldName)
                put("new_name", newName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/rename") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "renameTag old=$oldName new=$newName status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "renameTag FAILED: ${e.message}")
            false
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

    /**
     * V9：GET /api/media/tag/cloud-data — 标签云数据（含每标签封面缩略图 URL）。
     *
     * 后端返回 `{ tags: [{tag_name, count, thumbnail_url}], total }`：
     * - tag_name：标签名
     * - count：该标签下媒体数量（已按 count DESC 排序）
     * - thumbnail_url：该标签关联第一个媒体的缩略图相对路径，形如
     *   `/api/media/thumbnail/{media_id}`；空串表示该标签无关联媒体。
     *
     * 前端只消费 [tagName] + [count] + [thumbnailUrl]（thumbnailUrl 仅用于
     * 提取 media_id 后交 [BackendImageLoader.loadThumbnail] 加载封面缩略图），故
     * 这里把相对路径原样透传，拆 media_id 的工作在 UI 层完成（避免本层引入路径解析）。
     *
     * 返回 `null` = 网络/HTTP 失败（UI 隐藏整张卡片）；非 null（可能为空列表）= 成功。
     */
    suspend fun getTagCloudData(): List<TagCloudItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/cloud-data") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagCloudItem(
                        tagName = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        thumbnailUrl = o["thumbnail_url"]?.let {
                            // 字段可能为 JSON null（无关联媒体）—— 防御 JsonNull 非 JsonPrimitive。
                            if (it is JsonNull) null else it.jsonPrimitive?.contentOrNull?.takeIf { url -> url.isNotEmpty() }
                        }
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagCloudData FAILED: ${e.message}")
            null
        }
    }

    /** V9：标签云条目（标签名 + 计数 + 封面缩略图相对 URL，可能为 null）。 */
    data class TagCloudItem(val tagName: String, val count: Int, val thumbnailUrl: String?)

    /**
     * V21：标签详情统计条目。
     *
     * 后端有两个相关端点，本端在前端合并：
     * - GET /api/media/tag-stat-detailed → {tag_name, count, total_bytes, last_created_at}
     * - GET /api/media/tag/stat-by-type  → {tag_name, total, image_count, video_count, live_count}
     *
     * [total] 沿用 tag-stat-detailed 的 count（标签关系表口径，含软删 media 关联）；
     * [imageCount]/[videoCount]/[liveCount] 来自 stat-by-type（按未软删 media 的 type 分布），
     * 故三者之和可能 <= total（差额为已被软删的关联 media），属正常口径差异，非 bug。
     * [totalBytes] 为该标签关联未软删 media 的文件 size 求和。
     * [lastCreatedAt] 为关联 media 中最近 created_at（RFC3339，无关联时为空串）。
     */
    data class TagDetailedStat(
        val tagName: String,
        val total: Int,
        val imageCount: Int,
        val videoCount: Int,
        val liveCount: Int,
        val totalBytes: Long,
        val lastCreatedAt: String
    ) {
        /** 该标签关联文件总大小 MB（保留一位小数由 UI 文字截断处理）。 */
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /**
     * V21：GET /api/media/tag-stat-detailed + tag/stat-by-type — 标签详情统计。
     *
     * 并发拉取两个端点：tag-stat-detailed 提供 count/size/时间，stat-by-type 提供
     * 媒体类型分布（图片/视频/Live）。按 tag_name 合并为 [TagDetailedStat]，按 total
     * 降序返回（与后端 TagStats 的 count DESC 口径一致）。
     *
     * 容错：若 stat-by-type 请求失败或非 200，仅用 tag-stat-detailed 数据，分布置 0；
     * tag-stat-detailed 失败则整体返回 null（UI 静默跳过卡片，与其他统计卡片一致）。
     * stat-by-type 中存在但 tag-stat-detailed 缺失的标签跳过，以 detailed 为权威源。
     */
    suspend fun getTagStatDetailed(): List<TagDetailedStat>? {
        return try {
            // 并发拉两端点；coroutineScope 保证任一异常冒泡到外层 catch 统一兜底。
            // runCatching 包裹 stat-by-type 使其失败不致命——分布缺失仅置 0。
            coroutineScope {
                val detailedDeferred = async {
                    val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-stat-detailed") {
                        getAuthToken()?.let { header("Authorization", "Bearer $it") }
                    }
                    if (response.status != HttpStatusCode.OK) return@async emptyList<Pair<String, JsonObject>>()
                    val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                    obj["tags"]?.jsonArray?.mapNotNull { item ->
                        val o = item.jsonObject
                        val name = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        name to o
                    } ?: emptyList()
                }
                val typeDeferred = async {
                    runCatching {
                        val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/stat-by-type") {
                            getAuthToken()?.let { header("Authorization", "Bearer $it") }
                        }
                        if (response.status != HttpStatusCode.OK) return@runCatching emptyMap<String, JsonObject>()
                        val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                        obj["tags"]?.jsonArray?.mapNotNull { item ->
                            val o = item.jsonObject
                            val name = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            name to o
                        }?.toMap() ?: emptyMap()
                    }.getOrDefault(emptyMap())
                }
                val detailed = detailedDeferred.await()
                val typeMap = typeDeferred.await()
                if (detailed.isEmpty()) return@coroutineScope emptyList<TagDetailedStat>()
                detailed.map { (name, o) ->
                    val to = typeMap[name]
                    TagDetailedStat(
                        tagName = name,
                        total = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        imageCount = to?.get("image_count")?.jsonPrimitive?.intOrNull ?: 0,
                        videoCount = to?.get("video_count")?.jsonPrimitive?.intOrNull ?: 0,
                        liveCount = to?.get("live_count")?.jsonPrimitive?.intOrNull ?: 0,
                        totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        lastCreatedAt = o["last_created_at"]?.let {
                            // 后端无关联 media 时该字段为 null（JsonNull），前端统一映射为空串。
                            if (it is JsonNull) "" else it.jsonPrimitive?.contentOrNull ?: ""
                        } ?: ""
                    )
                }.sortedByDescending { it.total }
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getTagStatDetailed FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * V22：智能标签推荐单项。后端 [tagRecommendation] JSON 对齐：
     * `{tag_name, reason, suggested_media_count}`。
     *
     * - [tagName] 推荐的标签名（如"照片"/"截图"），由后端按文件名命名模式映射。
     * - [reason] 推荐理由（如"文件名以 IMG_ 开头"），直接展示给用户。
     * - [suggestedMediaCount] 命中该模式的可标记媒体数，供 UI 显示"(N 项可标记)"。
     */
    data class TagRecommendation(
        val tagName: String = "",
        val reason: String = "",
        val suggestedMediaCount: Int = 0
    )

    /**
     * V22：GET /api/media/tag-recommendations — 拉取智能标签推荐。
     *
     * 后端扫描用户所有未删除媒体的文件名，按常见命名模式（IMG_/VID_/Screenshot/
     * WeChat/camera）映射到中文标签名；若用户已有该标签则跳过。返回命中该模式的
     * 媒体数量，供前端"推荐标签"区展示并让用户一键采纳（点击调 [autoTag] 批量打标签）。
     *
     * 端点只读、不修改任何媒体；采纳动作走 [autoTag]（POST /api/media/auto-tag）。
     *
     * 响应：`{recommendations:[{tag_name,reason,suggested_media_count}], total}`
     * 成功返回 [TagRecommendation] 列表（可能为空，表示无推荐）；失败/非 200 返回 null，
     * 调用方按 null 降级（隐藏推荐区）。
     */
    suspend fun getTagRecommendations(): List<TagRecommendation>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-recommendations") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["recommendations"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    TagRecommendation(
                        tagName = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                        suggestedMediaCount = o["suggested_media_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else {
                logger.info("MediaService", "getTagRecommendations status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getTagRecommendations FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V8：GET /api/media/media-lifecycle — 媒体生命周期分析。 */
    suspend fun getMediaLifecycle(): List<LifecycleStage>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-lifecycle") {
                header("Authorization", "Bearer ${getAuthToken()}")
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["lifecycle"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    LifecycleStage(
                        stage = o["stage"]?.jsonPrimitive?.contentOrNull ?: "",
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaLifecycle FAILED: ${e.message}")
            null
        }
    }

    data class LifecycleStage(
        val stage: String,
        val action: String,
        val count: Int,
        val percentage: Double
    )

    /**
     * V23：POST /api/media/apply-tag-recommendations — 一键应用全部标签推荐。
     *
     * 后端复用 [getTagRecommendations] 的推荐计算逻辑，对每条推荐的匹配媒体逐个落库
     * （AddMediaTag，INSERT OR IGNORE 幂等），返回已应用的标签-Media 关联总数。
     *
     * 响应：`{status, applied_count, tags_applied:[{tag_name,count}]}`。
     * 成功返回 [applied_count][Result.appliedCount]（≥0）；失败/非 200 返回 null，
     * 调用方按 null 降级并提示错误。
     *
     * 与 [autoTag] 区别：autoTag 按文件名前缀规则笼统打标签（单一 tagged_count），
     * 本方法按推荐列表逐条应用并返回每标签明细，语义上覆盖推荐区列出的全部匹配媒体。
     */
    suspend fun applyTagRecommendations(): Int? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/apply-tag-recommendations") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["applied_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else {
                logger.info("MediaService", "applyTagRecommendations status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "applyTagRecommendations FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V9：GET /api/media/tag/export — 导出标签数据。
     *
     * 返回后端原始 JSON 字符串（包含全部标签及其关联媒体），供前端复制到剪贴板
     * 或保存为文件分享。与 [listAllTags] 区别：后者仅返回标签名列表，本方法返回
     * 完整导出数据（标签 + 媒体映射），格式由后端决定，前端透传不解析。
     *
     * @return 成功时为原始 JSON 字符串；失败/非 200 时返回 null。
     */
    suspend fun exportTags(): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/export") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                logger.info("MediaService", "exportTags status=${response.status} bytes=${body.length}")
                body
            } else {
                logger.info("MediaService", "exportTags status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "exportTags FAILED: ${e::class.simpleName} ${e.message}")
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
     * V8：GET /api/media/video-info/{id} — 视频时长/分辨率/编码（ffprobe 解析结果）。
     *
     * 后端 [getMediaInfo] 的 /api/media/info/{id} 响应不含时长（Media 模型无该字段），
     * 视频时长由独立的 ffprobe 端点提供。前端在 [MediaInfoDialog] 对 type==VIDEO
     * 的媒体并发请求本方法，展示"时长：xxx 秒"。非视频或解析失败返回 null，UI 静默跳过。
     *
     * 后端 [service.VideoInfoResponse] 结构：`{duration_seconds,width,height,codec,container}`。
     */
    suspend fun getVideoInfo(mediaId: String): VideoInfo? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/video-info/$mediaId")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                VideoInfo(
                    durationSeconds = o["duration_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    width = o["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    codec = o["codec"]?.jsonPrimitive?.contentOrNull ?: "",
                    container = o["container"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getVideoInfo FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V8：视频 ffprobe 解析结果（时长/分辨率/编码）。 */
    data class VideoInfo(
        val durationSeconds: Double,
        val width: Int,
        val height: Int,
        val codec: String,
        val container: String
    )

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

    /**
     * V9：GET /api/media/storage-forecast — 存储预测。
     *
     * 后端基于最近 6 个月上传趋势线性外推 1/3/6 个月后用量，并估算配额耗尽月数。
     * 字段对齐后端 [b12cc8e] 响应：current_bytes / monthly_average_bytes /
     * growth_rate_percent / forecast[{months_ahead,predicted_bytes}] /
     * quota_bytes / months_until_full（Int?，已超配额或样本不足时为 null）。
     *
     * 与 [getStorageTrend] 同走 GET + 运行时 Json 解析；非 200 或异常返回 null，
     * UI 侧静默跳过预测卡片。
     */
    suspend fun getStorageForecast(): StorageForecast? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-forecast") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val forecast = o["forecast"]?.jsonArray?.mapNotNull { item ->
                    val fo = item.jsonObject
                    StorageForecastPoint(
                        monthsAhead = fo["months_ahead"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        predictedBytes = fo["predicted_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                } ?: emptyList()
                StorageForecast(
                    currentBytes = o["current_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    monthlyAverageBytes = o["monthly_average_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    growthRatePercent = o["growth_rate_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    forecast = forecast,
                    quotaBytes = o["quota_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    monthsUntilFull = o["months_until_full"]?.let {
                        // 后端可能返回 null（无上限）或 0（已超配额），两者均视作"无有效预计"
                        if (it is JsonNull) null else it.jsonPrimitive?.intOrNull?.takeIf { v -> v > 0 }
                    }
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageForecast FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V9：存储预测结果。 */
    data class StorageForecast(
        val currentBytes: Long,
        val monthlyAverageBytes: Long,
        val growthRatePercent: Double,
        val forecast: List<StorageForecastPoint>,
        val quotaBytes: Long,
        val monthsUntilFull: Int?
    ) {
        /** 月均增长 MB（保留一位小数由 UI 文字截断处理，遵循 commonMain 无 String.format 约定）。 */
        val monthlyAverageMB: Double get() = monthlyAverageBytes.toDouble() / (1024.0 * 1024.0)

        /** 取指定月数后的预测字节；预测点缺失返回 null。 */
        fun predictedBytes(monthsAhead: Int): Long? =
            forecast.firstOrNull { it.monthsAhead == monthsAhead }?.predictedBytes
    }

    /** V9：存储预测单点（months_ahead → predicted_bytes）。 */
    data class StorageForecastPoint(
        val monthsAhead: Int,
        val predictedBytes: Long
    ) {
        val predictedMB: Double get() = predictedBytes.toDouble() / (1024.0 * 1024.0)
    }

    /**
     * V9：GET /api/media/growth-report — 媒体增长报告。
     *
     * 后端基于 created_at（上传时间）在 Go 侧按 ISO-8601 周边界 / 自然月边界分桶，
     * 返回本周/上周/本月/上月/本年的上传统计（count+bytes）及周环比/月环比增长率。
     * 周边界：本周 = 本周一 00:00 UTC ~ 当前；上周 = 上周一 ~ 本周一。
     * 月边界：本月 = 本月 1 日 00:00 UTC；上月 = 上月 1 日 ~ 本月 1 日。
     * 环比增长率 = (本期-上期)/上期*100；上期为 0 时后端返回 null（避免除零），
     * 前端映射为 [Double.NaN] 以保持非空 Double 类型便于 UI 判断。
     *
     * 字段对齐后端 handleMediaGrowthReport 响应：
     * this_week/last_week/week_change_percent/this_month/last_month/
     * month_change_percent/this_year，每个周期为 {count,bytes}。
     *
     * 非 200 或异常返回 null，UI 侧静默跳过卡片。
     */
    suspend fun getGrowthReport(): GrowthReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/growth-report") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                GrowthReport(
                    thisWeek = parsePeriod(o["this_week"]?.jsonObject),
                    lastWeek = parsePeriod(o["last_week"]?.jsonObject),
                    weekChangePercent = parseNullablePercent(o["week_change_percent"]),
                    thisMonth = parsePeriod(o["this_month"]?.jsonObject),
                    lastMonth = parsePeriod(o["last_month"]?.jsonObject),
                    monthChangePercent = parseNullablePercent(o["month_change_percent"]),
                    thisYear = parsePeriod(o["this_year"]?.jsonObject)
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getGrowthReport FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 解析单个周期统计 {count,bytes}，缺失字段回退 0。 */
    private fun parsePeriod(o: JsonObject?): PeriodStats =
        PeriodStats(
            count = o?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
            bytes = o?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
        )

    /**
     * 解析环比百分比：后端 null（上期为 0，无法计算）映射为 [Double.NaN]，
     * 其余取 double 值。NaN 在 UI 侧按"无对比数据"处理，不显示箭头。
     */
    private fun parseNullablePercent(el: JsonElement?): Double =
        if (el == null || el is JsonNull) Double.NaN
        else el.jsonPrimitive?.doubleOrNull ?: Double.NaN

    /** V9：媒体增长报告（周/月环比 + 本年累计）。 */
    data class GrowthReport(
        val thisWeek: PeriodStats,
        val lastWeek: PeriodStats,
        val weekChangePercent: Double,
        val thisMonth: PeriodStats,
        val lastMonth: PeriodStats,
        val monthChangePercent: Double,
        val thisYear: PeriodStats
    )

    /** V9：单个周期的上传统计。 */
    data class PeriodStats(
        val count: Int,
        val bytes: Long
    ) {
        val mb: Double get() = bytes.toDouble() / (1024.0 * 1024.0)
    }

    /**
     * V9：GET /api/media/yearly-review?year=YYYY — 年度回顾。
     *
     * 后端按年聚合当前用户的媒体上传情况，返回字段：
     * - [total_count] / [total_bytes]：该年上传总项数 / 总字节数
     * - [byMonth]：1~12 月每月上传统计（[MonthCount]），缺失月份 count=0
     * - [byType]：按类型汇总（[TypeCount]）
     * - [firstUpload] / [lastUpload]：该年首/末上传的 RFC3339 时间串（空串表无数据）
     * - [topDay]：该年上传最多的一天（[TopDay]），无数据时 date 空串 count 0
     * - [favorites]：该年收藏数
     *
     * 非 200 或异常返回 null，UI 侧静默跳过卡片。与 [getGrowthReport] 同款解析：
     * 走运行时 [Json.parseToJsonElement]，不依赖 kotlinx.serialization 编译器插件。
     */
    suspend fun getYearlyReview(year: Int = 2026): YearlyReview? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/yearly-review") {
                parameter("year", year)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val monthsArr = o["by_month"]?.jsonArray
                val byMonth = (1..12).map { m ->
                    val mc = monthsArr?.firstOrNull {
                        it.jsonObject["month"]?.jsonPrimitive?.intOrNull == m
                    }?.jsonObject
                    MonthCount(
                        month = m,
                        count = mc?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
                val typeObj = o["by_type"]?.jsonObject
                YearlyReview(
                    year = o["year"]?.jsonPrimitive?.intOrNull ?: year,
                    totalCount = o["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    byMonth = byMonth,
                    byType = TypeCount(
                        image = typeObj?.get("image")?.jsonPrimitive?.intOrNull ?: 0,
                        video = typeObj?.get("video")?.jsonPrimitive?.intOrNull ?: 0,
                        live = typeObj?.get("live")?.jsonPrimitive?.intOrNull
                            ?: typeObj?.get("live_photo")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    firstUpload = o["first_upload"]?.jsonPrimitive?.contentOrNull ?: "",
                    lastUpload = o["last_upload"]?.jsonPrimitive?.contentOrNull ?: "",
                    topDay = o["top_day"]?.jsonObject?.let { td ->
                        TopDay(
                            date = td["date"]?.jsonPrimitive?.contentOrNull ?: "",
                            count = td["count"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } ?: TopDay("", 0),
                    favorites = o["favorites"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getYearlyReview FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V9：年度回顾响应体。 */
    data class YearlyReview(
        val year: Int,
        val totalCount: Int,
        val totalBytes: Long,
        val byMonth: List<MonthCount>,
        val byType: TypeCount,
        val firstUpload: String,
        val lastUpload: String,
        val topDay: TopDay,
        val favorites: Int
    ) {
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /** V9：单月上传统计。 */
    data class MonthCount(val month: Int, val count: Int)

    /** V9：按类型汇总的年度统计。 */
    data class TypeCount(val image: Int, val video: Int, val live: Int) {
        val total: Int get() = image + video + live
    }

    /** V9：年度上传最多的一天。 */
    data class TopDay(val date: String, val count: Int)

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

    /** V8：POST /api/media/batch-restore — 批量恢复，返回成功数。 */
    suspend fun batchRestore(mediaIds: List<String>): Int {
        if (mediaIds.isEmpty()) return 0
        return try {
            val body = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
            })
            val response: HttpResponse = jsonClient.post("${rnBackendBaseUrl()}/api/media/batch-restore") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val respBody: String = response.body()
                val obj = Json.parseToJsonElement(respBody).jsonObject
                obj["restored_count"]?.jsonPrimitive?.intOrNull ?: 0
            } else 0
        } catch (e: Exception) {
            logger.error("MediaService", "batchRestore failed: ${e.message}")
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

    /**
     * 批量分享结果项（与后端 batch-share 响应 `links` 元素对齐）。
     *
     * 后端为每个 media_id 各自生成一条分享记录，返回 token + 可访问 url。
     */
    data class BatchShareResult(
        val mediaId: String,
        val token: String,
        val url: String
    )

    /**
     * POST /api/media/batch-share — 为多个媒体各自创建分享链接。
     *
     * 请求体 `{ "media_ids": [...] }`；响应
     * `{ "links": [{ "media_id","token","url" }], "created_count" }`。
     *
     * 与 [createShareLink] 区别：后者为多个媒体创建**单条**聚合分享链接（一个 token
     * 指向一组媒体）；本方法为每个媒体**各自**生成独立链接（N 个 token）。
     *
     * @return 生成的链接列表（可能为空表示后端未创建任何链接）；网络/HTTP 失败返回 null，
     *         由调用方按空状态降级提示。
     */
    suspend fun batchShare(mediaIds: List<String>): List<BatchShareResult>? {
        if (mediaIds.isEmpty()) return null
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-share") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_ids", Json.encodeToJsonElement(mediaIds))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["links"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val token = o["token"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = o["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    BatchShareResult(mediaId, token, url)
                }
            } else {
                logger.info("MediaService", "batchShare status=${response.status} (non-OK)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchShare failed: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ---- V8 审计日志 API ----

    /** V8：审计日志条目（与后端 audit_log 表对齐）。 */
    data class AuditLogEntry(
        val id: Long,
        val action: String,
        val mediaId: String,
        val detail: String,
        val createdAt: String
    )

    /** V8：审计日志统计项（按操作类型汇总计数）。 */
    data class AuditLogStat(val action: String, val count: Int)

    /**
     * V8：GET /api/media/audit-log/list — 操作历史。
     *
     * 返回最近 [limit] 条审计记录。后端约定响应体：
     * `{ "logs": [{ "id","action","media_id","detail","created_at" }] }`。
     * 失败返回 null，调用方按空状态展示。
     */
    suspend fun getAuditLogs(limit: Int = 50): List<AuditLogEntry>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/audit-log/list") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["logs"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AuditLogEntry(
                        id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getAuditLogs FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/audit-log/by-media?media_id=xxx — 单个媒体操作历史。
     *
     * 返回指定媒体的审计记录（按时间倒序）。后端约定响应体：
     * `{ "logs": [{ "id","action","media_id","detail","created_at" }], "total": N }`。
     * 解析模式与 [getAuditLogs] 一致，仅查询参数不同。失败返回 null，
     * 调用方（MediaInfoDialog）按空状态展示。
     */
    suspend fun getAuditLogsByMedia(mediaId: String): List<AuditLogEntry>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/audit-log/by-media") {
                parameter("media_id", mediaId)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["logs"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AuditLogEntry(
                        id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getAuditLogsByMedia FAILED mediaId=$mediaId: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/audit-log/stats — 操作统计。
     *
     * 返回各操作类型的累计计数。后端约定响应体：
     * `{ "stats": [{ "action","count" }] }`。
     * 失败返回 null，设置页按空状态展示。
     */
    suspend fun getAuditLogStats(): List<AuditLogStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/audit-log/stats") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["stats"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AuditLogStat(
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getAuditLogStats FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：GET /api/media/storage-breakdown — 按类型分组的存储统计。
     *
     * 后端响应：`{ "by_type": {"IMAGE":{count,bytes}, "VIDEO":{...}, "LIVE_PHOTO":{...}},
     * "by_month": [...], "total":{count,bytes,mb} }`。前端只消费 by_type + total，
     * 拍平为 [StorageBreakdown] 便于设置页逐行展示。失败返回 null，设置页按空状态展示。
     */
    suspend fun getStorageBreakdown(): StorageBreakdown? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-breakdown") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val byType = o["by_type"]?.jsonObject
                fun pick(key: String): Pair<Int, Long> {
                    val t = byType?.get(key)?.jsonObject
                    val c = t?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    val b = t?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
                    return c to b
                }
                val (ic, ib) = pick("IMAGE")
                val (vc, vb) = pick("VIDEO")
                val (lc, lb) = pick("LIVE_PHOTO")
                val total = o["total"]?.jsonObject
                StorageBreakdown(
                    imageCount = ic,
                    imageBytes = ib,
                    videoCount = vc,
                    videoBytes = vb,
                    liveCount = lc,
                    liveBytes = lb,
                    totalCount = total?.get("count")?.jsonPrimitive?.intOrNull ?: (ic + vc + lc),
                    totalBytes = total?.get("bytes")?.jsonPrimitive?.longOrNull ?: (ib + vb + lb)
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageBreakdown FAILED: ${e.message}")
            null
        }
    }

    /** V8：存储分析结果（by_type 拍平 + total）。 */
    data class StorageBreakdown(
        val imageCount: Int,
        val imageBytes: Long,
        val videoCount: Int,
        val videoBytes: Long,
        val liveCount: Int,
        val liveBytes: Long,
        val totalCount: Int,
        val totalBytes: Long
    )

    /**
     * V15：GET /api/media/storage-recommendations — 存储清理建议。
     *
     * 后端一次拉取用户全部媒体，从重复/大文件/旧文件/孤立四个维度分析可回收空间。响应：
     * ```
     * { "duplicates": {"count","reclaimable_bytes"},
     *   "large_files": [{"media_id","filename","size"}],
     *   "old_files": {"count","bytes"},
     *   "orphans": {"count","bytes"},
     *   "total_reclaimable_bytes": N,
     *   "recommendation_count": N }
     * ```
     * 前端消费 duplicates + large_files + old_files + total_reclaimable_bytes，
     * 拍平为 [StorageRecommendations] 供设置页"清理建议"卡片展示。orphans 字段后端
     * 返回但前端暂不展示（手动解析忽略即可）。失败返回 null，调用方按空态处理。
     */
    suspend fun getStorageRecommendations(): StorageRecommendations? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-recommendations") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val dup = o["duplicates"]?.jsonObject
                val old = o["old_files"]?.jsonObject
                val larges = o["large_files"]?.jsonArray?.map { lf ->
                    val lo = lf.jsonObject
                    LargeFile(
                        mediaId = lo["media_id"]?.jsonPrimitive?.content ?: "",
                        filename = lo["filename"]?.jsonPrimitive?.content ?: "",
                        size = lo["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                } ?: emptyList()
                StorageRecommendations(
                    duplicates = DupInfo(
                        count = dup?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        reclaimableBytes = dup?.get("reclaimable_bytes")?.jsonPrimitive?.longOrNull ?: 0L
                    ),
                    largeFiles = larges,
                    oldFiles = OldInfo(
                        count = old?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = old?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
                    ),
                    totalReclaimableBytes = o["total_reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    recommendationCount = o["recommendation_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageRecommendations FAILED: ${e.message}")
            null
        }
    }

    /**
     * V8：POST /api/media/duplicate-cleanup — 一键清理重复媒体。
     *
     * 后端按 SHA256 分组，每组保留最早的，其余软删（置 deleted 标志）。响应：
     * `{ "status","groups_found","deleted_count","deleted":[{media_id,filename,sha256}] }`。
     *
     * @return 成功删除的重复文件数；失败（网络/HTTP 非 200）返回 null，调用方提示重试。
     */
    suspend fun cleanupDuplicates(): Int? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/duplicate-cleanup") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val deleted = o["deleted_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "cleanupDuplicates deleted=$deleted")
                deleted
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "cleanupDuplicates FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V15：storage-recommendations 重复文件子统计。 */
    data class DupInfo(
        val count: Int,
        val reclaimableBytes: Long
    )

    /** V15：storage-recommendations 大文件条目。 */
    data class LargeFile(
        val mediaId: String,
        val filename: String,
        val size: Long
    )

    /** V15：storage-recommendations 旧文件子统计。 */
    data class OldInfo(
        val count: Int,
        val bytes: Long
    )

    /** V15：storage-recommendations 汇总结果（重复/大文件/旧文件 + 总可回收字节）。 */
    data class StorageRecommendations(
        val duplicates: DupInfo,
        val largeFiles: List<LargeFile>,
        val oldFiles: OldInfo,
        val totalReclaimableBytes: Long,
        val recommendationCount: Int
    )

    /**
     * V16：GET /api/media/duplicate-report — 重复文件详细报告（仅报告，不删除）。
     *
     * 与 [getStorageRecommendations] 的 duplicates 子统计不同，本端点返回每组重复的
     * 完整媒体条目（id/filename/size/created_at），用于设置页「查看重复详情」Dialog
     * 展示具体文件名与可回收空间。响应结构（与后端 [handleMediaDuplicateReport] 对齐）：
     * ```
     * { "duplicates": [{ "sha256", "count", "media": [{ "id","filename","size","created_at" }],
     *                     "reclaimable_bytes" }],
     *   "total_groups": N,
     *   "total_reclaimable_bytes": N,
     *   "total_duplicate_count": N }
     * ```
     * 复用既有 [DuplicateMedia] / [DuplicateGroup]（V7 getDuplicates 同款）；后端不返回
     * media.type，置空串。组的 [DuplicateGroup.size] 存 reclaimable_bytes（可回收字节），
     * 与 [getDuplicates] 中 size 语义一致（均为浪费/可回收空间）。失败返回 null。
     */
    suspend fun getDupReport(): DupReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/duplicate-report") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val dups = o["duplicates"]?.jsonArray?.map { g ->
                    val go = g.jsonObject
                    val media = go["media"]?.jsonArray?.map { m ->
                        val mo = m.jsonObject
                        DupReportMedia(
                            id = mo["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            filename = mo["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                            size = mo["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            sha256 = go["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
                            type = mo["type"]?.jsonPrimitive?.contentOrNull ?: "",
                            createdAt = mo["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    } ?: emptyList()
                    DupReportGroup(
                        sha256 = go["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = go["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        size = go["reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        media = media
                    )
                } ?: emptyList()
                DupReport(
                    duplicates = dups,
                    totalGroups = o["total_groups"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalReclaimableBytes = o["total_reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    totalDuplicateCount = o["total_duplicate_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getDupReport FAILED: ${e.message}")
            null
        }
    }

    /** V16：duplicate-report 汇总（重复分组列表 + 全局总计）。复用 [DuplicateGroup]。 */
    data class DupReport(
        val duplicates: List<DupReportGroup>,
        val totalGroups: Int,
        val totalReclaimableBytes: Long,
        val totalDuplicateCount: Int
    )

    /**
     * V9：GET /api/media/stat-summary — 一站式统计汇总。
     *
     * 单次请求合并"我的"Tab 多个卡片所需的最常用统计（summary / tags / audit / quota /
     * favorites / shares / albums / trash / recent_uploads），避免逐卡片多次调用。
     *
     * 后端响应结构（各子统计 best-effort：单条 Store 调用失败仅令对应字段为空/null）：
     * ```
     * { "summary": {total_count, total_bytes, image_count, video_count, live_count},
     *   "tags":     [{tag, count}],      // top 5
     *   "audit":    [{action, count}],
     *   "quota":    {quota_bytes, used_bytes, usage_percent},
     *   "favorites": N,
     *   "shares":    N,
     *   "albums":    N,
     *   "trash":     N,
     *   "recent_uploads": [{id, filename, type, created_at}]  // top 3
     * }
     * ```
     *
     * summary 子对象在后端 ListMediaByUser 失败时为 null，前端按 [SummaryData] 默认 0 容错；
     * tags / audit 数组缺失或解析失败回退空列表；recent_uploads 同理。这样 UI 侧永远拿到
     * 一个非 null 的 [StatSummary]，按各字段是否为默认值/空列表自行渲染空态。
     *
     * @return 汇总对象；HTTP 非 200 或网络异常返回 null（调用方按空态提示）
     */
    suspend fun getStatSummary(): StatSummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/stat-summary") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // summary：可能为 null（后端 ListMediaByUser 失败），回退零值对象。
                val s = o["summary"]?.jsonObject
                StatSummary(
                    summary = SummaryData(
                        totalCount = s?.get("total_count")?.jsonPrimitive?.intOrNull ?: 0,
                        totalBytes = s?.get("total_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                        imageCount = s?.get("image_count")?.jsonPrimitive?.intOrNull ?: 0,
                        videoCount = s?.get("video_count")?.jsonPrimitive?.intOrNull ?: 0,
                        liveCount = s?.get("live_count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    tags = o["tags"]?.jsonArray?.mapNotNull { item ->
                        val t = item.jsonObject
                        val name = t["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        TagStat(
                            tag = name,
                            count = t["count"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } ?: emptyList(),
                    audit = o["audit"]?.jsonArray?.mapNotNull { item ->
                        val a = item.jsonObject
                        val action = a["action"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        AuditLogStat(
                            action = action,
                            count = a["count"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } ?: emptyList(),
                    quota = run {
                        val q = o["quota"]?.jsonObject
                        QuotaData(
                            quotaBytes = q?.get("quota_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                            usedBytes = q?.get("used_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                            usagePercent = q?.get("usage_percent")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        )
                    },
                    favorites = o["favorites"]?.jsonPrimitive?.intOrNull ?: 0,
                    shares = o["shares"]?.jsonPrimitive?.intOrNull ?: 0,
                    albums = o["albums"]?.jsonPrimitive?.intOrNull ?: 0,
                    trash = o["trash"]?.jsonPrimitive?.intOrNull ?: 0,
                    recentUploads = o["recent_uploads"]?.jsonArray?.mapNotNull { item ->
                        val ru = item.jsonObject
                        val id = ru["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        RecentUpload(
                            id = id,
                            filename = ru["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                            type = ru["type"]?.jsonPrimitive?.contentOrNull ?: "",
                            size = ru["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            createdAt = ru["created_at"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    } ?: emptyList()
                )
            } else {
                logger.info("MediaService", "getStatSummary status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStatSummary FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V9：stat-summary 的汇总计数（媒体总数与按类型拆分）。 */
    data class SummaryData(
        val totalCount: Int,
        val totalBytes: Long,
        val imageCount: Int,
        val videoCount: Int,
        val liveCount: Int
    )

    /** V9：stat-summary 的配额子对象（总配额/已用/百分比）。 */
    data class QuotaData(
        val quotaBytes: Long,
        val usedBytes: Long,
        val usagePercent: Double
    )

    /** V9：stat-summary 一站式汇总结果。各子统计 best-effort，缺失字段回退零值/空列表。 */
    data class StatSummary(
        val summary: SummaryData,
        val tags: List<TagStat>,
        val audit: List<AuditLogStat>,
        val quota: QuotaData,
        val favorites: Int,
        val shares: Int,
        val albums: Int,
        val trash: Int,
        val recentUploads: List<RecentUpload>
    )

    /**
     * V21：GET /api/media/full-report?year=YYYY — 综合报告（原始 JSON 字符串）。
     *
     * 后端把 quick_stats / yearly / storage / tags / pattern / duplicates 合并为一次请求；
     * 前端无需逐字段解析，仅在设置页"导出报告"以 JSON 文本展示，供用户复制/分享。
     *
     * 返回 pretty 化后的 JSON 字符串（2 空格缩进，便于阅读）；非 200 或网络异常返回 null，
     * UI 侧提示"导出失败"。与 [exportTags] 同款直返 body 字符串，额外做一次格式化。
     *
     * @param year 年度筛选（默认 2026，透传 query param；后端按年聚合 yearly 子块）
     */
    suspend fun getFullReport(year: Int = 2026): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/full-report") {
                parameter("year", year)
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                logger.info(
                    "MediaService",
                    "getFullReport year=$year status=${response.status} bytes=${body.length}"
                )
                // pretty 化：重新格式化便于阅读与复制。解析失败（非合法 JSON）原样返回。
                // kotlinx-serialization-json 1.7.x 的 JsonBuilder 属性名为 prettyPrintIndent。
                runCatching {
                    val pretty = Json {
                        prettyPrint = true
                        prettyPrintIndent = "  "
                        ignoreUnknownKeys = true
                    }
                    val element = pretty.parseToJsonElement(body)
                    pretty.encodeToString(JsonElement.serializer(), element)
                }.getOrDefault(body)
            } else {
                logger.info("MediaService", "getFullReport status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getFullReport FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V22：GET /api/media/storage-health — 存储健康度评估。
     *
     * 后端综合重复率、配额占用、数据新鲜度（age）算出一个 0-100 的 [StorageHealth.score]
     * 与字母等级 [StorageHealth.grade]（A/B/C/D），并给出可操作 [StorageHealth.suggestions]。
     * 设置页"存储健康度"卡片据此渲染：大字号评分+等级、3 条比例条、建议列表。
     *
     * 后端响应结构：
     * ```
     * { "score": 82, "grade": "B",
     *   "duplicate_rate": 0.12,   // 0.0-1.0
     *   "quota_usage": 0.65,      // 0.0-1.0
     *   "age_score": 0.30,        // 0.0-1.0（越低越"冷"）
     *   "suggestions": ["...", "..."] }
     * ```
     *
     * 解析宽容：缺字段回退零值/空列表，保证 UI 永不崩。HTTP 非 200 或网络异常返回 null，
     * 调用方按空态提示"无法获取存储健康度"。鉴权头由 defaultRequest 统一注入，此处不再
     * 重复附加（与 [getStatSummary] 同款）。
     *
     * @return 健康度对象；失败返回 null
     */
    suspend fun getStorageHealth(): StorageHealth? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-health")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                StorageHealth(
                    score = o["score"]?.jsonPrimitive?.intOrNull ?: 0,
                    grade = o["grade"]?.jsonPrimitive?.contentOrNull ?: "",
                    duplicateRate = o["duplicate_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    quotaUsage = o["quota_usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    ageScore = o["age_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    suggestions = o["suggestions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                )
            } else {
                logger.info("MediaService", "getStorageHealth status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageHealth FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V22：storage-health 响应体。
     *
     * @param score 0-100 健康度评分（越高越好）
     * @param grade 字母等级 A/B/C/D（后端给定，前端按首字母着色）
     * @param duplicateRate 重复率 0.0-1.0
     * @param quotaUsage 配额占用 0.0-1.0
     * @param ageScore 数据温度 0.0-1.0（越低越冷/越陈旧）
     * @param suggestions 改善建议文本列表
     */
    data class StorageHealth(
        val score: Int,
        val grade: String,
        val duplicateRate: Double,
        val quotaUsage: Double,
        val ageScore: Double,
        val suggestions: List<String>
    )

    /**
     * V23：GET /api/media/media-coverage — 媒体覆盖率分析。
     *
     * 后端按 4 个维度统计媒体覆盖情况：已标签、已收藏、已分享、在相册；并给出未标签数。
     * 设置页"媒体覆盖率"卡片据此渲染 4 行进度条（标签/收藏/分享/相册）。
     *
     * 后端响应结构：
     * ```
     * { "total": 1234,
     *   "tagged":     { "count": 800,  "percent": 64.8 },
     *   "favorited":  { "count": 120,  "percent": 9.7 },
     *   "shared":     { "count": 45,   "percent": 3.6 },
     *   "in_album":   { "count": 300,  "percent": 24.3 },
     *   "untagged":   { "count": 434,  "percent": 35.2 } }
     * ```
     *
     * 解析宽容：缺字段回退零值（count=0 / percent=0.0），保证 UI 永不崩。
     * HTTP 非 200 或网络异常返回 null，调用方按空态提示"无法获取媒体覆盖率"。
     * 鉴权头由 defaultRequest 统一注入（与 [getStorageHealth] 同款，此处不重复附加）。
     *
     * @return 覆盖率对象；失败返回 null
     */
    suspend fun getMediaCoverage(): MediaCoverage? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-coverage")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                fun parseItem(key: String): CoverageItem {
                    val item = o[key]?.jsonObject
                    return CoverageItem(
                        count = item?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        percent = item?.get("percent")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
                MediaCoverage(
                    total = o["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    tagged = parseItem("tagged"),
                    favorited = parseItem("favorited"),
                    shared = parseItem("shared"),
                    inAlbum = parseItem("in_album"),
                    untagged = parseItem("untagged")
                )
            } else {
                logger.info("MediaService", "getMediaCoverage status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaCoverage FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V23：media-coverage 响应体。
     *
     * @param total 媒体总数
     * @param tagged 已标签维度
     * @param favorited 已收藏维度
     * @param shared 已分享维度
     * @param inAlbum 在相册维度
     * @param untagged 未标签维度（剩余参考）
     */
    data class MediaCoverage(
        val total: Int,
        val tagged: CoverageItem,
        val favorited: CoverageItem,
        val shared: CoverageItem,
        val inAlbum: CoverageItem,
        val untagged: CoverageItem
    )

    /**
     * V23：单维度覆盖率。
     *
     * @param count 已覆盖计数
     * @param percent 百分比（0.0-100.0，非小数）
     */
    data class CoverageItem(
        val count: Int,
        val percent: Double
    )

    /**
     * V23：GET /api/media/insights — 智能洞察。
     *
     * 后端聚合多种自动分析（重复文件、存储占用、上传习惯、未标签、相册整理、
     * 健康度等），返回一组带类型标签的建议。设置页"智能洞察"卡片据此渲染：每条
     * 按 [Insight.type] 匹配 emoji，展示 [Insight.title] + [Insight.detail]。
     *
     * 后端响应结构（即将提供，当前端点尚未上线）：
     * ```
     * { "insights": [
     *     { "type": "duplicate", "title": "发现 12 组重复媒体", "detail": "可释放 240 MB", "action_url": "/media/duplicates" },
     *     { "type": "storage",   "title": "...",               "detail": "...",           "action_url": null },
     *     ...
     *   ],
     *   "total": 5 }
     * ```
     *
     * 解析宽容：缺字段回退空串，[Insight.actionUrl] 可空（type 不带 action_url 时为 null）。
     * HTTP 非 200 或网络异常返回 null，调用方按空态提示"无法获取智能洞察"。
     * 鉴权头由 defaultRequest 统一注入（与 [getStorageHealth] 同款，此处不重复附加）。
     *
     * @return 洞察列表；失败返回 null
     */
    suspend fun getMediaInsights(): List<Insight>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/insights")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["insights"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val type = item["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Insight(
                        type = type,
                        title = item["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = item["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        actionUrl = item["action_url"]?.let {
                            if (it is JsonNull) null else it.jsonPrimitive?.contentOrNull
                        }
                    )
                }
            } else {
                logger.info("MediaService", "getMediaInsights status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaInsights FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V23：insights 响应体单条洞察。
     *
     * @param type 洞察类型：duplicate（重复）/ storage（存储）/ habit（习惯）/
     *             untagged（未标签）/ album（相册）/ health（健康度）；前端按类型映射 emoji
     * @param title 标题（一句话概要）
     * @param detail 详情（量化说明，如"可释放 240 MB"）
     * @param actionUrl 可选的跳转路径（null 表示无快捷动作）
     */
    data class Insight(
        val type: String,
        val title: String,
        val detail: String,
        val actionUrl: String? = null
    )
}
