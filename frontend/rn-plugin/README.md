# React Native Plugin 模块

此模块用于在 Media Manager 应用中集成 React Native。

## 模块结构

```
rn-plugin/
├── rn-host/          # React Native 宿主模块（打包 AAR）
│   ├── src/main/java/com/wgt/rn/host/
│   │   ├── ReactHostManager.kt      # React Native 宿主管理器
│   │   ├── ReactViewManager.kt      # React Native View 管理器
│   │   ├── ReactExtensions.kt       # 扩展函数
│   │   └── BuildConfig.kt           # 构建配置
│   └── build.gradle.kts
├── rn-android/       # Android 平台集成模块
│   └── build.gradle.kts
├── scripts/          # 构建脚本
│   └── publish-aar.gradle.kts
└── output/           # AAR 输出目录
```

## 使用流程

### 1. 构建 rn-host AAR

```bash
# 构建并发布 AAR
./gradlew :rn-plugin:rn-host:publishAar
```

这会生成 `rn-plugin/output/rn-host.aar` 文件。

### 2. 在 rn-android 中使用

`rn-android` 模块会自动检测并使用 `rn-plugin/output/rn-host.aar`。

### 3. 初始化 React Native

在 Application 中初始化：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化 React Native
        ReactHostManager.getInstance().initialize(this)
    }
}
```

### 4. 嵌入 React Native 视图

```kotlin
val viewManager = ReactViewManager(context)
viewManager.attachToContainer(
    container = containerView,
    moduleName = "MyReactApp",
    initialProps = mapOf("initialProp" to "value")
)
```

## 开发指南

### 添加新的 React Package

在 `ReactHostManager.kt` 中修改 `getPackages()` 方法：

```kotlin
override fun getPackages(): List<ReactPackage> {
    return PackageList(this).packages.apply {
        add(MyCustomPackage())  // 添加自定义 Package
    }
}
```

### 从 React Native 接收事件

```kotlin
viewManager.setReactContextListener { reactContext ->
    // ReactContext 已准备好
    // 可以在这里注册事件监听器
}
```

### 向 React Native 发送事件

```kotlin
viewManager.sendEvent("NativeEvent", mapOf(
    "data" to "value"
))
```

## 注意事项

1. **AAR 版本管理**: 每次修改 rn-host 代码后，需要重新运行 `publishAar` 任务
2. **调试模式**: rn-host 使用 `BuildConfig.DEBUG` 控制调试支持
3. **ProGuard**: 发布版本需要保留 React Native 相关的混淆规则

## 依赖版本

React Native 版本在 `gradle/libs.versions.toml` 中定义：

```toml
[versions]
react-native = "0.82.1"
```
