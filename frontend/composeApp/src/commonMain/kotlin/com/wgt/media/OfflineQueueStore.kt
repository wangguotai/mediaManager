package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 离线上传队列项。
 *
 * 弱网下上传失败时入队；恢复网络后由 [SyncManager] 重放。仅存本地相册引用与元数据，
 * **不存图片字节**——字节在重放时即时从本地相册 ([galleryFeature.getMediaData]) 重读，
 * 这样队列文件极小且不占磁盘；代价是若用户已删除该本地照片，重放取字节为 null，
 * 该项标记失败并跳过撤离队列（无法重传已不存在的源）。
 *
 * [clientId] 复合自 mediaId+纳秒偏移（由入队方保证唯一），作为后端幂等键上传时透传，
 * 避免重放导致重复落库。
 *
 * @param localMediaId 本地相册 mediaId（重放时据此取字节）
 * @param filename 原始文件名（后端取扩展名）
 * @param sha256 内容指纹（命中 Sha256Dedup 可直接跳过，省去取字节+上传）
 * @param isLivePhoto 是否为 Live Photo
 * @param takenAt 拍摄时间 ms（排序/时序用，0 表未知）
 * @param clientId 客户端幂等键
 */
data class OfflineQueueItem(
    val localMediaId: String,
    val filename: String,
    val sha256: String,
    val isLivePhoto: Boolean,
    val takenAt: Long,
    val clientId: String
)

/**
 * 离线上传队列持久化存储。
 *
 * 弱网入队：[enqueue] 把失败上传的 [OfflineQueueItem] 追加落盘（JSON 数组）；恢复网络后
 * [SyncManager] 调用 [snapshot] 取全部待传项，逐项重放，成功即 [remove] 出队。
 *
 * 实现：JSON 数组文件（`offline_queue.json`，经 [PersistentFileStore]）。不引 SQLDelight，
 * 与本仓库"零新依赖、编译稳"的取舍一致——队列规模小（弱网期间积压项通常数十以内），
 * 线性扫描 + 整文件覆盖足够；行为对等於 SQLite 队列表的入队/出队/重放语义。
 *
 * 线程模型：[lock] 串行化所有读写落盘，可在任意线程调用。
 */
object OfflineQueueStore {

    private const val TAG = "OfflineQueueStore"
    private const val FILE_NAME = "offline_queue.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** 追加入队一项；返回入队后队列长度。落盘为追加后完整数组（覆盖写）。 */
    fun enqueue(item: OfflineQueueItem): Int {
        val cur = readFromDisk()
        if (cur.any { it.localMediaId == item.localMediaId }) return cur.size
        val next = cur + item
        persist(next)
        return next.size
    }

    /**
     * 取队列快照（不可变副本）供重放；不修改队列。
     * 重放成功后对每项调 [remove] 撤离。
     */
    fun snapshot(): List<OfflineQueueItem> {
        return readFromDisk()
    }

    /** 队列当前长度（UI 展示"待上传 N 项"用）。 */
    fun size(): Int {
        return readFromDisk().size
    }

    /**
     * 从队列移除一项（重放成功后调用）。按 [localMediaId] 匹配。
     * 幂等：不存在则无操作。
     */
    fun remove(localMediaId: String) {
        val cur = readFromDisk()
        val next = cur.filterNot { it.localMediaId == localMediaId }
        if (next.size != cur.size) persist(next)
    }

    /**
     * 清空队列。用于登出/重置时丢弃本端待传项。
     */
    fun clear() {
        persist(emptyList())
    }

    // ---- 序列化 ----

    private fun readFromDisk(): List<OfflineQueueItem> = try {
        val text = PersistentFileStore.read(FILE_NAME) ?: return emptyList()
        val arr = Json.parseToJsonElement(text).jsonArray
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["local_media_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            OfflineQueueItem(
                localMediaId = id,
                filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: "image.jpg",
                sha256 = o["sha256"]?.jsonPrimitive?.contentOrNull ?: "",
                isLivePhoto = o["is_live_photo"]?.jsonPrimitive?.booleanOrNull ?: false,
                takenAt = o["taken_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                clientId = o["client_id"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    } catch (e: Exception) {
        logger.error(TAG, "readFromDisk failed: ${e.message}")
        emptyList()
    }

    private fun persist(items: List<OfflineQueueItem>) {
        try {
            val arr = buildJsonArray {
                items.forEach { it ->
                    add(buildJsonObject {
                        put("local_media_id", JsonPrimitive(it.localMediaId))
                        put("filename", JsonPrimitive(it.filename))
                        put("sha256", JsonPrimitive(it.sha256))
                        put("is_live_photo", JsonPrimitive(it.isLivePhoto))
                        put("taken_at", JsonPrimitive(it.takenAt))
                        put("client_id", JsonPrimitive(it.clientId))
                    })
                }
            }
            PersistentFileStore.write(FILE_NAME, json.encodeToString(JsonArray.serializer(), arr))
        } catch (e: Exception) {
            logger.error(TAG, "persist failed: ${e.message}")
        }
    }
}
