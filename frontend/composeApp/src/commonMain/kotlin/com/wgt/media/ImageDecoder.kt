package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 平台特定的图片字节解码 —— 把后端返回的图片字节流解码为 [ImageBitmap]。
 *
 * Android 用 [android.graphics.BitmapFactory]；iOS 用 skia Image。
 * bytes 为空或解码失败返回 null。
 */
expect fun decodeImageBitmap(bytes: ByteArray?): ImageBitmap?
