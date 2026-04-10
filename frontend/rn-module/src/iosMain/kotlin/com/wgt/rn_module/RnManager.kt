package com.wgt.rn_module

import com.wgt.platform.logger.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.concurrent.freeze
import platform.Foundation.NSLog
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * RN Manager 实现 - iOS 平台 (actual)
 *
 * iOS 平台的 RN Manager 实现，使用 React Native for iOS
 * 由于 iOS 平台的 RN 集成方式不同，部分方法为 stub 实现
 */
internal actual class RnManager private actual constructor() : IRnManager {

    actual companion object {
        // 使用 AtomicReference 替代 @Volatile + synchronized (Kotlin/Native)
        @OptIn(ExperimentalAtomicApi::class)
        private val instanceRef = AtomicReference<RnManager?>(null)

        /**
         * 获取 RnManager 实例（iOS 版本）
         * 使用原子操作确保线程安全
         */
        @OptIn(ExperimentalAtomicApi::class)
        actual fun getInstance(): RnManager {
            // 先检查一次
            instanceRef.load()?.let { return it }

            // 创建新实例（Kotlin/Native 使用 freeze 确保不可变性）
            val newInstance = RnManager()

            // CAS 操作确保只有一个实例
            return if (instanceRef.compareAndExchange(null, newInstance) == null) {
                newInstance
            } else {
                instanceRef.load()!!
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

    // ========== 当前 Host 状态 ==========
    private var currentHostId: String = "default"

    // ========== ReactContext 初始化等待器 ==========
    private var contextInitializedDeferred = CompletableDeferred<Any?>()

    // ========== IManager 生命周期实现 ==========

    /**
     * 初始化 RN 环境
     * 在 Manager 被首次使用时调用
     */
    actual override suspend fun initialize() {
        if (_isInitialized) {
            logger.debug(TAG, "已初始化，跳过")
            return
        }

        logger.info(TAG, "初始化 RN 环境 (iOS)")

        withContext(Dispatchers.Default) {
            // iOS 平台 RN 环境初始化
            // 通常在 AppDelegate 中完成，此处仅标记状态
            initRNEnvironment()
        }

        _isInitialized = true
        logger.info(TAG, "RN 环境初始化完成 (iOS)")
    }

    /**
     * 激活 RN 容器
     * 启动 RCTBridge/RCTRootView
     */
    actual override suspend fun activate() {
        if (!_isInitialized) {
            initialize()
        }
        if (_isActive) {
            logger.debug(TAG, "已激活，跳过")
            return
        }

        logger.info(TAG, "激活 RN 容器 (iOS)")

        withContext(Dispatchers.Main) {
            // 重置 deferred，支持多次激活
            if (contextInitializedDeferred.isCompleted) {
                contextInitializedDeferred = CompletableDeferred()
            }

            // iOS 平台激活逻辑
            // 实际实现需要与 RCTBridge 集成
            activateRNBridge()
        }

        _isActive = true
        logger.info(TAG, "RN 容器已激活 (iOS)")
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

        logger.info(TAG, "停用 RN 容器 (iOS)")

        withContext(Dispatchers.Main) {
            // iOS 平台停用逻辑
            deactivateRNBridge()
        }

        _isActive = false
        logger.info(TAG, "RN 容器已停用 (iOS)")
    }

    /**
     * 销毁 RN 容器
     */
    @OptIn(ExperimentalAtomicApi::class)
    actual override suspend fun destroy() {
        if (!_isInitialized) {
            return
        }

        logger.info(TAG, "销毁 RN 容器 (iOS)")

        if (_isActive) {
            deactivate()
        }

        withContext(Dispatchers.Main) {
            // iOS 平台销毁逻辑
            destroyRNBridge()
        }

        _isInitialized = false
        instanceRef.compareAndExchange(this, null)
        logger.info(TAG, "RN 容器已销毁 (iOS)")
    }

    // ========== IRnManager 特有方法实现 ==========

    /**
     * 预加载 ReactContext
     */
    actual override suspend fun preload() {
        if (!_isInitialized) {
            initialize()
        }

        logger.info(TAG, "预加载 ReactContext (iOS)")

        withContext(Dispatchers.Main) {
            // iOS 平台预加载逻辑
            preloadRNContext()
        }

        logger.info(TAG, "ReactContext 预加载请求已发送 (iOS)")
    }

    /**
     * 重新加载 Bundle
     */
    actual override suspend fun reload(reason: String) {
        if (!_isActive) {
            logger.warning(TAG, "未激活，无法重载")
            return
        }

        logger.info(TAG, "重新加载 Bundle: $reason (iOS)")

        withContext(Dispatchers.Main) {
            // iOS 平台重载逻辑
            reloadRNBundle(reason)
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

        logger.info(TAG, "切换 Host: $hostId, bundle=$bundleAssetName, component=$componentName (iOS)")

        withContext(Dispatchers.Main) {
            // 更新当前配置
            currentHostId = hostId

            // iOS 平台切换 Host 逻辑
            switchRNHost(hostId, bundleAssetName, componentName)

            // 重置 deferred
            if (!contextInitializedDeferred.isCompleted) {
                contextInitializedDeferred = CompletableDeferred()
            }
        }
    }

    // ========== 平台特有方法 ==========

    /**
     * 获取 ReactHost
     * iOS 平台返回 null（使用 RCTBridge 而非 ReactHost）
     */
    actual fun getReactHost(): Any? {
        logger.debug(TAG, "iOS 平台不支持 ReactHost，返回 null")
        return null
    }

    /**
     * 获取 ReactContext（异步等待初始化完成）
     */
    actual suspend fun awaitReactContext(): Any? {
        if (!_isActive) {
            activate()
        }
        return contextInitializedDeferred.await()
    }

    /**
     * 获取当前 ReactContext（如果已初始化）
     */
    actual fun getCurrentReactContext(): Any? {
        // iOS 平台获取当前 RCTBridge 或 RCTRootView 的 context
        return getCurrentRNContext()
    }

    // ========== 私有方法（iOS 平台特定实现）==========

    private fun initRNEnvironment() {
        // iOS RN 环境初始化
        // 通常在 AppDelegate 中完成，此处可添加额外的初始化逻辑
        NSLog("[RnManager] 初始化 RN 环境 (iOS)")
    }

    private fun activateRNBridge() {
        // iOS RCTBridge 激活逻辑
        // 需要与原生代码桥接
        NSLog("[RnManager] 激活 RN Bridge (iOS)")
    }

    private fun deactivateRNBridge() {
        // iOS RCTBridge 停用逻辑
        NSLog("[RnManager] 停用 RN Bridge (iOS)")
    }

    private fun destroyRNBridge() {
        // iOS RCTBridge 销毁逻辑
        NSLog("[RnManager] 销毁 RN Bridge (iOS)")
    }

    private fun preloadRNContext() {
        // iOS 预加载逻辑
        NSLog("[RnManager] 预加载 RN Context (iOS)")
    }

    private fun reloadRNBundle(reason: String) {
        // iOS 重载 Bundle 逻辑
        // 可通过 RCTBridge.reload() 实现
        NSLog("[RnManager] 重载 RN Bundle: $reason (iOS)")
    }

    private fun switchRNHost(hostId: String, bundleAssetName: String, componentName: String) {
        // iOS 切换 Host 逻辑
        NSLog("[RnManager] 切换 RN Host: $hostId (iOS)")
    }

    private fun getCurrentRNContext(): Any? {
        // iOS 获取当前 RN Context
        // 返回 RCTBridge 或相关对象
        NSLog("[RnManager] 获取当前 RN Context (iOS)")
        return null
    }
}
