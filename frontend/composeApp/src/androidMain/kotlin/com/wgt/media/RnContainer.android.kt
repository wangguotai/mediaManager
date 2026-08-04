package com.wgt.media

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.runtime.ReactHostImpl
import com.facebook.react.runtime.ReactSurfaceImpl
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.getAuthToken
import com.wgt.platform.AppContext
import com.wgt.platform.application
import com.wgt.rn_module.RNContainerManager
import com.wgt.rn_module.RNModuleInit

private const val TAG = "RnContainer"

/**
 * Android 平台 RN 视图嵌入（V7 §3.1 / V8 §1.3 热更新闭环）。
 *
 * 参照 TestAarApp 可工作实现：
 * 1. RNModuleInit.initialize(app) — Application.onCreate 中已调用（幂等）
 * 2. RNContainerManager.getOrCreateReactHost(...)
 * 3. host.start() — 启动 JS 运行时（Hermes）
 * 4. 【关键】等 onReactContextInitialized 回调后才 createSurface + surface.start()
 *    （旧实现在 start() 后立即 createSurface，ReactContext 尚未就绪导致白屏）
 * 5. surface.start() 后 surface.view 非空，再渲染 AndroidView
 * 6. 生命周期转发 ON_RESUME→host.onHostResume, ON_PAUSE→host.onHostPause, ON_DESTROY→host.destroy
 *    （旧实现无生命周期转发，RN UIManager 不运行 → 白屏）
 * 7. Composable dispose / ON_DESTROY 时 surface.stop() + detach 清理
 *
 * 热更新路径解析（V8 §1.3）：
 * - [bundleFilePath] 非空（调用方 RnActivityScreen 预解析）→ 直接作为 override path
 * - [bundleFilePath] 为 null → 用 [bundleName]（回退 bundleAssetName）做兜底
 *   ensureBundleWithVersion 查询；查询失败 RNContainerManager 内部回退 assets
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
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // surface 就绪状态——ReactContext 初始化后才创建并 start，view 才有效
    var surface by remember { mutableStateOf<ReactSurfaceImpl?>(null) }
    var hostImpl by remember { mutableStateOf<ReactHostImpl?>(null) }

    // ReactContext 就绪监听器——提升到 remember 作用域，
    // 使 LaunchedEffect（注册/移除）和 DisposableEffect（清理）都能引用同一实例
    var reactContextListener by remember { mutableStateOf<ReactInstanceEventListener?>(null) }

    // 初始化 RN Host + 启动；surface 创建延后到 onReactContextInitialized 回调
    LaunchedEffect(componentName, bundleAssetName, hostId, bundleFilePath) {
        val app = runCatching {
            if (AppContext.isInitialized) AppContext.application else null
        }.getOrNull()

        if (app == null) {
            android.util.Log.e(TAG, "AppContext not initialized")
            return@LaunchedEffect
        }

        // 幂等初始化 SoLoader + Bridgeless FeatureFlags
        runCatching { RNModuleInit.initialize(app) }

        // 热更新路径解析（V8 §1.3）：
        // 优先用调用方预解析的 bundleFilePath（RnActivityScreen 已查 manifest）；
        // 为空时用 bundleName 做兜底查询（常驻 Tab 等未预解析场景）。
        val overridePath: String? = bundleFilePath ?: runCatching {
            val queryName = bundleName ?: bundleAssetName
            ensureBundleWithVersion(queryName)?.path
        }.getOrNull()

        if (overridePath != null) {
            android.util.Log.i(TAG, "热更新 bundle: $overridePath (bundleName=${bundleName ?: bundleAssetName})")
        } else {
            android.util.Log.i(TAG, "无热更新缓存，回退 assets: $bundleAssetName")
        }

        val manager = RNContainerManager.getInstance(app)
        val host = manager.getOrCreateReactHost(
            hostId = hostId,
            bundleAssetName = bundleAssetName,
            mainComponentName = componentName,
            useDevelopmentSupport = false,
            bundleOverridePath = overridePath
        ) as ReactHostImpl
        hostImpl = host

        // build initial props
        val initialProps = Bundle().apply {
            putString("backendUrl", MediaService.rnBackendBaseUrl())
            putString("authToken", getAuthToken())
        }

        // 【关键修复】等 ReactContext 就绪后再 createSurface + start。
        // 旧实现 host.start() 后立即 createSurface → 白屏（Fabric Surface 在
        // ReactInstance 未初始化时无法挂载视图树）。
        val listener = object : ReactInstanceEventListener {
            override fun onReactContextInitialized(context: ReactContext) {
                android.util.Log.i("RnContainer", "onReactContextInitialized 回调触发, componentName=$componentName")
                // 总在 UI 线程回调
                if (surface != null) return // 已创建（防御）
                android.util.Log.i("RnContainer", "createSurface: componentName=$componentName")
                val s = host.createSurface(app, componentName, initialProps) as ReactSurfaceImpl
                // 注意：createSurface 内部已 attach，无需再调 surface.attach(host)
                s.start()
                surface = s
                android.util.Log.i("RnContainer", "surface.start() 完成")
            }
        }
        reactContextListener = listener
        host.addReactInstanceEventListener(listener)

        // 启动 JS 运行时（幂等——已启动则跳过）
        host.start()

        // 创建 Surface 的统一入口（回调路径 + 轮询路径共用）
        fun tryCreateSurface(ctx: ReactContext) {
            if (surface != null) return
            val s = host.createSurface(app, componentName, initialProps) as ReactSurfaceImpl
            s.start()
            surface = s
        }

        // 快捷路径：若 start() 前 ReactContext 已就绪（host 复用场景），
        // onReactContextInitialized 不会再次回调，需手动触发 surface 创建。
        host.currentReactContext?.let { ctx ->
            tryCreateSurface(ctx)
        }

        // 轮询兜底：onReactContextInitialized 回调在 Compose 协程与 ReactInstance
        // 异步初始化的时序下可能不触发（TestAarApp 在 Activity 同步上下文调用能收到
        // 回调；KMP Compose 的 LaunchedEffect 协程时序不同，回调被错过）。若快捷路径
        // 未命中且 surface 仍空，主线程轮询 currentReactContext 直到就绪，就绪后立即
        // 创建 surface。50ms 间隔，最多 10 秒。
        if (surface == null) {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val checkInterval = 50L
            val maxChecks = 200
            var checks = 0
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (surface != null) return
                    checks++
                    val ctx = host.currentReactContext
                    if (ctx != null) {
                        tryCreateSurface(ctx)
                    } else if (checks < maxChecks) {
                        mainHandler.postDelayed(this, checkInterval)
                    }
                }
            }
            mainHandler.postDelayed(pollRunnable, checkInterval)
        }

        // LaunchedEffect 退出（key 变化/dispose）时移除监听器
        // （surface 清理在下面的 DisposableEffect 中处理）
    }

    // 渲染：surface 就绪后才嵌入 AndroidView，否则占位
    val currentSurface = surface
    if (currentSurface != null && currentSurface.view != null) {
        AndroidView(
            factory = { ctx ->
                // surface.start() 已在回调中调用，view 此时非空
                currentSurface.view as ViewGroup
            },
            modifier = modifier,
            update = {}
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                "正在加载 RN 模块...",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 生命周期转发：让 ReactHost 感知 Activity 生命周期
    // （缺失是白屏主因之一——RN 的 UIManager/动画需要在 ON_RESUME 后才真正运行）
    DisposableEffect(lifecycleOwner, hostImpl, hostId) {
        val observer = LifecycleEventObserver { _, event ->
            val h = hostImpl ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> h.onHostResume(activity, null)
                Lifecycle.Event.ON_PAUSE -> h.onHostPause(activity)
                Lifecycle.Event.ON_DESTROY -> {
                    surface?.stop()
                    surface?.detach()
                    surface = null
                    reactContextListener?.let { h.removeReactInstanceEventListener(it) }
                    runCatching { h.destroy("lifecycle_on_destroy", null) }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Composable 离开组合时清理 surface + 监听器
            surface?.stop()
            surface?.detach()
            surface = null
            val h = hostImpl
            reactContextListener?.let { h?.removeReactInstanceEventListener(it) }
        }
    }

    // 返回键转发给 ReactHost（JS 有机会处理，如导航返回）
    val h = hostImpl
    if (h != null && h.currentReactContext != null) {
        BackHandler(enabled = true) {
            // 若 JS 未处理（onBackPressed 返回 false），finish 当前 Activity
            // 对齐 TestAarApp 中 super.onBackPressed() 的语义（关闭页面）
            if (!h.onBackPressed()) {
                activity?.finish()
            }
        }
    }
}

/**
 * 从 Context 递归找到 Activity（LocalContext 可能是 ContextWrapper）。
 */
private tailrec fun android.content.Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
