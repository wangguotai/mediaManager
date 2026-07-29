package com.wgt.media

/**
 * 跨平台媒体分享工具。
 *
 * commonMain 仅声明 expect，各平台 actual 负责调用系统分享面板：
 * - Android: Intent.ACTION_SEND / ACTION_SEND_MULTIPLE
 * - iOS: UIActivityViewController
 */

/**
 * 单个分享项：字节流 + 文件名 + MIME 类型。
 */
data class ShareMediaItem(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String
)

/**
 * 分享单个媒体文件。
 */
expect fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String)

/**
 * 批量分享多个媒体文件（一次系统分享面板处理所有文件）。
 *
 * - Android: ACTION_SEND_MULTIPLE + ArrayList<Uri>
 * - iOS: UIActivityViewController with multiple NSURLs
 */
expect fun shareMediaBatch(items: List<ShareMediaItem>)
