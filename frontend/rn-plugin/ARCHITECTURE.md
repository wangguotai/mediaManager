# React Native Plugin 架构说明

## 模块关系

```
┌─────────────────────────────────────────────────────────────────┐
│                        composeApp (Application)                   │
│  ┌─────────────────┐  ┌──────────────────────────────────────┐  │
│  │ rn-plugin:rn-android │  │ rn-plugin:rn-host (AAR)              │  │
│  │  (Library)       │  │  (包含 React Native + Bundle)         │  │
│  │                  │  │                                       │  │
│  │  • RNPluginManager   │  │  • ReactHostManager                   │  │
│  │  • MediaManagerModule│  │  • ReactViewManager                   │  │
│  │  • Native Modules    │  │  • RN Core                            │  │
│  └─────────────────┘  └──────────────────────────────────────┘  │
│           │                            │                         │
│           └────────── compileOnly ─────┘                         │
│                                                                  │
│  依赖配置：                                                       │
│  implementation(project(":rn-plugin:rn-android"))                │
│  implementation(files("rn-plugin/output/rn-host.aar"))            │
│  implementation(libs.react.android)                               │
│  implementation(libs.react.hermes.android)                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ 打包 Bundle
                              ▼
                    ┌──────────────────┐
                    │ rn-test-app (RN) │
                    │                  │
                    │ • JS 业务代码     │
                    │ • Metro 配置      │
                    │ • 测试页面        │
                    └──────────────────┘
```

## 为什么这样设计？

### 1. rn-host (AAR)

**职责**：
- 封装 React Native 核心运行时
- 提供 `ReactHostManager` 管理 RN 生命周期
- 包含打包好的 JS Bundle

**为什么是 AAR？**
- React Native 有大量依赖和资源文件
- AAR 可以完整封装所有内容
- 便于版本管理和分发

### 2. rn-android (Library)

**职责**：
- 提供业务相关的 Native Module
- 封装 `RNPluginManager` 供外部使用
- 不直接包含 RN 运行时

**为什么是 Library + compileOnly？**
- 不能将 AAR 打包进另一个 AAR（Android 限制）
- `compileOnly` 确保编译时可用，运行时由应用提供
- 最终应用负责组合 rn-android 和 rn-host

### 3. composeApp (Application)

**职责**：
- 最终的应用模块
- 引入所有依赖
- 初始化 RN 环境

## 构建流程

```
1. 构建 rn-host AAR
   ./gradlew :rn-plugin:rn-host:publishAar
   
   输出: rn-plugin/output/rn-host.aar

2. 构建 rn-android AAR
   ./gradlew :rn-plugin:rn-android:assembleRelease
   
   输出: rn-plugin/rn-android/build/outputs/aar/rn-android-release.aar

3. 在 composeApp 中使用
   同时引入两个依赖
```

## 开发模式 vs 发布模式

### 开发模式

```
rn-test-app (Metro Server) ←──→ rn-host (Debug)
                                      ↓
                              rn-android (project 依赖)
                                      ↓
                              composeApp
```

- JS 代码从 Metro 服务器实时加载
- 支持热更新
- 需要配置网络权限

### 发布模式

```
rn-test-app (Bundle) ──→ rn-host (AAR)
                              ↓
                       rn-android (Library)
                              ↓
                       composeApp
```

- JS 代码内嵌在 AAR 中
- 无需网络
- 需要重新构建 AAR 来更新

## 常见问题

### Q: 为什么不能直接让 rn-android 依赖 rn-host AAR？

**A**: Android Library 模块（`com.android.library`）不能将 AAR 文件作为依赖打包进输出的 AAR 中。这是 Android Gradle Plugin 的限制。

解决方案：
1. 最终 Application 模块同时引入两者
2. 使用 Maven 仓库发布，通过坐标引用

### Q: 如何让 composeApp 使用？

**A**: 在 `composeApp/build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(project(":rn-plugin:rn-android"))
    implementation(files("../rn-plugin/output/rn-host.aar"))
    implementation(libs.react.android)
    implementation(libs.react.hermes.android)
}
```

### Q: 如何调试？

**A**:
1. 开发模式：使用 Metro Server
2. 日志：`adb logcat -s ReactNative:V MediaManager:V`
3. 断点：直接调试 RN 源码

## 扩展 Native Module

1. 在 `rn-android/src/main/java/com/wgt/rn_android/bridge/` 创建 Module
2. 在 `MediaManagerPackage` 中注册
3. 重新构建 rn-android
4. 在 RN 中使用

## 更新 RN Bundle

```bash
cd rn-plugin/rn-test-app
npm run bundle:android
npm run copy:assets
cd ../..
./gradlew :rn-plugin:rn-host:publishAar
```
