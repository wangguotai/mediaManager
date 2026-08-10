package com.wgt.media

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * PRD-v10 §4.1 —— Android 端 [PlatformWebSocket] 实现（OkHttp）。
 *
 * composeApp androidMain 已依赖 okhttp 4.9.2（见 composeApp/build.gradle.kts），
 * 故直接用 okhttp3.WebSocket + WebSocketListener，无需引入 ktor-client-websocket。
 *
 * [connect] 在内部创建 OkHttp client（独立超时，避免复用主 client 的长超时），
 * 发起 ws 握手；onOpen/onText/onClose 回调在 OkHttp 的调度线程触发，
 * 调用方（[SyncWebSocket]）据此驱动重连与业务回调。
 */
actual class PlatformWebSocket actual constructor(private val url: String) {

    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    @Volatile private var closedByUs = false

    actual fun connect(
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClose: (Throwable?) -> Unit,
    ) {
        closedByUs = false
        val c = OkHttpClient.Builder()
            // ws 握手本身无 HTTP body；ping 帧间隔由服务端 25s 维持，这里给宽松读超时。
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
        client = c
        val req = Request.Builder().url(url).build()
        socket = c.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 服务端发起关闭：让 onClosed 统一回调。
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) onClose(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) onClose(t)
            }
        })
    }

    actual fun close() {
        closedByUs = true // 抑制 onClose 回调（主动关闭不应触发重连）。
        socket?.close(1000, "client closing")
        socket = null
        client = null
    }
}
