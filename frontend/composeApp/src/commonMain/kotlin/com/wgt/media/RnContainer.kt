package com.wgt.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RN 页面容器（V7 §3.1 / V8 §1.3 热更新闭环）。
 *
 * 在 Compose 中嵌入一个 React Native 页面。平台实现：
 * - Android：用 AndroidView 嵌入 ReactSurface（见 PlatformRnView actual），
 *   内部完成 host.start → 等 ReactContext 就绪 → createSurface + start，
 *   并转发 Activity 生命周期到 ReactHost。
 * - iOS：用 UIKitView 嵌入 RCTRootView（待实现）。
 *
 * 热更新闭环（V8 §1.3）：
 * - 调用方（如 RnActivityScreen）在进入时通过 ensureBundleWithVersion(bundleName)
 *   检查后端 manifest，有新版本则下载到本地缓存，将返回的本地路径通过
 *   [bundleFilePath] 传入。平台实现优先用该路径加载（跳过 assets 复制）。
 * - [bundleFilePath] 为 null 时（网络失败或无缓存），平台回退到 [bundleAssetName]
 *   指向的 assets 内置 bundle。
 * - [bundleName] 用于平台侧兜底热更新查询（当调用方未预先解析路径时），
 *   为 null 时平台用 [bundleAssetName] 作为 manifest 查询 key。
 *
 * @param componentName RN 组件名（如 "MediaManagerApp"）
 * @param bundleAssetName 内置 assets bundle 文件名（如 "index.android.bundle"），热更新失败时回退
 * @param hostId ReactHost 标识
 * @param bundleFilePath 热更新本地缓存 bundle 路径（非空时优先），null 则回退 assets
 * @param bundleName 热更新 manifest 查询用的 bundle 名称（如 "activity-bundle"），
 *   仅当 [bundleFilePath] 为 null 时平台用作兜底查询
 */
@Composable
fun RnContainer(
    componentName: String,
    bundleAssetName: String,
    hostId: String = componentName,
    modifier: Modifier = Modifier,
    bundleFilePath: String? = null,
    bundleName: String? = null
) {
    // 直接渲染平台 RN 视图——host / surface / 生命周期由各平台
    // PlatformRnView actual 内部处理（参照 TestAarApp 可工作模式）。
    // 热更新路径优先级：bundleFilePath（调用方预解析）> 平台内兜底查询 > assets。
    Box(modifier = modifier.fillMaxSize()) {
        PlatformRnView(
            componentName = componentName,
            bundleAssetName = bundleAssetName,
            hostId = hostId,
            modifier = Modifier.fillMaxSize(),
            bundleFilePath = bundleFilePath,
            bundleName = bundleName
        )
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
 *
 * @param bundleFilePath 热更新本地缓存路径（优先），null 回退 assets
 * @param bundleName 热更新 manifest 查询名，null 时用 bundleAssetName 兜底
 */
@Composable
expect fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier,
    bundleFilePath: String? = null,
    bundleName: String? = null
)
