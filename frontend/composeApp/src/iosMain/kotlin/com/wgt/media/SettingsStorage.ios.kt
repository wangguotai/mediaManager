package com.wgt.media

import platform.Foundation.NSUserDefaults

actual class SettingsStorage actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults()

    actual fun getString(key: String, default: String): String {
        return defaults.stringForKey(key) ?: default
    }

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    // V4: 敏感凭据暂时也用 NSUserDefaults，后续迁移到 Keychain
    actual fun getSecureString(key: String, default: String): String {
        return defaults.stringForKey(key) ?: default
    }

    actual fun putSecureString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun removeSecureString(key: String) {
        defaults.removeObjectForKey(key)
    }
}
