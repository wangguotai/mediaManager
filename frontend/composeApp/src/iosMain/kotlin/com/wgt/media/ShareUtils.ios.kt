package com.wgt.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIWindowScene
import platform.UIKit.presentationContext

/**
 * iOS 平台分享实现 —— 用 UIActivityViewController 弹出系统分享面板。
 *
 * 字节流先写入临时文件，再生成 NSURL 传给 UIActivityViewController。
 * 用 connectedScenes 获取当前 window scene 的 rootViewController 做 presenter，
 * 兼容 iOS 13+ 场景 API（keyWindow 已废弃）。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String) {
    // 写入临时文件
    val tempDir = NSTemporaryDirectory()
    val filePath = "$tempDir$filename"

    // ByteArray → NSData → 写文件
    mediaBytes.usePinned { pinned ->
        val nsData = NSData.create(
            pinned.addressOf(0),
            mediaBytes.size.toULong()
        )
        nsData?.writeToFile(filePath, atomically = true)
    }

    val fileUrl = NSURL.fileURLWithPath(filePath)

    val activityViewController = UIActivityViewController(
        activityItems = listOf(fileUrl),
        applicationActivities = null
    )

    // 兼容 iOS 13+ 场景 API：从 connectedScenes 取 UIWindowScene 的 rootViewController
    val rootVc = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull()
        ?.windows
        ?.firstOrNull()
        ?.rootViewController

    rootVc?.presentViewController(
        activityViewController,
        animated = true,
        completion = null
    )
}
