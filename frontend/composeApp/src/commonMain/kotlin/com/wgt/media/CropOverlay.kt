package com.wgt.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 裁剪手柄种类。
 *
 * - 四个角 [TL]/[TR]/[BL]/[BR]：缩放裁剪框，对角为锚点。
 * - 四条边 [T]/[B]/[L]/[R]：自由模式下单边移动；锁定宽高比模式下绕中心对称缩放。
 * - [BODY]：拖动整个裁剪框平移。
 * - [NONE]：未命中任何可拖动区域。
 */
private enum class CropHandle { NONE, TL, TR, BL, BR, T, B, L, R, BODY }

/**
 * 裁剪框遮罩 + 拖拽 UI 组件。
 *
 * 纯绘制层：填充父容器（即图片显示区），在其上绘制四块半透明遮罩、裁剪框边线、
 * 九宫格辅助线与八个拖拽手柄，并接管拖拽手势更新 [cropRect]。所有坐标均在组件自身的
 * 像素坐标系内，与 [ImageEditor] 传来的 [cropRect] 一致；编辑器负责把该像素裁剪框
 * 换算回源图坐标。
 *
 * 宽高比约束：[aspectRatio] 为 `null` 表示自由裁剪，四角四边各自独立缩放；
 * 为正值（w/h）时四角以对角为锚按比例缩放，四边绕裁剪框中心对称缩放，始终保持比例。
 *
 * @param viewSize 父容器像素尺寸，用于越界裁剪
 * @param cropRect 当前裁剪框（像素坐标），由父组件持有以便换算/复位
 * @param aspectRatio 目标宽高比 w/h，`null` = 自由
 * @param onCropRectChange 拖拽产生的新裁剪框回调
 */
@Composable
fun CropOverlay(
    viewSize: IntSize,
    cropRect: Rect,
    aspectRatio: Float?,
    onCropRectChange: (Rect) -> Unit
) {
    val density = LocalDensity.current
    // 手柄可命中半径（dp→px）：约 24dp 触控区，兼顾可见性与易触。
    val handleTouchRadius = with(density) { 24.dp.toPx() }
    // 裁剪框最小边长（px），避免被拖成一条线后无法再捏开。
    val minSizePx = with(density) { 48.dp.toPx() }
    val bounds = Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat())

    // 当前命中的手柄：拖拽起始时在 [onDragStart] 命中检测中确定，拖拽过程中复用，
    // 避免每帧重新命中导致手柄在中途跳变。
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    if (viewSize.width == 0 || viewSize.height == 0) return

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewSize, aspectRatio, cropRect) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeHandle = hitTest(
                                offset = offset,
                                crop = cropRect,
                                touchRadius = handleTouchRadius
                            )
                        },
                        onDrag = { _, dragAmount ->
                            if (activeHandle == CropHandle.NONE) return@detectDragGestures
                            val next = applyDrag(
                                current = cropRect,
                                handle = activeHandle,
                                dx = dragAmount.x,
                                dy = dragAmount.y,
                                aspect = aspectRatio,
                                bounds = bounds,
                                minSize = minSizePx
                            )
                            onCropRectChange(next)
                        },
                        onDragEnd = { activeHandle = CropHandle.NONE },
                        onDragCancel = { activeHandle = CropHandle.NONE }
                    )
                }
        ) {
            // 遮罩：画四块半透明矩形（上/下/左/右），跨端兼容无需 PathOp。
            val sz = size
            val maskColor = Color.Black.copy(alpha = 0.5f)
            // 上
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(sz.width, cropRect.top))
            // 下
            drawRect(maskColor, topLeft = Offset(0f, cropRect.bottom), size = androidx.compose.ui.geometry.Size(sz.width, sz.height - cropRect.bottom))
            // 左
            drawRect(maskColor, topLeft = Offset(0f, cropRect.top), size = androidx.compose.ui.geometry.Size(cropRect.left, cropRect.height))
            // 右
            drawRect(maskColor, topLeft = Offset(cropRect.right, cropRect.top), size = androidx.compose.ui.geometry.Size(sz.width - cropRect.right, cropRect.height))

            // 裁剪框边线：白色细线 + 三分线（九宫格辅助线），帮用户对齐。
            drawRect(
                color = Color.White,
                topLeft = cropRect.topLeft,
                size = cropRect.size,
                style = Stroke(width = with(density) { 2.dp.toPx() })
            )
            cropGridLines(cropRect).forEach { line ->
                drawLine(
                    color = Color.White.copy(alpha = 0.4f),
                    start = line.first,
                    end = line.second,
                    strokeWidth = with(density) { 1.dp.toPx() }
                )
            }

            // 八个手柄：角为 L 形、边为中点短线段，强化“可拖拽”视觉暗示。
            val cornerLen = with(density) { 18.dp.toPx() }
            val edgeLen = with(density) { 22.dp.toPx() }
            val handleWidth = with(density) { 3.dp.toPx() }
            drawCornerHandles(cropRect, cornerLen, handleWidth)
            drawEdgeHandles(cropRect, edgeLen, handleWidth)
        }
    }
}

/**
 * 命中检测：依次判断是否落在某手柄触控区或裁剪框内部。
 *
 * 优先级：四角 → 四边 → 框内（BODY）→ 否则 [NONE]。角与边的触控半径为 [touchRadius]。
 */
private fun hitTest(offset: Offset, crop: Rect, touchRadius: Float): CropHandle {
    // 四角：以角点为圆心、touchRadius 为半径的圆。
    if (offset.distanceTo(crop.topLeft) <= touchRadius) return CropHandle.TL
    if (offset.distanceTo(crop.topRight) <= touchRadius) return CropHandle.TR
    if (offset.distanceTo(crop.bottomLeft) <= touchRadius) return CropHandle.BL
    if (offset.distanceTo(crop.bottomRight) <= touchRadius) return CropHandle.BR

    if (crop.contains(offset)) {
        val nearTop = offset.y - crop.top <= touchRadius
        val nearBottom = crop.bottom - offset.y <= touchRadius
        val nearLeft = offset.x - crop.left <= touchRadius
        val nearRight = crop.right - offset.x <= touchRadius
        // 仅当靠近某条边时算边手柄；靠近两条边时已先被角命中，故此处互斥。
        if (nearTop) return CropHandle.T
        if (nearBottom) return CropHandle.B
        if (nearLeft) return CropHandle.L
        if (nearRight) return CropHandle.R
        return CropHandle.BODY
    }
    return CropHandle.NONE
}

private fun Offset.distanceTo(point: Offset): Float {
    val dx = x - point.x
    val dy = y - point.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * 把一次拖拽增量 [dx]/[dy] 作用到 [current] 上，返回新裁剪框（已按 [bounds] 裁切、
 * 不小于 [minSize]）。逻辑分三类：整体平移、自由缩放、锁定宽高比缩放。
 */
private fun applyDrag(
    current: Rect,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    aspect: Float?,
    bounds: Rect,
    minSize: Float
): Rect {
    if (handle == CropHandle.NONE) return current
    if (handle == CropHandle.BODY) {
        // 整体平移：尽量保持尺寸不变，越界时贴边。
        val w = current.width
        val h = current.height
        val newLeft = (current.left + dx).coerceIn(bounds.left, bounds.right - w)
        val newTop = (current.top + dy).coerceIn(bounds.top, bounds.bottom - h)
        return Rect(newLeft, newTop, newLeft + w, newTop + h)
    }

    // 锁定宽高比模式：四角以对角为锚按比例缩放，四边绕中心对称缩放。
    if (aspect != null && aspect > 0f) {
        return when (handle) {
            // 四角：锚点为对角顶点，沿拖拽方向延伸；宽高保持 aspect。
            CropHandle.TL -> scaleAspectFromAnchor(
                anchorX = current.right, anchorY = current.bottom,
                dirX = -1f, dirY = -1f,
                rawW = current.right - (current.left + dx),
                rawH = current.bottom - (current.top + dy),
                aspect = aspect, bounds = bounds, minSize = minSize
            )
            CropHandle.TR -> scaleAspectFromAnchor(
                anchorX = current.left, anchorY = current.bottom,
                dirX = 1f, dirY = -1f,
                rawW = (current.right + dx) - current.left,
                rawH = current.bottom - (current.top + dy),
                aspect = aspect, bounds = bounds, minSize = minSize
            )
            CropHandle.BL -> scaleAspectFromAnchor(
                anchorX = current.right, anchorY = current.top,
                dirX = -1f, dirY = 1f,
                rawW = current.right - (current.left + dx),
                rawH = (current.bottom + dy) - current.top,
                aspect = aspect, bounds = bounds, minSize = minSize
            )
            CropHandle.BR -> scaleAspectFromAnchor(
                anchorX = current.left, anchorY = current.top,
                dirX = 1f, dirY = 1f,
                rawW = (current.right + dx) - current.left,
                rawH = (current.bottom + dy) - current.top,
                aspect = aspect, bounds = bounds, minSize = minSize
            )
            // 四边：绕中心对称缩放，T/B 改高度、L/R 改宽度，另一维按比例联动。
            CropHandle.T, CropHandle.B -> {
                val cx = current.center.x
                val cy = current.center.y
                val halfH = (current.height / 2f + dy).coerceAtLeast(minSize / 2f)
                val h2 = halfH * 2f
                val w2 = h2 * aspect
                centerRect(cx, cy, w2, h2, bounds)
            }
            CropHandle.L, CropHandle.R -> {
                val cx = current.center.x
                val cy = current.center.y
                val halfW = (current.width / 2f + dx).coerceAtLeast(minSize / 2f)
                val w2 = halfW * 2f
                val h2 = w2 / aspect
                centerRect(cx, cy, w2, h2, bounds)
            }
            else -> current
        }
    }

    // 自由模式：四角移动单角、四边移动单边，对角/对边固定。
    return when (handle) {
        CropHandle.TL -> clampRect(
            Rect(current.left + dx, current.top + dy, current.right, current.bottom), bounds, minSize)
        CropHandle.TR -> clampRect(
            Rect(current.left, current.top + dy, current.right + dx, current.bottom), bounds, minSize)
        CropHandle.BL -> clampRect(
            Rect(current.left + dx, current.top, current.right, current.bottom + dy), bounds, minSize)
        CropHandle.BR -> clampRect(
            Rect(current.left, current.top, current.right + dx, current.bottom + dy), bounds, minSize)
        CropHandle.T -> clampRect(
            Rect(current.left, current.top + dy, current.right, current.bottom), bounds, minSize)
        CropHandle.B -> clampRect(
            Rect(current.left, current.top, current.right, current.bottom + dy), bounds, minSize)
        CropHandle.L -> clampRect(
            Rect(current.left + dx, current.top, current.right, current.bottom), bounds, minSize)
        CropHandle.R -> clampRect(
            Rect(current.left, current.top, current.right + dx, current.bottom), bounds, minSize)
        else -> current
    }
}

/**
 * 锁定宽高比的四角缩放：以对角顶点 (anchorX,anchorY) 为锚，沿 (dirX,dirY) 方向延伸，
 * 用拖拽产生的 [rawW]/[rawH] 取“既不超出拖拽点、又保持比例”的最大尺寸：
 * `w = min(rawW, rawH*aspect)`，`h = w/aspect`。若该尺寸从锚点延伸会越出 [bounds]，
 * 则整体同比例缩到刚好不越界，仍保持 aspect。
 */
private fun scaleAspectFromAnchor(
    anchorX: Float,
    anchorY: Float,
    dirX: Float,
    dirY: Float,
    rawW: Float,
    rawH: Float,
    aspect: Float,
    bounds: Rect,
    minSize: Float
): Rect {
    val rw = rawW.coerceAtLeast(0f)
    val rh = rawH.coerceAtLeast(0f)
    var w = minOf(rw, rh * aspect).coerceAtLeast(minSize)
    var h = w / aspect
    // 从锚点沿 dir 延伸 w/h，若越界则按 bounds 反算可用 w/h 并取较小者，保持比例。
    val availW = if (dirX > 0f) bounds.right - anchorX else anchorX - bounds.left
    val availH = if (dirY > 0f) bounds.bottom - anchorY else anchorY - bounds.top
    if (w > availW) { w = availW.coerceAtLeast(minSize); h = w / aspect }
    if (h > availH) { h = availH.coerceAtLeast(minSize); w = h * aspect }
    val left = if (dirX > 0f) anchorX else anchorX - w
    val top = if (dirY > 0f) anchorY else anchorY - h
    return Rect(left, top, left + w, top + h)
}

/**
 * 以 (cx,cy) 为中心、(w,h) 为尺寸构建裁剪框，并在越出 [bounds] 时整体回缩到界内
 * （保持尺寸，移动中心）。
 */
private fun centerRect(cx: Float, cy: Float, w: Float, h: Float, bounds: Rect): Rect {
    var left = cx - w / 2f
    var top = cy - h / 2f
    if (left < bounds.left) left = bounds.left
    if (top < bounds.top) top = bounds.top
    if (left + w > bounds.right) left = bounds.right - w
    if (top + h > bounds.bottom) top = bounds.bottom - h
    return Rect(left, top, left + w, top + h)
}

/**
 * 把任意 Rect 规整到 [bounds] 内并保证宽高不小于 [minSize]：先 clamp 四边到界内，
 * 再确保 right-left/bottom-top ≥ minSize（必要时回拉左/上）。
 */
private fun clampRect(rect: Rect, bounds: Rect, minSize: Float): Rect {
    var left = rect.left.coerceIn(bounds.left, bounds.right)
    var top = rect.top.coerceIn(bounds.top, bounds.bottom)
    var right = rect.right.coerceIn(bounds.left, bounds.right)
    var bottom = rect.bottom.coerceIn(bounds.top, bounds.bottom)
    // 维持最小尺寸：若某边被压得太近对边，以对边为基准回拉。
    if (right - left < minSize) {
        if (rect.right >= rect.left) right = (left + minSize).coerceAtMost(bounds.right)
        else left = (right - minSize).coerceAtLeast(bounds.left)
    }
    if (bottom - top < minSize) {
        if (rect.bottom >= rect.top) bottom = (top + minSize).coerceAtMost(bounds.bottom)
        else top = (bottom - minSize).coerceAtLeast(bounds.top)
    }
    return Rect(left, top, right, bottom)
}

/** 九宫格辅助线：返回 4 条线段（两条竖三分线 + 两条横三分线）的起止点。 */
private fun cropGridLines(rect: Rect): List<Pair<Offset, Offset>> {
    val dx = rect.width / 3f
    val dy = rect.height / 3f
    return listOf(
        Offset(rect.left + dx, rect.top) to Offset(rect.left + dx, rect.bottom),
        Offset(rect.left + 2 * dx, rect.top) to Offset(rect.left + 2 * dx, rect.bottom),
        Offset(rect.left, rect.top + dy) to Offset(rect.right, rect.top + dy),
        Offset(rect.left, rect.top + 2 * dy) to Offset(rect.right, rect.top + 2 * dy)
    )
}

/** 画四个角的 L 形手柄。 */
private fun DrawScope.drawCornerHandles(rect: Rect, len: Float, width: Float) {
    val topLeft = rect.topLeft
    val topRight = Offset(rect.right, rect.top)
    val bottomLeft = Offset(rect.left, rect.bottom)
    val bottomRight = Offset(rect.right, rect.bottom)
    drawLine(Color.White, topLeft, Offset(topLeft.x + len, topLeft.y), width)
    drawLine(Color.White, topLeft, Offset(topLeft.x, topLeft.y + len), width)
    drawLine(Color.White, topRight, Offset(topRight.x - len, topRight.y), width)
    drawLine(Color.White, topRight, Offset(topRight.x, topRight.y + len), width)
    drawLine(Color.White, bottomLeft, Offset(bottomLeft.x + len, bottomLeft.y), width)
    drawLine(Color.White, bottomLeft, Offset(bottomLeft.x, bottomLeft.y - len), width)
    drawLine(Color.White, bottomRight, Offset(bottomRight.x - len, bottomRight.y), width)
    drawLine(Color.White, bottomRight, Offset(bottomRight.x, bottomRight.y - len), width)
}

/** 画四条边中点的短线段手柄。 */
private fun DrawScope.drawEdgeHandles(rect: Rect, len: Float, width: Float) {
    val topMid = Offset(rect.center.x, rect.top)
    val bottomMid = Offset(rect.center.x, rect.bottom)
    val leftMid = Offset(rect.left, rect.center.y)
    val rightMid = Offset(rect.right, rect.center.y)
    drawLine(Color.White, Offset(topMid.x, topMid.y - len / 2), Offset(topMid.x, topMid.y + len / 2), width)
    drawLine(Color.White, Offset(bottomMid.x, bottomMid.y - len / 2), Offset(bottomMid.x, bottomMid.y + len / 2), width)
    drawLine(Color.White, Offset(leftMid.x - len / 2, leftMid.y), Offset(leftMid.x + len / 2, leftMid.y), width)
    drawLine(Color.White, Offset(rightMid.x - len / 2, rightMid.y), Offset(rightMid.x + len / 2, rightMid.y), width)
}
