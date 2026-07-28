package com.wgt.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.wgt.platform.logger.logger
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect

actual fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Float,
    colorMatrix: FloatArray?
): ImageBitmap {
    return try {
        val srcBitmap = source.asSkiaBitmap()
        val cropped: Bitmap =
            if (cropRect != null && cropRect.width > 0f && cropRect.height > 0f) {
                val left = cropRect.left.toInt().coerceIn(0, srcBitmap.width - 1)
                val top = cropRect.top.toInt().coerceIn(0, srcBitmap.height - 1)
                val right = cropRect.right.toInt().coerceIn(left + 1, srcBitmap.width)
                val bottom = cropRect.bottom.toInt().coerceIn(top + 1, srcBitmap.height)
                val w = right - left
                val h = bottom - top
                Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(w, h))
                    Canvas(this).drawImageRect(
                        Image.makeFromBitmap(srcBitmap),
                        SkiaRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                        SkiaRect(0f, 0f, w.toFloat(), h.toFloat()),
                        Paint()
                    )
                }
            } else {
                srcBitmap
            }

        val srcImg = Image.makeFromBitmap(cropped)

        // 旋转（任意角度）：计算旋转后 bounding box 尺寸，用 canvas.rotate 绕中心旋转。
        val rotated: Bitmap =
            if (rotationDegrees != 0f) {
                val rad = kotlin.math.PI * rotationDegrees.toDouble() / 180.0
                val cos = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                val sin = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
                val origW = cropped.width.toFloat()
                val origH = cropped.height.toFloat()
                val outW = (origW * cos + origH * sin).toInt().coerceAtLeast(1)
                val outH = (origW * sin + origH * cos).toInt().coerceAtLeast(1)
                Bitmap().apply {
                    allocPixels(ImageInfo.makeN32Premul(outW, outH))
                    val canvas = Canvas(this)
                    canvas.translate(outW / 2f, outH / 2f)
                    canvas.rotate(rotationDegrees)
                    canvas.translate(-origW / 2f, -origH / 2f)
                    canvas.drawImage(srcImg, 0f, 0f, Paint())
                }
            } else {
                cropped
            }

        // 滤镜（ColorMatrix via Skia ColorFilter）
        if (colorMatrix != null && colorMatrix.size >= 20) {
            val filtered = Bitmap().apply {
                allocPixels(ImageInfo.makeN32Premul(rotated.width, rotated.height))
                val canvas = Canvas(this)
                val paint = Paint().apply {
                    colorFilter = org.jetbrains.skia.ColorFilter.makeMatrix(ColorMatrix(*colorMatrix))
                }
                canvas.drawImage(Image.makeFromBitmap(rotated), 0f, 0f, paint)
            }
            Image.makeFromBitmap(filtered).toComposeImageBitmap()
        } else {
            Image.makeFromBitmap(rotated).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        logger.error("ImageProcessing", "cropAndRotate failed: ${e.message}")
        source
    }
}

actual suspend fun saveImageBitmapToGallery(
    bitmap: ImageBitmap,
    filename: String
): String? {
    logger.warning("ImageProcessing", "saveImageBitmapToGallery not yet implemented on iOS")
    return null
}
