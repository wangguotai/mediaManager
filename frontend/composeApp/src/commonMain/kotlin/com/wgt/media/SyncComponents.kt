package com.wgt.media

import com.wgt.common.util.sha256
import com.wgt.platform.logger.logger
import kotlin.time.Clock

private const val TAG = "SyncComponents"

/**
 * 已上传内容的 SHA-256 去重集合 —— 自动备份时避免把同一张图片重复传到云。
 *
 * 双层来源：
 * 1. [loadFromSync] —— 拉取 [MediaService.getSyncChanges] 后，把返回项的 sha256 灌入，
 *    使"别的设备/之前已传过的图"在本次自动备份时被判为已存在、跳过上传。
 * 2. [markUploaded] —— 本次本设备成功上传后即时登记，避免同一会话内重复判重。
 *
 * 持久化经 [SettingsStorage] 落盘（逗号分隔），冷启动 [load] 读回——否则重启后内存集合
 * 清空、第一次自动备份会重传全库。集合仅记非空 sha256；空指纹（后端未计算/旧数据）不入表，
 * 不影响其上传（无指纹无法去重，按原逻辑上传，按上传成功后由后端去重兜底）。
 *
 * commonMain 安全：不依赖 java.* 与 android.*；持久化用 [SettingsStorage] 抽象。
 */
class Sha256Dedup(private val storage: SettingsStorage) {
    private val seen: MutableSet<String> = HashSet()

    init {
        load()
    }

    /** 读取持久化的已传指纹集合到内存。 */
    private fun load() {
        val raw = storage.getString(SettingsKeys.UPLOADED_SHA256, "")
        if (raw.isEmpty()) return
        raw.split(',').filter { it.isNotEmpty() }.forEach { seen.add(it) }
        logger.info(TAG, "dedup loaded ${seen.size} hashes")
    }

    /** 持久化当前集合。批量上传后调用一次，避免逐条写盘。 */
    private fun persist() {
        storage.putString(SettingsKeys.UPLOADED_SHA256, seen.joinToString(","))
    }

    /** 该指纹是否已登记为已上传。空指纹恒为 false（无指纹不去重）。 */
    fun contains(hash: String): Boolean = hash.isNotEmpty() && seen.contains(hash)

    /** 登记一个已上传指纹并落盘。空指纹忽略。 */
    fun markUploaded(hash: String) {
        if (hash.isEmpty() || seen.add(hash)) persist()
    }

    /**
     * 把一次增量同步返回的变更项指纹灌入集合。
     *
     * 仅登记非删除（[deleted]=false）项的 sha256；删除项的指纹不剔除——保留历史记录
     * 无害（用户删了又传同图，后端会按 sha256 去重，前端跳过上传也是合理行为，
     * 避免把已删图重新传上去）。批量灌入后统一持久化一次。
     */
    fun loadFromSync(changes: List<com.wgt.feature.media.MediaService.SyncChange>) {
        var changed = false
        for (c in changes) {
            if (!c.deleted && c.sha256.isNotEmpty() && seen.add(c.sha256)) changed = true
        }
        if (changed) {
            persist()
            logger.info(TAG, "dedup merged from sync, total=${seen.size}")
        }
    }

    /** 当前已登记指纹数（调试/展示用）。 */
    val size: Int get() = seen.size
}

/**
 * 单条待上传离线任务（UI 投影模型）。
 *
 * **注意：此 data class 仅用于 UI 展示快照，不再是上传待办的数据源。** V5 上传路径统一后，
 * 唯一的持久化待办表是 [OfflineQueueStore]（落盘 JSON，进程重启不丢）；[OnlineUploadQueue]
 * 退化为其只读镜像，供设置页"待上传 N 项"等 UI 展示。
 *
 * 字段补齐 PRD §2.4 要求的元数据，避免重放丢拍摄时间/客户端幂等键/本地引用。这些字段
 * 与 [OfflineQueueItem] 一一对应，由 [OnlineUploadQueue.snapshot] 从持久化层映射而来。
 *
 * @param mediaId 本地图库媒体 id（galleryFeature.getMediaData 取字节用）
 * @param filename 原始文件名
 * @param sha256 内容指纹（入队前算好，重放时复用做去重判断）
 * @param isLivePhoto 是否 Live Photo
 * @param takenAt 拍摄时间 ms（排序/时序用，0 表未知）
 * @param clientId 客户端幂等键（device register 的 id），重放透传
 * @param enqueuedAt 入队时刻（UI 展示"积压多久"用；[OfflineQueueItem] 未存此字段，
 *        故此处为读取快照时刻，仅近似）
 */
data class PendingUpload(
    val mediaId: String,
    val filename: String,
    val sha256: String,
    val isLivePhoto: Boolean,
    val takenAt: Long = 0L,
    val clientId: String = "",
    val enqueuedAt: Long
)

/**
 * 离线上传队列（UI 投影层）—— 弱网失败入队、恢复后重放的**唯一持久化表**是 [OfflineQueueStore]。
 *
 * V5 上传路径统一后，本类不再是内存待办表（旧实现用 [mutableStateListOf]，杀进程即丢，
 * 与 PRD §2.4"离线队列持久化"要求冲突）。现退化为 [OfflineQueueStore] 的只读视图：
 * - [size] / [isNotEmpty] / [items] 均直接读持久化层，UI（设置页"待上传 N 项"）观察到的
 *   永远是落盘真实值，进程重启后仍准确。
 * - 入队/出队/重放由 [SyncManager] 经 [OfflineQueueStore] 直接操作，不经本类，避免两套语义
 *   混乱（旧 UI 队列与持久化队列计数不一致的历史问题）。
 * - [clear] 转发到 [OfflineQueueStore.clear]（登出/切账号时丢弃本端待传项）。
 *
 * [syncFromStore] 在 UI 需要刷新快照时调用，把持久化项映射为 [PendingUpload] 投影。
 * 不持有可变内存集合——所有状态以 [OfflineQueueStore] 为准。
 */
class UploadQueue {
    /** 当前待上传条目（UI 观察用，从持久化层映射）。 */
    val items: List<PendingUpload> get() = snapshot()

    /** 队列是否非空。 */
    val isNotEmpty: Boolean get() = OfflineQueueStore.size() > 0

    /** 待上传条数。 */
    val size: Int get() = OfflineQueueStore.size()

    /** 从持久化层取快照并映射为 UI 投影模型。 */
    private fun snapshot(): List<PendingUpload> =
        OfflineQueueStore.snapshot().map {
            PendingUpload(
                mediaId = it.localMediaId,
                filename = it.filename,
                sha256 = it.sha256,
                isLivePhoto = it.isLivePhoto,
                takenAt = it.takenAt,
                clientId = it.clientId,
                enqueuedAt = Clock.System.now().toEpochMilliseconds()
            )
        }

    /** 清空队列（如切账号/手动取消）—— 转发到持久化层。 */
    fun clear() = OfflineQueueStore.clear()
}

/**
 * 计算给定字节的 SHA-256 内容指纹，供去重与上传幂等使用。
 * commonMain 安全包装，转发到 [com.wgt.common.util.sha256]。
 */
fun computeSha256(bytes: ByteArray): String = sha256(bytes)
