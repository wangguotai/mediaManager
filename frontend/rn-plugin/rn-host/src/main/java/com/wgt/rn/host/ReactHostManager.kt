package com.wgt.rn.host

import android.app.Activity
import android.app.Application
import android.content.Context
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.LifecycleState
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

/**
 * React Native 宿主管理器
 * 
 * 负责初始化 React Native 环境，管理 ReactInstance 生命周期
 * 
 * 注意：这是一个库模块，不直接使用 PackageList（RN CLI 自动生成）
 * 外部模块可以通过 addPackage() 方法添加自定义 ReactPackage
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
    private var application: Application? = null
    private var isInitialized = false
    private val externalPackages = mutableListOf<ReactPackage>()

    /**
     * 初始化 React Native 环境
     * @param application Application 实例
     * @param packages 可选的初始 ReactPackage 列表
     */
    @JvmOverloads
    fun initialize(
        application: Application,
        packages: List<ReactPackage> = emptyList()
    ) {
        if (isInitialized) return
        
        this.application = application
        externalPackages.addAll(packages)
        
        // 初始化 SoLoader
        SoLoader.init(application, OpenSourceMergedSoMapping)
        
        // 创建 ReactNativeHost
        reactNativeHost = createReactNativeHost(application)
        
        // 预创建 ReactInstanceManager（在后台线程）
        // 注意：不在这里调用 createReactContextInBackground，而是在有 Activity 时调用
        reactNativeHost?.reactInstanceManager
        
        isInitialized = true
    }

    /**
     * 在 Activity 创建时调用，启动 React 上下文
     * 
     * @param activity 当前 Activity
     */
    fun onActivityCreate(activity: Activity) {
        val reactInstanceManager = reactNativeHost?.reactInstanceManager ?: return
        
        // 如果还没有创建 ReactContext，则在 Activity 中启动
        if (!reactInstanceManager.hasStartedCreatingInitialContext()) {
            reactInstanceManager.createReactContextInBackground()
        }
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
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean {
        return isInitialized && reactNativeHost != null
    }

    /**
     * 添加 ReactPackage（用于 rn-android 模块注册自定义 Package）
     * 
     * 注意：必须在 initialize() 之前调用，或者在添加后重新创建 ReactInstanceManager
     * 
     * @param packageInstance ReactPackage 实例
     */
    fun addPackage(packageInstance: ReactPackage) {
        externalPackages.add(packageInstance)
        
        // 如果已经初始化，需要重新创建 ReactInstanceManager
        if (isInitialized) {
            reactNativeHost?.reactInstanceManager?.destroy()
            application?.let { app ->
                reactNativeHost = createReactNativeHost(app)
            }
        }
    }

    /**
     * 添加多个 ReactPackage
     */
    fun addPackages(packages: List<ReactPackage>) {
        externalPackages.addAll(packages)
        
        // 如果已经初始化，需要重新创建 ReactInstanceManager
        if (isInitialized) {
            reactNativeHost?.reactInstanceManager?.destroy()
            application?.let { app ->
                reactNativeHost = createReactNativeHost(app)
            }
        }
    }

    /**
     * 获取已注册的 Packages 列表（用于调试）
     */
    fun getRegisteredPackages(): List<ReactPackage> {
        return externalPackages.toList()
    }

    /**
     * 创建 ReactNativeHost
     * 
     * 注意：不依赖 PackageList，而是使用外部传入的 packages
     */
    private fun createReactNativeHost(application: Application): ReactNativeHost {
        return object : ReactNativeHost(application) {
            override fun getUseDeveloperSupport(): Boolean {
                // 使用自动生成的 BuildConfig
                return com.wgt.rn.host.BuildConfig.DEBUG
            }

            override fun getPackages(): List<ReactPackage> {
                // 返回外部注册的 packages
                // rn-android 等模块应该通过 addPackage() 方法注册自己的 packages
                return externalPackages.toList()
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
     * 销毁 React Native 环境
     */
    fun destroy() {
        reactNativeHost?.reactInstanceManager?.destroy()
        reactNativeHost = null
        application = null
        externalPackages.clear()
        isInitialized = false
        instance = null
    }
}
