# RN 模块 (rn-module)

本模块为 KMP 项目提供 React Native 容器能力，基于 RN 新架构（Bridgeless + Fabric + TurboModules）。

## 前置要求

### 1. 添加 RN SDK AAR

**重要**: 使用前需要将 RN SDK AAR 文件放入 `libs` 目录：

```
rn-module/
├── libs/
│   ├── rn-sdk-debug.aar      # 调试版本
│   └── rn-sdk-release.aar    # 发布版本（可选）
├── src/
└── build.gradle.kts
```

AAR 文件可以从以下途径获取：
- 从 RN SDK 构建输出目录复制
- 从参考项目 `/Users/wanggt01/projects/rn/TestApp/TestAarApp/app/libs/` 复制

### 2. 项目配置检查

确保 `gradle.properties` 中已启用新架构（已在项目中配置）：
```properties
newArchEnabled=true
```

## Manager 架构设计说明

本模块的 Manager 遵循项目架构规范：

1. **接口在 commonMain**: `IRnManager` 继承 `IManager`，定义跨平台抽象
2. **实现在 platformMain**: 
   - Android: `RnManager` 实现完整的 RN 容器能力
   - iOS: 当前为占位实现，待后续集成
3. **通过 DI 注册**: `initRnManager()` 注册到全局 Manager 系统

### 架构改进建议

关于 Manager 体系的设计思考：

**当前问题**:
1. shared 模块臃肿 - 所有 Manager 集中在 shared，导致模块职责不清晰
2. 初始化分散 - Manager 初始化分散在各 Feature，通过 InitManager() 集中调用
3. 平台实现困难 - 对于需要平台特定实现的 Manager（如 RN），缺乏良好的支持方案

**改进建议**:
1. **模块自管理** - 每个模块管理自己的 Manager，接口在 commonMain，实现在 platformMain
2. **按需注册** - 模块通过注册函数（如 `initRnManager()`）将 Manager 注册到全局系统
3. **延迟初始化** - Manager 接口抽象化，支持运行时动态添加和按需加载

## 使用方法

### 1. 初始化 Provider（Android）

在 Application.onCreate 中初始化：

```kotlin
import com.wgt.rn_module.RnManagerProvider

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 RN Manager Provider
        RnManagerProvider.initialize(this)
    }
}
```

### 2. 注册到 Manager 系统

在 `InitManager()` 或 `ManagerObserver.onAppLaunched()` 中注册：

```kotlin
import com.wgt.rn_module.initRnManager

fun InitManager() {
    // 注册其他 Manager...
    initRnManager()
}
```

### 3. 通过 DI 获取 IRnManager

```kotlin
import com.wgt.rn_module.IRnManager
import com.wgt.architecture.di.inject

class MyViewModel {
    // 通过依赖注入获取
    private val rnManager: IRnManager by inject()
    
    suspend fun initRn() {
        // 初始化 RN 环境（SoLoader + FeatureFlags）
        rnManager.initialize()
        
        // 激活并启动 ReactHost
        rnManager.activate()
    }
}
```

### 4. 直接使用（Application Context）

```kotlin
import com.wgt.rn_module.RnManagerProvider

// 获取 IRnManager 实例
val rnManager = RnManagerProvider.getManager()

// 使用协程执行生命周期方法
lifecycleScope.launch {
    // 初始化
    rnManager.initialize()
    
    // 激活并启动
    rnManager.activate()
    
    // 预加载（可选）
    rnManager.preload()
}
```

### 5. 获取 ReactHost（Android 平台）

```kotlin
import com.wgt.rn_module.RnManager
import android.app.Application

// 从 Android 平台获取具体实现
val rnManager = RnManager.getInstance(application)

// 获取 ReactHost
val reactHost = rnManager.getReactHost()

// 获取 ReactContext（异步等待）
lifecycleScope.launch {
    val reactContext = rnManager.awaitReactContext()
    // 创建 ReactSurface...
}
```

## API 参考

### IRnManager 接口

| 方法 | 说明 |
|------|------|
| `initialize()` | 初始化 RN 环境（SoLoader + FeatureFlags） |
| `activate()` | 激活 RN 容器，启动 ReactHost |
| `deactivate()` | 停用 RN 容器，暂停但不销毁 |
| `destroy()` | 销毁 RN 容器，清理资源 |
| `preload()` | 预加载 ReactContext |
| `reload(reason)` | 重新加载 Bundle |
| `getCurrentHostId()` | 获取当前 Host ID |
| `switchHost(...)` | 切换 Host（多 Bundle） |

### 从 IManager 继承

| 属性 | 说明 |
|------|------|
| `name` | Manager 名称 |
| `isInitialized` | 是否已初始化 |
| `isActive` | 是否已激活 |

## Compose 中使用示例

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import com.wgt.rn_module.RnManagerProvider
import com.facebook.react.ReactSurfaceImpl

@Composable
fun RNContent(
    modifier: Modifier = Modifier,
    componentName: String = "MediaManagerApp"
) {
    val context = LocalContext.current
    val rnManager = remember { RnManagerProvider.getManager() }
    var reactSurface by remember { mutableStateOf<ReactSurfaceImpl?>(null) }
    
    LaunchedEffect(Unit) {
        // 初始化并激活
        rnManager.initialize()
        rnManager.activate()
        
        // 等待 ReactContext 初始化
        (rnManager as? RnManager)?.awaitReactContext()
        
        // 创建 Surface
        val host = (rnManager as RnManager).getReactHost()
        val surface = host.createSurface(
            context as Activity,
            componentName,
            null
        ) as? ReactSurfaceImpl
        
        surface?.start()
        reactSurface = surface
    }
    
    DisposableEffect(Unit) {
        onDispose {
            reactSurface?.stop()
            reactSurface?.detach()
        }
    }
    
    reactSurface?.view?.let { surfaceView ->
        AndroidView(
            factory = { surfaceView },
            modifier = modifier
        )
    } ?: Box(modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}
```

## 目录结构

```
rn-module/
├── libs/                      # RN SDK AAR 文件
├── src/
│   ├── commonMain/
│   │   └── kotlin/com/wgt/rn_module/
│   │       ├── IRnManager.kt         # Manager 接口
│   │       └── RnInitializer.kt      # 注册函数
│   ├── androidMain/
│   │   ├── assets/
│   │   │   ├── index.android.bundle  # RN JS Bundle
│   │   │   └── drawable-mdpi/        # 资源文件
│   │   ├── kotlin/com/wgt/rn_module/
│   │   │   ├── RNContainerManager.kt # 底层容器管理
│   │   │   ├── RNModuleInit.kt       # 模块初始化（已废弃，使用 Manager）
│   │   │   ├── RnManager.kt          # Manager 实现
│   │   │   └── RnManagerProvider.kt  # Android Provider
│   │   └── AndroidManifest.xml
│   └── iosMain/
│       └── kotlin/com/wgt/rn_module/
│           └── RnManagerProvider.kt   # iOS Provider（占位）
├── build.gradle.kts
└── README.md
```

## 注意事项

1. **AAR 文件**: 必须在 `libs` 目录放置 `rn-sdk-debug.aar`（调试）或 `rn-sdk-release.aar`（发布）
2. **Bundle 文件**: `index.android.bundle` 需放入 `src/androidMain/assets/`
3. **权限**: 已自动添加 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限
4. **新架构**: 确保 `gradle.properties` 中 `newArchEnabled=true`
5. **Provider 初始化**: 在 Application.onCreate 中调用 `RnManagerProvider.initialize(app)`

## 故障排除

### "Could not find com.facebook.react:react-native"
确认 AAR 文件已正确放入 `libs` 目录

### "RnManagerProvider 未初始化"
确认在 Application.onCreate 中调用了 `RnManagerProvider.initialize(app)`

### "libreactnative.so not found"
确认已调用 `rnManager.initialize()` 初始化 SoLoader

### "Bridgeless architecture not enabled"
确认 `gradle.properties` 中 `newArchEnabled=true`，并且在创建 ReactHost 前已启用 FeatureFlags
