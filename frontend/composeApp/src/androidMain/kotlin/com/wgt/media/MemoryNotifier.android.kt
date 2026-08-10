package com.wgt.media

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wgt.MainActivity
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import java.util.Calendar

/**
 * Android 平台回忆通知实现（PRD-v10 §3.1）。
 *
 * 通道 "memories"，IMPORTANCE_DEFAULT（默认发声+振动）——回忆推送语义上属「主动提示」，
 * 与 [BackupStatusNotifier] 的进度通知（LOW、不发声）相反，应有声提醒用户点开回忆。
 *
 * 防重复：[SettingsStorage] 持久化 [SettingsKeys.MEMORY_LAST_NOTIFIED_PREFIX]+`yyyy-MM`，
 * 同一年月只通知一次。App 当天多次启动或多次同步触发 [checkAndNotifyMemories] 时不再打扰。
 *
 * 通知点击行为：PendingIntent 打开 [MainActivity]（App 入口），由系统带回到已运行任务栈，
 * 无需额外路由——用户点击后进入主界面自行滑到「回忆」区域查看。
 */
private const val CHANNEL_ID = "memories"
// 通知 id 用 year*100+month 动态生成：不同年月互不覆盖、同年月更新复用同一条。
// 与备份通知（id=1001）独立，避免互相覆盖。

/**
 * 安全获取 Context：AppContext 未初始化时返回 null（通知静默跳过）。
 * 与 [BackupStatusNotifier.android.kt] 同款防御：同步可能在 App 启动早期触发。
 */
private val appContext: Context? get() = runCatching {
    if (AppContext.isInitialized) AppContext.applicationContext else null
}.getOrNull()

/**
 * 确保 [CHANNEL_ID] 通道已创建（API 26+ 必需，低于 26 无 channel 概念，忽略）。
 * 重复调用安全——创建幂等。
 */
@SuppressLint("ServiceCast")
private fun ensureChannel() {
    val ctx = appContext ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        "回忆推送",
        NotificationManager.IMPORTANCE_DEFAULT // 回忆通知发声，与备份进度通知(LOW)区分
    ).apply {
        description = "「那年今天」回忆照片提醒"
        setShowBadge(true) // 桌面角标提示有未读回忆
    }
    nm.createNotificationChannel(channel)
}

actual fun checkAndNotifyMemories(months: List<MemoryMonth>) {
    if (!isMemoryNotificationEnabled()) return
    val ctx = appContext ?: return
    if (months.isEmpty()) return

    // 当前年月（本地时区）。用 java.util.Calendar 保证与 [groupMediaByMonth] 的
    // systemTimeZoneOffsetMillis 口径基本一致（本机时区）。
    val now = Calendar.getInstance()
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) + 1 // Calendar.MONTH 0-based → 1..12

    // 找「那年今天」：月份等于当前月，且年份早于当前年（跨过至少一年才算「那年」）。
    // 可能有多个历史年份同月命中（如 2024-07、2025-07 都对照 2026-07），各自独立通知，
    // 每条按 yyyy-MM 去重——同年月一次通知覆盖该月张数即可。
    val matched = months.filter { it.month == curMonth && it.year < curYear }
    if (matched.isEmpty()) return

    val storage = SettingsStorage()
    ensureChannel()
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

    // 点击打开 MainActivity。FLAG_IMMUTABLE 是 API 31+ 强制要求；FLAG_UPDATE_CURRENT
    // 保证 PendingIntent 携带最新 extras（虽然此处无 extras，保持与系统推荐一致）。
    val launchIntent = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    for (m in matched) {
        val dedupKey = "${SettingsKeys.MEMORY_LAST_NOTIFIED_PREFIX}${m.year}-${"%02d".format(m.month)}"
        // 已通知过该年月 → 跳过，防同日多次启动/同步重复打扰。
        if (storage.getString(dedupKey, "") == "1") continue

        val pi = PendingIntent.getActivity(ctx, m.year * 100 + m.month, launchIntent, pendingFlags)
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("📸 那年今天")
            .setContentText("${m.year}年${m.month}月：${m.totalCount}张回忆")
            .setSmallIcon(android.R.drawable.ic_menu_gallery) // 系统相册图标
            .setContentIntent(pi)
            .setAutoCancel(true) // 点击后自动消失
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        // 通知 id 用 year*100+month 保证不同年月互不覆盖、同年月更新复用同一条。
        nm.notify(m.year * 100 + m.month, builder.build())
        // 落盘去重标记，避免当日后续启动/同步重复通知。
        storage.putString(dedupKey, "1")
    }
}

actual fun isMemoryNotificationEnabled(): Boolean {
    // 默认开启：回忆推送是核心体验，对标系统相册类 App 默认行为；用户可在设置页关闭。
    // 集中通过 SettingsStorage 读写，与其它设置项同源（Android→SharedPreferences，iOS→NSUserDefaults）。
    return SettingsStorage().getString(SettingsKeys.MEMORY_NOTIFICATION_ENABLED, "true") == "true"
}
