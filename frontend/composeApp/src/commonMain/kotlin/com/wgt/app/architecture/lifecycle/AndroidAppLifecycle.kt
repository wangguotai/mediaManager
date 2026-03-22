package com.wgt.app.architecture.lifecycle

import com.wgt.app.architecture.observer.ManagerObserver
import com.wgt.architecture.lifecycle.AppLifecycleManager
import com.wgt.architecture.lifecycle.AppLifecycleState

/**
 * Android平台应用生命周期集成
 * 通过Application回调手动管理应用生命周期并转发到AppLifecycleManager
 *
 * 使用方式：在Application的相应生命周期回调中调用对应方法
 */
object AndroidAppLifecycle {

    private var isInitialized = false

    /**
     * 初始化Android应用生命周期
     * 应在Application.onCreate()中调用，会触发AppLaunched事件
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        // 应用启动
        AppLifecycleManager.registerObserver(ManagerObserver())
        AppLifecycleManager.notifyAppLaunched()
    }

    /**
     * 应用进入前台
     * 应在Activity.onActivityStarted()的第一个Activity时调用
     */
    fun onAppForeground() {
        val currentState = AppLifecycleManager.getCurrentState()
        if (currentState == AppLifecycleState.BACKGROUND) {
            AppLifecycleManager.notifyAppForeground()
        }
    }

    /**
     * 应用进入后台
     * 应在Activity.onActivityStopped()的最后一个Activity时调用
     */
    fun onAppBackground() {
        val currentState = AppLifecycleManager.getCurrentState()
        if (currentState == AppLifecycleState.FOREGROUND || currentState == AppLifecycleState.LAUNCHED) {
            AppLifecycleManager.notifyAppBackground()
        }
    }

    /**
     * 应用即将终止
     * 应在Application.onTerminate()中调用（仅调试模式有效）
     */
    fun onAppTerminating() {
        AppLifecycleManager.notifyAppTerminating()
    }
}