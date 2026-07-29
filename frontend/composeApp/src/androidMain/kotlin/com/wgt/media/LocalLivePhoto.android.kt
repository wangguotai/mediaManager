package com.wgt.media

import com.wgt.feature.gallery.getLocalLivePhotoVideoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android 实现：通过 PhotoGalleryService 提取 Motion Photo 嵌入的视频数据，
 * 写入应用缓存目录临时文件，返回 file:// URI 供 VideoView 播放。
 *
 * 文件名按 mediaId 固定(每次播放覆盖同一文件)，避免反复播放累积垃圾临时文件。
 */
internal actual suspend fun extractLocalLivePhotoVideo(mediaId: String): String? = withContext(Dispatchers.IO) {
    try {
        val videoData = getLocalLivePhotoVideoData(mediaId)
        if (videoData == null || videoData.isEmpty()) return@withContext null

        val cacheDir = File(System.getProperty("java.io.tmpdir") ?: ".")
        cacheDir.mkdirs()
        val tempFile = File(cacheDir, "live_photo_$mediaId.mp4")
        tempFile.writeBytes(videoData)
        "file://${tempFile.absolutePath}"
    } catch (e: Exception) {
        null
    }
}
