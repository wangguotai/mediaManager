package com.wgt.app.architecture.observer

import com.wgt.architecture.di.generated.InitFeature
import com.wgt.architecture.lifecycle.AppLifecycleObserver
import com.wgt.architecture.lifecycle.AppLifecycleState
import com.wgt.architecture.manager.InitManager
import com.wgt.platform.logger.logger

/**
 * Manager生命周期观察者
 * 注册到AppLifecycleManager中，在应用启动时初始化Manager系统
 */
class ManagerObserver : AppLifecycleObserver {
    private val _tag = "ManagerObserver"

    override fun onAppLaunched() {
        logger.info(_tag, "onAppLaunched: Initializing Manager system")
        // 初始化Manager系统
        InitManager()
        InitFeature()
    }

    override fun onAppForeground() {
        logger.info(_tag, "onAppForeground")
    }

    override fun onAppBackground() {
        logger.info(_tag, "onAppBackground")
    }

    override fun onAppTerminating() {
        logger.info(_tag, "onAppTerminating")
    }

    override fun onLifecycleStateChanged(state: AppLifecycleState) {
        logger.info(_tag, "onLifecycleStateChanged: $state")
    }
}