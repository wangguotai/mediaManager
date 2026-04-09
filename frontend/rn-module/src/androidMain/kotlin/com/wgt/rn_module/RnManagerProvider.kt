package com.wgt.rn_module

import android.app.Application

/**
 * Android 平台的 RN Manager 提供者
 */
actual object RnManagerProvider {

    private lateinit var application: Application

    /**
     * 初始化 Provider
     * 在 Application.onCreate 中调用
     */
    fun initialize(app: Application) {
        application = app
    }

    actual fun getManager(): IRnManager {
        if (!::application.isInitialized) {
            throw IllegalStateException(
                "RnManagerProvider 未初始化，请在 Application.onCreate 中调用 initialize(app)"
            )
        }
        return RnManager.getInstance(application)
    }
}
