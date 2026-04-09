package com.wgt.feature.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.wgt.platform.applicationContext
import com.wgt.platform.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.MediaMetadata
import media.MediaType

internal class AndroidPhotoGalleryService(private val context: Context) : PhotoGalleryService {
    
    override suspend fun getMediaFromGallery(): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaMetadata>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeTypeColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // 转换为毫秒
                val dateModified = cursor.getLong(dateModifiedColumn) * 1000 // 转换为毫秒
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                
                // 检查是否为Live Photo（简化处理，实际需要更复杂的检测）
                val isLivePhoto = name?.contains("IMG_") == true && name.endsWith(".HEIC")
                
                val mediaMetadata = MediaMetadata(
                    id = id.toString(),
                    filename = name ?: "unknown",
                    type = if (isLivePhoto) MediaType.LIVE_PHOTO else MediaType.IMAGE,
                    size = size,
                    mime_type = mimeType ?: "image/jpeg",
                    created_at = dateAdded,
                    updated_at = dateModified,
                    is_live_photo = isLivePhoto,
                    live_photo_video_id = if (isLivePhoto) "${id}_video" else "",
                    width = width,
                    height = height
                )
                
                mediaList.add(mediaMetadata)
            }
        }
        
        return@withContext mediaList
    }
    
    override suspend fun getMediaData(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mediaId.toLong()
            )
            
            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun getLivePhotoVideoData(mediaId: String): ByteArray? {
        // Android中Live Photo的视频数据需要特殊处理
        // 这里返回null，实际应用中需要实现Live Photo视频提取逻辑
        return null
    }
}

internal actual val photoGalleryService: PhotoGalleryService
    get() = AndroidPhotoGalleryService(AppContext.applicationContext)
