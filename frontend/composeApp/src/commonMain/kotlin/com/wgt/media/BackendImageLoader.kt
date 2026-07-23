package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger

private const val TAG = "BackendImageLoader"

/**
 * 从后端 REST 端点加载并解码图片 —— 供"网盘图片" / "已上传" Tab 使用。
 *
 * 与 [loadThumbnail] / [loadFullImage]（走平台相册 MediaStore/PHAsset）互补：
 * 后端图片的 id 是后端定义的字符串（网盘图片为去扩展名的文件名，如 `test-cloud-image`），
 * 不是本地相册的 long id，因此必须通过 HTTP 端点拿字节流再解码。
 *
 * 取字节流在 commonMain 完成；解码交给平台实现 [decodeImageBitmap]（Android 用
 * BitmapFactory，iOS 用 skia），因为 skia 在 commonMain 不直接可见。
 */
object BackendImageLoader {

    /**
     * 加载缩略图。走 `GET /api/media/thumbnail/{id}?size=medium`。
     *
     * @param mediaId 后端媒体 id（网盘图片为去扩展名的文件名）
     * @return 解码后的 [ImageBitmap]；网络失败或解码失败返回 null
     */
    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        return try {
            val bytes = MediaService.getThumbnail(mediaId, size = "medium")
            decodeImageBitmap(bytes)
        } catch (e: Exception) {
            logger.error(TAG, "loadThumbnail failed for $mediaId: ${e.message}")
            null
        }
    }

    /**
     * 加载原图。走 `GET /api/media/stream/{id}`（后端直接以文件字节返回）。
     *
     * @param mediaId 后端媒体 id
     * @return 解码后的 [ImageBitmap]；失败返回 null
     */
    suspend fun loadFullImage(mediaId: String): ImageBitmap? {
        return try {
            val bytes = MediaService.getMediaStream(mediaId)
            decodeImageBitmap(bytes)
        } catch (e: Exception) {
            logger.error(TAG, "loadFullImage failed for $mediaId: ${e.message}")
            null
        }
    }
}
