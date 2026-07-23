package com.wgt.media

import com.wgt.feature.media.MediaService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenClaw 桥梁 — 通过 media-manager 后端的 /api/openclaw/command 端点
 * 将命令转发到本地 OpenClaw gateway，前端不直接访问 OpenClaw。
 *
 * 后端会校验 path（必须以 / 开头、不含 ..）、限制 method 白名单，
 * 并把 upstream 响应包成 { status, content_type, body?, raw_body?, upstream }。
 */
object OpenClawBridge {

    /**
     * 发送命令到 OpenClaw。
     *
     * @param path OpenClaw gateway 上的路径，必须以 '/' 开头，例如 "/api/foo/bar"
     * @param method HTTP method，默认 POST；后端白名单为 GET/POST/PUT/PATCH/DELETE
     * @param body 请求体 JSON；可选
     * @return upstream 响应（JsonObject），包含 status / content_type / body / raw_body / upstream；
     *         若请求失败或后端不可达，返回 null
     */
    suspend fun send(
        path: String,
        method: String = "POST",
        body: JsonObject? = null
    ): JsonObject? = MediaService.sendOpenClawCommand(path, method, body)

    /**
     * 便捷重载：用 builder 形式构造 body。
     */
    suspend fun send(
        path: String,
        method: String = "POST",
        bodyBuilder: JsonObjectBuilder.() -> Unit
    ): JsonObject? = send(path, method, buildJsonObject(bodyBuilder))

    /**
     * 发送一条命令/消息到 OpenClaw。
     *
     * 面向"消息文本"场景的语义化便捷方法：把 [message] 包成
     * `{ "message": message }` 的 JSON body，经后端 `/api/openclaw/command`
     * 转发到 OpenClaw gateway。等价于 `send(path, "POST", body)`。
     *
     * @param message 命令/消息文本，作为 upstream body 的 `message` 字段
     * @param path OpenClaw gateway 上的路径，默认 `/api/v1/chat`（见 openclaw-bridge-design.md §3.2 示例）；
     *             必须以 '/' 开头
     * @return upstream 响应（JsonObject）；失败或后端不可达返回 null
     */
    suspend fun sendCommand(
        message: String,
        path: String = DEFAULT_COMMAND_PATH
    ): JsonObject? = send(
        path = path,
        method = "POST",
        body = buildJsonObject { put("message", message) }
    )

    private const val DEFAULT_COMMAND_PATH = "/api/v1/chat"
}
