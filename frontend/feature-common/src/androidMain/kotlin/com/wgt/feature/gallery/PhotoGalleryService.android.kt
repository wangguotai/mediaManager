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
import java.io.File
import java.io.RandomAccessFile

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
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                val dateModified = cursor.getLong(dateModifiedColumn) * 1000
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)

                val isLivePhoto = detectLivePhoto(id, name)

                val mediaMetadata = MediaMetadata(
                    id = id.toString(),
                    filename = name ?: "unknown",
                    type = if (isLivePhoto) MediaType.LIVE_PHOTO else MediaType.IMAGE,
                    size = size,
                    mime_type = mimeType ?: "image/jpeg",
                    created_at = dateAdded,
                    updated_at = dateModified,
                    is_live_photo = isLivePhoto,
                    live_photo_video_id = if (isLivePhoto) "local_live_$id" else "",
                    width = width,
                    height = height
                )

                mediaList.add(mediaMetadata)
            }
        }

        return@withContext mediaList
    }

    /**
     * 检测是否为 Live Photo (Motion Photo)。
     *
     * 支持两种格式:
     * 1. MVIMG_*.jpg — 小米/Google Motion Photo，视频嵌在 JPEG 末尾，XMP 元数据标记
     * 2. 任何包含 GCamera:MicroVideo XMP 元数据的 JPEG
     */
    private fun detectLivePhoto(mediaId: Long, filename: String?): Boolean {
        // Fast path: MVIMG prefix is the common Xiaomi Motion Photo marker
        if (filename != null && filename.startsWith("MVIMG_")) {
            return true
        }
        // Slow path: read XMP metadata to check for MicroVideo
        return try {
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId
            )
            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                val header = ByteArray(8192)
                val read = stream.read(header)
                if (read > 0) {
                    val headerStr = String(header, 0, read, Charsets.ISO_8859_1)
                    headerStr.contains("GCamera:MicroVideo=\"1\"") ||
                        headerStr.contains("MicroVideo=\"1\"")
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 JPEG 文件中提取嵌入的 Motion Photo 视频数据。
     *
     * Google/Xiaomi Motion Photo 格式: JPEG 正常图像数据 + 附加的 MP4 视频数据。
     * XMP 元数据中 GCamera:MicroVideoOffset 指示视频数据开始的字节偏移。
     *
     * @param mediaId MediaStore 图片 ID
     * @return 提取的 MP4 视频字节，null 表示不是 Live Photo 或提取失败
     */
    override suspend fun getLivePhotoVideoData(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val id = mediaId.toLong()

            // 1. 获取原始文件路径
            val dataColumn = arrayOf(MediaStore.Images.Media.DATA)
            val selection = "${MediaStore.Images.Media._ID} = ?"
            val selectionArgs = arrayOf(id.toString())
            var filePath: String? = null
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                dataColumn, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    filePath = cursor.getString(0)
                }
            }
            if (filePath == null) return@withContext null
            val file = java.io.File(filePath)
            if (!file.exists()) return@withContext null

            // 2. 读取 XMP 元数据获取视频偏移量
            val offset = extractMicroVideoOffset(id)
            if (offset == null) return@withContext null

            // 3. 从偏移量开始读取嵌入的 MP4 视频数据
            val raf = java.io.RandomAccessFile(file, "r")
            raf.seek(offset.toLong())
            val videoLength = file.length() - offset
            val videoBytes = ByteArray(videoLength.toInt())
            raf.readFully(videoBytes)
            raf.close()
            videoBytes
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 JPEG 的 XMP 元数据中提取 MicroVideoOffset。
     * 必须通过 MediaStore.DATA 获取原始文件路径直接读取，ContentResolver.openInputStream 返回压缩版本不含 XMP。
     */
    private fun extractMicroVideoOffset(mediaId: Long): Int? {
        return try {
            val dataColumn = arrayOf(MediaStore.Images.Media.DATA)
            val selection = "${MediaStore.Images.Media._ID} = ?"
            val selectionArgs = arrayOf(mediaId.toString())
            var filePath: String? = null
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                dataColumn, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    filePath = cursor.getString(0)
                }
            }
            if (filePath == null) return null
            val file = java.io.File(filePath)
            if (!file.exists()) return null

            val raf = java.io.RandomAccessFile(file, "r")
            val headerSize = minOf(65536, file.length().toInt())
            val header = ByteArray(headerSize)
            raf.readFully(header)
            raf.close()
            val headerStr = String(header, Charsets.ISO_8859_1)
            val hasMicroVideo = headerStr.contains("MicroVideo")
            val offsetRegex = """MicroVideoOffset="(\d+)""".toRegex()
            offsetRegex.find(headerStr)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
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
}

internal actual val photoGalleryService: PhotoGalleryService
    get() = AndroidPhotoGalleryService(AppContext.applicationContext)
