package com.wgt.media

import com.wgt.feature.media.MediaService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.MediaMetadata
import media.MediaType

private const val TAG = "SyncManager"

/**
 * 增量同步管理器。
 *
 * App 启动 + 进入"已上传"Tab 时拉取增量变更，
 * 云相册自动备份开关控制本地新图后台上传。
 */
object SyncManager {
    private var cursor: Long = 0L
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    /**
     * 拉取云端增量变更，返回需要展示的媒体列表。
     */
    suspend fun pullChanges(): List<MediaMetadata> {
        _syncing.value = true
        try {
            val result = MediaService.getSyncChanges(cursor) ?: return emptyList()
            val items = result.changes.filter { !it.deleted }
            if (result.changes.isNotEmpty()) {
                cursor = result.changes.maxOf { it.updatedAt }
            }
            return items.map { it.toMediaMetadata() }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            _syncing.value = false
        }
    }

    /**
     * 上传本地图片到云端（带 sha256 去重）。
     */
    suspend fun uploadLocal(
        mediaId: String,
        filename: String,
        data: ByteArray,
        isLivePhoto: Boolean = false
    ): Boolean {
        val sha = sha256Hex(data)
        return try {
            if (sha.isNotEmpty() && DedupStore.contains(sha)) {
                return true // 已存在，秒传
            }
            val success = MediaService.uploadMedia(data, filename, isLivePhoto)
            if (success && sha.isNotEmpty()) {
                DedupStore.add(sha)
            }
            success
        } catch (e: Exception) {
            OfflineQueueStore.enqueue(OfflineQueueItem(mediaId, filename, sha, isLivePhoto, 0L, "fe-$mediaId"))
            false
        }
    }

    /**
     * 重放在线队列中的失败上传。
     */
    suspend fun replayOfflineQueue(getData: suspend (String) -> ByteArray?) {
        val pending = OfflineQueueStore.snapshot()
        for (item in pending) {
            val data = getData(item.localMediaId) ?: continue
            val ok = uploadLocal(item.localMediaId, item.filename, data, item.isLivePhoto)
            if (ok) OfflineQueueStore.remove(item.localMediaId)
        }
    }

    private fun MediaService.SyncChange.toMediaMetadata(): MediaMetadata = MediaMetadata(
        id = id,
        filename = filename,
        type = type,
        size = size,
        mime_type = mimeType,
        created_at = createdAt,
        updated_at = updatedAt,
        is_live_photo = false,
        live_photo_video_id = "",
        width = width,
        height = height
    )
}
