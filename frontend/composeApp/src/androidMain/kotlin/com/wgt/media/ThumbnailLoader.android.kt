package com.wgt.media

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台缩略图加载器
 */
actual suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
    return try {
        val context = AppContext.applicationContext
        val id = mediaId.toLongOrNull() ?: return null

        // 加载缩略图 — 使用 MICRO_KIND (96x96) 以减少内存占用。
        // 之前使用 MINI_KIND (512x384)，在瀑布流网格中每屏可见数十个缩略图，
        // 解码后的 Bitmap 内存累积会导致 MIUI 杀进程。MICRO_KIND 足够网格展示，
        // 点击预览时再加载高清图。
        val thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
            context.contentResolver,
            id,
            MediaStore.Images.Thumbnails.MICRO_KIND,
            null
        )

        if (thumbnail != null) {
            thumbnail.asImageBitmap()
        } else {
            // 如果缩略图不存在，尝试加载原图
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                bitmap?.asImageBitmap()
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Android 平台完整图片加载器
 */
actual suspend fun loadFullImage(mediaId: String): ImageBitmap? {
    return try {
        val context = AppContext.applicationContext
        val id = mediaId.toLongOrNull() ?: return null

        val contentUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id
        )
        
        context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap?.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}
