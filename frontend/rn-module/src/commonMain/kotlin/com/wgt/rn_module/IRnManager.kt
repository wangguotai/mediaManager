package com.wgt.rn_module

import com.wgt.architecture.manager.IManager

/**
 * RN Manager 接口 - 继承 IManager，添加 RN 特有方法
 */
interface IRnManager : IManager {

    /**
     * 预加载 ReactContext
     */
    suspend fun preload()

    /**
     * 重新加载 Bundle
     */
    suspend fun reload(reason: String = "reload")

    /**
     * 获取当前的 Host ID
     */
    fun getCurrentHostId(): String

    /**
     * 切换 Host（用于多 Bundle 场景）
     */
    suspend fun switchHost(
        hostId: String,
        bundleAssetName: String,
        componentName: String
    )
}
