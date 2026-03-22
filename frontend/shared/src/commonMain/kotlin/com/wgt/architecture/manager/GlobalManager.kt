package com.wgt.architecture.manager

import com.wgt.architecture.manager.claim.IGlobalManager
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel



/**
 * 全局Manager - 统一管理所有Manager和Feature
 * 提供 manager.feature.xxxFeature 和 manager.xxx 的访问方式
 *
 * 注意：此类仅限在当前文件内访问，禁止在文件外实例化
 */
internal class GlobalManager private constructor() : Manager(), IGlobalManager {

    companion object {
        /**
         * 创建GlobalManager实例
         */
        fun create(): GlobalManager {
            return GlobalManager()
        }
    }

    override val name: String = "GlobalManager"
    private val TAG = "GlobalManager"

    /**
     * Feature管理器 - 管理所有Feature
     */
    override val managers = mutableMapOf<String, IManager>()

    /**
     * 全局协程作用域
     */
    private val globalScope = CoroutineScope(SupervisorJob())


    /**
     * 初始化全局Manager
     */
    override suspend fun onInitialize() {
        logger.info(TAG, "初始化全局Manager")
    }

    /**
     * 销毁全局Manager
     */
    override suspend fun onDestroy() {
        logger.info(TAG, "销毁全局Manager")

        // 销毁所有Manager
        managers.values.forEach { manager ->
            try {
                manager.destroy()
            } catch (e: Exception) {
                logger.error(TAG, "销毁Manager失败: ${manager.name}", e)
            }
        }
        managers.clear()


        // 取消全局协程作用域
        globalScope.cancel("GlobalManager销毁")

        logger.info(TAG, "全局Manager已销毁")
    }

    /**
     * 注册Manager
     */
    override fun <T : IManager> registerManager(manager: T) {
        managers[manager.name] = manager
        logger.debug(TAG, "注册Manager: ${manager.name}")
    }

    /**
     * 获取Manager
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : IManager> getManager(name: String): T? {
        return managers[name] as? T
    }

}


/**
 * 全局Manager实例
 */
val manager: IManager = GlobalManager.create()

