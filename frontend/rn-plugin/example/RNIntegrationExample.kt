package com.wgt.rn.example

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.wgt.rn_android.RNPluginManager

/**
 * React Native 集成示例
 * 
 * 展示如何在 Compose 中嵌入 React Native 视图
 */

/**
 * 初始化 RN Plugin
 * 在 Application.onCreate() 中调用
 */
fun initRNPlugin(application: Application) {
    RNPluginManager.getInstance().initialize(application)
}

/**
 * Compose 组件：React Native 视图容器
 * 
 * @param moduleName React Native 模块名称
 * @param initialProps 传递给 RN 的初始属性
 */
@Composable
fun ReactNativeContainer(
    moduleName: String,
    initialProps: Map<String, Any>? = null
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { container ->
            RNPluginManager.getInstance().loadReactModule(
                container = container,
                moduleName = moduleName,
                initialProps = initialProps
            )
        }
    )
}

/**
 * 使用示例：在 Compose 中嵌入 RN 页面
 * 
 * ```kotlin
 * @Composable
 * fun MediaGalleryScreen() {
 *     ReactNativeContainer(
 *         moduleName = "MediaGallery",
 *         initialProps = mapOf(
 *             "title" to "媒体库",
 *             "enableUpload" to true
 *         )
 *     )
 * }
 * ```
 */

/**
 * 发送事件到 React Native 示例
 */
fun sendEventToRN(eventName: String, data: Map<String, Any>) {
    RNPluginManager.getInstance().sendEvent(eventName, data)
}

/**
 * 清理 RN 资源（在退出页面时调用）
 */
fun cleanupRN() {
    // 注意：不要销毁全局实例，除非应用退出
    // 只需要清理当前页面的 viewManager
}
