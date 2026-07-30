package com.wgt.media

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSJSONReadingAllowFragments
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Foundation.NSLock

/**
 * iOS 键值存储实现。
 *
 * 分两类存储，与 [expect][SettingsStorage] 注释一致：
 * - 普通设置 [getString]/[putString]：仍走 NSUserDefaults（线程安全、非敏感项）。
 * - 敏感凭据 [getSecureString]/[putSecureString]/[removeSecureString]：V5 起落到
 *   iOS **Keychain**（`kSecClassGenericPassword`），替代 V4 的明文 JSON 文件方案。
 *
 * **为何用 Keychain**：JWT token / 用户名 / 用户 id 属敏感凭据，Keychain 是 iOS 官方
 * 凭据存储，受系统级加密保护，App 卸载即清除，符合凭据安全语义。V4 曾以"Keychain
 * 互操作脆弱"为由改用 Documents 下明文 JSON，但明文落盘凭据安全等级不足；V5 修正为
 * Keychain 并补首次启动迁移。
 *
 * **键模型**：每个凭据存为一个 GenericPassword item。
 * - `kSecAttrService` = 固定命名空间 [SEC_SERVICE]（Keychain GenericPassword 以
 *   service+account 联合唯一，service 隔离本 App 凭据）。
 * - `kSecAttrAccount` = 传入的 [key]（如 `auth_token`）。
 * - `kSecValueData` = 凭据 UTF-8 字节（以 CFData 二进制承载，对任意 UTF-8 安全）。
 *
 * **C 互操作约定**：
 * - 查询字典用 [CFDictionaryCreate] 构造（`const void**` 形参以 `COpaquePointer` 数组经
 *   `allocArrayOf(*arr)` 落进 memScoped C 数组、首元素指针传入），回调用
 *   `kCFTypeDictionary{Key,Value}CallBacks`（值结构体取 `.ptr`）。
 *   不用 `Array.toCValues()`：其扩展要求具体 `T:CPointed`，而 `COpaquePointer` 是投影类型，
 *   编译器无法定 T；`allocArrayOf` 的 Array 重载直接吃 `COpaquePointer`。
 * - 不用 NSMutableDictionary，遵循 C 风查询字典语义。
 * - [SecItemCopyMatching] 出参用 `memScoped { alloc<CFTypeRefVar>() }` + `.ptr` 传入、
 *   `.value` 读出，读回的 CFDataRef 以 `as CFDataRef?` 落到具体类型取字节。
 * - Account/Service 是受控 ASCII（无嵌入 NUL），用 `CFStringCreateWithCString` + UTF-8 转 CFString。
 *
 * **并发**：Kotlin/Native 无 `synchronized`，secure 全部方法用 [NSLock] 串行化，
 * 避免迁移与读写交错读到半写态（Keychain 单次调用本身原子，跨调用序列化靠此锁）。
 *
 * **首次启动迁移**（[maybeMigrateFromJson]）：V4 升级用户在 Documents 下留有
 * `mm_secure.json`（明文凭据）。首次启动读回逐项写入 Keychain，成功后删除 JSON，
 * 保证老用户不丢登录态、明文不再留存。迁移成功即删文件 = 天然幂等。
 *
 * 平台 API 注意：Security/CoreFoundation 的 C 互操作属 `ExperimentalForeignApi`，本类 opt-in。
 */
@OptIn(ExperimentalForeignApi::class)
actual class SettingsStorage actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults()

    /** secure 操作串行锁。所有 secure 方法在锁内完成，避免迁移与读写交错。 */
    private val lock = NSLock()

    init {
        // 首次启动迁移：V4 明文 JSON → Keychain。放 init，保证任何 secure 调用前已完成。
        // 迁移自身用 put 的 unlocked 变体（init 早于任何外部 secure 调用，无需加锁）。
        maybeMigrateFromJson()
    }

    actual fun getString(key: String, default: String): String {
        return defaults.stringForKey(key) ?: default
    }

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun getSecureString(key: String, default: String): String {
        lock.lock()
        try {
            return keychainGet(key) ?: default
        } finally {
            lock.unlock()
        }
    }

    actual fun putSecureString(key: String, value: String) {
        lock.lock()
        try {
            keychainPut(key, value)
        } finally {
            lock.unlock()
        }
    }

    actual fun removeSecureString(key: String) {
        lock.lock()
        try {
            keychainDelete(key)
        } finally {
            lock.unlock()
        }
    }

    // ---------- Keychain 原语 ----------

    /** 从 Keychain 读回 [account] 对应凭据；不存在/失败返回 null。 */
    private fun keychainGet(account: String): String? = memScoped {
        // CF 创建放 try 内，任一失败时 finally 仅释放已创建的非空引用，不泄漏。
        var cfService: CFTypeRef? = null
        var cfAccount: CFTypeRef? = null
        var query: CFDictionaryRef? = null
        try {
            cfService = cfString(SEC_SERVICE) ?: return@memScoped null
            cfAccount = cfString(account) ?: return@memScoped null
            val keys = arrayOf<COpaquePointer>(
                kSecClass!!, kSecAttrService!!, kSecAttrAccount!!,
                kSecReturnData!!, kSecMatchLimitOne!!
            )
            val values = arrayOf<COpaquePointer>(
                kSecClassGenericPassword!!, cfService, cfAccount,
                kCFBooleanTrue!!, kSecMatchLimitOne!!
            )
            query = cfDictionary(keys, values) ?: return@memScoped null

            val resultRef = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, resultRef.ptr)
            val out: String? = when {
                status == errSecSuccess.toInt() -> {
                    // SecItemCopyMatching 在 kSecReturnData 时写回 +1 retain 的 CFDataRef。
                    // CFTypeRef 与 CFDataRef 在 K/N 中同为 CPointer，用 `as` 落到具体类型读字节。
                    (resultRef.value as CFDataRef?)?.let { cfDataToString(it) }
                }
                status == errSecItemNotFound.toInt() -> null // 未存过
                else -> null // 其它错误降级返回 null（可用性优先，不阻断登录）
            }
            resultRef.value?.let { CFRelease(it) } // 释放返回的 CFDataRef（与成功/失败分支无关，统一此处归还）
            out
        } finally {
            query?.let { CFRelease(it) } // CFDictionaryCreate 返回 +1 retain，本块归还
            cfAccount?.let { CFRelease(it) }
            cfService?.let { CFRelease(it) }
        }
    }

    /** 写入/覆盖 Keychain 中 [account] 对应凭据。 */
    private fun keychainPut(account: String, value: String) = memScoped {
        var cfService: CFTypeRef? = null
        var cfAccount: CFTypeRef? = null
        var cfData: CFDataRef? = null
        var attrs: CFDictionaryRef? = null
        try {
            cfService = cfString(SEC_SERVICE) ?: return@memScoped
            cfAccount = cfString(account) ?: return@memScoped
            cfData = cfData(value) ?: return@memScoped
            val keys = arrayOf<COpaquePointer>(
                kSecClass!!, kSecAttrService!!, kSecAttrAccount!!, kSecValueData!!
            )
            val values = arrayOf<COpaquePointer>(
                kSecClassGenericPassword!!, cfService, cfAccount, cfData
            )
            attrs = cfDictionary(keys, values) ?: return@memScoped

            val status = SecItemAdd(attrs, null)
            if (status == errSecDuplicateItem.toInt()) {
                // 既有项：GenericPassword 的 add 不覆盖，需 delete 后重新 add。
                keychainDeleteUnlocked(account)
                SecItemAdd(attrs, null)
            }
            // 写入结果 OSStatus 忽略：凭据写失败属可降级场景（下次登录可重写），
            // 与原方案一致保持静默，不抛出阻断 UI。
        } finally {
            attrs?.let { CFRelease(it) } // CFDictionaryCreate 返回 +1 retain，本块归还
            cfData?.let { CFRelease(it) }
            cfAccount?.let { CFRelease(it) }
            cfService?.let { CFRelease(it) }
        }
    }

    /** 删除 Keychain 中 [account] 对应凭据；不存在幂等。 */
    private fun keychainDelete(account: String) {
        keychainDeleteUnlocked(account)
    }

    /** delete 实现（自有 memScoped，供 put 的 duplicate 分支与 remove 共用）。 */
    private fun keychainDeleteUnlocked(account: String) {
        memScoped {
            var cfService: CFTypeRef? = null
            var cfAccount: CFTypeRef? = null
            var query: CFDictionaryRef? = null
            try {
                cfService = cfString(SEC_SERVICE) ?: return@memScoped
                cfAccount = cfString(account) ?: return@memScoped
                val keys = arrayOf<COpaquePointer>(kSecClass!!, kSecAttrService!!, kSecAttrAccount!!)
                val values = arrayOf<COpaquePointer>(kSecClassGenericPassword!!, cfService, cfAccount)
                query = cfDictionary(keys, values) ?: return@memScoped
                SecItemDelete(query)
                // errSecItemNotFound 视作已删除，幂等，无需处理。
            } finally {
                query?.let { CFRelease(it) } // CFDictionaryCreate 返回 +1 retain，本块归还
                cfAccount?.let { CFRelease(it) }
                cfService?.let { CFRelease(it) }
            }
        }
    }

    // ---------- C 互操作工具 ----------

    /**
     * 由 keys/values 数组构造不可变 CFDictionaryRef。
     *
     * key/value 以 `COpaquePointer?` 统一承载（CFString/CFData/kSec 常量皆可隐式进入），
     * 拷进 memScoped 的 `COpaquePointerVar` C 数组后，把首元素指针喂给 [CFDictionaryCreate]
     * 的 `const void**` 形参（不用 NSMutableDictionary，遵循 C 风查询字典语义）。
     * 回调用 `kCFTypeDictionary{Key,Value}CallBacks`（值结构体取 `.ptr`），使字典对 CF 类型正确持有。
     *
     * **所有权**：[CFDictionaryCreate] 返回 +1 retain 的引用，调用方须在用完后 [CFRelease] 归还
     * （各 keychain* 方法在 finally 中释放）。字典会 retain 其内的 CF 值，故 cfService/cfAccount/
     * cfData 的 release 与字典 release 可任意先后，互不影响。
     */
    private fun MemScope.cfDictionary(
        keys: Array<COpaquePointer>,
        values: Array<COpaquePointer>
    ): CFDictionaryRef? {
        val n = keys.size
        // allocArrayOf(Array) 在 memScoped 内分配 C 连续内存并把元素拷入，返回 CPointer<COpaquePointerVar>，
        // 直接喂给 CFDictionaryCreate 的 `const void**` 形参（不用 NSMutableDictionary，遵循 C 风查询字典语义）。
        // 不用 Array.toCValues()/cValuesOf()：二者要求具体 T:CPointed，而 COpaquePointer = CPointer<out CPointed>
        // 是投影类型，编译器无法定 T；allocArrayOf 的 Array 重载直接吃 COpaquePointer。
        val cKeys = allocArrayOf(*keys)
        val cVals = allocArrayOf(*values)
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            cKeys,
            cVals,
            n.toLong(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
    }

    /** Kotlin String → CFStringRef（account/service，受控 ASCII，UTF-8 无嵌入 NUL）。
     *  cinterop 将 `CFStringCreateWithCString` 的 `const char*` 映射为 `String?`，故直接传 Kotlin String。 */
    private fun cfString(s: String): CFTypeRef? =
        CFStringCreateWithCString(kCFAllocatorDefault, s, kCFStringEncodingUTF8)

    /** String → CFDataRef（UTF-8 字节，不含尾 NUL），二进制安全，用于 kSecValueData。 */
    private fun MemScope.cfData(s: String): CFDataRef? =
        if (s.isEmpty()) {
            CFDataCreate(kCFAllocatorDefault, null, 0L)
        } else {
            // CFDataCreate 的 bytes 形参为 CValuesRef<UByteVar>；UByteArray.toCValues() 直接命中类型。
            val bytes = s.encodeToByteArray().asUByteArray()
            CFDataCreate(kCFAllocatorDefault, bytes.toCValues(), bytes.size.toLong())
        }

    /** CFDataRef → Kotlin String（UTF-8）。 */
    private fun cfDataToString(data: CFDataRef?): String? {
        if (data == null) return null
        val len = CFDataGetLength(data).toInt()
        if (len <= 0) return ""
        val bytePtr = CFDataGetBytePtr(data) ?: return null
        return bytePtr.readBytes(len).decodeToString()
    }

    // ---------- 首次启动迁移：mm_secure.json → Keychain ----------

    /**
     * 读旧 `Documents/mm_secure.json`（V4 明文凭据），逐项写入 Keychain，成功后删除 JSON。
     * 文件不存在则无操作；解析失败/写入失败不删文件（下次启动重试，保证不丢凭据）。
     * 迁移成功即删文件 = 幂等（已迁移用户文件已不存在）。
     */
    private fun maybeMigrateFromJson() {
        val url = secureFileUrl() ?: return
        val data = try {
            NSData.dataWithContentsOfURL(url)
        } catch (e: Exception) {
            return
        } ?: return
        if (data.length == 0UL) return
        val map = try {
            parseSecureJson(data)
        } catch (e: Exception) {
            return // 解析失败：保留文件，下次启动重试，避免误删
        }
        if (map.isEmpty()) {
            // 空文件：无凭据残留，直接清理。
            deleteSecureFile(url)
            return
        }
        // 逐项写入 Keychain（init 早于任何外部 secure 调用，用 unlocked 变体不加锁）。
        var allWritten = true
        for ((k, v) in map) {
            try {
                keychainPut(k, v)
            } catch (e: Exception) {
                allWritten = false
            }
        }
        if (allWritten) {
            deleteSecureFile(url)
        }
    }

    /** 解析 mm_secure.json 的 `{"k":"v"}` 扁平结构为 map（与 V4 loadSecureMap 同语义）。 */
    private fun parseSecureJson(data: NSData): MutableMap<String, String> {
        val obj = NSJSONSerialization.JSONObjectWithData(data, NSJSONReadingAllowFragments, null)
        val result = mutableMapOf<String, String>()
        when (obj) {
            is Map<*, *> -> obj.forEach { (k, v) ->
                val ks = k as? String ?: return@forEach
                val vs = v as? String ?: return@forEach
                result[ks] = vs
            }
            else -> { /* 非 object：按空处理 */ }
        }
        return result
    }

    /** 删除 mm_secure.json 文件。 */
    private fun deleteSecureFile(url: NSURL) {
        try {
            NSFileManager.defaultManager().removeItemAtURL(url, null)
        } catch (e: Exception) {
            // 删除失败不阻断：残留文件凭据已迁完，下次启动再清。
        }
    }

    /** 返回 Documents/mm_secure.json 的 URL（仅迁移用）。 */
    private fun secureFileUrl(): NSURL? {
        val urls = NSFileManager.defaultManager()
            .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docUrl = urls.firstOrNull() as? NSURL ?: return null
        return docUrl.URLByAppendingPathComponent(SECURE_FILE_NAME)
    }

    private companion object {
        /** Keychain service 命名空间常量（service+account 联合定位凭据）。 */
        private const val SEC_SERVICE = "com.wgt.media.settings"
        /** V4 遗留明文凭据文件名。 */
        private const val SECURE_FILE_NAME = "mm_secure.json"
    }
}
