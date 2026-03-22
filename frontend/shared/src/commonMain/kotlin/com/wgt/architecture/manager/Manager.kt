package com.wgt.architecture.manager

import com.wgt.architecture.di.DependencyContainer
import com.wgt.architecture.di.Lifecycle
import com.wgt.architecture.feature.IFeature
import com.wgt.architecture.feature.registerFeature
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Manager接口 - 定义Manager的公共API
 * 所有Manager实现类应当只有internal的访问权限
 */
interface IManager {

    /**
     * Manager名称，用于日志和调试
     */
    val name: String

    /**
     * Manager初始化状态
     */
    val isInitialized: Boolean

    /**
     * Manager激活状态
     */
    val isActive: Boolean

    /**
     * 初始化Manager
     * 在Manager被首次使用时调用
     */
    suspend fun initialize()

    /**
     * 激活Manager
     * 开始执行Manager的核心功能
     */
    suspend fun activate()

    /**
     * 停用Manager
     * 暂停Manager的核心功能，但保留状态
     */
    suspend fun deactivate()

    /**
     * 销毁Manager
     * 清理所有资源，Manager无法再次使用
     */
    suspend fun destroy()
}

/**
 * AbstractManager基类 - 具有生命周期管理的功能总组件
 * 保留原有的Manager功能，但只有internal的访问权限
 */
internal abstract class Manager : IManager {

    /**
     * Manager名称，用于日志和调试
     */
    abstract override val name: String

    /**
     * Manager的协程作用域，用于管理所有异步操作
     */
    protected val scope = CoroutineScope(SupervisorJob())

    /**
     * Manager初始化状态
     */
    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized

    /**
     * Manager激活状态
     */
    private var _isActive = false
    override val isActive: Boolean get() = _isActive

    /**
     * 初始化Manager
     * 在Manager被首次使用时调用
     */
    override suspend fun initialize() {
        if (_isInitialized) return

        logger.info(name, "初始化Manager")
        onInitialize()
        _isInitialized = true
        logger.info(name, "Manager初始化完成")
    }

    /**
     * 激活Manager
     * 开始执行Manager的核心功能
     */
    override suspend fun activate() {
        if (!_isInitialized) {
            initialize()
        }

        if (_isActive) return

        logger.info(name, "激活Manager")
        onActivate()
        _isActive = true
        logger.info(name, "Manager已激活")
    }

    /**
     * 停用Manager
     * 暂停Manager的核心功能，但保留状态
     */
    override suspend fun deactivate() {
        if (!_isActive) return

        logger.info(name, "停用Manager")
        onDeactivate()
        _isActive = false
        logger.info(name, "Manager已停用")
    }

    /**
     * 销毁Manager
     * 清理所有资源，Manager无法再次使用
     */
    override suspend fun destroy() {
        logger.info(name, "销毁Manager")

        if (_isActive) {
            deactivate()
        }

        onDestroy()
        scope.cancel("Manager销毁")
        _isInitialized = false
        logger.info(name, "Manager已销毁")
    }

    /**
     * 子类重写：初始化逻辑
     */
    protected open suspend fun onInitialize() {
        // 默认实现为空
    }

    /**
     * 子类重写：激活逻辑
     */
    protected open suspend fun onActivate() {
        // 默认实现为空
    }

    /**
     * 子类重写：停用逻辑
     */
    protected open suspend fun onDeactivate() {
        // 默认实现为空
    }

    /**
     * 子类重写：销毁逻辑
     */
    protected open suspend fun onDestroy() {
        // 默认实现为空
    }

    /**
     * 安全执行操作，自动处理Manager状态
     */
    protected suspend fun <T> safeExecute(operation: suspend () -> T): Result<T> {
        return if (isActive) {
            try {
                Result.success(operation())
            } catch (e: Exception) {
                logger.error(name, "操作执行失败: ${e.message}")
                Result.failure(e)
            }
        } else {
            logger.warning(name, "操作被拒绝: Manager未激活")
            Result.failure(IllegalStateException("Manager未激活"))
        }
    }
}

/**
 * Manager和Feature的依赖注入支持
 */

/**
 * Manager注册扩展函数
 */
inline fun <reified T : IManager> registerManager(
    lifecycle: Lifecycle = Lifecycle.SINGLETON,
    noinline factory: () -> T
) {
    DependencyContainer.register(lifecycle, factory)
}


fun InitManager() {
    InitFeatureManager()
}
