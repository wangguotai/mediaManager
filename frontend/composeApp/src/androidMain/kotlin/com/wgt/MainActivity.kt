package com.wgt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不调用 enableEdgeToEdge()：MIUI 状态栏会拦截 TopAppBar 区域触摸事件，
        // 非沉浸式让系统正常处理状态栏 insets，保证操作按钮可点击。
        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}