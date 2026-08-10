package com.wgt.media

import com.wgt.feature.media.syncWsUrl
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * PRD-v10 §4.1 —— WebSocket 实时同步通道（commonMain 期望声明）。
 *
 * 包装各端原生 WebSocket（Android: OkHttp / iOS: NSURLSessionWebSocketTask），
 * 暴露统一的 connect / disconnect / onEvent 回调。因 [feature-media] 与
 * [composeApp] 均未声明 ktor-client-websocket 依赖，这里用 expect/actual 而非 ktor。
 *
 * 实际连接由 [SyncWebSocket]（同包 commonMain 协调器）驱动，它负责握手、
 * 指数退避重连与把 media_changed 帧回调给 MediaViewModel。
 */
expect class PlatformWebSocket(url: String) {
    /** 打开连接。onOpen 在握手成功后回调；onText 收到文本帧时回调；onClose 连接断开时回调。 */
    fun connect(onOpen: () -> Unit, onText: (String) -> Unit, onClose: (Throwable?) -> Unit)
    /** 主动关闭（不再触发 onClose 回调）。幂等。 */
    fun close()
}

private const val TAG = "SyncWebSocket"

/**
 * PRD-v10 §4.1 —— WebSocket 实时同步推送客户端（commonMain 协调器）。
 *
 * 职责：
 *  1. 用 [token] 构造 /api/sync/ws?token= 握手 URL（经 [syncWsUrl]）。
 *  2. [connect] 后启动重连循环：握手成功 → 等待断开 → 指数退避重连
 *     （1s → 2s → 4s → 8s → 16s，封顶 16s）。
 *  3. 收到 {type:"media_changed"} 帧时回调 [onMediaChanged]；收到 ping/其他帧忽略。
 *  4. [disconnect] 彻底停止重连循环并关闭当前连接。
 *
 * 由 MediaViewModel 在登录态就绪后创建并 [connect]，登出/离开云端 Tab 时 [disconnect]。
 * 重连在内部协程进行，不阻塞调用方；[onMediaChanged] 在平台网络线程回调，
 * 调用方需自行切回主线程更新 Compose state。
 */
class SyncWebSocket(
    token: String,
    private val onMediaChanged: (event: String) -> Unit,
) {
    // 握手 URL：http→ws / https→wss，token 作 query 透传（浏览器原生 WS 无法带 Auth 头）。
    private val url: String = syncWsUrl(token)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var loopJob: Job? = null
    @Volatile private var running = false
    @Volatile private var currentConn: PlatformWebSocket? = null

    /**
     * 启动连接 + 自动重连循环。幂等：重复调用不叠加循环。
     * 内部在 Default 协程上跑重连退避；[onMediaChanged] 回调在平台网络线程。
     */
    fun connect() {
        if (running) return
        running = true
        loopJob = scope.launch {
            var backoffMs = 1_000L
            val maxBackoffMs = 16_000L
            while (isActive && running) {
                var connected = false
                var closeErr: Throwable? = null
                val conn = PlatformWebSocket(url)
                currentConn = conn
                try {
                    conn.connect(
                        onOpen = {
                            // 握手成功：重置退避到初始值，便于下次断开后快速重连。
                            backoffMs = 1_000L
                            logger.info(TAG, "WS connected: $url")
                        },
                        onText = { text -> handleFrame(text) },
                        onClose = { err ->
                            closeErr = err
                            logger.info(TAG, "WS closed: ${err?.message ?: "clean"}")
                        },
                    )
                    // connect 返回即连接已结束（正常或异常）。标记以便退避重连。
                    connected = true
                } catch (e: Throwable) {
                    closeErr = e
                    logger.warning(TAG, "WS connect failed: ${e.message}")
                }
                currentConn = null

                if (!running) break // 主动 disconnect 期间，不重连。
                if (connected || closeErr != null) {
                    // 指数退避：1→2→4→8→16（封顶），避免服务端不可用时疯狂重连。
                    logger.info(TAG, "WS reconnect in ${backoffMs}ms (backoff)")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    /** 彻底断开并停止重连循环。幂等。 */
    fun disconnect() {
        running = false
        loopJob?.cancel()
        loopJob = null
        currentConn?.close()
        currentConn = null
        logger.info(TAG, "WS disconnected")
    }

    /** 解析服务端推送帧：media_changed → 回调 event；ping/其他 → 忽略。 */
    private fun handleFrame(text: String) {
        // 轻量解析，不引入 JSON 库依赖（帧结构简单）。
        // 帧：{"type":"media_changed","event":"upload","cursor":123,"at":123}
        if (text.contains("\"type\":\"media_changed\"")) {
            val event = extractStringField(text, "event") ?: "unknown"
            onMediaChanged(event)
        }
        // ping 帧（{"type":"ping"}）忽略 —— 仅保活，无业务语义。
    }

    /** 从 JSON 文本抽取某字符串字段的值（正则，避免 JSON 库）。失败返回 null。 */
    private fun extractStringField(json: String, field: String): String? {
        // 匹配 "field":"value"
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.getOrNull(1)
    }
}
