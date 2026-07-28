package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 平台特定的图片字节解码 —— 把后端返回的图片字节流解码为 [ImageBitmap]。
 *
 * Android 用 [android.graphics.BitmapFactory]；iOS 用 skia Image。
 * bytes 为空或解码失败返回 null。
 */
expect fun decodeImageBitmap(bytes: ByteArray?): ImageBitmap?

/**
 * 平台特定的降采样解码 —— 解码时限制最长边不超过 [maxDimension] 像素，
 * 避免大图全尺寸解码导致内存暴涨（MIUI OOM kill 根因）。
 *
 * Android 用 BitmapFactory.Options.inSampleSize 两阶段解码；
 * iOS 用 skia 解码后按比例缩放。
 *
 * @param bytes 图片字节流
 * @param maxDimension 长边像素上限（如 2048）
 * @return 降采样后的 [ImageBitmap]；失败返回 null
 */
expect fun decodeImageBitmapDownsampled(bytes: ByteArray?, maxDimension: Int): ImageBitmap?
