package com.wgt.media

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台内存警告监听：注册 [ComponentCallbacks2] 到 Application Context，
 * 在 onTrimMemory(TRIM_MEMORY_RUNNING_LOW+) 或 onLowMemory 时触发回调。
 */
actual fun registerMemoryWarningCallback(callback: () -> Unit): MemoryWarningHandle {
    val appContext = AppContext.applicationContext
    val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(config: Configuration) {}
        override fun onLowMemory() { callback() }
        override fun onTrimMemory(level: Int) {
            // TRIM_MEMORY_RUNNING_LOW (10) 及以上表示系统内存紧张，
            // 需要释放缓存避免被系统杀进程。
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                callback()
            }
        }
    }
    appContext.registerComponentCallbacks(componentCallbacks)
    return object : MemoryWarningHandle {
        override fun dispose() {
            appContext.unregisterComponentCallbacks(componentCallbacks)
        }
    }
}
