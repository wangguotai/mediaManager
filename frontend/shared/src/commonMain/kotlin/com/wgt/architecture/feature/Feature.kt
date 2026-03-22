package com.wgt.architecture.feature

import com.wgt.architecture.di.DependencyContainer
import com.wgt.architecture.di.Lifecycle
import com.wgt.architecture.manager.IManager
import com.wgt.platform.logger.logger

/**
 * Feature接口 - 定义Feature的公共API
 * 所有Feature实现类应当只有internal的访问权限
 */
interface IFeature {
    
    /**
     * Feature名称，用于日志和调试
     */
    val name: String
    
    /**
     * Feature初始化状态
     */
    val isInitialized: Boolean
    
    /**
     * 初始化Feature
     */
    suspend fun initialize()
    
    /**
     * 销毁Feature
     */
    suspend fun destroy()
}

/**
 * Feature基类 - 针对特定业务的功能实现
 * Feature是具体的功能单元，由Manager管理
 */
 abstract class Feature : IFeature {
    /**
     * Feature初始化状态
     */
    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized

    /**
     * 初始化Feature
     */
    override suspend fun initialize() {
        if (_isInitialized) return

        logger.info(name, "初始化Feature")
        onInitialize()
        _isInitialized = true
        logger.info(name, "Feature初始化完成")
    }

    /**
     * 销毁Feature
     */
    override suspend fun destroy() {
        if (!_isInitialized) return

        logger.info(name, "销毁Feature")
        onDestroy()
        _isInitialized = false
        logger.info(name, "Feature已销毁")
    }

    /**
     * 子类重写：初始化逻辑
     */
    protected open suspend fun onInitialize() {
        // 默认实现为空
    }

    /**
     * 子类重写：销毁逻辑
     */
    protected open suspend fun onDestroy() {
        // 默认实现为空
    }
}


/**
 * Feature注册扩展函数
 */
inline fun <reified T : IFeature> registerFeature(
    lifecycle: Lifecycle = Lifecycle.SINGLETON,
    noinline factory: () -> T
) {
    DependencyContainer.register(lifecycle, factory)
}

