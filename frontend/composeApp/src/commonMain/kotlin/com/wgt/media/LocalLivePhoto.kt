package com.wgt.media

import androidx.compose.runtime.Composable

/**
 * 提取本地 Live Photo 的嵌入视频到临时文件，返回 file:// URI。
 *
 * Android: 从 JPEG Motion Photo 中解析 XMP offset，提取嵌入的 MP4 数据到缓存文件。
 * iOS: 返回 null（iOS Live Photo 走 PHAsset 原生 API，不由此路径处理）。
 *
 * @param mediaId 本地 MediaStore ID
 * @return file:// 临时文件 URI，null 表示不是本地 Live Photo 或提取失败
 */
internal expect suspend fun extractLocalLivePhotoVideo(mediaId: String): String?
