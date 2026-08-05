package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes
import platform.Foundation.writeToFile

/**
 * iOS 平台 [OfflineCacheManager] 实现 —— App 沙箱 [NSTemporaryDirectory] 作缓存目录，
 * NSFileManager 处理文件存在性 / 字节写入。
 *
 * 网络探测 [platformNetworkAvailable] 简单返回 true：
 * iOS 推荐用 `NWPathMonitor` 但需异步回调与 `suspendCancellableCoroutine` 包装，
 * 与 [OfflineCacheManager.isOfflineMode] 的同步语义冲突。当前离线场景里
 * [OfflineCacheManager.getCachedThumbnailPath] 命中即显图、未命中即走原始 HTTP
 * （失败回退空态），网络误判"在线"只会让缓存未命中时多发一次必失败的请求，
 * 不破坏功能。后续如需精确可改造为带状态缓存的 NWPathMonitor 监听。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun getOfflineCacheDir(): String {
    // NSTemporaryDirectory() 形如 .../tmp/；末尾已带 /，与 common 层 trimEnd('/') 对齐。
    val base = NSTemporaryDirectory().trimEnd('/')
    val dir = "$base/offline_thumbs"
    // 不存在则创建（中间目录一并创建）；isDirectory=true 防与同名文件冲突。
    val url = NSURL.fileURLWithPath(dir)
    try {
        if (!NSFileManager.defaultManager().fileExistsAtPath(dir)) {
            NSFileManager.defaultManager()
                .createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        }
    } catch (_: Exception) {
        // 创建失败不致命：[platformWriteBytes] 仍会尝试写，失败再被 common 层 downloadOne 兜底。
    }
    return dir
}

@OptIn(ExperimentalForeignApi::class)
actual fun platformFileExists(path: String): Boolean {
    return try {
        NSFileManager.defaultManager().fileExistsAtPath(path)
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun platformWriteBytes(path: String, bytes: ByteArray) {
    try {
        // ByteArray → NSData 必须借 usePinned 取裸指针交给 dataWithBytes（NSData 拷贝一份持有）。
        val nsData = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        // atomically=true：先写临时文件再 rename，避免中途崩溃留下半截文件，
        // 与 PersistentFileStore.ios.kt 的写入策略一致。
        nsData.writeToFile(path, atomically = true)
    } catch (_: Exception) {
        // 静默：与 android actual 一致，由上层 downloadOne 的 try/catch 兜底日志。
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun platformReadBytes(path: String): ByteArray? {
    val url = NSURL.fileURLWithPath(path)
    val nsData: NSData? = NSData.dataWithContentsOfURL(url)
    if (nsData == null) return null
    val length = nsData.length.toInt()
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        nsData.getBytes(pinned.addressOf(0), length = length.toULong())
    }
    return bytes
}

/**
 * 简化策略 —— 直接返回 true。详见文件头注释说明 NWPathMonitor 改造空间。
 */
actual fun platformNetworkAvailable(): Boolean = true
