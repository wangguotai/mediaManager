package com.wgt.rn_android

import android.app.Application
import android.content.Context
import android.view.ViewGroup
import com.wgt.rn.host.ReactHostManager
import com.wgt.rn.host.ReactViewManager

/**
 * React Native 插件管理器
 * 
 * 提供给 Media Manager 应用使用的 RN 集成入口
 */
class RNPluginManager private constructor() {

    companion object {
        @Volatile
        private var instance: RNPluginManager? = null

        fun getInstance(): RNPluginManager {
            return instance ?: synchronized(this) {
                instance ?: RNPluginManager().also { instance = it }
            }
        }
    }

    private var isInitialized = false
    private var viewManager: ReactViewManager? = null

    /**
     * 初始化 React Native 插件
     * 需要在 Application.onCreate() 中调用
     */
    fun initialize(application: Application) {
        if (isInitialized) return

        // 初始化 React Host Manager
        ReactHostManager.getInstance().initialize(application)

        isInitialized = true
    }

    /**
     * 获取 React Host Manager
     */
    fun getReactHostManager(): ReactHostManager {
        checkInitialized()
        return ReactHostManager.getInstance()
    }

    /**
     * 创建 React View Manager
     */
    fun createViewManager(context: Context): ReactViewManager {
        checkInitialized()
        return ReactViewManager(context).also {
            viewManager = it
        }
    }

    /**
     * 加载 React Native 页面到容器中
     * @param container 容器 ViewGroup
     * @param moduleName React Native 模块名称
     * @param initialProps 传递给 RN 的初始属性
     */
    fun loadReactModule(
        container: ViewGroup,
        moduleName: String,
        initialProps: Map<String, Any>? = null
    ) {
        checkInitialized()
        val context = container.context
        val manager = createViewManager(context)
        manager.attachToContainer(container, moduleName, initialProps)
    }

    /**
     * 发送事件到 React Native
     */
    fun sendEvent(eventName: String, params: Map<String, Any>?) {
        viewManager?.sendEvent(eventName, params)
    }

    /**
     * 销毁插件管理器
     */
    fun destroy() {
        viewManager?.destroy()
        viewManager = null
        ReactHostManager.getInstance().destroy()
        isInitialized = false
        instance = null
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("RNPluginManager not initialized. Call initialize() first.")
        }
    }
}
