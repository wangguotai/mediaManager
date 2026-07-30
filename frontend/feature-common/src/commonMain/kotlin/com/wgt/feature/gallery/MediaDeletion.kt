package com.wgt.feature.gallery

/**
 * 发起媒体删除的系统确认流程。
 *
 * 用于在 [PhotoGalleryService.deleteMedia] 返回 -1（表示需系统确认，Android 10+ scoped storage
 * 下对非 owner 媒体的可恢复删除）后，由 ViewModel 调用以拉起系统授权弹窗完成删除。
 *
 * 平台行为：
 * - Android API ≥29：用 [android.provider.MediaStore.createDeleteRequest] 生成批量删除
 *   PendingIntent，借助当前 Activity 启动系统弹窗；用户同意后由系统删除。
 * - Android API <29：直接 [android.content.ContentResolver.delete]（scoped storage 之前可直接删）。
 * - iOS：no-op，回调 [onResult] 传 0（iOS 删除走 PHAsset，不由此入口处理）。
 *
 * @param mediaIds 待删除的本地媒体 id 列表（同 [PhotoGalleryService.deleteMedia] 的 id 语义）
 * @param onResult 删除完成后回调，参数为成功删除的数量；无可用 Activity 或用户拒绝时为 0
 */
expect fun requestMediaDeletion(mediaIds: List<String>, onResult: (Int) -> Unit)
