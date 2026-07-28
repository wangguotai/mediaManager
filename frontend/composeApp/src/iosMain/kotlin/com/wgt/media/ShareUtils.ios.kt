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
 * iOS 平台分享实现 —— 用 UIActivityViewController 弹出系统分享面板。
 *
 * 字节流通过 POSIX fwrite 写入临时文件（避免 NSData interop 签名差异），
 * 再生成 NSURL 传给 UIActivityViewController。
 * 用 connectedScenes 获取当前 window scene 的 rootViewController 做 presenter，
 * 兼容 iOS 13+ 场景 API（keyWindow 已废弃）。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String) {
    // 写入临时文件（POSIX fwrite，绕过 NSData interop）
    val tempDir = NSTemporaryDirectory()
    val filePath = "$tempDir$filename"

    val fp = fopen(filePath, "wb")
    if (fp != null) {
        mediaBytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1UL, mediaBytes.size.toULong(), fp)
        }
        fclose(fp)
    }

    val fileUrl = NSURL.fileURLWithPath(filePath)

    val activityViewController = UIActivityViewController(
        activityItems = listOf(fileUrl),
        applicationActivities = null
    )

    // 兼容 iOS 13+ 场景 API：从 connectedScenes 取 UIWindowScene 的 key window 的 rootViewController
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
