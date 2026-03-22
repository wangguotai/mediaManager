package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 平台特定的缩略图加载器
 */
expect suspend fun loadThumbnail(mediaId: String): ImageBitmap?

/**
 * 平台特定的完整图片加载器
 */
expect suspend fun loadFullImage(mediaId: String): ImageBitmap?
