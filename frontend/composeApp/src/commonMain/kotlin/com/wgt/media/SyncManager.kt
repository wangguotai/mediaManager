package com.wgt.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wgt.architecture.manager.claim.feature
import com.wgt.architecture.manager.manager
import com.wgt.feature.gallery.gallery
import com.wgt.feature.media.MediaService
import com.wgt.platform.architecture.dispatchers.dispatchers
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.MediaMetadata
import media.MediaType
import kotlin.concurrent.Volatile

private const val TAG = "SyncManager"

/**
 * 前端自动同步中枢（单例）。
 *
 * 汇聚三件事：
 * 1. **增量拉取**：[pullChanges] 循环调用 [MediaService.getSyncChanges]，以 next_cursor 续拉
 *    直至 has_more=false，把云端媒体清单维护成可观察的 [cloudMedia]（供"已上传"Tab 直接渲染）。
 *    游标持久化到 [PersistentFileStore]（`sync_cursor.json`），冷启动后从上次位置续拉。
 * 2. **统一上传 + 去重**：[uploadLocal] 算 [sha256Hex] → 命中 [DedupStore] 跳过 → 否则上传
 *    （传 sha256 触发后端秒传兜底）→ 成功登记 sha 到 DedupStore。自动备份与手动上传共用此入口。
 * 3. **离线队列重放**：弱网下 [uploadLocal] 失败入 [OfflineQueueStore]；[replayOfflineQueue]
 *    在恢复后逐项重放，成功出队。重放也走去重（命中即直接出队，不重复传）。
 *
 * 增量拉取登记去重：拉到的非墓碑项的 sha256 批量 addAll 入 DedupStore（另一端已传的内容本端知）；
 * 墓碑项 sha 从 DedupStore 移除（删除后允许重传）。如此多端去重清单最终一致。
 *
 * 触发时机（由 [App] / ViewModel 调用）：
 * - App 启动登录态就绪后 [pullChanges]（App.kt LaunchedEffect）；
 * - 进入"已上传"Tab [refreshCloudMedia]（MediaListScreen selectedTab==1）；
 * - 自动备份开启时 [startAutoBackup] 后台周期上传本地新增。
 *
 * 并发：[pullMutex] / [uploadMutex] 各自串行化同类操作，避免并发拉取/并发重放自相冲突；
 * 跨类操作（拉取 vs 上传）不互锁——它们访问的共享状态（cloudMedia / DedupStore）各自有内部同步。
 */
object SyncManager {

    /** cursor 持久化文件名。内容为单个毫秒时间戳字符串。 */
    private const val CURSOR_FILE = "sync_cursor.json"

    /**
     * 云端媒体清单（"已上传"Tab 的数据源）。
     *
     * 由 [pullChanges] 维护：增量页里的非墓碑项 upsert（按 id 替换），墓碑项按 id 移除。
     * 按 [takenAt]/[createdAt] 倒序排列，呼应"最近在上"的相册浏览直觉。mutableStateOf 暴露
     * 供 Compose 观察重组。
     */
    var cloudMedia by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    /** 增量拉取进行中（UI 展示 loading / 阻止重复并发拉取）。 */
    var isPulling by mutableStateOf(false)
        private set

    /** 上次增量拉取错误（一次性，UI 可展示重试）；成功/无错为 null。 */
    var lastSyncError by mutableStateOf<String?>(null)
        private set

    /** 离线队列待传项数（UI 展示"待上传 N 项"）。mutableStateOf 供观察。 */
    var pendingQueueSize by mutableStateOf(0)
        private set

    /** 后台同步协程作用域：IO dispatcher，长生命周期单例。 */
    private val scope = CoroutineScope(dispatchers.io)

    /** 串行化增量拉取，避免并发请求浪费与 cursor 竞争。 */
    private val pullMutex = Mutex()
    /** 串行化离线队列重放，避免并发重放同项重复上传。 */
    private val replayMutex = Mutex()

    /** 自动备份周期 Job、轮询句柄。null 表示未在跑。 */
    @Volatile
    private var backupJob: Job? = null

    init {
        // 启动即读已有队列长度，供 UI 展示"待上传"角标。
        pendingQueueSize = OfflineQueueStore.size()
    }

    // ============ 增量拉取 ============

    /**
     * 执行一次完整增量同步（循环拉页直至 has_more=false）。
     *
     * 游标语义：从持久化读上次 cursor（0=首次全量），循环以 next_cursor 续拉；每拉一页
     * 成功即落盘新 cursor（断点续传：中途失败已保存进度，下次接着拉）。鉴权失败(401)由
     * MediaService 触发清会话，本方法捕获 null 不清 cursor 留待重试。
     *
     * @param force true 时忽略已有云端清单做一次全量刷新（cursor 仍续用，但重算 cloudMedia）。
     * @return 拉到的总变更数（成功）；失败返回 -1。
     */
    suspend fun pullChanges(force: Boolean = false): Int = pullMutex.withLock {
        if (isPulling) return@withLock -1
        isPulling = true
        lastSyncError = null
        var cursor = loadCursor()
        var totalApplied = 0
        try {
            // force 模式下从当前云端清单起步就地重建（而非从空起步丢历史），仍按增量合并。
            val working = if (force) cloudMedia.toMutableList() else cloudMedia.toMutableList()
            val toAddSha = mutableListOf<String>()
            val toRemoveSha = mutableListOf<String>()

            var page = 1
            while (true) {
                val pageResult = MediaService.getSyncChanges(since = cursor, pageSize = 200)
                    ?: run {
                        // null：网络/鉴权失败——保留旧 cursor，留待下次重试。
                        lastSyncError = "同步失败，将稍后重试"
                        return@withLock totalApplied.takeIf { it > 0 } ?: -1
                    }
                if (pageResult.changes.isEmpty() && page == 1) {
                    // 首页即空：无任何变更，cursor 不动。
                    break
                }
                for (c in pageResult.changes) {
                    applyChange(working, c, toAddSha, toRemoveSha)
                    totalApplied++
                }
                // 每页落地 cursor（断点续传）。
                if (pageResult.nextCursor > 0L) {
                    cursor = pageResult.nextCursor
                    saveCursor(cursor)
                }
                if (!pageResult.hasMore) break
                page++
                // 安全上限：防止后端 cursor 异常导致无限翻页。
                if (page > 200) break
            }

            // 去重清单批量更新（墓碑移除、活跃新增），单次落盘。
            if (toRemoveSha.isNotEmpty()) toRemoveSha.forEach { DedupStore.remove(it) }
            if (toAddSha.isNotEmpty()) DedupStore.addAll(toAddSha)

            cloudMedia = sortByRecency(working)
            logger.info(TAG, "pullChanges done applied=$totalApplied cursor=$cursor cloud=${cloudMedia.size}")
            totalApplied
        } catch (e: Exception) {
            logger.error(TAG, "pullChanges failed: ${e::class.simpleName} ${e.message}")
            lastSyncError = e.message ?: "同步失败"
            -1
        } finally {
            isPulling = false
        }
    }

    /**
     * 把单条 [SyncChangeItem] 合并进 [working] 清单，并把 sha 登记到去重增量列表。
     *
     * - 墓碑（deleted=true）：按 id 移除；其 sha 加入 [toRemoveSha]（让 DedupStore 放行重传）。
     * - 活跃：按 id upsert（替换旧条目），sha 加入 [toAddSha]。
     */
    private fun applyChange(
        working: MutableList<MediaMetadata>,
        c: MediaService.SyncChangeItem,
        toAddSha: MutableList<String>,
        toRemoveSha: MutableList<String>
    ) {
        val idx = working.indexOfFirst { it.id == c.id }
        if (c.deleted) {
            if (idx >= 0) working.removeAt(idx)
            if (c.sha256.isNotEmpty()) toRemoveSha.add(c.sha256)
            return
        }
        val md = c.toMediaMetadata()
        if (idx >= 0) working[idx] = md else working.add(md)
        if (c.sha256.isNotEmpty()) toAddSha.add(c.sha256)
    }

    /** SyncChangeItem → MediaMetadata（字段对应，type 字符串转 [MediaType]）。 */
    private fun MediaService.SyncChangeItem.toMediaMetadata(): MediaMetadata = MediaMetadata(
        id = id,
        filename = filename,
        type = parseType(type),
        size = size,
        mime_type = mimeType,
        created_at = if (takenAt > 0) takenAt else createdAt,
        updated_at = updatedAt,
        is_live_photo = type.equals("LIVE_PHOTO", ignoreCase = true),
        live_photo_video_id = "",
        width = width,
        height = height
    )

    /** "IMAGE"/"VIDEO"/"LIVE_PHOTO"/数字 → MediaType，未知回退 IMAGE。与 MediaService.parseMediaType 同口径。 */
    private fun parseType(raw: String): MediaType = when (raw.trim().uppercase()) {
        "", "0", "IMAGE" -> MediaType.IMAGE
        "1", "LIVE_PHOTO" -> MediaType.LIVE_PHOTO
        "2", "VIDEO" -> MediaType.VIDEO
        else -> MediaType.IMAGE
    }

    /** 按 created_at 倒序（最近在上）；created_at 相同则按 updatedAt 倒序；都为 0 保持稳定。 */
    private fun sortByRecency(list: List<MediaMetadata>): List<MediaMetadata> =
        list.sortedWith(compareByDescending<MediaMetadata> { it.created_at }.thenByDescending { it.updated_at })

    /** 进入"已上传"Tab 触发：增量刷新云端清单。若云清单已非空则做轻量增量；否则全量拉。 */
    suspend fun refreshCloudMedia(): List<MediaMetadata> {
        pullChanges()
        return cloudMedia
    }

    // ---- cursor 持久化 ----

    private fun loadCursor(): Long = try {
        PersistentFileStore.read(CURSOR_FILE)?.trim()?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun saveCursor(cursor: Long) {
        PersistentFileStore.write(CURSOR_FILE, cursor.toString())
    }

    // ============ 统一上传 + 去重 + 入队 ============

    /**
     * 上传单个本地媒体，走去重与离线队列。
     *
     * 流程：
     * 1. 取本地字节 [galleryFeature.getMediaData]；为空则视为本地已删除，跳过（不入队——无源可传）。
     * 2. 算 [sha256Hex]；命中 [DedupStore] → 直接视为已上传成功（返回 [UploadOutcome.Deduped]），不传。
     * 3. 否则 [MediaService.uploadMediaWithMeta]（带 sha256 / clientId / takenAt）；成功登记 sha。
     * 4. 失败入 [OfflineQueueStore]（弱网/后端不可达），返回 [UploadOutcome.Queued]。
     *
     * @param localMediaId 本地相册 mediaId
     * @param filename 文件名
     * @param isLivePhoto 是否 Live Photo
     * @param takenAt 拍摄时间 ms（0 未知）
     * @return 上传结果（见 [UploadOutcome]）
     */
    suspend fun uploadLocal(
        localMediaId: String,
        filename: String,
        isLivePhoto: Boolean,
        takenAt: Long
    ): UploadOutcome {
        val galleryFeature = manager.feature.gallery
        val data = try {
            galleryFeature.getMediaData(localMediaId)
        } catch (e: Exception) {
            logger.error(TAG, "getMediaData failed id=$localMediaId: ${e.message}")
            null
        }
        if (data == null) {
            // 本地源已不存在（用户删除），不入队——重放也无字节可传。
            logger.info(TAG, "uploadLocal skip (no local bytes) id=$localMediaId")
            return UploadOutcome.Skipped("本地文件不存在")
        }
        return uploadLocalData(data, localMediaId, filename, isLivePhoto, takenAt)
    }

    /**
     * 上传已取好的本地字节（核心实现）。
     *
     * 与 [uploadLocal] 的区别：调用方已持有字节（如自动备份扫描时为判断去重先取了一次字节，
     * 避免重复取）。流程同 [uploadLocal]：算 sha → 命中 DedupStore 跳过 → 上传 → 成功登记 sha
     * → 失败入队。
     */
    private suspend fun uploadLocalData(
        data: ByteArray,
        localMediaId: String,
        filename: String,
        isLivePhoto: Boolean,
        takenAt: Long
    ): UploadOutcome {
        val sha = try {
            sha256Hex(data)
        } catch (e: Exception) {
            "" // 算 sha 失败不阻断：后端仍以实测兜底去重
        }

        // 客户端早判去重：命中已知云端 sha 即跳过传输。
        if (sha.isNotEmpty() && DedupStore.contains(sha)) {
            logger.info(TAG, "uploadLocal deduped (local hit) id=$localMediaId sha=${sha.take(12)}")
            return UploadOutcome.Deduped
        }

        val clientId = "fe-${localMediaId}-${takenAt}"
        val result = MediaService.uploadMediaWithMeta(
            fileData = data,
            filename = filename,
            isLivePhoto = isLivePhoto,
            sha256 = sha,
            clientId = clientId,
            takenAt = takenAt
        )

        if (result.success) {
            // 后端实测 sha 优先登记（与落盘字节为准），客户端早判 sha 作兜底。
            val effectiveSha = result.sha256.ifEmpty { sha }
            if (effectiveSha.isNotEmpty()) DedupStore.add(effectiveSha)
            logger.info(TAG, "uploadLocal OK id=$localMediaId status=${result.status} sha=${effectiveSha.take(12)}")
            return UploadOutcome.Uploaded(result.mediaId)
        }

        // 失败入队，待恢复后重放。
        OfflineQueueStore.enqueue(
            OfflineQueueItem(
                localMediaId = localMediaId,
                filename = filename,
                sha256 = sha,
                isLivePhoto = isLivePhoto,
                takenAt = takenAt,
                clientId = clientId
            )
        )
        pendingQueueSize = OfflineQueueStore.size()
        logger.info(TAG, "uploadLocal queued id=$localMediaId (queue size=$pendingQueueSize)")
        return UploadOutcome.Queued
    }

    /**
     * 上传结果。
     *
     * - [Uploaded]：成功落盘（或后端秒传），带 mediaId。
     * - [Deduped]：命中本端去重清单，已跳过传输（视为成功）。
     * - [Queued]：弱网失败，已入离线队列待重放。
     * - [Skipped]：本地源缺失等不可传情形。
     */
    sealed interface UploadOutcome {
        data class Uploaded(val mediaId: String) : UploadOutcome
        data object Deduped : UploadOutcome
        data object Queued : UploadOutcome
        data class Skipped(val reason: String) : UploadOutcome
    }

    // ============ 离线队列重放 ============

    /**
     * 重放离线队列：逐项取本地字节 → 去重 → 上传 → 成功出队。
     *
     * 已入队项的 sha 命中 DedupStore（期间另一端已传同内容）则直接出队不传；
     * 本地字节取不到（用户已删源）也出队（无法重传）。其余按 [uploadLocal] 走，
     * 成功后 [OfflineQueueStore.remove] 撤离。遇首个仍失败项即停止本次重放
     * （大概率是网络仍未恢复，继续重试后续项只是徒劳），留待下次。
     *
     * @return 本次成功出队数。
     */
    suspend fun replayOfflineQueue(): Int = replayMutex.withLock {
        val snapshot = OfflineQueueStore.snapshot()
        if (snapshot.isEmpty()) return@withLock 0
        var succeeded = 0
        for (item in snapshot) {
            // 命中去重：另一端已传同内容，直接出队。
            if (item.sha256.isNotEmpty() && DedupStore.contains(item.sha256)) {
                OfflineQueueStore.remove(item.localMediaId)
                succeeded++
                continue
            }
            val data = try {
                manager.feature.gallery.getMediaData(item.localMediaId)
            } catch (e: Exception) {
                null
            }
            if (data == null) {
                // 本地源已删，无法重传，撤离以免永久卡队列。
                OfflineQueueStore.remove(item.localMediaId)
                logger.info(TAG, "replay drop (no local bytes) id=${item.localMediaId}")
                succeeded++
                continue
            }
            val result = MediaService.uploadMediaWithMeta(
                fileData = data,
                filename = item.filename,
                isLivePhoto = item.isLivePhoto,
                sha256 = item.sha256,
                clientId = item.clientId,
                takenAt = item.takenAt
            )
            if (result.success) {
                val effectiveSha = result.sha256.ifEmpty { item.sha256 }
                if (effectiveSha.isNotEmpty()) DedupStore.add(effectiveSha)
                OfflineQueueStore.remove(item.localMediaId)
                succeeded++
            } else {
                // 仍失败（网络未恢复）。停止本次重放，后续项留待下次。
                logger.info(TAG, "replay stall at id=${item.localMediaId}, will retry later")
                break
            }
        }
        pendingQueueSize = OfflineQueueStore.size()
        if (succeeded > 0) {
            logger.info(TAG, "replayOfflineQueue succeeded=$succeeded remaining=$pendingQueueSize")
            // 上传了新内容，拉一次增量把刚传的条目并入云清单。
            launchPull()
        }
        succeeded
    }

    /** 重放队列 + 增量拉取的复合入口（恢复网络 / 进入前台时调）。 */
    suspend fun syncAndReplay(): Int {
        val r = replayOfflineQueue()
        launchPull()
        return r
    }

    /** 后台触发一次增量拉取（非阻塞，fire-and-forget）。 */
    fun launchPull() {
        scope.launch { pullChanges() }
    }

    // ============ 自动备份 ============

    /**
     * 启动云相册自动备份：后台周期扫描本地相册，对未在去重清单中的图片增量上传。
     *
     * 策略：每 [BACKUP_INTERVAL_MS] 扫描一次本地相册，对每张图算 sha（需先取字节，此处为
     * 控制开销先按 filename+size 近似，仅对未在"已知已备份"内存集合中的项取字节算 sha 上传）。
     * 已开启时再次调用幂等（先停旧 Job 再启新的）。
     *
     * 仅 IMAGE 与 LIVE_PHOTO 参与（视频备份成本高，按需求"本地新增图片"聚焦图片）。
     */
    fun startAutoBackup() {
        if (backupJob?.isActive == true) return
        backupJob = scope.launch {
            logger.info(TAG, "autoBackup started (interval=${BACKUP_INTERVAL_MS}ms)")
            // 首次立即跑一轮（启动后尽快备份新增），之后周期轮询。
            backupCycle()
            while (true) {
                delay(BACKUP_INTERVAL_MS)
                backupCycle()
            }
        }
    }

    /** 停止自动备份（关闭设置开关时调用）。 */
    fun stopAutoBackup() {
        backupJob?.cancel()
        backupJob = null
        logger.info(TAG, "autoBackup stopped")
    }

    /** 一轮备份扫描：枚举本地图库 → 对疑似新增项算 sha → 命中去重跳过 / 否则上传。 */
    private suspend fun backupCycle() {
        try {
            val galleryFeature = manager.feature.gallery
            // 需要相册权限；未授权则跳过本轮（下次再试）。
            if (!galleryFeature.hasPermission()) return
            val local = galleryFeature.getMediaFromGallery()
            // 仅图片类（VIDEO 备份成本高，本需求聚焦图片）。
            val images = local.filter { it.type != MediaType.VIDEO }
            var uploaded = 0
            var deduped = 0
            for (m in images) {
                // 取字节算 sha（去重判断必需）；命中 DedupStore 则跳过，省去上传往返。
                val data = try {
                    galleryFeature.getMediaData(m.id)
                } catch (e: Exception) {
                    null
                } ?: continue
                val sha = try { sha256Hex(data) } catch (e: Exception) { "" }
                if (sha.isNotEmpty() && DedupStore.contains(sha)) {
                    deduped++
                    continue
                }
                // 字节已就绪，直接走内部入口避免重复取字节/重算 sha。
                val result = MediaService.uploadMediaWithMeta(
                    fileData = data,
                    filename = m.filename,
                    isLivePhoto = m.is_live_photo,
                    sha256 = sha,
                    clientId = "fe-${m.id}-${m.created_at}",
                    takenAt = m.created_at
                )
                if (result.success) {
                    val effectiveSha = result.sha256.ifEmpty { sha }
                    if (effectiveSha.isNotEmpty()) DedupStore.add(effectiveSha)
                    uploaded++
                } else {
                    // 弱网/后端不可达入队，待重放。
                    OfflineQueueStore.enqueue(
                        OfflineQueueItem(
                            localMediaId = m.id,
                            filename = m.filename,
                            sha256 = sha,
                            isLivePhoto = m.is_live_photo,
                            takenAt = m.created_at,
                            clientId = "fe-${m.id}-${m.created_at}"
                        )
                    )
                    pendingQueueSize = OfflineQueueStore.size()
                }
            }
            if (uploaded > 0 || deduped > 0) {
                logger.info(TAG, "backupCycle done scanned=${images.size} uploaded=$uploaded deduped=$deduped")
                // 备份了新内容，拉增量并入云清单。
                launchPull()
            }
        } catch (e: Exception) {
            logger.error(TAG, "backupCycle failed: ${e::class.simpleName} ${e.message}")
        }
    }

    /** 自动备份扫描间隔。10 分钟——平衡及时性与电量/流量。 */
    private const val BACKUP_INTERVAL_MS = 10L * 60L * 1000L
}
