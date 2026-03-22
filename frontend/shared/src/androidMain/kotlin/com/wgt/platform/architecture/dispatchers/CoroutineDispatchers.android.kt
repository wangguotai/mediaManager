package com.wgt.platform.architecture.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Android平台协程调度器实现
 */
class AndroidCoroutineDispatchers : CoroutineDispatchers {
    override val main: CoroutineDispatcher
        get() = Dispatchers.Main

    override val default: CoroutineDispatcher
        get() = Dispatchers.Default

    override val io: CoroutineDispatcher
        get() = Dispatchers.IO

    override val unconfined: CoroutineDispatcher
        get() = Dispatchers.Unconfined
}

actual val dispatchers: CoroutineDispatchers = AndroidCoroutineDispatchers()
