package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScope
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSUserDefaults
import platform.Foundation.appendData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS 平台 [SettingsStorage] 实际实现 —— 双层存储。
 *
 * - **普通设置**：[NSUserDefaults]（standardUserDefaults），存后端地址、主题。
 * - **敏感凭据**：Keychain（kSecClassGenericPassword，由 (service, account) 定位条目），
 *  数据由 iOS 加密保护，存 JWT token / 用户名 / 用户 id。
 *
 * Keychain 经 Security 框架 C API（[SecItemAdd]/[SecItemCopyMatching]/[SecItemDelete]）访问，
 * 查询字典用 [NSMutableDictionary] 构造——它在 Kotlin/Native 中可作 CFDictionaryRef 桥接
 * 传给 SecItem*（toll-free bridged），比手写 [platform.CoreFoundation.CFDictionaryCreate]
 * 的键值数组管理更稳妥。
 *
 * 读取结果的 result 句柄是 `CFTypeRef*`，对应 K/N 的 [CFTypeRefVar]；SecItemCopyMatching
 * 把匹配到的 NSData 写入其中，取 `.value` 后 as [NSData] 取 bytes。
 */
@OptIn(ExperimentalForeignApi::class)
actual class SettingsStorage actual constructor() {
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    actual fun getString(key: String, default: String): String {
        val value = defaults.stringForKey(key)
        return value ?: default
    }

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun getSecureString(key: String, default: String): String {
        val bytes = keychainGet(key) ?: return default
        return bytes.decodeToString()
    }

    actual fun putSecureString(key: String, value: String) {
        keychainSet(key, value)
    }

    actual fun removeSecureString(key: String) {
        keychainDelete(key)
    }
}

// ---- Keychain helpers ----

/** Keychain service 标识，与 account 二元组唯一定位条目。 */
private const val KEYCHAIN_SERVICE = "media_manager"

/**
 * 从 Keychain 读取 account 对应的密码字节。不存在返回 null。
 *
 * 查询字典含 kSecReturnData = [kCFBooleanTrue]，[SecItemCopyMatching] 把结果 NSData
 * 写入 [result] 句柄；errSecItemNotFound → null，其余错误亦 → null（回退 default）。
 * 默认 kSecMatchLimit 为 One，无需显式设置。
 */
@OptIn(ExperimentalForeignApi::class)
private fun keychainGet(account: String): ByteArray? = memScope {
    val query = baseQuery(account)
    query.setObject(kCFBooleanTrue!!, forKey = kSecReturnData!!)

    val result = alloc<CFTypeRefVar>()
    val status: OSStatus = SecItemCopyMatching(query, result.ptr)
    if (status.toInt() == errSecItemNotFound.toInt()) return@memScope null
    if (status.toInt() != 0) return@memScope null
    val data = result.value as? NSData ?: return@memScope null
    val length = data.length.toInt()
    if (length == 0) return@memScope ByteArray(0)
    val bytesPtr = data.bytes ?: return@memScope null
    bytesPtr.reinterpret<platform.darwin.UInt8Var>().readBytes(length)
}

/**
 * 写入/覆盖 Keychain 条目。先删除旧值再插入，保证幂等覆盖。
 *
 * token 字节以 [NSMutableData] 承载（[appendData] 拷贝 [ByteArray] 字节），作 kSecValueData。
 */
@OptIn(ExperimentalForeignApi::class)
private fun keychainSet(account: String, value: String) {
    val dataBytes = value.encodeToByteArray()
    val nsData = NSMutableData()
    dataBytes.usePinned { nsData.appendData(it.getPointer()) }

    keychainDelete(account) // 幂等：不存在也安全

    val query = baseQuery(account)
    query.setObject(nsData, forKey = kSecValueData!!)
    SecItemAdd(query, null)
}

/** 删除 Keychain 条目。不存在时 SecItemDelete 返 errSecItemNotFound，忽略。 */
@OptIn(ExperimentalForeignApi::class)
private fun keychainDelete(account: String) {
    SecItemDelete(baseQuery(account))
}

/**
 * 构造 Keychain 查询的基础字典：kSecClass + kSecAttrService + kSecAttrAccount。
 *
 * 返回 [NSMutableDictionary]，可继续 setObject 补 kSecReturnData / kSecValueData，
 * 亦可整体作 CFDictionaryRef 传给 SecItem* API。
 *
 * 注意：[kSecClass] 等 kSec* 是 CFStringRef；[kSecClassGenericPassword] 同。
 * 用 `!!` 断言非空——这些常量在系统库中恒存在。
 */
@OptIn(ExperimentalForeignApi::class)
private fun baseQuery(account: String): NSMutableDictionary {
    val query = NSMutableDictionary()
    query.setObject(kSecClassGenericPassword!!, forKey = kSecClass!!)
    query.setObject(KEYCHAIN_SERVICE, forKey = kSecAttrService!!)
    query.setObject(account, forKey = kSecAttrAccount!!)
    return query
}
