package com.wgt.architecture.dispatchers

import com.wgt.platform.architecture.dispatchers.dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 协程作用域管理器
 * 提供灵活的协程作用域管理和线程切换功能
 */
object CoroutineScopeManager {
    
    /**
     * 创建新的协程作用域
     */
    fun createScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + dispatchers.default)
    }
    
    /**
     * 在主线程执行代码块
     */
    suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T {
        return withContext(dispatchers.main, block)
    }
    
    /**
     * 在默认线程执行代码块
     */
    suspend fun <T> onDefault(block: suspend CoroutineScope.() -> T): T {
        return withContext(dispatchers.default, block)
    }
    
    /**
     * 在IO线程执行代码块
     */
    suspend fun <T> onIO(block: suspend CoroutineScope.() -> T): T {
        return withContext(dispatchers.io, block)
    }
    
    /**
     * 在无限制线程执行代码块
     */
    suspend fun <T> onUnconfined(block: suspend CoroutineScope.() -> T): T {
        return withContext(dispatchers.unconfined, block)
    }
    
    /**
     * 在主线程启动协程
     */
    fun CoroutineScope.launchOnMain(block: suspend CoroutineScope.() -> Unit) {
        launch(dispatchers.main, block = block)
    }
    
    /**
     * 在默认线程启动协程
     */
    fun CoroutineScope.launchOnDefault(block: suspend CoroutineScope.() -> Unit) {
        launch(dispatchers.default, block = block)
    }
    
    /**
     * 在IO线程启动协程
     */
    fun CoroutineScope.launchOnIO(block: suspend CoroutineScope.() -> Unit) {
        launch(dispatchers.io, block = block)
    }
    
    /**
     * 在无限制线程启动协程
     */
    fun CoroutineScope.launchOnUnconfined(block: suspend CoroutineScope.() -> Unit) {
        launch(dispatchers.unconfined, block = block)
    }
    
    /**
     * 将Flow切换到主线程
     */
    fun <T> Flow<T>.flowOnMain(): Flow<T> {
        return flowOn(dispatchers.main)
    }
    
    /**
     * 将Flow切换到默认线程
     */
    fun <T> Flow<T>.flowOnDefault(): Flow<T> {
        return flowOn(dispatchers.default)
    }
    
    /**
     * 将Flow切换到IO线程
     */
    fun <T> Flow<T>.flowOnIO(): Flow<T> {
        return flowOn(dispatchers.io)
    }
    
    /**
     * 安全的协程执行器，自动处理异常
     */
    suspend fun <T> safeExecute(
        onError: (Throwable) -> Unit = {},
        block: suspend () -> T
    ): T? {
        return try {
            block()
        } catch (e: Throwable) {
            onError(e)
            null
        }
    }
    
    /**
     * 带重试机制的协程执行器
     */
    suspend fun <T> retryExecute(
        retries: Int = 3,
        delayMillis: Long = 1000,
        onError: (Throwable, Int) -> Boolean = { _, _ -> true }, // 返回true表示继续重试
        block: suspend () -> T
    ): T {
        var lastException: Throwable? = null
        
        repeat(retries) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                if (!onError(e, attempt + 1)) {
                    throw e
                }
                if (attempt < retries - 1) {
                    delay(delayMillis)
                }
            }
        }
        
        throw lastException ?: IllegalStateException("重试失败")
    }
}

/**
 * 简化的协程作用域管理扩展函数
 */

/**
 * 在主线程执行
 */
suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = CoroutineScopeManager.onMain(block)

/**
 * 在默认线程执行
 */
suspend fun <T> onDefault(block: suspend CoroutineScope.() -> T): T = CoroutineScopeManager.onDefault(block)

/**
 * 在IO线程执行
 */
suspend fun <T> onIO(block: suspend CoroutineScope.() -> T): T = CoroutineScopeManager.onIO(block)

/**
 * 在无限制线程执行
 */
suspend fun <T> onUnconfined(block: suspend CoroutineScope.() -> T): T = CoroutineScopeManager.onUnconfined(block)

/**
 * 安全的协程执行
 */
suspend fun <T> safeExecute(
    onError: (Throwable) -> Unit = {},
    block: suspend () -> T
): T? = CoroutineScopeManager.safeExecute(onError, block)

/**
 * 带重试的协程执行
 */
suspend fun <T> retryExecute(
    retries: Int = 3,
    delayMillis: Long = 1000,
    onError: (Throwable, Int) -> Boolean = { _, _ -> true },
    block: suspend () -> T
): T = CoroutineScopeManager.retryExecute(retries, delayMillis, onError, block)
