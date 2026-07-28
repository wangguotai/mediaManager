package com.wgt.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect as AndroidRect
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.platform.logger.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90

actual fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Int
): ImageBitmap {
    return try {
        val srcBitmap = source.asAndroidBitmap()

        // 裁剪
        val cropped: Bitmap =
            if (cropRect != null && cropRect.width > 0f && cropRect.height > 0f) {
                val left = cropRect.left.toInt().coerceIn(0, srcBitmap.width - 1)
                val top = cropRect.top.toInt().coerceIn(0, srcBitmap.height - 1)
                val right = cropRect.right.toInt().coerceIn(left + 1, srcBitmap.width)
                val bottom = cropRect.bottom.toInt().coerceIn(top + 1, srcBitmap.height)
                val w = right - left
                val h = bottom - top
                Bitmap.createBitmap(
                    srcBitmap,
                    left,
                    top,
                    w,
                    h
                )
            } else {
                srcBitmap
            }

        val rot = normalizeRotation(rotationDegrees)
        if (rot == 0) return cropped.asImageBitmap()

        val matrix = Matrix().apply {
            postRotate(rot.toFloat())
        }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        rotated.asImageBitmap()
    } catch (e: Exception) {
        logger.error("ImageProcessing", "cropAndRotate failed: ${e.message}")
        source
    }
}

actual suspend fun saveImageBitmapToGallery(
    bitmap: ImageBitmap,
    filename: String
): String? = withContext(Dispatchers.IO) {
    try {
        val context = AppContext.applicationContext
        val androidBitmap = bitmap.asAndroidBitmap()

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MediaManager")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
        if (uri == null) return@withContext null
        val result: String? = uri.toString()

        context.contentResolver.openOutputStream(uri)?.use { out ->
            androidBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }

        uri.toString()
    } catch (e: Exception) {
        logger.error("ImageProcessing", "saveImageBitmapToGallery failed: ${e.message}")
        null
    }
}
