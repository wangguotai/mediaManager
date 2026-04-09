package com.wgt.platform

/**
 * 应用全局上下文提供者
 * 使用expect/actual模式封装平台特定的上下文实现
 */
object AppContext : IGlobal {
    /**
     * 初始化全局上下文
     * 在Android端传入Application，在iOS端传入UIApplication
     */
    override fun initialize(context: Any) {
        initializePlatformContext(context)
    }
    
    /**
     * 检查是否已初始化
     */
    override val isInitialized: Boolean
        get() = isPlatformContextInitialized()
}

/**
 * 平台特定的上下文初始化
 */
internal expect fun initializePlatformContext(context: Any)

/**
 * 检查平台特定的上下文是否已初始化
 */
internal expect fun isPlatformContextInitialized(): Boolean
