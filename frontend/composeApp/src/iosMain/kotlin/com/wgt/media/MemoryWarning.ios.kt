package com.wgt.media

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * iOS 平台内存警告监听：通过 NSNotificationCenter 监听
 * UIApplicationDidReceiveMemoryWarningNotification，
 * 收到通知时触发回调。
 */
actual fun registerMemoryWarningCallback(callback: () -> Unit): MemoryWarningHandle {
    val center = NSNotificationCenter.defaultCenter
    val observer = center.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = null
    ) { _ -> callback() }
    return object : MemoryWarningHandle {
        override fun dispose() {
            center.removeObserver(observer)
        }
    }
}
