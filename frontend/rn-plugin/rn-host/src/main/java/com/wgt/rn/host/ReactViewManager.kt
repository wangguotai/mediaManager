package com.wgt.rn.host

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import com.facebook.react.ReactRootView
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native View 管理器 (兼容模式)
 * 
 * 使用 ReactRootView 和 ReactNativeHost
 */
class ReactViewManager(private val context: Context) {

    private var reactRootView: ReactRootView? = null

    /**
     * 创建并附加 ReactRootView
     * @param container 容器 ViewGroup
     * @param moduleName React Native 模块名称
     * @param initialProps 传递给 React Native 的初始属性
     */
    fun attachToContainer(
        container: ViewGroup,
        moduleName: String,
        initialProps: Map<String, Any>? = null
    ) {
        // 确保 ReactHostManager 已初始化
        val reactNativeHost = ReactHostManager.getInstance().getReactNativeHost()
            ?: throw IllegalStateException("ReactHostManager not initialized. Call initialize() first.")

        // 移除之前的视图
        reactRootView?.let { rootView ->
            (rootView.parent as? ViewGroup)?.removeView(rootView)
            rootView.unmountReactApplication()
        }

        // 创建 ReactRootView
        reactRootView = ReactRootView(context).apply {
            // 注意：这里会触发 ReactInstanceManager 的创建
            // 但由于 RN 0.82 新架构限制，可能只能在 Activity 上下文中工作
            startReactApplication(
                reactNativeHost.reactInstanceManager,
                moduleName,
                initialProps?.toBundle()
            )
        }

        // 添加到容器
        container.addView(reactRootView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * 从容器中移除
     */
    fun detachFromContainer(container: ViewGroup) {
        reactRootView?.let { rootView ->
            container.removeView(rootView)
        }
    }

    /**
     * 销毁
     */
    fun destroy() {
        reactRootView?.unmountReactApplication()
        reactRootView = null
    }

    /**
     * 发送事件到 React Native
     */
    fun sendEvent(eventName: String, params: Map<String, Any>?) {
        val context = ReactHostManager.getInstance().getCurrentReactContext() ?: return
        
        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(eventName, params?.toWritableMap())
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
