package com.wgt.rnhost

import android.app.Application
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.soloader.SoLoader

/**
 * React Native Host 管理器
 * 
 * 标准的 RN 集成方式，按照官方文档实现
 */
class RNHostManager private constructor() {

    companion object {
        @Volatile
        private var instance: RNHostManager? = null

        fun getInstance(): RNHostManager {
            return instance ?: synchronized(this) {
                instance ?: RNHostManager().also { instance = it }
            }
        }
    }

    private var reactNativeHost: ReactNativeHost? = null
    private var isInitialized = false

    /**
     * 初始化 RN 环境
     */
    fun initialize(application: Application) {
        if (isInitialized) return

        // 初始化 SoLoader
        SoLoader.init(application, false)

        isInitialized = true
    }

    /**
     * 获取 ReactNativeHost
     */
    fun getReactNativeHost(): ReactNativeHost? {
        return reactNativeHost
    }

    /**
     * 设置 ReactNativeHost
     */
    fun setReactNativeHost(host: ReactNativeHost) {
        this.reactNativeHost = host
    }

    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }
}
