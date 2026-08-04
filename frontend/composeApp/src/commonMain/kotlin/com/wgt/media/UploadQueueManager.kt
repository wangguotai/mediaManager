package com.wgt.media

import kotlinx.coroutines.flow.StateFlow

/**
 * 后台上传单条任务描述（平台无关模型）。
 *
 * 由 [MediaViewModel.enqueueBackgroundUpload] 在 commonMain 侧根据选中媒体的
 * [media.MediaMetadata] 元数据构造，透传给 [UploadQueueManager.enqueueUploads]，
 * 最终由平台 actual 实现（Android: WorkManager / iOS: TODO）消费。
 *
 * 字段口径与 [com.wgt.feature.media.MediaService.uploadMedia] 去重扩展参数对齐：
 * - [mediaId]：本地图库媒体 id（Android 为 MediaStore _ID 字符串，iOS 为 PHAsset localIdentifier），
 *   Worker 据此经 GalleryFeature.getMediaData 取字节流。
 * - [filename]：原始文件名，透传给后端取扩展名 / metadata sidecar。
 * - [isLivePhoto]：是否 Live Photo（后端按 true 存 live photo 关系）。
 * - [takenAt]：拍摄时间（epoch ms），>0 透传，0 表未知；用于后端时序与前端按月分组。
 *
 * 注意：sha256 不在此处预算——值大且算 sha 会阻塞 UI 线程，留给 UploadWorker 在
 * 后台线程取字节后现算（复用 [sha256Hex] / [Sha256Dedup] 秒传短路逻辑）。
 *
 * @property mediaId 本地图库媒体 id
 * @property filename 原始文件名
 * @property isLivePhoto 是否 Live Photo
 * @property takenAt 拍摄时间 ms（0 表未知）
 */
data class BackgroundUploadItem(
    val mediaId: String,
    val filename: String,
    val isLivePhoto: Boolean,
    val takenAt: Long
)

/**
 * 后台上传整体状态（PRD-v8 §2.2）。
 *
 * 由 [UploadQueueManager.uploadState] 暴露，[MediaViewModel.backgroundUploadState]
 * 转发该 Flow 给 UI，驱动"已加入后台上传队列"Snackbar 与可选的进度展示。
 *
 * - [Idle]：无后台上传任务（初态 / 全部结束并复位后）。
 * - [Running]：WorkManager 有进行中的上传 WorkRequest，[completed]/[total] 为实时进度。
 *   total 为本批入队总数，completed 为已成功上传数（每上传完一个 setProgress 推进）。
 * - [Completed]：本批全部上传成功。UI 可一次性提示后复位回 Idle。
 * - [Failed]：达到重试上限仍未成功（[failedCount] 为最终失败数）。UI 提示失败。
 *
 * 设计为 sealed class 而非 enum+字段：不同状态携带的进度字段不同（Idle/Completed/Failed
 * 无需 completed/total），sealed 便于 when 穷尽匹配，避免"Idle 时 total=0"这类无意义字段。
 */
sealed class BackgroundUploadState {
    /** 无进行中的后台上传。 */
    data object Idle : BackgroundUploadState()

    /**
     * 后台上传进行中。
     * @param completed 已成功上传条数
     * @param total 本批入队总数
     */
    data class Running(val completed: Int, val total: Int) : BackgroundUploadState()

    /**
     * 本批全部完成。
     * @param total 本批总数（= completed，显式保留便于 UI 文案"已上传 N 项"）
     */
    data class Completed(val total: Int) : BackgroundUploadState()

    /**
     * 本批在重试上限内未能全部成功。
     * @param failedCount 最终失败条数
     * @param total 本批总数
     */
    data class Failed(val failedCount: Int, val total: Int) : BackgroundUploadState()
}

/**
 * 后台上传队列管理器（expect/actual 模式）。
 *
 * commonMain 仅声明契约与平台无关的状态模型；actual 实现见：
 * - Android（[UploadQueueManager.android.kt]）：基于 WorkManager OneTimeWorkRequest，
 *   带联网约束 + 指数退避 + 最多 5 次重试。前台被杀后 WorkManager 持久化 WorkRequest，
 *   进程重启后自动续跑——这正是"前台被杀也能继续上传"的核心保障。
 * - iOS（[UploadQueueManager.ios.kt]）：TODO 空实现，iOS RN 集成仍在进行中，
 *   后续用 BGTaskScheduler / URLSession background upload 补齐。
 *
 * 进度反馈：actual 实现把 WorkManager 的 WorkInfo 流映射为 [BackgroundUploadState]，
 * 经 [uploadState] 上抛；[MediaViewModel] 转发给 UI。
 *
 * 调用方（[MediaViewModel.enqueueBackgroundUpload]）只负责把选中媒体元数据打包为
 * [BackgroundUploadItem] 列表传入，不感知平台细节。
 */
expect class UploadQueueManager() {
    /**
     * 把一批待上传项入队，交由平台后台任务调度执行。
     *
     * 幂等：重复入队相同 mediaId 会产生新 WorkRequest（WorkManager 不去重同类 Request），
     * 调用方应在 UI 侧防止重复点击（SelectionBottomBar 按钮点击后即 deselectAll）。
     *
     * @param items 待上传项列表（每项含 mediaId / filename / isLivePhoto / takenAt）
     */
    fun enqueueUploads(items: List<BackgroundUploadItem>)

    /**
     * 当前后台上传状态流。初值 [BackgroundUploadState.Idle]；入队后转 [Running]，
     * 全部成功转 [Completed]，重试耗尽转 [Failed]。
     *
     * UI 经 [MediaViewModel.backgroundUploadState] 观察，展示 Snackbar / 进度。
     */
    val uploadState: StateFlow<BackgroundUploadState>
}
