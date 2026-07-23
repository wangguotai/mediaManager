package com.wgt.rn_module

import com.facebook.react.ReactHost
import com.facebook.react.bridge.ReactContext

/**
 * Android 平台 actual 实现
 * ReactHost/ReactContext 是 final class，不能直接 typealias 到 interface，
 * 用 AndroidReactHost/AndroidReactContext 包装
 */
actual sealed interface PlatformReactHost {
    /** Android 实现，持有真实 ReactHost 引用 */
    class Android(val host: ReactHost) : PlatformReactHost
}

actual sealed interface PlatformReactContext {
    /** Android 实现，持有真实 ReactContext 引用 */
    class Android(val context: ReactContext) : PlatformReactContext
}
