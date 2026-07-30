package com.wgt.feature.gallery

/**
 * iOS 端 [requestMediaDeletion] 实现：no-op。
 *
 * iOS 媒体删除由 [IOSPhotoGalleryService.deleteMedia]（PHAssetDeleteRequest）处理，
 * 不走此系统确认入口；直接回调 0，保持与 expect 契约一致。
 */
actual fun requestMediaDeletion(mediaIds: List<String>, onResult: (Int) -> Unit) {
    onResult(0)
}
