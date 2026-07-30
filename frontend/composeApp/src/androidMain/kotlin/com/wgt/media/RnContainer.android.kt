package com.wgt.media

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.runtime.ReactHostImpl
import com.facebook.react.runtime.ReactSurfaceImpl
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.getAuthToken
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.rn_module.RNContainerManager
import com.wgt.rn_module.RNModuleInit

private const val TAG = "RnContainer"

/**
 * Android 平台 RN 视图嵌入（V7 §3.1）。
 *
 * 用 AndroidView 嵌入 RN 新架构 ReactSurfaceView。
 *
 * 流程：
 * 1. RNModuleInit.initialize(app) — Application.onCreate 中已调用（幂等）
 * 2. RNContainerManager.getInstance(app).getOrCreateReactHost(hostId, bundleAsset, componentName)
 * 3. host.start() — 启动 JS 运行时（Hermes）
 * 4. host.createSurface(context, componentName, props) — 创建 Fabric Surface
 * 5. surface.start() → surface.attach(host) → surface.getView() 返回 ReactSurfaceView
 * 6. ReactSurfaceView 嵌入 AndroidView
 * 7. Composable dispose 时 surface.stop() 清理
 */
@Composable
actual fun PlatformRnView(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier
) {
    var surfaceState by remember { mutableStateOf<ReactSurface?>(null) }
    var hostImpl by remember { mutableStateOf<ReactHostImpl?>(null) }

    // 初始化 RN Host + 创建 Surface
    LaunchedEffect(componentName, bundleAssetName, hostId) {
        val app = runCatching {
            if (AppContext.isInitialized) AppContext.applicationContext as? android.app.Application else null
        }.getOrNull()

        if (app == null) {
            android.util.Log.e(TAG, "AppContext not initialized")
            return@LaunchedEffect
        }

        // 幂等初始化 SoLoader + Bridgeless FeatureFlags
        runCatching { RNModuleInit.initialize(app) }

        // 创建/获取 ReactHost
        val manager = RNContainerManager.getInstance(app)
        val host = manager.getOrCreateReactHost(
            hostId = hostId,
            bundleAssetName = bundleAssetName,
            mainComponentName = componentName
        ) as ReactHostImpl
        hostImpl = host

        // 启动 JS 运行时（幂等——已启动则跳过）
        host.start()

        // 创建 Fabric Surface，传入初始 props（后端地址 + token）
        val initialProps = Bundle().apply {
            putString("backendUrl", MediaService.rnBackendBaseUrl())
            // token 从 SettingsStorage 读取（已有 jwtToken 持久化）
            putString("authToken", getAuthToken())
        }
        val surface = host.createSurface(app, componentName, initialProps)
        surfaceState = surface

        // 启动 Surface 渲染（start() 内部会自动 attach 到 host）
        surface.start()
    }

    // 嵌入 ReactSurfaceView 到 Compose
    val currentSurface = surfaceState
    if (currentSurface != null) {
        AndroidView(
            factory = { ctx ->
                // getView() 在 ReactSurfaceImpl 上返回 ReactSurfaceView（extends ViewGroup）
                // ReactSurface 接口没有 getView()，需 cast
                (currentSurface as ReactSurfaceImpl).view as ViewGroup
            },
            modifier = modifier,
            // RN 内部自行 measure/layout，不额外 update
            update = {}
        )
    } else {
        // Surface 未就绪时显示占位
        androidx.compose.foundation.layout.Box(
            modifier = modifier,
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "正在加载 RN 模块...",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Composable 离开时停止 Surface
    DisposableEffect(hostId, componentName) {
        onDispose {
            currentSurface?.stop()
            currentSurface?.detach()
        }
    }
}
