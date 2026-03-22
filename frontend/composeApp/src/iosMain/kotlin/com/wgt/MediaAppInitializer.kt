package com.wgt

import com.wgt.architecture.lifecycle.AppLifecycleManager
import com.wgt.app.architecture.observer.ManagerObserver
import com.wgt.platform.AppContext
import com.wgt.platform.logger.logger
import platform.UIKit.UIApplication
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS应用初始化器
 * 提供静态方法供Swift调用，用于初始化应用
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("MediaAppInitializer")
object MediaAppInitializer {
    private val _tag = "MediaAppInitializer"
    
    // Manager观察者实例
    private val managerObserver = ManagerObserver()
    
    /**
     * 应用启动完成时调用
     */
    fun onApplicationDidFinishLaunching(application: UIApplication): Boolean {
        logger.info(_tag, "Application did finish launching")
        
        // 初始化全局上下文
        AppContext.initialize(application)
        
        // 注册Manager观察者到AppLifecycleManager
        AppLifecycleManager.registerObserver(managerObserver)
        
        // 通知应用生命周期管理器应用已启动
        AppLifecycleManager.notifyAppLaunched()
        
        return true
    }
    
    /**
     * 应用进入后台时调用
     */
    fun onApplicationDidEnterBackground() {
        logger.info(_tag, "Application did enter background")
        AppLifecycleManager.notifyAppBackground()
    }
    
    /**
     * 应用进入前台时调用
     */
    fun onApplicationWillEnterForeground() {
        logger.info(_tag, "Application will enter foreground")
        AppLifecycleManager.notifyAppForeground()
    }
    
    /**
     * 应用即将终止时调用
     */
    fun onApplicationWillTerminate() {
        logger.info(_tag, "Application will terminate")
        AppLifecycleManager.notifyAppTerminating()
    }
}
