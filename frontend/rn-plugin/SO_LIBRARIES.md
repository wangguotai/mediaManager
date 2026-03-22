# React Native SO 库说明

## 已提取的 SO 库

以下 SO 库已从 RN 0.82.1 AAR 中提取并打包到 rn-host：

| 库名 | 说明 | 大小 (arm64) |
|------|------|-------------|
| `libreactnative.so` | React Native 核心库 | ~6MB |
| `libhermesvm.so` | Hermes JavaScript 引擎 | ~2MB |
| `libjsi.so` | JavaScript Interface | ~400KB |
| `libfbjni.so` | Facebook JNI 工具库 | ~180KB |
| `libc++_shared.so` | C++ 标准库 | ~1.3MB |
| `libhermestooling.so` | Hermes 调试工具 | ~140KB |

## 缺失的 SO 库

### `libreact_featureflagsjni.so`

**状态**: ⚠️ 未找到

**说明**: 
这是 RN 0.82 新架构引入的 Feature Flags JNI 库。可能在以下位置：

1. **独立的 Maven 包**: `com.facebook.react:react-native-featureflags:${version}`
2. **源码编译产物**: 需要从 RN 源码构建
3. **可选组件**: 可能不是所有配置都必需

**解决方案**:

#### 方案 1: 从 Maven 下载 (推荐)

```bash
# 查找 Maven 依赖
find ~/.gradle/caches -name "*featureflags*"

# 或者添加依赖到 rn-host/build.gradle.kts:
// implementation("com.facebook.react:react-native-featureflags:0.82.1")
```

#### 方案 2: 从 RN 源码构建

```bash
cd rn-plugin/rn-source
./gradlew :rn-plugin:rn-source:prepareReactNative
```

#### 方案 3: 禁用 Feature Flags (临时)

在 `ReactHostManager.kt` 中配置：

```kotlin
// 禁用新架构 Feature Flags
System.setProperty("REACT_NATIVE_FEATURE_FLAGS", "false")
```

#### 方案 4: 降级 RN 版本 (稳定)

修改 `gradle/libs.versions.toml`：

```toml
react-native = "0.72.15"  # 使用稳定版本
```

## 自动提取脚本

使用提供的脚本提取 SO 库：

```bash
./rn-plugin/scripts/extract-so.sh
```

## 手动提取

```bash
# 创建目录
mkdir -p rn-plugin/rn-host/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}

# 提取 react-android
unzip -j ~/.gradle/caches/modules-2/files-2.1/com.facebook.react/react-android/0.82.1/*/react-android-0.82.1-release.aar \
    "jni/arm64-v8a/*.so" -d rn-plugin/rn-host/src/main/jniLibs/arm64-v8a/

# 重复其他架构...
```

## 验证 SO 库打包

```bash
# 检查 rn-host AAR
unzip -l rn-plugin/rn-host/build/outputs/aar/rn-host-release.aar | grep "\.so"

# 检查最终 APK
unzip -l composeApp/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64-v8a"
```

## 运行时错误处理

如果遇到 `UnsatisfiedLinkError`，检查：

1. SO 文件是否在 APK 的 `lib/${arch}/` 目录
2. 设备架构是否支持 (arm64-v8a, armeabi-v7a, x86, x86_64)
3. 所有依赖的 SO 是否都已打包

## 相关文档

- [React Native 0.82 Release Notes](https://github.com/facebook/react-native/releases/tag/v0.82.0)
- [RN New Architecture](https://reactnative.dev/docs/new-architecture-intro)
