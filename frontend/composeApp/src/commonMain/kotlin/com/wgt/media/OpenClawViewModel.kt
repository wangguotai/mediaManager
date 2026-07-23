package com.wgt.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wgt.platform.architecture.dispatchers.dispatchers
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "OpenClawViewModel"

/**
 * OpenClaw 桥梁视图模型。
 *
 * 负责把用户在输入框里填的 path + message 通过 [OpenClawBridge.sendCommand] 发到后端
 * `/api/openclaw/command`，并把 upstream 响应（status / content_type / body / raw_body /
 * upstream）整理成可展示的 [OpenClawResult]。
 *
 * 响应结构见 plan/openclaw-bridge-design.md §3.3：后端统一以 HTTP 200 返回，真正的上游
 * 状态码放在 JSON `status` 字段里，故这里以 `status` 是否解析到为准判断"有响应"。
 */
class OpenClawViewModel {
    private val scope = CoroutineScope(dispatchers.main)

    // 输入：OpenClaw gateway 上的路径（必须以 '/' 开头）。默认用桥梁文档中的 /api/v1/chat，
    // 实测该路径在当前 OpenClaw Control gateway 上为 404 —— 保留默认值仅作示例，
    // 用户可改成 /healthz 等真实可用路径（见对话框内的提示与快捷按钮）。
    var path by mutableStateOf(OpenClawBridge.DEFAULT_COMMAND_PATH)
        private set

    // 输入：命令/消息文本，作为 upstream body 的 message 字段
    var message by mutableStateOf("")
        private set

    // 是否正在发送（等待后端 + upstream 响应）
    var isSending by mutableStateOf(false)
        private set

    // 最近一次响应的展示结果；null 表示尚未发送过
    var result by mutableStateOf<OpenClawResult?>(null)
        private set

    fun onPathChange(value: String) {
        path = value
    }

    fun onMessageChange(value: String) {
        message = value
    }

    fun clearResult() {
        result = null
    }

    /**
     * 发送命令。path 为空或非 '/' 开头时不发送（后端也会以 400 拒绝，这里前置拦截更友好）。
     */
    fun send() {
        val p = path.trim()
        if (isSending || p.isEmpty() || !p.startsWith("/")) {
            result = OpenClawResult(
                status = 0,
                contentType = "",
                body = "",
                upstream = "",
                ok = false,
                error = "path 必须以 '/' 开头，例如 /healthz"
            )
            return
        }
        isSending = true
        result = null
        scope.launch {
            try {
                val resp: JsonObject? = OpenClawBridge.sendCommand(message = message.trim(), path = p)
                result = resp?.toResult() ?: OpenClawResult(
                    status = 0,
                    contentType = "",
                    body = "",
                    upstream = "",
                    ok = false,
                    error = "请求失败：后端不可达或桥梁返回空"
                )
            } catch (e: Exception) {
                logger.error(TAG, "send failed: ${e::class.simpleName} ${e.message}")
                result = OpenClawResult(
                    status = 0,
                    contentType = "",
                    body = "",
                    upstream = "",
                    ok = false,
                    error = "请求异常: ${e.message}"
                )
            } finally {
                isSending = false
            }
        }
    }

    private fun JsonObject.toResult(): OpenClawResult {
        val status = this["status"]?.jsonPrimitive?.intOrNull ?: 0
        val contentType = this["content_type"]?.jsonPrimitive?.contentOrNull ?: ""
        val upstream = this["upstream"]?.jsonPrimitive?.contentOrNull ?: ""
        // body 是结构化 JSON（上游为 application/json 时透传），raw_body 是字符串（非 JSON 时透传）。
        val bodyJson = this["body"]
        val rawBody = this["raw_body"]?.jsonPrimitive?.contentOrNull
        val body = when {
            bodyJson != null -> bodyJson.toString()
            rawBody != null -> rawBody
            else -> ""
        }
        return OpenClawResult(
            status = status,
            contentType = contentType,
            body = body,
            upstream = upstream,
            // 后端桥梁自身以 200 包裹；只要解析到了 status 认为通信成功。
            // 是否是上游业务错误由 status 数值体现（如 404），UI 会单独提示。
            ok = true,
            error = null
        )
    }
}

/**
 * OpenClaw 响应的可展示结果。
 *
 * @param status 上游 HTTP 状态码（0 表示未拿到有效响应）
 * @param contentType 上游 Content-Type
 * @param body body(JSON 字符串) 或 raw_body(字符串)，二选一展示
 * @param upstream 实际访问的上游 URL
 * @param ok 通信是否成功（后端桥梁是否返回了有效结构）；上游业务错误（404 等）仍算 ok=true
 * @param error 通信失败时的错误描述
 */
data class OpenClawResult(
    val status: Int,
    val contentType: String,
    val body: String,
    val upstream: String,
    val ok: Boolean,
    val error: String?
) {
    /** 上游状态码是否表示成功（2xx） */
    val isUpstreamSuccess: Boolean get() = ok && status in 200..299
}
