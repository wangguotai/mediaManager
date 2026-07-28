package com.wgt.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import mediamanager.composeapp.generated.resources.ic_check_circle
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_refresh
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 宽高比选项。`null` = 自由裁剪；其余为 w/h。
 *
 * 切换比例时若当前裁剪框已存在，则以其中心 + 当前尺寸重置为符合新比例的最大内接框，
 * 保持用户已选定的构图中心，只调长宽比。
 */
private data class AspectOption(val label: String, val ratio: Float?)

private val ASPECT_OPTIONS = listOf(
    AspectOption("自由", null),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3f),
    AspectOption("16:9", 16f / 9f),
    AspectOption("3:4", 3f / 4f),
    AspectOption("9:16", 9f / 16f)
)

/**
 * 图片编辑器全屏对话框：裁剪（宽高比 + 拖拽裁剪框）+ 旋转（90°）+ 保存到本地相册。
 *
 * 显示模型：图片按 [ContentScale.Fit] 在显示区内渲染；旋转用 [graphicsLayer] 的
 * `rotationZ` 应用到图片本身，旋转 90/270 时显示区有效宽高比按 `源高/源宽` 重新 Fit，
 * 使旋转后图像仍完整可见且裁剪框（套在可视矩形上）不被旋转、保持正向。裁剪框为显示
 * 像素坐标，保存前由 [mapDisplayRectToSource] 按当前旋转换算回源图像素坐标，连同旋转角
 * 一并交给 [cropAndRotateImageBitmap] 先裁后旋，再 [saveImageBitmapToGallery] 落盘。
 *
 * 编辑器内部按 [useBackendLoader] 自行加载原图（与 [ImagePreviewDialog] 的加载链一致），
 * 不依赖预览已加载的 bitmap，解耦更干净、避免预览缩放态串入编辑器。
 *
 * @param media 被编辑的媒体（取 filename 作保存名、id 作加载键）
 * @param useBackendLoader true 走 [BackendImageLoader]（后端 HTTP），false 走平台相册加载器
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun ImageEditor(
    media: MediaMetadata,
    useBackendLoader: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 旋转角（0/90/180/270）。用 Int 累加，按 90 取模规整。
    var rotation by remember { mutableIntStateOf(0) }
    // 当前宽高比选项索引（默认自由）。
    var aspectIndex by remember { mutableIntStateOf(0) }
    val aspect by remember { derivedStateOf { ASPECT_OPTIONS[aspectIndex].ratio } }

    // 显示区像素尺寸：由 BoxWithConstraints 测得，用于裁剪框初始化与几何换算。
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 裁剪框（显示像素坐标）。nullable 表示尚未初始化（首次拿到源图与视口时按比例居中建框）。
    var cropRect by remember(media.id) { mutableStateOf<Rect?>(null) }

    // 原图加载状态：复用预览/网格的同一条加载链，编辑器独立持有一份 bitmap。
    var sourceBitmap by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(media.id) { mutableStateOf(true) }
    LaunchedEffect(media.id, useBackendLoader) {
        isLoading = true
        val bmp = if (useBackendLoader) {
            BackendImageLoader.loadFullImage(media.id)
        } else {
            loadFullImage(media.id)
        }
        sourceBitmap = bmp
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val src = sourceBitmap
            val srcW = src?.width ?: 0
            val srcH = src?.height ?: 0
            // 旋转 90/270 时"可视宽高比"取源图高宽比；0/180 取源图宽高比。
            val viewAspect by remember(srcW, srcH, rotation) {
                derivedStateOf {
                    if (srcW == 0 || srcH == 0) 1f
                    else if (rotation % 180 == 0) srcW.toFloat() / srcH else srcH.toFloat() / srcW
                }
            }

            // 顶部工具栏：返回 / 旋转 / 保存（图未加载时禁用旋转/保存）。
            TopToolbar(
                onBack = onDismiss,
                onRotate = { if (src != null) rotation = (rotation + 90) % 360 },
                onSave = {
                    val displayRect = cropRect
                    val src2 = src
                    if (src2 == null) return@TopToolbar
                    scope.launch(dispatchers.io) {
                        // 把显示像素裁剪框换算回源图未旋转坐标系，交给落地函数先裁后旋。
                        val sourceRect = displayRect?.let {
                            mapDisplayRectToSource(it, viewport, src2.width, src2.height, rotation)
                        }
                        val processed = cropAndRotateImageBitmap(src2, sourceRect, rotation)
                        saveImageBitmapToGallery(processed, media.filename)
                    }
                    onDismiss()
                },
                actionsEnabled = src != null
            )

            when {
                isLoading -> LoadingState()
                src == null -> ErrorState(onDismiss)
                else -> EditorCanvas(
                    sourceBitmap = src,
                    srcW = srcW,
                    srcH = srcH,
                    viewAspect = viewAspect,
                    rotation = rotation,
                    aspect = aspect,
                    viewport = viewport,
                    cropRect = cropRect,
                    onViewport = { viewport = it },
                    onCropRect = { cropRect = it },
                    density = density
                )
            }

            // 底部宽高比选择条。
            BottomAspectBar(
                selectedIndex = aspectIndex,
                onSelect = { aspectIndex = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 编辑画布：根据源图尺寸与 [viewAspect] 在可用区内 Fit 渲染图片，叠加 [CropOverlay]。
 * 旋转用 [graphicsLayer].rotationZ 应用到图片本身；裁剪框套在 fitted 矩形上且不旋转，
 * 使裁剪交互始终在"旋转后视图"的正向坐标系内进行。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun EditorCanvas(
    sourceBitmap: ImageBitmap,
    srcW: Int,
    srcH: Int,
    viewAspect: Float,
    rotation: Int,
    aspect: Float?,
    viewport: IntSize,
    cropRect: Rect?,
    onViewport: (IntSize) -> Unit,
    onCropRect: (Rect) -> Unit,
    density: androidx.compose.ui.unit.Density
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp, bottom = 96.dp)
    ) {
        val maxWpx = with(density) { maxWidth.toPx() }
        val maxHpx = with(density) { maxHeight.toPx() }
        // Fit：按 viewAspect 在显示区内求最大可视矩形尺寸（以 0,0 为左上，宽高即所需）。
        val fitted = fittedRect(maxWpx, maxHpx, viewAspect)
        val fittedInt = IntSize(fitted.width.toInt().coerceAtLeast(1), fitted.height.toInt().coerceAtLeast(1))

        // 视口/裁剪框初始化：fitted 尺寸、比例或旋转变化时，重置 cropRect 为 fitted 内
        // 符合当前比例的最大居中框。
        LaunchedEffect(fittedInt.width, fittedInt.height, aspect, rotation, srcW, srcH) {
            if (fittedInt.width > 0 && fittedInt.height > 0) {
                onViewport(fittedInt)
                onCropRect(centeredCropFor(fitted, aspect))
            }
        }

        // 图片层：graphicsLayer 旋转；裁剪 overlay 套在 fitted 矩形上且不旋转。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(
                        with(density) { fitted.width.toDp() },
                        with(density) { fitted.height.toDp() }
                    )
            ) {
                Image(
                    bitmap = sourceBitmap,
                    contentDescription = "编辑中图片",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = rotation.toFloat()),
                    contentScale = ContentScale.Fit
                )
                val cr = cropRect
                if (cr != null && viewport.width > 0 && viewport.height > 0) {
                    // cropRect 坐标系即 fitted 矩形局部像素系（0,0 起），与 overlay 同系。
                    CropOverlay(
                        viewSize = viewport,
                        cropRect = cr,
                        aspectRatio = aspect,
                        onCropRectChange = onCropRect
                    )
                }
            }
        }
    }
}

/** 加载中占位：居中圆圈。 */
@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

/** 加载失败占位：提示 + 返回。 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ErrorState(onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(Res.drawable.ic_close),
                contentDescription = "加载失败",
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("图片加载失败，无法编辑", color = Color.White.copy(alpha = 0.7f))
            TextButton(onClick = onDismiss) { Text("返回", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/**
 * 顶部工具栏：返回 / 旋转 / 保存。浮于黑底之上，左中右三段。
 * [actionsEnabled] 为 false（图未加载）时旋转与保存不可用。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun TopToolbar(
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onSave: () -> Unit,
    actionsEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Black.copy(alpha = 0.4f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onRotate, enabled = actionsEnabled) {
            Icon(
                painter = painterResource(Res.drawable.ic_refresh),
                contentDescription = "旋转90度",
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSave, enabled = actionsEnabled, modifier = Modifier.padding(end = 8.dp)) {
            Icon(
                painter = painterResource(Res.drawable.ic_check_circle),
                contentDescription = "保存",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "保存",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 底部宽高比选择条：横向滚动的 FilterChip。
 */
@Composable
private fun BottomAspectBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(ASPECT_OPTIONS) { opt ->
                val idx = ASPECT_OPTIONS.indexOf(opt)
                FilterChip(
                    selected = idx == selectedIndex,
                    onClick = { onSelect(idx) },
                    label = { Text(opt.label) }
                )
            }
        }
    }
}

/**
 * 在 (maxW,maxH) 内按 [aspect] (w/h) 求最大内接矩形（居中）。返回的 Rect 以 (0,0) 为左上，
 * 由调用方在 `Alignment.Center` 容器内居中放置——这里返回尺寸而非绝对坐标，宽高即够。
 */
private fun fittedRect(maxW: Float, maxH: Float, aspect: Float): Rect {
    if (maxW <= 0f || maxH <= 0f || aspect <= 0f) return Rect(0f, 0f, 0f, 0f)
    val w: Float
    val h: Float
    if (maxW / maxH > aspect) {
        h = maxH
        w = h * aspect
    } else {
        w = maxW
        h = w / aspect
    }
    return Rect(0f, 0f, w, h)
}

/**
 * 在 fitted 矩形内求符合 [aspect] 的最大居中裁剪框。`aspect=null` 取 fitted 的 80%
 * 内接居中框（自由模式给一个略小于可视区的初始框，留边便于拖拽扩展）。
 */
private fun centeredCropFor(fitted: Rect, aspect: Float?): Rect {
    val fw = fitted.width
    val fh = fitted.height
    if (fw <= 0f || fh <= 0f) return fitted
    if (aspect == null || aspect <= 0f) {
        val w = fw * 0.8f
        val h = fh * 0.8f
        return Rect((fw - w) / 2f, (fh - h) / 2f, (fw - w) / 2f + w, (fh - h) / 2f + h)
    }
    val w: Float
    val h: Float
    if (fw / fh > aspect) {
        h = fh
        w = h * aspect
    } else {
        w = fw
        h = w / aspect
    }
    val left = (fw - w) / 2f
    val top = (fh - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * 把显示像素坐标的裁剪框（在 fitted/viewport 局部系，0..viewport.width）换算为
 * **源图未旋转坐标系**（0..srcW, 0..srcH）内的裁剪框，再随 [cropAndRotateImageBitmap]
 * 旋转。本函数不旋转，只做"用户在旋转后视图里框选的区域 ↔ 源图区域"的反向映射。
 *
 * 旋转关系（顺时针旋转源图得到当前视图，旋转角 = [rotation]）：
 * - 0°：视图(dx,dy) ↔ 源(dx,dy)，等比缩放。
 * - 180°：视图(dx,dy) ↔ 源(srcW−dx, srcH−dy)，双向翻转。
 * - 90°：视图宽对应源高、视图高对应源宽；视图(dx,dy) ↔ 源(dy·kH, (dvw−dx)·kW)。
 * - 270°：视图(dx,dy) ↔ 源(dy·kH, dx·kW)。
 *
 * 实现：先按旋转角确定"视图→源"的轴对应与系数，再把裁剪框两端投影到源坐标并排序，
 * 保证返回的 Rect 为正向（left/right、top/bottom 不反）。
 *
 * 系数约定：kX 为视图 x 像素 → 源对应轴的源像素数；kY 为视图 y 像素 → 源对应轴的源像素数。
 * 90/270 时视图 x 轴对应源的 **高度** 轴，故 kX = srcH/dvh；视图 y 轴对应源的 **宽度** 轴，
 * 故 kY = srcW/dvw。
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
    // kX: 视图 x 像素 → 源对应轴源像素数；kY: 视图 y 像素 → 源对应轴源像素数。
    val kX: Float
    val kY: Float
    when (rotation % 360) {
        90, 270 -> { kX = srcH.toFloat() / dvh; kY = srcW.toFloat() / dvw }
        else    -> { kX = srcW.toFloat() / dvw; kY = srcH.toFloat() / dvh }
    }
    val l = displayRect.left.coerceIn(0f, dvw)
    val t = displayRect.top.coerceIn(0f, dvh)
    val r = displayRect.right.coerceIn(0f, dvw)
    val b = displayRect.bottom.coerceIn(0f, dvh)

    // 投影裁剪框两端到源坐标：(sx0,sy0)-(sx1,sy1) 可能因翻转而反序，下方取 min/max 归正。
    val (sx0, sy0, sx1, sy1) = when (rotation % 360) {
        0 -> Quad4(l * kX, t * kY, r * kX, b * kY)
        180 -> Quad4((dvw - r) * kX, (dvh - b) * kY, (dvw - l) * kX, (dvh - t) * kY)
        90 -> {
            // 视图 x → 源 y（翻转）：源 y = (dvw - dx)·kX；视图 y → 源 x：源 x = dy·kY
            Quad4(t * kY, (dvw - r) * kX, b * kY, (dvw - l) * kX)
        }
        270 -> {
            // 视图 x → 源 y：源 y = dx·kX；视图 y → 源 x：源 x = dy·kY
            Quad4(t * kY, l * kX, b * kY, r * kX)
        }
        else -> Quad4(l * kX, t * kY, r * kX, b * kY)
    }
    return Rect(
        minOf(sx0, sx1).coerceIn(0f, srcW.toFloat()),
        minOf(sy0, sy1).coerceIn(0f, srcH.toFloat()),
        maxOf(sx0, sx1).coerceIn(0f, srcW.toFloat()),
        maxOf(sy0, sy1).coerceIn(0f, srcH.toFloat())
    )
}

/** 4 值组，仅 [mapDisplayRectToSource] 内部承载两端的源坐标。 */
private data class Quad4(val a: Float, val b: Float, val c: Float, val d: Float)

