package com.wgt.rn

import android.os.Bundle
import com.wgt.rn_android.RNPluginActivity

/**
 * React Native 测试 Activity
 * 
 * 用于测试 rn-host / rn-android 集成功能
 */
class RNTestActivity : RNPluginActivity() {

    override fun getMainComponentName(): String = "RNTTestApp"

    override fun getInitialProps(): Map<String, Any>? {
        return mapOf(
            "from" to "Native Android",
            "screen" to "RNTestActivity",
            "timestamp" to System.currentTimeMillis()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 可以在这里添加额外的初始化逻辑
    }
}
