package com.wgt.platform

/**
 * 全局能力提供者接口
 * 提供跨平台的全局上下文和Activity访问能力
 */
interface IGlobal {
    /**
     * 检查是否已初始化
     */
    val isInitialized: Boolean
    
    /**
     * 初始化全局上下文
     * 在Android端传入Application，在iOS端传入UIApplication
     */
    fun initialize(context: Any)
}
