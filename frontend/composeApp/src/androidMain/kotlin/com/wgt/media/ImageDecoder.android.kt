package com.wgt.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android 平台图片解码 —— 用 BitmapFactory 解码后端返回的图片字节流。
 */
actual fun decodeImageBitmap(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Android 降采样解码：两阶段 BitmapFactory 解码。
 *
 * 1. inJustBounds=true 只读边界，不分配像素内存
 * 2. 根据 [maxDimension] 计算 inSampleSize（2 的幂）
 * 3. 用 inSampleSize 真正解码，像素内存降低 sampleSize² 倍
 *
 * 这避免了 MIUI 系统因全尺寸解码大图（如 4000×3000 → ~48MB Bitmap）
 * 导致内存暴涨而被系统杀死的问题。
 */
actual fun decodeImageBitmapDownsampled(bytes: ByteArray?, maxDimension: Int): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return try {
        // 阶段 1：只读边界
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return null

        // 阶段 2：计算 inSampleSize
        // inSampleSize 必须是 2 的幂（Android 要求），向下取整确保降采样后尺寸不超过 maxDimension。
        // 每次采样使尺寸减半，所以 sampleSize = 2^k 使得 max(width, height) / 2^k <= maxDimension。
        var sampleSize = 1
        val longestEdge = maxOf(width, height)
        while (longestEdge / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        // 阶段 3：用 inSampleSize 真正解码
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // RGB_565 比默认 ARGB_8888 节省一半内存，对预览画质影响可忽略
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
