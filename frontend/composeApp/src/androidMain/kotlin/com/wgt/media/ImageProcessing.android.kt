package com.wgt.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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

actual fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Float,
    colorMatrix: FloatArray?
): ImageBitmap {
    return try {
        val srcBitmap = source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)

        // 裁剪
        val cropped: Bitmap =
            if (cropRect != null && cropRect.width > 0f && cropRect.height > 0f) {
                val left = cropRect.left.toInt().coerceIn(0, srcBitmap.width - 1)
                val top = cropRect.top.toInt().coerceIn(0, srcBitmap.height - 1)
                val right = cropRect.right.toInt().coerceIn(left + 1, srcBitmap.width)
                val bottom = cropRect.bottom.toInt().coerceIn(top + 1, srcBitmap.height)
                Bitmap.createBitmap(srcBitmap, left, top, right - left, bottom - top)
            } else {
                srcBitmap
            }

        // 旋转（任意角度）
        val rotated: Bitmap =
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
            } else {
                cropped
            }

        // 滤镜（ColorMatrix）
        if (colorMatrix != null && colorMatrix.size >= 20) {
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(colorMatrix))
            }
            val filtered = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(filtered)
            canvas.drawBitmap(rotated, 0f, 0f, paint)
            filtered.asImageBitmap()
        } else {
            rotated.asImageBitmap()
        }
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
        ) ?: return@withContext null

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
