package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSLock
import platform.Foundation.NSFileManager
import platform.Foundation.NSJSONReadingAllowFragments
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSJSONWritingPrettyPrinted
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.writeToURL
import platform.Foundation.dataWithContentsOfURL

/**
 * iOS 键值存储实现。
 *
 * 分两类存储，与 [expect][SettingsStorage] 注释一致：
 * - 普通设置 [getString]/[putString]：仍走 NSUserDefaults（线程安全、非敏感项）。
 * - 敏感凭据 [getSecureString]/[putSecureString]/[removeSecureString]：落到
 *   `Documents/mm_secure.json`，格式为 `{"key":"val"}` 的扁平 JSON。
 *
 * 选择文件方案而不是 Keychain 的原因：Keychain 在 Kotlin/Native 互操作与
 * Simulator/真机配置上较脆弱，而凭据（JWT token、用户名）只需进程内可读、
 * 跨重启可恢复即可。文件落在 app sandbox 的 Documents 下，受沙箱保护，
 * 其它 app 不可访问。
 *
 * 并发：Kotlin/Native 没有 `synchronized`，secure 全部方法改用 [NSLock]
 * 串行化，在锁内完成 load→mutate→store，避免多次 put 期间读到半写状态。
 * 写入使用 atomic 写入（先写临时文件再 rename），保证不会出现损坏的半截 JSON。
 *
 * 平台 API 注意：[NSData] 与 [NSJSONSerialization] 的相关 API 属于
 * `ExperimentalForeignApi`，本类整体 opt-in。
 */
@OptIn(ExperimentalForeignApi::class)
actual class SettingsStorage actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults()

    /** secure 文件持久化锁。secure 全部方法都在此锁内完成 load→mutate→store。 */
    private val lock = NSLock()

    actual fun getString(key: String, default: String): String {
        return defaults.stringForKey(key) ?: default
    }

    actual fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    // V4→fix: 敏感凭据改用文件方案（Documents/mm_secure.json）替代 NSUserDefaults。
    actual fun getSecureString(key: String, default: String): String {
        lock.lock()
        try {
            val map = loadSecureMap()
            return map[key] ?: default
        } finally {
            lock.unlock()
        }
    }

    actual fun putSecureString(key: String, value: String) {
        lock.lock()
        try {
            val map = loadSecureMap()
            map[key] = value
            saveSecureMap(map)
        } finally {
            lock.unlock()
        }
    }

    actual fun removeSecureString(key: String) {
        lock.lock()
        try {
            val map = loadSecureMap()
            val existed = map.remove(key) != null
            if (existed) {
                saveSecureMap(map)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 读取 `mm_secure.json` 为可变 map。文件不存在/解析失败时返回空 map
     *（不抛异常，避免冷启动因半写/损坏文件崩溃而无法进入登录页）。
     */
    private fun loadSecureMap(): MutableMap<String, String> {
        val url = secureFileUrl() ?: return mutableMapOf()
        val data = NSData.dataWithContentsOfURL(url) ?: return mutableMapOf()
        if (data.length == 0UL) return mutableMapOf()
        return try {
            val obj = NSJSONSerialization.JSONObjectWithData(
                data,
                NSJSONReadingAllowFragments,
                null
            )
            val result = mutableMapOf<String, String>()
            when (obj) {
                is Map<*, *> -> {
                    obj.forEach { (k, v) ->
                        val ks = k as? String ?: return@forEach
                        val vs = v as? String ?: return@forEach
                        result[ks] = vs
                    }
                }
                else -> { /* 非 object，按空处理 */ }
            }
            result
        } catch (e: Exception) {
            // 解析失败：文件损坏或被截断，按空 map 重写，避免后续持续失败
            mutableMapOf()
        }
    }

    /** 把 map 序列化为 JSON 并原子写入 `mm_secure.json`。 */
    private fun saveSecureMap(map: MutableMap<String, String>) {
        val url = secureFileUrl() ?: return
        try {
            val nsData = NSJSONSerialization.dataWithJSONObject(
                map,
                NSJSONWritingPrettyPrinted,
                null
            ) ?: return
            nsData.writeToURL(url, atomically = true)
        } catch (e: Exception) {
            // 写失败不抛出：上层凭据写入失败属可降级场景（下次登录可重写），
            // 但这里记录不到日志组件，保持与原 NSUserDefaults 方案一致的静默语义。
        }
    }

    /**
     * 返回 `Documents/mm_secure.json` 的 URL。Documents 不存在时返回 null
     *（理论上 sandbox 总存在，nil 作防御）。
     */
    private fun secureFileUrl(): NSURL? {
        val urls = NSFileManager.defaultManager()
            .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val docUrl = urls.firstOrNull() as? NSURL ?: return null
        return docUrl.URLByAppendingPathComponent("mm_secure.json")
    }
}
