package com.wgt.architecture.manager.claim

import com.wgt.architecture.di.DependencyContainer
import com.wgt.architecture.di.inject
import com.wgt.architecture.feature.IFeature
import com.wgt.architecture.manager.FeatureManager
import com.wgt.architecture.manager.IManager
import com.wgt.architecture.manager.Manager

/**
 * FeatureManager的公共接口 - 提供Feature管理的访问能力
 */
interface IFeatureManager : IManager {

    /**
     * Feature集合 - 管理所有Feature
     */
    val features: MutableMap<String, IFeature>

    /**
     * 注册Feature
     */
    fun <T : IFeature> registerFeature(feature: T)

    /**
     * 获取Feature
     */
    fun <T : IFeature> getFeature(name: String): T?


}


/**
 * Feature管理器实例 - 通过依赖注入解析
 */
val IManager.feature: IFeatureManager by inject()
