package com.wgt.media

import androidx.compose.runtime.mutableStateListOf
import com.wgt.common.util.sha256
import com.wgt.platform.logger.logger

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
 * 单条待上传离线任务。
 *
 * 自动备份在弱网下上传失败时入队。持有上传所需全部材料：图库内 mediaId（用于取字节）、
 * 文件名、内容指纹、Live Photo 标记。bytes 暂存内存；进程被杀则丢失（任务约定内存队列）。
 *
 * @param mediaId 本地图库媒体 id（galleryFeature.getMediaData 取字节用）
 * @param filename 原始文件名
 * @param sha256 内容指纹（入队前算好，重放时复用做去重判断）
 * @param isLivePhoto 是否 Live Photo
 */
data class PendingUpload(
    val mediaId: String,
    val filename: String,
    val sha256: String,
    val isLivePhoto: Boolean,
    val enqueuedAt: Long
)

/**
 * 离线上传队列 —— 弱网失败入队，恢复后重放。
 *
 * 任务明确"简单内存队列即可，不需要 SQLite"：用 [mutableStateListOf] 让 UI 可观察
 * 队列长度（设置页/已上传页展示"待上传 N 项"）。进程重启丢失，可接受——下次自动备份
 * 轮询会重新检测未传项并入队。
 *
 * 不在此类内自行重放：网络恢复时机与字节获取由 [MediaViewModel] 编排（它持有 galleryFeature
 * 与网络状态），本类只管进队/出队/查询。幂等：入队时按 mediaId 去重，避免同一项多次入队。
 */
class UploadQueue {
    private val pending = mutableStateListOf<PendingUpload>()

    /** 当前待上传条目（UI 观察用）。 */
    val items: List<PendingUpload> get() = pending

    /** 队列是否非空。 */
    val isNotEmpty: Boolean get() = pending.isNotEmpty()

    /** 待上传条数。 */
    val size: Int get() = pending.size

    /**
     * 入队一条待上传项。同一 mediaId 已在队列则不重复加入（幂等）。
     */
    fun enqueue(item: PendingUpload) {
        if (pending.none { it.mediaId == item.mediaId }) {
            pending.add(item)
            logger.info(TAG, "enqueue ${item.filename} (queue=${pending.size})")
        }
    }

    /** 取出并移除队首项；队列空返回 null。 */
    fun dequeue(): PendingUpload? =
        if (pending.isEmpty()) null else pending.removeAt(0)

    /** 按 mediaId 移除一条（上传成功后清理）。 */
    fun remove(mediaId: String) {
        pending.removeAll { it.mediaId == mediaId }
    }

    /** 清空队列（如切账号/手动取消）。 */
    fun clear() = pending.clear()
}

/**
 * 计算给定字节的 SHA-256 内容指纹，供去重与上传幂等使用。
 * commonMain 安全包装，转发到 [com.wgt.common.util.sha256]。
 */
fun computeSha256(bytes: ByteArray): String = sha256(bytes)
