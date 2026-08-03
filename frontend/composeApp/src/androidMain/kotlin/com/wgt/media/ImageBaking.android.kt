package com.wgt.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.wgt.platform.logger.logger

/**
 * Android actual：把涂鸦 / 马赛克 / 文字叠加层烘焙到已 crop+rotate 的位图。
 *
 * 总体流程（见 commonMain expect 注释）：
 * 1. 对 [baseImage] 整图 crop+rotate（不应用 filter），得到 [processedBase]。
 * 2. 建立 [viewport].width × [viewport].height 的透明 [overlay] 位图，在「显示像素坐标系」
 *    里涂鸦 / 马赛克 / 文字，几何与编辑器预览一致。
 * 3. 对 [overlay] 整图 crop+rotate（同 [processedBase] 的变换），得到 [processedOverlay]。
 *    两条 crop+rotate 都用 [cropAndRotateImageBitmap] 的同款几何变换，保证叠加层与主图对齐。
 * 4. 把 [processedOverlay] 以 SRC_OVER 模式画到 [processedBase] 之上，得到 [composited]。
 * 5. 最后对 [composited] 应用 [filterMatrix]（可选），保证叠加层不被滤镜改色（与预览一致）。
 *
 * 注：Android 上 [cropAndRotateImageBitmap] 的签名是「源图坐标系裁剪框」，这里 [cropRect]
 * 是显示像素坐标，需先经 [mapDisplayRectToSource] 换算到源图坐标系再传入。叠加层 overlay
 * 本身就是 viewport 大小，所以 overlay 的 crop 直接用 [cropRect]（显示坐标==overlay 源坐标）。
 */
actual fun bakeOverlaysToImageBitmap(
    baseImage: ImageBitmap,
    viewport: IntSize,
    cropRect: Rect?,
    rotationDegrees: Float,
    drawColor: Color,
    drawStrokes: List<List<Offset>>,
    mosaicStrokes: List<List<Offset>>,
    textOverlays: List<TextOverlay>,
    filterMatrix: FloatArray?
): ImageBitmap? {
    if (viewport.width <= 0 || viewport.height <= 0) return null
    val hasOverlays =
        drawStrokes.isNotEmpty() || mosaicStrokes.isNotEmpty() || textOverlays.isNotEmpty()

    return try {
        // —— 1. 主图 crop+rotate（先不带 filter；filter 在最后统一应用）——
        val srcAndroid = baseImage.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val sourceCrop = cropRect?.let {
            // 显示坐标 → 源图坐标（含旋转补偿，与编辑器保存原逻辑一致）
            mapDisplayRectToSource(
                it, viewport, srcAndroid.width, srcAndroid.height, (rotationDegrees.toInt() % 360 + 360) % 360
            )
        }
        val processedBase = androidCropRotate(srcAndroid, sourceCrop, rotationDegrees, colorMatrix = null)

        // 无叠加层：直接应用 filter 返回（等价于原 cropAndRotateImageBitmap 的结果）
        if (!hasOverlays) {
            val filtered = applyFilter(processedBase, filterMatrix)
            return filtered.asImageBitmap()
        }

        // —— 2. 在 viewport 大小的透明画布上绘制叠加层（显示像素坐标系，几何与预览一致）——
        val overlay = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888)
        val overlayCanvas = Canvas(overlay)
        // 画布默认透明（ARGB_8888 初始为 0）
        drawDrawStrokes(overlayCanvas, drawStrokes, drawColor)
        drawMosaicStrokes(overlayCanvas, mosaicStrokes)
        drawTextOverlays(overlayCanvas, textOverlays, viewport)

        // —— 3. 对 overlay 应用同主图一样的 crop+rotate；
        //    overlay 源图坐标系 = viewport 显示坐标系，cropRect 直接可用。 ——
        val processedOverlay = androidCropRotate(overlay, cropRect, rotationDegrees, colorMatrix = null)

        // 尺寸兜底：理论上 processedOverlay 与 processedBase 尺寸一致；不一致时强制对齐避免越界。
        val outW = processedBase.width
        val outH = processedBase.height
        val composited = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val compCanvas = Canvas(composited)
        compCanvas.drawBitmap(processedBase, 0f, 0f, null)
        // 把 overlay 按「目标矩形 = processedBase 全幅」绘制，自动缩放对齐（理论上等尺寸无需缩放）。
        val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        compCanvas.drawBitmap(
            processedOverlay,
            null,
            dst,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER) }
        )

        // —— 4. 最后统一应用滤镜（保证叠加层不被 ColorFilter 改色，与预览一致）——
        val filtered = applyFilter(composited, filterMatrix)
        filtered.asImageBitmap()
    } catch (e: Exception) {
        logger.error("ImageBaking", "bakeOverlaysToImageBitmap failed: ${e.message}")
        null
    }
}

/**
 * android.graphics 版 crop+rotate（与 [cropAndRotateImageBitmap] 同几何语义，便于本文件复用且不引入
 * 跨 actual 依赖）。`colorMatrix` 不为 null 且长度≥20 时应用 ColorMatrix 滤镜。输入 [src] 会被拷贝，
 * 不修改调用方持有的 Bitmap。返回值为新分配 Bitmap。
 */
private fun androidCropRotate(
    src: Bitmap,
    cropRect: Rect?,
    rotationDegrees: Float,
    colorMatrix: FloatArray?
): Bitmap {
    val cropped: Bitmap = if (cropRect != null && cropRect.width > 0f && cropRect.height > 0f) {
        val left = cropRect.left.toInt().coerceIn(0, src.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, src.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, src.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, src.height)
        Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    } else {
        src
    }
    val rotated: Bitmap = if (rotationDegrees != 0f) {
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    } else {
        cropped
    }
    if (colorMatrix != null && colorMatrix.size >= 20) {
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(colorMatrix))
        }
        val filtered = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(filtered).drawBitmap(rotated, 0f, 0f, paint)
        return filtered
    }
    return rotated
}

/**
 * 对 [src] 应用 [filterMatrix]（拷贝）。filter 为 null 或长度不足时直接返回原 bitmap。
 */
private fun applyFilter(src: Bitmap, filterMatrix: FloatArray?): Bitmap {
    if (filterMatrix == null || filterMatrix.size < 20) return src
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(filterMatrix))
    }
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, paint)
    return out
}

/**
 * 在 [canvas] 上绘制涂鸦笔触。线宽与编辑器预览（[drawDrawStroke] Stroke.width=6f）一致，
 * 端点/连接取 Round，颜色取 [color]。
 */
private fun drawDrawStrokes(canvas: Canvas, strokes: List<List<Offset>>, color: Color) {
    if (strokes.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = colorToArgb(color)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val path = Path()
        path.moveTo(stroke[0].x, stroke[0].y)
        for (i in 1 until stroke.size) path.lineTo(stroke[i].x, stroke[i].y)
        canvas.drawPath(path, paint)
    }
}

/**
 * 在 [canvas] 上绘制马赛克笔触。与编辑器预览（[drawMosaicStroke]）的视觉参数对齐：
 * - 笔触主体用 cell=14f 宽的 Square 端点 Bevel 连接半透明灰色（0.55）。
 * - 沿笔触按 cell 间隔加画方格网格增强马赛克观感。
 *
 * 不做真实像素采样（与预览一致的马赛克模拟）。
 */
private fun drawMosaicStrokes(canvas: Canvas, strokes: List<List<Offset>>) {
    if (strokes.isEmpty()) return
    val cell = 14f
    val radius = cell / 2f
    val grayArgb = colorToArgb(Color.Gray.copy(alpha = 0.55f))
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = grayArgb
        style = Paint.Style.STROKE
        strokeWidth = cell
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.BEVEL
    }
    val squarePaint = Paint().apply { color = grayArgb }
    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val path = Path()
        path.moveTo(stroke[0].x, stroke[0].y)
        for (i in 1 until stroke.size) path.lineTo(stroke[i].x, stroke[i].y)
        canvas.drawRect(
            stroke[0].x - radius, stroke[0].y - radius,
            stroke[0].x + radius, stroke[0].y + radius,
            squarePaint
        )
        var accum = 0f
        var last: Offset = stroke[0]
        for (i in 1 until stroke.size) {
            val cur = stroke[i]
            accum += (cur - last).getDistance()
            while (accum >= cell) {
                last = Offset(
                    last.x + (cur.x - last.x) * (cell / (accum + 0.0001f)),
                    last.y + (cur.y - last.y) * (cell / (accum + 0.0001f))
                )
                accum -= cell
                canvas.drawRect(
                    last.x - radius, last.y - radius,
                    last.x + radius, last.y + radius,
                    squarePaint
                )
            }
            last = cur
        }
        canvas.drawPath(path, strokePaint)
    }
}

/**
 * 在 [canvas] 上绘制文字标注。文字定位与编辑器预览（[EditorCanvas] 文字 overlay 段）一致：
 * 锚点 [TextOverlay.position] 为显示坐标，文字向左偏移 `length*11px`（与预览偏移一致），
 * 向上偏移 14px，背景为 alpha=[TextOverlay.backgroundAlpha] 的圆角黑底，白色粗体居中。
 *
 * 字号用 [TextOverlay.fontSizeSp] sp；Canvas 上不能用 sp/dp，需经 density 换算，
 * 但 bake 是离屏处理，无 Android display metrics context，故按 sp≈px 的近似处理
 * （编辑器在常见 density=1 的 fitted px 下与 px 行为接近；若需精准可后续传入 density）。
 */
private fun drawTextOverlays(
    canvas: Canvas,
    textOverlays: List<TextOverlay>,
    viewport: IntSize
) {
    if (textOverlays.isEmpty()) return
    for (o in textOverlays) {
        if (o.text.isBlank()) continue
        // 文字相对锚点的偏移与预览一致：水平 -(length*11)、垂直 -14。
        val offsetX = o.text.length * 11f
        val anchorX = (o.position.x - offsetX).coerceIn(0f, viewport.width.toFloat())
        val anchorY = (o.position.y - 14f).coerceIn(0f, viewport.height.toFloat())

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorToArgb(o.color)
            textSize = o.fontSizeSp
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        // 背景半透明黑底，圆角半径 4px
        val bgPadX = 8f
        val bgPadY = 4f
        val textW = textPaint.measureText(o.text)
        val fs = textPaint.fontMetrics
        val bgLeft = anchorX - textW / 2f - bgPadX
        val bgTop = anchorY + fs.ascent - bgPadY
        val bgRight = anchorX + textW / 2f + bgPadX
        val bgBottom = anchorY + fs.descent + bgPadY
        val bgPaint = Paint().apply {
            color = android.graphics.Color.argb(
                (o.backgroundAlpha * 255f).toInt().coerceIn(0, 255), 0, 0, 0
            )
        }
        val bgRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
        canvas.drawRoundRect(bgRect, 4f, 4f, bgPaint)
        // 文字基线：anchorY 近似基线锚点（与预览 fontSize 22 / offset y -14 近似对齐）。
        canvas.drawText(o.text, anchorX, anchorY - fs.ascent / 2f, textPaint)
    }
}

/** Compose [Color] → Android ARGB Int。 */
private fun colorToArgb(c: Color): Int =
    android.graphics.Color.argb(
        (c.alpha * 255f).toInt().coerceIn(0, 255),
        (c.red * 255f).toInt().coerceIn(0, 255),
        (c.green * 255f).toInt().coerceIn(0, 255),
        (c.blue * 255f).toInt().coerceIn(0, 255)
    )

/**
 * 显示像素坐标系下的裁剪框 → 源图坐标系的裁剪框（与 ImageEditor.kt 中 [mapDisplayRectToSource]
 * 同语义，本文件需要独立一份以避免跨文件可见性 / 命名冲突）。rotation 只接受 0/90/180/270。
 */
private fun mapDisplayRectToSource(
    displayRect: Rect,
    viewport: IntSize,
    srcW: Int,
    srcH: Int,
    rotation: Int
): Rect {
    val dvw = viewport.width.toFloat()
    val dvh = viewport.height.toFloat()
    if (dvw <= 0f || dvh <= 0f || srcW <= 0 || srcH <= 0) return displayRect
    val kX: Float
    val kY: Float
    when (rotation % 360) {
        90, 270 -> { kX = srcH.toFloat() / dvh; kY = srcW.toFloat() / dvw }
        else -> { kX = srcW.toFloat() / dvw; kY = srcH.toFloat() / dvh }
    }
    val l = displayRect.left.coerceIn(0f, dvw)
    val t = displayRect.top.coerceIn(0f, dvh)
    val r = displayRect.right.coerceIn(0f, dvw)
    val b = displayRect.bottom.coerceIn(0f, dvh)

    data class Q(val a: Float, val b: Float, val c: Float, val d: Float)
    val (sx0, sy0, sx1, sy1) = when (rotation % 360) {
        0 -> Q(l * kX, t * kY, r * kX, b * kY)
        180 -> Q((dvw - r) * kX, (dvh - b) * kY, (dvw - l) * kX, (dvh - t) * kY)
        90 -> Q(t * kY, (dvw - r) * kX, b * kY, (dvw - l) * kX)
        270 -> Q(t * kY, l * kX, b * kY, r * kX)
        else -> Q(l * kX, t * kY, r * kX, b * kY)
    }
    return Rect(
        minOf(sx0, sx1).coerceIn(0f, srcW.toFloat()),
        minOf(sy0, sy1).coerceIn(0f, srcH.toFloat()),
        maxOf(sx0, sx1).coerceIn(0f, srcW.toFloat()),
        maxOf(sy0, sy1).coerceIn(0f, srcH.toFloat())
    )
}
