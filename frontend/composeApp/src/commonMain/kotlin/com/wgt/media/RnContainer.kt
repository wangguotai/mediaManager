package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.wgt.platform.logger.logger

private const val TAG = "RnContainer"

/**
 * RN 页面容器（V7 §3.1）。
 *
 * 在 Compose 中嵌入一个 React Native 页面。平台实现：
 * - Android：用 AndroidView 嵌入 ReactRootView/Surface（需配置 RN 依赖，见 PlatformRnView actual）。
 * - iOS：用 UIKitView 嵌入 RCTRootView（待实现）。
 *
 * 远程 bundle 加载（V7 §3.2）：先经 RnBundleDownloader 下载到本地缓存，
 * 再调平台加载。
 *
 * @param componentName RN 组件名（如 "ActivityCenter"）
 * @param bundleAssetName bundle 文件名（如 "index.android.bundle"）
 * @param hostId ReactHost 标识
 */
@Composable
fun RnContainer(
    componentName: String,
    bundleAssetName: String,
    hostId: String = componentName,
    modifier: Modifier = Modifier
) {
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(componentName, bundleAssetName) {
        loaded = false
        error = null
        try {
            val localPath = ensureBundle(bundleAssetName)
            if (localPath != null) {
                logger.info(TAG, "RN bundle ready: $bundleAssetName -> $localPath")
            } else {
                logger.info(TAG, "RN bundle not in cache, trying assets: $bundleAssetName")
            }
            loaded = true
        } catch (e: Exception) {
            logger.error(TAG, "RN bundle load failed: ${e.message}")
            error = e.message
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> Text("动态模块加载失败: $error", color = MaterialTheme.colorScheme.error)
            !loaded -> Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> PlatformRnView(componentName, bundleAssetName, hostId, Modifier.fillMaxSize())
        }
    }
}

/**
 * 平台 RN 视图嵌入（expect/actual）。
 */
@Composable
expect fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier
)
