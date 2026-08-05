package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView
import rnsdk.bridge.RNSDKBridge_createView
import rnsdk.bridge.RNSDKBridge_createViewWithBundle
import rnsdk.bridge.RNSDKBridge_initialize
import rnsdk.bridge.RNSDKBridge_setBundleURL
import platform.Foundation.NSURL

/**
 * iOS 平台 RN 视图嵌入（V7 §3.1 / V8 §1.3 热更新闭环）。
 *
 * 对标 Android RnContainer.android.kt：
 * - 用 UIKitView 嵌入 RNSDK 的 RNContainerView (Swift)
 * - Kotlin 通过 ObjC bridge (RNSDKBridge.h) 调用 Swift RNSDK
 * - bridge 函数: RNSDKBridge_createView / RNSDKBridge_createViewWithBundle
 *
 * 热更新路径解析（V8 §1.3）：
 * - [bundleFilePath] 非空 → 用文件路径加载 (热更新缓存)
 * - [bundleFilePath] 为 null → 用 main.jsbundle (app bundle 内置)
 *
 * 前提：AppDelegate didFinishLaunching 中需调用 RNSDKBridge_initialize(false)
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier,
    bundleFilePath: String?,
    bundleName: String?
) {
    val rnView = remember(componentName, bundleFilePath) {
        if (bundleFilePath != null) {
            RNSDKBridge_createViewWithBundle(componentName, bundleFilePath)
        } else {
            RNSDKBridge_createView(componentName)
        }
    }

    DisposableEffect(componentName) {
        onDispose {
            (rnView as? UIView)?.removeFromSuperview()
        }
    }

    if (rnView != null) {
        UIKitView(
            factory = { rnView },
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "RN 容器未就绪",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
