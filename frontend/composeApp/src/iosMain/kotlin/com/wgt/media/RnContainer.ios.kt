package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * iOS 平台 RN 视图嵌入（V7 §3.1）。
 *
 * TODO: 用 UIKitView 嵌入 RCTRootView。
 * 当前占位——iOS RN 集成需额外配置 Xcode bridging + RCTBridge，
 * 留待后续 sprint 实现。
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
            "iOS RN 容器待实现",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
