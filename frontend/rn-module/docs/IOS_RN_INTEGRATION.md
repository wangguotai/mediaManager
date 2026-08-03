# iOS RN 集成方案 (对齐 Android AAR 化)

> 状态: 方案设计 (2026-08-03)
> 对标: Android 侧已通过 fat-aar 把 RN 0.84.1 运行时打包成单 AAR，三项目共享。
> 目标: iOS 侧实现等价的"RN 运行时预编译产物 + KMP 模块消费"链路。

---

## 1. 现状分析

### 1.1 Android 侧已打通的链路 (参照基准)

```
rn_test/packages/react-native (RN 0.84.1 源码)
  → build_rn_android.mjs 编译各子模块 AAR
  → fat-aar-plugin embed 成单 rn-sdk-debug.aar (含 codegen SO)
  → TestAarApp / media-manager/rn-module flatDir 引用
  → ReactHost + Bridgeless 架构渲染
```

### 1.2 iOS 侧现状 (待实现)

| 维度 | 状态 |
|---|---|
| RnManager.ios.kt | **全 stub** — initialize/activate/destroy 等方法空壳，注释"需与 RCTBridge 集成" |
| PlatformTypes.ios.kt | **占位符** — PlatformReactHost/Context 只有 `object None` |
| iosApp/Podfile | **不存在** — 未接入 CocoaPods |
| RN iOS 运行时 | **未集成** — 无 React.xcframework / 无 pod 依赖 |
| iosApp Xcode 工程 | 已消费 KMP framework (composeApp/build/xcode-frameworks)，但无 RN |

### 1.3 iOS 与 Android 的根本差异

| | Android | iOS |
|---|---|---|
| RN 产物形态 | 单个 .aar (fat-aar 合并) | React.xcframework + 80 个 podspec (CocoaPods 生态) |
| Codegen 方式 | CMake 编译 libappmodules.so，注入 AAR | Codegen 生成 .mm/.h，随 pod 编译 |
| 架构 | Bridgeless ReactHost (新架构) | RCTBridge / RCTRootView (旧) 或 Bridgeless (新架构 iOS 0.74+) |
| 宿主语言 | Kotlin (KMP androidMain) | Swift/Obj-C + Kotlin/Native interop |
| 打包机制 | fat-aar 合并所有依赖 | 无等价"单文件"机制，CocoaPods 管理多 framework |

**关键结论**: iOS 没有 Android 式"fat-aar 单产物"的等价物。最接近的方案是 Meta 官方提供的 **prebuilt React.xcframework** (见 React-Core-prebuilt.podspec)。

---

## 2. 方案对比

### 方案 A: CocoaPods 源码集成 (官方标准路径)

```
iosApp/Podfile 引用 packages/react-native 源码 podspec
  → pod install 从源码编译 React + 所有子 pod
  → 生成 .xcworkspace
  → RnManager.ios.kt 通过 Kotlin/Native interop 调 RCTBridge
```

- ✅ 官方推荐，文档最全
- ✅ 新架构 (Fabric/TurboModule) 自动支持
- ❌ 编译时间长 (首次 10-20 分钟编译 React 源码)
- ❌ 需要 packages/react-native 在 iOS 构建机器上
- ❌ KMP 与 CocoaPods 混合构建复杂
- ❌ 与现有 KMP framework 链路叠加，配置负担重

### 方案 B: 预编译 React.xcframework + 本地 Pod (推荐)

```
rn_test 侧: xcodebuild + cocoapods 打包出 React.xcframework (预编译产物)
  → 放到 rn-module/ios/Frameworks/React.xcframework
  → rn-module 产出含 RN 的 KMP framework
  → iosApp 引用 rn-module framework (现有链路,无需 Podfile)
```

对标 Android: React.xcframework ≈ rn-sdk-debug.aar，rn-module framework ≈ rn-module AAR

- ✅ 产物化，构建机一次编译，消费机直接用
- ✅ iosApp 无需 Podfile (KMP framework 已封装 RN)
- ✅ 与 Android 架构对称
- ⚠️ 预编译 xcframework 打包脚本需开发 (RN 官方有 react-native-xcode-builder 社区工具)
- ⚠️ TurboModule codegen 的 .mm 文件需随 framework 带入

### 方案 C: Swift Package Manager (未来方向)

```
rn-module 产 Swift Package
  → 含 React.xcframework binary target
  → iosApp 通过 SPM 引用
```

- ✅ Apple 生态未来方向，无需 CocoaPods
- ❌ RN 0.84 对 SPM 支持有限 (官方仍以 CocoaPods 为主)
- ❌ KMP 与 SPM 集成尚不成熟
- 评估: **现阶段不适用，留作 V2 演进**

### 推荐: 方案 B (预编译 xcframework)

理由: 与 Android fat-aar 方案架构对称，产物可共享，iosApp 改动最小。

---

## 3. 方案 B 详细设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  rn_test (构建源)                                        │
│  packages/react-native (0.84.1 源码)                     │
│    ↓ build-ios-rn.sh (新脚本, 对标 build-rn-android.mjs) │
│  React.xcframework (预编译, 含所有 RN native + codegen)  │
│    ↓ 复制                                                 │
│  media-manager/frontend/rn-module/ios/Frameworks/        │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  rn-module (KMP)                                         │
│  iosMain: RnManager.ios.kt (实现, 非 stub)               │
│    → Kotlin/Native interop 调 RCTBridge/RCTRootView      │
│  iosFramework: embed React.xcframework                   │
│    ↓ 产出 rn-moduleKit.framework (含 RN)                 │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  iosApp (现有, 改动最小)                                  │
│  引用 composeApp/build/xcode-frameworks (现有链路)        │
│  RN 页面通过 RnContainer.commonMain → iosMain actual     │
│  渲染: RCTRootView 嵌入 UIView (UIKit interop)           │
└─────────────────────────────────────────────────────────┘
```

### 3.2 预编译 React.xcframework 构建

**位置**: `rn_test/doc/build-ios-rn.sh` (对标 `build-rn-android.mjs`)

**流程**:
1. 临时 Podfile 指向 `packages/react-native` 本地源码
2. `pod install` 解析依赖 + codegen
3. `xcodebuild archive` 编译 React 及子 pod 为 framework
4. `xcodebuild -create-xcframework` 合并模拟器+真机架构
5. 产出 `React.xcframework` (含 Headers + Modules + 二进制)

**关键配置**:
- `RN_NEW_ARCH_ENABLED=1` (对齐 Android newArchEnabled)
- `USE_HERMES=1` (对齐 Android hermesEnabled)
- codegen 输出的 .mm/.h 随 framework 打包 (对标 Android libappmodules.so)
- 架构: `iphoneos` (arm64) + `iphonesimulator` (arm64+x86_64)

**产物结构**:
```
React.xcframework/
├── ios-arm64/
│   └── React.framework/        (真机 arm64)
├── ios-arm64_x86_64-simulator/
│   └── React.framework/        (模拟器)
└── Info.plist
```

### 3.3 rn-module iOS 集成

#### 3.3.1 framework 嵌入

`rn-module/build.gradle.kts` iosTarget 配置:
```kotlin
iosArm64 {
    binaries.framework {
        baseName = "rn-moduleKit"
        // 嵌入预编译 React.xcframework
        // 通过 Podfile 或直接 linking
    }
}
```

两种嵌入方式:
- **方式 1 (推荐)**: 在 rn-module 下建临时 Podfile，`pod 'React-Core-prebuilt'` 引用本地 xcframework，CocoaPods 处理 linking
- **方式 2**: iosApp Xcode 工程直接 `Embed & Sign` React.xcframework，rn-module framework 只暴露 interop API

#### 3.3.2 Kotlin/Native Interop

RnManager.ios.kt 需要调用 RN 的 Obj-C API。两种 interop 路径:

**路径 1: def 文件 (纯 Kotlin)**
```kotlin
// rn-module/iosMain/definitions/ReactNative.def
language = Objective-C
headers = React/RCTBridge.h React/RCTRootView.h
---
```
- ✅ 纯 Kotlin，编译期生成 stub
- ❌ 类型映射有时不理想

**路径 2: Swift wrapper (推荐)**
```swift
// rn-module/iosMain/swift/RnBridge.swift
import React
public class RnBridge {
    static func createRootView(moduleName: String) -> RCTRootView { ... }
}
```
KMP 通过 `export OBJC` 暴露给 Kotlin:
```kotlin
// RnManager.ios.kt
actual override suspend fun activate() {
    val rootView = RnBridge.createRootView(componentName)
    // 嵌入 UIKit 容器
}
```
- ✅ Swift 直接用 RN API，类型安全
- ⚠️ rn-module 需启用 Swift 支持或建独立 Swift framework

### 3.4 RnContainer iosMain 实现

对标 Android 的 `RnContainer.android.kt`，新建 `RnContainer.ios.kt`:

```kotlin
@Composable
actual fun RnContainer(
    componentName: String,
    bundleAssetName: String,
    hostId: String,
    modifier: Modifier
) {
    // iOS Compose Multiplatform 支持 UIView 嵌入
    UIKitView(
        factory = {
            RCTRootView(
                bridge: sharedBridge,
                moduleName: componentName,
                initialProperties: [:]
            )
        },
        modifier = modifier
    )
}
```

**关键点**:
- iOS RN 用 **单 RCTBridge** (不像 Android 多 ReactHost)，RnManager 管理 bridge 生命周期
- RCTRootView 直接作为 UIView 嵌入 Compose (UIKitView interop)
- JS bundle 从 App Bundle 资源加载 (`Bundle.main.urlForResource`)

### 3.5 JS Bundle 处理

| 平台 | bundle 来源 | 机制 |
|---|---|---|
| Android | assets/index.android.bundle → 复制到 files/ | JSBundleLoader.createFileLoader |
| iOS | App Bundle 资源 | `[[RCTBridge alloc] initWithBundleURL:...]` |

- rn_test 侧同一份 JS bundle 逻辑可在 iOS 复用 (Metro 产出平台无关 Hermes bytecode)
- 需将 `index.android.bundle` 重命名为 `index.ios.bundle` 放入 iosApp 资源
- 或用同一份 `main.jsbundle` (两端共享 Hermes bytecode，RN 0.84 Hermes 支持)

### 3.6 Codegen 对齐

Android 用 `android/app` 模块编译产生 libappmodules.so (注册 TurboModule)。
iOS 对应: codegen 生成 `<AppSpec>.mm/.h` 文件，随 React.xcframework 编译。

需确保:
- SafeAreaContext TurboModule iOS 版本存在 (react-native-safe-area-context 有 iOS podspec)
- media-manager 自定义 TurboModule (如有) 的 codegen spec 在 iOS 编译

---

## 4. 实施路线图

### Phase 1: 预编译产物链路 (rn_test 侧)
1. 编写 `build-ios-rn.sh` 脚本
2. 验证 React.xcframework 产出 (真机+模拟器)
3. 验证 codegen .mm 文件含 TurboModule 注册
4. 产出对标: `rn-module/ios/Frameworks/React.xcframework`

依赖: macOS + Xcode + CocoaPods (RN iOS 编译必需 macOS 环境)

### Phase 2: rn-module iOS 实现
1. RnManager.ios.kt 从 stub 改为真实实现 (RCTBridge 管理)
2. PlatformTypes.ios.kt 定义 RCTBridge/RCTRootView 的 Kotlin 映射
3. RnContainer.ios.kt 实现 (UIKitView + RCTRootView)
4. 验证 rn-moduleKit.framework 产出含 RN interop

### Phase 3: iosApp 集成
1. React.xcframework 嵌入 Xcode 工程 (Embed & Sign)
2. JS bundle 放入 App Bundle 资源
3. AppDelegate 初始化 RCTBridge (对标 Android Application.onCreate)
4. 验证: 进入 RN Tab 页面渲染

### Phase 4: 端到端验证
1. media-manager iOS 真机运行 RN 页面
2. TurboModule (DeviceInfo/SafeAreaContext) 正常
3. 活动中心页面 (MediaManagerApp) 渲染

---

## 5. 风险与约束

| 风险 | 影响 | 缓解 |
|---|---|---|
| Xcode/CocoaPods 版本兼容 | 编译失败 | 锁定 Xcode 16+, CocoaPods 1.15+ |
| Kotlin/Native interop 类型映射 | API 不可用 | Swift wrapper 中间层 |
| React.xcframework 体积大 (>100MB) | 仓库膨胀 | gitignore + 构建产物共享 (同 Android aar) |
| iOS 新架构 (Fabric) iOS 端成熟度 | 渲染异常 | 可先走 RCTBridge 旧架构，后续切 Bridgeless |
| KMP Compose iOS UIKitView interop | RN 视图不渲染 | 已有 Compose Multiplatform iOS UIView 支持验证 |
| 构建机需 macOS | 无法在 Linux 构建 | iOS 构建本就需 macOS (Apple 限制) |

---

## 6. 与 Android 方案的对称性

| 维度 | Android (已实现) | iOS (本方案) |
|---|---|---|
| 构建源 | rn_test/packages/react-native | 同左 |
| 构建脚本 | build-rn-android.mjs | build-ios-rn.sh |
| 产物 | rn-sdk-debug.aar (fat-aar) | React.xcframework |
| 共享方式 | rn-module/libs/ flatDir | rn-module/ios/Frameworks/ |
| 运行时 API | ReactHost (Bridgeless) | RCTBridge (可后续升 Bridgeless) |
| KMP 容器 | RnContainer.android.kt | RnContainer.ios.kt |
| Codegen | libappmodules.so (注入 AAR) | .mm/.h (随 xcframework) |
| 生命周期 | Application.onCreate (SoLoader+FeatureFlags) | AppDelegate.didFinishLaunching (RCTBridge init) |
| 验证 App | TestAarApp | iosApp |
