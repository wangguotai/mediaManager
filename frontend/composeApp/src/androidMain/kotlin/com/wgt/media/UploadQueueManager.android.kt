package com.wgt.media

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Android 平台 [UploadQueueManager] actual 实现 —— 基于 WorkManager。
 *
 * 入队：[enqueueUploads] 把 [BackgroundUploadItem] 列表经 [UploadWorker.itemsToWorkData]
 * 序列化进 OneTimeWorkRequest 的 inputData，加 [WORK_TAG] 入 WorkManager 队列。约束：
 * - [NetworkType.CONNECTED]：仅联网时执行（断网时挂起等待，符合"弱网恢复后续传"预期）；
 * - [BackoffPolicy.EXPONENTIAL]：失败按指数退避（10s→20s→40s...），上限 5 次
 *   （[UploadWorker.MAX_RETRY_ATTEMPTS]），达上限 WorkManager 转 FAILED 终态；
 * - 普通 OneTimeWorkRequest（非 expedited）：expedited 受前台服务配额限制且与"批量长任务"
 *   语义不符。普通 Request 在系统调度下后台执行，App 被杀后 WorkManager 持久化
 *   WorkRequest 到 SQLite，进程重启时自动恢复执行——这正是"前台被杀也能继续上传"的保障。
 *
 * 进度反馈：.enqueueUploads 后用 [workManager.getWorkInfoByIdFlow] observe 该 WorkRequest，
 * 在 [observeScope] 中 collect，把 WorkInfo State（RUNNING/SUCCEEDED/FAILED）+ progress
 * （completed/total）映射为 [BackgroundUploadState]，推入 [_uploadState]，经 [uploadState]
 * 上抛给 [MediaViewModel] 转发 UI。
 *
 * 一次入队 = 一个 WorkRequest（一批 items 在单 Worker 内顺序上传）。进度语义清晰
 * （completed/total 对应本批），减少 WorkManager 调度开销；doWork 内单项失败不中断
 * （见 [UploadWorker.doWork]），仅最终 retry 时整批重跑，WorkManager 退避可控。
 *
 * 多批并发：每次 enqueue 产生独立 WorkRequest，WorkManager 默认串行执行同 tag 的
 * OneTimeWorkRequest，避免并发上传打满网络。[uploadState] 反映最近一批状态
 * （每次 enqueue 立即覆盖为 Running，observe 接管后续状态推进）。
 *
 * observe 生命周期：[observeScope] 为应用级 SupervisorJob，不随 VM 销毁——因为
 * 后台上传本就要求 App 被杀后状态可恢复（虽然被杀后 in-process StateFlow 自然消失，
 * 但 WorkManager 持久化的 WorkRequest 在进程重建后重新 observe 即可恢复进度展示）。
 */
actual class UploadQueueManager {
    private val workManager: WorkManager = WorkManager.getInstance(AppContext.applicationContext)

    private val _uploadState = MutableStateFlow<BackgroundUploadState>(BackgroundUploadState.Idle)
    actual val uploadState: StateFlow<BackgroundUploadState> = _uploadState.asStateFlow()

    /**
     * observe 用的应用级协程作用域。SupervisorJob 保证单个 observe 协程异常不影响其他；
     * Dispatchers.Default 因 WorkInfo flow 回调在后台线程，映射逻辑轻量切默认即可。
     */
    private val observeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual fun enqueueUploads(items: List<BackgroundUploadItem>) {
        if (items.isEmpty()) return

        val inputData = UploadWorker.itemsToWorkData(items)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        // 立即标记 Running（total 已知，completed=0），UI 即时反馈"已加入队列"。
        _uploadState.value = BackgroundUploadState.Running(completed = 0, total = items.size)

        workManager.enqueue(request)

        // observe 本次入队 WorkRequest 的状态流，映射为 BackgroundUploadState。
        // 用 id 精确 observe（而非 tag），避免多批交叉污染状态。
        observeScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                _uploadState.value = mapWorkInfo(info, items.size)
            }
        }
    }

    /**
     * 把 [WorkInfo] 映射为 [BackgroundUploadState]。
     *
     * - RUNNING：progress 携带 completed/total（Worker 每传完一项 setProgress）。
     * - SUCCEEDED：→ [BackgroundUploadState.Completed]（total 取 progress，兜底入队时总数）。
     * - FAILED：达重试上限 → [BackgroundUploadState.Failed]（failedCount = total - completed）。
     * - ENQUEUED/CANCELLED：ENQUEUED 是"已入队待调度"，保持 Running(0,total)（已在入队时
     *   设置，此处不覆盖，避免因 ENQUEUED 出现在 Running 后把状态打回造成 UI 闪烁）；
     *   CANCELLED → Idle。
     */
    private fun mapWorkInfo(info: WorkInfo, fallbackTotal: Int): BackgroundUploadState {
        val completed = info.progress.getInt(UploadWorker.KEY_PROGRESS_COMPLETED, 0)
        val total = info.progress.getInt(UploadWorker.KEY_PROGRESS_TOTAL, fallbackTotal)
        return when (info.state) {
            WorkInfo.State.RUNNING ->
                BackgroundUploadState.Running(completed = completed, total = total)
            WorkInfo.State.ENQUEUED ->
                // 已入队待调度：维持入队时设的 Running(0, total)，不回退。
                _uploadState.value
            WorkInfo.State.SUCCEEDED ->
                BackgroundUploadState.Completed(total = total.coerceAtLeast(completed))
            WorkInfo.State.FAILED ->
                BackgroundUploadState.Failed(
                    failedCount = (total - completed).coerceAtLeast(0),
                    total = total
                )
            WorkInfo.State.CANCELLED ->
                BackgroundUploadState.Idle
            WorkInfo.State.BLOCKED ->
                _uploadState.value
        }
    }

    companion object {
        /** WorkRequest tag：用于 observe / cancel 全部后台上传任务。 */
        const val WORK_TAG = "com.wgt.media.UploadWorker"
    }
}
