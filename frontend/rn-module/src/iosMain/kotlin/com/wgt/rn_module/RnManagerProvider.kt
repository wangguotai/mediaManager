package com.wgt.rn_module

import com.wgt.platform.logger.logger

/**
 * iOS 平台的 RN Manager 提供者
 * 
 * TODO: RN iOS 端待实现，当前为占位实现
 * 后续需要创建 RnManager 的 iOS 实现
 */
actual object RnManagerProvider {

    actual fun getManager(): IRnManager {
        // iOS 端 RN 还未集成，返回空实现
        logger.warning("RnManager", "iOS 端 RN Manager 尚未实现，返回空实现")
        return RnManagerIosPlaceholder()
    }
}

/**
 * iOS 端 RnManager 空实现
 * 用于占位，后续需要替换为真实实现
 */
internal class RnManagerIosPlaceholder : IRnManager {
    
    override val name: String = "RnManagerIosPlaceholder"
    override val isInitialized: Boolean = false
    override val isActive: Boolean = false
    
    override suspend fun initialize() {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，initialize 忽略")
    }
    
    override suspend fun activate() {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，activate 忽略")
    }
    
    override suspend fun deactivate() {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，deactivate 忽略")
    }
    
    override suspend fun destroy() {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，destroy 忽略")
    }
    
    override suspend fun preload() {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，preload 忽略")
    }
    
    override suspend fun reload(reason: String) {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，reload 忽略")
    }
    
    override fun getCurrentHostId(): String = "ios_placeholder"
    
    override suspend fun switchHost(
        hostId: String,
        bundleAssetName: String,
        componentName: String
    ) {
        logger.warning("RnManagerIosPlaceholder", "iOS 端 RN 尚未实现，switchHost 忽略")
    }
}
