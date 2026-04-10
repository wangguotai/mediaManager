package com.wgt.rn_module

import com.wgt.architecture.di.annotations.ManagerProvider

/**
 * RN Manager - expect 定义
 *
 * Kotlin Multiplatform 期望声明，具体实现在各平台中
 */
@ManagerProvider
internal expect class RnManager private constructor() : IRnManager {

    companion object {
        /**
         * 获取 RnManager 实例
         * Android 版本需要传入 Application 参数
         * iOS 版本不需要参数
         */
        fun getInstance(): RnManager
    }

    /**
     * 获取 ReactHost（Android 特有）
     * iOS 实现返回空
     */
    fun getReactHost(): Any?

    /**
     * 获取 ReactContext（异步等待初始化完成）
     */
    suspend fun awaitReactContext(): Any?

    /**
     * 获取当前 ReactContext（如果已初始化）
     */
    fun getCurrentReactContext(): Any?

    override suspend fun preload()
    override suspend fun reload(reason: String)
    override fun getCurrentHostId(): String
    override suspend fun switchHost(hostId: String, bundleAssetName: String, componentName: String)
    override val name: String
    override val isInitialized: Boolean
    override val isActive: Boolean
    override suspend fun initialize()
    override suspend fun activate()
    override suspend fun deactivate()
    override suspend fun destroy()
}
