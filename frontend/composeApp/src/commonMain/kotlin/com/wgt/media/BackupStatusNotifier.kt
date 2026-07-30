package com.wgt.media

/**
 * 备份进度通知器（PRD-v7 §1.5）。
 *
 * 通过 expect/actual 桥接平台原生通知能力：
 * - Android：[android.app.NotificationManager] + NotificationCompat，带进度条的前台通知。
 * - iOS：空实现（iOS 后台通知受限，暂不实现，见 [notifyBackupProgress] 注释）。
 *
 * 顶层函数式 API：自动备份流程在 commonMain（[MediaViewModel.checkAndBackupNewLocalMedia]）
 * 中按进度阶段调用，无需持有 notifier 实例。各平台 actual 负责创建/更新/取消通知。
 *
 * 通知 id 固定为 [NOTIFICATION_ID]，使各阶段（进度/暂停/完成）复用同一条通知，
 * 避免通知栏堆积多条。
 */
expect class BackupStatusNotifier()

/**
 * 显示备份进度通知："备份中 current/total"，带进度条。
 *
 * - Android：NotificationCompat.Builder + setProgress(total, current, false)。
 * - iOS：空实现。iOS 后台执行受限，App 在后台时用户通知（UNUserNotification）
 *   需显式 request permission 且仅在后台/杀进程时投递，与本场景「前台/后台实时进度」
 *   语义不匹配；UI 已有上传进度对话框（前台）覆盖，故 iOS 端暂不实现通知。
 *
 * @param current 已完成上传数
 * @param total 本次待备份总数
 */
expect fun notifyBackupProgress(current: Int, total: Int)

/**
 * 显示"备份已暂停（reason）"通知。reason 形如 "非WiFi" / "非充电"。
 *
 * 自动备份策略检查未满足时调用（[shouldBackupByPolicy] 返回 false）。
 * iOS 端空实现。
 */
expect fun notifyBackupPaused(reason: String)

/**
 * 显示"备份完成"通知，1s 后自动取消。
 *
 * iOS 端空实现。
 */
expect fun notifyBackupComplete()

/**
 * 取消备份通知。
 *
 * 用于停止自动备份（[MediaViewModel.stopAutoBackup]）时清理通知。
 * iOS 端空实现。
 */
expect fun cancelBackupNotification()
