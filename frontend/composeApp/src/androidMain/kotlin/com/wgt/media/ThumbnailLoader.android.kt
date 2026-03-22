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

        // 加载缩略图
        val thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
            context.contentResolver,
            id,
            MediaStore.Images.Thumbnails.MINI_KIND,
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
