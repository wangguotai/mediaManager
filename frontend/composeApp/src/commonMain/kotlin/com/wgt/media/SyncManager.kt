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
     *
     * 去重分两层互补：
     * 1. 本端 [DedupStore] 命中即直接短路——连后端往返都省（客户端早判）。
     * 2. 未命中或本端缓存不可信时，把 [sha256Hex] 算出的指纹透传给后端
     *    （POST /api/media/upload?sha256=...），由后端按 (user_id, sha256) 做权威秒传：
     *    命中则不落盘直接返回既有 media_id，命中软删记录还会复活。这样即便本端
     *    DedupStore 未登记但云端实际已有同内容（如另一台设备刚传过），仍能秒传。
     *
     * [clientId]/[takenAt] 走 query param 透传给后端入库，供多端冲突排查与时序。
     * 默认值用于在线首传路径（无离线上下文）；重放路径 ([replayOfflineQueue]) 会
     * 带上离线队列里保存的 takenAt/clientId。
     */
    suspend fun uploadLocal(
        mediaId: String,
        filename: String,
        data: ByteArray,
        isLivePhoto: Boolean = false,
        clientId: String = "fe-$mediaId",
        takenAt: Long = 0L
    ): Boolean {
        val sha = sha256Hex(data)
        return try {
            if (sha.isNotEmpty() && DedupStore.contains(sha)) {
                return true // 本端已知云端有此内容，秒传
            }
            // 透传 sha256 让后端做权威秒传；未命中则在后端落盘并由其实测 sha 入库。
            val success = MediaService.uploadMedia(
                fileData = data,
                filename = filename,
                isLivePhoto = isLivePhoto,
                sha256 = sha,
                clientId = clientId,
                takenAt = takenAt
            )
            if (success && sha.isNotEmpty()) {
                DedupStore.add(sha)
            }
            success
        } catch (e: Exception) {
            OfflineQueueStore.enqueue(OfflineQueueItem(mediaId, filename, sha, isLivePhoto, takenAt, clientId))
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
            val ok = uploadLocal(
                mediaId = item.localMediaId,
                filename = item.filename,
                data = data,
                isLivePhoto = item.isLivePhoto,
                clientId = item.clientId,
                takenAt = item.takenAt
            )
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
