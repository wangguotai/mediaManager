package com.wgt.rn.host

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.facebook.react.ReactRootView
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native View 管理器
 * 
 * 用于在原生 Android 应用中嵌入 React Native 视图
 * 
 * 注意：RN 0.82+ 新架构推荐使用 ReactHost API，但为了兼容性，
 * 这里使用传统的 ReactRootView + ReactNativeHost 方式
 */
class ReactViewManager(private val context: Context) {

    private var reactRootView: ReactRootView? = null
    private var reactContext: ReactContext? = null

    /**
     * 创建 ReactRootView
     * @param moduleName React Native 模块名称（在 AppRegistry.registerComponent 中注册的名称）
     * @param initialProps 传递给 React Native 的初始属性
     */
    fun createReactRootView(
        moduleName: String,
        initialProps: Bundle? = null
    ): ReactRootView {
        // 确保 ReactHostManager 已初始化
        if (!ReactHostManager.getInstance().isInitialized()) {
            throw IllegalStateException("ReactHostManager not initialized. Call initialize() first.")
        }

        val reactNativeHost = ReactHostManager.getInstance().getReactNativeHost()
            ?: throw IllegalStateException("ReactNativeHost not available")

        reactRootView = ReactRootView(context).apply {
            // RN 0.82+：使用 ReactNativeHost 初始化
            startReactApplication(reactNativeHost.reactInstanceManager, moduleName, initialProps)
        }

        // 监听 ReactContext 初始化
        reactNativeHost.reactInstanceManager.addReactInstanceEventListener(
            object : com.facebook.react.ReactInstanceManager.ReactInstanceEventListener {
                override fun onReactContextInitialized(reactContext: ReactContext) {
                    this@ReactViewManager.reactContext = reactContext
                    // 初始化完成后移除监听器
                    reactNativeHost.reactInstanceManager.removeReactInstanceEventListener(this)
                }
            }
        )

        return reactRootView!!
    }

    /**
     * 将 ReactRootView 添加到容器
     */
    fun attachToContainer(container: ViewGroup, moduleName: String, initialProps: Map<String, Any>? = null) {
        // 如果已经存在，先移除
        reactRootView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        val bundle = initialProps?.toBundle()
        val view = createReactRootView(moduleName, bundle)
        container.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * 从容器中移除 ReactRootView
     */
    fun detachFromContainer(container: ViewGroup) {
        reactRootView?.let {
            container.removeView(it)
        }
    }

    /**
     * 销毁 ReactRootView
     */
    fun destroy() {
        reactRootView?.unmountReactApplication()
        reactRootView = null
        reactContext = null
    }

    /**
     * 发送事件到 React Native
     */
    fun sendEvent(eventName: String, params: Map<String, Any>?) {
        val context = reactContext ?: ReactHostManager.getInstance().getCurrentReactContext() ?: return
        
        // 使用 RCTDeviceEventEmitter 发送事件
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(eventName, params?.toWritableMap())
    }

    /**
     * 设置 ReactContext 回调
     */
    fun setReactContextListener(listener: (ReactContext) -> Unit) {
        val reactHostManager = ReactHostManager.getInstance()
        val reactInstanceManager = reactHostManager.getReactInstanceManager() ?: return
        
        reactInstanceManager.addReactInstanceEventListener(object : 
            com.facebook.react.ReactInstanceManager.ReactInstanceEventListener {
            override fun onReactContextInitialized(context: ReactContext) {
                reactContext = context
                listener(context)
                reactInstanceManager.removeReactInstanceEventListener(this)
            }
        })
    }

    /**
     * 将 Map 转换为 WritableMap
     */
    private fun Map<String, Any>.toWritableMap(): WritableMap {
        val map = com.facebook.react.bridge.Arguments.createMap()
        forEach { (key, value) ->
            when (value) {
                is String -> map.putString(key, value)
                is Int -> map.putInt(key, value)
                is Double -> map.putDouble(key, value)
                is Boolean -> map.putBoolean(key, value)
                is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                    map.putMap(key, (value as Map<String, Any>).toWritableMap())
                else -> map.putString(key, value.toString())
            }
        }
        return map
    }

    /**
     * 将 Map 转换为 Bundle
     */
    private fun Map<String, Any>.toBundle(): Bundle {
        val bundle = Bundle()
        forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
        return bundle
    }
}

/**
 * 检查 ReactHostManager 是否已初始化
 */
fun ReactHostManager.isInitialized(): Boolean {
    return this.getReactNativeHost() != null
}
