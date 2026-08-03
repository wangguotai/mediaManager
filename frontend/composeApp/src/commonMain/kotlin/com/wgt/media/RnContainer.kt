package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RN 页面容器（V7 §3.1）。
 *
 * 在 Compose 中嵌入一个 React Native 页面。平台实现：
 * - Android：用 AndroidView 嵌入 ReactSurface（见 PlatformRnView actual），
 *   内部完成 host.start → 等 ReactContext 就绪 → createSurface + start，
 *   并转发 Activity 生命周期到 ReactHost。
 * - iOS：用 UIKitView 嵌入 RCTRootView（待实现）。
 *
 * 远程 bundle 热更新（V7 §3.2）：由各平台 PlatformRnView 内部按需调用
 * ensureBundleWithVersion，失败静默回退 assets 内置 bundle，不阻塞渲染。
 * 此处不再预先 await 热更新——旧实现在此处阻塞调用 ensureBundleWithVersion，
 * 后端不可达时一直 loading 导致白屏。
 *
 * @param componentName RN 组件名（如 "MediaManagerApp"）
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
    // 直接渲染平台 RN 视图——热更新 / host / surface / 生命周期均由各平台
    // PlatformRnView actual 内部处理（参照 TestAarApp 可工作模式）。
    Box(modifier = modifier.fillMaxSize()) {
        PlatformRnView(componentName, bundleAssetName, hostId, Modifier.fillMaxSize())
    }
}

/**
 * 平台 RN 视图嵌入（expect/actual）。
 *
 * Android actual（com.wgt.media.RnContainer.android.kt）：
 * - RNModuleInit.initialize（幂等，Application.onCreate 已调）
 * - RNContainerManager.getOrCreateReactHost
 * - host.start()，注册 ReactInstanceEventListener
 * - onReactContextInitialized 回调中 createSurface + surface.start()
 * - LocalLifecycleOwner 转发 ON_RESUME/ON_PAUSE/ON_DESTROY 到 host
 * - BackHandler 转发返回键给 host.onBackPressed()
 * - surface 就绪后 AndroidView 嵌入 surface.view
 */
@Composable
expect fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier
)
