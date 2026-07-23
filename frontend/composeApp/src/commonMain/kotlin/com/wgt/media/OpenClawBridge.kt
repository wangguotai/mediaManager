package com.wgt.media

import com.wgt.feature.media.MediaService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

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
}
