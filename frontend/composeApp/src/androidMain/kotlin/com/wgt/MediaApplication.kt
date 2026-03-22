package com.wgt

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.wgt.app.architecture.lifecycle.AndroidAppLifecycle
import com.wgt.platform.AppContext
import com.wgt.platform.setCurrentActivity
import com.wgt.rn_android.RNPluginManager

/**
 * 媒体管理器应用类
 * 用于初始化全局上下文和应用程序级组件
 */
class MediaApplication : Application() {

    // 记录已启动的Activity数量
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()

        // 初始化全局上下文
        AppContext.initialize(this)

        // 注册Activity生命周期回调
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                // 第一个Activity启动时，应用进入前台
                if (startedActivityCount == 0) {
                    AndroidAppLifecycle.onAppForeground()
                }
                startedActivityCount++
            }

            override fun onActivityResumed(activity: Activity) {
                // 更新当前Activity
                AppContext.setCurrentActivity(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                // 清除当前Activity引用
                AppContext.setCurrentActivity(null)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                // 最后一个Activity停止时，应用进入后台
                if (startedActivityCount == 0) {
                    AndroidAppLifecycle.onAppBackground()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                // 如果当前Activity被销毁，清除引用
                AppContext.setCurrentActivity(null)
            }
        })

        // 初始化应用生命周期（触发AppLaunched）
        AndroidAppLifecycle.initialize()

        // 初始化 React Native
        RNPluginManager.getInstance().initialize(this)

        // 可以在这里初始化其他全局组件
        // 例如：数据库、网络客户端、日志系统等
    }

    override fun onTerminate() {
        super.onTerminate()
        // 应用终止（仅调试模式有效）
        AndroidAppLifecycle.onAppTerminating()
    }
}
