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
 * PRD-v10 §4.1：返回 WebSocket 实时同步端点 URL（ws/wss）。
 *
 * 把当前 [backendBaseUrl] 的 http→ws / https→wss 改写后拼上 /api/sync/ws，
 * 并把 [token] 作为 query ?token= 透传——浏览器/原生 WebSocket 无法带自定义
 * Authorization 头，故后端 handleSyncWS 支持从 query 取 token 校验 JWT。
 *
 * token 为空时仍返回 URL（不含 query），由调用方保证仅登录态连接。供
 * [com.wgt.media.SyncWebSocket] 握手用。
 */
fun syncWsUrl(token: String): String {
    val base = backendBaseUrl()
    val wsScheme = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
        else -> "ws://$base"
    }
    return if (token.isNotEmpty()) "$wsScheme/api/sync/ws?token=$token" else "$wsScheme/api/sync/ws"
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
    // 上架前 UX 打磨：网络超时保护。
    // 此前 HttpClient 未配置任何超时，弱网/后端无响应时请求会无限挂起，UI 永久卡在 loading。
    // - connectTimeout 10s：TCP 连接建立上限（覆盖 DNS+握手）。超过则抛 ConnectTimeoutException，
    //   由各调用方 catch 后展示「无法连接服务器」友好提示。
    // - requestTimeout 20s：整个请求（含连接+收发）上限。大文件流式下载（getMediaStream）可能超此值，
    //   但媒体流走单独的 stream 端点且已在调用侧做容错；常规 list/auth/rotate 等小请求 20s 足够。
    // - socketTimeout 15s：单次读写空闲上限，防止连接建立后服务端挂起不回包。
    // 三个值均 ≤20s，保证任何失败路径在 20s 内有可感知的错误反馈，不会黑屏/白屏卡死。
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 15_000
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

    // ============ 相关媒体推荐 ============

    /**
     * 相关媒体条目（与后端 [handleMediaRelated] 的 relatedItem JSON 对齐）。
     *
     * 后端按 `相同标签`（reason="shared_tag:&lt;tag&gt;"）与 `相同类型+相近日期`（reason="same_type_nearby_date"）
     * 两种策略推荐相关媒体；前端仅消费基础信息，[reason] 可选展示。
     *
     * @param mediaId 媒体 ID
     * @param filename 文件名
     * @param type 媒体类型字符串（"IMAGE"/"VIDEO"/"LIVE_PHOTO"）
     * @param reason 推荐原因（shared_tag:&lt;tag&gt; 或 same_type_nearby_date）
     */
    data class RelatedMedia(
        val mediaId: String,
        val filename: String,
        val type: String,
        val reason: String
    )

    /**
     * GET /api/media/media-related/{id}?limit=10 — 获取相关媒体推荐列表。
     *
     * 后端基于相同标签（优先）与相同类型+相近日期（±7天）两种策略推荐相关媒体，
     * 去重并截断到 [limit]。响应：`{ "related": [{media_id,filename,type,reason}], "total": N }`。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getStatSummary] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示推荐区）。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param mediaId 目标媒体 ID
     * @param limit 返回上限（默认 10，后端上限 50）
     * @return 相关媒体列表；失败返回 null
     */
    suspend fun getRelatedMedia(mediaId: String, limit: Int = 10): List<RelatedMedia>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-related/$mediaId") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["related"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val id = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    RelatedMedia(
                        mediaId = id,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = item["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        reason = item["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getRelatedMedia id=$mediaId status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getRelatedMedia FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相似媒体条目（与后端 [handleMediaSimilar] 的 similarItem JSON 对齐）。
     *
     * 后端按"看起来像"——同类型 + 文件大小差距 <20% + 分辨率差距 <30%——推荐相似媒体，
     * 与 [RelatedMedia]（基于标签/日期）互补。[similarityScore] 为 0~100（100 最像）。
     *
     * @param mediaId 媒体 ID
     * @param filename 文件名
     * @param similarityScore 相似度评分 0~100（100 最像）
     */
    data class SimilarMedia(
        val mediaId: String,
        val filename: String,
        val similarityScore: Double
    )

    /**
     * GET /api/media/media-similar/{id}?limit=10 — 获取相似媒体推荐列表。
     *
     * 后端基于物理特征（同类型 + size 差距 <20% + 分辨率差距 <30%）推荐"看起来像"的媒体，
     * 返回 `{ "similar": [{media_id,filename,similarity_score}], "total": N }`，similarity_score 为
     * 0~100（100 最像）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getRelatedMedia] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示推荐区）。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param mediaId 目标媒体 ID
     * @param limit 返回上限（默认 10，后端上限 50）
     * @return 相似媒体列表；失败返回 null
     */
    suspend fun getSimilarMedia(mediaId: String, limit: Int = 10): List<SimilarMedia>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-similar/$mediaId") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["similar"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val id = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SimilarMedia(
                        mediaId = id,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        similarityScore = item["similarity_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else {
                logger.info("MediaService", "getSimilarMedia id=$mediaId status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getSimilarMedia FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
            // 友好错误提示：区分「连接超时」「其他网络异常」，避免用户看到裸异常类名。
            // 超时类异常（ConnectTimeout/SocketTimeout/HttpRequestTimeout）统一提示「连接超时」，
            // 其余连接失败仍走「无法连接服务器」+ 简短原因。
            val friendly = when {
                e::class.simpleName?.contains("Timeout", ignoreCase = true) == true -> "连接超时，请检查网络或后端地址后重试"
                else -> "无法连接服务器：${e.message ?: e::class.simpleName}"
            }
            AuthOutcome(success = false, error = friendly)
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
    ): List<MediaMetadata> = getMediaListPaged(page, pageSize, source, cloud).list

    /**
     * 分页版 [getMediaList]，返回 [PagedMediaList]（含 totalCount / hasMore）。
     *
     * 后端 `/api/media/list` 响应已包含 `total_count`/`page`/`page_size` 字段。
     * 本方法解析这些字段，计算 `hasMore = page * pageSize < totalCount`，供前端
     * 无限滚动判断是否继续拉取下一页。JSON 缺失 total_count 时回退 0（hasMore=false）。
     *
     * 网盘场景（cloud=true）出错时不回退 mock，直接抛出；其余场景回退 mock
     * （PagedMediaList 无 hasMore，避免假数据触发无限滚动）。
     */
    suspend fun getMediaListPaged(
        page: Int = 1,
        pageSize: Int = 20,
        source: MediaSource = MediaSource.BACKEND,
        cloud: Boolean = false
    ): PagedMediaList {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/list") {
                parameter("page", page)
                parameter("page_size", pageSize)
                if (cloud) parameter("q", "source=cloud")
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val (list, totalCount) = parseMediaListPaged(body)
                logger.info(
                    "MediaService",
                    "getMediaListPaged cloud=$cloud page=$page status=${response.status} parsed=${list.size} total=$totalCount"
                )
                PagedMediaList(
                    list = list,
                    page = page,
                    pageSize = pageSize,
                    totalCount = totalCount,
                    hasMore = page * pageSize < totalCount
                )
            } else {
                // 网盘场景不回退 mock，保证 UI 真实性
                if (cloud) throw RuntimeException("后端返回 ${response.status}")
                PagedMediaList(mockMediaList(page), page, pageSize, 0, false)
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaListPaged FAILED cloud=$cloud: ${e::class.simpleName} ${e.message}")
            if (cloud) {
                // 友好错误消息：超时类异常给「连接超时」提示，其余给「无法连接后端」+ 原因。
                // ViewModel 据此设置 listLoadError，驱动 ErrorStateView 展示可重试占位。
                val msg = when {
                    e::class.simpleName?.contains("Timeout", ignoreCase = true) == true -> "连接超时，请检查网络后重试"
                    else -> "无法连接后端：${e.message ?: e::class.simpleName}"
                }
                throw RuntimeException(msg, e)
            }
            PagedMediaList(mockMediaList(page), page, pageSize, 0, false)
        }
    }

    /** 分页媒体列表结果，含分页元数据。 */
    data class PagedMediaList(
        val list: List<MediaMetadata>,
        val page: Int,
        val pageSize: Int,
        val totalCount: Int,
        val hasMore: Boolean
    )

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

    /** GeoCluster — 照片地理聚类点。 */
    data class GeoCluster(
        val lat: Double,
        val lng: Double,
        val count: Int,
        val thumbMediaId: String,
        val thumbUrl: String
    )

    /**
     * GET /api/media/geo-clusters — 照片 GPS 位置聚类。
     *
     * 后端按 haversine 500m 半径聚类，返回 [{lat,lng,count,thumb_media_id,thumb_url}]。
     * 供照片地图视图展示。
     */
    suspend fun getGeoClusters(): List<GeoCluster>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/geo-clusters")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["clusters"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    GeoCluster(
                        lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                        lng = o["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        thumbMediaId = o["thumb_media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        thumbUrl = o["thumb_url"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getGeoClusters FAILED: ${e::class.simpleName} ${e.message}")
            null
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
     * 智能搜索结果（与后端 `media-smart-search` JSON 对齐）。
     *
     * 后端返回 `{ "results": [...], "total": N, "parsed_query": "..." }`，
     * 其中 `results` 复用 [advancedSearch] 同款 `storage.Media` 结构体序列化
     * （`mime` / `created_at` 为 RFC3339），故 [results] 由共享 [parseMediaItem] 解析。
     *
     * @param results 命中的媒体列表（已解析为 [MediaMetadata]）
     * @param total 后端报告的总命中数（可能大于 [results].size，受 limit 截断）
     * @param parsedQuery 后端把自然语言解析后的结构化查询描述，供 UI 展示
     *                    “理解为：xxx”，增加透明度。后端缺失时为空串。
     */
    data class SmartSearchResult(
        val results: List<MediaMetadata>,
        val total: Int,
        val parsedQuery: String
    )

    /**
     * 智能搜索 —— GET /api/media/media-smart-search?q=去年夏天的视频。
     *
     * 与 [advancedSearch] 区别：本端点接受**自然语言查询**（如“去年夏天的视频”、
     * “大于 10MB 的图片”），由后端 LLM/规则解析为结构化条件后查库，无需前端拼参数。
     * 响应顶层为 `results`（非 `media`），并多 `parsed_query` 字段供 UI 透明展示解析结果。
     *
     * 媒体项字段口径与 [advancedSearch] 一致（直接序列化 `storage.Media`），故复用
     * [parseMediaItem]。HTTP 非 200 或网络异常返回 null，调用方按空态提示。
     * 鉴权头由 defaultRequest 统一注入。
     *
     * @param query 自然语言查询串（原样 URL 编码由 Ktor 处理）
     * @param limit 返回上限（默认 100，与 [advancedSearch] 默认一致）
     * @return 解析后的智能搜索结果；失败返回 null
     */
    suspend fun getMediaSmartSearch(query: String, limit: Int = 100): SmartSearchResult? {
        val q = query.trim()
        if (q.isEmpty()) return SmartSearchResult(emptyList(), 0, "")
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-smart-search") {
                parameter("q", q)
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val list = obj["results"]?.jsonArray?.map { item ->
                    parseMediaItem(item.jsonObject)
                } ?: emptyList()
                val total = obj["total"]?.jsonPrimitive?.intOrNull ?: list.size
                val parsedQuery = obj["parsed_query"]?.jsonPrimitive?.contentOrNull ?: ""
                logger.info(
                    "MediaService",
                    "getMediaSmartSearch q=$q status=${response.status} parsed=${list.size} total=$total"
                )
                SmartSearchResult(list, total, parsedQuery)
            } else {
                logger.info("MediaService", "getMediaSmartSearch q=$q status=${response.status} (non-OK)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSmartSearch FAILED q=$q: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ---- 全文搜索 ----

    /**
     * 全文搜索结果（与后端 `media-full-text-search` JSON 对齐）。
     *
     * 后端返回 `{ "results": [...], "total": N, "query_info": {...} }`，
     * 其中 `results` 与 [advancedSearch] / [getMediaSmartSearch] 同款直接序列化
     * `storage.Media` 结构体（`mime` / RFC3339 时间），故 [results] 复用 [parseMediaItem]。
     *
     * [queryInfo] 暴露后端对本次查询的元信息：是否启用了类型/位置筛选，以及位置筛选
     * 是否因后端无地理位置索引而不可用（[QueryInfo.locationFilterUnavailable]）——前端据此
     * 给用户“位置筛选不可用”的提示，避免静默丢失条件。
     *
     * @param results 命中的媒体列表（已解析为 [MediaMetadata]）
     * @param total 后端报告的总命中数（可能大于 [results].size，受 limit 截断）
     * @param queryInfo 查询元信息
     */
    data class FullTextSearchResult(
        val results: List<MediaMetadata>,
        val total: Int,
        val queryInfo: QueryInfo
    )

    /**
     * 全文搜索查询元信息（后端 `query_info` 字段）。
     *
     * @param q 原始查询串（回显）
     * @param locationFilter 是否施加了位置筛选（location/radius 非空且后端支持）
     * @param typeFilter 是否施加了类型筛选
     * @param locationFilterUnavailable 位置筛选不可用标志：后端无地理位置索引时为 true，
     *        此时 [locationFilter] 为 false。前端据此提示“位置筛选不可用”。缺失时为 null。
     */
    data class QueryInfo(
        val q: String,
        val locationFilter: Boolean,
        val typeFilter: Boolean,
        val locationFilterUnavailable: Boolean? = null
    )

    /**
     * 全文搜索 —— GET /api/media/media-full-text-search。
     *
     * 与 [advancedSearch]（多条件字段过滤）/ [getMediaSmartSearch]（自然语言）区别：
     * 本端点对媒体文件名、描述、标签等文本字段做**全文检索**（后端 FTS/LIKE），
     * 并可选叠加位置（经纬度 + 半径）与类型筛选。当后端未建地理位置索引时，
     * 位置筛选降级为不可用，经 [QueryInfo.locationFilterUnavailable] 透传给前端。
     *
     * 响应顶层为 `results`（与 [getMediaSmartSearch] 一致，非 `media`），媒体项字段口径
     * 复用 [parseMediaItem]（直接序列化 `storage.Media`）。HTTP 非 200 或网络异常返回 null，
     * 调用方按空态提示。鉴权头由 defaultRequest 统一注入。
     *
     * @param q 关键词（原样 URL 编码由 Ktor 处理）；空串直接返回空结果。
     * @param location 位置筛选，形如 `lat,lon`；null 表示不限位置。
     * @param radius 位置筛选半径（米）；仅 [location] 非空时有效。
     * @param type 类型筛选：`IMAGE` / `VIDEO`（与后端口径一致）；null 表示不限类型。
     * @param limit 返回上限（默认 100）。
     * @return 解析后的全文搜索结果；失败返回 null。
     */
    suspend fun getMediaFullTextSearch(
        q: String,
        location: String? = null,
        radius: Int? = null,
        type: String? = null,
        limit: Int = 100
    ): FullTextSearchResult? {
        val query = q.trim()
        if (query.isEmpty()) return FullTextSearchResult(emptyList(), 0, QueryInfo("", false, false, null))
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-full-text-search") {
                parameter("q", query)
                if (!location.isNullOrBlank()) parameter("location", location)
                if (radius != null && radius > 0) parameter("radius", radius)
                if (!type.isNullOrBlank()) parameter("type", type)
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val list = obj["results"]?.jsonArray?.map { item ->
                    parseMediaItem(item.jsonObject)
                } ?: emptyList()
                val total = obj["total"]?.jsonPrimitive?.intOrNull ?: list.size
                val qi = obj["query_info"]?.jsonObject
                val queryInfo = QueryInfo(
                    q = qi?.get("q")?.jsonPrimitive?.contentOrNull ?: query,
                    locationFilter = qi?.get("location_filter")?.jsonPrimitive?.booleanOrNull ?: false,
                    typeFilter = qi?.get("type_filter")?.jsonPrimitive?.booleanOrNull ?: false,
                    locationFilterUnavailable = qi?.get("location_filter_unavailable")?.jsonPrimitive?.booleanOrNull
                )
                logger.info(
                    "MediaService",
                    "getMediaFullTextSearch q=$query status=${response.status} parsed=${list.size} total=$total locUnavailable=${queryInfo.locationFilterUnavailable}"
                )
                FullTextSearchResult(list, total, queryInfo)
            } else {
                logger.info("MediaService", "getMediaFullTextSearch q=$query status=${response.status} (non-OK)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaFullTextSearch FAILED q=$query: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ---- 运维面板 ----

    /**
     * 运维可观测面板 —— GET /api/ops/observability-dashboard。
     *
     * 返回后端运维概览的**原始 JSON**（[JsonObject]），由前端自行解析展示，不在服务层
     * 固化字段结构——运维字段会随后端迭代增减，透传原始 JSON 可避免前后端 data class
     * 频繁同步。预期顶层字段：`metrics_summary`（总请求 / 上传成功率等）、
     * `top_users_by_storage`（按存储排序的用户列表）、`recent_errors`（近期错误日志）、
     * `daily_active_trend`（日活趋势序列）。前端按存在性逐项 `jsonObject[key]` 取值，
     * 缺失字段静默跳过对应展示区。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态/错误提示处理。鉴权头由 defaultRequest
     * 统一注入（运维端点通常要求管理员 token）。
     *
     * @return 后端运维面板原始 JSON；失败返回 null。
     */
    suspend fun getObservabilityDashboard(): JsonObject? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/ops/observability-dashboard")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                logger.info("MediaService", "getObservabilityDashboard status=${response.status} keys=${o.keys}")
                o
            } else {
                logger.info("MediaService", "getObservabilityDashboard status=${response.status} (non-OK)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getObservabilityDashboard FAILED: ${e::class.simpleName} ${e.message}")
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
            parseMediaItem(item.jsonObject)
        } ?: emptyList()
    }

    /**
     * 把单个媒体 JSON 对象解析为 [MediaMetadata]。
     *
     * `advanced-search` 与 `media-smart-search` 后端均直接序列化 `storage.Media` 结构体，
     * 字段口径一致且**不同于** [parseMediaList] 走的 gRPC list 口径：
     * - 顶层用 `mime`（gRPC list 用 `mime_type`）；
     * - `created_at` / `updated_at` 为 RFC3339 字符串（gRPC list 为 epoch 毫秒）；
     * - 多 `total` / `parsed_query` 等顶层字段。
     * `mime_type` 兜底兼容旧口径。提取为单点以便两个搜索端点共享，避免字段漂移。
     */
    private fun parseMediaItem(m: JsonObject): MediaMetadata = MediaMetadata(
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
     * V12：批量强制设置相册封面 — POST /api/media/album/batch-set-cover。
     *
     * 后端遍历 [albumIds] 中每个相册，用其首张媒体作为封面**覆盖**已有封面；
     * 空相册跳过。区别于 [autoPickAlbumCover]（智能挑选最佳，单个）与 auto-cover
     * （仅在封面为空时取首张），本端点接受显式 album_ids 列表且**无论是否已有封面都重设**。
     *
     * 请求体: `{album_ids: [...]}`；响应: `{status, updated_count, skipped_count}`。
     * 用于相册列表页批量选择模式下的"智能选封面"批量入口——选中多个空封面相册一键设封面。
     *
     * @param albumIds 目标相册 id 列表
     * @return 成功时的批量结果（含成功/跳过计数）；HTTP 非 200 / 异常返回 null（stat 失败姿态，不抛）
     */
    suspend fun batchSetAlbumCover(albumIds: List<String>): BatchCoverResult? {
        if (albumIds.isEmpty()) return BatchCoverResult(0, 0)
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/batch-set-cover") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_ids", Json.encodeToJsonElement(albumIds))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                BatchCoverResult(
                    updatedCount = obj["updated_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    skippedCount = obj["skipped_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "batchSetAlbumCover status=${response.status} count=${albumIds.size}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchSetAlbumCover FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 批量设封面结果（与后端 batch-set-cover 响应对齐）。updatedCount=成功设封面数；skippedCount=空相册跳过数。 */
    data class BatchCoverResult(
        val updatedCount: Int,
        val skippedCount: Int
    )

    /**
     * 智能选封面：POST /api/media/album/cover-auto-pick。
     *
     * 后端遍历相册内所有媒体，按优先级「图片类型 > 最大尺寸(width*height) >
     * 最近上传」挑选最佳封面并 SetAlbumCover 落库；无论是否已有封面都重选覆盖。
     *
     * 请求体: `{album_id}`；响应: `{status, album_id, cover_media_id, reason}`。
     * reason 取值如 "best_image_by_size_and_recency" / "no_image_fallback_to_most_recent_media"。
     *
     * @param albumId 目标相册 id
     * @return 成功时的选封面结果；HTTP 非 200 / 异常返回 null（stat 方法失败姿态，不抛）
     */
    suspend fun autoPickAlbumCover(albumId: String): AutoPickResult? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/cover-auto-pick") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                AutoPickResult(
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                    coverMediaId = obj["cover_media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else {
                logger.info("MediaService", "autoPickAlbumCover status=${response.status} albumId=$albumId")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "autoPickAlbumCover FAILED albumId=$albumId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 智能选封面结果（与后端 cover-auto-pick 响应对齐）。 */
    data class AutoPickResult(
        val status: String,
        val coverMediaId: String,
        val reason: String
    )

    /**
     * 封面分析：GET /api/media/album-smart-cover?album_id=xxx。
     *
     * 后端对比当前封面与相册内所有媒体，按封面质量评分给出当前封面分数与推荐封面
     * （含推荐原因），并标记是否建议更换（should_change）。前端用于"封面分析"弹窗：
     * 展示当前 vs 推荐对比，用户确认后可一键应用推荐封面（复用 [setAlbumCover]）。
     *
     * 响应: `{current_cover: {id, score}, recommended: {id, score, reason}, should_change: bool}`。
     * recommended 可为 null（如相册内仅一张图片时无推荐）。
     *
     * @param albumId 目标相册 id
     * @return 分析结果；HTTP 非 200 / 异常返回 null（不抛，调用方按空态处理）
     */
    suspend fun getAlbumSmartCover(albumId: String): SmartCoverResult? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-smart-cover") {
                parameter("album_id", albumId)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val current = obj["current_cover"]?.jsonObject
                val rec = obj["recommended"]?.jsonObject
                SmartCoverResult(
                    currentCover = CoverInfo(
                        id = current?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
                        score = current?.get("score")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        reason = current?.get("reason")?.jsonPrimitive?.contentOrNull
                    ),
                    recommended = rec?.let {
                        CoverInfo(
                            id = it["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            score = it["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            reason = it["reason"]?.jsonPrimitive?.contentOrNull
                        )
                    },
                    shouldChange = obj["should_change"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            } else {
                logger.info("MediaService", "getAlbumSmartCover status=${response.status} albumId=$albumId")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumSmartCover FAILED albumId=$albumId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 封面分析结果（与后端 album-smart-cover 响应对齐）。 */
    data class SmartCoverResult(
        val currentCover: CoverInfo,
        val recommended: CoverInfo?,
        val shouldChange: Boolean
    )

    /** 封面信息：媒体 id + 质量评分（0~100）+ 可选原因。 */
    data class CoverInfo(
        val id: String,
        val score: Double,
        val reason: String?
    )

    /**
     * 给整个相册的所有媒体批量打标签。
     *
     * POST /api/media/tag/batch-tag-album
     * 请求体：{album_id, tag_name}
     * 响应：{tagged_count: Int}（被成功打标签的媒体数量）
     *
     * @param albumId 相册 ID
     * @param tagName 标签名
     * @return 被打标签的媒体数量；HTTP 非 200 或异常时返回 null（不抛）
     */
    suspend fun batchTagAlbum(albumId: String, tagName: String): Int? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/batch-tag-album") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("tag_name", tagName)
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tagged_count"]?.jsonPrimitive?.intOrNull
            } else {
                logger.info("MediaService", "batchTagAlbum status=${response.status} albumId=$albumId tag=$tagName")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchTagAlbum FAILED albumId=$albumId tag=$tagName: ${e::class.simpleName} ${e.message}")
            null
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
     * V20：相册智能建议——GET /api/media/album-suggestions 返回的单条建议。
     *
     * 后端基于当前用户**未分类**媒体（不在任何相册中），按日期/类型/标签分组生成
     * 可一键创建的相册建议。字段与后端 `albumSuggestion` 结构对齐：
     * - [name] 建议相册名（形如"2026年7月的照片"/"视频合集"/"旅行"）
     * - [mediaCount] 该建议覆盖的未分类媒体数
     * - [type] 分组类型：`by_month` / `by_type` / `by_tag`
     * - [previewIds] 封面预览 media id（最多 4 个，按 created_at 降序），供前端渲染缩略图
     */
    data class AlbumSuggestion(
        val name: String,
        val mediaCount: Int = 0,
        val type: String = "",
        val previewIds: List<String> = emptyList()
    )

    /**
     * V20：GET /api/media/album-suggestions — 获取相册智能建议。
     *
     * 基于当前用户未分类媒体，后端按 月份/类型/标签 分组产出可一键创建的相册建议。
     * 只读端点，不修改数据。响应：
     *
     * `{ "suggestions":[{"name","media_count","type","preview_ids":[...]}], "total":N }`
     *
     * 后端不可用/出错时返回 null（与 [getAlbumCountRanking] 同语义——区分"成功但空"
     * 与"网络失败"），调用方据此决定是否渲染推荐区。
     *
     * @return 建议列表（成功，可能为空），或 null（失败）
     */
    suspend fun getAlbumSuggestions(): List<AlbumSuggestion>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-suggestions")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["suggestions"]?.jsonArray ?: JsonArray(emptyList())
                arr.map { el ->
                    val o = el.jsonObject
                    AlbumSuggestion(
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        previewIds = o["preview_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                            ?: emptyList()
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumSuggestions FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V23：相册组织建议——GET /api/media/album-organize-suggest 返回的单条建议。
     *
     * 与 [AlbumSuggestion]（来自 album-suggestions，只给 preview_ids 预览用）不同，
     * 本结构面向"一键创建相册"场景：[mediaIds] 含每个分组的**全部** media id，
     * 前端可直接拿去调 [createAlbum] + [batchAddMediaToAlbum] 落地建相册。
     * 字段与后端 `albumSuggestion`（handleAlbumOrganizeSuggest）对齐：
     * - [type] 分组类型：`by_month` | `by_type`
     * - [name] 建议相册名（形如"2026年7月"/"视频合集"）
     * - [mediaIds] 命中媒体的完整 id（按时间升序，便于按序建档）
     * - [reason] 人类可读的生成理由
     */
    data class AlbumOrganizeSuggestion(
        val name: String,
        val mediaIds: List<String> = emptyList(),
        val type: String = "",
        val reason: String = ""
    )

    /**
     * V23：GET /api/media/album-organize-suggest — 获取相册组织建议（含完整 media_ids）。
     *
     * 基于当前用户未软删媒体，后端按 月份/类型 分组产出可一键创建的相册建议，
     * 每条携带该分组的**完整** media id 列表（区别于 [getAlbumSuggestions] 的 preview_ids），
     * 供"我的"Tab"照片组织建议"卡片直接落地为创建相册动作。只读端点。
     *
     * 响应：`{ "suggestions":[{type,name,media_ids,reason}], "total":N }`
     *
     * 后端不可用/出错时返回 null（与 [getAlbumCountRanking] 同语义——区分"成功但空"
     * 与"网络失败"），调用方据此决定是否渲染建议区。
     *
     * @return 建议列表（成功，可能为空），或 null（失败）
     */
    suspend fun getAlbumOrganizeSuggest(): List<AlbumOrganizeSuggestion>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-organize-suggest")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["suggestions"]?.jsonArray ?: JsonArray(emptyList())
                arr.map { el ->
                    val o = el.jsonObject
                    AlbumOrganizeSuggestion(
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaIds = o["media_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                            ?: emptyList(),
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumOrganizeSuggest FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * V23：相册综合统计——GET /api/media/album-stats-comprehensive 返回的合并数据。
     *
     * 一次请求合并三组相册维度统计（summary + sharing + ranking top3），供"我的"页
     * "相册综合"卡片一次渲染，避免并发拉取 stats-summary / sharing-summary / count-ranking。
     *
     * 与后端 [handleAlbumStatsComprehensive] 字段对齐：
     * - summary: {total_albums, total_photos, avg_photos, largest_album{name,count}|null}
     *   total_photos 为各相册 MediaIDs 长度之和（跨相册不去重，与 stats-summary 口径一致）；
     *   largest_album 在无相册时为 null。avg_photos 为 0.0（无相册时后端置 0）。
     * - sharing: {shared, unshared}（store 不可用时全部按 unshared 计）。
     * - ranking: 按 count 倒序的 top 3，仅 name+count，可能为空数组。
     *
     * ranking 复用既有 [AlbumRankItem]（albumId/name/count/coverMediaId）。综合端点只返
     * name+count，解析时 albumId 置空、coverMediaId 置 null，与 count-ranking 解析同款补默认。
     * 任一字段缺失回退默认值（0/空/null），与既有 [AlbumStatsSummary] 同款宽容解析。
     */
    data class LargestAlbumInfo(
        val name: String = "",
        val count: Int = 0
    )

    data class AlbumSummary(
        val totalAlbums: Int = 0,
        val totalPhotos: Int = 0,
        val avgPhotos: Double = 0.0,
        val largestAlbum: LargestAlbumInfo? = null
    )

    data class AlbumSharing(
        val shared: Int = 0,
        val unshared: Int = 0
    )

    data class AlbumStatsComprehensive(
        val summary: AlbumSummary = AlbumSummary(),
        val sharing: AlbumSharing = AlbumSharing(),
        val ranking: List<AlbumRankItem> = emptyList()
    )

    /**
     * V23：GET /api/media/album-stats-comprehensive — 相册综合统计。
     *
     * 一次请求返回 summary + sharing + ranking top3，供"我的"页"相册综合"卡片渲染。
     * 需认证（Authorization 头由 [jsonClient] defaultRequest 自动注入）。
     * 后端不可用/出错时返回 null（与 [getAlbumStatsSummary] 同语义——区分"成功但空"
     * 与"网络失败"，故用 null 表示失败）。
     *
     * 路径为带连字符的 album-stats-comprehensive（不会落入 /api/media/album/ 前缀匹配），
     * 见后端 [Server.handleAlbumStatsComprehensive]。
     *
     * @return 相册综合统计，或 null（失败）
     */
    suspend fun getAlbumStatsComprehensive(): AlbumStatsComprehensive? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-stats-comprehensive")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                // summary
                val s = obj["summary"]?.jsonObject
                val largestEl = s?.get("largest_album")
                val largest = if (largestEl == null || largestEl is JsonNull) null
                              else LargestAlbumInfo(
                                  name = largestEl.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                  count = largestEl.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 0
                              )
                val summary = AlbumSummary(
                    totalAlbums = s?.get("total_albums")?.jsonPrimitive?.intOrNull ?: 0,
                    totalPhotos = s?.get("total_photos")?.jsonPrimitive?.intOrNull ?: 0,
                    avgPhotos = s?.get("avg_photos")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    largestAlbum = largest
                )
                // sharing
                val sh = obj["sharing"]?.jsonObject
                val sharing = AlbumSharing(
                    shared = sh?.get("shared")?.jsonPrimitive?.intOrNull ?: 0,
                    unshared = sh?.get("unshared")?.jsonPrimitive?.intOrNull ?: 0
                )
                // ranking（top3，仅 name+count；复用 AlbumRankItem，albumId/coverMediaId 补默认）
                val ranking = obj["ranking"]?.jsonArray?.map { el ->
                    val r = el.jsonObject
                    AlbumRankItem(
                        albumId = r["album_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = r["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = r["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        coverMediaId = null
                    )
                } ?: emptyList()
                AlbumStatsComprehensive(summary = summary, sharing = sharing, ranking = ranking)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumStatsComprehensive FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }
    /**
     * 相册分享摘要项——GET /api/media/album-sharing-summary 返回的单条相册分享信息。
     *
     * 与后端 [handleAlbumSharingSummary] 返回字段对齐：
     * album_id / name / shared / share_count / access_count。
     */
    data class AlbumShareInfo(
        val albumId: String = "",
        val name: String = "",
        val shared: Boolean = false,
        val shareCount: Int = 0
    )

    /**
     * 相册分享摘要——GET /api/media/album-sharing-summary 返回的聚合数据。
     *
     * 后端返回 `{albums:[{album_id,name,shared,share_count,access_count}], shared_total, unshared_total}`。
     * [sharedTotal] / [unsharedTotal] 分别为已分享/未分享的相册数；
     * [albums] 为各相册的分享详情列表。
     *
     * 后端不可用/出错时返回 null（与 [getAlbumStatsSummary] 同语义）。
     */
    data class AlbumSharingSummary(
        val sharedTotal: Int = 0,
        val unsharedTotal: Int = 0,
        val albums: List<AlbumShareInfo> = emptyList()
    )

    /**
     * GET /api/media/album-sharing-summary — 获取相册分享摘要
     * （已分享/未分享相册数 + 各相册分享详情）。
     *
     * 后端返回 `{albums:[{album_id,name,shared,share_count,access_count}], shared_total, unshared_total}`。
     * 失败时返回 null（与 [getAlbumStatsSummary] 同语义——区分"成功但空"与"网络失败"）。
     *
     * @return 相册分享摘要，或 null（失败）
     */
    suspend fun getAlbumSharingSummary(): AlbumSharingSummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-sharing-summary")
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["albums"]?.jsonArray ?: JsonArray(emptyList())
                val infos = arr.map { el ->
                    val o = el.jsonObject
                    AlbumShareInfo(
                        albumId = o["album_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        shared = o["shared"]?.jsonPrimitive?.booleanOrNull ?: false,
                        shareCount = o["share_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
                AlbumSharingSummary(
                    sharedTotal = obj["shared_total"]?.jsonPrimitive?.intOrNull ?: 0,
                    unsharedTotal = obj["unshared_total"]?.jsonPrimitive?.intOrNull ?: 0,
                    albums = infos
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumSharingSummary FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相册封面质量条目——GET /api/media/album-cover-quality 返回的单条相册封面质量信息。
     *
     * 与后端 [handleAlbumCoverQuality] 返回字段对齐：
     * album_id / name / cover_id / quality_grade / resolution。
     */
    data class CoverQualityItem(
        val albumId: String = "",
        val name: String = "",
        val coverId: String = "",
        val qualityGrade: String = "",
        val resolution: String = ""
    )

    /**
     * 相册封面质量汇总——GET /api/media/album-cover-quality 返回的聚合数据。
     *
     * 后端返回 `{albums:[{album_id,name,cover_id,quality_grade,resolution}], total, grade_distribution:{A,B,C}}`。
     * - [albums]：有封面相册的逐条质量信息（无封面相册不在列表中）。
     * - [total]：有封面的相册数（即 albums 列表长度）。
     * - [gradeDistribution]：A/B/C 三级分布数量（key 为 "A"/"B"/"C"）。
     *
     * 后端不可用/出错时返回 null（与 [getAlbumSharingSummary] 同语义）。
     */
    data class AlbumCoverQuality(
        val albums: List<CoverQualityItem> = emptyList(),
        val total: Int = 0,
        val gradeDistribution: Map<String, Int> = emptyMap()
    )

    /**
     * GET /api/media/album-cover-quality — 获取相册封面质量评分汇总
     * （各相册封面 A/B/C 级分布 + 逐条质量信息）。
     *
     * 后端按封面 media 分辨率评级：A≥1920×1080、B≥1280×720、C 为更低/缺分辨率。
     * 返回 `{albums:[{album_id,name,cover_id,quality_grade,resolution}], total, grade_distribution:{A,B,C}}`。
     * 失败时返回 null（与 [getAlbumSharingSummary] 同语义——区分"成功但空"与"网络失败"）。
     *
     * @return 相册封面质量汇总，或 null（失败）
     */
    suspend fun getAlbumCoverQuality(): AlbumCoverQuality? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-cover-quality")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val arr = obj["albums"]?.jsonArray ?: JsonArray(emptyList())
                val items = arr.map { el ->
                    val o = el.jsonObject
                    CoverQualityItem(
                        albumId = o["album_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        coverId = o["cover_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        qualityGrade = o["quality_grade"]?.jsonPrimitive?.contentOrNull ?: "",
                        resolution = o["resolution"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                val dist: Map<String, Int> = obj["grade_distribution"]?.jsonObject?.mapValues { (_, v) ->
                    v.jsonPrimitive.intOrNull ?: 0
                } ?: emptyMap()
                AlbumCoverQuality(
                    albums = items,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: items.size,
                    gradeDistribution = dist
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumCoverQuality FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相册关系分析条目中单个相册的概要信息——GET /api/media/album-relationship-analysis
     * 返回的配对里 album_a / album_b 各自携带的相册概要。
     *
     * 与后端 [handleAlbumRelationshipAnalysis] 的 albumBrief 字段对齐：
     * id / name / media_count。
     *
     * 注：不复用 [AlbumSummaryItem]（其字段为 albumId/count/coverMediaId，与本端点的
     * id/name/media_count 结构不同），也不复用 [CoverQualityItem]（语义不符），
     * 故为本端点新建专用概要类型 [AlbumPairBrief]。
     */
    data class AlbumPairBrief(
        val id: String = "",
        val name: String = "",
        val mediaCount: Int = 0
    )

    /**
     * 相册关系分析条目——GET /api/media/album-relationship-analysis 返回的单个相册配对。
     *
     * 与后端 [handleAlbumRelationshipAnalysis] 的 relationshipPair 字段对齐：
     * album_a / album_b（各为 [AlbumPairBrief]）/ shared_count / similarity /
     * recommend_merge。后端还返回 union_count，前端目前不展示并集数，故不建模
     * （运行时解析时忽略该字段——[Json] 已配 `ignoreUnknownKeys`，宽容）。
     *
     * @param albumA 相册 A 概要
     * @param albumB 相册 B 概要
     * @param sharedCount 两相册共享的媒体数（去重后）
     * @param similarity Jaccard 相似度 0~1（shared / union；union 为 0 时为 0）
     * @param recommendMerge 后端建议合并（shared_count≥2 且 similarity≥0.3）
     */
    data class AlbumPair(
        val albumA: AlbumPairBrief = AlbumPairBrief(),
        val albumB: AlbumPairBrief = AlbumPairBrief(),
        val sharedCount: Int = 0,
        val similarity: Double = 0.0,
        val recommendMerge: Boolean = false
    )

    /**
     * 相册关系分析汇总——GET /api/media/album-relationship-analysis 返回的聚合数据。
     *
     * 后端遍历当前用户所有相册两两配对（C(n,2)），按 Jaccard 相似度排序，
     * 标记建议合并的相册对，返回：
     * `{pairs:[{album_a,album_b,shared_count,union_count,similarity,recommend_merge}],
     *   total_pairs, high_similarity_count}`。
     *
     * - [pairs]：所有配对，按 shared_count 降序、再按 similarity 降序（高重叠对在前）。
     *   前端通常只展示 [highSimilarityCount] 个建议合并对。
     * - [totalPairs]：参与分析的相册对总数。
     * - [highSimilarityCount]：recommend_merge 为 true 的相册对数（即"高相似度"对数）。
     *
     * 相册数 < 2 时后端返回空 pairs 与 0 计数；网络/HTTP 失败时本方法返回 null
     * （与 [getAlbumSharingSummary] 同语义——区分"成功但空"与"网络失败"）。
     */
    data class AlbumRelationship(
        val pairs: List<AlbumPair> = emptyList(),
        val totalPairs: Int = 0,
        val highSimilarityCount: Int = 0
    )

    /**
     * GET /api/media/album-relationship-analysis — 获取相册关系分析（相似相册配对）。
     *
     * 后端遍历当前用户所有相册两两配对，计算共享媒体数与 Jaccard 相似度，
     * 标记建议合并的相册对（shared_count≥2 且 similarity≥0.3）。返回：
     * `{pairs:[{album_a:{id,name,media_count},album_b:{...},shared_count,
     *   union_count,similarity,recommend_merge}], total_pairs, high_similarity_count}`。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getAlbumSharingSummary]/[getAlbumCoverQuality] 同款）。HTTP 非 200 或网络异常返回
     * null，调用方按 null 态静默跳过渲染（与 [getAlbumSharingSummary] 同语义）。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @return 相册关系分析汇总，或 null（失败）
     */
    suspend fun getAlbumRelationshipAnalysis(): AlbumRelationship? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-relationship-analysis")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                fun parseBrief(o: JsonObject): AlbumPairBrief = AlbumPairBrief(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
                val pairs = obj["pairs"]?.jsonArray?.map { el ->
                    val p = el.jsonObject
                    AlbumPair(
                        albumA = p["album_a"]?.jsonObject?.let(::parseBrief) ?: AlbumPairBrief(),
                        albumB = p["album_b"]?.jsonObject?.let(::parseBrief) ?: AlbumPairBrief(),
                        sharedCount = p["shared_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        similarity = p["similarity"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        recommendMerge = p["recommend_merge"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                } ?: emptyList()
                AlbumRelationship(
                    pairs = pairs,
                    totalPairs = obj["total_pairs"]?.jsonPrimitive?.intOrNull ?: pairs.size,
                    highSimilarityCount = obj["high_similarity_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumRelationshipAnalysis FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相册内媒体分布分析——GET /api/media/album-media-distribution?album_id=xxx。
     *
     * 后端返回 `{by_type, by_month, by_size, total}`：
     * - [byType]：按媒体类型聚合的数量映射（key 如 "image"/"video"/"live"，后端按实际类型键返回）。
     * - [byMonth]：按月份聚合的上传数量（[DistributionMonthCount]，month 为后端给的
     *   "YYYY-MM" 字符串，零值时间归入 "unknown"）。
     *   注：不复用既有 [MonthCount]（其 month 为 Int，对应 yearly-review 的 1~12 整数月），
     *   因后端本端点 month 为 "YYYY-MM" 字符串——类型不符，故新建专用项类型。
     * - [bySize]：按字节区间分桶的数量（[SizeDist]，small/medium/large 三档）。
     * - [total]：该相册媒体总数。
     *
     * 后端不可用 / 非 200 / 异常时返回 null（与 [getAlbumSharingSummary] 同语义，
     * 调用方按 null 态静默跳过渲染，不抛）。
     *
     * @param albumId 目标相册 id
     * @return 分布分析结果，或 null（失败）
     */
    suspend fun getAlbumMediaDistribution(albumId: String): AlbumDistribution? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album-media-distribution") {
                parameter("album_id", albumId)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // by_type: {image: N, video: M, ...} —— 后端键名按实际类型返回，逐项解析为 Map
                val byType: Map<String, Int> = obj["by_type"]?.jsonObject?.mapValues { (_, v) ->
                    v.jsonPrimitive.intOrNull ?: 0
                } ?: emptyMap()
                // by_month: [{month, count}] —— month 为 "YYYY-MM" 字符串（零值时间归 "unknown"）
                val byMonth: List<DistributionMonthCount> = obj["by_month"]?.jsonArray?.map { el ->
                    val o = el.jsonObject
                    DistributionMonthCount(
                        month = o["month"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                // by_size: {small, medium, large} —— 三档分桶数量
                val sizeObj = obj["by_size"]?.jsonObject
                val bySize = SizeDist(
                    small = sizeObj?.get("small")?.jsonPrimitive?.intOrNull ?: 0,
                    medium = sizeObj?.get("medium")?.jsonPrimitive?.intOrNull ?: 0,
                    large = sizeObj?.get("large")?.jsonPrimitive?.intOrNull ?: 0
                )
                AlbumDistribution(
                    byType = byType,
                    byMonth = byMonth,
                    bySize = bySize,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getAlbumMediaDistribution status=${response.status} albumId=$albumId")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAlbumMediaDistribution FAILED albumId=$albumId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相册媒体分布分析结果（与后端 album-media-distribution 响应对齐）。
     *
     * [byMonth] 用 [DistributionMonthCount]（month 为 "YYYY-MM" 字符串），不复用既有
     * [MonthCount]（其 month 为 Int）——两者后端语义不同，避免类型错位。
     */
    data class AlbumDistribution(
        val byType: Map<String, Int> = emptyMap(),
        val byMonth: List<DistributionMonthCount> = emptyList(),
        val bySize: SizeDist = SizeDist(),
        val total: Int = 0
    )

    /** 相册分布分析的月份项：month 为 "YYYY-MM" 字符串（零值时间归 "unknown"）。 */
    data class DistributionMonthCount(
        val month: String = "",
        val count: Int = 0
    )

    /** 字节区间分桶：[small] (<1MB) / [medium] (1~10MB) / [large] (>10MB)。 */
    data class SizeDist(
        val small: Int = 0,
        val medium: Int = 0,
        val large: Int = 0
    ) {
        val total: Int get() = small + medium + large
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
     * V9：用户活跃度评分维度明细——后端 [handleUserActivityScore] 的 breakdown 元素。
     *
     * 后端结构：`{action, count, weight, points}`，前端展示 action + count + points
     * （weight 后端也回传，但 UI 不展示，留 [weight] 供后续优化）。
     *
     * @param action 动作名（upload/favorite/share/tag/rename/rotate）
     * @param count  该动作累计次数
     * @param weight 权重（后端回传，前端可选展示）
     * @param points 该维度得分（count * weight）
     */
    data class ScoreBreakdown(
        val action: String,
        val count: Int,
        val weight: Int = 0,
        val points: Int
    )

    /**
     * V9：用户活跃度评分结果——对应后端 [handleUserActivityScore] 响应。
     *
     * 后端结构：`{score, level, breakdown:[{action,count,weight,points}], total_actions, user_id}`。
     *
     * @param score 总分（各维度 count*weight 之和）
     * @param level 等级（新手/活跃/达人/专家）
     * @param breakdown 各维度明细列表（顺序：upload → favorite → share → tag → rename → rotate）
     * @param totalActions 总操作次数（各维度 count 之和）
     */
    data class UserActivityScore(
        val score: Int,
        val level: String,
        val breakdown: List<ScoreBreakdown>,
        val totalActions: Int
    )

    /**
     * V9：GET /api/media/user-activity-score — 用户活跃度评分。
     *
     * 拉取后端按各操作维度加权累计的活跃度评分：总分数 + 等级 + 维度明细。
     * 需认证（Authorization 头由 [jsonClient] defaultRequest 自动注入）；失败返回 null。
     *
     * 等级映射（与后端 [handleUserActivityScore] 一致）：
     * - 新手(0-10) / 活跃(11-50) / 达人(51-100) / 专家(101+)
     */
    suspend fun getUserActivityScore(): UserActivityScore? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/user-activity-score")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val breakdown = obj["breakdown"]?.jsonArray?.map { el ->
                    val b = el.jsonObject
                    ScoreBreakdown(
                        action = b["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = b["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        weight = b["weight"]?.jsonPrimitive?.intOrNull ?: 0,
                        points = b["points"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                UserActivityScore(
                    score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0,
                    level = obj["level"]?.jsonPrimitive?.contentOrNull ?: "新手",
                    breakdown = breakdown,
                    totalActions = obj["total_actions"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getUserActivityScore status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getUserActivityScore FAILED: ${e::class.simpleName} ${e.message}")
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
     * GET /api/media/media-duplicate-deep?limit=50 — 重复深度分析（三类重复一次返回）。
     *
     * 后端一次 ListMediaByUser 全量拉取后在 Go 侧聚合，同时返回三类重复文件分析，供前端
     * "深度去重"卡片一次加载渲染：
     *
     * a) exact_groups：相同 SHA256 的完全重复（同内容多份占用）。
     * b) near_pairs  ：SHA256 不同但同类型 + 同分辨率(总像素) + 文件大小 ±5% 的近似重复
     *    （可能是同一照片的不同格式/质量版本）。
     * c) burst_groups：同一小时内按拍摄时间连续拍摄的同类型媒体（典型如连拍/截图）。
     *
     * 同时返回汇总字段：total_reclaimable_bytes（三类可回收字节之和）、
     * total_redundant_count（三类冗余文件数之和），以及每类的截断前全量计数
     * （exact_groups_total / near_pairs_total / burst_groups_total）与每类可回收字节。
     *
     * 本方法**简化处理**：直接返回后端响应体原始 JSON 字符串，由调用方（设置页
     * [com.wgt.media.DuplicateDeepCard]）按需运行时解析所需字段，避免在此声明一长串
     * 一次性 data class。与 [getBackendInfo] 同属"返回 String?"的简化范式。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态/错误态处理（不展示卡片数据）。
     * 鉴权头由 [jsonClient] 的 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param limit 每类返回条数上限（默认 50，后端上限 500）；total_* 汇总为截断前全量统计
     * @return 后端响应体 JSON 字符串；失败返回 null
     */
    suspend fun getMediaDuplicateDeep(limit: Int = 50): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-duplicate-deep") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText()
            } else {
                logger.info("MediaService", "getMediaDuplicateDeep status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaDuplicateDeep FAILED: ${e::class.simpleName} ${e.message}")
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
     * V26：文件名前缀补全建议（GET /api/media/media-search-suggestions?q=xxx&limit=5）。
     *
     * 与 [getEnhancedSearchSuggestions] 的区别：本端点聚焦"补全"语义——
     * 后端仅做大小写不敏感前缀匹配（HasPrefix），保留完整文件名（含扩展名），
     * 每条建议携带 [mediaId] 便于前端潜在跳转；后者是多源子串合并建议。
     *
     * 响应：`{suggestions:[{text,type:"filename",media_id?}], total}`。
     * 成功返回 [MediaSearchSuggestion] 列表（可能为空）；失败/非 200 返回 null，
     * 调用方按 null 降级（隐藏补全区）。鉴权由 [jsonClient] defaultRequest 统一附加。
     *
     * 与 [EnhancedSuggestion] 字段差异：此处 [type] 对应后端 "type"（固定 "filename"），
     * [mediaId] 为可为空媒体 ID（后端 `media_id,omitempty`）——补全文本即完整文件名，
     * 不做去扩展名处理。
     */
    data class MediaSearchSuggestion(
        val text: String = "",
        val type: String = "",
        val mediaId: String? = null
    )

    suspend fun getMediaSearchSuggestions(query: String, limit: Int = 5): List<MediaSearchSuggestion>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val url = "${backendBaseUrl()}/api/media/media-search-suggestions?q=${q.encodeURLQueryComponent()}&limit=$limit"
            val response: HttpResponse = jsonClient.get(url) {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["suggestions"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    MediaSearchSuggestion(
                        text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaId = o["media_id"]?.let { el ->
                            if (el is JsonNull) null
                            else el.jsonPrimitive.contentOrNull
                        }
                    )
                }
            } else {
                logger.info("MediaService", "getMediaSearchSuggestions status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSearchSuggestions FAILED: ${e::class.simpleName} ${e.message}")
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
     * 搜索查询统计条目 —— 对应 GET /api/media/media-query-stats 返回结构。
     *
     * 后端结构：
     * - [totalSearches]：`total_searches` 累计搜索次数（int）。
     * - [topKeywords]：`top_keywords` 热搜关键词数组，每条 `{keyword, count}`。
     * - [searchTrend]：`search_trend` 趋势数组，每条 `{date, count}`（前端暂未展示，仅透传）。
     *
     * 注：使用 [TopKeyword] / [SearchTrendPoint] 而非 `KeywordCount` / `DayCount`，
     * 因 MediaService 已存在 [DayCount]（本周上传摘要的元素，字段为 day+count），
     * 重名会冲突；此处语义不同（date+count），故独立命名。
     * 调用方（搜索栏"热门搜索"区）在有数据（totalSearches > 0）时展示热词 chip。
     */
    data class TopKeyword(
        val keyword: String,
        val count: Int
    )

    data class SearchTrendPoint(
        val date: String,
        val count: Int
    )

    data class MediaQueryStats(
        val totalSearches: Int,
        val topKeywords: List<TopKeyword>,
        val searchTrend: List<SearchTrendPoint>
    )

    /**
     * GET /api/media/media-query-stats — 搜索查询统计（热词 + 趋势）。
     *
     * 响应：`{total_searches, top_keywords:[{keyword,count}], search_trend:[{date,count}]}`。
     * 成功返回 [MediaQueryStats]；失败/非 200 返回 null，调用方降级为不展示"热门搜索"区。
     * count 后端为 int，用 [jsonPrimitive.intOrNull] 容错解析。鉴权由 [jsonClient]
     * defaultRequest 统一附加 Bearer token。
     */
    suspend fun getMediaQueryStats(): MediaQueryStats? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-query-stats") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val total = obj["total_searches"]?.jsonPrimitive?.intOrNull ?: 0
                val topKeywords = obj["top_keywords"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val kw = o["keyword"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    TopKeyword(
                        keyword = kw,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                val trend = obj["search_trend"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SearchTrendPoint(
                        date = date,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                MediaQueryStats(totalSearches = total, topKeywords = topKeywords, searchTrend = trend)
            } else {
                logger.info("MediaService", "getMediaQueryStats status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaQueryStats FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 使用习惯分析——会话维度统计（GET /api/media/media-session-stats）。
     *
     * 后端聚合用户浏览媒体时的会话数据，返回：
     * `{total_sessions, avg_actions, avg_duration, common_first_action, longest_session:{actions, duration}}`
     * - total_sessions: 会话总数
     * - avg_actions: 平均每个会话的操作次数
     * - avg_duration: 平均会话时长（秒，后端口径）
     * - common_first_action: 最常见的首次操作（如 "view"/"favorite"）
     * - longest_session: 最长一次会话的 actions/duration，可能为 null（无会话时）
     *
     * 前端按"分钟"展示时长——[LongestSession.duration] 与 [avgDuration] 均为秒，
     * UI 层 ([SessionStatsCard]) 除以 60 转分钟。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaQueryStats] 同款）。HTTP 非 200 或网络异常返回 null，调用方降级为空态。
     * 鉴权头由 [jsonClient] defaultRequest 统一注入。
     *
     * @return 会话统计；失败返回 null
     */
    data class LongestSession(
        val actions: Int = 0,
        val duration: Double = 0.0
    )

    data class MediaSessionStats(
        val totalSessions: Int = 0,
        val avgActions: Double = 0.0,
        val avgDuration: Double = 0.0,
        val commonFirstAction: String = "",
        val longestSession: LongestSession? = null
    )

    suspend fun getMediaSessionStats(): MediaSessionStats? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-session-stats")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaSessionStats(
                    totalSessions = obj["total_sessions"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgActions = obj["avg_actions"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    avgDuration = obj["avg_duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    commonFirstAction = obj["common_first_action"]?.jsonPrimitive?.contentOrNull ?: "",
                    longestSession = parseLongestSession(obj["longest_session"])
                )
            } else {
                logger.info("MediaService", "getMediaSessionStats status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSessionStats FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 解析 longest_session 子对象；后端在无会话时返回 null/缺省。 */
    private fun parseLongestSession(el: JsonElement?): LongestSession? {
        if (el == null || el is JsonNull) return null
        val o = el.jsonObject
        return LongestSession(
            actions = o["actions"]?.jsonPrimitive?.intOrNull ?: 0,
            duration = o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        )
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

    // ============ V8 批量下载 + 相册管理 API ============

    /**
     * 将后端返回的相对下载 URL（如 `/api/media/download/{id}`）拼接为完整 URL。
     *
     * 后端 [getBatchDownloadUrls] 返回的 url 字段为相对路径，前端展示/复制时需拼接
     * [backendBaseUrl]。若 [relativeUrl] 已是绝对 URL（含 scheme），则原样返回。
     */
    fun buildFullDownloadUrl(relativeUrl: String): String {
        if (relativeUrl.isEmpty()) return ""
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) return relativeUrl
        val base = backendBaseUrl()
        return if (relativeUrl.startsWith("/")) "$base$relativeUrl" else "$base/$relativeUrl"
    }

    /**
     * 批量下载 URL 条目（与后端 handleMediaBatchDownloadUrls 响应对齐）。
     *
     * @param mediaId 媒体 ID
     * @param filename 原始文件名
     * @param url 直接下载 URL（/api/media/download/{id}，鉴权后有效）
     * @param size 文件字节数
     */
    data class BatchDownloadUrl(
        val mediaId: String,
        val filename: String,
        val url: String,
        val size: Long
    )

    /**
     * POST /api/media/batch-download-urls — 批量获取多个媒体的直接下载 URL 列表。
     *
     * 与 [batchDownload]（zip 流式打包）不同，本端点不打包文件，仅返回临时直接下载 URL
     * （/api/media/download/{id}），前端可据此渲染"复制链接"或并发批量下载。
     *
     * 请求体: `{"media_ids": ["id1","id2",...]}`（单批最多 100，超限后端整体拒绝）。
     * 响应: `{"urls": [{"media_id","filename","url","size"}], "count": N}`。
     *
     * @param mediaIds 媒体 ID 列表（≤100）
     * @return 下载 URL 条目列表；HTTP 非 200 / 异常返回 null
     */
    suspend fun getBatchDownloadUrls(mediaIds: List<String>): List<BatchDownloadUrl>? {
        if (mediaIds.isEmpty()) return emptyList()
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-download-urls") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_ids", Json.encodeToJsonElement(mediaIds))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["urls"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val id = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    BatchDownloadUrl(
                        mediaId = id,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        url = item["url"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else {
                logger.info("MediaService", "getBatchDownloadUrls count=${mediaIds.size} status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getBatchDownloadUrls FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * POST /api/media/batch-download — 批量下载多个媒体文件，后端打包成 zip 流式返回。
     *
     * 与 [getBatchDownloadUrls]（仅返回 URL）不同，本端点直接返回 application/zip 字节流，
     * 前端可写入本地文件。单批最多 100 个 media_ids。
     *
     * 请求体: `{"media_ids": ["id1","id2",...]}`。
     * 响应: `application/zip` 字节流（Content-Disposition: attachment; filename="media-batch-{ts}.zip"）。
     *
     * @param mediaIds 媒体 ID 列表（≤100）
     * @return zip 字节流；HTTP 非 200 / 异常返回 null
     */
    suspend fun batchDownload(mediaIds: List<String>): ByteArray? {
        if (mediaIds.isEmpty()) return null
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/batch-download") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("media_ids", Json.encodeToJsonElement(mediaIds))
                })
            }
            if (response.status == HttpStatusCode.OK) {
                val bytes = response.body<ByteArray>()
                logger.info("MediaService", "batchDownload count=${mediaIds.size} status=${response.status} bytes=${bytes.size}")
                bytes
            } else {
                logger.info("MediaService", "batchDownload count=${mediaIds.size} status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchDownload FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * POST /api/media/album/reorder — 调整相册内照片顺序。
     *
     * 请求体: `{"album_id": "x", "media_ids": ["id1","id2",...]}`。
     * 响应: `{"status":"success","album_id":"x","reordered": N}`。
     *
     * @param albumId 目标相册 ID
     * @param mediaIds 新顺序的媒体 ID 列表（须为相册内全部媒体，后端按此顺序覆写）
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun reorderAlbum(albumId: String, mediaIds: List<String>): Boolean {
        if (albumId.isBlank() || mediaIds.isEmpty()) return false
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/reorder") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("album_id", albumId)
                    put("media_ids", Json.encodeToJsonElement(mediaIds))
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "reorderAlbum id=$albumId count=${mediaIds.size} status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "reorderAlbum FAILED id=$albumId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * POST /api/media/album/batch-pin 或 /api/media/album/batch-unpin — 批量置顶/取消置顶相册。
     *
     * [pin]=true 走 batch-pin，false 走 batch-unpin。请求体: `{"album_ids": [...]}`。
     * 响应: batch-pin `{"status","pinned_count","skipped_count"}`；
     *       batch-unpin `{"status","unpinned_count","skipped_count"}`。
     * 对不存在/非当前用户所属的相册跳过，不中断整体流程。
     *
     * @param albumIds 相册 ID 列表
     * @param pin true=置顶，false=取消置顶
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun batchPinAlbums(albumIds: List<String>, pin: Boolean): Boolean {
        if (albumIds.isEmpty()) return false
        val path = if (pin) "/api/media/album/batch-pin" else "/api/media/album/batch-unpin"
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}$path") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("album_ids", Json.encodeToJsonElement(albumIds))
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "batchPinAlbums pin=$pin count=${albumIds.size} status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "batchPinAlbums FAILED pin=$pin: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * POST /api/media/album/merge — 合并两个相册。
     *
     * 把 [sourceId] 的所有 media 移到 [targetId]，然后删除 source 相册。
     * 请求体: `{"source_album_id": "x", "target_album_id": "y"}`。
     * 响应: `{"status":"success","merged_count": N,"target_album_id":"y","deleted_source":"x"}`。
     *
     * @param sourceId 源相册 ID（合并后删除）
     * @param targetId 目标相册 ID（合并后保留）
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun mergeAlbums(sourceId: String, targetId: String): Boolean {
        if (sourceId.isBlank() || targetId.isBlank() || sourceId == targetId) return false
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/album/merge") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("source_album_id", sourceId)
                    put("target_album_id", targetId)
                })
            }
            val ok = response.status == HttpStatusCode.OK
            logger.info("MediaService", "mergeAlbums source=$sourceId target=$targetId status=${response.status}")
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "mergeAlbums FAILED source=$sourceId target=$targetId: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * GET /api/media/album/export?album_id=xxx — 导出相册元数据为 JSON。
     *
     * 返回相册基本信息（id/name/cover_media_id/created_at）及内含媒体的完整 metadata 列表。
     * 注意：本端点返回 JSON 元数据（非文件字节流），与 [batchDownload] 的 zip 字节流不同。
     * 响应: `{"album": {id,name,cover_media_id,created_at}, "media_count": N, "media": [...]}`
     *
     * @param albumId 目标相册 ID
     * @return 导出的 JSON 字符串（调用方可直接展示或保存）；HTTP 非 200 / 异常返回 null
     */
    suspend fun exportAlbum(albumId: String): String? {
        if (albumId.isBlank()) return null
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/album/export") {
                parameter("album_id", albumId)
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                logger.info("MediaService", "exportAlbum id=$albumId status=${response.status} bytes=${body.length}")
                body
            } else {
                logger.info("MediaService", "exportAlbum id=$albumId status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "exportAlbum FAILED id=$albumId: ${e::class.simpleName} ${e.message}")
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
     * V21：GET /api/media/media-resolution-distribution — 增强版分辨率分布。
     *
     * 与 [getByResolution]（按最大边 maxDim 粗分 4K/2K/1080p/720p/其他，仅返 count）不同，
     * 本端点按 width*height 像素总量分四档（低清 / 标清·高清 / 超清 / 4K+），并补充：
     *   - 每档 count + bytes（累计该档媒体 Size）
     *   - 方向统计（横向 landscape / 纵向 portrait / 正方形 square）
     *   - 极值分辨率（max/min，按像素总量比较；无有效分辨率媒体时为 null）
     *
     * 响应结构（与 [handleMediaResolutionDist] 对齐）：
     *   `{tiers:[{tier,count,bytes}], orientation:{landscape,portrait,square},
     *     max_resolution:{width,height,pixels}|null, min_resolution:..., total:N}`
     *
     * count/bytes/tier 均为后端 int64/string（永不为 JSON null），用 `?: 0` / `?: 0L` 安全默认。
     * `tiers` 中 tier 字段缺失则 `return@mapNotNull null` 丢弃该行；max/min_resolution 可为 JSON null
     * （Go `*resolution` 指针），故先 `is JsonNull` 短路再读字段。失败返回 null，调用方按 null 静默跳过。
     */
    suspend fun getResolutionDistribution(): ResolutionDist? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-resolution-distribution") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val tiers = obj["tiers"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    ResolutionTier(
                        tier = o["tier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                } ?: emptyList()
                val orientObj = obj["orientation"]?.jsonObject
                val orientation = Orientation(
                    landscape = orientObj?.get("landscape")?.jsonPrimitive?.intOrNull ?: 0,
                    portrait = orientObj?.get("portrait")?.jsonPrimitive?.intOrNull ?: 0,
                    square = orientObj?.get("square")?.jsonPrimitive?.intOrNull ?: 0
                )
                val maxRes = obj["max_resolution"]?.let { el ->
                    if (el is JsonNull) null
                    else {
                        val o = el.jsonObject
                        ResolutionInfo(
                            width = o["width"]?.jsonPrimitive?.intOrNull ?: 0,
                            height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
                            pixels = o["pixels"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    }
                }
                val minRes = obj["min_resolution"]?.let { el ->
                    if (el is JsonNull) null
                    else {
                        val o = el.jsonObject
                        ResolutionInfo(
                            width = o["width"]?.jsonPrimitive?.intOrNull ?: 0,
                            height = o["height"]?.jsonPrimitive?.intOrNull ?: 0,
                            pixels = o["pixels"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    }
                }
                ResolutionDist(
                    tiers = tiers,
                    orientation = orientation,
                    maxResolution = maxRes,
                    minResolution = minRes,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getResolutionDistribution FAILED: ${e.message}")
            null
        }
    }

    /** V21：分辨率分布单档（档次名 + 该档媒体数 + 累计字节）。 */
    data class ResolutionTier(val tier: String, val count: Int, val bytes: Long)

    /** V21：方向统计（横向 / 纵向 / 正方形）。 */
    data class Orientation(val landscape: Int, val portrait: Int, val square: Int)

    /** V21：分辨率极值（宽 + 高 + 像素总量）。 */
    data class ResolutionInfo(val width: Int, val height: Int, val pixels: Int)

    /** V21：增强版分辨率分布（四档 + 方向 + 极值 + 参与分档的未软删媒体总数）。 */
    data class ResolutionDist(
        val tiers: List<ResolutionTier>,
        val orientation: Orientation,
        val maxResolution: ResolutionInfo?,
        val minResolution: ResolutionInfo?,
        val total: Int
    )

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
     * V9：整年日历的一天数据（与后端 [handleMediaCalendarYear] 的 dayStat JSON 对齐）。
     *
     * 后端按 media.created_at 的 UTC 日期（YYYY-MM-DD）分组，仅返回有数据的天；
     * 前端按需补 count=0 的空天。结构与 [UploadDay] 同形（date/count/bytes），
     * 但语义为"整年内某天"而非"最近30天内的某天"。
     */
    data class CalendarDayData(val date: String, val count: Int, val bytes: Long)

    /**
     * V9：GET /api/media/media-calendar-year?year=N — 整年日历热力数据。
     *
     * 后端返回 `{year, days:[{date,count,bytes}], total_count, total_bytes}`，
     * 仅包含有上传记录的天（按 date 升序）。本方法解析 [days] 列表，
     * year/total_* 字段由调用方自行渲染（当前前端固定取 2026，暂不消费 total）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getUploadCalendar] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param year 目标年份；后端对非法值回退当年，故前端不做事前校验。
     * @return 该年每天的上传统计列表（仅含非零天）；失败返回 null
     */
    suspend fun getMediaCalendarYear(year: Int): List<CalendarDayData>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-calendar-year") {
                parameter("year", year)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["days"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    CalendarDayData(
                        date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else {
                logger.info("MediaService", "getMediaCalendarYear year=$year status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaCalendarYear FAILED year=$year: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * V8：GET /api/media/media-camera-stats — 拍摄设备分布统计。
     *
     * 后端按文件名前缀（IMG_/IMG-/PXL_/Screenshot/WXCam_/VIDEO_/DCIM 等）推断拍摄设备
     * 并统计每类数量与占比，倒序返回 top 15。响应（与后端 [handleMediaCameraStats] 对齐）：
     *
     * `{cameras: [{camera, count, percentage}], total}`
     *
     * - [camera] 设备/来源名（如 "Apple (IMG_)"、"截图"、"微信相机"）。
     * - [count] 该类媒体数量。
     * - [percentage] 占总量百分比（0~100，Double）。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaByHour] / [getMediaTimeAnalysis] 同语义。
     */
    suspend fun getMediaCameraStats(): List<CameraStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-camera-stats") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["cameras"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val name = o["camera"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    CameraStat(
                        camera = name,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaCameraStats FAILED: ${e.message}")
            null
        }
    }

    /** V8：拍摄设备单条统计（设备名 + 数量 + 百分比，与后端 cameras[] 对齐）。 */
    data class CameraStat(val camera: String, val count: Int, val percentage: Double)

    /**
     * V8：GET /api/media/media-filename-pattern — 文件名模式分析。
     *
     * 后端按文件名前缀（取首个分隔符 _ - 空格 . 之前部分；无分隔符取前 4 rune）分组，
     * 统计每种前缀的 count / percentage / example，按 count 倒序返回。响应（与后端
     * [handleMediaFilenamePattern] 对齐）：
     *
     * `{patterns: [{prefix, count, percentage, example}], total}`
     *
     * - [prefix] 文件名前缀（如 "IMG"、"PXL"、"Screenshot"）。
     * - [count] 该前缀下媒体数量。
     * - [percentage] 占总量百分比（0~100，Double）。
     * - [example] 该前缀下的一条示例文件名。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaCameraStats] / [getMediaByHour] 同语义。
     */
    suspend fun getMediaFilenamePattern(): List<FilenamePattern>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-filename-pattern") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["patterns"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val prefix = o["prefix"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    FilenamePattern(
                        prefix = prefix,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        example = o["example"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaFilenamePattern FAILED: ${e.message}")
            null
        }
    }

    /** V8：文件名模式单条统计（前缀 + 数量 + 百分比 + 示例，与后端 patterns[] 对齐）。 */
    data class FilenamePattern(
        val prefix: String,
        val count: Int,
        val percentage: Double,
        val example: String
    )

    /**
     * GET /api/media/media-decade-distribution — 媒体年代分布。
     *
     * 后端按 created_at（上传时间）的 UTC 年份将所有未软删媒体归入 4 个年代分桶：
     * 2020s（2020-2029）/ 2010s（2010-2019）/ 2000s（2000-2009）/ 更早（<2000）。
     * 每个年代统计 count（数量）、bytes（累计字节）、percentage（占总量百分比，0~100），
     * 另返回 total（参与统计的未软删媒体总数）。响应（与后端
     * [handleMediaDecadeDistribution] 对齐）：
     *
     * `{decades: [{decade, count, bytes, percentage}], total}`
     *
     * - [decade] 年代标签（"2020s"/"2010s"/"2000s"/"更早"）。
     * - [count] 该年代下媒体数量。
     * - [bytes] 该年代下媒体累计字节数。
     * - [percentage] 占总量百分比（0~100，Double，两位小数）。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaFilenamePattern] / [getMediaCameraStats] 同语义。
     */
    suspend fun getMediaDecadeDistribution(): List<DecadeStat>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-decade-distribution") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["decades"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val decade = o["decade"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    DecadeStat(
                        decade = decade,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaDecadeDistribution FAILED: ${e.message}")
            null
        }
    }

    /** 媒体年代分布单条统计（年代 + 数量 + 字节数 + 百分比，与后端 decades[] 对齐）。 */
    data class DecadeStat(
        val decade: String,
        val count: Int,
        val bytes: Long,
        val percentage: Double
    )

    /**
     * GET /api/media/media-weekday-analysis — 媒体星期分布。
     *
     * 后端对当前用户全部未软删媒体按 created_at（上传时间）的 UTC 星期几分组
     * （Go time.Weekday: 0=周日, 1=周一 ... 6=周六），统计每个 weekday 的上传量与
     * 占比，并找出最活跃的 weekday。响应结构（与后端
     * [handleMediaWeekdayAnalysis] 对齐）：
     *
     * `{weekdays: [{weekday, count, percentage}], most_active: {weekday, count}|null, total}`
     *
     * - [weekdays] 固定 7 项，按后端"周日→周一→...→周六"顺序返回；前端可自行重排展示。
     * - [weekday] 星期标签（"周日"/"周一"/.../"周六"）。
     * - [count] 该星期上传的媒体数量。
     * - [percentage] 占总量百分比（0~100，Double，两位小数）。
     * - [mostActive] 上传量最大的星期；total=0 时后端返回 null，前端 [mostActive] 为 null。
     * - [total] 参与分桶的未软删媒体总数。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaDecadeDistribution] / [getMediaFilenamePattern] 同语义。
     */
    suspend fun getMediaWeekdayAnalysis(): WeekdayAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-weekday-analysis") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val weekdays = obj["weekdays"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val weekday = o["weekday"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    WeekdayItem(
                        weekday = weekday,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                val mostActive = obj["most_active"]?.takeIf { it !is JsonNull }?.let { el ->
                    val mo = el.jsonObject
                    MostActive(
                        weekday = mo["weekday"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = mo["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
                WeekdayAnalysis(
                    weekdays = weekdays,
                    mostActive = mostActive,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaWeekdayAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** 媒体星期分布分析结果（7 天分布 + 最活跃日 + 总数，与后端 weekday-analysis 对齐）。 */
    data class WeekdayAnalysis(
        val weekdays: List<WeekdayItem>,
        val mostActive: MostActive?,
        val total: Int
    )

    /** 星期分布单条统计（星期标签 + 数量 + 百分比，与后端 weekdays[] 对齐）。 */
    data class WeekdayItem(
        val weekday: String,
        val count: Int,
        val percentage: Double
    )

    /** 最活跃星期（星期标签 + 数量，与后端 most_active 对齐；total=0 时为 null）。 */
    data class MostActive(
        val weekday: String,
        val count: Int
    )

    /**
     * GET /api/media/media-season-analysis — 媒体季节分布。
     *
     * 后端对当前用户全部未软删媒体按 created_at（上传时间）的 UTC 月份分到四季：
     * 春(3-5月)/夏(6-8月)/秋(9-11月)/冬(12-2月)，每季返回 count/bytes/percentage，
     * 并给出 most_active_season（按 count 取最大）。响应结构（与后端
     * [handleMediaSeasonAnalysis] 对齐）：
     *
     * `{seasons: [{season, count, bytes, percentage}], total, most_active_season}`
     *
     * - [seasons] 固定 4 项，顺序固定（春→夏→秋→冬）。
     * - [season] 季节标签（"春"/"夏"/"秋"/"冬"）。
     * - [count] 该季节上传的媒体数量。
     * - [bytes] 该季节上传媒体的累计字节数（Long）。
     * - [percentage] 占总量百分比（0~100，Double，两位小数）。
     * - [mostActiveSeason] 上传量最大的季节；total=0 时后端返回 null，前端 [mostActiveSeason] 为 null。
     * - [total] 参与分桶的未软删媒体总数。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaWeekdayAnalysis] / [getMediaDecadeDistribution] 同语义。
     */
    suspend fun getMediaSeasonAnalysis(): SeasonAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-season-analysis") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val seasons = obj["seasons"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val season = o["season"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SeasonItem(
                        season = season,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                val mostActiveSeason = obj["most_active_season"]?.takeIf { it !is JsonNull }?.let { el ->
                    el.jsonPrimitive.contentOrNull
                }
                SeasonAnalysis(
                    seasons = seasons,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    mostActiveSeason = mostActiveSeason
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSeasonAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** 媒体季节分布分析结果（四季分布 + 最活跃季 + 总数，与后端 season-analysis 对齐）。 */
    data class SeasonAnalysis(
        val seasons: List<SeasonItem>,
        val total: Int,
        val mostActiveSeason: String?
    )

    /** 季节分布单条统计（季节标签 + 数量 + 字节数 + 百分比，与后端 seasons[] 对齐）。 */
    data class SeasonItem(
        val season: String,
        val count: Int,
        val bytes: Long,
        val percentage: Double
    )

    /**
     * GET /api/media/media-timeline-events — 时间线大事件（媒体库里程碑）。
     *
     * 后端对当前用户全部未软删媒体按 created_at（上传时间）升序扫描，挑出 5 类重要
     * 节点，供前端"我的"Tab 顶部时间线卡片渲染：first_upload（首次上传）/busiest_day
     * （上传最多的一天）/longest_gap（最长上传间隔天数）/milestone_100（第 100 个上传）/
     * milestone_500（第 500 个，若总量不足则省略）。响应结构（与后端
     * [handleMediaTimelineEvents] 对齐）：
     *
     * `{events: [{type, date, detail}], total_events}`
     *
     * - [events] 按时间顺序排列，0~5 项。
     * - [type] 事件类型（"first_upload"/"busiest_day"/"longest_gap"/"milestone_100"/
     *   "milestone_500"）。
     * - [date] 事件日期（UTC `YYYY-MM-DD` 字符串，前端仅透传展示）。
     * - [detail] 事件详情（首次上传为文件名，其余为 "N uploads"/"N days"/"Nth upload"）。
     * - [total_events]，无上传历史返回空 events。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaSeasonAnalysis] / [getMediaWeekdayAnalysis] / [getMediaDecadeDistribution] 同语义。
     */
    suspend fun getMediaTimelineEvents(): List<TimelineEvent>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-timeline-events") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                Json.parseToJsonElement(response.body<String>()).jsonObject["events"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    TimelineEvent(
                        type = type,
                        date = o["date"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTimelineEvents FAILED: ${e.message}")
            null
        }
    }

    /**
     * 时间线大事件单条（事件类型 + 日期 + 详情，与后端 media-timeline-events events[] 对齐）。
     *
     * [type] 取值见 [getMediaTimelineEvents]；[date] 为 UTC 日期串；[detail] 为可读文案。
     */
    data class TimelineEvent(
        val type: String,
        val date: String,
        val detail: String
    )

    /**
     * GET /api/media/media-duration-analysis — 视频时长分布分析。
     *
     * 后端对当前用户 VIDEO 类型媒体逐条 ffprobe 取时长（秒），归入 5 个时长分段：
     * <30s / 30s-2min / 2-5min / 5-15min / >15min。每段返回 count 与 percentage
     * （相对视频总数，空集时为 0），另返回 total_videos / avg_duration / max_duration
     * （单位均为秒，Double）。ffprobe 失败或时长为 0 的条目计入 <30s 段。响应结构
     * （与后端 [handleMediaDurationAnalysis] 对齐）：
     *
     * `{tiers: [{tier, count, percentage}], total_videos, avg_duration, max_duration}`
     *
     * - [tiers] 固定 5 项，顺序固定（短→长）。
     * - [tier] 时长档位标签（"<30s"/"30s-2min"/"2-5min"/"5-15min"/">15min"）。
     * - [count] / [percentage] 该档视频数量 / 占视频总数百分比（0~100，Double）。
     * - [totalVideos] 实际取到时长的视频数。
     * - [avgDuration] / [maxDuration] 平均/最长时长（**秒**，Double），前端按需换算展示。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaWeekdayAnalysis] / [getMediaDecadeDistribution] 同语义。
     */
    suspend fun getMediaDurationAnalysis(): DurationAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-duration-analysis") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val tiers = obj["tiers"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val tier = o["tier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    DurationTier(
                        tier = tier,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                DurationAnalysis(
                    tiers = tiers,
                    totalVideos = obj["total_videos"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgDuration = obj["avg_duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    maxDuration = obj["max_duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaDurationAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** 视频时长分布分析结果（5 档分布 + 总数 + 平均/最长时长，与后端 media-duration-analysis 对齐）。 */
    data class DurationAnalysis(
        val tiers: List<DurationTier>,
        val totalVideos: Int,
        val avgDuration: Double,
        val maxDuration: Double
    )

    /** 视频时长单档统计（档位标签 + 数量 + 百分比，与后端 tiers[] 对齐）。 */
    data class DurationTier(
        val tier: String,
        val count: Int,
        val percentage: Double
    )

    /**
     * GET /api/media/media-aspect-ratio — 媒体宽高比分布分析。
     *
     * 后端按 width/height 比值将每条媒体（含图片+视频，无尺寸信息的跳过）归入
     * 4 个固定档：panorama（w/h>2.0，全景/超宽幅）/ landscape（w/h>1.2，横向）/
     * portrait（h/w>1.2，纵向）/ square（其余，近似方形）。每档返回 count 与
     * percentage（相对有尺寸媒体总数，total=0 时均为 0），另返回 total 与 most_common
     * （数量最多的一档 type；无样本时为 null）。响应结构（与后端
     * [handleMediaAspectRatio] 对齐）：
     *
     * `{ratios: [{type, count, percentage}], total, most_common}`
     *
     * - [ratios] 固定 4 项，顺序固定 panorama→landscape→portrait→square（宽→高趋势）。
     * - [type] 档位标识（"panorama"/"landscape"/"portrait"/"square"）。
     * - [count] / [percentage] 该档数量 / 占比（0~100，Double）。
     * - [total] 有尺寸信息的媒体总数（无尺寸的不计入）。
     * - [mostCommon] 最常见档位 type；无样本时为 null（前端展示时判空）。
     *
     * 失败（非 200 / 网络异常）或 total=0（无样本）返回 null，调用方按 null
     * 静默跳过卡片渲染，与 [getMediaDurationAnalysis] / [getMediaTimeAnalysis] 同语义。
     */
    suspend fun getMediaAspectRatio(): AspectRatioAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-aspect-ratio") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                if (total == 0) return null
                val ratios = obj["ratios"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    RatioItem(
                        type = type,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                // most_common 后端可为 string 或 null；null 时 contentOrNull 返回 null。
                val mostCommon = obj["most_common"]?.jsonPrimitive?.contentOrNull
                AspectRatioAnalysis(
                    ratios = ratios,
                    total = total,
                    mostCommon = mostCommon
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaAspectRatio FAILED: ${e.message}")
            null
        }
    }

    /** 宽高比分布分析结果（4 档分布 + 总数 + 最常见档位，与后端 media-aspect-ratio 对齐）。 */
    data class AspectRatioAnalysis(
        val ratios: List<RatioItem>,
        val total: Int,
        val mostCommon: String?
    )

    /** 宽高比单档统计（档位 type + 数量 + 百分比，与后端 ratios[] 对齐）。 */
    data class RatioItem(
        val type: String,
        val count: Int,
        val percentage: Double
    )

    /**
     * V22：GET /api/media/media-time-analysis — 上传 vs 拍摄延迟分析。
     *
     * 后端基于当前用户全部未软删媒体的 [taken_at]（拍摄时间，EXIF/元数据）与
     * [created_at]（上传时间）之差，统计上传相对拍摄的延迟分布。响应结构（与后端
     * [handleMediaTimeAnalysis] 对齐）：
     *
     * `{total, skipped_unknown, avg_delay_seconds, max_delay_seconds,
     *   delay_buckets: {lt_1h, 1h_24h, 1d_7d, gt_7d}, same_day_count, same_day_ratio}`
     *
     * - [total] 参与统计的有效媒体数（taken_at≠0）。
     * - [skippedUnknown] 跳过拍摄时间未知的媒体数（taken_at=0）。
     * - [avgDelaySeconds] / [maxDelaySeconds] 平均/最大延迟（**秒**，Double）。
     *   前端按需换算为分钟/小时/天展示。负延迟（时钟异常）不计入 avg/max 但保留计数。
     * - [buckets] 延迟分布四档（顺序固定，int64）：&lt;1h / 1-24h / 1-7d / &gt;7d。
     * - [sameDayCount] 拍摄与上传落在同一 UTC 日期的数量。
     * - [sameDayRatio] same_day_count / total，total=0 时为 0。
     *
     * total=0（无有效样本）或请求异常时返回 null，调用方静默跳过卡片渲染
     * （与 [getUploadPatternAnalysis] 同语义：区分"成功但空"与"失败"）。
     *
     * count/ratio 均为后端 int64/double（永不为 JSON null），用 `?: 0` / `?: 0.0` 安全默认。
     */
    suspend fun getMediaTimeAnalysis(): MediaTimeAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-time-analysis") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                if (total == 0) return null
                val dist = obj["delay_buckets"]?.jsonObject
                // 解析延迟分布四档——固定键，缺失时按 0 渲染。
                fun bucket(key: String): Int =
                    dist?.get(key)?.jsonPrimitive?.intOrNull ?: 0
                MediaTimeAnalysis(
                    total = total,
                    skippedUnknown = obj["skipped_unknown"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgDelaySeconds = obj["avg_delay_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    maxDelaySeconds = obj["max_delay_seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    buckets = DelayBuckets(
                        under1h = bucket("lt_1h"),
                        h1To24 = bucket("1h_24h"),
                        d1To7 = bucket("1d_7d"),
                        over7d = bucket("gt_7d")
                    ),
                    sameDayCount = obj["same_day_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    sameDayRatio = obj["same_day_ratio"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTimeAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** V22：延迟分布四档（<1h / 1-24h / 1-7d / >7d，与后端 delay_buckets 对齐）。 */
    data class DelayBuckets(
        val under1h: Int,
        val h1To24: Int,
        val d1To7: Int,
        val over7d: Int
    )

    /** V22：上传 vs 拍摄延迟分析结果（秒级 avg/max + 四档分布 + 同日统计 + 总数）。 */
    data class MediaTimeAnalysis(
        val total: Int,
        val skippedUnknown: Int,
        val avgDelaySeconds: Double,
        val maxDelaySeconds: Double,
        val buckets: DelayBuckets,
        val sameDayCount: Int,
        val sameDayRatio: Double
    )

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
     * V21：GET /api/media/media-size-percentile — 媒体大小百分位分析。
     *
     * 后端对当前用户所有未软删媒体的 Size 升序排序，用 nearest-rank 法计算
     * P10/P25/P50(中位数)/P75/P90，并返回 total/min_size/max_size/mean_size。
     * total<2 时各百分位回退为 0（样本不足以做有意义的分位）。
     *
     * 响应结构（与 [handleMediaSizePercentile] 对齐）：
     *   `{percentiles:{p10,p25,p50,p75,p90}, total, min_size, max_size, mean_size}`
     * percentiles 为嵌套 map；各值为后端 int64（永不为 JSON null），
     * 用 `?: 0L` 安全默认。失败返回 null，调用方按 null 静默跳过不渲染占位
     * （与 [getMediaAgeDistribution] / [getMediaArchiveStatus] 同款）。
     */
    suspend fun getMediaSizePercentile(): SizePercentile? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-size-percentile")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // percentiles 嵌套对象 → Map<String, Long>；缺失或字段非数时回退 0L。
                val pMap = mutableMapOf<String, Long>()
                obj["percentiles"]?.jsonObject?.forEach { (k, v) ->
                    pMap[k] = v.jsonPrimitive.longOrNull ?: 0L
                }
                SizePercentile(
                    percentiles = pMap,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    minSize = obj["min_size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    maxSize = obj["max_size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    meanSize = obj["mean_size"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSizePercentile FAILED: ${e.message}")
            null
        }
    }

    /** V21：媒体大小百分位（P10-P90 五档 + 总数 + 最小/最大/平均字节）。 */
    data class SizePercentile(
        val percentiles: Map<String, Long>,
        val total: Int,
        val minSize: Long,
        val maxSize: Long,
        val meanSize: Long
    )

    /**
     * 媒体质量评估单条（与后端 [handleMediaQualityAssessment] 的 scored JSON 对齐）。
     *
     * 后端对每张参与评分的 IMAGE 计算 0~100 的 quality_score
     * （分辨率归一化 50 + 文件大小归一化 30 + IMAGE 类型奖励 20），并附分辨率串。
     *
     * @param mediaId 媒体 ID
     * @param filename 文件名
     * @param score 质量评分 0~100
     * @param resolution 分辨率串（如 "3840x2160"）
     */
    data class QualityItem(
        val mediaId: String,
        val filename: String,
        val score: Double,
        val resolution: String
    )

    /**
     * 媒体质量评估结果（top/bottom 各 [limit] 条 + 全量平均分 + 参与评分总数）。
     *
     * @param top 分数最高的若干条（降序）
     * @param bottom 分数最低的若干条（升序）
     * @param avgScore 参与评分媒体的平均分（0~100）
     * @param total 参与评分的 IMAGE 媒体数
     */
    data class QualityAssessment(
        val top: List<QualityItem>,
        val bottom: List<QualityItem>,
        val avgScore: Double,
        val total: Int
    )

    /**
     * GET /api/media/media-quality-assessment?limit=N — 媒体质量评估（综合分辨率+大小+类型评分）。
     *
     * 后端对当前用户所有未软删的 IMAGE（且有非零 Width/Height）计算 quality_score（0~100）：
     *   resolution_score = min(width*height / 4K, 1) * 50
     *   size_score       = min(size / 10MB, 1) * 30
     *   type_bonus       = IMAGE → 20
     * 按分数倒序取 top、正序取 bottom，各 [limit] 条；另返回 avg_score 与 total。
     *
     * 响应结构（与 [handleMediaQualityAssessment] 对齐）：
     *   `{top:[{media_id,filename,score,resolution}], bottom:[...], avg_score, total}`
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaSizePercentile] / [getMediaColorTemperature] 同语义。
     *
     * @param limit 每档（top/bottom）返回条数，默认 10，后端上限 100
     */
    suspend fun getMediaQualityAssessment(limit: Int = 10): QualityAssessment? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-quality-assessment") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // top/bottom 为对象数组；mapNotNull 跳过缺 media_id 的无效条目（与 getRelatedMedia 同款）。
                fun parseItems(arr: JsonArray?): List<QualityItem> = arr?.mapNotNull { el ->
                    val item = el.jsonObject
                    val id = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    QualityItem(
                        mediaId = id,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        score = item["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        resolution = item["resolution"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                } ?: emptyList()
                QualityAssessment(
                    top = parseItems(obj["top"]?.jsonArray),
                    bottom = parseItems(obj["bottom"]?.jsonArray),
                    avgScore = obj["avg_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaQualityAssessment FAILED: ${e.message}")
            null
        }
    }

    /**
     * GET /api/media/media-color-temperature — 媒体色温分布。
     *
     * 后端对当前用户全部未软删媒体按拍摄时段（taken_at 毫秒；缺失=0 时回退到
     * created_at 上传时间）的 UTC 小时数推断色温基调，分三类：
     *   - warm    （暖色调）  → 晚上 18-22h 拍摄，低色温光源（室内灯/日落）主导
     *   - cool    （冷色调）  → 早上 6-10h 拍摄，高色温日光主导
     *   - natural （自然光）  → 其他时段，日光/混合光难以判定
     *
     * 响应结构（与 [handleMediaColorTemperature] 对齐）：
     *   `{distribution:{warm, cool, natural}, total, dominant}`
     * - [distribution] 三类色温的计数（Int）。
     * - [total] 参与分桶的未软删媒体总数。
     * - [dominant] count 最大的类别（"warm"/"cool"/"natural"）；total=0 时后端返回
     *   JSON null，前端 [dominant] 为 null。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaSizePercentile] / [getMediaSeasonAnalysis] 同语义。
     */
    suspend fun getMediaColorTemperature(): ColorTemperature? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-color-temperature")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // distribution 嵌套对象 → 三个计数；缺失或字段非数时回退 0。
                val dist = obj["distribution"]?.jsonObject
                val warm = dist?.get("warm")?.jsonPrimitive?.intOrNull ?: 0
                val cool = dist?.get("cool")?.jsonPrimitive?.intOrNull ?: 0
                val natural = dist?.get("natural")?.jsonPrimitive?.intOrNull ?: 0
                // dominant 为字符串或 JSON null（total=0 时）；takeIf 排除 JsonNull。
                val dominant = obj["dominant"]?.takeIf { it !is JsonNull }?.let { el ->
                    el.jsonPrimitive.contentOrNull
                }
                ColorTemperature(
                    distribution = mapOf("warm" to warm, "cool" to cool, "natural" to natural),
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    dominant = dominant
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaColorTemperature FAILED: ${e.message}")
            null
        }
    }

    /** 媒体色温分布（暖/冷/自然三档计数 + 总数 + 主导色调）。 */
    data class ColorTemperature(
        val distribution: Map<String, Int>,
        val total: Int,
        val dominant: String?
    )

    /**
     * GET /api/media/media-gps-estimate — 拍摄地点估算。
     *
     * 后端对当前用户全部未软删媒体按文件名模式 + 拍摄时间做启发式地点类型推断
     *（Media model 无 GPS 字段，故为估算而非精确坐标），分四类：
     *   - indoor   （室内）  → 截屏 / 微信相机 / IMG_ 工作日非核心时段
     *   - outdoor  （户外）  → IMG_ 周末拍摄
     *   - office   （办公）  → IMG_ 工作日 9-18h 核心时段
     *   - unknown  （未知）  → 无法识别的文件名模式
     *
     * 时间口径：优先 taken_at（毫秒时间戳），缺失(=0)回退 created_at，统一 UTC。
     *
     * 响应结构（与 [handleMediaGpsEstimate] 对齐）：
     *   `{locations:{indoor, outdoor, office, unknown}, total, dominant}`
     * - [locations] 四类地点的计数（Int）。
     * - [total] 参与推断的未软删媒体总数。
     * - [dominant] count 最大的类别（"indoor"/"outdoor"/"office"/"unknown"）；
     *   total=0 时后端返回 JSON null，前端 [dominant] 为 null。
     *
     * 失败（非 200 / 网络异常）返回 null，调用方按 null 静默跳过卡片渲染，
     * 与 [getMediaColorTemperature] / [getMediaSizePercentile] 同语义。
     */
    suspend fun getMediaGpsEstimate(): GpsEstimate? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-gps-estimate")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // locations 嵌套对象 → 四个计数；缺失或字段非数时回退 0。
                val loc = obj["locations"]?.jsonObject
                val indoor = loc?.get("indoor")?.jsonPrimitive?.intOrNull ?: 0
                val outdoor = loc?.get("outdoor")?.jsonPrimitive?.intOrNull ?: 0
                val office = loc?.get("office")?.jsonPrimitive?.intOrNull ?: 0
                val unknown = loc?.get("unknown")?.jsonPrimitive?.intOrNull ?: 0
                // dominant 为字符串或 JSON null（total=0 时）；takeIf 排除 JsonNull。
                val dominant = obj["dominant"]?.takeIf { it !is JsonNull }?.let { el ->
                    el.jsonPrimitive.contentOrNull
                }
                GpsEstimate(
                    locations = mapOf(
                        "indoor" to indoor,
                        "outdoor" to outdoor,
                        "office" to office,
                        "unknown" to unknown
                    ),
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    dominant = dominant
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaGpsEstimate FAILED: ${e.message}")
            null
        }
    }

    /** 拍摄地点估算（室内/户外/办公/未知四类计数 + 总数 + 主导地点）。 */
    data class GpsEstimate(
        val locations: Map<String, Int>,
        val total: Int,
        val dominant: String?
    )

    /**
     * GET /api/media/media-mood-analysis — 照片心情分析。
     *
     * 后端对当前用户全部未软删媒体，按拍摄时段（taken_at 毫秒；缺失=0 回退 created_at）
     * 的 UTC 小时推断照片"心情"，统计四类分布（[handleMediaMoodAnalysis] 与色温/地点
     * 同类端点口径一致，统一 UTC）：
     *   - 清晨清新 → 6-10h   🌅
     *   - 午后活力 → 10-16h  ☀️
     *   - 黄昏浪漫 → 16-19h  🌇
     *   - 夜晚神秘 → 19-6h   🌃
     *
     * 响应结构：`{moods:[{mood,count,percentage,emoji}], total, dominant_mood}`
     * - 后端按 count 降序输出（count 相同保持定义顺序），故返回列表首位即主调。
     * - [total] 为 0 时 dominant_mood 为 JSON null，此时 moods 各 count 均为 0；
     *   前端按 total==0 静默跳过卡片渲染（与 [getMediaGpsEstimate] 同语义）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaGpsEstimate] 同款）。HTTP 非 200 / 网络异常返回 null，调用方按空态处理。
     *
     * @return 心情分桶列表（按 count 降序）；失败返回 null。主调 = 首位（total>0 时）。
     */
    suspend fun getMediaMoodAnalysis(): List<MoodItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-mood-analysis")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // moods 为对象数组 [{mood,count,percentage,emoji}]；缺失或非数组时返回 null。
                obj["moods"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    // mood 缺失则整条丢弃（无意义），其余字段宽容回退。
                    val mood = item["mood"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    MoodItem(
                        mood = mood,
                        count = item["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        percentage = item["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        emoji = item["emoji"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getMediaMoodAnalysis status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaMoodAnalysis FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 照片心情分桶（mood 名称 + 计数 + 百分比 + emoji）。 */
    data class MoodItem(
        val mood: String,
        val count: Int,
        val percentage: Double,
        val emoji: String
    )

    /**
     * GET /api/media/media-content-diversity — 媒体内容多样性评分（Shannon 熵四维度）。
     *
     * 后端对当前用户全部未软删媒体，按四维度计算 Shannon 熵并归一化（entropy / ln(k)，
     * k 为该维度类别数；单一类别时熵为 0、归一化为 0；均匀分布时归一化为 1）：
     *   - type  IMAGE / VIDEO / LIVE_PHOTO 分布的类型熵
     *   - mime  各 MIME 类型分布的 MIME 熵（如 image/jpeg、video/mp4）
     *   - hour  按 created_at 所在小时（0-23）分布的时段熵
     *   - tag   各标签关联媒体数分布的标签熵（来自 TagStats）
     *
     * 综合多样性 = average(各维度归一化熵)，等级 A(>=0.8) / B(>=0.6) / C(>=0.4) / D(<0.4)；
     * total=0 时 score=0 grade=D 各维度熵均为 0。
     *
     * 响应结构：`{diversity_score: Double(0-1), grade: String,
     *   breakdown: {type:{entropy,categories,distribution}, mime:{...}, hour:{...}, tag:{...}},
     *   total: Int}`
     * - [diversityScore] 为 0-1 归一化值，UI 显示时乘 100 体现为百分制。
     * - [breakdown] 仅取各维度的归一化熵值，类别数与分布详情
     *   前端卡片暂不展示（保持四维度卡片简洁，与照片心情/色温等同密度）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaMoodAnalysis] 同款）。HTTP 非 200 / 网络异常返回 null，调用方按空态处理。
     *
     * @return 多样性评分聚合；失败返回 null。total=0 时由 UI 静默跳过。
     */
    suspend fun getMediaContentDiversity(): ContentDiversity? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-content-diversity")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val breakdown = obj["breakdown"]?.jsonObject
                // 每维度结构 {entropy: Double, categories: Int, distribution: {k:v}}；
                // 前端只取 entropy（归一化 0-1），缺失维度默认 0.0 不会误判为高多样性。
                fun dim(key: String): Double =
                    breakdown?.get(key)?.jsonObject?.get("entropy")?.jsonPrimitive?.doubleOrNull ?: 0.0
                ContentDiversity(
                    diversityScore = obj["diversity_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    grade = obj["grade"]?.jsonPrimitive?.contentOrNull ?: "D",
                    breakdown = mapOf(
                        "type" to dim("type"),
                        "mime" to dim("mime"),
                        "hour" to dim("hour"),
                        "tag" to dim("tag")
                    ),
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaContentDiversity status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaContentDiversity FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 媒体内容多样性评分（综合多样性 0-1 + 等级 + 四维度归一化熵 + 媒体总数）。 */
    data class ContentDiversity(
        val diversityScore: Double,
        val grade: String,
        val breakdown: Map<String, Double>,
        val total: Int
    )

    /**
     * GET /api/media/media-monthly-highlights?months=N — 月度亮点（最近 N 个月，每月
     * 首上传 / 最大文件 / 末上传）。仅基于 created_at 一次全量拉取后在 Go 侧聚合，只读端点。
     *
     * 后端响应（与 [handleMediaMonthlyHighlights] 对齐）：
     *   `{months: [{month, first, largest, last}], total_months}`
     * 其中 month 为 "YYYY-MM"；first/last 为 `{id, filename}`（无 size），
     * largest 为 `{id, filename, size}`；某月无媒体时对应字段为 null（前端按 null 跳过该行）。
     *
     * @param months 查询月数，默认 6，透传 query（?months=N），后端钳制 [1,120]。
     * @return 月份亮点列表（按后端顺序，最近月在最前）；非 200 / 异常返回 null，调用方按 null 静默跳过。
     */
    suspend fun getMediaMonthlyHighlights(months: Int = 6): List<MonthlyHighlight>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-monthly-highlights") {
                parameter("months", months)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["months"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    val month = o["month"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    // first/last 仅 {id, filename}；缺失（null）时返回 null 字段。
                    fun parseMedia(key: String): HighlightMedia? {
                        val hm = o[key]?.jsonObject ?: return null
                        return HighlightMedia(
                            id = hm["id"]?.jsonPrimitive?.contentOrNull ?: return null,
                            filename = hm["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                            size = hm["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    }
                    MonthlyHighlight(
                        month = month,
                        first = parseMedia("first"),
                        largest = parseMedia("largest"),
                        last = parseMedia("last")
                    )
                }
            } else {
                logger.info("MediaService", "getMediaMonthlyHighlights status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaMonthlyHighlights FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 月度亮点单项：月份键 + 首个 / 最大 / 末个媒体引用（任一可为 null 表示该月无对应）。 */
    data class MonthlyHighlight(
        val month: String,
        val first: HighlightMedia?,
        val largest: HighlightMedia?,
        val last: HighlightMedia?
    )

    /** 亮点媒体引用：id + 文件名 + 字节数（first/last 后端不返回 size，默认 0）。 */
    data class HighlightMedia(
        val id: String,
        val filename: String,
        val size: Long = 0L
    ) {
        /** 字节数转 MB 字符串（一位小数，commonMain 无 String.format，沿用 take 截断）。 */
        val sizeMB: String
            get() {
                val s = (size.toDouble() / (1024.0 * 1024.0)).toString()
                return s.take(s.indexOf('.') + 2)
            }
    }

    /**
     * GET /api/media/media-upload-ranking?limit=N — 上传日排行 top N。
     *
     * 后端按 created_at 的 UTC 日期分组，统计每日上传 count/bytes，按 count 倒序
     * （并列按 date 升序）取 top N（默认 10，钳制 [1,100]）。响应（与
     * [handleMediaUploadRanking] 对齐）：
     *   `{ranking: [{rank, date, count, bytes}], total_days, top_day: {date, count}}`
     *
     * [ranking] 已按 count 降序带 rank（1-based）；[totalDays] 为有上传记录的总天数；
     * [topDay] 为冠军日（无数据时 date="" count=0）。前端"上传排行"卡片取 top 5 渲染。
     *
     * @param limit 返回上限，默认 10，透传 query（?limit=N），后端钳制 [1,100]。
     * @return 上传排行聚合，或 null（非 200/异常，调用方静默跳过卡片）。
     */
    suspend fun getMediaUploadRanking(limit: Int = 10): UploadRanking? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-upload-ranking") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val ranking = obj["ranking"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    RankItem(
                        date = date,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        rank = o["rank"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                val topDayObj = obj["top_day"]?.jsonObject
                UploadRanking(
                    ranking = ranking,
                    totalDays = obj["total_days"]?.jsonPrimitive?.intOrNull ?: 0,
                    topDay = TopDay(
                        date = topDayObj?.get("date")?.jsonPrimitive?.contentOrNull ?: "",
                        count = topDayObj?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    )
                )
            } else {
                logger.info("MediaService", "getMediaUploadRanking status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaUploadRanking FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 上传日排行聚合：排行榜 + 总天数 + 冠军日。复用既有 [TopDay]（date+count，与年度 topDay 同构）。 */
    data class UploadRanking(
        val ranking: List<RankItem>,
        val totalDays: Int,
        val topDay: TopDay
    )

    /** 排行单项：日期 + 上传数 + 字节数 + 名次（1-based）。 */
    data class RankItem(
        val date: String,
        val count: Int,
        val bytes: Long,
        val rank: Int
    ) {
        /** 字节数转 MB 字符串（一位小数，commonMain 无 String.format，沿用 take 截断）。 */
        val bytesMB: String
            get() {
                val s = (bytes.toDouble() / (1024.0 * 1024.0)).toString()
                return s.take(s.indexOf('.') + 2)
            }
    }

    /**
     * GET /api/media/media-upload-heatmap?days=N — 上传热力图（日期 × 时段二维）。
     *
     * 后端按 created_at（上传时间）的 UTC 日期(YYYY-MM-DD) × 时段二维分桶，返回最近
     * [days] 天（默认 90，钳制 [1,365]）内每个非空格的上传数量。响应（与
     * [handleMediaUploadHeatmap] 对齐）：
     *   `{heatmap: [{date, slot, count}], total_slots, max_count, days_covered}`
     *
     * 时段定义（slot 为 0~3 的整数索引，按 UTC 小时段划分）：0→0-5 深夜 / 1→6-11 上午 /
     * 2→12-17 下午 / 3→18-23 晚上。后端只返回有数据的格（count>0），按 date 升序、
     * slot 升序排列；空格前端按需补 0。
     *
     * [heatmap] 为非空格列表（可能为空）；[totalSlots] = heatmap 长度；
     * [maxCount] 为单格最大上传数（全空时为 0）；[daysCovered] 为至少有一条上传的独立日期数。
     * 窗口内无上传时 [heatmap] 为空，[totalSlots]/[maxCount]/[daysCovered] 同步为 0。
     *
     * @param days 时间窗口天数，默认 90，透传 query（?days=N），后端钳制 [1,365]。
     * @return 上传热力图聚合，或 null（非 200/异常，调用方静默跳过卡片）。
     */
    suspend fun getMediaUploadHeatmap(days: Int = 90): UploadHeatmap? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-upload-heatmap") {
                parameter("days", days)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val heatmap = obj["heatmap"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    HeatCell(
                        date = date,
                        slot = o["slot"]?.jsonPrimitive?.intOrNull ?: 0,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                UploadHeatmap(
                    heatmap = heatmap,
                    totalSlots = obj["total_slots"]?.jsonPrimitive?.intOrNull ?: 0,
                    maxCount = obj["max_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    daysCovered = obj["days_covered"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaUploadHeatmap status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaUploadHeatmap FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 上传热力图聚合：非空格列表 + 非空格数 + 单格最大上传数 + 覆盖独立日期数。 */
    data class UploadHeatmap(
        val heatmap: List<HeatCell>,
        val totalSlots: Int,
        val maxCount: Int,
        val daysCovered: Int
    )

    /** 热力图单格：日期(YYYY-MM-DD) + 时段索引(0~3) + 上传数量。 */
    data class HeatCell(
        val date: String,
        val slot: Int,
        val count: Int
    )

    /**
     * GET /api/media/media-upload-velocity — 最近 N 天的上传速度分析。
     *
     * 后端对窗口内未软删媒体按 UTC 日期分桶，返回：
     *   - avg_per_day   窗口内平均每日上传量（四舍五入两位小数，Double）。
     *   - total         窗口内上传总量（Int）。
     *   - days          窗口天数（Int，默认 7，钳制 [1,365]）。
     *   - window_start / window_end  窗口起止日期（"YYYY-MM-DD" UTC）。
     *   - max_day       上传最多的一天 {date, count}（全 0 → {date:"", count:0}）。
     *   - peak_days     超过 avg*1.5 的高峰日 [{date, count, ratio}]（按 date 升序；可为空）。
     *   - peak_threshold / peak_multiplier  高峰判定阈值与倍数（仅信息用，前端可不显示）。
     *
     * days 参数透传 query（?days=N）；非 200 / 异常返回 null，调用方按 null 静默跳过。
     */
    suspend fun getMediaUploadVelocity(days: Int = 7): UploadVelocity? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-upload-velocity") {
                parameter("days", days)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                // max_day 嵌套对象 {date, count}；缺失时退化为默认（空串 + 0）。
                val md = obj["max_day"]?.jsonObject
                val maxDay = MaxDay(
                    date = md?.get("date")?.jsonPrimitive?.contentOrNull ?: "",
                    count = md?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                )
                // peak_days 为对象数组 [{date, count, ratio}]；缺失或非数组时返回空列表。
                val peaks = obj["peak_days"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val date = o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PeakDay(
                        date = date,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        ratio = o["ratio"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                UploadVelocity(
                    avgPerDay = obj["avg_per_day"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    days = obj["days"]?.jsonPrimitive?.intOrNull ?: days,
                    windowStart = obj["window_start"]?.jsonPrimitive?.contentOrNull ?: "",
                    windowEnd = obj["window_end"]?.jsonPrimitive?.contentOrNull ?: "",
                    maxDay = maxDay,
                    peakDays = peaks,
                    peakThreshold = obj["peak_threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaUploadVelocity FAILED: ${e.message}")
            null
        }
    }

    /** 最近 N 天上传速度分析（平均/最高/高峰日列表）。 */
    data class UploadVelocity(
        val avgPerDay: Double,
        val total: Int,
        val days: Int,
        val windowStart: String,
        val windowEnd: String,
        val maxDay: MaxDay,
        val peakDays: List<PeakDay>,
        val peakThreshold: Double
    )

    /** 上传最多的一天。 */
    data class MaxDay(
        val date: String,
        val count: Int
    )

    /** 高峰日（超过平均 1.5 倍）；ratio = count / avg。 */
    data class PeakDay(
        val date: String,
        val count: Int,
        val ratio: Double
    )

    /**
     * GET /api/media/media-upload-pattern — 上传模式深度分析（四维度上传习惯画像）。
     *
     * 后端对当前用户全部未软删媒体（基于 created_at 上传时间，UTC）做四维度画像，
     * 供前端一键展示用户上传模式与主导模式（dominant_pattern），避免前端并发拉
     * weekday/by-hour/upload-velocity 再自己组合判断。四维度：
     *   1. 工作日 vs 周末：Sun/Sat 为周末，Mon-Fri 为工作日（按 media 计数）。
     *   2. 白天 vs 夜晚：UTC 小时 [6,18) 为白天，其余为夜晚（按 media 计数）。
     *   3. 批量日 vs 单张日：同一 UTC 日上传 >=5 张为批量日，<5 张为单张日（按天计数）。
     *   4. 平均上传间隔：全部媒体按 created_at 升序，相邻两条间隔小时数取平均（媒体<2 时为 0）。
     *
     * 响应结构（与 [handleMediaUploadPattern] 对齐，扁平标量聚合，无嵌套对象/数组）：
     *   `{weekday, weekend, daytime, nighttime, batch_days, single_days, total_days,
     *      avg_interval_hours, total_media, dominant_pattern}`
     * - [dominantPattern] 由前三维度各自取较大一侧拼接（如"工作日·白天·批量上传"）；
     *   total=0 时后端返回字面量"无数据"，前端按 [totalMedia]==0 静默跳过卡片渲染，
     *   与 [getMediaColorTemperature] / [getMediaGpsEstimate] 同语义（UI guard total>0）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaGpsEstimate] 同款），无 per-call auth 头（由 defaultRequest 统一注入）。
     * HTTP 非 200 / 网络异常返回 null，调用方按空态处理。
     */
    suspend fun getMediaUploadPattern(): MediaUploadPattern? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-upload-pattern")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaUploadPattern(
                    weekday = obj["weekday"]?.jsonPrimitive?.intOrNull ?: 0,
                    weekend = obj["weekend"]?.jsonPrimitive?.intOrNull ?: 0,
                    daytime = obj["daytime"]?.jsonPrimitive?.intOrNull ?: 0,
                    nighttime = obj["nighttime"]?.jsonPrimitive?.intOrNull ?: 0,
                    batchDays = obj["batch_days"]?.jsonPrimitive?.intOrNull ?: 0,
                    singleDays = obj["single_days"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalDays = obj["total_days"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgIntervalHours = obj["avg_interval_hours"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    totalMedia = obj["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    dominantPattern = obj["dominant_pattern"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaUploadPattern FAILED: ${e.message}")
            null
        }
    }

    /**
     * 上传模式画像（工作日/周末 · 白天/夜晚 · 批量日/单张日 · 平均间隔 + 主导模式）。
     * [totalMedia] 为参与统计的未软删媒体总数；0 时主导模式为"无数据"。
     */
    data class MediaUploadPattern(
        val weekday: Int,
        val weekend: Int,
        val daytime: Int,
        val nighttime: Int,
        val batchDays: Int,
        val singleDays: Int,
        val totalDays: Int,
        val avgIntervalHours: Double,
        val totalMedia: Int,
        val dominantPattern: String
    )

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
     * V22：GET /api/media/archive-suggest — 智能归档建议。
     *
     * 后端扫描当前用户全部未软删媒体，挑出同时满足"冷数据（上传 >180 天）+ 大视频
     * （>50MB）"的条目作为归档候选，按大小倒序排列，给出累计可释放空间。
     *
     * 响应：`{should_archive, media_to_archive:[{media_id,filename,size,age_days,type}],
     * total_count, potential_savings_mb}`。请求失败（非 200 / 异常）返回 null，调用方
     * 静默跳过卡片渲染（非阻塞式设置项）。
     */
    suspend fun getArchiveSuggest(): ArchiveSuggest? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/archive-suggest") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val items = obj["media_to_archive"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val mid = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ArchiveItem(
                        mediaId = mid,
                        filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        ageDays = o["age_days"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                ArchiveSuggest(
                    shouldArchive = obj["should_archive"]?.jsonPrimitive?.booleanOrNull ?: false,
                    mediaToArchive = items,
                    totalCount = obj["total_count"]?.jsonPrimitive?.intOrNull ?: items.size,
                    potentialSavingsMb = obj["potential_savings_mb"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getArchiveSuggest FAILED: ${e.message}")
            null
        }
    }

    /** V22：智能归档建议中的单个候选项（冷数据 + 大视频，后端已按 size 倒序）。 */
    data class ArchiveItem(
        val mediaId: String,
        val filename: String,
        val size: Long,
        val ageDays: Int
    )

    /** V22：智能归档建议响应（是否需归档 + 候选列表 + 总数 + 可释放空间 MB）。 */
    data class ArchiveSuggest(
        val shouldArchive: Boolean,
        val mediaToArchive: List<ArchiveItem>,
        val totalCount: Int,
        val potentialSavingsMb: Double
    )

    /**
     * V26：GET /api/media/media-archive-recommend-v2 — 归档建议V2（多维度评分）。
     *
     * 后端对全量未软删 media 按 5 个维度累加 archive_score（分数越高越建议归档）：
     * 年龄>180天(+30) / 无标签(+20) / 不在相册(+20) / 大小>10MB(+15) / 非收藏(+15)。
     * 按 score 倒序、并列按 size 倒序返回。
     *
     * 响应：`{recommendations:[{media_id,filename,score,size,age_days,type,reasons:[...]}],
     * total, potential_savings_bytes, potential_savings_mb, scored_count, limit,
     * dimensions:{...}, user_id}`。
     *
     * 本方法返回**原始 JSON 字符串**（简化：不在此层解析为 data class，由调用方
     * [com.wgt.media.SettingsScreen] 的 [ArchiveRecommendV2Card] 自行运行时解析展示），
     * 与其他返回强类型 data class 的端点风格略异，但符合任务要求的简化口径。
     * HTTP 非 200 / 网络异常返回 null，调用方静默跳过卡片渲染（非阻塞设置项）。
     *
     * @param limit 返回上限（默认 20，后端钳制 [1,200]）
     * @return 后端原始 JSON 字符串；失败返回 null
     */
    suspend fun getMediaArchiveRecommendV2(limit: Int = 20): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-archive-recommend-v2") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<String>()
            } else {
                logger.info("MediaService", "getMediaArchiveRecommendV2 status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaArchiveRecommendV2 FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V27：GET /api/media/media-cleanup-plan — 一键清理计划（多来源合并的优先级清单）。
     *
     * 后端合并五大清理来源为单一执行清单，按 score 倒序排序：
     *   - exact_duplicates  (score=100) 相同 SHA256 完全重复；action="delete_duplicate"
     *   - orphan_files      (score=90)  DB 有记录但磁盘文件缺失；action="cleanup_orphan"
     *   - near_duplicates   (score=70)  近似重复对；action="review_near_duplicate"
     *   - error_files       (score=60)  size<=0 或 size 不符；action="fix_error_file"
     *   - archive_candidates(score=40)  >180天 且 >=50MB 旧大文件；action="archive_old_large"
     *
     * 响应结构：
     * `{plan:[{type,media_id,filename,score,action,reclaimable_bytes,...}],
     *  total_items, total_reclaimable_bytes, estimated_time_min, source_counts:{...},
     *  disk_check, user_id}`。total_items/total_reclaimable_bytes 为截断前全量汇总。
     *
     * 本方法返回**原始 JSON 字符串**（简化：不在此层解析为 data class，由调用方
     * [com.wgt.media.SettingsScreen] 的 [com.wgt.media.CleanupPlanCard] 自行运行时解析），
     * 与 [getMediaArchiveRecommendV2] 同款口径。HTTP 非 200 / 网络异常返回 null，
     * 调用方静默跳过卡片渲染（非阻塞设置项）。
     *
     * @param limit 返回上限（默认 20，后端钳制 [0,500]）
     * @return 后端原始 JSON 字符串；失败返回 null
     */
    suspend fun getMediaCleanupPlan(limit: Int = 20): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-cleanup-plan") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                response.body<String>()
            } else {
                logger.info("MediaService", "getMediaCleanupPlan status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaCleanupPlan FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V25：照片组织建议单条（GET /api/media/photo-organize-suggest 返回）。
     *
     * 后端从 月份(by_month)/类型(by_type)/未标签(untagged) 三个维度对全量媒体做
     * 整理诊断并给出可操作建议，字段与后端 `organizeSuggestion` 结构对齐：
     * - [type] 分组类型：`by_month` | `by_type` | `untagged`
     * - [name] 建议相册/分组名（形如"2026年七月"/"视频合集"/"待整理"）
     * - [mediaCount] 该建议覆盖的媒体数
     * - [reason] 人类可读的生成理由（如"该月共有 8 张媒体，建议创建相册集中管理"）
     * - [previewIds] 命中媒体的预览 id（最多 4 个，按 created_at 倒序），供"一键创建相册"
     *   时作为相册初始成员（与 [AlbumSuggestion.previewIds] 同口径，仅预览而非全量 media id）
     */
    data class OrganizeSuggestion(
        val type: String = "",
        val name: String = "",
        val mediaCount: Int = 0,
        val reason: String = "",
        val previewIds: List<String> = emptyList()
    )

    /**
     * V25：GET /api/media/photo-organize-suggest — 照片组织建议。
     *
     * 后端分析当前用户媒体库，从 月份/类型/未标签 三个维度给出可供"一键创建相册"
     * 或批量打标签的组织建议。只读端点，不修改数据。响应：
     *
     * `{ suggestions: [{type, name, media_count, reason, preview_ids:[...]}], total }`
     *
     * 后端不可用/出错时返回 null（与 [getArchiveSuggest] 同语义——区分"成功但空"
     * 与"网络失败"），调用方据此决定是否渲染组织建议卡片。
     *
     * @return 建议列表（成功，可能为空），或 null（失败）
     */
    suspend fun getPhotoOrganizeSuggest(): List<OrganizeSuggestion>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/photo-organize-suggest") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                val obj = Json.parseToJsonElement(body).jsonObject
                val arr = obj["suggestions"]?.jsonArray ?: JsonArray(emptyList())
                arr.map { el ->
                    val o = el.jsonObject
                    OrganizeSuggestion(
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        reason = o["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                        previewIds = o["preview_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                            ?: emptyList()
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getPhotoOrganizeSuggest FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * GET /api/media/share-analytics — 分享链接分析统计。
     *
     * 后端返回 `{total, active, expired, password_protected, expiring_soon,
     * active_percentage, user_id}`，前端取前六项渲染"分享分析"卡片
     * （总分享 / 活跃率 / 即将过期 / 密码保护）。`active_percentage` 已由后端
     * 保留两位小数（active/total*100，total=0 时为 0）。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过卡片，不崩溃"我的"Tab。
     */
    suspend fun getShareAnalytics(): ShareAnalytics? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/share-analytics") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                ShareAnalytics(
                    total = o["total"]?.jsonPrimitive?.intOrNull ?: 0,
                    active = o["active"]?.jsonPrimitive?.intOrNull ?: 0,
                    expired = o["expired"]?.jsonPrimitive?.intOrNull ?: 0,
                    passwordProtected = o["password_protected"]?.jsonPrimitive?.intOrNull ?: 0,
                    expiringSoon = o["expiring_soon"]?.jsonPrimitive?.intOrNull ?: 0,
                    activePercentage = o["active_percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getShareAnalytics FAILED: ${e.message}")
            null
        }
    }

    /** 分享分析统计（[getShareAnalytics] 返回）。 */
    data class ShareAnalytics(
        val total: Int,
        val active: Int,
        val expired: Int,
        val passwordProtected: Int,
        val expiringSoon: Int,
        val activePercentage: Double
    )

    /**
     * GET /api/media/media-share-activity?days=N — 分享活动时序统计。
     *
     * 与 [getShareAnalytics]（仅返聚合计数）互补：本端点返回最近 N 天（默认 30，
     * 钳制 [1, 365]）每日新建分享数的时间序列及整体趋势方向，供"我的"Tab
     * "分享活动趋势"卡片渲染趋势箭头 + 7 天柱状图。
     *
     * 后端返回 `{activity:[{date, count}], total_shares, active_shares,
     * expired_shares, trend, days}`：
     *   - activity     : 升序的 N 个 `{date:"YYYY-MM-DD", count:Int}`，含 count=0
     *                    的零创建日（窗口按 UTC 日期分桶）；落在窗口之外的较早
     *                    分享仍计入 total_shares 等汇总但不进入此序列；
     *   - total_shares : 分享链接总数；
     *   - active_shares: 未过期分享数（含永不过期 + 过期晚于 now）；
     *   - expired_shares: 已过期分享数（永不过期不计入）；
     *   - trend        : "up" | "down" | "stable"（后半窗口日均 vs 前半窗口日均）；
     *   - days         : 实际窗口天数（前端不解析，仅用于核对）。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过卡片，不崩溃"我的"Tab。
     */
    suspend fun getMediaShareActivity(days: Int = 30): ShareActivity? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-share-activity") {
                parameter("days", days)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val activity = o["activity"]?.jsonArray?.mapNotNull { el ->
                    val d = el.jsonObject
                    ShareActivityDay(
                        date = d["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = d["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                ShareActivity(
                    activity = activity,
                    totalShares = o["total_shares"]?.jsonPrimitive?.intOrNull ?: 0,
                    activeShares = o["active_shares"]?.jsonPrimitive?.intOrNull ?: 0,
                    expiredShares = o["expired_shares"]?.jsonPrimitive?.intOrNull ?: 0,
                    trend = o["trend"]?.jsonPrimitive?.contentOrNull ?: "stable"
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaShareActivity FAILED: ${e.message}")
            null
        }
    }

    /** 分享活动单日（[getMediaShareActivity] 返回的 activity 序列元素）。 */
    data class ShareActivityDay(
        val date: String,
        val count: Int
    )

    /** 分享活动统计（[getMediaShareActivity] 返回）。trend 取值 "up"|"down"|"stable"。 */
    data class ShareActivity(
        val activity: List<ShareActivityDay>,
        val totalShares: Int,
        val activeShares: Int,
        val expiredShares: Int,
        val trend: String
    )

    /**
     * GET /api/media/share-expiring — 即将过期（7 天内）分享链接明细列表。
     *
     * 与 [getShareAnalytics]（仅返聚合的 `expiring_soon` 计数）互补：本端点返回
     * 每条即将过期分享的具体信息（token / expires_at / days_left / media_id?），
     * 供"我的"Tab"即将过期分享"卡片逐条渲染提醒。
     *
     * 后端返回 `{expiring:[{token, expires_at, days_left, media_id?}], total}`：
     *   - expires_at : RFC3339（UTC）字符串；
     *   - days_left  : 距过期剩余整天数（后端向上取整、下界 1，前端原样展示）；
     *   - media_id   : 可选，无媒体或后端省略时为 null。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过卡片，不崩溃"我的"Tab。
     */
    suspend fun getShareExpiring(): List<ShareExpiringItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/share-expiring") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val arr = o["expiring"]?.jsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val it = el.jsonObject
                    val token = it["token"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val expiresAt = it["expires_at"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val daysLeft = it["days_left"]?.jsonPrimitive?.intOrNull ?: 0
                    val mediaId = it["media_id"]?.let { mid ->
                        if (mid is JsonNull) null else mid.jsonPrimitive.contentOrNull
                    }
                    ShareExpiringItem(
                        token = token,
                        expiresAt = expiresAt,
                        daysLeft = daysLeft,
                        mediaId = mediaId
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getShareExpiring FAILED: ${e.message}")
            null
        }
    }

    /** 即将过期分享项（[getShareExpiring] 返回的单个元素）。 */
    data class ShareExpiringItem(
        val token: String,
        val expiresAt: String,
        val daysLeft: Int,
        val mediaId: String?
    )

    /**
     * GET /api/media/media-share-deep-analysis — 分享深度分析。
     *
     * 与 [getShareAnalytics]（聚合 6 计数）/ [getMediaShareActivity]（30 天时序趋势）
     * 互补：本端点单次返回分享全貌的"完整统计"——聚合摘要 + 按媒体类型分布 +
     * 平均有效期天数，供"我的"Tab"分享深度分析"卡片渲染一卡综览。
     *
     * 后端返回 `{summary:{total, active, expired, password_protected},
     * by_type:{"IMAGE":N,"VIDEO":N,...}, by_month:{...}, avg_lifetime_days:float}`：
     *   - summary         : 聚合计数对象（4 字段）；
     *   - by_type         : 按媒体类型分桶的分享数量映射（key 为 "IMAGE"/"VIDEO"/...）；
     *   - by_month        : 按月份分桶（前端不展示，端点附带返回，故丢弃）；
     *   - avg_lifetime_days: 分享链接平均有效期天数（永不过期链接按其设定值或
     *                        一个标称上限参与均值；0 表示无有效样本）。
     *
     * 任务 spec 注：后端端点即将上线（commit 跟进），前端解析层已逐字段宽容回退
     * （每个 JSON key 带 `?: 0`/`?: 0.0`/`?: emptyMap()` 兜底），待后端就绪即可联调。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过卡片，不崩溃"我的"Tab。
     */
    suspend fun getMediaShareDeepAnalysis(): ShareDeepAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-share-deep-analysis")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val s = o["summary"]?.jsonObject
                val summary = ShareSummary(
                    total = s?.get("total")?.jsonPrimitive?.intOrNull ?: 0,
                    active = s?.get("active")?.jsonPrimitive?.intOrNull ?: 0,
                    expired = s?.get("expired")?.jsonPrimitive?.intOrNull ?: 0,
                    passwordProtected = s?.get("password_protected")?.jsonPrimitive?.intOrNull ?: 0
                )
                val byType = mutableMapOf<String, Int>()
                o["by_type"]?.jsonObject?.forEach { (k, v) ->
                    byType[k] = v.jsonPrimitive.intOrNull ?: 0
                }
                ShareDeepAnalysis(
                    summary = summary,
                    byType = byType,
                    avgLifetimeDays = o["avg_lifetime_days"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaShareDeepAnalysis FAILED: ${e.message}")
            null
        }
    }

    /** 分享深度分析聚合摘要（[getMediaShareDeepAnalysis] 返回的 summary 字段）。 */
    data class ShareSummary(
        val total: Int,
        val active: Int,
        val expired: Int,
        val passwordProtected: Int
    )

    /** 分享深度分析（[getMediaShareDeepAnalysis] 返回）。byType key 为媒体类型字符串。 */
    data class ShareDeepAnalysis(
        val summary: ShareSummary,
        val byType: Map<String, Int>,
        val avgLifetimeDays: Double
    )

    /**
     * GET /api/media/media-integrity-report — 媒体完整性综合报告。
     *
     * 后端单次遍历合并 orphan-check + error-check + duplicate-report，并据此计算
     * 0-100 完整性评分与 A/B/C/D 等级（>=85 A / 70-84 B / 50-69 C / <50 D）。
     * 响应结构（见后端 [handleMediaIntegrityReport]）：
     *   { integrity_score:N, grade:"A"|"B"|"C"|"D",
     *     orphans:{count,samples}, errors:{count,samples},
     *     duplicates:{groups,count,reclaimable_bytes}, total_media:N, disk_check:bool }
     * 前端仅取评分/等级 + 四维度计数，不渲染 samples（错误详情已在"媒体错误检查"卡片展示）。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization 编译器插件）。
     * 失败（HTTP 非 200 或网络异常）返回 null，调用方 null-skip 静默跳过卡片。
     */
    suspend fun getMediaIntegrityReport(): MediaIntegrityReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-integrity-report") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val orp = o["orphans"]?.jsonObject
                val err = o["errors"]?.jsonObject
                val dup = o["duplicates"]?.jsonObject
                MediaIntegrityReport(
                    integrityScore = o["integrity_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    grade = o["grade"]?.jsonPrimitive?.contentOrNull ?: "D",
                    orphans = IntegritySection(
                        count = orp?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        samples = orp?.get("samples")?.jsonArray?.mapNotNull { s ->
                            s.jsonObject["filename"]?.jsonPrimitive?.contentOrNull
                                ?: s.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull
                        } ?: emptyList()
                    ),
                    errors = IntegritySection(
                        count = err?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        samples = err?.get("samples")?.jsonArray?.mapNotNull { s ->
                            s.jsonObject["filename"]?.jsonPrimitive?.contentOrNull
                                ?: s.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull
                        } ?: emptyList()
                    ),
                    duplicates = DupSection(
                        groups = dup?.get("groups")?.jsonPrimitive?.intOrNull ?: 0,
                        count = dup?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                        reclaimableBytes = dup?.get("reclaimable_bytes")?.jsonPrimitive?.longOrNull ?: 0L
                    ),
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaIntegrityReport FAILED: ${e.message}")
            null
        }
    }

    /** 完整性报告（[getMediaIntegrityReport] 返回）。 */
    data class MediaIntegrityReport(
        val integrityScore: Int,
        val grade: String,
        val orphans: IntegritySection,
        val errors: IntegritySection,
        val duplicates: DupSection,
        val totalMedia: Int
    )

    /** 孤立/错误维度的计数与示例文件名列表。 */
    data class IntegritySection(
        val count: Int,
        val samples: List<String>
    )

    /** 重复维度的组数、总重复份数、可回收字节数。 */
    data class DupSection(
        val groups: Int,
        val count: Int,
        val reclaimableBytes: Long
    )

    /**
     * GET /api/media/media-storage-audit — 存储审计报告。
     *
     * 合并 orphan + error + duplicate + near-duplicate 四项检查为一份完整审计报告，
     * 并据此计算审计评分（0-100）与等级（A/B/C/D）+ 针对性建议。单次遍历 + 单次磁盘扫描
     * 同时完成四类检查，避免前端并发拉取 cleanup-orphan/orphan-check/error-check/
     * duplicates/duplicates-similar 五个端点各自重扫磁盘的 IO 放大。
     *
     * 响应字段（[backend handler](backend/internal/gateway/server.go) `handleMediaStorageAudit`）：
     * `audit_score`(0-100) / `grade`(A-D) / `orphans`{count,samples} / `errors`{count,samples} /
     * `duplicates`{groups,count,**reclaimable**(注意是 `reclaimable` 非 `reclaimable_bytes`)} /
     * `near_duplicates`{pairs,count,total,samples} / `total_media` / `recommendations`[string]。
     *
     * 解析沿用 [getMediaIntegrityReport] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip 静默跳过卡片。
     */
    suspend fun getMediaStorageAudit(): StorageAudit? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-storage-audit") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val orp = o["orphans"]?.jsonObject
                val err = o["errors"]?.jsonObject
                val dup = o["duplicates"]?.jsonObject
                val near = o["near_duplicates"]?.jsonObject
                StorageAudit(
                    auditScore = o["audit_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    grade = o["grade"]?.jsonPrimitive?.contentOrNull ?: "D",
                    orphans = AuditSection(
                        count = orp?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    errors = AuditSection(
                        count = err?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    duplicates = AuditDupSection(
                        groups = dup?.get("groups")?.jsonPrimitive?.intOrNull ?: 0,
                        count = dup?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    nearDuplicates = AuditNearSection(
                        pairs = near?.get("pairs")?.jsonPrimitive?.intOrNull ?: 0,
                        count = near?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    recommendations = o["recommendations"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaStorageAudit FAILED: ${e.message}")
            null
        }
    }

    /** 存储审计报告（[getMediaStorageAudit] 返回）。四维度选 0-100 综合评分 + A-D 等级 + 建议。 */
    data class StorageAudit(
        val auditScore: Int,          // 0-100
        val grade: String,            // A/B/C/D
        val orphans: AuditSection,
        val errors: AuditSection,
        val duplicates: AuditDupSection,
        val nearDuplicates: AuditNearSection,
        val totalMedia: Int,
        val recommendations: List<String>
    )

    /** 审计维度（孤立/错误）：仅取计数用于卡片汇总展示（samples 后端仍返回，前端卡片层不展示明细）。 */
    data class AuditSection(
        val count: Int
    )

    /** 重复维度：组数 + 总重复份数。 */
    data class AuditDupSection(
        val groups: Int,
        val count: Int
    )

    /** 近似重复维度：对数 + 涉及媒体数。 */
    data class AuditNearSection(
        val pairs: Int,
        val count: Int
    )

    /**
     * GET /api/media/favorite-timeline — 收藏时间线，按收藏时间倒序。
     *
     * 后端返回 `{favorites:[{media_id,filename,type,favorited_at}],total}`，其中
     * `favorited_at` 为 RFC3339 字符串（time.Time 序列化）。前端取前 [limit] 条
     * 供"我的"Tab 的"收藏时间线"卡片渲染最近收藏的媒体列表。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization 编译器插件）。
     * 失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip 静默跳过卡片，不崩溃"我的"Tab。
     *
     * @param limit 取前 N 条（透传 query param，后端上限 200，<=0 回退默认 20）
     * @return 收藏时间线条目列表（已按 favorited_at 倒序）；失败返回 null
     */
    suspend fun getFavoriteTimeline(limit: Int = 10): List<FavoriteTimelineItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/favorite-timeline") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["favorites"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    FavoriteTimelineItem(
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = o["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        favoritedAt = o["favorited_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getFavoriteTimeline FAILED: ${e.message}")
            null
        }
    }

    /** 收藏时间线条目（[getFavoriteTimeline] 返回项）。 */
    data class FavoriteTimelineItem(
        val mediaId: String,
        val filename: String,
        val type: String,
        val favoritedAt: String
    )

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
     * V10：GET /api/media/tag/most-used — 最常用标签排行。
     *
     * 后端按关联媒体数 count DESC 返回 top N（默认 10，[limit] 透传 query param，
     * 后端范围 [1,100]）。每条含 tag_name、count（关联媒体数，取自 media_tags 关系表
     * 口径，不随 media 软删联动清理，故可能大于实际未软删数量）、total_bytes（关联
     * 未软删 media 的 size 总和）、avg_bytes（total_bytes/count，整数除法）。
     * 另返回 total_tags（该用户全部标签数）与 total_tagged_media（被任意标签标记的
     * 未软删去重媒体数），前端目前仅消费 tags 列表渲染排行区。
     *
     * 响应: `{tags: [{tag_name, count, total_bytes, avg_bytes}], total_tags, total_tagged_media}`。
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过排行区，不崩溃标签管理面板。
     *
     * @param limit 取前 N 条（透传 query param）；默认 10，标签管理面板取 top 5。
     * @return 最常用标签列表（已按 count DESC）；失败返回 null
     */
    suspend fun getMostUsedTags(limit: Int = 10): List<MostUsedTag>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag/most-used") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    MostUsedTag(
                        tagName = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        avgBytes = o["avg_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMostUsedTags FAILED: ${e.message}")
            null
        }
    }

    /** V10：最常用标签排行项（[getMostUsedTags] 返回）。 */
    data class MostUsedTag(
        val tagName: String,
        val count: Int,
        val totalBytes: Long,
        val avgBytes: Long
    )

    /**
     * V23：GET /api/media/tag-power-score — 标签影响力排行。
     *
     * 后端按 power_score DESC 返回全部标签，每条含 tag_name、media_count（关联媒体数）、
     * total_bytes（关联媒体总大小）、coverage_percent（覆盖用户总媒体的百分比）、
     * power_score（影响力分 = media_count*2 + total_bytes_mb*0.1）。另返回 total_tags
     * （用户全部标签数），前端目前仅消费 tags 列表渲染影响力排行区，取 top 5。
     *
     * 响应: `{tags: [{tag_name, media_count, total_bytes, coverage_percent, power_score}], total_tags}`。
     *
     * 解析沿用 [getMostUsedTags] 的运行时 JSON 操作（feature-media 无 serialization
     * 编译器插件）。失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip
     * 静默跳过排行区，不崩溃标签管理面板。
     *
     * @return 按 power_score DESC 排序的标签影响力列表（后端已排序，前端取 top 5）；失败返回 null
     */
    suspend fun getTagPowerScore(): List<TagPowerItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-power-score") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagPowerItem(
                        tagName = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        mediaCount = o["media_count"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        coveragePercent = o["coverage_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        powerScore = o["power_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagPowerScore FAILED: ${e.message}")
            null
        }
    }

    /** V23：标签影响力排行项（[getTagPowerScore] 返回）。 */
    data class TagPowerItem(
        val tagName: String,
        val mediaCount: Int,
        val totalBytes: Long,
        val coveragePercent: Double,
        val powerScore: Double
    )

    /**
     * V22：GET /api/media/tag-trend?months=6 — 标签使用趋势（每月新增标签数）。
     *
     * 后端返回 `{ months: [{month, new_tags}], total_new_tags }`：month 形如
     * "2026-03"，new_tags 为该月新增标签数（audit_log action="tag" 口径）。
     * 月份窗口 `[本月往前推 months-1 个月 .. 本月]`，升序，空月补 0。
     *
     * 返回 `null` = 网络/HTTP 失败（UI 隐藏整张卡片）；非 null（可能为空列表）
     * = 成功。调用方默认取 6 个月，最多展示 6 行。
     *
     * @param months 月份窗口大小（默认 6，后端收敛到 [1,24]）
     */
    suspend fun getTagTrend(months: Int = 6): List<TagTrendPoint>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-trend") {
                parameter("months", months)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["months"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagTrendPoint(
                        month = o["month"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        newTags = o["new_tags"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagTrend FAILED: ${e.message}")
            null
        }
    }

    /** V22：标签趋势单月数据点（[getTagTrend] 返回）。 */
    data class TagTrendPoint(
        val month: String,
        val newTags: Int
    )

    /**
     * V23：GET /api/media/media-tag-evolution?months=6 — 标签演化趋势（每标签月度使用次数变化）。
     *
     * 后端返回 `{ tags: [ {tag, monthly_counts: [{month,count},...], trend, total} ],
     * total_tags }`：month 形如 "2026-03"，count 为该月该标签的关联操作次数
     * （audit_log action="tag" 口径，与 tag-trend 的 new_tags_count 同源）。月份窗口
     * `[本月往前推 months-1 个月 .. 本月]`，升序，空月补 0。trend 为 "up"|"down"|"stable"
     * （窗口末日 vs 首日 count 比较）。total 为窗口内该标签全部月度计数之和。后端按 total
     * 降序、并列 tag 升序返回。
     *
     * 返回 `null` = 网络/HTTP 失败（UI 隐藏整区）；非 null（可能为空列表）= 成功。
     * 调用方默认取 6 个月，最多展示 5 个标签。
     *
     * @param months 月份窗口大小（默认 6，后端收敛到 [1,24]）
     */
    suspend fun getMediaTagEvolution(months: Int = 6): List<TagEvolution>? {
        return try {
            val response: HttpResponse = jsonClient.get("${'$'}{backendBaseUrl()}/api/media/media-tag-evolution") {
                parameter("months", months)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagEvolution(
                        tag = o["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        monthlyCounts = o["monthly_counts"]?.jsonArray?.map { mc ->
                            val m = mc.jsonObject
                            TagEvolutionMonthCount(
                                month = m["month"]?.jsonPrimitive?.contentOrNull ?: "",
                                count = m["count"]?.jsonPrimitive?.intOrNull ?: 0
                            )
                        } ?: emptyList(),
                        trend = o["trend"]?.jsonPrimitive?.contentOrNull ?: "stable",
                        total = o["total"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagEvolution FAILED: ${'$'}{e.message}")
            null
        }
    }

    /** V23：单个标签在 N 个月窗口内的演化数据（[getMediaTagEvolution] 返回）。 */
    data class TagEvolution(
        val tag: String = "",
        val monthlyCounts: List<TagEvolutionMonthCount> = emptyList(),
        val trend: String = "stable",
        val total: Int = 0
    )

    /** V23：标签演化单月计数点（[getMediaTagEvolution] 返回）;month 形如 "2026-03"。
     *  不复用既有 [MonthCount]（其 month 为 Int，对应 yearly-review 的 1~12 整数月），
     *  此处 month 为 "YYYY-MM" 字符串——类型不符，故新建专用项类型（同 [DistributionMonthCount] 约定）。 */
    data class TagEvolutionMonthCount(
        val month: String = "",
        val count: Int = 0
    )

    /**
     * V8：GET /api/media/media-tag-smart-group — 标签智能语义分组。
     *
     * 后端按预设语义簇（旅行/美食/人物/风景/其他）将用户全部标签归入若干组，
     * 如"旅行"/"旅游"/"trip"/"travel"→旅行组。返回
     * `{groups: [{group_name, tags, count, total_media}], total_groups, ungrouped_count}`：
     * - groups：按预设顺序输出，仅含命中的组；count 为组内标签数，
     *   total_media 为该组所有标签关联的去重未软删 media 数。
     * - total_groups：实际有标签的组数（含其他组，若非空）。
     * - ungrouped_count：未归入任何预设语义组的标签数（即"其他组"内的标签数）。
     *
     * 返回 `null` = 网络/HTTP 失败（UI 隐藏整区）；非 null（可能为空列表）= 成功。
     * 调用方最多展示 5 个组。
     */
    suspend fun getMediaTagSmartGroup(): List<TagSmartGroup>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-tag-smart-group")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["groups"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    TagSmartGroup(
                        groupName = o["group_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        tags = o["tags"]?.jsonArray?.map { t ->
                            t.jsonPrimitive.contentOrNull ?: ""
                        } ?: emptyList(),
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagSmartGroup FAILED: ${e.message}")
            null
        }
    }

    /** V8：标签智能语义分组单项（[getMediaTagSmartGroup] 返回）。
     *  [count] = 组内标签数，[totalMedia] = 该组所有标签关联的去重 media 数。 */
    data class TagSmartGroup(
        val groupName: String = "",
        val tags: List<String> = emptyList(),
        val count: Int = 0,
        val totalMedia: Int = 0
    )

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
     * 标签关联性矩阵数据（GET /api/media/media-tag-correlation）。
     *
     * 与 [TagNetwork]/[TagPair] 同源共现语义但输出 N×N 完整矩阵（含对角线）：
     * [tags] 为 top N 标签名数组（顺序即行/列索引），[matrix][i][j] 表示同时拥有
     * tag_i 和 tag_j 的 media 数量；对角线 [matrix][i][i] = 该标签自身关联 media 数
     * （即该标签 count）。对称矩阵。[totalTags] 为该用户全部标签数（含未入选者），
     * 供前端展示"共 N 个标签"汇总。
     *
     * 适合热力图 / 弦图等矩阵可视化；本前端目前仅渲染文字版共现对列表。
     */
    data class TagCorrelation(
        val tags: List<String>,
        val matrix: List<List<Int>>,
        val totalTags: Int
    )

    /**
     * GET /api/media/media-tag-correlation?limit=N — 标签关联性矩阵。
     *
     * 后端取 count DESC 排序后的 top N 标签（默认 10，范围 [1,100]），构建 N×N
     * 矩阵：cell[i][j] = 同时拥有 tag_i 和 tag_j 的 media 数量；对角线 cell[i][i] =
     * 该标签自身关联 media 数（即 count）。响应: `{tags, matrix, total_tags}`。
     *
     * 与 [getTagCoOccurrence] 的区别：后者只返 count>=2 的稀疏标签对列表（适合
     * "哪些标签经常共现"的查询）；本端点返完整 N×N 矩阵，适合矩阵可视化。前端在
     * 标签管理面板"常一起出现"区之后用此矩阵渲染文字版 top 5 共现对（i<j 且 count>0）。
     *
     * 解析沿用 [getTagNetwork] 的运行时 JSON 操作（无 serialization 编译器插件依赖）。
     * 失败时返回 null（HTTP 非 200 或网络异常），调用方 null-skip 静默跳过矩阵区，
     * 不崩溃标签管理面板。
     *
     * @param limit 取前 N 个标签构建矩阵（透传 query param）；默认 10，前端取 top 5 渲染。
     * @return 标签关联矩阵（tags + matrix + totalTags）；失败返回 null
     */
    suspend fun getMediaTagCorrelation(limit: Int = 10): TagCorrelation? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-tag-correlation") {
                parameter("limit", limit)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val tags = o["tags"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()
                val matrix = o["matrix"]?.jsonArray?.map { row ->
                    row.jsonArray.map { cell -> cell.jsonPrimitive.intOrNull ?: 0 }
                } ?: emptyList()
                TagCorrelation(
                    tags = tags,
                    matrix = matrix,
                    totalTags = o["total_tags"]?.jsonPrimitive?.intOrNull ?: tags.size
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagCorrelation FAILED: ${e.message}")
            null
        }
    }

    /**
     * V25：标签网络节点（[tag] = 标签名，[mediaCount] = 关联媒体数）。
     *
     * 与 V9 [TagNode] 的区别：后者节点 id/count 来自 `/api/media/tag-network`（纯交集
     * 整数语义）；本类字段名为 [tag]/[mediaCount]，对齐新端点
     * `/api/media/media-tag-network` 的 JSON（`{tag, media_count}`）。
     */
    data class MediaTagNode(val tag: String, val mediaCount: Int)

    /**
     * V2：标签层级根节点项（[getMediaTagHierarchyV2] 返回）。
     *  [parent] = 根标签名（或合成路径片段），[children] = 其直系子标签全名列表
     *  （含路径前缀，按名字升序），[totalMedia] = 该子树（parent 自身 + 所有后代）
     *  关联的去重 media 数。
     */
    data class TagHierarchyV2Item(
        val parent: String,
        val children: List<String>,
        val totalMedia: Int
    )

    /**
     * V2：GET /api/media/media-tag-hierarchy-v2 — 标签层级V2（多层嵌套树状结构）。
     *
     * 相比 V1（[getTagHierarchy]，仅按 - / : 单层切分），V2 支持多层嵌套并融合两种
     * 发现策略：① 按 "/" 分隔符切分标签名还原完整路径层级（如 "旅行/日本/东京" →
     * 旅行 → 日本 → 东京 三级）；② 对不含 "/" 的标签做语义后缀匹配（如 "日本旅行"
     * 以 "旅行" 结尾 → 视为 "旅行" 的子标签，长度差 >1 才生效避免噪声）。
     *
     * 响应: `{hierarchy: [{parent, children: [...], total_media}], total_roots, total_depth}`。
     *  - hierarchy：每个根标签一条记录；children 为其**直系子标签**全名列表（含路径
     *    前缀，按名字升序），不是全量后代——前端如需深层可据 children 再查。
     *  - total_roots：根标签数（= hierarchy 数组长度）。
     *  - total_depth：最大嵌套深度（根=1）。
     *
     * 鉴权头由 defaultRequest 统一注入。解析沿用 [getMediaTagNetwork] 的运行时 JSON
     * 操作（无 serialization 编译器插件依赖）；children 缺失落空列表非致命，
     * total_media 缺失落 0。
     *
     * @return 根节点项列表（仅 [hierarchy] 数组，不含 total_roots/total_depth——
     * 前端如需可在调用侧另行取用）；HTTP 非 200 或网络异常返回 null（调用方 null-skip）。
     */
    suspend fun getMediaTagHierarchyV2(): List<TagHierarchyV2Item>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-tag-hierarchy-v2")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["hierarchy"]?.jsonArray?.mapNotNull { item ->
                    val n = item.jsonObject
                    TagHierarchyV2Item(
                        parent = n["parent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        children = n["children"]?.jsonArray?.map { c ->
                            c.jsonPrimitive.contentOrNull ?: ""
                        } ?: emptyList(),
                        totalMedia = n["total_media"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            } else {
                logger.info("MediaService", "getMediaTagHierarchyV2 status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagHierarchyV2 FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V25：标签网络边。[weight] 为 Jaccard 相似度（0~1，归一化），后端 float64 序列化；
     * 非 V9 [TagEdge]（整数共现次数）。
     */
    data class MediaTagEdge(val source: String, val target: String, val weight: Double)

    /**
     * V25：标签网络图数据（GET /api/media/media-tag-network）。节点按 media_count DESC 截
     * top [limit] 标签，边为节点对 Jaccard 相似度（weight>0 才入）。
     */
    data class MediaTagNetwork(
        val nodes: List<MediaTagNode>,
        val edges: List<MediaTagEdge>,
        val totalNodes: Int,
        val totalEdges: Int
    )

    /**
     * GET /api/media/media-tag-network?limit=N — 标签网络图（节点+边）。
     *
     * 后端取该用户全部标签按 media_count DESC 截 top [limit]（默认 20，上限 200）作为
     * 节点；对节点对 (i<j) 计算 Jaccard 相似度 = |A∩B| / |A∪B| 作为边权重（0~1，归一化），
     * 仅保留 weight>0 的边。响应:
     * `{nodes: [{tag, media_count}], edges: [{source, target, weight}], total_nodes, total_edges}`。
     *
     * 与旧端点 [getTagNetwork]（`/api/media/tag-network`）的区别：后者用集合大小作 count、
     * 纯交集大小作 weight（整数共现数）、无 limit；本端点 weight 为 Jaccard 系数（Double，
     * 0~1），支持 [limit] 截断使网络规模可控、边权重可跨标签对横向比较。
     *
     * 解析沿用 [getMediaTagCorrelation] 的运行时 JSON 操作（无 serialization 编译器插件依赖）。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param limit 取前 N 个标签构建网络图（透传 query param）；默认 20，前端取 top 5 渲染。
     * @return 标签网络（节点+边+汇总数）；HTTP 非 200 或网络异常返回 null（调用方 null-skip）。
     */
    suspend fun getMediaTagNetwork(limit: Int = 20): MediaTagNetwork? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-tag-network") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val nodes = o["nodes"]?.jsonArray?.mapNotNull { item ->
                    val n = item.jsonObject
                    MediaTagNode(
                        tag = n["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        mediaCount = n["media_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                val edges = o["edges"]?.jsonArray?.mapNotNull { item ->
                    val e = item.jsonObject
                    MediaTagEdge(
                        source = e["source"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        target = e["target"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        weight = e["weight"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                } ?: emptyList()
                MediaTagNetwork(
                    nodes = nodes,
                    edges = edges,
                    totalNodes = o["total_nodes"]?.jsonPrimitive?.intOrNull ?: nodes.size,
                    totalEdges = o["total_edges"]?.jsonPrimitive?.intOrNull ?: edges.size
                )
            } else {
                logger.info("MediaService", "getMediaTagNetwork status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagNetwork FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 标签层级子节点（[tag] = 子标签名，[count] = 该子标签自身关联媒体数）。
     * 后端 count 仅含子标签自身，不含父标签，避免重复统计口径混乱。
     */
    data class TagChild(val tag: String, val count: Int)

    /**
     * 标签层级根节点（[tag] = 根标签名，[count] = 该根标签自身媒体数，
     * [children] = 由标签名分隔符（- / : ）推断出的单层子标签列表，可能为空）。
     */
    data class TagHierarchyNode(
        val tag: String,
        val count: Int,
        val children: List<TagChild>
    )

    /**
     * 标签层级分析数据。
     *
     * 后端 GET /api/media/tag-hierarchy — 自动分析标签名中的分隔符（- / : 等）
     * 推断父子关系：如 "旅行-国内" 的父节点为 "旅行"；无分隔符或父标签不独立存在的
     * 标签作为顶层根。仅做单层父子切分（a-b-c 的父为 a），不做多层嵌套。
     *
     * 响应: `{hierarchy: [{tag, count, children: [{tag, count}]}], total_roots, total_tags}`。
     * `total_roots` = 根标签数；`total_tags` = 全部标签数（含父与子，去重）。
     *
     * 解析沿用 [getTagNetwork] 的运行时 JSON 操作（无 serialization 编译器插件依赖）。
     * children 数组缺失时落空列表非致命；计数缺失落 0。
     *
     * @return 根节点列表（含子节点）；HTTP 非 200 或网络异常返回 null（调用方按空态跳过卡片）。
     */
    suspend fun getTagHierarchy(): List<TagHierarchyNode>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/tag-hierarchy") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["hierarchy"]?.jsonArray?.mapNotNull { item ->
                    val n = item.jsonObject
                    val children = n["children"]?.jsonArray?.mapNotNull { c ->
                        val co = c.jsonObject
                        TagChild(
                            tag = co["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            count = co["count"]?.jsonPrimitive?.intOrNull ?: 0
                        )
                    } ?: emptyList()
                    TagHierarchyNode(
                        tag = n["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        count = n["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        children = children
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getTagHierarchy FAILED: ${e.message}")
            null
        }
    }

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

    /**
     * V8：POST /api/media/tag/batch-rename — 批量重命名标签，返回成功数。
     *
     * 后端按 `{ renames: [{ old_name, new_name }] }` 接收，逐项调 RenameTag，
     * 响应 `{ status, renamed_count, failed }`（status 为 success/partial/failed）。
     * 本方法返回成功重命名的数量；HTTP 非 200 或异常返回 0（与 [batchAddTag] 一致）。
     *
     * @param oldName 旧标签名
     * @param newName 新标签名
     * @return 后端实际成功重命名的数量
     */
    suspend fun batchRenameTag(oldName: String, newName: String): Boolean {
        if (oldName.isBlank() || newName.isBlank()) return false
        return try {
            val body = buildJsonObject {
                putJsonArray("renames") {
                    add(buildJsonObject {
                        put("old_name", oldName)
                        put("new_name", newName)
                    })
                }
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/batch-rename") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val renamed = obj["renamed_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "batchRenameTag old=$oldName new=$newName renamed=$renamed")
                renamed > 0
            } else {
                logger.info("MediaService", "batchRenameTag status=${response.status}")
                false
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchRenameTag FAILED: ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/tag/merge — 合并标签，返回是否成功。
     *
     * 后端按 `{ source_tag, target_tag }` 接收，把所有 source_tag 的记录改为
     * target_tag（INSERT OR IGNORE 处理冲突），然后删除 source_tag。响应
     * `{ source_tag, target_tag, merged_count }`。
     *
     * 调用成功后需刷新 tagStats 以反映合并后的标签分布。
     *
     * @param sourceTag 被合并的源标签（将被删除）
     * @param targetTag 合并目标标签（保留）
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun mergeTags(sourceTag: String, targetTag: String): Boolean {
        if (sourceTag.isBlank() || targetTag.isBlank()) return false
        if (sourceTag == targetTag) return false
        return try {
            val body = buildJsonObject {
                put("source_tag", sourceTag)
                put("target_tag", targetTag)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/merge") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val ok = response.status == HttpStatusCode.OK
            if (ok) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val merged = obj["merged_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "mergeTags source=$sourceTag target=$targetTag merged=$merged")
            } else {
                logger.info("MediaService", "mergeTags status=${response.status}")
            }
            ok
        } catch (e: Exception) {
            logger.error("MediaService", "mergeTags FAILED: ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/tag/import — 导入标签，返回成功导入数。
     *
     * 后端按 `{ tags: [{ media_id, tag_name }] }` 接收，幂等判重（同 media 已挂
     * 同 tag 则跳过），单批上限 500。响应 `{ status, imported_count, skipped_count, total }`。
     *
     * [data] 为后端导出格式（[exportTags] 的返回）的 JSON 字符串，或用户手写的
     * `{ "tags": [...] }` 结构。本方法解析其中的 tags 数组并透传，解析失败返回 0。
     *
     * @param data 导入数据 JSON 字符串
     * @return 成功导入数；解析失败/HTTP 非 200/异常返回 0
     */
    suspend fun importTags(data: String): Boolean {
        if (data.isBlank()) return false
        return try {
            // 解析输入，提取 tags 数组（兼容 exportTags 返回的 {tags:[{media_id,tag_name}]} 与
            // 用户手写的简单 {tags:["a","b"]}——后者.media_id 为空会被后端 skip）。
            val parsed = Json.parseToJsonElement(data).jsonObject
            val tagsArray = parsed["tags"]?.jsonArray
            if (tagsArray.isNullOrEmpty()) {
                logger.info("MediaService", "importTags: no tags array in input")
                return false
            }
            val body = buildJsonObject {
                putJsonArray("tags") {
                    tagsArray.forEach { el ->
                        val item = el.jsonObject
                        add(buildJsonObject {
                            put("media_id", item["media_id"]?.jsonPrimitive?.contentOrNull ?: "")
                            put("tag_name", item["tag_name"]?.jsonPrimitive?.contentOrNull ?: "")
                        })
                    }
                }
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/import") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val imported = obj["imported_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "importTags imported=$imported")
                imported > 0
            } else {
                logger.info("MediaService", "importTags status=${response.status}")
                false
            }
        } catch (e: Exception) {
            logger.error("MediaService", "importTags FAILED: ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/tag/batch-remove — 批量移除标签，返回是否成功。
     *
     * 后端按 `{ media_ids, tag_name }` 接收，逐个调 RemoveMediaTag，响应
     * `{ tag, removed_count, total }`。本方法返回 removed_count 与预期数一致
     * 时为 true（与 [batchAddTag] 的 Int 返回不同：此处语义为"全部移除成功"，
     * 由调用方按布尔判断是否提示成功/部分失败）。
     *
     * @param mediaIds 待移除标签的媒体 ID 列表
     * @param tags 标签名列表（后端只接受单个 tag_name，此处取第一个非空；
     *             多标签场景由调用方多次调用）
     * @return 后端是否成功处理（HTTP 200）
     */
    suspend fun batchRemoveTags(mediaIds: List<String>, tags: List<String>): Boolean {
        if (mediaIds.isEmpty() || tags.isEmpty()) return false
        val tagName = tags.firstOrNull { it.isNotBlank() } ?: return false
        return try {
            val body = buildJsonObject {
                putJsonArray("media_ids") { mediaIds.forEach { add(it) } }
                put("tag_name", tagName)
            }.toString()
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/batch-remove") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val removed = obj["removed_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "batchRemoveTags tag=$tagName removed=$removed/${mediaIds.size}")
                removed > 0
            } else {
                logger.info("MediaService", "batchRemoveTags status=${response.status}")
                false
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchRemoveTags FAILED: ${e.message}")
            false
        }
    }

    /**
     * V8：POST /api/media/tag/batch-by-type — 按媒体类型批量打标签，返回成功数。
     *
     * 后端不接收请求体参数，自动按媒体类型（image/video/live）给每个媒体打上
     * 类型名称标签，响应 `{ status, tagged_count }`。本方法返回 tagged_count；
     * HTTP 非 200 或异常返回 0。
     *
     * 注意：后端 [type]/[tags] 参数当前不被使用（自动推断），保留签名以兼容
     * 未来扩展（后端若改为接受 type 过滤，此处可透传）。
     *
     * @param type 媒体类型过滤（预留，当前后端忽略）
     * @param tags 标签名列表（预留，当前后端忽略）
     * @return 后端实际打标签成功的媒体数
     */
    suspend fun batchAddTagsByType(type: String = "", tags: List<String> = emptyList()): Int {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/batch-by-type") {
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                val tagged = obj["tagged_count"]?.jsonPrimitive?.intOrNull ?: 0
                logger.info("MediaService", "batchAddTagsByType type=$type tagged=$tagged")
                tagged
            } else {
                logger.info("MediaService", "batchAddTagsByType status=${response.status}")
                0
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchAddTagsByType FAILED: ${e.message}")
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
     * POST /api/media/tag/cleanup-unused — 清理未被任何媒体使用的空标签。
     *
     * 后端返回 `{status, removed_count, removed_tags:[...], total_tags_before}`。
     * 本方法返回封装的 [CleanupResult]；HTTP 非 200 或异常时返回 null（与
     * [exportTags] 等统计方法一致的失败姿态，见 MediaService 约定 5）。
     *
     * 后端端点可能尚未部署（complementary sibling collision 场景）——本方法
     * 先前端先行：端点缺失时返回 null，UI 静默降级提示失败。
     *
     * @return 成功时为 [CleanupResult]；失败/端点缺失时返回 null。
     */
    suspend fun cleanupUnusedTags(): CleanupResult? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/cleanup-unused") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val removedTags = o["removed_tags"]?.jsonArray?.mapNotNull { el ->
                    el.jsonPrimitive.contentOrNull
                } ?: emptyList()
                val result = CleanupResult(
                    removedCount = o["removed_count"]?.jsonPrimitive?.intOrNull ?: removedTags.size,
                    removedTags = removedTags,
                    totalTagsBefore = o["total_tags_before"]?.jsonPrimitive?.intOrNull ?: 0
                )
                logger.info("MediaService", "cleanupUnusedTags status=${response.status} removed=${result.removedCount}")
                result
            } else {
                logger.info("MediaService", "cleanupUnusedTags status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "cleanupUnusedTags FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** [cleanupUnusedTags] 返回结果。字段对齐后端 JSON：removed_count/removed_tags/total_tags_before。 */
    data class CleanupResult(
        val removedCount: Int,
        val removedTags: List<String>,
        val totalTagsBefore: Int
    )

    /**
     * POST /api/media/tag/merge-smart — 智能合并相似标签。
     *
     * 后端自动检测三类相似标签并合并（保留字典序较小/中文形式为目标，把后者
     * RenameTag 并入）：a) 大小写不同（"Travel" vs "travel"）；b) 简繁不同
     * （"旅行" vs "旅遊"）；c) 中英对应（"travel" vs "旅行"）。
     *
     * 后端返回 `{status, merged_count, merges:[{from,to,count,reason}],
     * total_tags_before, total_tags_after}`。本方法返回封装的
     * [MergeSmartResult]；HTTP 非 200 或异常时返回 null（与 [cleanupUnusedTags]
     * 等统计方法一致的失败姿态，见 MediaService 约定 5）。
     *
     * @return 成功时为 [MergeSmartResult]；失败/端点缺失时返回 null。
     */
    suspend fun mergeSmartTags(): MergeSmartResult? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/tag/merge-smart") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val merges = o["merges"]?.jsonArray?.mapNotNull { el ->
                    val jo = el.jsonObject
                    val from = jo["from"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val to = jo["to"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    MergePair(
                        from = from,
                        to = to,
                        count = jo["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        reason = jo["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                } ?: emptyList()
                val result = MergeSmartResult(
                    mergedCount = o["merged_count"]?.jsonPrimitive?.intOrNull ?: merges.size,
                    merges = merges,
                    totalTagsBefore = o["total_tags_before"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalTagsAfter = o["total_tags_after"]?.jsonPrimitive?.intOrNull ?: 0
                )
                logger.info("MediaService", "mergeSmartTags status=${response.status} merged=${result.mergedCount}")
                result
            } else {
                logger.info("MediaService", "mergeSmartTags status=${response.status} (no body)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "mergeSmartTags FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** [mergeSmartTags] 返回结果。字段对齐后端 JSON：merged_count/merges/total_tags_before/total_tags_after。 */
    data class MergeSmartResult(
        val mergedCount: Int,
        val merges: List<MergePair>,
        val totalTagsBefore: Int,
        val totalTagsAfter: Int
    )

    /** 单次合并记录。from 被并入 to，count 为受影响媒体数，reason 为合并原因（case_or_trad_simp / cn_en_mapping）。 */
    data class MergePair(
        val from: String,
        val to: String,
        val count: Int,
        val reason: String
    )

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
     * V8：POST /api/media/media-video-trim/{mediaId}?ext=.mp4 — 视频裁剪后端对接。
     *
     * 后端协议：请求体为裁剪后视频片段的**原始字节流**（raw bytes，非 JSON），
     * `mediaId` 走路径尾段，扩展名走 `ext` query 参数（须在白名单 .mp4/.mov/.avi/.mkv/.webm/.m4v）。
     * 后端流式落盘 + magic bytes 校验，生成新 uuid media_id 作为独立裁剪片段媒体，
     * 写 metadata sidecar 记录 trimmed=true + source_media_id。响应 `{ media_id, source_media_id, size, version }`。
     *
     * **集成模型**：前端先用平台本地 [com.wgt.media.trimVideo]（expect/actual，MediaExtractor+MediaMuxer）
     * 对源视频做时间段裁剪得到本地文件，读其字节后调本方法上传到后端。本方法只负责"上传裁剪片段"
     * 这一步——本地裁剪由 UI 层在 Dispatchers.IO 中调用 trimVideo 完成。
     *
     * 为何不把本地裁剪+下载源视频塞进本方法：getMediaStream 拿到的是 ByteArray，而 trimVideo 需要
     * 文件路径输入，跨平台写临时文件路径属平台层职责；故拆分为"UI 编排本地裁剪"+"本方法上传"两步。
     * 见 [VideoTrimDialog] 的调用示例。
     *
     * @param mediaId 源视频媒体 ID（后端据此写 source_media_id 追溯关系）
     * @param trimmedData 本地裁剪后的视频片段字节
     * @param ext 视频容器扩展名（含点，如 ".mp4"）；默认 ".mp4"，须在白名单内
     * @return 成功时为后端生成的新 media_id（裁剪片段作为独立媒体）；失败返回 null
     */
    suspend fun videoTrimUpload(mediaId: String, trimmedData: ByteArray, ext: String = ".mp4"): String? {
        if (mediaId.isBlank() || trimmedData.isEmpty()) return null
        val normExt = ext.trim().lowercase().let { if (it.isNotEmpty() && !it.startsWith(".")) ".$it" else it }
        val finalExt = if (normExt.isEmpty()) ".mp4" else normExt
        return try {
            val response: HttpResponse = jsonClient.post(
                "${backendBaseUrl()}/api/media/media-video-trim/${mediaId.encodeURLPath()}"
            ) {
                parameter("ext", finalExt)
                contentType(ContentType.Application.OctetStream)
                setBody(trimmedData)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val newId = o["media_id"]?.jsonPrimitive?.contentOrNull
                logger.info("MediaService", "videoTrimUpload mediaId=$mediaId ext=$finalExt bytes=${trimmedData.size} newId=$newId")
                newId
            } else {
                logger.info("MediaService", "videoTrimUpload mediaId=$mediaId status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "videoTrimUpload FAILED mediaId=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * POST /api/media/media-edit-save/{mediaId} — 图片编辑后保存到后端。
     *
     * 后端协议：请求体为编辑后图片的**原始字节流**（raw bytes，非 JSON/multipart），
     * `mediaId` 走路径尾段。后端流式落盘（上限 50MB）+ magic bytes 校验，
     * 固定保存为 JPEG（文件名 `{mediaId}_edited_{unix秒}.jpg`），并写 metadata sidecar
     * 记录 `edited=true` + `original_media_id`。响应 `{ media_id, version, path, size }`。
     *
     * 与 [videoTrimUpload] 同构：路径参数 + raw body + OctetStream content-type。
     *
     * **降级策略**：本方法仅负责上传，调用方（ImageEditor.onSave）在返回 null 时
     * 应降级为仅本地相册保存，不阻断用户编辑成果。
     *
     * @param mediaId 原图媒体 ID（后端据此写 original_media_id 追溯关系）
     * @param imageBytes 编辑后图片的 JPEG 字节流
     * @param filename 原文件名（仅用于日志，后端不消费此参数，固定按 mediaId 命名）
     * @return 成功时为后端保存的版本标识（`path` 字段，即落盘文件名）；失败返回 null
     */
    suspend fun uploadEditedImage(mediaId: String, imageBytes: ByteArray, filename: String): String? {
        if (mediaId.isBlank() || imageBytes.isEmpty()) return null
        return try {
            val response: HttpResponse = jsonClient.post(
                "${backendBaseUrl()}/api/media/media-edit-save/${mediaId.encodeURLPath()}"
            ) {
                contentType(ContentType.Application.OctetStream)
                setBody(imageBytes)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val version = o["path"]?.jsonPrimitive?.contentOrNull
                    ?: o["version"]?.jsonPrimitive?.contentOrNull
                logger.info("MediaService", "uploadEditedImage mediaId=$mediaId filename=$filename bytes=${imageBytes.size} path=$version")
                version
            } else {
                logger.info("MediaService", "uploadEditedImage mediaId=$mediaId filename=$filename status=${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "uploadEditedImage FAILED mediaId=$mediaId filename=$filename: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V9：GET /api/media/exif/{id} — 返回单个媒体的完整 EXIF/metadata。
     *
     * 后端合并两个数据源（见 gateway.handleMediaExif）：
     *   1. SQLite 持久化字段（taken_at、orientation、sha256、width/height 等）；
     *   2. 实时从磁盘文件解析出的 EXIF 标签 map（Make/Model/DateTimeOriginal/
     *      ExifImageWidth/Height/Orientation 等，由 service 层 parseTIFFExif 提取）。
     *
     * 响应结构：`{ media_id, exif: {DateTimeOriginal: "...", ...}, source, filename,
     * taken_at, orientation, ... }`。本方法仅取前端需要的字段；exif map 原样保留，
     * 供 UI 按需读取各条目（当前 UI 用 [dateTimeOriginal] 显示"原始拍摄时间"）。
     *
     * 失败（网络异常/非 200）返回 null，UI 静默跳过相关行——与 [getVideoInfo] 同款降级。
     */
    suspend fun getExifData(mediaId: String): ExifData? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/exif/$mediaId")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val exifMap = o["exif"]?.jsonObject
                ExifData(
                    dateTimeOriginal = exifMap?.get("DateTimeOriginal")?.jsonPrimitive?.contentOrNull,
                    make = exifMap?.get("Make")?.jsonPrimitive?.contentOrNull,
                    model = exifMap?.get("Model")?.jsonPrimitive?.contentOrNull,
                    orientation = exifMap?.get("Orientation")?.jsonPrimitive?.contentOrNull,
                    exifWidth = exifMap?.get("ExifImageWidth")?.jsonPrimitive?.contentOrNull,
                    exifHeight = exifMap?.get("ExifImageHeight")?.jsonPrimitive?.contentOrNull,
                    source = o["source"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getExifData FAILED id=$mediaId: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V9：EXIF/metadata 详情（GET /api/media/exif/{id} 响应解析结果）。
     *
     * 各字段可能为 null（对应 EXIF 条目缺失或后端未解析到）。[dateTimeOriginal] 为
     * EXIF DateTimeOriginal（格式通常为 "YYYY:MM:DD HH:MM:SS"），UI 用作"原始拍摄时间"。
     * 其余字段（相机厂商/型号等）当前 UI 未展示，预留以便扩展。
     */
    data class ExifData(
        val dateTimeOriginal: String?,
        val make: String?,
        val model: String?,
        val orientation: String?,
        val exifWidth: String?,
        val exifHeight: String?,
        val source: String
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

     /** V8：批量重命名建议（只读预览，与 media-batch-rename-suggest 配对） */
     data class RenameSuggestion(
         val mediaId: String,
         val oldName: String,
         val suggestedName: String
     )

     /**
      * V8：GET /api/media/media-batch-rename-suggest — 批量重命名前置预览。
      * 返回 old→new 建议列表（最多 limit 条），不落库。前端在 BatchRenameDialog
      * 中展示供用户确认后，再调 batchRename 落盘。
      */
     suspend fun getBatchRenameSuggest(
         prefix: String,
         start: Int,
         limit: Int
     ): List<RenameSuggestion>? {
         return try {
             val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-batch-rename-suggest") {
                 getAuthToken()?.let { header("Authorization", "Bearer $it") }
                 // 与后端一致：prefix 默认 IMG_，start 默认 1，limit 默认 10。
                 parameter("prefix", prefix)
                 parameter("start", start.toString())
                 parameter("limit", limit.toString())
             }
             if (response.status == HttpStatusCode.OK) {
                 val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                 obj["suggestions"]?.jsonArray?.mapNotNull { item ->
                     val o = item.jsonObject
                     RenameSuggestion(
                         mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                         oldName = o["old_name"]?.jsonPrimitive?.contentOrNull ?: "",
                         suggestedName = o["suggested_name"]?.jsonPrimitive?.contentOrNull ?: ""
                     )
                 }
             } else null
         } catch (e: Exception) {
             logger.error("MediaService", "getBatchRenameSuggest FAILED: ${e::class.simpleName} ${e.message}")
             null
         }
     }

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
     * V14：存储增长预测——GET /api/media/storage-growth-prediction 返回的预测数据。
     *
     * 与 V9 [StorageForecast] 同源（均基于最近 6 个月上传趋势线性外推），但字段口径不同：
     * - [currentBytes]：当前未软删媒体总字节。
     * - [avgMonthlyGrowthBytes]：样本月（最近最多 6 个月）月均增长字节；样本月数 < 2 时为 0。
     * - [predictions]：未来 3/6/12 个月后的预测用量，键为 "3_months"/"6_months"/"12_months"。
     *   线性外推：currentBytes + avgMonthlyGrowthBytes * N。
     * - [estimatedFullDate]：10GB 配额下"用完"的日期（YYYY-MM-DD）；增长率为 0 或无趋势时为 null，
     *   已达/超配额时为当前日期。后端返回 null → 此处保持 null。
     *
     * 字段缺失回退默认值（0L / 空串），与既有 [StorageForecast] 同款宽容解析。
     */
    data class StorageGrowthPrediction(
        val currentBytes: Long = 0L,
        val avgMonthlyGrowthBytes: Long = 0L,
        val predictions: Map<String, Long> = emptyMap(),
        val estimatedFullDate: String? = null
    ) {
        /** 月均增长 MB（UI 文字截断处理，遵循 commonMain 无 String.format 约定）。 */
        val avgMonthlyGrowthMB: Double get() = avgMonthlyGrowthBytes.toDouble() / (1024.0 * 1024.0)

        /** 当前用量 MB。 */
        val currentMB: Double get() = currentBytes.toDouble() / (1024.0 * 1024.0)

        /** 取指定键（"3_months"/"6_months"/"12_months"）的预测字节；缺失返回 null。 */
        fun predictedBytes(key: String): Long? = predictions[key]
    }

    /**
     * V14：GET /api/media/storage-growth-prediction — 存储增长预测。
     *
     * 后端基于最近 6 个月每月上传字节趋势线性外推未来 3/6/12 个月用量，并估算 10GB 配额
     * 耗尽日期。响应字段：current_bytes / avg_monthly_growth_bytes /
     * predictions{3_months,6_months,12_months} / estimated_full_date（YYYY-MM-DD 或 null）。
     *
     * 与 [getStorageForecast] 同走 GET + 运行时 Json 解析；非 200 或异常返回 null，
     * UI 侧静默跳过预测卡片。需认证（Authorization 头由 [jsonClient] defaultRequest 自动注入）。
     *
     * @return 存储增长预测，或 null（失败）
     */
    suspend fun getStorageGrowthPrediction(): StorageGrowthPrediction? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-growth-prediction") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val predsObj = o["predictions"]?.jsonObject
                val predictions = if (predsObj != null) {
                    buildMap {
                        listOf("3_months", "6_months", "12_months").forEach { key ->
                            predsObj[key]?.jsonPrimitive?.longOrNull?.let { put(key, it) }
                        }
                    }
                } else {
                    emptyMap()
                }
                StorageGrowthPrediction(
                    currentBytes = o["current_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    avgMonthlyGrowthBytes = o["avg_monthly_growth_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    predictions = predictions,
                    estimatedFullDate = o["estimated_full_date"]?.let {
                        if (it is JsonNull) null else it.jsonPrimitive?.contentOrNull
                    }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageGrowthPrediction FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
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
     * V9：GET /api/media/media-volume-report — 媒体量报告。
     *
     * 后端 handleMediaVolumeReport 返回全年汇总视角：
     * - [totalMedia] / [totalBytes]：未软删媒体总数与累计字节。
     * - [thisMonth] / [lastMonth]：本月/上月自然月新增量（仅取 count；bytes 也在响应中备用）。
     * - [momGrowth]：本月相对上月的环比增长率 (this-last)/last*100。后端在上月为 0 时
     *   返回 null（除零保护），[parseNullablePercent] 映射为 [Double.NaN]——UI 侧按
     *   "无对比数据"处理，不显示箭头。与 [GrowthReport.monthChangePercent] 同口径。
     * - [avgDaily]：自首条上传至当前的日均上传统量（不足 1 天按 1 天计）。
     * - [projectedYearEnd]：按本年至今日均新增速率外推到年底的预测总量。
     *
     * 非 200 或异常返回 null，UI 侧静默跳过卡片。复用 [parsePeriod] / [parseNullablePercent]。
     */
    suspend fun getMediaVolumeReport(): MediaVolumeReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-volume-report") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaVolumeReport(
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    thisMonth = parsePeriod(o["this_month"]?.jsonObject).count,
                    lastMonth = parsePeriod(o["last_month"]?.jsonObject).count,
                    momGrowth = parseNullablePercent(o["mom_growth"]),
                    avgDaily = o["avg_daily_uploads"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    projectedYearEnd = o["projected_year_end"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaVolumeReport FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V9：媒体量报告（总量/环比/日均/年底预测）。 */
    data class MediaVolumeReport(
        val totalMedia: Int = 0,
        val totalBytes: Long = 0L,
        val thisMonth: Int = 0,
        val lastMonth: Int = 0,
        val momGrowth: Double = Double.NaN,
        val avgDaily: Double = 0.0,
        val projectedYearEnd: Int = 0
    )

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

    /**
     * GET /api/media/media-year-stats?year=YYYY — 按年份统计媒体。
     *
     * 与 [getYearlyReview]（调 yearly-review 端点）互补：本端点返回更精简的
     * {year, total_count, total_bytes, by_month:[{month,count,bytes}], by_type:{IMAGE,VIDEO,LIVE}}。
     * 差异点：
     * - by_month 的 month 字段每项含 **bytes**（[MonthStat]），而 [MonthCount] 只有 count
     * - by_type 的键为**大写** IMAGE/VIDEO/LIVE（yearly-review 用小写 image/video/live），
     *   解析时按大写键取值，缺失回退 0
     *
     * 后端 by_month 固定返回 12 项（含 count=0），但仍按 month 字段映射以宽容缺失月份。
     * 非 200 或异常返回 null，UI 侧静默降级。
     */
    suspend fun getMediaYearStats(year: Int): MediaYearStats? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-year-stats") {
                parameter("year", year)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val byMonth = o["by_month"]?.jsonArray?.map { el ->
                    val mc = el.jsonObject
                    MonthStat(
                        month = mc["month"]?.jsonPrimitive?.intOrNull ?: 0,
                        count = mc["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = mc["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                } ?: emptyList()
                val typeObj = o["by_type"]?.jsonObject
                MediaYearStats(
                    year = o["year"]?.jsonPrimitive?.intOrNull ?: year,
                    totalCount = o["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    byMonth = byMonth,
                    byType = mapOf(
                        "IMAGE" to (typeObj?.get("IMAGE")?.jsonPrimitive?.intOrNull ?: 0),
                        "VIDEO" to (typeObj?.get("VIDEO")?.jsonPrimitive?.intOrNull ?: 0),
                        "LIVE" to (typeObj?.get("LIVE")?.jsonPrimitive?.intOrNull ?: 0)
                    )
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaYearStats FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** media-year-stats 响应体。 */
    data class MediaYearStats(
        val year: Int,
        val totalCount: Int,
        val totalBytes: Long,
        val byMonth: List<MonthStat>,
        val byType: Map<String, Int>
    )

    /** media-year-stats 单月统计（含 bytes，区别于 [MonthCount]）。 */
    data class MonthStat(val month: Int, val count: Int, val bytes: Long)

    /**
     * GET /api/media/media-yearly-comparison — 年度对比（今年 vs 去年同期 + 增长率）。
     *
     * 与 [getMediaYearStats]（单年按月分布）互补：本端点对比当年与去年同期的
     * 上传统计总量（count/bytes）+ 类型分布 + 增长率，供"我的"Tab 年度对比卡片。
     *
     * 后端响应结构：
     * ```
     * { this_year: {count, bytes, by_type:{IMAGE,VIDEO,LIVE}},
     *   last_year: {count, bytes, by_type:{...}},
     *   growth: {count_pct, bytes_pct} }   // pct 可为 null（去年同期为 0 的除零保护）
     * ```
     *
     * 增长率字段（[GrowthInfo.countPct]/[GrowthInfo.bytesPct]）在后端去年同期为 0 时
     * 返回 JSON null（除零保护，与 growth-report / media-volume-report 同口径）；前端
     * 解析为 [Double.NaN]（"无对比数据"），UI 侧不展示箭头（与 [GrowthReportRow] 同款）。
     *
     * 非 200 或异常返回 null，UI 侧静默降级（不渲染卡片）。
     */
    suspend fun getMediaYearlyComparison(): YearlyComparison? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-yearly-comparison")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                YearlyComparison(
                    thisYear = parseYearStat(o["this_year"]?.jsonObject),
                    lastYear = parseYearStat(o["last_year"]?.jsonObject),
                    growth = GrowthInfo(
                        countPct = parseNullablePercent(o["growth"]?.jsonObject?.get("count_pct")),
                        bytesPct = parseNullablePercent(o["growth"]?.jsonObject?.get("bytes_pct"))
                    )
                )
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaYearlyComparison FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** 解析 media-yearly-comparison 的年度统计子对象 {count, bytes, by_type}。 */
    private fun parseYearStat(obj: JsonObject?): YearStat {
        if (obj == null) return YearStat()
        val typeObj = obj["by_type"]?.jsonObject
        return YearStat(
            count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0,
            bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            byType = mapOf(
                "IMAGE" to (typeObj?.get("IMAGE")?.jsonPrimitive?.intOrNull ?: 0),
                "VIDEO" to (typeObj?.get("VIDEO")?.jsonPrimitive?.intOrNull ?: 0),
                "LIVE" to (typeObj?.get("LIVE")?.jsonPrimitive?.intOrNull ?: 0)
            )
        )
    }

    // parseNullablePercent 复用 growth-report / media-volume-report 既有同名私有方法
    // （JSON null/缺失 → Double.NaN，数字 → Double），此处不再重复定义。

    /** 年度对比响应体（今年 + 去年 + 增长率）。 */
    data class YearlyComparison(
        val thisYear: YearStat,
        val lastYear: YearStat,
        val growth: GrowthInfo
    )

    /** 单年统计（count / bytes / 类型分布，by_type 键为大写 IMAGE/VIDEO/LIVE）。 */
    data class YearStat(
        val count: Int = 0,
        val bytes: Long = 0L,
        val byType: Map<String, Int> = emptyMap()
    )

    /** 增长率（百分比）；去年同期为 0 时后端返回 null，前端映射为 [Double.NaN]。 */
    data class GrowthInfo(
        val countPct: Double = Double.NaN,
        val bytesPct: Double = Double.NaN
    )

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
     * 分页版 [parseMediaList]：额外解析 `total_count` 字段（缺失回退 0）。
     * 返回 Pair(list, totalCount)，供 [getMediaListPaged] 计算 hasMore。
     */
    private fun parseMediaListPaged(json: String): Pair<List<MediaMetadata>, Int> {
        val obj = Json.parseToJsonElement(json).jsonObject
        val list = obj["media_list"]?.jsonArray?.map { item ->
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
        val totalCount = obj["total_count"]?.jsonPrimitive?.intOrNull ?: 0
        return Pair(list, totalCount)
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

    /** V8：操作时间线条目（与后端 [handleAuditTimeline] item JSON 对齐）。 */
    data class AuditTimelineItem(
        val id: String,
        val action: String,
        val detail: String,
        val mediaId: String,
        val createdAt: String,
        val relativeTime: String
    )

    /**
     * V8：GET /api/media/audit-timeline?limit=50 — 操作时间线。
     *
     * 返回当前用户最近 [limit] 条审计记录（按 created_at 倒序），每条附中文相对时间
     * `relative_time`（如"3分钟前"/"昨天"，由后端 [relativeTimeZh] 计算）。
     *
     * 后端响应体：`{ "timeline": [{id,action,detail,media_id,created_at,relative_time}], "total": N }`。
     * 注意 `id` 为后端 `audit_logs.id`（TEXT PRIMARY KEY），按字符串解析；`detail`/`media_id`
     * 为 omitempty 字段，缺失时回退空串。`created_at` 为 RFC3339 字符串，前端仅透传不解析。
     *
     * 失败返回 null，调用方按空状态展示。
     *
     * @param limit 返回上限（默认 50，后端上限 200，<=0 回退默认 50）
     */
    suspend fun getAuditTimeline(limit: Int = 50): List<AuditTimelineItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/audit-timeline") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["timeline"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AuditTimelineItem(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        action = o["action"]?.jsonPrimitive?.contentOrNull ?: "",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: "",
                        relativeTime = o["relative_time"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getAuditTimeline status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAuditTimeline FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 重命名历史条目（与后端 GET /api/media/media-rename-history 的 historyItem JSON 对齐）。
     *
     * 后端返回 `{ "history": [{media_id, old_name, new_name, renamed_at}], "total": N }`，
     * [renamedAt] 为 RFC3339 字符串，前端仅透传不解析（由卡片侧截取日期部分展示）。
     *
     * @param mediaId 媒体 ID
     * @param oldName 重命名前文件名
     * @param newName 重命名后文件名
     * @param renamedAt 重命名发生时间（RFC3339 字符串）
     */
    data class RenameHistoryItem(
        val mediaId: String,
        val oldName: String,
        val newName: String,
        val renamedAt: String
    )

    /**
     * GET /api/media/media-rename-history?limit=50 — 重命名历史。
     *
     * 返回当前用户最近 [limit] 条媒体重命名记录（按 renamed_at 倒序）。
     * 后端响应体：`{ "history": [{media_id, old_name, new_name, renamed_at}], "total": N }`。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getAuditTimeline] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param limit 返回上限（默认 50）
     * @return 重命名历史列表；失败返回 null
     */
    suspend fun getMediaRenameHistory(limit: Int = 50): List<RenameHistoryItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-rename-history") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["history"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    RenameHistoryItem(
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        oldName = o["old_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        newName = o["new_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        renamedAt = o["renamed_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getMediaRenameHistory status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaRenameHistory FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 分享历史条目（与后端 GET /api/media/media-share-history 的 historyItem JSON 对齐）。
     *
     * 后端返回 `{ "history": [{media_id, detail, shared_at}], "total": N }`，
     * [sharedAt] 为 RFC3339 字符串，前端仅透传不解析（由卡片侧截取日期部分展示）。
     *
     * @param mediaId 媒体 ID
     * @param detail 分享详情（后端拼装的描述文本）
     * @param sharedAt 分享发生时间（RFC3339 字符串）
     */
    data class ShareHistoryItem(
        val mediaId: String,
        val detail: String,
        val sharedAt: String
    )

    /**
     * GET /api/media/media-share-history?limit=50 — 分享历史。
     *
     * 返回当前用户最近 [limit] 条媒体分享记录（按 shared_at 倒序）。
     * 后端响应体：`{ "history": [{media_id, detail, shared_at}], "total": N }`。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaRenameHistory] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param limit 返回上限（默认 50）
     * @return 分享历史列表；失败返回 null
     */
    suspend fun getMediaShareHistory(limit: Int = 50): List<ShareHistoryItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-share-history") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["history"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    ShareHistoryItem(
                        mediaId = o["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                        sharedAt = o["shared_at"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getMediaShareHistory status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaShareHistory FAILED: ${e::class.simpleName} ${e.message}")
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
     * 近似重复媒体对条目（与后端 [handleMediaDuplicatesSimilar] 的 pair JSON 对齐）。
     *
     * 后端按"同类型 + 文件大小差距 <5% + 分辨率完全相同"两两比对，捕获 SHA256 不同但
     * 物理特征高度相近的可疑对（同一照片的不同格式/质量版本）。与 [DupReport]（精确
     * SHA256 去重）互补。[size] 为较大者的字节，[resolution] 形如 "1920x1080"。
     *
     * @param mediaAId 媒体 A 的 ID
     * @param mediaBId 媒体 B 的 ID
     * @param filenameA 媒体 A 的文件名
     * @param filenameB 媒体 B 的文件名
     * @param size 较大者的文件大小（字节）
     * @param resolution 分辨率字符串 "WxH"
     */
    data class DupSimilarPair(
        val mediaAId: String,
        val mediaBId: String,
        val filenameA: String,
        val filenameB: String,
        val size: Long,
        val resolution: String
    )

    /**
     * GET /api/media/media-duplicates-similar?limit=50 — 获取近似重复媒体对列表。
     *
     * 后端两两比对用户媒体库，返回 SHA256 不同但同类型 + size 差距 <5% + 分辨率完全
     * 相同的可疑对，响应：`{ "pairs": [{media_a_id,media_b_id,filename_a,filename_b,
     * size,resolution,type,size_diff}], "total": N }`。
     *
     * 解析沿用运行时 JSON 操作（与 [getDupReport] 同款）。HTTP 非 200 或网络异常返回
     * null，调用方按空态处理。[limit] 截断到前 50 对（设置页仅展示前 5，此处多取一些
     * 兼顾将来扩展）。鉴权头由 defaultRequest 统一注入。
     *
     * @param limit 返回上限（默认 50，后端上限 500）
     * @return 近似重复对列表；失败返回 null
     */
    suspend fun getMediaDuplicatesSimilar(limit: Int = 50): List<DupSimilarPair>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-duplicates-similar") {
                parameter("limit", limit)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["pairs"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val aId = item["media_a_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val bId = item["media_b_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    DupSimilarPair(
                        mediaAId = aId,
                        mediaBId = bId,
                        filenameA = item["filename_a"]?.jsonPrimitive?.contentOrNull ?: "",
                        filenameB = item["filename_b"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        resolution = item["resolution"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getMediaDuplicatesSimilar status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaDuplicatesSimilar FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 批量一键删除重复文件结果（与后端 [handleMediaDuplicatesBatchDelete] 响应对齐）。
     *
     * 后端按 SHA256 分组，每组保留最早（created_at 最小）一份，其余软删，best-effort：
     * - [deletedCount] 成功软删的行数
     * - [freedBytes] 这些行 Size 之和（int64 字节；磁盘文件由后续清理任务回收）
     * - [groupsProcessed] 处理的重复组数（len>=2 的 SHA256 分组数）
     * - [errors] 失败项列表，每项形如 `"media_id: error"`（前端仅作摘要展示，不阻断其余项）
     *
     * 用普通 data class 承载——解析走运行时 [Json.parseToJsonElement]（与
     * [getMediaIntegrityReport] 同款），不依赖 kotlinx.serialization 编译器插件。
     */
    data class BatchDeleteResult(
        val deletedCount: Int,
        val freedBytes: Long,
        val groupsProcessed: Int,
        val errors: List<String>
    )

    /**
     * POST /api/media/media-duplicates-batch-delete — 一键删除全部重复文件。
     *
     * 后端按 SHA256 分组，每组保留最早（created_at 最小）一份原件，其余成员标记软删除
     * （deleted=1，不物理删文件，与回收站策略一致），归属校验由 MarkDeletedForUser 按
     * (id,user_id) 双键过滤防横向越权。响应结构（见 [handleMediaDuplicatesBatchDelete]）：
     * `{deleted_count, freed_bytes, groups_processed, errors:[{media_id,error}], user_id}`。
     * errors 为 best-effort 失败项，不阻断其余删除；前端仅作摘要展示。
     *
     * 本端点为写操作（POST only），无请求体（后端直接从 DB 拉全部媒体按 SHA256 分组）。
     * 鉴权头由显式 `getAuthToken()?.let { header(...) }` 注入（与 [renameMedia] 等 POST 写端点
     * 同款）。HTTP 非 200 或网络异常返回 null，调用方按失败提示处理。
     *
     * @return 删除结果 [BatchDeleteResult]；失败返回 null
     */
    suspend fun batchDeleteDuplicates(): BatchDeleteResult? {
        return try {
            val response: HttpResponse = jsonClient.post("${backendBaseUrl()}/api/media/media-duplicates-batch-delete") {
                contentType(ContentType.Application.Json)
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
                // 后端无请求体要求，发空 JSON 对象避免某些代理对空 POST 的处理差异
                setBody(buildJsonObject {})
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                BatchDeleteResult(
                    deletedCount = o["deleted_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    freedBytes = o["freed_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    groupsProcessed = o["groups_processed"]?.jsonPrimitive?.intOrNull ?: 0,
                    errors = o["errors"]?.jsonArray?.mapNotNull { el ->
                        val item = el.jsonObject
                        val mid = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val msg = item["error"]?.jsonPrimitive?.contentOrNull ?: ""
                        "$mid: $msg"
                    } ?: emptyList()
                )
            } else {
                logger.info("MediaService", "batchDeleteDuplicates status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "batchDeleteDuplicates FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 深度存储分析——单个交叉格（年份×类型 或 大小段×类型）的计数与字节数。
     *
     * 与后端 [handleStorageDeepAnalysis] 的 typeStat `{count, bytes}` 对齐。
     * [bytes] 用 Long（后端 int64）。前端展示时除以 1MB 换算。
     */
    data class TypeStat(
        val count: Int,
        val bytes: Long
    )

    /**
     * 深度存储分析结果——对应后端 [handleStorageDeepAnalysis] 响应。
     *
     * 后端结构：
     * ```
     * { "by_year_type":  { "<year>": { "IMAGE":{count,bytes}, "VIDEO":..., "LIVE_PHOTO":... } },
     *   "by_size_type":  { "small|medium|large|xlarge": { "<type>":{count,bytes} } },
     *   "total":         { "count":N, "bytes":B, "mb":double } }
     * ```
     * 年份键为字符串（如 "2026"），类型键为 IMAGE/VIDEO/LIVE_PHOTO（后端已为每个年份补齐
     * 三种主类型零值槽，前端直接按固定类型维度渲染即可）。[byYearType] 保留解析得到的
     * 全部年份；设置页仅展示前 3 年，但数据层不截断以兼顾将来扩展。
     *
     * @param byYearType 年份 → 类型 → 该格统计
     * @param bySizeType 大小段 → 类型 → 该格统计（本卡片暂不展示，留作扩展）
     * @param totalCount 全部媒体计数（后端 total.count）
     * @param totalBytes 全部媒体字节数（后端 total.bytes）
     */
    data class StorageDeepAnalysis(
        val byYearType: Map<String, Map<String, TypeStat>>,
        val bySizeType: Map<String, Map<String, TypeStat>> = emptyMap(),
        val totalCount: Int = 0,
        val totalBytes: Long = 0L
    )

    /**
     * GET /api/media/storage-deep-analysis — 深度存储分析（年份×类型 / 大小段×类型 二维矩阵）。
     *
     * 后端对当前用户未软删媒体做两套交叉分桶：
     * - by_year_type：按 CreatedAt UTC 年份 × IMAGE/VIDEO/LIVE_PHOTO，便于"每年图片/视频各占多少"。
     * - by_size_type：按 small(<1MB)/medium(1-10MB)/large(10-100MB)/xlarge(>100MB) × 类型。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaDuplicatesSimilar] 同款，feature-media 无
     * serialization 编译器插件）。年份键顺序不保证，调用方按需排序（设置页取最近 3 年）。
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理。鉴权头由 defaultRequest 统一注入。
     *
     * @return 深度分析结果；失败返回 null
     */
    suspend fun getStorageDeepAnalysis(): StorageDeepAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-deep-analysis")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // 解析交叉矩阵：外层 key→桶名/年份，内层 key→类型，值→{count,bytes}
                fun parseMatrix(obj: JsonObject?): Map<String, Map<String, TypeStat>> {
                    if (obj == null) return emptyMap()
                    val out = LinkedHashMap<String, MutableMap<String, TypeStat>>()
                    for ((bucket, typeMapEl) in obj) {
                        val typeMap = typeMapEl.jsonObject
                        val inner = LinkedHashMap<String, TypeStat>()
                        for ((type, statEl) in typeMap) {
                            val s = statEl.jsonObject
                            inner[type] = TypeStat(
                                count = s["count"]?.jsonPrimitive?.intOrNull ?: 0,
                                bytes = s["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                            )
                        }
                        out[bucket] = inner
                    }
                    return out
                }
                val byYearType = parseMatrix(o["by_year_type"]?.jsonObject)
                val bySizeType = parseMatrix(o["by_size_type"]?.jsonObject)
                val total = o["total"]?.jsonObject
                StorageDeepAnalysis(
                    byYearType = byYearType,
                    bySizeType = bySizeType,
                    totalCount = total?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = total?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else {
                logger.info("MediaService", "getStorageDeepAnalysis status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageDeepAnalysis FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V27：GET /api/media/storage-breakdown-v2 — 存储矩阵（类型×年份 二维交叉）。
     *
     * 与 [getStorageDeepAnalysis] 的 by_year_type（年份→类型）互补，本端点矩阵方向为
     * **类型→年份**：`matrix[type][year] = {count, bytes}`。响应顶层扁平：
     * ```
     * { "matrix":      { "IMAGE":{"2024":{count,bytes}, "2025":{...}},
     *                    "VIDEO":{...}, "LIVE_PHOTO":{...} },
     *   "total_count": N,
     *   "total_bytes": N }
     * ```
     * 复用既有 [TypeStat]（count + bytes，与 deep-analysis 同口径）。设置页\"存储矩阵\"
     * 卡片消费：转置为年份→类型后取最近 3 年，按图片/视频/Live 列展示每格 count。
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理。鉴权头由 defaultRequest 注入。
     */
    suspend fun getStorageBreakdownV2(): StorageBreakdownV2? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-breakdown-v2") {
                getAuthToken()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // 解析 matrix: type → year → {count,bytes}，沿用 deep-analysis 的运行时解析风格。
                val matrixEl = o["matrix"]?.jsonObject
                val matrix = LinkedHashMap<String, MutableMap<String, TypeStat>>()
                if (matrixEl != null) {
                    for ((type, yearMapEl) in matrixEl) {
                        val inner = LinkedHashMap<String, TypeStat>()
                        for ((year, statEl) in yearMapEl.jsonObject) {
                            val s = statEl.jsonObject
                            inner[year] = TypeStat(
                                count = s["count"]?.jsonPrimitive?.intOrNull ?: 0,
                                bytes = s["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                            )
                        }
                        matrix[type] = inner
                    }
                }
                StorageBreakdownV2(
                    matrix = matrix,
                    totalCount = o["total_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else {
                logger.info("MediaService", "getStorageBreakdownV2 status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageBreakdownV2 FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** V27：storage-breakdown-v2 结果——类型×年份 矩阵 + 顶层汇总。复用 [TypeStat] 作格单元。 */
    data class StorageBreakdownV2(
        val matrix: Map<String, Map<String, TypeStat>>,
        val totalCount: Int,
        val totalBytes: Long
    )

    /**
     * GET /api/media/media-storage-breakdown-v3 — 三维存储分项（JSON 字符串）。
     *
     * 后端按 type × year × size_range 三维交叉分组，叶子节点 `{count, bytes}`；顶层另附 `total`。
     * 响应结构（[handleStorageBreakdownV3] 已上线，commit def9ef7）：
     * ```
     * { "breakdown": { "IMAGE": { "2024": { "small":{count,bytes}, "medium":{...},
     *                                        "large":{...}, "xlarge":{...} },
     *                           "2025": {...} },
     *                  "VIDEO": {...}, "LIVE_PHOTO": {...} },
     *   "total":     { "count": N, "bytes": N } }
     * ```
     * 大小分桶与 storage-deep-analysis 同阈值：small<1MB / medium 1-10MB / large 10-100MB /
     * xlarge>100MB；年份用 CreatedAt UTC 年份；空类型归 IMAGE。breakdown 按数据如实填充
     * （不预置空槽），故前端访问 `(type,year)` 缺格须宽容回退。
     *
     * 与 [getStorageBreakdownV2]（类型×年份 二维，已结构化解析）互补：本端点含第三维 size_range
     * 嵌套。任务要求前端简化为按类型+年份的二维 2×2 概要展示，故此处服务层直接返回原始 JSON
     * 字符串；卡片层 [StorageBreakdownV3Card] 用 `Json.parseToJsonElement` 取
     * `breakdown[IMAGE|VIDEO][2024|2025][*].count` 之和做二维概要，每层缺失即 `?: 0` 回退。
     *
     * 鉴权头由 [jsonClient] 的 `defaultRequest` 中心化注入，此处不带 per-call auth lambda。
     *
     * @return 后端原始 JSON 字符串；HTTP 非 200 或网络异常返回 null
     */
    suspend fun getStorageBreakdownV3(): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-storage-breakdown-v3")
            if (response.status == HttpStatusCode.OK) {
                response.body<String>()
            } else {
                logger.info("MediaService", "getStorageBreakdownV3 status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageBreakdownV3 FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * 媒体库总览 —— 12 关键数字一次请求聚合。
     *
     * 后端 `GET /api/media/media-library-overview` 返回扁平 30+ 维度，前端仅取最常用的
     * 12 个核心指标做"我的"Tab 顶部快速摘要卡片。字段均 best-effort：后端某子统计失败
     * 时对应字段回退零值，UI 拿到一个非 null 的 [LibraryOverview] 自行渲染。healthScore /
     * diversityScore / activityScore 为 0~100 百分制；tagCoverage 为 0~1（已打标签媒体占比）。
     *
     * @param totalMedia       媒体总数（未软删）
     * @param totalBytes       媒体总占用字节
     * @param favorites        收藏数
     * @param albums           相册数
     * @param shares           分享链接数
     * @param healthScore      存储健康度评分 0~100
     * @param diversityScore   内容多样性评分 0~100
     * @param activityScore    活跃度评分 0~100
     * @param streak           连续上传天数
     * @param totalTags        标签总数（去重）
     * @param tagCoverage      标签覆盖率 0~1（已打标签媒体占比）
     * @param totalDuplicates  重复文件组数
     */
    data class LibraryOverview(
        val totalMedia: Int,
        val totalBytes: Long,
        val favorites: Int,
        val albums: Int,
        val shares: Int,
        val healthScore: Int,
        val diversityScore: Int,
        val activityScore: Int,
        val streak: Int,
        val totalTags: Int,
        val tagCoverage: Double,
        val totalDuplicates: Int
    ) {
        /** 总占用 MB（一位小数截断由调用方做，此处提供原始 MB）。 */
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /**
     * GET /api/media/media-library-overview — 媒体库总览（12 关键数字一次请求）。
     *
     * 后端把分散在 stat-summary / storage-stats / health / activity / diversity / tags /
     * duplicates 等端点的最常用指标合并为扁平响应，避免"我的"Tab 顶部摘要卡片逐卡片多次调用。
     * 前端仅消费 12 个核心字段（见 [LibraryOverview]），其余 30+ 维度暂不解析。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getStatSummary] / [getStorageStats] 同款）。**不附加 per-call 鉴权头**——Bearer token
     * 由 [jsonClient] 的 `defaultRequest` 统一注入（与 [getRelatedMedia] / [getMediaList] 同款）。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示总览卡片）。
     *
     * @return 总览对象；失败返回 null
     */
    suspend fun getMediaLibraryOverview(): LibraryOverview? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-library-overview")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                LibraryOverview(
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    favorites = o["favorites"]?.jsonPrimitive?.intOrNull ?: 0,
                    albums = o["albums"]?.jsonPrimitive?.intOrNull ?: 0,
                    shares = o["shares"]?.jsonPrimitive?.intOrNull ?: 0,
                    healthScore = o["health_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    diversityScore = o["diversity_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    activityScore = o["activity_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    streak = o["streak"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalTags = o["total_tags"]?.jsonPrimitive?.intOrNull ?: 0,
                    tagCoverage = o["tag_coverage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    totalDuplicates = o["total_duplicates"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaLibraryOverview status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaLibraryOverview FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 成长里程碑 —— 媒体库规模达成关键节点 (100/500/1000/5000/10000) 的历史记录
     * 与下一目标的预测达成日期。
     *
     * 后端 `GET /api/media/media-growth-milestone` 基于 ListMediaByUser 按上传时间
     * 正序累计，记录每个里程碑首次达成日期（见 [MilestoneItem]），并按全程日均增长
     * 速率外推 [nextMilestone] 的预测达成日期 [projectedDate]。
     *
     * @param achieved       已达成的里程碑列表（按 milestone 升序）
     * @param nextMilestone  下一未达成里程碑数值；全部已达成时为 null
     * @param projectedDate  下一里程碑预测达成日期（RFC3339 UTC）；无数据时为 null
     * @param total          媒体总数（未软删）
     */
    data class GrowthMilestone(
        val achieved: List<MilestoneItem>,
        val nextMilestone: Int?,
        val projectedDate: String?,
        val total: Int
    )

    /**
     * 单个已达成里程碑（与后端 achievedMilestone JSON 对齐）。
     *
     * @param milestone   里程碑阈值（100/500/1000/5000/10000）
     * @param date        达成日期（RFC3339 UTC，如 `2025-01-15T…Z`）
     * @param mediaCount  达成时点的累计媒体数
     */
    data class MilestoneItem(
        val milestone: Int,
        val date: String,
        val mediaCount: Int
    )

    /**
     * GET /api/media/media-growth-milestone — 媒体增长里程碑。
     *
     * 后端追踪媒体库规模达成关键里程碑 (100/500/1000/5000/10000) 的日期，并基于
     * 历史增长速率预测下一里程碑的达成日期。前端用于"我的"Tab 成长里程碑卡片
     * 展示已达成里程碑 + 下一目标 + 预测日期。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaLibraryOverview] 同款）。**不附加 per-call
     * 鉴权头**——Bearer token 由 [jsonClient] 的 `defaultRequest` 统一注入（与
     * [getRelatedMedia] / [getMediaList] 同款）。`next_milestone` / `projected_date`
     * 可为 JSON null，前端按可空类型接收（[nextMilestone] / [projectedDate]）。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @return 里程碑对象；失败返回 null
     */
    suspend fun getMediaGrowthMilestone(): GrowthMilestone? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-growth-milestone")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val achieved = o["achieved"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val ms = item["milestone"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    MilestoneItem(
                        milestone = ms,
                        date = item["date"]?.jsonPrimitive?.contentOrNull ?: "",
                        mediaCount = item["media_count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()
                GrowthMilestone(
                    achieved = achieved,
                    nextMilestone = o["next_milestone"]
                        ?.takeIf { it !is JsonNull }
                        ?.let { it.jsonPrimitive.intOrNull },
                    projectedDate = o["projected_date"]
                        ?.takeIf { it !is JsonNull }
                        ?.let { it.jsonPrimitive.contentOrNull },
                    total = o["total"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaGrowthMilestone status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaGrowthMilestone FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 相册覆盖率 —— 媒体归入相册的覆盖率画像。
     *
     * 后端 `GET /api/media/media-album-coverage` 统计当前用户全部未软删媒体中有多少
     * 被归入至少一个相册（[inAlbum]）、多少属于未分类（[notInAlbum]），并给出覆盖率
     * 百分比、相册数、每相册平均项数与一条整理建议。前端用于"我的"Tab 相册覆盖率卡片
     * 展示进度条 + 分类统计 + 建议。
     *
     * @param totalMedia       媒体总数（未软删）
     * @param inAlbum          已归入相册的媒体数
     * @param notInAlbum       未归入任何相册的媒体数
     * @param coveragePercent  覆盖率百分比 0~100
     * @param albumCount       相册总数
     * @param avgPerAlbum      每个相册的平均媒体项数
     * @param suggestion       整理建议文字；无建议时为空串
     */
    data class AlbumCoverage(
        val totalMedia: Int,
        val inAlbum: Int,
        val notInAlbum: Int,
        val coveragePercent: Double,
        val albumCount: Int,
        val avgPerAlbum: Double,
        val suggestion: String
    )

    /**
     * GET /api/media/media-album-coverage — 相册覆盖率。
     *
     * 后端统计媒体归入相册的覆盖率，返回
     * `{total_media, in_album, not_in_album, coverage_percent, album_count, avg_per_album, suggestion}`。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaGrowthMilestone] 同款）。**不附加 per-call
     * 鉴权头**——Bearer token 由 [jsonClient] 的 `defaultRequest` 统一注入。各字段宽容回退
     *（`?: 0` / `?: 0.0` / `?: ""`），后端端点上线后即可联调。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @return 覆盖率对象；失败返回 null
     */
    suspend fun getMediaAlbumCoverage(): AlbumCoverage? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-album-coverage")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                AlbumCoverage(
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    inAlbum = o["in_album"]?.jsonPrimitive?.intOrNull ?: 0,
                    notInAlbum = o["not_in_album"]?.jsonPrimitive?.intOrNull ?: 0,
                    coveragePercent = o["coverage_percent"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    albumCount = o["album_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    avgPerAlbum = o["avg_per_album"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    suggestion = o["suggestion"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } else {
                logger.info("MediaService", "getMediaAlbumCoverage status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaAlbumCoverage FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 收藏分析 —— 媒体库收藏率与类型分布画像。
     *
     * 后端 `GET /api/media/media-favorite-analysis` 统计当前用户全部未软删媒体的收藏率
     *（[favoriteRate] = 收藏数 / 总数）、收藏项按媒体类型（图片/视频）分布（[byType]）
     * 以及收藏媒体平均上传到收藏的间隔天数（[avgAgeDays]）。前端用于"我的"Tab "收藏分析"
     * 卡片展示收藏率进度 + 类型分布 + 平均间隔。
     *
     * 后端字段约定（snake_case JSON → camelCase Kotlin，已对照 handler 核实）：
     *   `total_favorites: Int` · `total_media: Int` · `favorite_rate: Double`(**0~100 百分比**, round2)
     * · `by_type: {"IMAGE":Int,"VIDEO":Int,"LIVE":Int,"OTHER":Int}` (**键名大写**)
     * · `avg_age_days: Double`（ageCount==0 时为 **JSON null** → 前端回退 0.0）
     *（`by_hour` 后端亦返回 24 槽，前端本卡片不展示，忽略即可）。
     *
     * @param totalFavorites 收藏媒体总数
     * @param totalMedia     媒体总数（未软删）
     * @param favoriteRate   收藏率百分比 0~100（后端 round2）
     * @param byType         收藏项按类型分布（键名大写 "IMAGE"/"VIDEO"/"LIVE"/"OTHER" → 数量）
     * @param avgAgeDays     收藏媒体平均上传到收藏间隔天数；无有效样本时为 0.0
     */
    data class FavoriteAnalysis(
        val totalFavorites: Int,
        val totalMedia: Int,
        val favoriteRate: Double,
        val byType: Map<String, Int>,
        val avgAgeDays: Double
    )

    /**
     * GET /api/media/media-favorite-analysis — 收藏分析。
     *
     * 后端返回
     * `{total_favorites, total_media, favorite_rate, by_type, by_hour, avg_age_days}`，
     * 其中 `by_type` 为媒体类型→数量的 JSON 对象（键名**大写** `IMAGE`/`VIDEO`/`LIVE`/`OTHER`，
     * 后端用 `strings.ToUpper(m.Type)` 分桶），`favorite_rate` 为 **0~100 百分比**（`round2`，
     * 非 0~1），`avg_age_days` 为平均间隔天数（**`ageCount==0` 时为 JSON `null`**，需
     * `takeIf { it !is JsonNull }` 守卫）。`by_hour` 字段本卡片不使用。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaAlbumCoverage] 同款）。**不附加 per-call
     * 鉴权头**——Bearer token 由 [jsonClient] 的 `defaultRequest` 统一注入。各字段宽容回退
     *（`?: 0` / `?: 0.0`）；`by_type` 若缺失返回空 Map。
     *
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @return 收藏分析对象；失败返回 null
     */
    suspend fun getMediaFavoriteAnalysis(): FavoriteAnalysis? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-favorite-analysis")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // by_type 是嵌套对象，逐键宽容读取（缺失键→0，整对象缺失→空 Map）。
                // 后端键名大写：IMAGE / VIDEO / LIVE / OTHER。
                val byType = mutableMapOf<String, Int>()
                o["by_type"]?.jsonObject?.forEach { (k, v) ->
                    byType[k] = v.jsonPrimitive?.intOrNull ?: 0
                }
                // favorite_rate 后端为 0~100 百分比（round2）。
                // avg_age_days 后端 ageCount==0 时为 JSON null，需 takeIf 非 JsonNull。
                FavoriteAnalysis(
                    totalFavorites = o["total_favorites"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    favoriteRate = o["favorite_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    byType = byType,
                    avgAgeDays = o["avg_age_days"]?.takeIf { it !is JsonNull }?.let { it.jsonPrimitive.doubleOrNull }
                        ?: 0.0
                )
            } else {
                logger.info("MediaService", "getMediaFavoriteAnalysis status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaFavoriteAnalysis FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 标签使用频率单项（[getMediaTagUsageFrequency] 返回）。
     *
     * @param tagName   标签名
     * @param totalUses 统计窗口内总使用次数
     * @param monthlyAvg 月均使用次数（Double，后端 round）
     * @param lastUsed  最近一次使用时间（RFC3339 字符串，前端直接展示前 10 位日期）
     * @param trend     使用趋势字符串：后端约定 `up`（上升）/`down`（下降）/`stable`（平稳）；
     *                  缺失回退 `stable`。卡片层映射为 ↑/↓/→。
     */
    data class TagUsageFreq(
        val tagName: String,
        val totalUses: Int,
        val monthlyAvg: Double,
        val lastUsed: String,
        val trend: String
    )

    /**
     * GET /api/media/media-tag-usage-frequency?months=N — 标签使用频率（月均+趋势）。
     *
     * 后端基于 audit_log 的 `tag` 操作，按 detail（标签标识）在最近 N 个月窗口内聚合，返回
     * `{ tags: [ {tag_name, total_uses, monthly_avg, last_used, trend} ], total_tags }`：
     * - `tag_name`：标签名（detail 为空时后端回退 `"(default)"`）
     * - `total_uses`：窗口内总使用次数（Int）
     * - `monthly_avg`：月均次数——**后端发字符串**（`strconv.FormatFloat 'f' 2`，如 `"1.50"`），
     *   非 JSON 数字；解析处先尝 `doubleOrNull`（兼容数字字面量）再 `contentOrNull.toDoubleOrNull`。
     * - `last_used`：窗口内最近使用时间（RFC3339 字符串；零值时后端发空串 `""`）
     * - `trend`：`up`/`down`/`stable`（末月 vs 首月计数比较）；恒为非空字符串
     * - `total_tags`：返回的标签总数
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件）。
     * 鉴权头由 [jsonClient] 的 `defaultRequest` 中心化注入，此处不带 per-call auth lambda
     *（与 [getMediaInteractionSummary] 同款 clean form）。
     *
     * 列表按后端给定顺序（total_uses desc，并列按 tag_name asc）取用，调用方最多展示 5 行。
     * HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @param months 统计窗口月数（默认 6，透传 query param）
     * @return 标签使用频率列表；失败返回 null
     */
    suspend fun getMediaTagUsageFrequency(months: Int = 6): List<TagUsageFreq>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-tag-usage-frequency") {
                parameter("months", months)
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["tags"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    TagUsageFreq(
                        tagName = name,
                        totalUses = o["total_uses"]?.jsonPrimitive?.intOrNull ?: 0,
                        // monthly_avg 后端发字符串（strconv.FormatFloat 'f' 2，如 "1.50"），
                        // 非 JSON 数字——doubleOrNull 对字符串返回 null。需取 contentOrNull 后
                        // toDoubleOrNull 兜底，同时兼容万一后端改为数字字面量（先尝 doubleOrNull）。
                        monthlyAvg = o["monthly_avg"]?.let { el ->
                            el.jsonPrimitive.doubleOrNull
                                ?: el.jsonPrimitive.contentOrNull?.toDoubleOrNull()
                        } ?: 0.0,
                        lastUsed = o["last_used"]?.takeIf { it !is JsonNull }
                            ?.let { it.jsonPrimitive.contentOrNull } ?: "",
                        trend = o["trend"]?.takeIf { it !is JsonNull }
                            ?.let { it.jsonPrimitive.contentOrNull } ?: "stable"
                    )
                }
            } else {
                logger.info("MediaService", "getMediaTagUsageFrequency months=$months status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaTagUsageFrequency FAILED months=$months: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 最近 N 天交互汇总（操作分布 + 趋势）。
     *
     * 对应后端 `GET /api/media/media-interaction-summary?days=30`（基于 audit_log 统计）。
     * 响应字段（snake_case）：
     * - `total_actions`：窗口内操作总次数
     * - `by_action`：各操作类型→次数（`upload`/`favorite`/`share`/`tag`/`rename`/`rotate`…）
     * - `most_frequent`：最频繁操作类型字符串；无数据时后端为 JSON null
     * - `daily_avg`：日均操作次数（Double）
     * - `trend_direction`：最近 7 天 vs 之前 7 天的操作趋势 `"up"`/`"down"`/`"flat"`；无数据时为 JSON null
     * - `total_days`：统计窗口天数
     *
     * [byAction] 保留后端操作名键 + 次数；[mostFrequent] / [trendDirection] 缺失回退空串。
     * getter 非 200 或异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @param totalActions 窗口内操作总次数
     * @param byAction     各操作类型→次数（后端键名小写 action 名）
     * @param mostFrequent 最频繁操作类型；无数据为空串
     * @param dailyAvg     日均操作次数
     * @param trendDirection `"up"`/`"down"`/`"flat"`；无数据为空串
     * @param totalDays    统计窗口天数
     */
    data class InteractionSummary(
        val totalActions: Int,
        val byAction: Map<String, Int>,
        val mostFrequent: String,
        val dailyAvg: Double,
        val trendDirection: String,
        val totalDays: Int
    )

    /**
     * GET /api/media/media-interaction-summary?days=N — 最近 N 天交互汇总。
     *
     * 后端基于 audit_log 统计操作总量、各操作类型分布、最频繁操作、日均、以及最近 7 天 vs
     * 之前 7 天趋势方向（`up`/`down`/`flat`）。`most_frequent` 与 `trend_direction` 在窗口无
     * 数据时后端返回 JSON null，需 `takeIf { it !is JsonNull }` 守卫。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaFavoriteAnalysis] 同款），不附加 per-call 鉴权头
     *（Bearer 由 [jsonClient] 的 `defaultRequest` 统一注入）。`by_action` 缺失返回空 Map。
     *
     * @param days 统计窗口天数（默认 30，后端 clamp [1,365]）
     * @return 交互汇总对象；失败返回 null
     */
    suspend fun getMediaInteractionSummary(days: Int = 30): InteractionSummary? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-interaction-summary") {
                parameter("days", days)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // by_action 是嵌套对象，逐键宽容读取（缺失键→0，整对象缺失→空 Map）。
                val byAction = mutableMapOf<String, Int>()
                o["by_action"]?.jsonObject?.forEach { (k, v) ->
                    byAction[k] = v.jsonPrimitive?.intOrNull ?: 0
                }
                InteractionSummary(
                    totalActions = o["total_actions"]?.jsonPrimitive?.intOrNull ?: 0,
                    byAction = byAction,
                    mostFrequent = o["most_frequent"]?.takeIf { it !is JsonNull }
                        ?.let { it.jsonPrimitive.contentOrNull } ?: "",
                    dailyAvg = o["daily_avg"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    trendDirection = o["trend_direction"]?.takeIf { it !is JsonNull }
                        ?.let { it.jsonPrimitive.contentOrNull } ?: "",
                    totalDays = o["total_days"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaInteractionSummary days=$days status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaInteractionSummary FAILED days=$days: ${e::class.simpleName} ${e.message}")
            null
        }
    }

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
     * GET /api/media/media-bulk-export — 批量导出当前用户媒体元数据（筛选后 JSON）。
     *
     * 后端按可选筛选条件返回命中媒体的元数据列表（[exported_at, total, media:[...]]），
     * 每条 media 携带 id/filename/type/size/mime/width/height/sha256/created_at/taken_at。
     * 各筛选条件均可选，空串即不施加该条件：
     *
     * - [type]：IMAGE / VIDEO / LIVE_PHOTO（精确匹配；后端对空 type 行归一为 IMAGE 再比）
     * - [tag]：精确标签名，命中 media_tags 关联的 media_id 集合做内存交集
     * - [dateFrom]：日期（YYYY-MM-DD）。前端把"YYYY-MM-DD"补成 RFC3339 当地 00:00:00→Z 透传。
     *   后端按 time.Parse(time.RFC3339) 解析，非法值静默忽略，故空串/格式错时仅不施加该条件。
     * - [dateTo]：同 [dateFrom]，对应闭区间右端（created_at <= 该时刻）。前端补成当天 23:59:59→Z。
     *
     * 返回 pretty 化后的 JSON 字符串（2 空格缩进，便于阅读）；非 200 或网络异常返回 null，
     * UI 侧提示"导出失败"。与 [getFullReport] 同款 pretty 化逻辑。
     *
     * @param type 媒体类型筛选，空串不施条件
     * @param tag 标签筛选，空串不施条件
     * @param dateFrom 起始日期 YYYY-MM-DD，空串不施条件
     * @param dateTo 截止日期 YYYY-MM-DD，空串不施条件
     * @return pretty 化后的 JSON 字符串；失败返回 null
     */
    suspend fun getMediaBulkExport(
        type: String = "",
        tag: String = "",
        dateFrom: String = "",
        dateTo: String = ""
    ): String? {
        return try {
            val response: HttpResponse = jsonClient.get("${'$'}{backendBaseUrl()}/api/media/media-bulk-export") {
                if (type.isNotBlank()) parameter("type", type.trim())
                if (tag.isNotBlank()) parameter("tag", tag.trim())
                // 日期补齐为 RFC3339：date_from 当地 00:00:00 / date_to 当地 23:59:59 + Z。
                // 复用既有的 [rfc3339StartOfDay]/[rfc3339EndOfDay]（advanced-search 同款），
                // 后端按 RFC3339 解析；非 YYYY-MM-DD 格式原样透传，后端 Parse 失败则静默忽略
                // 该条件（即不施加），与"空串不筛"等价。
                if (dateFrom.isNotBlank()) parameter("date_from", rfc3339StartOfDay(dateFrom.trim()))
                if (dateTo.isNotBlank()) parameter("date_to", rfc3339EndOfDay(dateTo.trim()))
            }
            if (response.status == HttpStatusCode.OK) {
                val body: String = response.body()
                logger.info(
                    "MediaService",
                    "getMediaBulkExport type=${'$'}type tag=${'$'}tag dateFrom=${'$'}dateFrom dateTo=${'$'}dateTo " +
                        "status=${'$'}{response.status} bytes=${'$'}{body.length}"
                )
                // pretty 化：与 getFullReport 同款，解析失败（非合法 JSON）原样返回。
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
                logger.info("MediaService", "getMediaBulkExport status=${'$'}{response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error(
                "MediaService",
                "getMediaBulkExport FAILED: ${'$'}{e::class.simpleName} ${'$'}{e.message}"
            )
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
     * GET /api/media/media-storage-efficiency — 存储效率分析。
     *
     * 后端用"每 MB 媒体数（密度）"作为效率核心指标，结合重复率与平均大小惩罚
     * （avg_bytes_per_media 相对 2MB 基准的偏离），算出 0-100 的 [StorageEfficiency.efficiencyScore]
     * 与字母等级 [StorageEfficiency.grade]（A/B/C/D），并给出针对性优化 [StorageEfficiency.suggestions]。
     *
     * 与 [getStorageHealth]（健康度，关注重复/配额/冷数据四维）互补：本端点聚焦"空间利用率"，
     * 以 [mediaPerMb]（每 MB 容纳媒体数）与 [avgBytesPerMedia] 评估是否被大文件拖累。
     *
     * 后端响应结构：
     * ```
     * { "total_media": N, "total_bytes": N, "avg_bytes_per_media": N,
     *   "media_per_mb": 0.5,          // 0.0-...，密度（越高越高效）
     *   "duplicate_rate": 0.12,       // 0.0-1.0
     *   "efficiency_score": 82,       // 0-100
     *   "grade": "B",                 // A/B/C/D
     *   "suggestions": ["...", "..."] }
     * ```
     *
     * 解析宽容：缺字段回退零值/空列表，保证 UI 永不崩。HTTP 非 200 或网络异常返回 null，
     * 调用方按空态提示"无法获取存储效率"。鉴权头由 defaultRequest 统一注入（与 [getStorageHealth]
     * 同款，此处不重复附加）。
     *
     * @return 存储效率对象；失败返回 null
     */
    suspend fun getMediaStorageEfficiency(): StorageEfficiency? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-storage-efficiency")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                StorageEfficiency(
                    totalMedia = o["total_media"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = o["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    avgBytesPerMedia = o["avg_bytes_per_media"]?.jsonPrimitive?.longOrNull ?: 0L,
                    duplicateRate = o["duplicate_rate"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    efficiencyScore = o["efficiency_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    grade = o["grade"]?.jsonPrimitive?.contentOrNull ?: "",
                    suggestions = o["suggestions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
                    mediaPerMb = o["media_per_mb"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            } else {
                logger.info("MediaService", "getMediaStorageEfficiency status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaStorageEfficiency FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * media-storage-efficiency 响应体。
     *
     * @param totalMedia 未软删媒体总数
     * @param totalBytes 未软删媒体总字节数
     * @param avgBytesPerMedia 平均每份媒体字节数（= total_bytes / total_media）
     * @param duplicateRate 重复率 0.0-1.0（重复份数 / total_media）
     * @param efficiencyScore 0-100 效率评分（越高越高效）
     * @param grade 字母等级 A/B/C/D（后端给定，前端按首字母着色：A绿/B蓝/C橙/D红）
     * @param suggestions 优化建议文本列表
     * @param mediaPerMb 每 MB 容纳的媒体数（密度，越高越高效；total_bytes=0 时为 0）
     */
    data class StorageEfficiency(
        val totalMedia: Int,
        val totalBytes: Long,
        val avgBytesPerMedia: Long,
        val duplicateRate: Double,
        val efficiencyScore: Int,
        val grade: String,
        val suggestions: List<String>,
        val mediaPerMb: Double
    )

    /**
     * V25：GET /api/media/media-error-check — 媒体错误检查（损坏文件检测）。
     *
     * 后端逐条扫描未软删媒体，按优先级判定三种错误（单条 media 至多记录一种）：
     * zero_size（DB Size<=0）/ missing_file（磁盘文件缺失或 stat 失败）/ size_mismatch
     * （磁盘大小与 DB 不符）。设置页"媒体错误检查"卡片据此渲染检查项数、错误数与错误列表。
     *
     * 后端响应结构：
     * ```
     * { "errors": [
     *     { "media_id": "...", "filename": "...", "error_type": "zero_size|missing_file|size_mismatch",
     *       "db_size": 1234, "disk_size": 1300 }   // disk_size 仅 size_mismatch 时携带（omitempty）
     *   ],
     *   "total_errors": 2,
     *   "total_checked": 100 }
     * ```
     *
     * 解析沿用 [getFileTypes] 的运行时 JSON 操作（feature-media 无 serialization 编译器插件）。
     * `disk_size` 为后端 `int64` + `omitempty`——仅在 size_mismatch 时出现，其余情况 key 缺失或
     * JSON null，需用 [JsonNull] 守卫后再取 `.jsonPrimitive`（直接 `?.jsonPrimitive` 会抛
     * IllegalStateException）。HTTP 非 200 或网络异常返回 null，调用方按"无法获取"提示。
     * 鉴权头由 defaultRequest 统一注入（与 [getStorageHealth] 同款，此处不重复附加）。
     *
     * @return 错误检查报告；失败返回 null
     */
    suspend fun getMediaErrorCheck(): MediaErrorReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-error-check")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                MediaErrorReport(
                    errors = o["errors"]?.jsonArray?.mapNotNull { item ->
                        val e = item.jsonObject
                        MediaError(
                            mediaId = e["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            filename = e["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                            errorType = e["error_type"]?.jsonPrimitive?.contentOrNull ?: "",
                            dbSize = e["db_size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            diskSize = e["disk_size"]?.let {
                                if (it is JsonNull) null else it.jsonPrimitive?.longOrNull
                            }
                        )
                    } ?: emptyList(),
                    totalErrors = o["total_errors"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalChecked = o["total_checked"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaErrorCheck status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaErrorCheck FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V25：media-error-check 响应体。
     *
     * @param errors 损坏文件列表（每条含 media_id/filename/error_type/db_size，size_mismatch 时带 disk_size）
     * @param totalErrors 错误总数
     * @param totalChecked 本次检查的媒体总数
     */
    data class MediaErrorReport(
        val errors: List<MediaError>,
        val totalErrors: Int,
        val totalChecked: Int
    )

    /**
     * V25：单条媒体错误。`diskSize` 仅在 [errorType] == "size_mismatch" 时由后端携带，
     * 其余错误类型为 null。
     *
     * @param mediaId 媒体 ID
     * @param filename 文件名
     * @param errorType 错误类型：zero_size / missing_file / size_mismatch
     * @param dbSize DB 记录大小（字节）
     * @param diskSize 磁盘实际大小（字节），仅 size_mismatch 时非 null
     */
    data class MediaError(
        val mediaId: String,
        val filename: String,
        val errorType: String,
        val dbSize: Long,
        val diskSize: Long?
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

    /** V8：GET /api/media/storage-trend-extended — 扩展存储趋势（环比+同比）。 */
    suspend fun getStorageTrendExtended(months: Int = 12): List<StorageTrendExtended>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/storage-trend-extended?months=$months") {
                header("Authorization", "Bearer ${getAuthToken()}")
            }
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["months"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    StorageTrendExtended(
                        month = o["month"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        momGrowth = o["mom_growth"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        yoyGrowth = o["yoy_growth"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    )
                }
            } else null
        } catch (e: Exception) {
            logger.error("MediaService", "getStorageTrendExtended FAILED: ${e.message}")
            null
        }
    }

    data class StorageTrendExtended(
        val month: String,
        val count: Int,
        val bytes: Long,
        val momGrowth: Double,
        val yoyGrowth: Double
    )

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

    /**
     * V24：GET /api/media/user-dashboard-v2 — 增强用户仪表盘（一次请求合并6维度）。
     *
     * 后端 [handleUserDashboardV2] 将 quick_stats / health / activity / coverage /
     * streak / insights 六大维度合并为单一 JSON 对象，前端一次拉取即可渲染首页/
     * 设置页全部关键卡片，区别于 [getDashboardOverview]（前端组合3路请求）与
     * [getMediaInsights]（仅洞察单维度）。
     *
     * 响应结构：
     * ```
     * {
     *   "quick_stats": { total_media, total_bytes, image_count, video_count, album_count, favorite_count },
     *   "health":      { score:0-100, grade:"A"/"B"/"C"/"D", duplicate_rate, quota_usage, ... },
     *   "activity":    { score, level:"新手"/"活跃"/"达人"/"专家", breakdown, total_actions },
     *   "coverage":    { total, tagged_count, tagged_percent, fav_count, favorited_percent },
     *   "streak":      { current_streak, longest_streak, total_active_days, last_upload_date, today_count },
     *   "insights":    [ { type, title, detail, action_url? }, ... ],   // top 3
     *   "user_id":     "..."
     * }
     * ```
     *
     * 解析宽容：各子对象缺字段回退 0/空串，[insights] 缺失返回空列表。HTTP 非 200
     * 或网络异常返回 null，调用方按空态处理（不展示卡片或提示"无法获取"）。
     * 鉴权头由 defaultRequest 统一注入，此处不重复附加。
     *
     * @return 合并6维度仪表盘数据；失败返回 null
     */
    suspend fun getUserDashboardV2(): UserDashboardV2? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/user-dashboard-v2")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                // quick_stats
                val qs = o["quick_stats"]?.jsonObject
                val quickStats = UserDashboardV2.QuickStats(
                    totalMedia = qs?.get("total_media")?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = qs?.get("total_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                    imageCount = qs?.get("image_count")?.jsonPrimitive?.intOrNull ?: 0,
                    videoCount = qs?.get("video_count")?.jsonPrimitive?.intOrNull ?: 0,
                    albumCount = qs?.get("album_count")?.jsonPrimitive?.intOrNull ?: 0,
                    favoriteCount = qs?.get("favorite_count")?.jsonPrimitive?.intOrNull ?: 0
                )
                // health
                val h = o["health"]?.jsonObject
                val health = UserDashboardV2.Health(
                    score = h?.get("score")?.jsonPrimitive?.intOrNull ?: 0,
                    grade = h?.get("grade")?.jsonPrimitive?.contentOrNull ?: "D",
                    duplicateRate = h?.get("duplicate_rate")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    quotaUsage = h?.get("quota_usage")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    duplicateCount = h?.get("duplicate_count")?.jsonPrimitive?.intOrNull ?: 0,
                    coldCount = h?.get("cold_count")?.jsonPrimitive?.intOrNull ?: 0,
                    usedBytes = h?.get("used_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                    quotaBytes = h?.get("quota_bytes")?.jsonPrimitive?.longOrNull ?: 0L
                )
                // activity
                val a = o["activity"]?.jsonObject
                val activity = UserDashboardV2.Activity(
                    score = a?.get("score")?.jsonPrimitive?.intOrNull ?: 0,
                    level = a?.get("level")?.jsonPrimitive?.contentOrNull ?: "新手",
                    totalActions = a?.get("total_actions")?.jsonPrimitive?.intOrNull ?: 0
                )
                // coverage
                val c = o["coverage"]?.jsonObject
                val coverage = UserDashboardV2.Coverage(
                    total = c?.get("total")?.jsonPrimitive?.intOrNull ?: 0,
                    taggedCount = c?.get("tagged_count")?.jsonPrimitive?.intOrNull ?: 0,
                    taggedPercent = c?.get("tagged_percent")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    favCount = c?.get("fav_count")?.jsonPrimitive?.intOrNull ?: 0,
                    favoritedPercent = c?.get("favorited_percent")?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
                // streak
                val s = o["streak"]?.jsonObject
                val streak = UserDashboardV2.Streak(
                    currentStreak = s?.get("current_streak")?.jsonPrimitive?.intOrNull ?: 0,
                    longestStreak = s?.get("longest_streak")?.jsonPrimitive?.intOrNull ?: 0,
                    totalActiveDays = s?.get("total_active_days")?.jsonPrimitive?.intOrNull ?: 0,
                    lastUploadDate = s?.get("last_upload_date")?.jsonPrimitive?.contentOrNull ?: "",
                    todayCount = s?.get("today_count")?.jsonPrimitive?.intOrNull ?: 0
                )
                // insights（top 3）—— 复用 [Insight] 结构解析
                val insights = o["insights"]?.jsonArray?.mapNotNull { el ->
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
                } ?: emptyList()
                UserDashboardV2(quickStats, health, activity, coverage, streak, insights)
            } else {
                logger.info("MediaService", "getUserDashboardV2 status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getUserDashboardV2 FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * V24：user-dashboard-v2 响应体。一次请求合并6维度数据。
     *
     * @param quickStats 6个核心计数（总媒体/字节数/图片/视频/相册/收藏）
     * @param health 存储健康度评分（0-100）+ 等级（A/B/C/D）
     * @param activity 用户活跃度评分 + 等级（新手/活跃/达人/专家）
     * @param coverage 媒体整理覆盖率（已打标签% + 已收藏%）
     * @param streak 上传连续天数（current/longest/total/today）
     * @param insights 智能洞察 top 3（复用 [Insight] 结构）
     */
    data class UserDashboardV2(
        val quickStats: QuickStats,
        val health: Health,
        val activity: Activity,
        val coverage: Coverage,
        val streak: Streak,
        val insights: List<Insight>
    ) {
        /** quick_stats 子对象。 */
        data class QuickStats(
            val totalMedia: Int = 0,
            val totalBytes: Long = 0L,
            val imageCount: Int = 0,
            val videoCount: Int = 0,
            val albumCount: Int = 0,
            val favoriteCount: Int = 0
        )

        /** health 子对象：存储健康度。 */
        data class Health(
            val score: Int = 0,
            val grade: String = "D",
            val duplicateRate: Double = 0.0,
            val quotaUsage: Double = 0.0,
            val duplicateCount: Int = 0,
            val coldCount: Int = 0,
            val usedBytes: Long = 0L,
            val quotaBytes: Long = 0L
        )

        /** activity 子对象：用户活跃度。 */
        data class Activity(
            val score: Int = 0,
            val level: String = "新手",
            val totalActions: Int = 0
        )

        /** coverage 子对象：媒体整理覆盖率。 */
        data class Coverage(
            val total: Int = 0,
            val taggedCount: Int = 0,
            val taggedPercent: Double = 0.0,
            val favCount: Int = 0,
            val favoritedPercent: Double = 0.0
        )

        /** streak 子对象：上传连续天数。 */
        data class Streak(
            val currentStreak: Int = 0,
            val longestStreak: Int = 0,
            val totalActiveDays: Int = 0,
            val lastUploadDate: String = "",
            val todayCount: Int = 0
        )
    }

    // ============ V8：媒体库总健康（collection-health）============

    /**
     * 媒体库综合健康报告（与后端 [handleMediaCollectionHealth] JSON 对齐）。
     *
     * 后端一次请求合并五个分析维度评分 → 综合 0-100 评分 + A/B/C/D 等级 + 汇总建议清单，
     * 避免前端并发拉取 storage-health / media-storage-efficiency / media-integrity-report /
     * media-coverage / user-activity-score 五个端点。
     *
     * @param overallScore 综合评分 0-100（五维度算术平均）
     * @param grade 综合等级 "A"/"B"/"C"/"D"
     * @param subscores 五维度子评分 0-100，键固定为
     *                 health / integrity / efficiency / coverage / activity
     * @param suggestions 汇总建议清单（各维度针对性提示）；空库时后端给一条占位建议
     * @param totalSuggestions 建议总数（与 subscores 列表长度一致，前端可据此判断是否有建议）
     */
    data class CollectionHealth(
        val overallScore: Int = 0,
        val grade: String = "D",
        val subscores: Map<String, Int> = emptyMap(),
        val suggestions: List<String> = emptyList(),
        val totalSuggestions: Int = 0
    )

    /**
     * GET /api/media/media-collection-health — 媒体库综合健康报告。
     *
     * 后端返回 `{overall_score, grade, subscores:{health,integrity,efficiency,coverage,activity},
     * suggestions:[...], total_suggestions, ...}`，前端仅消费上述五字段。
     *
     * 解析沿用运行时 [Json.parseToJsonElement]（feature-media 无 serialization 编译器插件，
     * 与 [getYearlyReview] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @return 综合健康报告；失败返回 null
     */
    suspend fun getMediaCollectionHealth(): CollectionHealth? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-collection-health")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val subs = o["subscores"]?.jsonObject?.mapValues { (_, v) ->
                    v.jsonPrimitive.intOrNull ?: 0
                } ?: emptyMap()
                CollectionHealth(
                    overallScore = o["overall_score"]?.jsonPrimitive?.intOrNull ?: 0,
                    grade = o["grade"]?.jsonPrimitive?.contentOrNull ?: "D",
                    subscores = subs,
                    suggestions = o["suggestions"]?.jsonArray?.mapNotNull { el ->
                        el.jsonPrimitive.contentOrNull
                    } ?: emptyList(),
                    totalSuggestions = o["total_suggestions"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getMediaCollectionHealth status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaCollectionHealth FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * GET /api/media/media-summary-report?year=YYYY — 年度报告（合并 8 大维度）。
     *
     * 后端一次请求聚合以下 8 个维度，供前端\"年度报告\"全屏 Dialog 精美展示：
     * 1. stats      —— 统计概览（total_media/image_count/video_count/album_count/favorite_count）
     * 2. yearly     —— 月度分布（year/summary{total_count,total_bytes}/by_month[{month,count}]/by_type/first_upload/last_upload/top_day/favorites）
     * 3. tags       —— 标签 Top5（[{tag,count}] 按 count DESC）
     * 4. health     —— 健康度（score 0-100 / grade A-D / duplicate_rate / quota_usage / age_score / suggestions[]）
     * 5. activity   —— 活跃度（active_days/total_uploads/avg_per_day/streak/max_streak/most_active_month）
     * 6. diversity  —— 多样性（type_count/tag_count/album_count/source_count/diversity_score 0-100）
     * 7. highlights —— 月度亮点（[{month,highlight,count}] 每月一项代表性事件）
     * 8. ranking    —— 上传排行（[{rank/user_or_device/count/bytes}] Top N）
     *
     * 解析沿用运行时 [Json.parseToJsonElement]（feature-media 无 serialization 编译器插件，
     * 与 [getYearlyReview]/[getMediaCollectionHealth] 同款），**逐字段宽容**——缺失/类型不符
     * 回退零值/空集合，保证 UI 在后端字段未齐或部分维度异常时永不崩。HTTP 非 200 或网络
     * 异常返回 null，调用方（[com.wgt.media.SettingsScreen] 的年度报告 Dialog）按\"加载失败\"提示。
     * 鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @param year 年度筛选（默认 2026，透传 query param）
     * @return 8 维度年度报告；失败返回 null
     */
    suspend fun getMediaSummaryReport(year: Int = 2026): SummaryReport? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-summary-report") {
                parameter("year", year)
            }
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject

                // —— 维度 1：stats 统计概览 ——
                val statsObj = o["stats"]?.jsonObject
                val stats = SummaryStats(
                    totalMedia = statsObj?.get("total_media")?.jsonPrimitive?.intOrNull ?: 0,
                    imageCount = statsObj?.get("image_count")?.jsonPrimitive?.intOrNull ?: 0,
                    videoCount = statsObj?.get("video_count")?.jsonPrimitive?.intOrNull ?: 0,
                    liveCount = statsObj?.get("live_count")?.jsonPrimitive?.intOrNull
                        ?: statsObj?.get("live_photo_count")?.jsonPrimitive?.intOrNull ?: 0,
                    albumCount = statsObj?.get("album_count")?.jsonPrimitive?.intOrNull ?: 0,
                    favoriteCount = statsObj?.get("favorite_count")?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = statsObj?.get("total_bytes")?.jsonPrimitive?.longOrNull ?: 0L
                )

                // —— 维度 2：yearly 月度分布 ——
                val yearlyObj = o["yearly"]?.jsonObject
                val monthsArr = yearlyObj?.get("by_month")?.jsonArray
                val byMonth = (1..12).map { m ->
                    val mc = monthsArr?.firstOrNull {
                        it.jsonObject["month"]?.jsonPrimitive?.intOrNull == m
                    }?.jsonObject
                    MonthCount(
                        month = m,
                        count = mc?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
                val typeObj = yearlyObj?.get("by_type")?.jsonObject
                val summaryObj = yearlyObj?.get("summary")?.jsonObject
                val topDayObj = yearlyObj?.get("top_day")?.jsonObject
                val yearly = SummaryYearly(
                    year = yearlyObj?.get("year")?.jsonPrimitive?.intOrNull ?: year,
                    totalCount = summaryObj?.get("total_count")?.jsonPrimitive?.intOrNull
                        ?: yearlyObj?.get("total_count")?.jsonPrimitive?.intOrNull ?: 0,
                    totalBytes = summaryObj?.get("total_bytes")?.jsonPrimitive?.longOrNull
                        ?: yearlyObj?.get("total_bytes")?.jsonPrimitive?.longOrNull ?: 0L,
                    byMonth = byMonth,
                    imageCount = typeObj?.get("image")?.jsonPrimitive?.intOrNull ?: 0,
                    videoCount = typeObj?.get("video")?.jsonPrimitive?.intOrNull ?: 0,
                    liveCount = typeObj?.get("live")?.jsonPrimitive?.intOrNull
                        ?: typeObj?.get("live_photo")?.jsonPrimitive?.intOrNull ?: 0,
                    firstUpload = yearlyObj?.get("first_upload")?.jsonPrimitive?.contentOrNull ?: "",
                    lastUpload = yearlyObj?.get("last_upload")?.jsonPrimitive?.contentOrNull ?: "",
                    topDay = TopDay(
                        date = topDayObj?.get("date")?.jsonPrimitive?.contentOrNull ?: "",
                        count = topDayObj?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    favorites = yearlyObj?.get("favorites")?.jsonPrimitive?.intOrNull ?: 0
                )

                // —— 维度 3：tags 标签 Top5 ——
                val tags = o["tags"]?.jsonArray?.mapNotNull { el ->
                    val t = el.jsonObject
                    val name = t["tag"]?.jsonPrimitive?.contentOrNull
                        ?: t["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SummaryTag(
                        name = name,
                        count = t["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()

                // —— 维度 4：health 健康度 ——
                val healthObj = o["health"]?.jsonObject
                val health = SummaryHealth(
                    score = healthObj?.get("score")?.jsonPrimitive?.intOrNull ?: 0,
                    grade = healthObj?.get("grade")?.jsonPrimitive?.contentOrNull ?: "",
                    duplicateRate = healthObj?.get("duplicate_rate")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    quotaUsage = healthObj?.get("quota_usage")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    ageScore = healthObj?.get("age_score")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    suggestions = healthObj?.get("suggestions")?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.contentOrNull
                    } ?: emptyList()
                )

                // —— 维度 5：activity 活跃度 ——
                val actObj = o["activity"]?.jsonObject
                val activity = SummaryActivity(
                    activeDays = actObj?.get("active_days")?.jsonPrimitive?.intOrNull ?: 0,
                    totalUploads = actObj?.get("total_uploads")?.jsonPrimitive?.intOrNull ?: 0,
                    avgPerDay = actObj?.get("avg_per_day")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    streak = actObj?.get("streak")?.jsonPrimitive?.intOrNull ?: 0,
                    maxStreak = actObj?.get("max_streak")?.jsonPrimitive?.intOrNull ?: 0,
                    mostActiveMonth = actObj?.get("most_active_month")?.jsonPrimitive?.intOrNull ?: 0
                )

                // —— 维度 6：diversity 多样性 ——
                val divObj = o["diversity"]?.jsonObject
                val diversity = SummaryDiversity(
                    typeCount = divObj?.get("type_count")?.jsonPrimitive?.intOrNull ?: 0,
                    tagCount = divObj?.get("tag_count")?.jsonPrimitive?.intOrNull ?: 0,
                    albumCount = divObj?.get("album_count")?.jsonPrimitive?.intOrNull ?: 0,
                    sourceCount = divObj?.get("source_count")?.jsonPrimitive?.intOrNull ?: 0,
                    diversityScore = divObj?.get("diversity_score")?.jsonPrimitive?.intOrNull ?: 0
                )

                // —— 维度 7：highlights 月度亮点 ——
                val highlights = o["highlights"]?.jsonArray?.mapNotNull { el ->
                    val h = el.jsonObject
                    val month = h["month"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    SummaryHighlight(
                        month = month,
                        highlight = h["highlight"]?.jsonPrimitive?.contentOrNull
                            ?: h["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = h["count"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } ?: emptyList()

                // —— 维度 8：ranking 上传排行 ——
                val ranking = o["ranking"]?.jsonArray?.mapNotNull { el ->
                    val r = el.jsonObject
                    SummaryRank(
                        rank = r["rank"]?.jsonPrimitive?.intOrNull ?: 0,
                        name = r["user"]?.jsonPrimitive?.contentOrNull
                            ?: r["device"]?.jsonPrimitive?.contentOrNull
                            ?: r["user_or_device"]?.jsonPrimitive?.contentOrNull
                            ?: r["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        count = r["count"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = r["bytes"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                } ?: emptyList()

                logger.info(
                    "MediaService",
                    "getMediaSummaryReport year=$year OK: stats=${stats.totalMedia} tags=${tags.size} " +
                        "highlights=${highlights.size} ranking=${ranking.size}"
                )
                SummaryReport(
                    year = o["year"]?.jsonPrimitive?.intOrNull ?: year,
                    stats = stats,
                    yearly = yearly,
                    tags = tags,
                    health = health,
                    activity = activity,
                    diversity = diversity,
                    highlights = highlights,
                    ranking = ranking
                )
            } else {
                logger.info("MediaService", "getMediaSummaryReport year=$year status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaSummaryReport FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** media-summary-report 年度报告（8 维度合并）。 */
    data class SummaryReport(
        val year: Int,
        val stats: SummaryStats,
        val yearly: SummaryYearly,
        val tags: List<SummaryTag>,
        val health: SummaryHealth,
        val activity: SummaryActivity,
        val diversity: SummaryDiversity,
        val highlights: List<SummaryHighlight>,
        val ranking: List<SummaryRank>
    )

    /** 维度 1：统计概览。 */
    data class SummaryStats(
        val totalMedia: Int,
        val imageCount: Int,
        val videoCount: Int,
        val liveCount: Int,
        val albumCount: Int,
        val favoriteCount: Int,
        val totalBytes: Long
    ) {
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /** 维度 2：月度分布。 */
    data class SummaryYearly(
        val year: Int,
        val totalCount: Int,
        val totalBytes: Long,
        val byMonth: List<MonthCount>,
        val imageCount: Int,
        val videoCount: Int,
        val liveCount: Int,
        val firstUpload: String,
        val lastUpload: String,
        val topDay: TopDay,
        val favorites: Int
    ) {
        val totalMB: Double get() = totalBytes.toDouble() / (1024.0 * 1024.0)
    }

    /** 维度 3：标签 Top5 条目。 */
    data class SummaryTag(val name: String, val count: Int)

    /** 维度 4：健康度。 */
    data class SummaryHealth(
        val score: Int,
        val grade: String,
        val duplicateRate: Double,
        val quotaUsage: Double,
        val ageScore: Double,
        val suggestions: List<String>
    )

    /** 维度 5：活跃度。 */
    data class SummaryActivity(
        val activeDays: Int,
        val totalUploads: Int,
        val avgPerDay: Double,
        val streak: Int,
        val maxStreak: Int,
        val mostActiveMonth: Int
    )

    /** 维度 6：多样性。 */
    data class SummaryDiversity(
        val typeCount: Int,
        val tagCount: Int,
        val albumCount: Int,
        val sourceCount: Int,
        val diversityScore: Int
    )

    /** 维度 7：月度亮点条目。 */
    data class SummaryHighlight(val month: Int, val highlight: String, val count: Int)

    /** 维度 8：上传排行条目。 */
    data class SummaryRank(val rank: Int, val name: String, val count: Int, val bytes: Long)

    /**
     * AI 洞察条目（与后端 [handleMediaAIInsights] 的 insightItem JSON 对齐）。
     *
     * 后端基于用户库的综合分析（重复/孤儿/归档/标签覆盖/活跃度等维度）产出个性化建议，
     * 每条带分类、文案、优先级与"是否可操作"标记。[actionUrl] 为可选的一键跳转链接
     * （后端 `action_url`，可空），前端展示时按 [priority] 着色，[category] 决定前置 emoji。
     *
     * @param category 建议分类（如 "duplicate"/"archive"/"tag"/"storage"/"activity"）
     * @param text 建议文案（已本地化的可读字符串）
     * @param priority 优先级（"high"/"medium"/"low"）
     * @param actionable 是否可一键操作（true 时前端可展示 action 入口）
     * @param actionUrl 可选的一键操作链接（后端 action_url，空串表示无）
     */
    data class AIInsight(
        val category: String,
        val text: String,
        val priority: String,
        val actionable: Boolean,
        val actionUrl: String
    )

    /**
     * GET /api/media/media-ai-insights — 获取个性化 AI 洞察建议清单。
     *
     * 后端综合重复/孤儿/归档/标签覆盖/活跃度等多维度分析，返回优先级排序的建议列表。
     * 响应体：`{ "insights": [{category,text,priority,actionable,action_url}], "total": N,
     * "generated_at": "<RFC3339>" }`，前端仅消费 `insights` 数组（`total`/`generated_at`
     * 由卡片按需取用，此处 getter 不返回以保持结构精简）。
     *
     * 解析沿用运行时 JSON 操作（feature-media 无 serialization 编译器插件，与
     * [getMediaRenameHistory] 同款）。HTTP 非 200 或网络异常返回 null，调用方按空态处理
     * （不展示洞察区）。鉴权头由 defaultRequest 统一注入，此处不再重复附加。
     *
     * @return AI 洞察建议列表（按后端优先级排序）；失败返回 null
     */
    suspend fun getMediaAIInsights(): List<AIInsight>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-ai-insights")
            if (response.status == HttpStatusCode.OK) {
                val obj = Json.parseToJsonElement(response.body<String>()).jsonObject
                obj["insights"]?.jsonArray?.mapNotNull { item ->
                    val o = item.jsonObject
                    AIInsight(
                        category = o["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        priority = o["priority"]?.jsonPrimitive?.contentOrNull ?: "medium",
                        actionable = o["actionable"]?.jsonPrimitive?.booleanOrNull ?: false,
                        actionUrl = o["action_url"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getMediaAIInsights status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaAIInsights FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ============ 个性化仪表盘 ============

    /**
     * 今日简报 —— 个性化仪表盘的当日活动统计。
     *
     * @param uploads 今日上传媒体数
     * @param actions 今日操作次数（含改名/打标/收藏/删除等交互）
     */
    data class TodaySummary(
        val uploads: Int,
        val actions: Int
    )

    /**
     * 个性化仪表盘 —— \"我的\"Tab 顶部欢迎卡片的底层数据。
     *
     * 后端 `GET /api/media/media-personalized-dashboard` 返回问候语、今日统计、
     * 快捷操作、推荐及每日提示。前端欢迎卡片仅消费 [greeting] / [today] / [tipOfDay]
     * 三个核心字段；[quickActions] / [recommendations] 预留供后续扩展（当前不展示）。
     *
     * @param greeting        问候语（如\"早上好，用户\"——后端按时段+用户名生成）
     * @param today           今日统计摘要（上传数 + 操作数）
     * @param tipOfDay        每日提示文字（媒体管理小贴士）
     * @param quickActions    快捷操作列表（预留，欢迎卡片暂不展示）
     * @param recommendations 推荐媒体列表（预留，欢迎卡片暂不展示）
     */
    data class PersonalizedDashboard(
        val greeting: String,
        val today: TodaySummary,
        val tipOfDay: String,
        val quickActions: List<String>,
        val recommendations: List<String>
    )

    /**
     * GET /api/media/media-personalized-dashboard — 个性化仪表盘。
     *
     * 后端聚合问候语（按当前时段 + 用户名）、今日上传/操作统计、快捷操作与推荐、
     * 每日提示，供\"我的\"Tab 顶部欢迎卡片一次请求渲染。响应 JSON：
     * `{greeting, today:{uploads,actions}, quick_actions:[...], recommendations:[...], tip_of_day}`。
     *
     * 解析沿用运行时 JSON 操作（与 [getMediaLibraryOverview] 同款）。**不附加 per-call
     * 鉴权头**——Bearer token 由 [jsonClient] 的 `defaultRequest` 统一注入（与
     * [getRelatedMedia] / [getMediaList] 同款）。各字段逐字段宽容回退（缺失→0/空），保证
     * 后端字段未就绪时不崩。HTTP 非 200 或网络异常返回 null，调用方按空态处理（不展示卡片）。
     *
     * @return 个性化仪表盘对象；失败返回 null
     */
    suspend fun getMediaPersonalizedDashboard(): PersonalizedDashboard? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-personalized-dashboard")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                val todayObj = o["today"]?.jsonObject
                PersonalizedDashboard(
                    greeting = o["greeting"]?.jsonPrimitive?.contentOrNull ?: "",
                    today = TodaySummary(
                        uploads = todayObj?.get("uploads")?.jsonPrimitive?.intOrNull ?: 0,
                        actions = todayObj?.get("actions")?.jsonPrimitive?.intOrNull ?: 0
                    ),
                    tipOfDay = o["tip_of_day"]?.jsonPrimitive?.contentOrNull ?: "",
                    quickActions = o["quick_actions"]?.jsonArray?.mapNotNull { el ->
                        el.jsonPrimitive.contentOrNull
                    } ?: emptyList(),
                    recommendations = o["recommendations"]?.jsonArray?.mapNotNull { el ->
                        el.jsonPrimitive.contentOrNull
                    } ?: emptyList()
                )
            } else {
                logger.info("MediaService", "getMediaPersonalizedDashboard status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getMediaPersonalizedDashboard FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ============ AI 清理建议（V8 §1.4） ============

    /** AI 清理建议单条项——检出的一张可清理图片及其原因。 */
    data class AiCleanupItem(
        val mediaId: String,
        val filename: String,
        val size: Long,
        val width: Int,
        val height: Int,
        val reason: String
    )

    /** AI 清理建议分类汇总——各类的条目列表 + 可回收字节数。 */
    data class AiCleanupCategory(
        val count: Int,
        val reclaimableBytes: Long,
        val items: List<AiCleanupItem>
    )

    /**
     * AI 清理建议 —— V8 §1.4。
     *
     * 后端 `GET /api/media/media-ai-cleanup-suggestions` 基于媒体元数据启发式分析
     * （低分辨率/截图/超大图/极小文件/缺拍摄时间），检出可删除候选并分组。
     * 纯元数据分析，不解码图片，轻量。
     *
     * @return 五类清理建议汇总；失败返回 null
     */
    suspend fun getAiCleanupSuggestions(): AiCleanupSuggestions? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-ai-cleanup-suggestions")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                AiCleanupSuggestions(
                    lowResolution = parseCleanupCategory(o["low_resolution"]?.jsonObject),
                    screenshots = parseCleanupCategory(o["screenshots"]?.jsonObject),
                    largeImages = parseCleanupCategory(o["large_images"]?.jsonObject),
                    tinyFiles = parseCleanupCategory(o["tiny_files"]?.jsonObject),
                    noTakenAt = parseCleanupCategory(o["no_taken_at"]?.jsonObject),
                    totalSuggestionCount = o["total_suggestion_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } else {
                logger.info("MediaService", "getAiCleanupSuggestions status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getAiCleanupSuggestions FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** AI 清理建议汇总。 */
    data class AiCleanupSuggestions(
        val lowResolution: AiCleanupCategory,
        val screenshots: AiCleanupCategory,
        val largeImages: AiCleanupCategory,
        val tinyFiles: AiCleanupCategory,
        val noTakenAt: AiCleanupCategory,
        val totalSuggestionCount: Int
    )

    /** 解析一个清理分类的 JSON 对象为 [AiCleanupCategory]。 */
    private fun parseCleanupCategory(o: JsonObject?): AiCleanupCategory {
        if (o == null) return AiCleanupCategory(0, 0L, emptyList())
        val items = o["items"]?.jsonArray?.mapNotNull { el ->
            val item = el.jsonObject
            AiCleanupItem(
                mediaId = item["media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                width = item["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = item["height"]?.jsonPrimitive?.intOrNull ?: 0,
                reason = item["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } ?: emptyList()
        return AiCleanupCategory(
            count = o["count"]?.jsonPrimitive?.intOrNull ?: 0,
            reclaimableBytes = o["reclaimable_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            items = items
        )
    }

    // ============ 离线缩略图缓存 ============

    /**
     * 离线缓存清单条目 —— 与后端 `GET /api/media/media-offline-manifest` 返回的数组元素对齐。
     *
     * 后端按\"已上传 / 常访问\"的最近媒体聚合出一个\"适合离线预览\"的清单，前端在网络可用时
     * 用 [OfflineCacheManager.prefetchOfflineThumbnails] 拉取每个 [id] 的缩略图字节并落盘，
     * 离线时直接从磁盘读图显图，无需回退到\"网络失败→空网格\"的体验。
     *
     * 字段命名沿用运行时 JSON 解析（与 [getMediaListPaged] 的 [MediaMetadata] 字段语义一致，
     * 但这里用简单 data class 而非 protobuf 生成的 [MediaMetadata]——离线清单无需
     * protobuf 编解码，只需\"id+缩略图 url\"即可，避免引入 wire 适配器）。
     *
     * 后端没有 `thumbnailUrl` 显式字段时，前端回退到 `${base}/api/media/thumbnail/{id}`，
     * 故 [thumbnailUrl] 可选（空串即走默认构造路径）。
     *
     * @property id 媒体 ID —— 缩略图文件名键与 [getThumbnail] 的入参
     * @property filename 文件名（仅用于日志，不参与缓存键）
     * @property type 媒体类型字符串（\"IMAGE\"/\"VIDEO\"/\"LIVE_PHOTO\"）；离线场景一般仅缓存 IMAGE
     * @property size 原始字节数；用于日志，不参与缓存键
     * @property createdAt 创建时间戳（后端 time.Time 的 RFC3339 字符串原样透传）；离线场景仅展示
     * @property thumbnailUrl 缩略图直链 URL（可选）。空串时 [OfflineCacheManager] 用 [id] 走默认 thumbnail 端点
     */
    data class OfflineMediaItem(
        val id: String,
        val filename: String = "",
        val type: String = "",
        val size: Long = 0L,
        val createdAt: String = "",
        val thumbnailUrl: String = ""
    )

    /**
     * GET /api/media/media-offline-manifest — 拉取离线缩略图预缓存清单。
     *
     * 后端返回 `{ "list": [{id, filename, type, size, created_at, thumbnail_url}] }`，
     * 前端解析为 [List]<[OfflineMediaItem]>。`thumbnail_url` 字段后端可不返回（空串回退），
     * 此时 [OfflineCacheManager.prefetchOfflineThumbnails] 改用默认 thumbnail 端点构造 URL。
     *
     * 失败策略：HTTP 非 200 或网络异常均返回 null，调用方按\"无清单\"处理 —— 即不预缓存，
     * 不抛异常打断上层流程（与 [getRelatedMedia] / [getSimilarMedia] 的容错风格一致）。
     * 鉴权头由 defaultRequest 统一注入，无需此处重复附加。
     *
     * @return 离线清单；失败返回 null。**清单为空不是失败**，后端可合理返回空 list（无媒体时）。
     */
    suspend fun getOfflineManifest(): List<OfflineMediaItem>? {
        return try {
            val response: HttpResponse = jsonClient.get("${backendBaseUrl()}/api/media/media-offline-manifest")
            if (response.status == HttpStatusCode.OK) {
                val o = Json.parseToJsonElement(response.body<String>()).jsonObject
                o["list"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    val id = item["id"]?.jsonPrimitive?.contentOrNull
                        ?: item["media_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    OfflineMediaItem(
                        id = id,
                        filename = item["filename"]?.jsonPrimitive?.contentOrNull ?: "",
                        type = item["type"]?.jsonPrimitive?.contentOrNull ?: "",
                        size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        createdAt = item["created_at"]?.jsonPrimitive?.contentOrNull
                            ?: item["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
                        thumbnailUrl = item["thumbnail_url"]?.jsonPrimitive?.contentOrNull
                            ?: item["thumbnailUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } else {
                logger.info("MediaService", "getOfflineManifest status=${response.status} (non-200)")
                null
            }
        } catch (e: Exception) {
            logger.error("MediaService", "getOfflineManifest FAILED: ${e::class.simpleName} ${e.message}")
            null
        }
    }
}
