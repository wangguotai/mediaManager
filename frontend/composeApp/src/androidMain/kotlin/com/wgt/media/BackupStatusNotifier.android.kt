package com.wgt.media

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台备份进度通知实现（PRD-v7 §1.5）。
 *
 * 用 [NotificationManager] 创建 channel "media_backup"（API 26+ 必需），
 * 通过 [NotificationCompat] 构建进度/暂停/完成通知。通知 id 固定
 * ([NOTIFICATION_ID])，各阶段复用同一条，避免通知栏堆积。
 *
 * 通道重要性 LOW：进度通知属信息性，不应发声打断用户（对标系统下载通知行为）。
 */
private const val CHANNEL_ID = "media_backup"
private const val NOTIFICATION_ID = 1001

/** 主线程 Handler，用于 [notifyBackupComplete] 延迟 1s 取消通知。 */
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * 安全获取 Context：AppContext 未初始化时返回 null（通知静默跳过）。
 * 自动备份可能在 App 启动早期触发，此时 AppContext 未必就绪。
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
        "媒体备份",
        NotificationManager.IMPORTANCE_LOW // 进度通知不发声
    ).apply {
        description = "云相册自动备份进度通知"
        setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
}

// expect class 的 actual：无参构造，无需初始化逻辑（通知用顶层函数，状态全在平台单例/通知系统）。
actual class BackupStatusNotifier

actual fun notifyBackupProgress(current: Int, total: Int) {
    val ctx = appContext ?: return
    ensureChannel()
    val percent = if (total > 0) current * 100 / total else 0
    val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle("媒体备份")
        .setContentText("备份中 $current/$total")
        .setSmallIcon(android.R.drawable.stat_sys_upload) // 系统上传图标
        .setProgress(total, current, false) // 确定性进度条
        .setOngoing(true) // 备份中不可滑动清除
        .setOnlyAlertOnce(true) // 更新进度时不重复提示音
        .setPriority(NotificationCompat.PRIORITY_LOW)
    notify(ctx, builder)
}

actual fun notifyBackupPaused(reason: String) {
    val ctx = appContext ?: return
    ensureChannel()
    val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle("媒体备份")
        .setContentText("备份已暂停（$reason）")
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
    notify(ctx, builder)
}

actual fun notifyBackupComplete() {
    val ctx = appContext ?: return
    ensureChannel()
    val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setContentTitle("媒体备份")
        .setContentText("备份完成")
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setOngoing(false) // 完成后可清除
        .setProgress(0, 0, false) // 清除进度条
        .setPriority(NotificationCompat.PRIORITY_LOW)
    notify(ctx, builder)
    // 1s 后自动取消完成通知，避免残留。
    mainHandler.postDelayed({ cancelBackupNotification() }, 1000L)
}

actual fun cancelBackupNotification() {
    val ctx = appContext ?: return
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    nm.cancel(NOTIFICATION_ID)
}

/** 统一 notify 入口，捕获通知 id。 */
private fun notify(ctx: Context, builder: NotificationCompat.Builder) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    nm.notify(NOTIFICATION_ID, builder.build())
}
