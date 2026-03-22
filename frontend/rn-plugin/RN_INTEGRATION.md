# rn-test-app 与 rn-host 对接指南

本文档详细说明如何将 React Native 测试项目（rn-test-app）与 Android 宿主模块（rn-host）进行对接。

## 对接流程

```
┌──────────────┐     Bundle     ┌──────────────┐      AAR       ┌──────────────┐
│  rn-test-app │ ───────────────→│   rn-host    │ ────────────────→│  rn-android  │
│   (RN JS)    │   copy-assets   │ (RN Android) │   publishAar    │   (集成层)    │
└──────────────┘                 └──────────────┘                 └──────────────┘
       │                                │                                │
       │ npm run bundle:android         │ ./gradlew assembleRelease      │ 自动依赖
       │                                │                                │
       ↓                                ↓                                ↓
  index.android.bundle              rn-host.aar                     完整功能
```

## 完整对接步骤

### 步骤 1：初始化 rn-test-app

```bash
cd rn-plugin/rn-test-app

# 安装依赖（仅需首次）
npm install

# 或者使用 yarn
yarn install
```

### 步骤 2：开发模式测试（可选）

开发时可以使用 Metro Server 实时加载 JS，无需打包：

```bash
# 启动 Metro 服务器
cd rn-plugin/rn-test-app
npm start

# 在另一个终端构建并安装 debug APK
# （需要在 rn-android 或 composeApp 中配置 Debug 模式）
```

### 步骤 3：打包 Bundle 到 rn-host

#### 方式 A：自动打包（推荐）

```bash
cd rn-plugin/rn-test-app

# 构建 Release Bundle 并自动复制到 rn-host
npm run bundle:android && npm run copy:assets
```

#### 方式 B：开发模式 Bundle

```bash
cd rn-plugin/rn-test-app

# 构建开发模式 Bundle（包含调试信息）
npm run bundle:android:dev && npm run copy:assets
```

### 步骤 4：构建 rn-host AAR

```bash
# 在项目根目录
cd /Volumes/ext/projects/media-manager/frontend

# 构建 AAR 并复制到 output 目录
./gradlew :rn-plugin:rn-host:publishAar
```

构建成功后，AAR 文件位于：
```
rn-plugin/output/rn-host.aar
```

### 步骤 5：验证对接

```bash
# 检查 rn-android 是否能正确依赖 AAR
./gradlew :rn-plugin:rn-android:dependencies --configuration implementation | grep rn-host

# 或者构建 rn-android
./gradlew :rn-plugin:rn-android:assembleDebug
```

## 配置说明

### rn-test-app 配置

**package.json 关键配置：**

```json
{
  "scripts": {
    "bundle:android": "react-native bundle --platform android --dev false --entry-file index.js --bundle-output android/app/src/main/assets/index.android.bundle",
    "copy:assets": "node scripts/copy-assets.js"
  },
  "dependencies": {
    "react-native": "0.82.1"
  }
}
```

**metro.config.js 配置：**

```javascript
const config = {
  resolver: {
    // 避免监听不必要的文件
    blacklistRE: /rn-plugin\/(rn-host|rn-android|scripts|output)\/.*/,
  },
};
```

### rn-host 配置

**build.gradle.kts 关键配置：**

```kotlin
android {
    // 确保包含 assets
    sourceSets["main"].assets.srcDir("src/main/assets")
}

dependencies {
    // React Native 核心依赖
    implementation(libs.react.android)
    implementation(libs.react.hermes.android)
}
```

### rn-android 配置

**build.gradle.kts 动态依赖：**

```kotlin
dependencies {
    val aarFile = rootProject.file("rn-plugin/output/rn-host.aar")
    if (aarFile.exists()) {
        implementation(files(aarFile))
    }
}
```

## 模块注册

### 1. rn-test-app 注册组件

**index.js：**

```javascript
import {AppRegistry} from 'react-native';
import App from './src/App';

// 注册多个组件名，供不同场景使用
AppRegistry.registerComponent('RNTTestApp', () => App);
AppRegistry.registerComponent('MediaGallery', () => App);
AppRegistry.registerComponent('TestHome', () => App);
```

### 2. rn-android 注册 Native Module

**MediaManagerPackage.kt：**

```kotlin
class MediaManagerPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(MediaManagerModule(reactContext))
    }
    // ...
}
```

## 使用示例

### 在 Compose 中加载 RN 页面

```kotlin
@Composable
fun ReactNativeTestScreen() {
    val context = LocalContext.current
    
    AndroidView(
        factory = { FrameLayout(it) },
        update = { container ->
            RNPluginManager.getInstance().loadReactModule(
                container = container,
                moduleName = "MediaGallery",  // 对应 rn-test-app 中注册的名称
                initialProps = mapOf(
                    "title" to "测试标题",
                    "userId" to "12345"
                )
            )
        }
    )
}
```

### RN 调用 Native 方法

```javascript
// rn-test-app/src/screens/MediaGalleryScreen.js
import MediaManager from '../modules/MediaManager';

// 获取媒体列表
const mediaList = await MediaManager.getMediaList();

// 选择媒体
await MediaManager.selectMedia({ mediaType: 'image', maxCount: 5 });
```

### Native 发送事件到 RN

```kotlin
// rn-android 中
viewManager.sendEvent("NativeEvent", mapOf(
    "type" to "media_selected",
    "data" to mediaInfo
))
```

```javascript
// rn-test-app 中
MediaManager.onMediaSelected((data) => {
    console.log('收到 Native 事件:', data);
});
```

## 调试指南

### 开发模式调试

1. **启动 Metro：**
   ```bash
   cd rn-test-app && npm start
   ```

2. **查看日志：**
   ```bash
   adb logcat -s ReactNative:V ReactNativeJS:V
   ```

3. **调试菜单：**
   - 摇一摇设备或按 `Ctrl+M`（Android 模拟器）
   - 选择 "Debug" 或 "Reload"

### 生产模式调试

1. **验证 Bundle：**
   ```bash
   ls -la rn-host/src/main/assets/index.android.bundle
   ```

2. **解压 AAR 检查：**
   ```bash
   cd rn-plugin/output
   unzip -l rn-host.aar | grep assets
   ```

3. **查看 Native 日志：**
   ```bash
   adb logcat -s MediaManager:V RNPluginManager:V
   ```

## 常见问题

### Q1: Metro 连接失败

**症状：** 红屏错误 "Unable to load script"

**解决：**
```bash
# 1. 确保 Metro 已启动
npm start

# 2. 检查网络连接
adb reverse tcp:8081 tcp:8081

# 3. 配置 DevSettings
adb shell input keyevent 82  # 打开开发菜单
# 选择 "Change Bundle Location" 输入电脑 IP:8081
```

### Q2: Native Module 为 null

**症状：** `MediaManager.getMediaList()` 报错 "null is not an object"

**解决：**
1. 确认 rn-android 已重新构建
2. 检查 AAR 是否包含最新的 rn-host
3. 验证 MediaManagerPackage 是否已注册

### Q3: Bundle 未找到

**症状：** 白屏或 "Could not connect to development server"

**解决：**
```bash
# 重新构建 bundle
cd rn-test-app
npm run bundle:android
npm run copy:assets

# 验证文件
cat ../rn-host/src/main/assets/index.android.bundle | head

# 重新构建 AAR
cd ..
./gradlew :rn-plugin:rn-host:publishAar
```

### Q4: 图片资源不显示

**解决：**
```bash
# 确保 assets-dest 参数正确
npx react-native bundle \
  --platform android \
  --dev false \
  --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res/  # 重要：指定资源输出目录
```

## 完整构建脚本

创建一个一键构建脚本：

```bash
#!/bin/bash
# rn-plugin/build.sh

echo "=== Building React Native Plugin ==="

# 1. 安装依赖
cd rn-test-app
npm install

# 2. 构建 Bundle
npm run bundle:android

# 3. 复制到 rn-host
npm run copy:assets

# 4. 构建 AAR
cd ../..
./gradlew :rn-plugin:rn-host:publishAar

echo "✅ Build complete! AAR location: rn-plugin/output/rn-host.aar"
```

## 版本对应关系

| rn-test-app | rn-host | react-native |
|-------------|---------|--------------|
| 1.0.0       | 1.0.0   | 0.82.1       |

确保版本匹配，特别是 react-native 版本要与 libs.versions.toml 中一致。

## 相关文件

- [rn-test-app/README.md](./rn-test-app/README.md) - RN 项目说明
- [rn-host/src/main/assets/README.md](./rn-host/src/main/assets/README.md) - Assets 说明
- [INTEGRATION_GUIDE.md](./INTEGRATION_GUIDE.md) - 整体集成指南
