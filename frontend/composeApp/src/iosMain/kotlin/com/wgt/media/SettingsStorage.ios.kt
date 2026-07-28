package com.wgt.media

import platform.Foundation.NSUserDefaults

/**
 * iOS 平台 [SettingsStorage] 实际实现 —— 基于 NSUserDefaults。
 *
 * 使用标准用户默认值（standardUserDefaults），键名与 Android 端一致
 *（见 [com.wgt.media.SettingsKeys]）。
 *
 * `getString` 读 nil 时回退 [default]；`putString` 写入后由系统负责持久化。
 * NSUserDefaults 本身线程安全，可在任意协程调用。
 */
actual class SettingsStorage actual constructor() {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String, default: String): String {
        val value = defaults.stringForKey(key)
        return value ?: default
    }

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}
