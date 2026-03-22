package com.wgt.rn.host

import android.content.Context
import android.view.ViewGroup
import com.facebook.react.ReactActivity
import com.facebook.react.ReactRootView
import com.facebook.react.bridge.ReactContext

/**
 * React Native View 管理器
 * 
 * 用于在原生 Android 应用中嵌入 React Native 视图
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
        initialProps: Map<String, Any>? = null
    ): ReactRootView {
        val reactHostManager = ReactHostManager.getInstance()
        val reactInstanceManager = reactHostManager.getReactInstanceManager()
            ?: throw IllegalStateException("ReactHostManager not initialized")

        reactRootView = ReactRootView(context).apply {
            startReactApplication(reactInstanceManager, moduleName, initialProps?.toBundle())
        }

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

        val view = createReactRootView(moduleName, initialProps)
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
        val catalystInstance = context.catalystInstance
        
        // 使用 RCTDeviceEventEmitter 发送事件
        context.getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
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
}
