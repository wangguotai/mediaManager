package com.wgt.media

import com.wgt.feature.media.MediaService
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
     * 上传本地图片到云端（带 sha256 去重 + 持久化离线队列）。
     *
     * 这是自动备份的**唯一上传通路**：MediaViewModel 的自动备份不再直接调
     * [MediaService.uploadMedia]（3 参版会丢 sha256/client_id/taken_at），而统一走本方法，
     * 保证 (user_id,sha256) 秒传、client_id 幂等键、taken_at 时序真正生效。
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
     *
     * 失败语义：**凡上传未成功**（HTTP 非 200 或网络异常）均入 [OfflineQueueStore] 持久化
     * 离线队列，进程重启不丢、[replayOfflineQueue] 重放。注意 [MediaService.uploadMedia]
     * 内部已吞异常并返回 false，所以这里不能只靠 catch 入队——必须显式判断 [success] 为
     * false 时入队，否则弱网失败项永远进不了队列（这是修复 uploadLocal 被重新接活前隐藏
     * 的致命缺陷）。
     *
     * @param precomputedSha 调用方已算好的 sha256（如自动备份扫描时顺带算了）。非空时
     *        直接复用，省一次 SHA 计算；空则本方法内部按 data 现算。
     */
    suspend fun uploadLocal(
        mediaId: String,
        filename: String,
        data: ByteArray,
        isLivePhoto: Boolean = false,
        clientId: String = "fe-$mediaId",
        takenAt: Long = 0L,
        precomputedSha: String = ""
    ): Boolean {
        val sha = if (precomputedSha.isNotEmpty()) precomputedSha else sha256Hex(data)
        if (sha.isNotEmpty() && DedupStore.contains(sha)) {
            return true // 本端已知云端有此内容，秒传（不落盘不入队）
        }
        val success = try {
            // 透传 sha256 让后端做权威秒传；未命中则在后端落盘并由其实测 sha 入库。
            MediaService.uploadMedia(
                fileData = data,
                filename = filename,
                isLivePhoto = isLivePhoto,
                sha256 = sha,
                clientId = clientId,
                takenAt = takenAt
            )
        } catch (e: Exception) {
            // 极少数情况：MediaService.uploadMedia 内部 try 已吞异常返回 false，
            // 能抛到这里说明是请求构造/序列化层异常。同样入队待重放。
            OfflineQueueStore.enqueue(OfflineQueueItem(mediaId, filename, sha, isLivePhoto, takenAt, clientId))
            return false
        }
        if (success) {
            if (sha.isNotEmpty()) DedupStore.add(sha)
            return true
        }
        // 显式失败入队：HTTP 非 200 / 网络异常（被 uploadMedia 吞成 false）均落离线队列。
        OfflineQueueStore.enqueue(OfflineQueueItem(mediaId, filename, sha, isLivePhoto, takenAt, clientId))
        return false
    }

    /**
     * 重放持久化离线队列中的失败上传。
     *
     * 进程重启 / 网络恢复后由 [com.wgt.media.MediaViewModel] 在自动备份轮询每轮开头调用
     * （也随之在 [com.wgt.media.MediaViewModel.onSessionReady] 启动时触发一次）。逐项取出
     * [OfflineQueueStore] 快照 → 由调用方经 [getData] 从本地图库重读字节 → 走 [uploadLocal]
     * （带原 takenAt/clientId/sha）。成功撤离该项；失败保留待下一轮重试。
     *
     * 三种结局：
     * - 上传成功：[OfflineQueueStore.remove] 撤离。
     * - 取字节为 null（本地照片已被删）：无法重传，**撤离该项**避免永久重试死循环。
     * - 上传失败：[uploadLocal] 内部已重新入队（幂等，按 mediaId 去重），此处不撤离，
     *   保留待下轮。注意幂等性：[OfflineQueueStore.enqueue] 按 localMediaId 去重，
     *   故 uploadLocal 失败重新入队不会产生重复项。
     */
    suspend fun replayOfflineQueue(getData: suspend (String) -> ByteArray?) {
        val pending = OfflineQueueStore.snapshot()
        for (item in pending) {
            val data = getData(item.localMediaId) ?: run {
                // 图库已无此源（用户删了照片）：撤离队列，不再重试。
                OfflineQueueStore.remove(item.localMediaId)
                continue
            }
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
