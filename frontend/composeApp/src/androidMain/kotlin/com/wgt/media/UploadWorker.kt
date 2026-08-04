package com.wgt.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wgt.architecture.manager.claim.feature
import com.wgt.architecture.manager.manager
import com.wgt.feature.gallery.gallery
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger

/**
 * 后台上传 Worker（PRD-v8 §2.2）—— WorkManager 驱动的断点续传上传。
 *
 * 由 [UploadQueueManager]（androidMain actual）以 OneTimeWorkRequest 入队，
 * WorkManager 持久化 WorkRequest 到 SQLite，App 前台被杀后系统按约束（联网）
 * 与退避策略在后台拉起本 Worker 继续上传——这正是"前台被杀也能继续上传"的核心。
 *
 * doWork 流程（与 [MediaViewModel.uploadSelectedLocalMedia] 前台逻辑对齐，复用
 * [MediaService.uploadMedia] 的 sha256 秒传 + [Sha256Dedup] 本端去重）：
 * 1. 从 inputData 解析 mediaId 列表 + 每项的 filename/isLivePhoto/takenAt 元数据；
 * 2. 逐项经 [com.wgt.feature.gallery.GalleryFeature.getMediaData] 取字节流；
 * 3. 算 sha256 → 先查 [Sha256Dedup.shared] 本端秒传短路 → 否则调
 *    [MediaService.uploadMedia]（透传 sha256/client_id/taken_at 走后端权威秒传）；
 * 4. 成功 → [Sha256Dedup.shared.markUploaded] 登记指纹，[setProgress] 推进进度；
 * 5. 单项失败计入 failedCount，不中断其余项（容错：坏图不阻塞整批）；
 * 6. 全部处理完：全成功 → [Result.success]；有失败 → [Result.retry]，
 *    WorkManager 按指数退避（默认 30s~5h）重试，最多 [MAX_RETRY_ATTEMPTS]（5）次后
 *    转为 FAILED 终态，由 [UploadQueueManager] 映射为 [BackgroundUploadState.Failed]。
 *
 * 注意：与 [com.wgt.media.SyncManager.uploadLocal] 的离线队列路径**互不交叉**——
 * 本 Worker 失败时由 WorkManager 自身重试（Result.retry），**不**入 OfflineQueueStore，
 * 避免两套重试机制叠加产生重复上传。OfflineQueueStore 服务于自动备份弱网兜底，
 * 本 Worker 服务于用户主动批量上传，职责分离。
 *
 * 字节获取：经全局 [manager.feature.gallery] 单例访问 GalleryFeature，与
 * [MediaViewModel] 同一访问路径，复用其已初始化的 photoGalleryService（Android 端
 * 基于 MediaStore ContentResolver，mediaId 为 _ID 字符串）。
 *
 * inputData 编码：数组以分隔符 "\u0001" 拼接成单字符串传递（WorkManager Data 不支持
 * 自定义对象数组，仅支持基础类型/String 数组；用单字符串 + 分隔符比多 key 数组更紧凑，
 * 且 \u0001 在文件名中不会出现，安全）。每项四字段以 "\u0002" 分隔。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val items = parseInput(inputData)
        if (items.isEmpty()) {
            logger.info(TAG, "doWork: empty input, returning success")
            return Result.success(progressOfWorkData(0, 0))
        }

        val galleryFeature = manager.feature.gallery
        // 直接从 SettingsStorage 读 deviceId，绕过 Compose mutableStateOf（线程安全：
        // WorkManager 后台线程 / 进程重建场景下 SettingsState 可能未初始化）
        val clientId = SettingsStorage().getString(SettingsKeys.DEVICE_ID, "")
        val total = items.size
        var completed = 0
        var failed = 0

        logger.info(TAG, "doWork start: total=$total attempt=$runAttemptCount clientId=$clientId")

        for ((index, item) in items.withIndex()) {
            // WorkManager 取消信号检查：系统/用户取消时及时停止，不跑完整批。
            if (isStopped) {
                logger.info(TAG, "doWork cancelled by WorkManager at ${index + 1}/$total")
                return Result.failure(progressOfWorkData(completed, total))
            }
            try {
                val data = galleryFeature.getMediaData(item.mediaId)
                if (data == null) {
                    // 图库已无此源（用户删了照片）：无法上传，计失败但不整体中断。
                    logger.warning(TAG, "doWork: mediaId=${item.mediaId} data=null (deleted?)")
                    failed++
                    continue
                }

                val hash = sha256Hex(data)

                // 本端秒传短路：他设备/本会话已传过同内容，跳过网络往返。
                if (hash.isNotEmpty() && Sha256Dedup.shared.contains(hash)) {
                    completed++
                    setProgress(progressOfWorkData(completed, total))
                    continue
                }

                val success = MediaService.uploadMedia(
                    fileData = data,
                    filename = item.filename,
                    isLivePhoto = item.isLivePhoto,
                    sha256 = hash,
                    clientId = clientId,
                    takenAt = item.takenAt
                )

                if (success) {
                    if (hash.isNotEmpty()) Sha256Dedup.shared.markUploaded(hash)
                    completed++
                    logger.info(TAG, "doWork: uploaded ${item.mediaId} (${index + 1}/$total)")
                } else {
                    failed++
                    logger.warning(TAG, "doWork: upload failed ${item.mediaId} (HTTP non-200/network)")
                }
            } catch (e: Exception) {
                // 单项异常不中断整批：坏图/解码失败记入 failed，继续下一项。
                failed++
                logger.error(TAG, "doWork: exception on ${item.mediaId}: ${e::class.simpleName} ${e.message}")
            }

            // 每处理完一项即报告进度，UI 经 WorkInfo.progress 实时观察。
            setProgress(progressOfWorkData(completed, total))
        }

        logger.info(TAG, "doWork done: completed=$completed failed=$failed total=$total attempt=$runAttemptCount")

        // 全部成功 → success；有失败 → retry（WorkManager 指数退避，达上限转 FAILED）。
        // 注意：即便部分项的 mediaId 已被删除（永久失败），retry 仍会重试其余可恢复项；
        // 达 MAX_RETRY_ATTEMPTS 后 WorkManager 转 FAILED，UploadQueueManager 映射为 Failed 状态。
        return if (failed == 0) {
            Result.success(progressOfWorkData(completed, total))
        } else if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
            // 达重试上限，停止重试，标记失败终态——WorkInfo 进入 FAILED，
            // UploadQueueManager.mapWorkInfo 映射为 BackgroundUploadState.Failed。
            logger.warning(TAG, "doWork: max retries ($MAX_RETRY_ATTEMPTS) reached, marking FAILURE")
            Result.failure(progressOfWorkData(completed, total))
        } else {
            Result.retry()
        }
    }

    /**
     * 把 inputData 解析回 [List<BackgroundUploadItem>]。
     *
     * 编码格式见类注释：单字符串 + "\u0001" 分项 + "\u0002" 分字段。
     * 空或格式错误返回空列表（Worker 直接 success 收尾，避免空转重试）。
     */
    private fun parseInput(data: Data): List<BackgroundUploadItem> {
        val raw = data.getString(KEY_ITEMS) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(ITEM_SEPARATOR)
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(FIELD_SEPARATOR)
                if (parts.size < 4) return@map null
                BackgroundUploadItem(
                    mediaId = parts[0],
                    filename = parts[1],
                    isLivePhoto = parts[2] == "1",
                    takenAt = parts[3].toLongOrNull() ?: 0L
                )
            }
            .filterNotNull()
    }

    /** 构造 setProgress / Result 携带的进度 WorkData（completed/total）。 */
    private fun progressOfWorkData(completed: Int, total: Int): Data =
        workDataOf(KEY_PROGRESS_COMPLETED to completed, KEY_PROGRESS_TOTAL to total)

    companion object {
        private const val TAG = "UploadWorker"

        /** WorkManager 最大重试次数（含首次尝试）。超此转 FAILED 终态。 */
        const val MAX_RETRY_ATTEMPTS = 5

        /** inputData key：序列化的待上传项列表（分隔符拼接字符串）。 */
        const val KEY_ITEMS = "bg_upload_items"

        /** progress key：已成功上传数。 */
        const val KEY_PROGRESS_COMPLETED = "bg_upload_completed"

        /** progress key：本批总数。 */
        const val KEY_PROGRESS_TOTAL = "bg_upload_total"

        /** 项与项之间的分隔符（\u0001 不会出现在文件名/mediaId 中）。 */
        const val ITEM_SEPARATOR = "\u0001"

        /** 单项内字段间分隔符。 */
        const val FIELD_SEPARATOR = "\u0002"

        /**
         * 把 [List<BackgroundUploadItem>] 序列化为 WorkManager Data（供 OneTimeWorkRequest 构造）。
         * 与 [parseInput] 对称。
         */
        fun itemsToWorkData(items: List<BackgroundUploadItem>): Data {
            val raw = items.joinToString(ITEM_SEPARATOR) { item ->
                listOf(
                    item.mediaId,
                    item.filename,
                    if (item.isLivePhoto) "1" else "0",
                    item.takenAt.toString()
                ).joinToString(FIELD_SEPARATOR)
            }
            return workDataOf(KEY_ITEMS to raw, KEY_PROGRESS_TOTAL to items.size)
        }
    }
}
