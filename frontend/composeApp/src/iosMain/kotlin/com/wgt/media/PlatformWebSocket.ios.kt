package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.NSUTF8StringEncoding
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

private const val TAG_IOS = "PlatformWebSocket"

/**
 * PRD-v10 §4.1 —— iOS 端 [PlatformWebSocket] 实现（NSURLSessionWebSocketTask，iOS 13+）。
 *
 * 不引入 ktor-client-websocket，直接用 Foundation 的 NSURLSessionWebSocketTask。
 * Kotlin/Native 直接映射此 API（platform.Foundation），无需 cinterop。
 *
 * [connect] 创建 defaultSessionConfiguration 的 NSURLSession，构造 ws task、resume()，
 * 并启动续拉循环不断调 receiveMessageWithCompletionHandler 读文本帧。
 * 主动 [close] 取消 task 并置标志，抑制 onClose 回调（避免触发重连）。
 *
 * onOpen 经 NSURLSessionWebSocketDelegateProtocol 的 didOpenWithProtocol 回调触发
 * （而非 resume 后立即调用）；didCloseWithCode 触发 onClose。
 */
actual class PlatformWebSocket actual constructor(private val url: String) {

    private var session: NSURLSession? = null
    private var task: NSURLSessionWebSocketTask? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile private var closedByUs = false
    @Volatile private var stopped = false
    @Volatile private var opened = false

    // 持有回调引用，供 delegate 触发。delegate 由 NSURLSession 持有，为避免循环引用
    // 导致泄漏，close() 时把 session 置 nil（session 释放 delegate）。
    private var onOpenCb: (() -> Unit)? = null
    private var onTextCb: ((String) -> Unit)? = null
    private var onCloseCb: ((Throwable?) -> Unit)? = null

    actual fun connect(
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClose: (Throwable?) -> Unit,
    ) {
        closedByUs = false
        stopped = false
        opened = false
        onOpenCb = onOpen
        onTextCb = onText
        onCloseCb = onClose

        val delegate = WebSocketDelegate(
            onOpen = { if (!opened) { opened = true; onOpen() } },
            onClose = { err -> if (!closedByUs) onClose(err) },
        )
        val cfg = NSURLSessionConfiguration.defaultSessionConfiguration()
        session = NSURLSession.sessionWithConfiguration(cfg, delegate, null)

        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            onClose(IllegalArgumentException("bad url: $url"))
            return
        }
        task = session?.webSocketTaskWithURL(nsUrl)
        task?.resume()

        // 续拉循环：NSURLSession WebSocket 需主动调 receiveMessageWithCompletionHandler
        // 拉取每一帧；读到 error 即结束循环并回调 onClose。
        scope.launch {
            val t = task ?: return@launch
            while (!stopped) {
                var received = false
                t.receiveMessageWithCompletionHandler { data, error ->
                    when {
                        stopped -> Unit
                        error != null -> {
                            stopped = true
                            if (!closedByUs) onClose(Throwable(error.localizedDescription))
                        }
                        data != null -> {
                            received = true
                            val s = dataToString(data as NSData)
                            if (s != null) onText(s)
                        }
                    }
                }
                // completionHandler 是异步回调；轻量 yield 等下一轮拉取。
                kotlinx.coroutines.delay(100)
            }
        }
    }

    /** NSData → String（UTF-8）。WebSocket 文本帧到达时 data 为 UTF-8 NSData。 */
    private fun dataToString(data: NSData): String? {
        return String(data.toByteArray(), Charsets.UTF_8)
    }

    actual fun close() {
        closedByUs = true
        stopped = true
        task?.cancel()
        task = null
        session = null // 释放 session → 释放 delegate
        onOpenCb = null
        onTextCb = null
        onCloseCb = null
    }
}

/**
 * NSURLSessionWebSocketDelegate 实现：捕获 didOpenWithProtocol / didCloseWithCode。
 * Kotlin/Native 实现 ObjC protocol 需继承 NSObject 并声明遵循。
 *
 * 注意 didCloseWithCode 的 code 参数在 Kotlin/Native 映射为 Long（NSInteger），
 * reason 为 NSData?，无 error 参数（与 NSURLSessionTaskDelegate.didCompleteWithError 不同）。
 */
private class WebSocketDelegate(
    private val onOpen: () -> Unit,
    private val onClose: (Throwable?) -> Unit,
) : NSObject(), NSURLSessionWebSocketDelegateProtocol {

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didOpenWithProtocol: String?,
    ) {
        onOpen()
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: Long,
        reason: NSData?,
    ) {
        onClose(null) // 正常关闭码非 0 也视为断连，交由上层重连；无 error 对象可转。
    }
}
