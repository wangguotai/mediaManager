package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Android 平台 RN 视图嵌入（V7 §3.1）。
 *
 * TODO: 用 AndroidView 嵌入 React Native Surface。
 * 当前占位——composeApp 需额外配置 react-native 依赖才能直接引用 ReactHost/Surface。
 * rn-module 已有完整 RnManager + RNContainerManager，但 ReactHost 类对 composeApp
 * 的 androidMain 不可见（需在 composeApp build.gradle.kts 加 react-native 依赖）。
 * 后续 sprint 配好依赖后替换为实际 AndroidView { RNModuleInit.getDefaultReactHost(app).surface }。
 */
@Composable
actual fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            "RN 动态模块\n组件: $componentName\n（需配置 RN 运行时依赖）",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
