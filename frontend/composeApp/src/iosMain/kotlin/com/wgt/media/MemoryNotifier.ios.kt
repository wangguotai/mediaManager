package com.wgt.media

/**
 * iOS 平台回忆通知实现 —— 空实现（PRD-v10 §3.1）。
 *
 * iOS 本地通知（UNUserNotificationCenter）需显式 request authorization，且调度时机
 * 由系统决定；当前 KMP commonMain 调用点（[MediaViewModel.loadCloudChanges] 同步成功后）
 * 属前台同步触发，与 iOS 本地通知「后台/锁屏投递」语义不完全契合。前台已有「回忆」
 * 横滚卡片入口可见反馈，故 iOS 端暂不实现通知，留待后续用 BGProcessingTask + 通知策略
 * 补齐（与 [BackupStatusNotifier] iOS 端取舍一致）。
 *
 * 所有函数空实现，调用方无需平台判断。
 */
actual fun checkAndNotifyMemories(months: List<MemoryMonth>) {
    // iOS 端暂不实现——见文件注释。
}

actual fun isMemoryNotificationEnabled(): Boolean {
    // iOS 端暂不实现：回忆通知策略待定，先返回 false 避免 actual 缺失。
    return false
}
