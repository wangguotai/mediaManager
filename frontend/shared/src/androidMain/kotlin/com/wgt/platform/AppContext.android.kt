package com.wgt.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context

/**
 * Android平台上下文实际实现
 */
private var _application: Application? = null
@SuppressLint("StaticFieldLeak")
private var _currentActivity: Activity? = null

internal actual fun initializePlatformContext(context: Any) {
    if (context is Application) {
        _application = context
    } else {
        throw IllegalArgumentException("Android平台需要传入Application实例")
    }
}

internal actual fun isPlatformContextInitialized(): Boolean = _application != null

/**
 * 设置当前Activity
 * 应在Application的ActivityLifecycleCallbacks中调用
 */
fun AppContext.setCurrentActivity(activity: Activity?) {
    _currentActivity = activity
}

/**
 * 获取当前Activity
 * @return 当前活跃的Activity，如果没有则返回null
 */
fun AppContext.getCurrentActivity(): Activity? = _currentActivity

/**
 * Android平台扩展：获取ApplicationContext
 */
val AppContext.applicationContext: Context
    get() = _application?.applicationContext 
        ?: throw IllegalStateException("AppContext尚未初始化，请在Application.onCreate()中调用AppContext.initialize(this)")

/**
 * Android平台扩展：获取Application实例
 */
val AppContext.application: Application
    get() = _application 
        ?: throw IllegalStateException("AppContext尚未初始化，请在Application.onCreate()中调用AppContext.initialize(this)")
