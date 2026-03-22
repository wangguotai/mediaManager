package com.wgt.platform.architecture.dispatchers

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 协程调度器提供者接口
 * 提供跨平台的协程调度器访问
 */
interface CoroutineDispatchers {
    /**
     * 主线程/UI线程调度器
     */
    val main: CoroutineDispatcher
    
    /**
     * 默认调度器（用于CPU密集型任务）
     */
    val default: CoroutineDispatcher
    
    /**
     * IO调度器（用于IO密集型任务）
     */
    val io: CoroutineDispatcher
    
    /**
     * 无限制调度器（用于不限制线程数的任务）
     */
    val unconfined: CoroutineDispatcher
}

/**
 * 期望的平台特定协程调度器提供者
 */
expect val dispatchers: CoroutineDispatchers
