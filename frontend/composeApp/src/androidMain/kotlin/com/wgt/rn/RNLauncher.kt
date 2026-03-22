package com.wgt.rn

import android.content.Context
import android.content.Intent

/**
 * React Native 页面启动器
 * 
 * 用于从 Compose 启动 RN 测试页面
 */
object RNLauncher {
    
    /**
     * 启动 RN 测试页面
     * 
     * @param context Context
     */
    fun launchTestPage(context: Context) {
        val intent = Intent(context, RNTestActivity::class.java).apply {
            // 可以添加额外的 flags
            // flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * 启动 RN 媒体库页面
     * 
     * @param context Context
     */
    fun launchMediaGallery(context: Context) {
        val intent = Intent(context, MediaGalleryActivity::class.java)
        context.startActivity(intent)
    }
}
