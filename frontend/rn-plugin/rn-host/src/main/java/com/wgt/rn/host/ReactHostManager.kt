package com.wgt.rn.host

import android.app.Activity
import android.app.Application
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.ReactContext
import com.facebook.soloader.SoLoader

/**
 * React Native 宿主管理器 (兼容模式)
 * 
 * 使用 ReactNativeHost 的受支持方法
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
        SoLoader.init(application, false)
        
        // 创建 ReactNativeHost（但不立即获取 ReactInstanceManager）
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
     * 添加 ReactPackage
     */
    fun addPackage(packageInstance: ReactPackage) {
        externalPackages.add(packageInstance)
    }

    /**
     * 添加多个 ReactPackage
     */
    fun addPackages(packages: List<ReactPackage>) {
        externalPackages.addAll(packages)
    }

    /**
     * 获取已注册的 Packages 列表
     */
    fun getRegisteredPackages(): List<ReactPackage> {
        return externalPackages.toList()
    }

    /**
     * 创建 ReactNativeHost
     */
    private fun createReactNativeHost(application: Application): ReactNativeHost {
        return object : ReactNativeHost(application) {
            override fun getUseDeveloperSupport(): Boolean {
                return com.wgt.rn.host.BuildConfig.DEBUG
            }

            override fun getPackages(): List<ReactPackage> {
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
        reactNativeHost = null
        application = null
        externalPackages.clear()
        isInitialized = false
        instance = null
    }
}
