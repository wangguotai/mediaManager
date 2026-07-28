package com.wgt.media

import android.content.Context
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台 [SettingsStorage] 实际实现 —— 基于 SharedPreferences。
 *
 * 使用私有模式（[Context.MODE_PRIVATE]），避免设置跨应用暴露。
 * 文件名固定为 `media_manager_settings`，与 [com.wgt.media.SettingsKeys] 配套。
 *
 * `getString` / `putString` 直接转交 SharedPreferences，内部已线程安全；
 * 写入用 `apply()` 异步落盘，不阻塞 UI 线程。
 */
actual class SettingsStorage actual constructor() {
    private val prefs: android.content.SharedPreferences by lazy {
        AppContext.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private companion object {
        private const val PREFS_NAME = "media_manager_settings"
    }
}
