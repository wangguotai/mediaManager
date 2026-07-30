package com.wgt.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RN 活动中心页面（V7 §3.1）。
 *
 * 嵌入 React Native 渲染的「活动中心」页面。
 * RN bundle 从 assets/index.android.bundle 加载，组件名 "MediaManagerApp"。
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RnActivityScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活动中心 (RN)") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        // RnContainer 嵌入 RN 视图，组件名 "MediaManagerApp"，bundle "index.android.bundle"
        RnContainer(
            componentName = "MediaManagerApp",
            bundleAssetName = "index.android.bundle",
            hostId = "activity-center",
            modifier = Modifier.fillMaxSize()
        )
    }
}
