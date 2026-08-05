package com.wgt.rn_module

import android.app.Application
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlags
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsDefaults
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader
import com.wgt.platform.architecture.dispatchers.dispatchers
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * RN Manager 实现 - Android 平台 (actual)
 *
 * 遵循项目 Manager 体系：
 * 1. 实现 IRnManager 接口，支持完整的生命周期管理
 * 2. 使用 suspend 函数支持协程
 * 3. 支持多 Host 管理（多 Bundle 场景）
 */
internal actual class RnManager private actual constructor() : IRnManager {

    // Android 平台特定：需要 Application 实例
    private lateinit var application: Application

    actual companion object {
        @Volatile
        private var instance: RnManager? = null

        /**
         * 获取 RnManager 实例（Android 版本）
         * 需要在首次调用前通过 setApplication 设置 Application
         */
        actual fun getInstance(): RnManager {
            return instance ?: synchronized(this) {
                instance ?: RnManager().also {
                    instance = it
                }
            }
        }
    }

    private val TAG = "RnManager"

    // ========== IManager 状态 ==========
    private var _isInitialized = false
    actual override val isInitialized: Boolean get() = _isInitialized

    private var _isActive = false
    actual override val isActive: Boolean get() = _isActive

    actual override val name: String = "RnManager"

    // ========== RN 容器管理器 ==========
    private val containerManager by lazy { RNContainerManager.getInstance(application) }

    // ========== 当前 Host 状态 ==========
    private var currentHostId: String = "default"
    private var currentBundleName: String = "index.android.bundle"
    private var currentComponentName: String = "rndemo"

    // ========== ReactContext 初始化等待器 ==========
    private var contextInitializedDeferred = CompletableDeferred<ReactContext?>()

    // ========== 生命周期监听 ==========
    private val reactInstanceListener = object : ReactInstanceEventListener {
        override fun onReactContextInitialized(context: ReactContext) {
            logger.info(TAG, "ReactContext 初始化完成")
            if (!contextInitializedDeferred.isCompleted) {
                contextInitializedDeferred.complete(context)
            }
        }
    }

    // ========== Android 平台特定方法 ==========

    /**
     * 设置 Application 实例（必须在首次使用前调用）
     */
    fun setApplication(app: Application) {
        if (!::application.isInitialized) {
            application = app
        }
    }

    // ========== IManager 生命周期实现 ==========

    /**
     * 初始化 RN 环境
     * 在 Manager 被首次使用时调用
     */
    actual override suspend fun initialize() {
        if (!_isInitialized) {
            logger.debug(TAG, "已初始化，跳过")
            return
        }

        if (!::application.isInitialized) {
            throw IllegalStateException("Application 未设置，请先调用 setApplication()")
        }

        logger.info(TAG, "初始化 RN 环境")

        withContext(dispatchers.io) {
            // 1. 初始化 SoLoader
            initSoLoader()

            // 2. 启用新架构 FeatureFlags
            initFeatureFlags()
        }

        _isInitialized = true
        logger.info(TAG, "RN 环境初始化完成")
    }

    /**
     * 激活 RN 容器
     * 启动 ReactHost
     */
    actual override suspend fun activate() {
        if (!_isInitialized) {
            initialize()
        }
        if (_isActive) {
            logger.debug(TAG, "已激活，跳过")
            return
        }

        logger.info(TAG, "激活 RN 容器")

        withContext(dispatchers.main) {
            // 重置 deferred，支持多次激活
            if (contextInitializedDeferred.isCompleted) {
                contextInitializedDeferred = CompletableDeferred()
            }

            // 获取或创建 ReactHost
            val host = getOrCreateReactHost()

            // 添加生命周期监听
            host.addReactInstanceEventListener(reactInstanceListener)

            // 如果已经初始化，直接完成
            host.currentReactContext?.let {
                contextInitializedDeferred.complete(it)
            }

            // 启动 ReactHost
            host.start()
        }

        _isActive = true
        logger.info(TAG, "RN 容器已激活")
    }

    /**
     * 停用 RN 容器
     * 暂停但不销毁
     */
    actual override suspend fun deactivate() {
        if (!_isActive) {
            logger.debug(TAG, "未激活，跳过")
            return
        }

        logger.info(TAG, "停用 RN 容器")

        withContext(dispatchers.main) {
            // 获取当前 Host
            val host = containerManager.getDefaultReactHost()

            // 移除监听器
            host.removeReactInstanceEventListener(reactInstanceListener)

            // 停用以清理资源，但保留配置
            host.destroy("deactivate", null)
        }

        _isActive = false
        logger.info(TAG, "RN 容器已停用")
    }

    /**
     * 销毁 RN 容器
     */
    actual override suspend fun destroy() {
        if (!_isInitialized) {
            return
        }

        logger.info(TAG, "销毁 RN 容器")

        if (_isActive) {
            deactivate()
        }

        withContext(dispatchers.main) {
            // 清理所有 Host
            containerManager.destroyAll()
        }

        _isInitialized = false
        instance = null
        logger.info(TAG, "RN 容器已销毁")
    }

    // ========== IRnManager 特有方法实现 ==========

    /**
     * 预加载 ReactContext
     */
    actual override suspend fun preload() {
        if (!_isInitialized) {
            initialize()
        }

        logger.info(TAG, "预加载 ReactContext")

        withContext(dispatchers.main) {
            containerManager.preloadReactContext(currentHostId)
        }

        logger.info(TAG, "ReactContext 预加载请求已发送")
    }

    /**
     * 重新加载 Bundle
     */
    actual override suspend fun reload(reason: String) {
        if (!_isActive) {
            logger.warning(TAG, "未激活，无法重载")
            return
        }

        logger.info(TAG, "重新加载 Bundle: $reason")

        withContext(dispatchers.main) {
            containerManager.reloadReactContext(currentHostId, reason)
        }
    }

    /**
     * 获取当前 Host ID
     */
    actual override fun getCurrentHostId(): String = currentHostId

    /**
     * 切换 Host（多 Bundle 场景）
     */
    actual override suspend fun switchHost(
        hostId: String,
        bundleAssetName: String,
        componentName: String
    ) {
        if (!_isActive) {
            logger.warning(TAG, "未激活，无法切换 Host")
            return
        }

        logger.info(TAG, "切换 Host: $hostId, bundle=$bundleAssetName, component=$componentName")

        withContext(dispatchers.main) {
            // 销毁旧 Host
            containerManager.destroyReactHost(currentHostId)

            // 更新当前配置
            currentHostId = hostId
            currentBundleName = bundleAssetName
            currentComponentName = componentName

            // 创建新 Host 并启动
            val newHost = containerManager.getOrCreateReactHost(
                hostId = hostId,
                bundleAssetName = bundleAssetName,
                mainComponentName = componentName
            )
            newHost.addReactInstanceEventListener(reactInstanceListener)

            // 重置 deferred
            if (!contextInitializedDeferred.isCompleted) {
                contextInitializedDeferred = CompletableDeferred()
            }

            newHost.start()
        }
    }

    // ========== 平台特有方法 ==========

    /**
     * 获取 ReactHost
     */
    actual fun getReactHost(): PlatformReactHost? {
        return PlatformReactHost.Android(getOrCreateReactHost())
    }

    /**
     * 获取 ReactContext（异步等待初始化完成）
     */
    actual suspend fun awaitReactContext(): PlatformReactContext? {
        if (!_isActive) {
            activate()
        }
        val ctx = contextInitializedDeferred.await()
        return ctx?.let { PlatformReactContext.Android(it) }
    }

    /**
     * 获取当前 ReactContext（如果已初始化）
     */
    actual fun getCurrentReactContext(): PlatformReactContext? {
        val ctx = containerManager.getCurrentReactContext(currentHostId)
        return ctx?.let { PlatformReactContext.Android(it) }
    }

    /**
     * 添加 ReactContext 初始化监听器
     */
    fun addReactInstanceListener(listener: ReactInstanceEventListener) {
        containerManager.addReactInstanceListener(listener)
    }

    /**
     * 移除 ReactContext 初始化监听器
     */
    fun removeReactInstanceListener(listener: ReactInstanceEventListener) {
        containerManager.removeReactInstanceListener(listener)
    }

    // ========== 私有方法 ==========

    private fun initSoLoader() {
        logger.debug(TAG, "初始化 SoLoader")
        SoLoader.init(application, OpenSourceMergedSoMapping)
    }

    private fun initFeatureFlags() {
        logger.debug(TAG, "启用新架构 FeatureFlags")
        ReactNativeFeatureFlags.override(object : ReactNativeFeatureFlagsDefaults() {
            override fun enableBridgelessArchitecture(): Boolean = true
            override fun enableFabricRenderer(): Boolean = true
            override fun useTurboModules(): Boolean = true
        })
    }

    private fun getOrCreateReactHost(): ReactHost {
        return containerManager.getOrCreateReactHost(
            hostId = currentHostId,
            bundleAssetName = currentBundleName,
            mainComponentName = currentComponentName
        )
    }
}
