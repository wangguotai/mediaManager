# Manager 架构优化建议

## 当前架构分析

### 1. 整体流程

```
Application.onCreate()
    │
    ├─► ManagerObserver.onAppLaunched()
    │       │
    │       ├─► InitManager()              ← shared 模块
    │       │       │
    │       │       ├─► FeatureManager()
    │       │       │       │
    │       │       │       └─► 注册各 Manager（分散在各模块）
    │       │       │
    │       │       └─► 直接注册 Manager（如果在 shared）
    │       │
    │       └─► InitFeature()              ← composeApp 模块
    │               │
    │               └─► 调用各 Feature 初始化函数
    │
    └─► 后续使用 inject<>() 获取 Manager 实例
```

### 2. 当前问题

#### 问题 1: shared 模块臃肿

```
shared/
├── manager/          ← 所有 Manager 基类和接口
├── feature/          ← Feature 基类
├── di/               ← 依赖注入容器
└── ...其他通用代码

# 问题：随着功能增加，shared 会越来越臃肿
# - Manager 接口和基类集中在 shared
# - 但实际上 Manager 的具体实现在各平台/模块
```

#### 问题 2: Manager 注册分散

```kotlin
// 当前方式：InitManager 在 shared，但 Manager 分布在各处
fun InitManager() {
    // 这些 Manager 可能在其他模块
    registerManager<DatabaseManager>()  // 可能来自 feature-db
    registerManager<NetworkManager>()   // 可能来自 feature-network
    registerManager<GalleryManager>()   // 来自 feature-common
    // ... 越来越多，shared 需要依赖所有模块
}
```

#### 问题 3: 平台实现困难

```kotlin
// 对于需要平台特定实现的 Manager（如 RN）
// 当前方案缺乏良好的支持方式

// shared 中定义
interface IRnManager : IManager

// 但实现需要在 androidMain 和 iosMain 分别写
// 且注册时需要知道平台特定的工厂函数
```

## 优化建议

### 1. 模块自管理架构

核心理念：**每个模块负责自己的 Manager 生命周期**

```
rn-module/
├── commonMain/
│   ├── IRnManager.kt          ← 接口（继承 IManager）
│   └── RnInitializer.kt       ← 注册函数
├── androidMain/
│   ├── RnManager.kt           ← Android 实现
│   └── RnManagerProvider.kt   ← Android Provider
└── iosMain/
    └── RnManagerProvider.kt   ← iOS Provider

# 此模块不依赖 shared 的 Manager，只依赖：
# - shared 的 DI 容器
# - shared 的 IManager 接口
# - shared 的 DispatcherProvider
```

### 2. 注册方式改进

#### 方案 A: 各模块自行注册（推荐）

```kotlin
// rn-module/src/commonMain/kotlin/../RnInitializer.kt
fun initRnManager() {
    registerManager<IRnManager>(Lifecycle.SINGLETON) {
        RnManagerProvider.getManager()
    }
}

// 使用 KSP 自动生成 InitFeature.kt
// KSP 扫描所有 @ManagerProvider 注解

// composeApp/src/commonMain/kotlin/../InitFeature.kt（KSP 生成）
fun InitFeature() {
    initRnManager()      // 自动插入
    initGalleryFeature() // 自动插入
    initNetworkFeature() // 自动插入
    // ...
}
```

#### 方案 B: 延迟注册

```kotlin
// 模块提供注册能力，但不立即注册
object RnManagerModule {
    fun register() {
        registerManager<IRnManager>(...) { }
    }
}

// 在需要时注册
class MyViewModel {
    init {
        // 第一次使用时注册并初始化
        RnManagerModule.register()
    }
}
```

### 3. 初始化流程优化

#### 当前流程 vs 优化后

```
当前:                          优化后:
┌──────────────┐               ┌──────────────┐
│ Application  │               │ Application  │
└──────┬───────┘               └──────┬───────┘
       │                              │
       ├─► ManagerObserver            ├─► ManagerObserver
       │       │                      │       │
       │       ├─► InitManager        │       └─► InitFeature() [KSP生成]
       │       │                              │
       │       │                              ├─► initRnManager()
       │       │                              │       │
       │       │                              │       └─► register IRnManager
       │       │                              │
       │       └─► InitFeature()              ├─► initGalleryManager()
       │               │                      │       │
       │               └─► 各Feature初始化      │       └─► register IGalleryManager
       │                                      │
       └─► inject<IManager>() 【使用】          └─► inject<IManager>() 【使用】
```

### 4. 目录结构调整建议

#### 当前结构

```
shared/
└── src/commonMain/kotlin/com/wgt/architecture/
    ├── manager/
    │   ├── Manager.kt           ← IManager + AbstractManager
    │   ├── GlobalManager.kt     ← 全局管理
    │   └── claim/
    │       └── IGlobalManager.kt
    ├── feature/
    │   └── Feature.kt           ← IFeature + AbstractFeature
    └── di/
        └── DependencyContainer.kt
```

#### 建议结构（拆分臃肿的 shared）

```
architecture-core/              ← 核心抽象（最精简）
├── manager/
│   └── IManager.kt            ← 仅接口
├── feature/
│   └── IFeature.kt            ← 仅接口
├── di/
│   └── DependencyContainer.kt ← DI 容器
└── dispatchers/
    └── DispatcherProvider.kt  ← 调度器

# 其他移出 shared
shared-ui/                      ← UI 相关
shared-logger/                  ← 日志系统
shared-platform/                ← 平台抽象
```

### 5. RnManager 示范实现解析

#### 依赖关系

```
rn-module commonMain ──────┐
                           │
    ┌──────────────────────▼──────────┐
    │  Implementation(architecture-core)│  ← 最基础依赖
    └──────────────────────┬──────────┘
                           │
    ┌──────────────────────▼──────────┐
    │   Implementation(projects.shared) │  ← 可选，如果 architecture-core 已拆分
    └─────────────────────────────────┘

rn-module androidMain ─────┐
                           │
    ┌──────────────────────▼──────────┐
    │ libs/rn-sdk-debug.aar           │
    │ React Native dependencies       │
    └─────────────────────────────────┘
```

#### 关键实现点

```kotlin
// 1. 接口继承 IManager（来自 architecture-core）
interface IRnManager : IManager {
    suspend fun preload()
    suspend fun reload(reason: String)
    // ... RN 特有方法
}

// 2. Android 实现内部类
internal class RnManager private constructor(...) : IRnManager {
    // 单例模式
    // 完整生命周期管理
}

// 3. Provider 模式
actual object RnManagerProvider {
    fun initialize(app: Application) { }
    actual fun getManager(): IRnManager { }
}

// 4. 注册函数
fun initRnManager() {
    registerManager<IRnManager>(...) { RnManagerProvider.getManager() }
}
```

## 实施建议

### Phase 1: 创建 rn-module（已完成）
- [x] 按照模块自管理架构实现 RnManager
- [x] 创建 IRnManager 接口继承 IManager
- [x] 实现 Android 平台的 RnManager
- [x] 创建注册函数 initRnManager()

### Phase 2: 架构拆分（可选）
- [ ] 创建 architecture-core 模块
- [ ] 将 IManager、IFeature、DI 容器移入
- [ ] 修改 shared 依赖 architecture-core

### Phase 3: 推广模式（后续）
- [ ] 其他 Manager 模块参考 RnManager 模式重构
- [ ] DatabaseManager → feature-db/databaseManager
- [ ] NetworkManager → feature-network/networkManager
- [ ] GalleryManager → feature-gallery/galleryManager

### Phase 4: KSP 自动生成（可选）
- [ ] 创建 @ManagerProvider 注解
- [ ] 创建 KSP 处理器
- [ ] 自动生成 InitFeature.kt 中的注册调用

## 好处总结

1. **职责清晰**: 每个模块管理自己的 Manager，不再集中在 shared
2. **按需加载**: 模块可以被依赖但不初始化，需要时才注册
3. **平台解耦**: 接口在 commonMain，实现分散在各平台
4. **可测试性**: 模块可以独立测试，不依赖其他 Manager
5. **扩展性**: 新增 Manager 只需创建新模块，不修改 shared
