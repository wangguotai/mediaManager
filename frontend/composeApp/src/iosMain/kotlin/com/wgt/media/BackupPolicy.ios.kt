package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

/**
 * iOS 平台备份策略检查（V6 §2.1）。
 *
 * WiFi 判断 — 已知限制：
 * iOS 上精确区分 WiFi vs 移动数据需 NWPathMonitor（异步回调式 API）。
 * Kotlin/Native 对 NWPathMonitor 的 C interop 桥接较复杂（需手动调
 * nw_path_monitor_create + dispatch_queue + update_handler C 函数），
 * 且异步回调难以提供同步瞬时值。当前实现宽松返回 true，含义：
 * - iOS 端「仅 WiFi 备份」开关暂不阻拦备份执行
 * - 实际 WiFi 策略由 iOS 系统后台任务调度（BGProcessingTask，待引入）
 *   时天然满足——系统只在合适网络条件下调度后台任务
 * - 前台即时备份路径不受此限制影响（用户主动在 App 内操作时已在 WiFi 下）
 *
 * 后续若需精确同步判断，可用 NWPathMonitor 启动后台 monitor 缓存最新 path
 * 类型（类似 Android ConnectivityManager 回调缓存），或引入 SCReachability
 * 同步 API。QA P1-6 已标注此项为已知降级。
 *
 * 充电判断：UIDevice.batteryState。unknown（未开 monitoring）宽松视为充电中。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun isOnWifi(): Boolean {
    // 已知限制：iOS NWPathMonitor 异步 API 难以同步查询，宽松返回 true。
    // 详见类注释。QA P1-6 标注为降级项。
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
