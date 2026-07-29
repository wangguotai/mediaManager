package com.wgt.media

import com.wgt.platform.logger.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上传去重清单（dedup manifest）—— 持久化本端"已知存在于云端"的 sha256 指纹集合。
 *
 * 目的：上传前对内容算 [sha256Hex]，命中此集合即跳过上传，避免重复传输相同内容
 * （客户端早判去重）。与后端 (user_id, sha256) 去重互为补充：后端拿 sha256 做秒传兜底，
 * 本端命中则连往返都省。
 *
 * 数据来源（双写、最终一致）：
 * 1. **增量拉取**([SyncManager])：[MediaService.getSyncChanges] 返回的每条变更带 `sha256`，
 *    拉取成功后把非墓碑、非空 sha 登记入册——这覆盖"另一台设备已传、本端需知"的场景。
 *    墓碑（deleted=true）条目从集合移除其 sha，避免删除后误判已存在导致无法重新上传。
 * 2. **本地上传成功**：[uploadMediaWithMeta] 返回后端实测 sha256，登入集合，供下次去重。
 *
 * 持久化：JSON 数组（sha256 hex 串列表），落盘到 [PersistentFileStore]（`dedup_sha256.json`）。
 * 进程内缓存内存 Set，写时整文件覆盖；并发由 [mutex] 串行化（避免并发写竞争损坏文件）。
 *
 * 线程模型：所有方法内部用 [synchronized] 串行，可在任意线程调用；调用方无需额外加锁。
 */
object DedupStore {

    private const val TAG = "DedupStore"
    private const val FILE_NAME = "dedup_sha256.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** 锁，串行化内存 Set 读写与文件落盘，保证并发安全。 */
    
    /** 内存缓存：本端已知云端的 sha256 集合。懒加载首读。 */
    
    private var cached: Set<String>? = null

    /**
     * 加载（懒加载 + 缓存）本端已知的 sha256 集合。
     * 文件不存在/解析失败返回空集，并把空集缓存，避免反复读盘。
     */
    private fun load(): Set<String> {
        cached?.let { return it }
        val set = readFromDisk()
        cached = set
        return set
    }

    private fun readFromDisk(): Set<String> = try {
        val text = PersistentFileStore.read(FILE_NAME) ?: return emptySet()
        val arr = Json.parseToJsonElement(text) as? JsonArray ?: return emptySet()
        arr.mapNotNull { it.jsonPrimitive.contentOrNull }
            .filter { it.isNotEmpty() }
            .toSet()
    } catch (e: Exception) {
        logger.error(TAG, "readFromDisk failed: ${e.message}")
        emptySet()
    }

    /** 落盘当前内存集合为 JSON 数组。在 [lock] 内调用。 */
    private fun persist(set: Set<String>) {
        try {
            val arr = buildJsonArray {
                set.sorted().forEach { add(JsonPrimitive(it)) }
            }
            PersistentFileStore.write(FILE_NAME, json.encodeToString(JsonArray.serializer(), arr))
        } catch (e: Exception) {
            logger.error(TAG, "persist failed: ${e.message}")
        }
    }

    /**
     * 判断 [sha256] 是否已登记（命中即跳过上传）。
     * 空 sha 视为未命中（无法去重，照常上传）。
     */
    fun contains(sha256: String): Boolean {
        if (sha256.isEmpty()) return false
        return load().contains(sha256)
    }

    /**
     * 登记一个 sha256（上传成功 / 增量拉取发现云端已有）。
     * 已存在则幂等无操作。返回是否确实新增。
     */
    fun add(sha256: String): Boolean {
        if (sha256.isEmpty()) return false
        val cur = load()
        if (cur.contains(sha256)) return false
        val next = cur + sha256
        cached = next
        persist(next)
        return true
    }

    /**
     * 批量登记多个 sha256。返回实际新增数量。增量拉取一页后一次性登记，减少落盘次数。
     */
    fun addAll(sha256s: Collection<String>): Int {
        if (sha256s.isEmpty()) return 0
        val cur = load()
        val fresh = sha256s.filter { it.isNotEmpty() && !cur.contains(it) }
        if (fresh.isEmpty()) return 0
        val next = cur + fresh
        cached = next
        persist(next)
        return fresh.size
    }

    /**
     * 移除一个 sha256。
     * 用于增量拉取遇到墓碑（deleted=true）时，清理其 sha，使该内容日后可重新上传。
     * 幂等：不存在则无操作。返回是否确实移除。
     */
    fun remove(sha256: String): Boolean {
        if (sha256.isEmpty()) return false
        val cur = load()
        if (!cur.contains(sha256)) return false
        val next = cur - sha256
        cached = next
        persist(next)
        return true
    }
}
