package com.wgt.media

/**
 * 内存警告回调句柄，用于注销回调。
 *
 * 由 [registerMemoryWarningCallback] 返回，调用 [dispose] 注销回调、释放资源。
 */
interface MemoryWarningHandle {
    fun dispose()
}

/**
 * 注册内存警告回调。当系统发出内存压力通知时调用 [callback]。
 *
 * 平台实现：
 * - Android: [android.content.ComponentCallbacks2.onTrimMemory]
 *   （TRIM_MEMORY_RUNNING_LOW 及以上级别触发）
 * - iOS: [platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification]
 *
 * commonMain 无法直接使用 java.* / android.* API，故通过 expect/actual 桥接。
 *
 * @param callback 内存压力时调用的清理函数
 * @return 句柄，用于注销回调
 */
expect fun registerMemoryWarningCallback(callback: () -> Unit): MemoryWarningHandle
