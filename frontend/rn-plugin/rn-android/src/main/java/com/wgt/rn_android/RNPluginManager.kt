package com.wgt.rn_android

import android.app.Application
import android.content.Context
import android.view.ViewGroup
import com.wgt.rn.host.ReactHostManager
import com.wgt.rn.host.ReactViewManager
import com.wgt.rn_android.bridge.MediaManagerPackage

/**
 * React Native 插件管理器
 * 
 * 提供给 Media Manager 应用使用的 RN 集成入口
 * 
 * 使用方法：
 * 1. 在 Application.onCreate() 中调用 initialize()
 * 2. 使用 loadReactModule() 加载 RN 页面
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
     * 
     * 需要在 Application.onCreate() 中调用，且只需调用一次
     * 
     * @param application Application 实例
     */
    fun initialize(application: Application) {
        if (isInitialized) return

        // 初始化 React Host Manager
        // 传入 MediaManagerPackage，注册我们的自定义 Native Module
        val reactHostManager = ReactHostManager.getInstance()
        reactHostManager.addPackage(MediaManagerPackage())
        reactHostManager.initialize(application)

        isInitialized = true
    }

    /**
     * 获取 React Host Manager
     * 
     * 用于高级操作，如获取 ReactContext、发送事件等
     */
    fun getReactHostManager(): ReactHostManager {
        checkInitialized()
        return ReactHostManager.getInstance()
    }

    /**
     * 创建 React View Manager
     * 
     * @param context Context 实例
     * @return ReactViewManager 实例
     */
    fun createViewManager(context: Context): ReactViewManager {
        checkInitialized()
        return ReactViewManager(context).also {
            viewManager = it
        }
    }

    /**
     * 加载 React Native 页面到容器中
     * 
     * @param container 容器 ViewGroup
     * @param moduleName React Native 模块名称（对应 AppRegistry.registerComponent 注册的名称）
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
     * 
     * @param eventName 事件名称
     * @param params 事件参数
     */
    fun sendEvent(eventName: String, params: Map<String, Any>?) {
        viewManager?.sendEvent(eventName, params)
    }

    /**
     * 销毁插件管理器
     * 
     * 注意：通常在 Application 终止时调用，不要在 Activity 销毁时调用
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
            throw IllegalStateException(
                "RNPluginManager not initialized. " +
                "Call RNPluginManager.getInstance().initialize(application) in Application.onCreate() first."
            )
        }
    }
}
