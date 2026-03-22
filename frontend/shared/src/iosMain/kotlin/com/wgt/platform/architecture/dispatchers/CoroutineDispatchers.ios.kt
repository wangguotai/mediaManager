package com.wgt.platform.architecture.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * iOS平台协程调度器实现
 */
class IOSCoroutineDispatchers : CoroutineDispatchers {
    override val main: CoroutineDispatcher
        get() = Dispatchers.Main

    override val default: CoroutineDispatcher
        get() = Dispatchers.Default

    override val io: CoroutineDispatcher
        get() = Dispatchers.Default // iOS中通常使用Default作为IO调度器

    override val unconfined: CoroutineDispatcher
        get() = Dispatchers.Unconfined
}

actual val dispatchers: CoroutineDispatchers = IOSCoroutineDispatchers()
