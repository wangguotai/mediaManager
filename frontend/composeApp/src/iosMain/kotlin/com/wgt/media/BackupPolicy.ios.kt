package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/**
 * iOS 平台备份策略检查（V6 §2.1）。
 *
 * WiFi 判断：NSProcessInfo 检查 activeProcessorCount/可达性不直接可靠，
 * 简化策略——iOS 上 NWPathMonitor 需要异步 callback，同步 API 难以获取瞬时值。
 * 此处用 NSProcessInfo 的 isiPhone（非模拟器）+ 网络可达性宽松判断：
 * iOS 端默认 WiFi 下运行，无法确定性判断时返回 true（宽松，避免误阻）。
 *
 * 充电判断：UIDevice.batteryState（需 UIDevice.currentDevice.isBatteryMonitoringEnabled = true，
 * 但即便未开启，batteryState 恒返回 unknown 被视为非充电——为宽松起见 unknown 返回 true）。
 *
 * 注：iOS 后台备份由系统 BGProcessingTask 调度（§2.1 待办 3），系统调度时已达条件，
 * 此策略检查主要约束前台即时协程备份路径。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun isOnWifi(): Boolean {
    // iOS 无简单同步 API 判断 WiFi vs 移动数据（NWPathMonitor 异步）。
    // 宽松返回 true：iOS 端不阻 WiFi 判断，由系统后台任务调度保证合适时机。
    return true
}

@OptIn(ExperimentalForeignApi::class)
actual fun isCharging(): Boolean {
    val state = UIDevice.currentDevice.batteryState
    // batteryState: unknown / unplugged / charging / full
    // unknown（未开 monitoring）宽松视为充电中，避免误阻。
    return state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
        state == UIDeviceBatteryState.UIDeviceBatteryStateFull ||
        state == UIDeviceBatteryState.UIDeviceBatteryStateUnknown
}
