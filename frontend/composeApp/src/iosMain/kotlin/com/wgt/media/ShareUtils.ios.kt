package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * iOS 平台分享实现。
 *
 * 单个/批量均通过 UIActivityViewController 实现（批量传入多个 NSURL）。
 * 字节流通过 POSIX fwrite 写入临时文件，再生成 NSURL 传给分享面板。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String) {
    shareMediaBatch(listOf(ShareMediaItem(mediaBytes, filename, mimeType)))
}

/**
 * iOS 批量分享：UIActivityViewController 传入多个 NSURL 文件 URL。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun shareMediaBatch(items: List<ShareMediaItem>) {
    if (items.isEmpty()) return

    val tempDir = NSTemporaryDirectory()
    val urls = items.map { item ->
        val filePath = "$tempDir${item.filename}"
        val fp = fopen(filePath, "wb")
        if (fp != null) {
            item.bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1UL, item.bytes.size.toULong(), fp)
            }
            fclose(fp)
        }
        NSURL.fileURLWithPath(filePath)
    }

    val activityViewController = UIActivityViewController(
        activityItems = urls,
        applicationActivities = null
    )

    val window = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull()
        ?.windows
        ?.firstOrNull() as? UIWindow

    window?.rootViewController?.presentViewController(
        activityViewController,
        animated = true,
        completion = null
    )
}
