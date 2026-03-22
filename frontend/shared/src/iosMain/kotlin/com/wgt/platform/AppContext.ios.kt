package com.wgt.platform

import platform.Foundation.NSBundle
import platform.UIKit.UIApplication

/**
 * iOS平台上下文实际实现
 */
private var _application: UIApplication? = null

internal actual fun initializePlatformContext(context: Any) {
    if (context is UIApplication) {
        _application = context
    } else {
        throw IllegalArgumentException("iOS平台需要传入UIApplication实例")
    }
}

internal actual fun isPlatformContextInitialized(): Boolean = _application != null

/**
 * iOS平台扩展：获取UIApplication实例
 */
val AppContext.application: UIApplication
    get() = _application 
        ?: throw IllegalStateException("AppContext尚未初始化，请在AppDelegate.didFinishLaunchingWithOptions()中调用AppContext.initialize(application)")

/**
 * iOS平台扩展：获取主Bundle
 */
val AppContext.mainBundle: NSBundle
    get() = NSBundle.mainBundle
