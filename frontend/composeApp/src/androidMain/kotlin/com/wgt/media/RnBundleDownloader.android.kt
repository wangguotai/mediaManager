package com.wgt.media

import android.content.Context
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import java.io.File

/**
 * Android 平台 RN bundle 缓存实现（V7 §3.2）。
 *
 * 缓存目录: App cacheDir/rn-bundles/<name>.bundle
 */

actual fun readCachedBundlePath(bundleName: String): String? {
    val ctx = runCatching {
        if (AppContext.isInitialized) AppContext.applicationContext else null
    }.getOrNull() ?: return null
    val file = File(ctx.cacheDir, "rn-bundles/$bundleName.bundle")
    return if (file.exists() && file.length() > 0) file.absolutePath else null
}

actual fun writeBundleToCache(bundleName: String, data: ByteArray): String {
    val ctx = runCatching {
        if (AppContext.isInitialized) AppContext.applicationContext else null
    }.getOrNull() ?: throw IllegalStateException("AppContext not initialized")
    val dir = File(ctx.cacheDir, "rn-bundles").apply { mkdirs() }
    val file = File(dir, "$bundleName.bundle")
    file.writeBytes(data)
    return file.absolutePath
}

// V7 §3.2：版本号用 SharedPreferences 持久化（key: rn_bundle_version_<name>）

actual fun readCachedBundleVersion(bundleName: String): String? {
    val ctx = runCatching {
        if (AppContext.isInitialized) AppContext.applicationContext else null
    }.getOrNull() ?: return null
    val prefs = ctx.getSharedPreferences("rn-bundle-versions", Context.MODE_PRIVATE)
    return prefs.getString("version_$bundleName", null)
}

actual fun writeCachedBundleVersion(bundleName: String, version: String) {
    val ctx = runCatching {
        if (AppContext.isInitialized) AppContext.applicationContext else null
    }.getOrNull() ?: return
    val prefs = ctx.getSharedPreferences("rn-bundle-versions", Context.MODE_PRIVATE)
    prefs.edit().putString("version_$bundleName", version).apply()
}
