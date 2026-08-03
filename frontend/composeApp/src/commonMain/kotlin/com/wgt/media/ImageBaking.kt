package com.wgt.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize

/**
 * 烘焙叠加层（涂鸦 / 马赛克 / 文字）到一张已完成 crop+rotate 的位图，返回最终的位图。
 *
 * 编辑器中用户的叠加操作均位于「显示像素坐标系」（fitted 矩形，由 [viewport] 表征）：
 * 涂鸦 / 马赛克笔触的 [Offset] 与文字标注的 [TextOverlay.position] 都使用这套坐标。
 * 烘焙时需把这套坐标补偿到目标位图坐标系。为了准确还原视觉，本函数内部：
 *
 * 1. 先对 [baseImage] 应用 crop+rotate（参考 [cropAndRotateImageBitmap]，同语义复用），
 *    得到 [processedBase]（含裁剪、旋转，但不带滤镜——滤镜最后统一应用，避免叠加层被滤镜改色）。
 * 2. 再在 [viewport].width × [viewport].height 的临时画布上把叠加层画出来，
 *    然后以与第 1 步相同的 crop+rotate 变换叠加到 [processedBase] 之上。
 *    这样几何与编辑器预览完全一致（叠加层随图片一起被裁剪/旋转）。
 * 3. 最后统一应用 [filterMatrix]（可选）。
 *
 * 设计权衡：
 * - 滤镜放在叠加层之后：保证涂鸦红/白、马赛克灰、文字白不被滤镜二次改色，
 *   与编辑器预览中叠加层不受 ColorFilter 影响（ColorFilter 只挂在 Image 上）一致。
 * - 复用 [cropAndRotateImageBitmap]：crop+rotate 几何变换已成熟实现（含 Android/iOS actual），
 *   叠加层临时位图同样走这条路径，保证变换一致性。
 *
 * @param baseImage 原始未裁剪、未旋转的 [ImageBitmap]（来自编辑器 sourceBitmap）
 * @param viewport 显示像素尺寸（fitted 矩形），叠加层坐标系原点为左上角 (0,0)
 * @param cropRect 显示像素坐标系下的裁剪框，null 表示不裁剪（等同 viewport 全画幅）
 * @param rotationDegrees 旋转角度（0/90/180/270，正=顺时针），与主图 crop+rotate 同源
 * @param drawColor 当前涂鸦颜色（红/白）；所有涂鸦笔触共用此色
 * @param drawStrokes 涂鸦笔触列表，每条笔触为 [Offset] 序列（显示像素坐标）
 * @param mosaicStrokes 马赛克笔触列表；视觉为粗灰色方格
 * @param textOverlays 文字标注列表
 * @param filterMatrix 可选的颜色矩阵（row-major 4×5），null=不应用滤镜；最后统一应用
 * @return 烘焙后的 [ImageBitmap]；平台烘焙失败时返回 null，调用方可回退到 [cropAndRotateImageBitmap]
 */
expect fun bakeOverlaysToImageBitmap(
    baseImage: ImageBitmap,
    viewport: IntSize,
    cropRect: Rect?,
    rotationDegrees: Float,
    drawColor: Color,
    drawStrokes: List<List<Offset>>,
    mosaicStrokes: List<List<Offset>>,
    textOverlays: List<TextOverlay>,
    filterMatrix: FloatArray?
): ImageBitmap?

/**
 * 文字标注（用于烘焙与预览共用）。
 *
 * @param position 文字标注锚点，显示像素坐标系（与 [ImageEditor] 中预览同源）
 * @param text 文字内容
 * @param color 文字颜色（默认白色，与预览一致）
 * @param fontSizeSp 文字字号，sp（默认 22，与预览一致）
 * @param backgroundAlpha 文字背景半透明黑底 alpha（默认 0.35，与预览一致）
 */
data class TextOverlay(
    val position: Offset,
    val text: String,
    val color: Color = Color.White,
    val fontSizeSp: Float = 22f,
    val backgroundAlpha: Float = 0.35f
)

