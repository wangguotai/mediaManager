package com.wgt.rn_module

import com.wgt.architecture.di.Lifecycle
import com.wgt.architecture.manager.registerManager

/**
 * RN Manager 注册函数
 * 
 * 在应用启动时调用，将 RnManager 注册到全局 Manager 系统
 * 
 * 使用示例：
 * ```kotlin
 * fun InitManager() {
 *     // 注册其他 Manager...
 *     initRnManager()
 * }
 * ```
 */
fun initRnManager() {
    registerManager<IRnManager>(Lifecycle.SINGLETON) {
        // Android 平台通过 RnManager.getInstance() 获取
        // iOS 平台通过 IRnManager 的空实现
        RnManagerProvider.getManager()
    }
}

/**
 * RN Manager 提供者
 * 平台特定的实现
 */
expect object RnManagerProvider {
    fun getManager(): IRnManager
}
