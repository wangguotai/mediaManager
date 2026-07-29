package com.wgt.media

// iOS: Live Photo 视频提取走 PHAsset 原生 API，不由此路径处理。
internal actual suspend fun extractLocalLivePhotoVideo(mediaId: String): String? = null
