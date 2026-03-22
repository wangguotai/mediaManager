# Media Manager - 媒体管理器

## 项目概述

Media Manager 是一个基于 Kotlin Multiplatform (KMP) 的跨平台媒体管理应用，使用 Compose Multiplatform 构建用户界面。该项目支持 Android、iOS、桌面 (JVM) 和 Web 平台。

### 主要功能
- 本地照片图库访问和管理（支持 Live Photo）
- 媒体文件上传到服务器
- 跨平台媒体浏览和选择
- 支持多种媒体类型：图片、视频、Live Photo

## 技术栈

### 核心框架
- **Kotlin Multiplatform**: 2.3.0 - 跨平台代码共享
- **Compose Multiplatform**: 1.10.0 - 跨平台 UI 框架
- **Kotlin Coroutines**: 1.10.2 - 异步编程
- **Android Gradle Plugin**: 8.11.2
- **Compose Hot Reload**: 1.0.0 - 开发时热重载

### 代码生成
- **KSP (Kotlin Symbol Processing)**: 2.3.0 - 注解处理
- **KotlinPoet**: 2.2.0 - 代码生成
- **Wire**: 5.5.0 - Protobuf 代码生成

### 依赖管理
- **Gradle Version Catalog**: `gradle/libs.versions.toml` 集中管理依赖版本

## 项目结构

```
media-manager/frontend/
├── composeApp/              # 主应用模块（Compose UI）
│   ├── src/commonMain/      # 跨平台共享代码
│   ├── src/androidMain/     # Android 特定代码
│   ├── src/iosMain/         # iOS 特定代码
│   └── src/jvmMain/         # 桌面 (JVM) 特定代码
├── shared/                  # 共享核心模块
│   ├── src/commonMain/      # 跨平台共享代码
│   │   └── kotlin/com/wgt/
│   │       ├── architecture/  # 架构基础组件
│   │       │   ├── annotation/    # 注解定义
│   │       │   ├── di/            # 依赖注入容器
│   │       │   ├── feature/       # Feature 基类和接口
│   │       │   └── manager/       # Manager 基类和实现
│   │       └── platform/      # 平台抽象
│   ├── src/androidMain/     # Android 平台实现
│   └── src/iosMain/         # iOS 平台实现
├── feature-common/          # 通用功能模块
│   └── src/commonMain/kotlin/com/wgt/feature/
│       ├── gallery/         # 照片图库功能
│       └── permission/      # 权限管理功能
├── feature-media/           # 媒体相关功能模块
├── base-network/            # 网络基础模块（预留）
├── protobuf-gen/            # Protobuf 生成的代码
│   └── 使用 Wire 从 proto 文件生成 Kotlin 代码
├── ksp-processor/           # KSP 注解处理器
│   └── 处理 @FeatureProvider 注解，生成 Feature 注册代码
├── rn-plugin/               # React Native 插件（Android）
│   └── rn-android/          # Android 平台的 RN 插件
├── iosApp/                  # iOS 应用入口（Swift）
│   ├── iosApp/iOSApp.swift
│   └── iosApp.xcodeproj/    # Xcode 项目
└── scripts/                 # 构建脚本
    └── composeApp/
        └── updateInitFeature.gradle.kts  # 更新 InitFeature.kt 的脚本
```

## 架构设计

### Manager-Feature 架构

项目采用分层架构，核心概念：

1. **Manager**: 管理组件的生命周期和协调
   - `IManager` 接口定义 Manager 的基本行为
   - `Manager` 抽象基类提供生命周期管理（初始化、激活、停用、销毁）
   - `FeatureManager`: 管理所有 Feature 的中央管理器

2. **Feature**: 具体的业务功能单元
   - `IFeature` 接口定义 Feature 的基本行为
   - `Feature` 抽象基类提供初始化/销毁生命周期
   - 通过 `@FeatureProvider` 注解标记，由 KSP 自动生成注册代码

3. **依赖注入 (DI)**: 轻量级自定义 DI 容器
   - `DependencyContainer`: 中央依赖注册和解析容器
   - 支持单例 (SINGLETON) 和瞬态 (TRANSIENT) 生命周期
   - `inject()` 扩展函数支持属性委托注入

### 代码生成流程

```
1. 开发者创建 Feature 类并添加 @FeatureProvider 注解
   ↓
2. KSP 处理器 (FeatureProcessor) 扫描并处理注解
   ↓
3. 生成 FeatureExtensions.kt（每个包一个）
   - 为每个 Feature 生成扩展属性（如 manager.feature.gallery）
   - 为每个 Feature 生成初始化函数（如 initGalleryFeature()）
   ↓
4. Gradle 脚本 (updateInitFeature.gradle.kts) 扫描所有 FeatureExtensions.kt
   ↓
5. 更新 InitFeature.kt，添加所有初始化函数的调用
```

### iOS 生命周期集成

```
iOSApp.swift (SwiftUI @main)
    ↓
@UIApplicationDelegateAdaptor
    ↓
MediaAppDelegateWrapper (Swift) → 调用 MediaAppInitializer (Kotlin)
    ↓
AppLifecycleManager (Kotlin)
    ↓
AppLifecycleObserver (各组件)
```

## 构建命令

### Android
```bash
# 构建 Debug APK
./gradlew :composeApp:assembleDebug

# 安装到设备
./gradlew :composeApp:installDebug
```

### iOS
```bash
# 构建 iOS 框架（ARM64）
./gradlew :composeApp:linkReleaseFrameworkIosArm64

# 构建 iOS 框架（模拟器）
./gradlew :composeApp:linkReleaseFrameworkIosSimulatorArm64

# 在 Xcode 中打开项目
open iosApp/iosApp.xcodeproj
```

### Desktop (JVM)
```bash
# 运行桌面应用
./gradlew :composeApp:run

# 打包桌面应用
./gradlew :composeApp:packageDmg    # macOS
./gradlew :composeApp:packageMsi    # Windows
./gradlew :composeApp:packageDeb    # Linux
```

### Web
```bash
# Wasm 目标（推荐，现代浏览器）
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# JS 目标（兼容旧浏览器）
./gradlew :composeApp:jsBrowserDevelopmentRun
```

### 代码生成
```bash
# 运行 KSP 代码生成
./gradlew kspCommonMainKotlinMetadata

# 更新 InitFeature.kt（通常在编译时自动执行）
./gradlew :composeApp:updateInitFeature
```

## 开发规范

### 代码风格
- 使用 Kotlin 官方代码风格（`kotlin.code.style=official`）
- 中文注释用于业务逻辑说明
- 英文用于类名、函数名、变量名

### Feature 开发规范

1. **创建 Feature 类**:
```kotlin
package com.wgt.feature.xxx

import com.wgt.architecture.di.annotations.FeatureProvider
import com.wgt.architecture.feature.Feature

@FeatureProvider
class XxxFeature : Feature() {
    override val name: String = "XxxFeature"
    
    override suspend fun onInitialize() {
        // 初始化逻辑
    }
    
    override suspend fun onDestroy() {
        // 清理逻辑
    }
}
```

2. **使用 Feature**:
```kotlin
// 通过 Manager 访问
val xxxFeature: XxxFeature by lazy { manager.feature.xxx }

// 或直接注入
val xxxFeature by inject<XxxFeature>()
```

### 平台特定代码

使用 Kotlin 的 `expect`/`actual` 机制：

```kotlin
// commonMain
expect class PlatformSpecificClass

// androidMain
actual class PlatformSpecificClass { /* Android 实现 */ }

// iosMain
actual class PlatformSpecificClass { /* iOS 实现 */ }
```

### 权限管理

使用 `feature-common` 模块中的 PermissionFeature：

```kotlin
val permissionFeature: PermissionFeature by lazy { manager.feature.permission }

// 检查权限
val hasPermission = permissionFeature.checkPermission(Permission.GALLERY)

// 请求权限
val granted = permissionFeature.requestPermission(Permission.GALLERY)
```

## Protobuf 定义

Protobuf 文件位于后端项目：`/Volumes/ext/projects/media-manager/shared/proto/`

主要的 proto 文件：
- `media.proto`: 媒体元数据定义、上传/下载/删除接口

Wire 插件配置在 `protobuf-gen/build.gradle.kts` 中，生成代码输出到 `protobuf-gen/src/commonMain/`。

## 配置说明

### 本地配置
`local.properties`: 包含本地 Android SDK 路径，**不应提交到版本控制**

### Gradle 配置
`gradle.properties`:
- 启用 Gradle 配置缓存和构建缓存
- AndroidX 启用
- 内存配置（4GB 堆内存）

### 版本管理
`gradle/libs.versions.toml`:
- 集中管理所有依赖版本
- 使用 TYPESAFE_PROJECT_ACCESSORS 启用类型安全的项目访问

## 故障排除

### KSP 代码生成问题
如果 Feature 没有被正确注册：
1. 确保类继承自 `Feature` 或实现 `IFeature`
2. 确保添加了 `@FeatureProvider` 注解
3. 运行 `./gradlew clean kspCommonMainKotlinMetadata`
4. 运行 `./gradlew :composeApp:updateInitFeature`

### iOS 构建问题
```bash
# 确保 Xcode 命令行工具正确配置
xcode-select --install
sudo xcode-select --switch /Applications/Xcode.app

# 清理 Xcode 构建缓存
./gradlew clean
rm -rf iosApp/build
# 在 Xcode 中: Cmd+Shift+K (Clean Build Folder)
```

### Gradle 配置缓存问题
如果遇到配置缓存错误：
```bash
./gradlew --stop
./gradlew clean
rm -rf .gradle/configuration-cache
```

## 相关文档

- [Kotlin Multiplatform 官方文档](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/)
- [Wire Protobuf](https://github.com/square/wire)
- [KSP 文档](https://kotlinlang.org/docs/ksp-overview.html)
- [IOS_LIFECYCLE_INTEGRATION.md](./IOS_LIFECYCLE_INTEGRATION.md): iOS 生命周期集成详细说明

## 注意事项

1. **线程安全**: `AppLifecycleManager` 假设在主线程调用
2. **内存管理**: 记得在不需要时注销生命周期观察者
3. **代码生成**: 不要手动修改 `build/generated/` 下的文件
4. **InitFeature.kt**: 由 Gradle 脚本自动更新，手动修改会被覆盖
