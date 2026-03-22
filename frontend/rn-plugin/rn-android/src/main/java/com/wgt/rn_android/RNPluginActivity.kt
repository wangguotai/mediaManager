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

    override fun onBackPressed() {
        // 将返回键事件传递给 React Native
        val reactContext = ReactHostManager.getInstance().getCurrentReactContext()
        if (reactContext != null) {
            // 检查 React Native 是否处理了返回键
            val handled = reactContext.onBackPressed()
            if (!handled) {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }
}
