package com.wgt.rn_android

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.wgt.rn.host.ReactHostManager

/**
 * React Native 插件 Activity 基类
 * 
 * 用于全屏展示 React Native 页面
 */
abstract class RNPluginActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private var viewManager: com.wgt.rn.host.ReactViewManager? = null

    /**
     * 子类需要实现的抽象方法，返回 React Native 模块名称
     */
    abstract fun getMainComponentName(): String

    /**
     * 返回传递给 React Native 的初始属性
     */
    open fun getInitialProps(): Map<String, Any>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知 ReactHostManager Activity 已创建
        // 这会触发 ReactInstanceManager.createReactContextInBackground()
        ReactHostManager.getInstance().onActivityCreate(this)

        // 创建容器
        container = FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)

        // 加载 React Native 视图
        loadReactContent()
    }

    private fun loadReactContent() {
        viewManager = com.wgt.rn.host.ReactViewManager(this).apply {
            attachToContainer(container, getMainComponentName(), getInitialProps())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewManager?.destroy()
        viewManager = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 将返回键事件传递给 React Native
        val reactInstanceManager = ReactHostManager.getInstance().getReactInstanceManager()
        if (reactInstanceManager != null) {
            // React Native 会处理返回键事件
            // 如果 JS 侧调用了 BackHandler，会阻止默认行为
            reactInstanceManager.onBackPressed()
        }
        // 注意：不要调用 super.onBackPressed()
        // ReactInstanceManager 会处理 Activity 的结束
    }
}
