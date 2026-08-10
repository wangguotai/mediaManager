package com.wgt.media

/**
 * 回忆通知器（PRD-v10 §3.1 回忆通知推送）。
 *
 * App 启动/云端增量同步后，检查「那年今天」并推送本地通知，提示用户回忆历史照片。
 * 通过 expect/actual 桥接平台原生通知能力，与 [BackupStatusNotifier] 同一模式：
 * - Android：NotificationManager + NotificationCompat，IMPORTANCE_DEFAULT 通道（发声），
 *   SharedPreferences（复用 [SettingsStorage]）记录 last_notified_key=yyyy-MM 防重复。
 * - iOS：暂空实现（本地通知需 request authorization 与调度，后续补齐，参考
 *   [BackupStatusNotifier] iOS 端的取舍说明）。
 *
 * 顶层函数式 API：调用方在 [MediaViewModel.loadCloudChanges] 成功累积 cloudMedia 后，
 * 传入 [memoryMonths]（已按年月聚合的回忆视图）触发检查。各平台 actual 决定是否发通知。
 */

/**
 * 检查「那年今天」回忆并按需推送本地通知。
 *
 * 策略（简单版）：
 * - 遍历 [months]，找出月份等于当前月（1-12）的历史年份回忆项（不含当年——「历史」
 *   至少跨一年才有「那年今天」语义）；
 * - 命中时按年月聚合张数，组装通知文案「📸 那年今天：N张回忆」；
 * - 用持久化键（`yyyy-MM`）记录已通知的月份，避免同一天多次启动/同步重复打扰。
 *
 * 由 [MediaViewModel.loadCloudChanges] 在 cloudMedia 累积成功后调用，传入
 * [MediaViewModel.memoryMonths]。也安全可在 App 启动时调用，只是那时数据可能尚未同步到。
 *
 * @param months 按年月聚合的回忆月份列表（来自 [MediaViewModel.memoryMonths]）
 */
expect fun checkAndNotifyMemories(months: List<MemoryMonth>)

/**
 * 回忆通知开关。
 *
 * 取自 [SettingsKeys.MEMORY_NOTIFICATION_ENABLED] 持久化值，默认开启（与系统相册类 App
 * 「回忆推送」默认行为一致；用户可在设置页关闭）。声明为 expect fun 让各平台 actual
 * 直接读各自键值存储（Android→[SettingsStorage]→SharedPreferences，
 * iOS→后续接 NSUserDefaults），避免 commonMain 此处耦合存储实例化时机。
 */
expect fun isMemoryNotificationEnabled(): Boolean
