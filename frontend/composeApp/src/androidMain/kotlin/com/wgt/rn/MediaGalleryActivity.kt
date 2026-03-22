package com.wgt.rn

import android.os.Bundle
import com.wgt.rn_android.RNPluginActivity

/**
 * 媒体库 React Native 页面
 * 
 * 对应 rn-test-app 中注册的 MediaGallery 模块
 */
class MediaGalleryActivity : RNPluginActivity() {

    override fun getMainComponentName(): String = "MediaGallery"

    override fun getInitialProps(): Map<String, Any>? {
        return mapOf(
            "title" to "媒体库",
            "enableUpload" to true,
            "enableDelete" to true,
            "maxSelectCount" to 9
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
