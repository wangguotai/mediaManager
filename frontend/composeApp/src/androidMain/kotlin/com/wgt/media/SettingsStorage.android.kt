package com.wgt.media

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台 [SettingsStorage] 实际实现 —— 双层存储。
 *
 * - **普通设置**：私有 [Context.MODE_PRIVATE] 的 [android.content.SharedPreferences]
 *  （文件名 `media_manager_settings`），存后端地址、主题等非敏感项。
 * - **敏感凭据**：[EncryptedSharedPreferences]（文件名 `media_manager_secure`），
 *  以 AndroidKeystore 支撑的 [MasterKey] 自动加解密，存 JWT token / 用户名 / 用户 id。
 *  退出登录所清的正是这层，普通设置不受影响。
 *
 * `getString` / `putString` 直接转交 SharedPreferences，内部已线程安全；
 * 写入用 `apply()` 异步落盘，不阻塞 UI 线程。安全层同样用 `apply()`。
 * [removeSecureString] 用 `edit().remove().apply()` 删除键——区分于"写空串"
 * （写空串会把空值留在密文文件里，下次 getString 仍命中空串而非 default，
 * 语义不准；故删除走显式 remove）。
 */
actual class SettingsStorage actual constructor() {
    /** 普通设置存储句柄，懒加载避免 Application 未初始化时过早取 Context。 */
    private val prefs: android.content.SharedPreferences by lazy {
        AppContext.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 敏感凭据存储句柄。
     *
     * EncryptedSharedPreferences 在 MasterKey 不可用（极少数设备 Keystore 损坏）
     * 时构造会抛 GeneralSecurityException / IOException。此处兜底：失败则回退到
     * 普通 SharedPreferences（明文）——可用性优先于"安全降级"，并记录日志。
     * token 仍能用，只是未加密，不阻断登录流程。
     */
    private val securePrefs: android.content.SharedPreferences by lazy {
        val ctx = AppContext.applicationContext
        try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore 异常时回退明文存储：可用性 > 加密。token 仍持久化。
            com.wgt.platform.logger.logger.error(
                "SettingsStorage",
                "EncryptedSharedPreferences unavailable, fallback to plain: ${e.message}"
            )
            ctx.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    actual fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun getSecureString(key: String, default: String): String =
        securePrefs.getString(key, default) ?: default

    actual fun putSecureString(key: String, value: String) {
        securePrefs.edit().putString(key, value).apply()
    }

    actual fun removeSecureString(key: String) {
        securePrefs.edit().remove(key).apply()
    }

    private companion object {
        private const val PREFS_NAME = "media_manager_settings"
        private const val SECURE_PREFS_NAME = "media_manager_secure"
    }
}
