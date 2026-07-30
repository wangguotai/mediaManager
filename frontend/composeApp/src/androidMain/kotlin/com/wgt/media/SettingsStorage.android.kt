package com.wgt.media

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.platform.logger.logger

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
 *
 * **V5：无明文降级（PRD §2.1）**。[EncryptedSharedPreferences] 初始化（MasterKey
 * 构造 / 加密文件创建）在极少数设备上因 AndroidKeystore 损坏会抛
 * GeneralSecurityException / IOException。此时 [securePrefs] 保持为 `null`，
 * secure 系列方法不再降级到明文 [SharedPreferences]——而是记录错误并拒绝落盘：
 * - [getSecureString] 返回 [default]（视作"未存过"，冷启动落到未登录态）。
 * - [putSecureString] 记录错误后静默不写入（内存态仍由调用方更新，但凭据不持久化，
 *   下次重启需重登——可用性退让于"凭据绝不以明文落盘"）。
 * - [removeSecureString] 记录后跳过。
 * 普通非敏感设置 [getString]/[putString] 不受影响，仍走明文 [SharedPreferences]。
 */
actual class SettingsStorage actual constructor() {
    /** 普通设置存储句柄，懒加载避免 Application 未初始化时过早取 Context。 */
    private val prefs: android.content.SharedPreferences by lazy {
        AppContext.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 敏感凭据存储句柄；Keystore 不可用时为 `null`（V5：不降级明文）。
     *
     * EncryptedSharedPreferences 在 MasterKey 不可用（极少数设备 Keystore 损坏 / 系统升级
     * 致密钥失效）时构造会抛 GeneralSecurityException / IOException。此处仅记录错误并令
     * [securePrefs] 为 `null`——secure 读写随后据此退回 default / 拒写，**不创建明文
     * SharedPreferences**。凭据安全优先于"token 仍能用"的可用性诉求（见类 KDoc）。
     */
    private val securePrefs: android.content.SharedPreferences? by lazy {
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
            // V5：Keystore 异常不再降级明文——记录错误、securePrefs 留 null，secure 读写据此拒落盘。
            logger.error(
                TAG,
                "EncryptedSharedPreferences unavailable, secure storage disabled (no plaintext fallback): ${e.message}"
            )
            null
        }
    }

    actual fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    actual fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * 读取敏感凭据。Keystore 损坏（[securePrefs]==null）时不降级明文，
     * 返回 [default]（视作未存过，冷启动回落到未登录态）。
     */
    actual fun getSecureString(key: String, default: String): String {
        val sp = securePrefs ?: run {
            logger.error(TAG, "getSecureString('$key') refused: secure storage disabled")
            return default
        }
        return sp.getString(key, default) ?: default
    }

    /**
     * 写入敏感凭据。Keystore 损坏（[securePrefs]==null）时拒绝落盘——仅记录错误、不写明文。
     * 调用方（[com.wgt.media.AuthState.saveSession]）仍会更新内存 token，本次会话内可用；
     * 但凭据未持久化，重启后需重登。此为可用性向"凭据绝不明文落盘"的让步（PRD §2.1）。
     */
    actual fun putSecureString(key: String, value: String) {
        val sp = securePrefs ?: run {
            logger.error(TAG, "putSecureString('$key') refused: secure storage disabled, not persisted")
            return
        }
        sp.edit().putString(key, value).apply()
    }

    /** 删除敏感凭据。Keystore 损坏时无明文可删，记录后跳过。 */
    actual fun removeSecureString(key: String) {
        val sp = securePrefs ?: run {
            logger.error(TAG, "removeSecureString('$key') skipped: secure storage disabled")
            return
        }
        sp.edit().remove(key).apply()
    }

    private companion object {
        private const val TAG = "SettingsStorage"
        private const val PREFS_NAME = "media_manager_settings"
        private const val SECURE_PREFS_NAME = "media_manager_secure"
    }
}
