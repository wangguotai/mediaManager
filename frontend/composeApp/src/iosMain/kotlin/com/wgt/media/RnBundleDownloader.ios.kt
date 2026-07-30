package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSFileManager
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * iOS 平台 RN bundle 缓存实现（V7 §3.2）。
 *
 * 缓存目录: NSTemporaryDirectory()/rn-bundles/<name>.bundle
 */

actual fun readCachedBundlePath(bundleName: String): String? {
    val path = "${NSTemporaryDirectory()}rn-bundles/$bundleName.bundle"
    return if (NSFileManager.defaultManager.fileExistsAtPath(path)) path else null
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeBundleToCache(bundleName: String, data: ByteArray): String {
    val dir = "${NSTemporaryDirectory()}rn-bundles"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val path = "$dir/$bundleName.bundle"
    // POSIX fwrite（与 PhotoGalleryService.ios.kt saveMediaToGallery 同款）
    val fp = fopen(path, "wb") ?: throw RuntimeException("fopen failed: $path")
    try {
        if (data.isNotEmpty()) {
            data.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.toULong(), data.size.toULong(), fp)
            }
        }
    } finally {
        fclose(fp)
    }
    return path
}
