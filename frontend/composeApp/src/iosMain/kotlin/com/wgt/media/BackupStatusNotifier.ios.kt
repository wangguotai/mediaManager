package com.wgt.media

/**
 * iOS 平台备份进度通知实现 —— 空实现（PRD-v7 §1.5）。
 *
 * iOS 后台通知受限：App 在后台时本地通知（UNUserNotification）需显式 request authorization，
 * 且投递时机由系统调度，与本场景「备份实时进度」语义不匹配。前台已有上传进度对话框
 * 覆盖可见反馈，故 iOS 端暂不实现通知，留待后续用 BGProcessingTask + 通知策略补齐。
 *
 * 所有函数空实现，调用方无需平台判断。
 */
actual class BackupStatusNotifier

actual fun notifyBackupProgress(current: Int, total: Int) {
    // iOS 端暂不实现——见文件注释。
}

actual fun notifyBackupPaused(reason: String) {
    // iOS 端暂不实现——见文件注释。
}

actual fun notifyBackupComplete() {
    // iOS 端暂不实现——见文件注释。
}

actual fun cancelBackupNotification() {
    // iOS 端暂不实现——见文件注释。
}
