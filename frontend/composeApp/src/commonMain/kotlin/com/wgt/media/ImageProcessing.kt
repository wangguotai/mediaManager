package com.wgt.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 裁剪 + 旋转 + 滤镜图片（跨平台 expect 声明）。
 *
 * @param source 原始图片
 * @param cropRect 裁剪区域（源图坐标系），null 表示不裁剪
 * @param rotationDegrees 旋转角度（任意 Float 值，正=顺时针）
 * @param colorMatrix 20 元素的颜色矩阵（row-major 4×5），null 表示不应用滤镜
 * @return 处理后的图片
 */
expect fun cropAndRotateImageBitmap(
    source: ImageBitmap,
    cropRect: Rect?,
    rotationDegrees: Float,
    colorMatrix: FloatArray? = null
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
