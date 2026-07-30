package com.wgt.media

/**
 * 备份策略前置条件检查（V6 §2.1）。
 *
 * 自动备份执行前据此判断当前网络/电量是否满足用户设置的策略（仅 WiFi / 仅充电）。
 * 平台实现：
 * - Android：ConnectivityManager 判断 WiFi、BatteryManager 判断充电状态。
 * - iOS：NWPathMonitor 判断 WiFi、UIDevice.batteryState 判断充电状态。
 *
 * commonMain 安全：不依赖平台 API，通过 expect/actual 桥接。
 */

/**
 * 查询当前网络是否为 WiFi（非移动数据）。
 *
 * @return true 表示当前通过 WiFi 接入网络；false 表示移动数据或无网络。
 *         平台无法判断时返回 true（宽松策略，避免误阻合法备份）。
 */
expect fun isOnWifi(): Boolean

/**
 * 查询当前设备是否正在充电（含 USB 充电/无线充电）。
 *
 * @return true 表示正在充电；false 表示电池供电。
 *         平台无法判断时返回 true（宽松策略，避免误阻合法备份）。
 */
expect fun isCharging(): Boolean

/**
 * 综合判断当前是否满足备份策略前置条件。
 *
 * 按 [SettingsState.backupWifiOnly] 与 [SettingsState.backupChargingOnly] 开关组合判断：
 * - 仅 WiFi 开启时，非 WiFi 网络下返回 false；
 * - 仅充电开启时，非充电状态下返回 false；
 * - 两者都关闭时始终返回 true。
 *
 * @return true 表示当前满足策略、可执行备份；false 表示策略不满足、应暂停。
 */
fun shouldBackupByPolicy(): Boolean {
    if (SettingsState.backupWifiOnly && !isOnWifi()) return false
    if (SettingsState.backupChargingOnly && !isCharging()) return false
    return true
}
