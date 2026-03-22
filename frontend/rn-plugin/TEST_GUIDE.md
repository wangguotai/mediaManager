# React Native 集成测试指南

本文档说明如何在 composeApp 中测试 RN 集成功能。

## 测试架构

```
composeApp (Android)
    ├── MediaApplication (初始化 RNPluginManager)
    ├── MainActivity (Compose UI)
    │       └── MediaListScreen
    │               └── RNTestButton ← 点击启动
    └── RN 测试 Activity
            ├── RNTestActivity (RNTTestApp 模块)
            └── MediaGalleryActivity (MediaGallery 模块)
                    └── rn-host (AAR + Bundle)
                            └── rn-test-app (JS)
```

## 准备工作

### 1. 确保 Bundle 已打包

```bash
# 检查 bundle 是否存在
ls rn-plugin/rn-host/src/main/assets/index.android.bundle

# 如果不存在，重新打包
cd rn-plugin/rn-test-app
npm install
npm run bundle:android
npm run copy:assets
```

### 2. 重新构建 rn-host AAR

```bash
cd /Volumes/ext/projects/media-manager/frontend
./gradlew :rn-plugin:rn-host:publishAar
```

### 3. 构建 composeApp

```bash
./gradlew :composeApp:assembleDebug
```

## 测试步骤

### 测试 1：启动 RN 测试页面

1. 在 Android 设备/模拟器上安装应用
   ```bash
   ./gradlew :composeApp:installDebug
   ```

2. 打开应用，进入主界面（媒体列表页面）

3. 点击顶部工具栏的 **刷新图标右侧的按钮**（RN 测试按钮）

4. 预期结果：
   - 启动 RNTestActivity
   - 显示 React Native 测试页面
   - 页面显示 "RN Test App" 标题
   - 显示 "Native Module 状态: 已连接"
   - 显示从 Native 传递的属性（timestamp 等）

### 测试 2：Native Module 调用

在 RN 测试页面中：

1. 点击 **"📷 媒体库测试"**
2. 测试以下功能：
   - **获取媒体列表**：应返回示例数据
   - **选择媒体**：调用 Native 方法
   - **上传媒体**：测试上传流程

### 测试 3：事件通信

1. 在媒体库测试页面，点击 **"选择媒体"**
2. 在 Android Studio 中查看 Logcat
3. 预期看到 RN 调用 Native Module 的日志

## 调试

### 查看日志

```bash
# React Native 日志
adb logcat -s ReactNative:V ReactNativeJS:V

# MediaManager 日志
adb logcat -s MediaManager:V RNPluginManager:V

# 所有日志
adb logcat | grep -E "(ReactNative|MediaManager|RNPlugin)"
```

### 常见问题

#### 1. 白屏或 Bundle 未找到

**症状**：RN 页面白屏，Logcat 显示 "Could not connect to development server"

**解决**：
```bash
# 确认 bundle 在 AAR 中
unzip -l rn-plugin/output/rn-host.aar | grep assets

# 重新构建
./gradlew clean :rn-plugin:rn-host:publishAar :composeApp:assembleDebug
```

#### 2. Native Module 未连接

**症状**：显示 "Native Module 状态: 未连接"

**原因**：
- MediaManagerPackage 未正确注册
- rn-android 和 rn-host 版本不匹配

**解决**：
```bash
# 检查 MediaApplication 是否调用了 RNPluginManager.initialize()
# 重新构建整个项目
./gradlew clean :rn-plugin:rn-host:publishAar :rn-plugin:rn-android:assembleRelease :composeApp:assembleDebug
```

#### 3. Metro Server 连接（开发模式）

如需使用 Metro Server 开发：

```bash
cd rn-plugin/rn-test-app
npm start

# 修改 rn-host BuildConfig.DEBUG = true
# 重新构建
```

## 文件位置

| 文件 | 路径 |
|------|------|
| 测试 Activity | `composeApp/src/androidMain/kotlin/com/wgt/rn/RNTestActivity.kt` |
| 启动器 | `composeApp/src/androidMain/kotlin/com/wgt/rn/RNLauncher.kt` |
| Application | `composeApp/src/androidMain/kotlin/com/wgt/MediaApplication.kt` |
| Manifest | `composeApp/src/androidMain/AndroidManifest.xml` |
| 测试按钮 | `composeApp/src/androidMain/kotlin/com/wgt/rn/RNLauncher.android.kt` |

## 一键测试

```bash
#!/bin/bash
# test-rn-integration.sh

echo "=== React Native Integration Test ==="

# 1. 构建 rn-host AAR
echo "📦 Building rn-host AAR..."
./gradlew :rn-plugin:rn-host:publishAar

# 2. 构建 composeApp
echo "📦 Building composeApp..."
./gradlew :composeApp:assembleDebug

# 3. 安装到设备
echo "📱 Installing..."
./gradlew :composeApp:installDebug

echo "✅ Done! Please check the app and tap the RN test button."
```

## 成功标志

- [x] 点击 RN 测试按钮能启动 RN 页面
- [x] 页面显示 "Native Module 状态: 已连接"
- [x] 能看到从 Native 传递的初始属性
- [x] 能调用 Native Module 方法（getMediaList, selectMedia 等）
- [x] Native 能发送事件到 RN

## 下一步

集成成功后，可以：
1. 开发具体的业务页面
2. 添加更多 Native Module
3. 优化 RN 与 Native 的通信机制
