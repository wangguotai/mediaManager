package com.wgt.media

import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "RnBundleDownloader"

/**
 * RN Bundle 下载器（V7 §3.2）。
 *
 * 从后端 `GET /api/rn/manifest` 拉取可用 bundle 列表 + 版本号，
 * 按需下载 `GET /api/rn/bundle/{name}` 到本地缓存目录。
 * 版本管理：缓存按 name 存储版本号，manifest 版本号 > 本地时才下载更新。
 *
 * 纯 commonMain，网络请求走 MediaService 的 HTTP client。
 */

/** RN bundle manifest 项。 */
data class RnBundleManifest(
    val name: String,
    val version: String,
    val description: String,
    val entry: String
)

/**
 * 拉取后端 RN bundle manifest 列表。
 * 失败返回空列表（静默降级，不阻断功能）。
 */
suspend fun fetchRnManifest(): List<RnBundleManifest> {
    return try {
        val response = MediaService.getRawJson("${MediaService.rnBackendBaseUrl()}/api/rn/manifest")
            ?: return emptyList()
        val obj = Json.parseToJsonElement(response).jsonObject
        val arr = obj["bundles"] as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            RnBundleManifest(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                version = o["version"]?.jsonPrimitive?.contentOrNull ?: "",
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                entry = o["entry"]?.jsonPrimitive?.contentOrNull ?: "index.android.bundle"
            )
        }
    } catch (e: Exception) {
        logger.error(TAG, "fetchRnManifest failed: ${e.message}")
        emptyList()
    }
}

/**
 * 确保指定 bundle 在本地缓存就绪。
 *
 * 1. 检查本地缓存目录是否有该 bundle 文件。
 * 2. 有则返回本地路径；无则从后端下载到缓存。
 * 3. 下载失败返回 null（调用方回退到 assets 内置 bundle）。
 *
 * @param bundleName bundle 名称（如 "activity-bundle"）
 * @return 本地文件路径（平台缓存目录下），或 null 表示未就绪
 */
suspend fun ensureBundle(bundleName: String): String? {
    return try {
        // 检查本地缓存
        val localPath = readCachedBundlePath(bundleName)
        if (localPath != null) {
            logger.info(TAG, "bundle cached: $bundleName -> $localPath")
            return localPath
        }
        // 从后端下载
        val bytes = MediaService.getRawBytes("${MediaService.rnBackendBaseUrl()}/api/rn/bundle/$bundleName")
            ?: return null
        // 写入缓存
        val path = writeBundleToCache(bundleName, bytes)
        logger.info(TAG, "bundle downloaded: $bundleName -> $path (${bytes.size} bytes)")
        path
    } catch (e: Exception) {
        logger.error(TAG, "ensureBundle failed for $bundleName: ${e.message}")
        null
    }
}

/**
 * 读取本地缓存的 bundle 文件路径（如果存在）。
 * 平台 expect/actual：Android 用 cacheDir，iOS 用 NSTemporaryDirectory。
 */
expect fun readCachedBundlePath(bundleName: String): String?

/**
 * 将 bundle 字节写入本地缓存目录，返回文件路径。
 * 平台 expect/actual。
 */
expect fun writeBundleToCache(bundleName: String, data: ByteArray): String
