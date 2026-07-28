package com.wgt.media

/**
 * 跨平台媒体分享工具。
 *
 * commonMain 仅声明 expect，各平台 actual 负责调用系统分享面板：
 * - Android: Intent.ACTION_SEND + startActivity
 * - iOS: UIActivityViewController
 *
 * @param mediaBytes 媒体文件字节流（图片 / 视频等）
 * @param filename 分享时的建议文件名（如 "photo.jpg"）
 * @param mimeType MIME 类型（如 "image/jpeg"、"video/mp4"）
 */
expect fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String)
