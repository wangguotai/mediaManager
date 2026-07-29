package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathDomainMask
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile

/**
 * iOS 平台 [PersistentFileStore] 实现：落盘到 App 的 NSDocumentDirectory。
 *
 * 用 [NSFileManager] 解析 Documents 目录路径，文件名加前缀以防与既有文档相撞。
 * 写用 [NSData.writeToFile:atomically:]（atomically=true 先写临时文件再替换），
 * 中途崩溃不留半截文件。读返回 NSString（UTF-8）。
 *
 * 与 Android 实现语义对齐：仅本 App 可访问（App sandbox），无需运行时权限。
 */
@OptIn(ExperimentalForeignApi::class)
actual object PersistentFileStore {

    private const val TAG = "PersistentFileStore"
    private const val PREFIX = "mm_sync_"

    private fun path(name: String): String? {
        val urls = NSFileManager.defaultManager.URLsForDirectory(
            NSSearchPathDirectory.NSDocumentDirectory,
            NSSearchPathDomainMask.NSUserDomainMask
        )
        val first = urls.firstOrNull() ?: return null
        // 拼接路径：<Documents>/mm_sync_<name>
        return "${first.path}/$PREFIX$name"
    }

    actual fun read(name: String): String? = try {
        val p = path(name) ?: return null
        if (!NSFileManager.defaultManager.fileExistsAtPath(p)) return null
        NSData.dataWithContentsOfFile(p)?.let { nsData ->
            NSString.create(nsData, encoding = NSUTF8StringEncoding)?.toString()
        }
    } catch (e: Exception) {
        logger.error(TAG, "read '$name' failed: ${e.message}")
        null
    }

    actual fun write(name: String, content: String) {
        try {
            val p = path(name) ?: return
            val nsString = content as NSString
            val data = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return
            // atomically = true：先写临时文件再替换，保证原子落盘。
            data.writeToFile(p, true)
        } catch (e: Exception) {
            logger.error(TAG, "write '$name' failed: ${e.message}")
        }
    }
}
