package com.wgt.architecture.manager

import com.wgt.architecture.di.Lifecycle
import com.wgt.architecture.feature.IFeature
import com.wgt.architecture.manager.claim.IFeatureManager
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel


/**
 * Feature管理器 - 管理所有Feature
 * 提供 feature.xxxFeature 的访问方式
 *
 * 注意：此类仅限在当前文件内访问，禁止在文件外实例化
 */
internal class FeatureManager private constructor() : Manager(), IFeatureManager {

    companion object {
        /**
         * 创建FeatureManager实例
         */
        fun create(): IFeatureManager {
            return FeatureManager()
        }
    }

    override val name: String = "FeatureManager"
    private val TAG = "FeatureManager"

    /**
     * Feature集合 - 管理所有Feature
     */
    override val features = mutableMapOf<String, IFeature>()

    /**
     * Feature管理器协程作用域
     */
    private val featureScope = CoroutineScope(SupervisorJob())

    /**
     * 初始化Feature管理器
     */
    override suspend fun onInitialize() {
        logger.info(TAG, "初始化Feature管理器")
    }

    /**
     * 激活Feature管理器
     */
    override suspend fun onActivate() {
        logger.info(TAG, "激活Feature管理器")
        // 激活所有已注册的Feature
        features.values.forEach { feature ->
            try {
                feature.initialize()
            } catch (e: Exception) {
                logger.error(TAG, "激活Feature失败: ${feature.name}", e)
            }
        }
    }

    /**
     * 停用Feature管理器
     */
    override suspend fun onDeactivate() {
        logger.info(TAG, "停用Feature管理器")
        // 停用所有Feature
        features.values.forEach { feature ->
            try {
                feature.destroy()
            } catch (e: Exception) {
                logger.error(TAG, "停用Feature失败: ${feature.name}", e)
            }
        }
    }

    /**
     * 销毁Feature管理器
     */
    override suspend fun onDestroy() {
        logger.info(TAG, "销毁Feature管理器")

        // 销毁所有Feature
        features.values.forEach { feature ->
            try {
                feature.destroy()
            } catch (e: Exception) {
                logger.error(TAG, "销毁Feature失败: ${feature.name}", e)
            }
        }
        features.clear()

        // 取消Feature管理器协程作用域
        featureScope.cancel("FeatureManager销毁")

        logger.info(TAG, "Feature管理器已销毁")
    }

    /**
     * 注册Feature
     */
    override fun <T : IFeature> registerFeature(feature: T) {
        features[feature.name] = feature
        logger.debug(TAG, "注册Feature: ${feature.name}")
    }

    /**
     * 获取Feature
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : IFeature> getFeature(name: String): T? {
        return features[name] as? T
    }

}

/**
 * 初始化Feature管理器 - 通过依赖注入注册FeatureManager
 */
fun InitFeatureManager() {
    registerManager(Lifecycle.SINGLETON) {
        FeatureManager.create()
    }
}
