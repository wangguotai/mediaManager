package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint

/**
 * iOS 平台图片解码 —— 用 skia Image 解码后端返回的图片字节流。
 */
actual fun decodeImageBitmap(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * iOS 降采样解码：用 skia 解码后检查尺寸，超过 [maxDimension] 时用 Canvas 缩放。
 *
 * skia 的 Image.makeFromEncoded 解码完整位图后，我们通过 Bitmap+Canvas
 * 将其绘制到缩小后的目标 Bitmap 上，实现降采样，避免大图全尺寸驻留内存。
 */
actual fun decodeImageBitmapDownsampled(bytes: ByteArray?, maxDimension: Int): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        val skiaImage = Image.makeFromEncoded(bytes)
        val width = skiaImage.width
        val height = skiaImage.height
        if (width <= 0 || height <= 0) return null

        val longestEdge = maxOf(width, height)
        if (longestEdge <= maxDimension) {
            // 尺寸已在限制内，无需降采样
            return skiaImage.toComposeImageBitmap()
        }

        // 计算缩放比例，使长边恰好等于 maxDimension
        val scale = maxDimension.toFloat() / longestEdge
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)

        // 用 Bitmap+Canvas 进行降采样绘制
        val targetBitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeN32Premul(targetWidth, targetHeight))
        }
        val canvas = Canvas(targetBitmap)
        val paint = Paint().apply {
            // 高质量绘制
            isAntiAlias = true
            isDither = true
        }
        canvas.drawImageRect(
            skiaImage,
            org.jetbrains.skia.Rect(0f, 0f, width.toFloat(), height.toFloat()),
            org.jetbrains.skia.Rect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            paint
        )
        Image.makeFromBitmap(targetBitmap).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
