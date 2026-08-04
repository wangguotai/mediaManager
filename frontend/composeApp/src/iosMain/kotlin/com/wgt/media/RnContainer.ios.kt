package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * iOS 平台 RN 视图嵌入（V7 §3.1 / V8 §1.3 热更新闭环）。
 *
 * TODO: 用 UIKitView 嵌入 RCTRootView。
 * 当前占位——iOS RN 集成需额外配置 Xcode bridging + RCTBridge，
 * 留待后续 sprint 实现（worktree feature/ios-rn-integration）。
 *
 * 热更新参数 [bundleFilePath] / [bundleName] 已接入签名，
 * 待 iOS RN 集成落地后由 actual 实现消费（与 Android 对齐）。
 */
@Composable
actual fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier,
    bundleFilePath: String?,
    bundleName: String?
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            "iOS RN 容器待实现",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
