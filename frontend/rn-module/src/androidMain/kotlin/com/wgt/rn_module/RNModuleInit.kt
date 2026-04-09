package com.wgt.rn_module

import android.app.Application
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlags
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsDefaults
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

/**
 * RN 模块初始化类
 * 在 Application 中调用初始化
 */
object RNModuleInit {

    /**
     * 初始化 RN 环境
     * 在 Application.onCreate 中调用
     */
    fun initialize(application: Application) {
        // 初始化 SoLoader（RN 必需）
        initSoLoader(application)

        // 启用 Bridgeless 架构（新架构必需）
        initFeatureFlags()
    }

    /**
     * 初始化 SoLoader
     * RN 依赖 SoLoader 加载 native 库
     * 新架构使用 SO 合并，需要配置 OpenSourceMergedSoMapping
     */
    private fun initSoLoader(application: Application) {
        SoLoader.init(application, OpenSourceMergedSoMapping)
    }

    /**
     * 初始化 ReactNative FeatureFlags
     * 新架构（Bridgeless）必需在 ReactHost 创建前启用
     */
    private fun initFeatureFlags() {
        ReactNativeFeatureFlags.override(object : ReactNativeFeatureFlagsDefaults() {
            override fun enableBridgelessArchitecture(): Boolean = true
            override fun enableFabricRenderer(): Boolean = true
            override fun useTurboModules(): Boolean = true
        })
    }

    /**
     * 预加载 ReactContext
     * 在后台线程初始化 JS 环境，提升首屏速度
     */
    fun preloadReactContext(
        application: Application,
        bundleAssetName: String = "index.android.bundle",
        mainComponentName: String = "MediaManagerApp"
    ) {
        val containerManager = RNContainerManager.getInstance(application)
        val host = containerManager.getOrCreateReactHost(
            hostId = "default",
            bundleAssetName = bundleAssetName,
            mainComponentName = mainComponentName
        )
        host.start()
    }

    /**
     * 获取容器管理器
     */
    fun getContainerManager(application: Application): RNContainerManager {
        return RNContainerManager.getInstance(application)
    }

    /**
     * 获取默认的 ReactHost
     */
    fun getDefaultReactHost(application: Application): ReactHost {
        return RNContainerManager.getInstance(application).getDefaultReactHost()
    }

    /**
     * 获取当前的 ReactContext（如果已初始化）
     */
    fun getCurrentReactContext(application: Application): ReactContext? {
        return RNContainerManager.getInstance(application).getCurrentReactContext("default")
    }

    /**
     * 添加全局 ReactContext 初始化监听器
     */
    fun addReactInstanceListener(
        application: Application,
        listener: ReactInstanceEventListener
    ) {
        RNContainerManager.getInstance(application).addReactInstanceListener(listener)
    }

    /**
     * 移除全局 ReactContext 初始化监听器
     */
    fun removeReactInstanceListener(
        application: Application,
        listener: ReactInstanceEventListener
    ) {
        RNContainerManager.getInstance(application).removeReactInstanceListener(listener)
    }

    /**
     * 销毁所有 ReactHost 实例
     * 在 Application.onTerminate 或需要清理时调用
     */
    fun destroyAll(application: Application) {
        RNContainerManager.getInstance(application).destroyAll()
    }
}
