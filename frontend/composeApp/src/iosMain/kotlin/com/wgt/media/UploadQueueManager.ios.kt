package com.wgt.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS 平台 [UploadQueueManager] actual 实现 —— **占位空实现**。
 *
 * PRD-v8 §2.2 后台上传服务当前仅 Android 端通过 WorkManager 落地（前台被杀也能续传）。
 * iOS 端因 RN 集成仍在进行中，后台上传暂未实现：[enqueueUploads] 为 no-op，
 * [uploadState] 恒为 [BackgroundUploadState.Idle]。
 *
 * TODO（iOS RN 集成完成后补齐）：
 * - 用 [BGTaskScheduler]（BGProcessingTaskRequest）注册后台上传任务标识；
 * - 单文件上传用 [URLSession] background upload task（config = backgroundSession），
 *   系统在 App 被杀后仍保活上传任务，完成后唤醒 App 回调——等效 WorkManager 续传语义；
 * - 进度反馈：URLSession task delegate 回调 → 推进 [BackgroundUploadState]。
 * - sha256 秒传 / dedup 复用 commonMain 的 [Sha256Dedup] 与 [MediaService.uploadMedia]。
 *
 * 在此之前，iOS 端批量上传走前台 [MediaViewModel.uploadSelectedLocalMedia] 既有路径。
 */
actual class UploadQueueManager {
    private val _uploadState = MutableStateFlow<BackgroundUploadState>(BackgroundUploadState.Idle)
    actual val uploadState: StateFlow<BackgroundUploadState> = _uploadState.asStateFlow()

    actual fun enqueueUploads(items: List<BackgroundUploadItem>) {
        // TODO: iOS 后台上传待实现（见类注释）。当前 no-op，UI 不展示后台上传入口
        // （MediaListScreen 仅在 selectedTab==0 本地相册 Tab 显示该按钮，iOS 端由
        // 前台 uploadSelectedLocalMedia 兜底，无功能缺失）。
    }
}
