package com.wgt.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 裁剪 + 旋转图片（跨平台 expect 声明）。
 *
 * @param source 原始图片
 * @param cropRect 裁剪区域（源图坐标系），null 表示不裁剪
 * @param rotationDegrees 旋转角度（90 的倍数）
 * @return 处理后的图片
 */
expect fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Int
): ImageBitmap

/**
 * 保存图片到系统相册（跨平台 expect 声明）。
 *
 * @param bitmap 要保存的图片
 * @param filename 文件名
 * @return 保存后的媒体 URI/路径，失败返回 null
 */
expect suspend fun saveImageBitmapToGallery(
    bitmap: ImageBitmap,
    filename: String
): String?
