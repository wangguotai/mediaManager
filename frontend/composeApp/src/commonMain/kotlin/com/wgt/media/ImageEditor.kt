package com.wgt.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_arrow_back
import mediamanager.composeapp.generated.resources.ic_check_circle
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_crop_reset
import mediamanager.composeapp.generated.resources.ic_rotate_left
import mediamanager.composeapp.generated.resources.ic_rotate_right
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * 编辑模式：裁剪 / 旋转 / 滤镜。底部 FilterChip 切换。
 */
private enum class EditMode(val label: String) {
    CROP("裁剪"),
    ROTATE("旋转"),
    FILTER("滤镜")
}

/**
 * 滤镜选项。每个附带一个 20 元素的 ColorMatrix（row-major 4×5），null = 原图。
 */
private data class FilterOption(val label: String, val matrix: FloatArray?)

private val FILTER_OPTIONS = listOf(
    FilterOption("原图", null),
    FilterOption("黑白", floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    FilterOption("暖色", floatArrayOf(
        1.5f, 0f, 0f, 0f, 0f,
        0f, 1.2f, 0f, 0f, 0f,
        0f, 0f, 0.8f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )),
    FilterOption("冷色", floatArrayOf(
        0.8f, 0f, 0f, 0f, 0f,
        0f, 1.0f, 0f, 0f, 0f,
        0f, 0f, 1.5f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))
)

/**
 * 宽高比选项。`null` = 自由裁剪；其余为 w/h。
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
 * 保存状态。
 */
private enum class SaveState { IDLE, SAVING, SUCCESS, FAILED }

/**
 * 图片编辑器全屏对话框：裁剪（宽高比 + 拖拽裁剪框）+ 旋转（90°）+ 滤镜 + 保存到本地相册。
 *
 * 底部用 FilterChip 切换 裁剪/旋转/滤镜 三个模式：
 * - 裁剪：宽高比选择条 + 拖拽裁剪框 + 重置按钮
 * - 旋转：左转90° / 右转90° 两个大按钮，点触即转
 * - 滤镜：原图/黑白/暖色/冷色 4 种，用 ColorFilter.colorMatrix 实现
 *
 * 保存流程：
 * 1. 用户点保存 → 进入 SAVING 状态，显示加载动画
 * 2. 异步执行 cropAndRotateImageBitmap + saveImageBitmapToGallery
 * 3. 成功 → SUCCESS 状态，短暂显示成功提示后关闭
 * 4. 失败 → FAILED 状态，显示错误提示，允许重试
 *
 * 取消编辑（点返回）不修改原文件。
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

    // 当前编辑模式。
    var editMode by remember { mutableStateOf(EditMode.CROP) }
    // 当前滤镜索引。
    var filterIndex by remember { mutableIntStateOf(0) }
    val filterMatrix by remember { derivedStateOf { FILTER_OPTIONS[filterIndex].matrix } }

    // 显示区像素尺寸：由 BoxWithConstraints 测得，用于裁剪框初始化与几何换算。
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 裁剪框（显示像素坐标）。nullable 表示尚未初始化。
    var cropRect by remember(media.id) { mutableStateOf<Rect?>(null) }

    // 保存状态
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // 原图加载状态：编辑器独立持有一份 bitmap。
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

    // 保存成功后自动关闭
    LaunchedEffect(saveState) {
        if (saveState == SaveState.SUCCESS) {
            kotlinx.coroutines.delay(800)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            // 保存中不允许关闭
            if (saveState != SaveState.SAVING) onDismiss()
        },
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
            val viewAspect by remember(srcW, srcH, rotation) {
                derivedStateOf {
                    if (srcW == 0 || srcH == 0) 1f
                    else if (rotation % 180 == 0) srcW.toFloat() / srcH else srcH.toFloat() / srcW
                }
            }

            // 顶部工具栏：返回 / 重置裁剪 / 保存。
            TopToolbar(
                onBack = {
                    if (saveState != SaveState.SAVING) onDismiss()
                },
                onResetCrop = {
                    // 重置裁剪框到全画面
                    val vp = viewport
                    if (vp.width > 0 && vp.height > 0) {
                        cropRect = Rect(0f, 0f, vp.width.toFloat(), vp.height.toFloat())
                    }
                },
                onSave = {
                    val displayRect = cropRect
                    val src2 = src
                    if (src2 == null || saveState == SaveState.SAVING) return@TopToolbar
                    saveState = SaveState.SAVING
                    saveError = null
                    scope.launch(dispatchers.io) {
                        try {
                            val sourceRect = displayRect?.let {
                                mapDisplayRectToSource(it, viewport, src2.width, src2.height, rotation)
                            }
                            val processed = cropAndRotateImageBitmap(
                                src2, sourceRect, rotation.toFloat(), filterMatrix
                            )
                            val result = saveImageBitmapToGallery(processed, media.filename)
                            if (result != null) {
                                saveState = SaveState.SUCCESS
                            } else {
                                saveState = SaveState.FAILED
                                saveError = "保存失败，请检查相册权限"
                            }
                        } catch (e: Exception) {
                            saveState = SaveState.FAILED
                            saveError = e.message ?: "保存失败"
                        }
                    }
                },
                actionsEnabled = src != null && saveState != SaveState.SAVING,
                saveState = saveState
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
                    editMode = editMode,
                    filterMatrix = filterMatrix,
                    onViewport = { viewport = it },
                    onCropRect = { cropRect = it },
                    density = density
                )
            }

            // 底部模式 + 选项条。
            BottomBar(
                editMode = editMode,
                onModeChange = { editMode = it },
                aspectIndex = aspectIndex,
                onAspectChange = { aspectIndex = it },
                filterIndex = filterIndex,
                onFilterChange = { filterIndex = it },
                onRotateLeft = { if (src != null) rotation = (rotation - 90 + 360) % 360 },
                onRotateRight = { if (src != null) rotation = (rotation + 90) % 360 },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 保存中遮罩
            AnimatedVisibility(
                visible = saveState == SaveState.SAVING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在保存…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            // 保存成功提示
            AnimatedVisibility(
                visible = saveState == SaveState.SUCCESS,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_check_circle),
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "已保存到相册",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 保存失败提示
            AnimatedVisibility(
                visible = saveState == SaveState.FAILED,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            saveError ?: "保存失败",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = { saveState = SaveState.IDLE }) {
                            Text("重试", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 编辑画布：根据源图尺寸与 [viewAspect] 在可用区内 Fit 渲染图片，叠加 [CropOverlay]。
 * 旋转用 [graphicsLayer].rotationZ 应用到图片本身；滤镜用 [ColorFilter.colorMatrix]。
 * 裁剪 overlay 仅在 CROP 模式下显示。
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
    editMode: EditMode,
    filterMatrix: FloatArray?,
    onViewport: (IntSize) -> Unit,
    onCropRect: (Rect) -> Unit,
    density: androidx.compose.ui.unit.Density
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp, bottom = 140.dp)
    ) {
        val maxWpx = with(density) { maxWidth.toPx() }
        val maxHpx = with(density) { maxHeight.toPx() }
        val fitted = fittedRect(maxWpx, maxHpx, viewAspect)
        val fittedInt = IntSize(fitted.width.toInt().coerceAtLeast(1), fitted.height.toInt().coerceAtLeast(1))

        LaunchedEffect(fittedInt.width, fittedInt.height, aspect, rotation, srcW, srcH) {
            if (fittedInt.width > 0 && fittedInt.height > 0) {
                onViewport(fittedInt)
                onCropRect(centeredCropFor(fitted, aspect))
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(
                        with(density) { fitted.width.toDp() },
                        with(density) { fitted.height.toDp() }
                    )
            ) {
                val colorFilter = if (filterMatrix != null) {
                    ColorFilter.colorMatrix(ColorMatrix(filterMatrix))
                } else {
                    null
                }
                Image(
                    bitmap = sourceBitmap,
                    contentDescription = "编辑中图片",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(rotationZ = rotation.toFloat()),
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter
                )
                // 裁剪 overlay 仅在 CROP 模式下显示。
                if (editMode == EditMode.CROP) {
                    val cr = cropRect
                    if (cr != null && viewport.width > 0 && viewport.height > 0) {
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
 * 顶部工具栏：返回 / 重置裁剪 / 保存。浮于黑底之上，左中右三段。
 * [actionsEnabled] 为 false（图未加载或保存中）时旋转与保存不可用。
 * [saveState] 控制保存按钮的显示状态。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun TopToolbar(
    onBack: () -> Unit,
    onResetCrop: () -> Unit,
    onSave: () -> Unit,
    actionsEnabled: Boolean,
    saveState: SaveState
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
        // 重置裁剪按钮（仅在非保存状态可用）
        IconButton(onClick = onResetCrop, enabled = actionsEnabled) {
            Icon(
                painter = painterResource(Res.drawable.ic_crop_reset),
                contentDescription = "重置裁剪",
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSave, enabled = actionsEnabled, modifier = Modifier.padding(end = 8.dp)) {
            when (saveState) {
                SaveState.SAVING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "保存中",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                else -> {
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
    }
}

/**
 * 底部栏：两行结构。
 * 第一行：模式切换 FilterChip（裁剪/旋转/滤镜）。
 * 第二行：根据当前模式显示对应选项：
 * - CROP: 宽高比选择条
 * - ROTATE: 左转90° / 右转90° 大按钮
 * - FILTER: 滤镜选择
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun BottomBar(
    editMode: EditMode,
    onModeChange: (EditMode) -> Unit,
    aspectIndex: Int,
    onAspectChange: (Int) -> Unit,
    filterIndex: Int,
    onFilterChange: (Int) -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        // 模式切换行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            EditMode.entries.forEach { mode ->
                FilterChip(
                    selected = editMode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                if (mode != EditMode.entries.last()) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 选项行：根据模式显示
        when (editMode) {
            EditMode.CROP -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ASPECT_OPTIONS) { opt ->
                    val idx = ASPECT_OPTIONS.indexOf(opt)
                    FilterChip(
                        selected = idx == aspectIndex,
                        onClick = { onAspectChange(idx) },
                        label = { Text(opt.label) }
                    )
                }
            }
            EditMode.ROTATE -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左转90° 按钮
                RotateButton(
                    iconRes = Res.drawable.ic_rotate_left,
                    label = "左转 90°",
                    onClick = onRotateLeft
                )
                Spacer(modifier = Modifier.width(32.dp))
                // 右转90° 按钮
                RotateButton(
                    iconRes = Res.drawable.ic_rotate_right,
                    label = "右转 90°",
                    onClick = onRotateRight
                )
            }
            EditMode.FILTER -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(FILTER_OPTIONS) { opt ->
                    val idx = FILTER_OPTIONS.indexOf(opt)
                    FilterChip(
                        selected = idx == filterIndex,
                        onClick = { onFilterChange(idx) },
                        label = { Text(opt.label) }
                    )
                }
            }
        }
    }
}

/**
 * 旋转按钮：圆形可点击，图标+文字垂直排列，参考小米相册风格。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun RotateButton(
    iconRes: org.jetbrains.compose.resources.DrawableResource,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp
        )
    }
}

/**
 * 在 (maxW,maxH) 内按 [aspect] (w/h) 求最大内接矩形（居中）。返回的 Rect 以 (0,0) 为左上，
 * 由调用方在 `Alignment.Center` 容器内居中放置。
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
 * 把显示像素坐标的裁剪框换算为源图未旋转坐标系内的裁剪框。
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
        else    -> { kX = srcW.toFloat() / dvw; kY = srcH.toFloat() / dvh }
    }
    val l = displayRect.left.coerceIn(0f, dvw)
    val t = displayRect.top.coerceIn(0f, dvh)
    val r = displayRect.right.coerceIn(0f, dvw)
    val b = displayRect.bottom.coerceIn(0f, dvh)

    val (sx0, sy0, sx1, sy1) = when (rotation % 360) {
        0 -> Quad4(l * kX, t * kY, r * kX, b * kY)
        180 -> Quad4((dvw - r) * kX, (dvh - b) * kY, (dvw - l) * kX, (dvh - t) * kY)
        90 -> {
            Quad4(t * kY, (dvw - r) * kX, b * kY, (dvw - l) * kX)
        }
        270 -> {
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
