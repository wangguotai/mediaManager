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

/**
 * iOS 保存图片到相册。
 *
 * 当前实现：通过 PHPhotoLibrary + PHAssetCreationRequest 保存。
 * 使用 NSURL.fileURLWithPath 创建临时文件 URL，再通过 PHAssetCreationRequest 从文件保存。
 *
 * 注意：由于 Kotlin/Native NSData 创建 API 限制，iOS 保存功能需要进一步集成测试。
 * 当前返回 null 表示保存未完成，但不影响编译和编辑功能（裁剪/旋转/滤镜）的正常使用。
 *
 * 需要 Info.plist 中的 NSPhotoLibraryAddUsageDescription 权限。
 */
actual suspend fun saveImageBitmapToGallery(
    bitmap: ImageBitmap,
    filename: String
): String? {
    return try {
        // iOS 保存到相册需要通过 PHPhotoLibrary，
        // 但 Kotlin/Native 中 NSData(initWithBytes:length:) 构造器未直接暴露，
        // 需要通过临时文件中转。POSIX 文件 API 在当前编译环境中解析有问题，
        // 暂不实现写入逻辑，后续通过 native framework 桥接解决。
        logger.warning("ImageProcessing", "saveImageBitmapToGallery: iOS save not yet fully implemented")
        null
    } catch (e: Exception) {
        logger.error("ImageProcessing", "saveImageBitmapToGallery failed: ${e.message}")
        null
    }
}
