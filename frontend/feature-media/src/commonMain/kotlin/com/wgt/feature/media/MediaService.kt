package com.wgt.feature.media

import media.MediaMetadata
import media.MediaType
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * MOCK 媒体服务
 */
object MediaService {
    
    /**
     * 获取媒体列表
     */
    suspend fun getMediaList(page: Int = 1, pageSize: Int = 20): List<MediaMetadata> {
        // 模拟网络延迟
        delay(500)
        
        return when (page) {
            1 -> listOf(
                MediaMetadata(
                    id = "1",
                    filename = "photo1.jpg",
                    type = MediaType.IMAGE,
                    size = 2048576,
                    mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 86400000,
                    updated_at = Clock.System.now().toEpochMilliseconds(),
                    is_live_photo = false
                ),
                MediaMetadata(
                    id = "2",
                    filename = "live_photo1.jpg",
                    type = MediaType.LIVE_PHOTO,
                    size = 5048576,
                    mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 172800000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 172800000,
                    is_live_photo = true,
                    live_photo_video_id = "video1"
                ),
                MediaMetadata(
                    id = "3",
                    filename = "photo2.png",
                    type = MediaType.IMAGE,
                    size = 1048576,
                    mime_type = "image/png",
                    created_at = Clock.System.now().toEpochMilliseconds() - 259200000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 259200000,
                    is_live_photo = false
                ),
                MediaMetadata(
                    id = "4",
                    filename = "live_photo2.jpg",
                    type = MediaType.LIVE_PHOTO,
                    size = 6048576,
                    mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 345600000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 345600000,
                    is_live_photo = true,
                    live_photo_video_id = "video2"
                ),
                MediaMetadata(
                    id = "5",
                    filename = "photo3.jpg",
                    type = MediaType.IMAGE,
                    size = 3048576,
                    mime_type = "image/jpeg",
                    created_at = Clock.System.now().toEpochMilliseconds() - 432000000,
                    updated_at = Clock.System.now().toEpochMilliseconds() - 432000000,
                    is_live_photo = false
                )
            )
            else -> emptyList()
        }
    }
    
    /**
     * 批量删除媒体
     */
    suspend fun deleteMedia(mediaIds: List<String>): Boolean {
        delay(300)
        println("删除媒体: $mediaIds")
        return true
    }
    
    /**
     * 上传媒体
     */
    suspend fun uploadMedia(fileData: ByteArray, filename: String, isLivePhoto: Boolean = false): Boolean {
        delay(1000)
        println("上传媒体: $filename, Live图: $isLivePhoto, 大小: ${fileData.size} bytes")
        return true
    }
    
    /**
     * 获取Live图视频URL
     */
    suspend fun getLivePhotoVideoUrl(mediaId: String): String? {
        delay(200)
        return if (mediaId == "2" || mediaId == "4") {
            "https://example.com/video/$mediaId.mp4"
        } else {
            null
        }
    }
}
