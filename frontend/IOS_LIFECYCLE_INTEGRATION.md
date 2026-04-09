# iOS 应用生命周期集成完成总结

## 已完成的工作

### 1. 创建应用生命周期系统

**文件**: `shared/src/iosMain/kotlin/com/wgt/platform/AppLifecycle.kt`

- `AppLifecycleState` 枚举：定义应用生命周期状态（LAUNCHED, FOREGROUND, BACKGROUND, TERMINATING）
- `AppLifecycleObserver` 接口：观察者接口，组件实现此接口以接收生命周期事件
- `AppLifecycleManager` 对象：生命周期管理器，负责管理观察者并分发事件

### 2. 更新 MediaAppDelegate

**文件**: `composeApp/src/iosMain/kotlin/com/wgt/MediaAppDelegate.kt`

关键变更：
- 继承 `NSObject`（必须，用于 `@UIApplicationDelegateAdaptor`）
- 实现 `UIApplicationDelegateProtocol`
- 添加 `@ObjCName("MediaAppDelegate")` 注解，使 Swift 可以访问
- 在各个生命周期回调中调用 `AppLifecycleManager` 的通知方法

### 3. 连接 SwiftUI App

**文件**: `iosApp/iosApp/iOSApp.swift`

```swift
import SwiftUI

@main
struct iOSApp: App {
    // 连接Kotlin的MediaAppDelegate，使iOS应用生命周期与Kotlin代码绑定
    @UIApplicationDelegateAdaptor(MediaAppDelegate.self) var appDelegate
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

### 4. 创建使用示例和文档

- `AppLifecycleExample.kt`: 提供了多个使用示例
- `README_LIFECYCLE.md`: 完整的使用文档

## 架构流程

```
iOSApp.swift (SwiftUI @main)
    ↓
@UIApplicationDelegateAdaptor
    ↓
MediaAppDelegate (Kotlin, 继承 NSObject)
    ↓
AppLifecycleManager.notifyApp*()
    ↓
AppLifecycleObserver (各个组件)
```

## 如何使用

### 1. 创建观察者

```kotlin
class MyComponent : AppLifecycleObserver {
    override fun onAppLaunched() {
        // 应用启动时的初始化
    }
    
    override fun onAppBackground() {
        // 应用进入后台时的处理
    }
    
    override fun onAppForeground() {
        // 应用回到前台时的处理
    }
    
    override fun onAppTerminating() {
        // 应用终止时的清理
    }
}
```

### 2. 注册观察者

```kotlin
val myObserver = MyComponent()
AppLifecycleManager.registerObserver(myObserver)
```

### 3. 在 InitManager 中集成

```kotlin
fun InitManager() {
    val databaseManager = DatabaseManager()
    AppLifecycleManager.registerObserver(databaseManager)
    
    val networkManager = NetworkManager()
    AppLifecycleManager.registerObserver(networkManager)
}
```

## 验证步骤

### 前置条件

确保 Xcode 命令行工具已正确安装：

```bash
xcode-select --install
sudo xcode-select --switch /Applications/Xcode.app
```

### 构建和测试

1. **构建 iOS 框架**：
```bash
cd media-manager/frontend
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

2. **在 Xcode 中打开项目**：
```bash
open iosApp/iosApp.xcodeproj
```

3. **运行应用并查看日志**：
   - 启动应用 → 应该看到 "Application did finish launching"
   - 按 Home 键 → 应该看到 "Application did enter background"
   - 重新打开应用 → 应该看到 "Application will enter foreground"
   - 终止应用 → 应该看到 "Application will terminate"

## 典型应用场景

### 数据库管理
```kotlin
class DatabaseManager : AppLifecycleObserver {
    override fun onAppLaunched() {
        initializeDatabase()
    }
    
    override fun onAppBackground() {
        savePendingChanges()
    }
    
    override fun onAppTerminating() {
        closeDatabase()
    }
}
```

### 网络请求管理
```kotlin
class NetworkManager : AppLifecycleObserver {
    override fun onAppBackground() {
        pauseNetworkRequests()
    }
    
    override fun onAppForeground() {
        resumeNetworkRequests()
    }
}
```

### 缓存管理
```kotlin
class CacheManager : AppLifecycleObserver {
    override fun onAppBackground() {
        flushCacheToDisk()
    }
    
    override fun onAppForeground() {
        preloadCache()
    }
}
```

## 注意事项

1. **线程安全**：`AppLifecycleManager` 假设所有调用都在主线程执行。iOS 的生命周期回调都在主线程调用，因此是安全的。

2. **观察者去重**：同一个观察者只会被注册一次，重复注册不会产生副作用。

3. **状态同步**：新注册的观察者会立即收到当前状态的通知。

4. **内存管理**：记得在不需要时注销观察者，避免内存泄漏。

5. **生命周期顺序**：
   - LAUNCHED 只在应用启动时触发一次
   - FOREGROUND 和 BACKGROUND 可能多次交替触发
   - TERMINATING 只在应用终止时触发一次

## 文件清单

### 新增文件
- `shared/src/iosMain/kotlin/com/wgt/platform/AppLifecycle.kt`
- `shared/src/iosMain/kotlin/com/wgt/platform/AppLifecycleExample.kt`
- `shared/src/iosMain/kotlin/com/wgt/platform/README_LIFECYCLE.md`

### 修改文件
- `composeApp/src/iosMain/kotlin/com/wgt/MediaAppDelegate.kt`
- `iosApp/iosApp/iOSApp.swift`

## 故障排除

### 错误：Cannot find 'MediaAppDelegate' in scope

**原因**：Swift 无法找到 Kotlin 类

**解决方案**：
1. 确保 `MediaAppDelegate` 有 `@ObjCName("MediaAppDelegate")` 注解
2. 确保 `MediaAppDelegate` 继承 `NSObject`
3. 重新构建 Kotlin 框架
4. 在 Xcode 中 Clean Build Folder (Cmd+Shift+K)

### 错误：Generic struct 'UIApplicationDelegateAdaptor' requires that 'NSObject' conform to 'UIApplicationDelegate'

**原因**：`MediaAppDelegate` 没有正确实现 `UIApplicationDelegateProtocol`

**解决方案**：
1. 确保 `MediaAppDelegate` 继承 `NSObject`
2. 确保 `MediaAppDelegate` 实现 `UIApplicationDelegateProtocol`
3. 检查所有必需的方法是否已实现

### 错误：An error occurred during an xcrun execution

**原因**：Xcode 命令行工具未正确配置

**解决方案**：
```bash
xcode-select --install
sudo xcode-select --switch /Applications/Xcode.app
```

## 下一步

1. 在 `InitManager` 中注册需要生命周期感知的组件
2. 为各个 Manager 添加 `AppLifecycleObserver` 实现
3. 测试应用在不同生命周期状态下的行为
4. 根据需要添加更多的生命周期事件处理
