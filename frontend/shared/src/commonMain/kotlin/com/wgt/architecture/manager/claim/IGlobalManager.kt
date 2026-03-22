package com.wgt.architecture.manager.claim

import com.wgt.architecture.manager.IManager

/**
 * GlobalManager的公共接口 - 提供全局Manager的访问能力
 */
internal interface IGlobalManager : IManager {

    /**
     * Feature管理器 - 管理所有Feature
     */
    val managers: MutableMap<String, IManager>

    /**
     * 注册Manager
     */
    fun <T : IManager> registerManager(manager: T)

    /**
     * 获取Manager
     */
    fun <T : IManager> getManager(name: String): T?
}