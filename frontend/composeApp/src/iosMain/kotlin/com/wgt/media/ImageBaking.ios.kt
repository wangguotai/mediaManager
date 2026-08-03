package com.wgt.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.wgt.platform.logger.logger

/**
 * iOS actual：烘焙叠加层到 [baseImage]。
 *
 * 当前实现（TODO）：iOS 端暂未实现真正的 Canvas+Paint 烘焙逻辑
 * （Kotlin/Native 到 Skia 的文字/笔触绘制 API 需要补齐），直接返回 null
 * 表示「未烘焙」，调用方在保存路径会回退到 [cropAndRotateImageBitmap]
 * （即仅 crop+rotate+filter，与历史行为一致），不阻塞功能。
 *
 * 后续：参考 ImageProcessing.ios.kt 的 Skia 路径，
 * 用 org.jetbrains.skia.Canvas + Paint + Path 在 overlay bitmap 上绘制涂鸦/马赛克/文字，
 * 再用同款 crop+rotate 几何合成到主图。
 */
actual fun bakeOverlaysToImageBitmap(
    baseImage: ImageBitmap,
    viewport: IntSize,
    cropRect: Rect?,
    rotationDegrees: Float,
    drawColor: Color,
    drawStrokes: List<List<androidx.compose.ui.geometry.Offset>>,
    mosaicStrokes: List<List<androidx.compose.ui.geometry.Offset>>,
    textOverlays: List<TextOverlay>,
    filterMatrix: FloatArray?
): ImageBitmap? {
    logger.warning(
        "ImageBaking",
        "bakeOverlaysToImageBitmap on iOS not yet implemented — overlays will not be baked"
    )
    return null
}
