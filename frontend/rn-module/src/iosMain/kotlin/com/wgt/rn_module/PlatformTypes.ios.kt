package com.wgt.rn_module

/**
 * iOS 平台 actual 实现 - 占位符
 */
actual sealed interface PlatformReactHost {
    /** iOS 空实现 */
    object None : PlatformReactHost
}

actual sealed interface PlatformReactContext {
    /** iOS 空实现 */
    object None : PlatformReactContext
}
