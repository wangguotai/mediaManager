package com.wgt.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import java.io.File

/**
 * Android 平台 [OfflineCacheManager] 实现 —— App 私有 [Context.getCacheDir] 作缓存目录，
 * `ConnectivityManager` 探测网络可达性。文件读写走 `java.io.File`（JVM common 的常规路径）。
 *
 * cacheDir 是 App 私有缓存区，系统在低存储时可清理、无需运行时权限；与
 * [PersistentFileStore] 走 [Context.getFilesDir]（持久区）相区分 —— 离线缩略图是可重建的
 * 缓存数据，放 cacheDir 而非 filesDir 以与系统缓存语义对齐。
 */

actual fun getOfflineCacheDir(): String {
    val dir = File(AppContext.applicationContext.cacheDir, "offline_thumbs")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}

actual fun platformFileExists(path: String): Boolean {
    return try {
        val f = File(path)
        f.exists() && f.isFile
    } catch (e: Exception) {
        // 路径非法等异常按"不存在"处理，供 common 层缓存命中判断统一为 false。
        false
    }
}

actual fun platformWriteBytes(path: String, bytes: ByteArray) {
    try {
        val f = File(path)
        f.parentFile?.takeIf { !it.exists() }?.mkdirs()
        f.writeBytes(bytes)
    } catch (e: Exception) {
        // 静默：与 common 层 downloadOne 的 try/catch 协作 —— 失败记日志于 prefetch 流，
        // 此处不重复打印，避免放大弱网场景的日志噪声。
    }
}

/**
 * Android 读取本地缓存文件字节 —— 供 [OfflineCacheManager] 离线缩略图加载链路。
 * 文件不存在或 IO 异常返回 null，调用方按缓存未命中处理（回退 HTTP 或占位图）。
 */
actual fun platformReadBytes(path: String): ByteArray? {
    return try {
        val f = File(path)
        if (!f.exists() || !f.isFile) return null
        f.readBytes()
    } catch (e: Exception) {
        null
    }
}

/**
 * 探测 Android 当前是否具有可用的网络连接。
 *
 * Android 6.0+ 不再用 `getActiveNetworkInfo().isConnected`（已废弃），改用
 * `getActiveNetwork` + `getNetworkCapabilities`：有 active network 且 capabilities 报告
 * 含 `NET_CAPABILITY_INTERNET` 与 `TRANSPORT_WIFI/CELLULAR/ETHERNET` 之一即视为在线。
 * 任一环节为空（飞行模式 / 未激活）视为离线。
 *
 * 缓存读路径 [OfflineCacheManager.getCachedThumbnailPath] 不依赖本函数 —— 离线时仍可命中
 * 本地图；本函数仅作为 [OfflineCacheManager.isOfflineMode] 的启发式依据（提示 UI 是否
 * 显示"离线"徽标或触发 prefetch）。
 */
actual fun platformNetworkAvailable(): Boolean {
    return try {
        val ctx = AppContext.applicationContext
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
    } catch (e: Exception) {
        // 权限缺失或反射异常等罕见情形 —— 保守按"在线"返回 true，避免误判离线导致
        // 预缓存被跳过（预缓存失败本身不阻塞业务，宁可尝试）。
        true
    }
}
