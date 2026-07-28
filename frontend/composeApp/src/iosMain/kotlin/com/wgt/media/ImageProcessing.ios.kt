package com.wgt.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.wgt.platform.logger.logger
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect

private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90

actual fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Int
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

        val rot = normalizeRotation(rotationDegrees)
        if (rot == 0) return Image.makeFromBitmap(cropped).toComposeImageBitmap()

        val srcImg = Image.makeFromBitmap(cropped)
        val outW: Int
        val outH: Int
        when (rot) {
            90, 270 -> { outW = cropped.height; outH = cropped.width }
            else -> { outW = cropped.width; outH = cropped.height }
        }
        val rotated = Bitmap().apply {
            allocPixels(ImageInfo.makeN32Premul(outW, outH))
            val canvas = Canvas(this)
            val paint = Paint()
            when (rot) {
                90 -> canvas.drawImageRect(srcImg,
                    SkiaRect(0f, 0f, cropped.width.toFloat(), cropped.height.toFloat()),
                    SkiaRect(outW.toFloat(), 0f, 0f, outH.toFloat()), paint)
                180 -> canvas.drawImageRect(srcImg,
                    SkiaRect(0f, 0f, cropped.width.toFloat(), cropped.height.toFloat()),
                    SkiaRect(cropped.width.toFloat(), cropped.height.toFloat(), 0f, 0f), paint)
                270 -> canvas.drawImageRect(srcImg,
                    SkiaRect(0f, 0f, cropped.width.toFloat(), cropped.height.toFloat()),
                    SkiaRect(0f, outH.toFloat(), outW.toFloat(), 0f), paint)
            }
        }
        Image.makeFromBitmap(rotated).toComposeImageBitmap()
    } catch (e: Exception) {
        logger.error("ImageProcessing", "cropAndRotate failed: ${e.message}")
        source
    }
}

actual suspend fun saveImageBitmapToGallery(
    bitmap: ImageBitmap,
    filename: String
): String? {
    // iOS 端保存相册暂未实现：Kotlin/Native cinterop 不暴露 NSData(bytes:length:) 构造器，
    // 需用 ObjC runtime bridging 或 memScoped 方案补全。
    logger.warning("ImageProcessing", "saveImageBitmapToGallery not yet implemented on iOS")
    return null
}
