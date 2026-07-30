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
    val entry: String,
    val size: Long = 0,
    val sha256: String = ""
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
                entry = o["entry"]?.jsonPrimitive?.contentOrNull ?: "index.android.bundle",
                size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                sha256 = o["sha256"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    } catch (e: Exception) {
        logger.error(TAG, "fetchRnManifest failed: ${e.message}")
        emptyList()
    }
}

/**
 * 确保指定 bundle 在本地缓存就绪，带版本号比对（V7 §3.2 热更新基础）。
 *
 * 1. 拉取后端 manifest 获取最新版本号。
 * 2. 比对本地缓存的版本号——相同则复用缓存，不同则重新下载。
 * 3. 下载成功后更新本地版本号记录。
 * 4. 任何步骤失败返回 null（调用方回退到 assets 内置 bundle）。
 *
 * @param bundleName bundle 名称（如 "activity-bundle"）
 * @return 包含本地路径和版本的 Result，或 null 表示未就绪
 */
suspend fun ensureBundleWithVersion(bundleName: String): BundleResult? {
    return try {
        // 拉取 manifest 获取版本
        val manifests = fetchRnManifest()
        val manifest = manifests.find { it.name == bundleName }
            ?: return null

        // 检查本地缓存版本
        val localVersion = readCachedBundleVersion(bundleName)
        val localPath = readCachedBundlePath(bundleName)

        if (localPath != null && localVersion == manifest.version) {
            // 版本一致，复用缓存
            logger.info(TAG, "bundle cached (version match): $bundleName v$localVersion -> $localPath")
            return BundleResult(localPath, manifest.version)
        }

        // 版本不同或无缓存，从后端下载
        val bytes = MediaService.getRawBytes("${MediaService.rnBackendBaseUrl()}/api/rn/bundle/$bundleName")
            ?: return null

        // V7：SHA256 完整性校验
        if (manifest.sha256.isNotEmpty()) {
            val actualSha = sha256Hex(bytes)
            if (actualSha != manifest.sha256) {
                logger.error(TAG, "bundle SHA256 mismatch for $bundleName: expected=${manifest.sha256}, actual=$actualSha")
                return null
            }
            logger.info(TAG, "bundle SHA256 verified: $bundleName ($actualSha)")
        }

        val path = writeBundleToCache(bundleName, bytes)
        writeCachedBundleVersion(bundleName, manifest.version)
        logger.info(TAG, "bundle downloaded: $bundleName v${manifest.version} -> $path (${bytes.size} bytes)")
        BundleResult(path, manifest.version)
    } catch (e: Exception) {
        logger.error(TAG, "ensureBundleWithVersion failed for $bundleName: ${e.message}")
        null
    }
}

/** bundle 下载结果。 */
data class BundleResult(val path: String, val version: String)

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

/**
 * 读取本地缓存的 bundle 版本号（V7 §3.2 热更新版本比对）。
 * 平台 expect/actual：Android 用 SharedPreferences，iOS 用 NSUserDefaults。
 */
expect fun readCachedBundleVersion(bundleName: String): String?

/**
 * 写入 bundle 版本号到本地缓存。
 * 平台 expect/actual。
 */
expect fun writeCachedBundleVersion(bundleName: String, version: String)
