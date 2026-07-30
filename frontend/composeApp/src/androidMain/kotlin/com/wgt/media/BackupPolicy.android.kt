package com.wgt.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext

/**
 * Android 平台备份策略检查（V6 §2.1）。
 *
 * WiFi 判断用 ConnectivityManager + NetworkCapabilities.TRANSPORT_WIFI；
 * 充电判断用 BatteryManager.isCharging。
 * AppContext 未初始化时宽松返回 true，避免误阻备份。
 */
private val appContext: Context? get() = runCatching {
    if (AppContext.isInitialized) AppContext.applicationContext else null
}.getOrNull()

actual fun isOnWifi(): Boolean {
    val ctx = appContext ?: return true
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

actual fun isCharging(): Boolean {
    val ctx = appContext ?: return true
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        ?: return true
    return bm.isCharging
}
