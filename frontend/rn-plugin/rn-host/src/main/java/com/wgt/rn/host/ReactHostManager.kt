package com.wgt.rn.host

import android.app.Activity
import android.app.Application
import android.content.Context
import com.facebook.react.PackageList
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

/**
 * React Native 宿主管理器
 * 
 * 负责初始化 React Native 环境，管理 ReactInstance 生命周期
 */
class ReactHostManager private constructor() {

    companion object {
        @Volatile
        private var instance: ReactHostManager? = null

        fun getInstance(): ReactHostManager {
            return instance ?: synchronized(this) {
                instance ?: ReactHostManager().also { instance = it }
            }
        }
    }

    private var reactNativeHost: ReactNativeHost? = null
    private var reactInstanceManager: ReactInstanceManager? = null
    private var application: Application? = null
    private var isInitialized = false

    /**
     * 初始化 React Native 环境
     */
    fun initialize(application: Application) {
        if (isInitialized) return
        
        this.application = application
        
        // 初始化 SoLoader
        SoLoader.init(application, OpenSourceMergedSoMapping)
        
        // 创建 ReactNativeHost
        reactNativeHost = createReactNativeHost(application)
        
        isInitialized = true
    }

    /**
     * 获取 ReactNativeHost
     */
    fun getReactNativeHost(): ReactNativeHost? {
        return reactNativeHost
    }

    /**
     * 获取 ReactInstanceManager
     */
    fun getReactInstanceManager(): ReactInstanceManager? {
        return reactNativeHost?.reactInstanceManager
    }

    /**
     * 获取当前 ReactContext
     */
    fun getCurrentReactContext(): ReactContext? {
        return reactNativeHost?.reactInstanceManager?.currentReactContext
    }

    /**
     * 创建 ReactNativeHost
     */
    private fun createReactNativeHost(application: Application): ReactNativeHost {
        return object : ReactNativeHost(application) {
            override fun getUseDeveloperSupport(): Boolean {
                return BuildConfig.DEBUG
            }

            override fun getPackages(): List<ReactPackage> {
                return PackageList(this).packages.apply {
                    // 在这里添加额外的 packages
                    // 注意：rn-android 模块的 MediaManagerPackage 会在运行时通过反射添加
                    // 或者可以在这里手动添加，如果需要硬编码依赖
                }
            }

            override fun getJSMainModuleName(): String {
                return "index"
            }

            override fun getBundleAssetName(): String {
                return "index.android.bundle"
            }
        }
    }
    
    /**
     * 添加额外的 ReactPackage（用于 rn-android 模块注册自定义 Package）
     */
    fun addPackage(packageInstance: ReactPackage) {
        val host = reactNativeHost as? MutableReactNativeHost
        host?.addPackage(packageInstance)
    }

    /**
     * 销毁 React Native 环境
     */
    fun destroy() {
        reactInstanceManager?.destroy()
        reactInstanceManager = null
        reactNativeHost = null
        application = null
        isInitialized = false
        instance = null
    }
}
