# React Native Android 集成指南

## 架构概览

```
rn-plugin/
├── rn-host/              # React Native 宿主模块 → 打包成 AAR
├── rn-android/           # Android 集成模块 → 依赖 AAR 并扩展功能
├── scripts/              # 自动化构建脚本
├── output/               # AAR 输出目录
└── example/              # 集成示例代码
```

## 集成流程

### 步骤 1: 构建 rn-host AAR

```bash
# 构建并输出 AAR 到 rn-plugin/output/
./gradlew :rn-plugin:rn-host:publishAar

# 或者直接构建 Release AAR
./gradlew :rn-plugin:rn-host:assembleRelease
```

AAR 文件将输出到: `rn-plugin/output/rn-host.aar`

### 步骤 2: 在 Application 中初始化

在 `composeApp` 或你的主模块的 Application 类中：

```kotlin
import com.wgt.rn_android.RNPluginManager

class MediaManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 React Native
        RNPluginManager.getInstance().initialize(this)
    }
}
```

### 步骤 3: 在 Compose 中嵌入 RN 视图

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.FrameLayout
import com.wgt.rn_android.RNPluginManager

@Composable
fun ReactNativeScreen(
    moduleName: String = "MediaGallery",
    initialProps: Map<String, Any>? = null
) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { container ->
            RNPluginManager.getInstance().loadReactModule(
                container = container,
                moduleName = moduleName,
                initialProps = initialProps
            )
        }
    )
}
```

### 步骤 4: 在 RN 中调用原生方法

```javascript
import { NativeModules } from 'react-native';

const { MediaManager } = NativeModules;

// 获取媒体列表
const mediaList = await MediaManager.getMediaList();

// 选择媒体
await MediaManager.selectMedia({ mediaType: 'image', maxCount: 5 });

// 上传媒体
await MediaManager.uploadMedia(mediaId, { quality: 'high' });
```

## 模块依赖关系

```
composeApp (或其他使用模块)
    └── rn-plugin:rn-android
        └── rn-plugin:rn-host (AAR 依赖)
            └── com.facebook.react:react-android
```

## 开发模式 vs 发布模式

### 开发模式
- rn-android 自动检测 AAR 是否存在
- 如果不存在，使用 project 依赖（便于同时开发 rn-host 和 rn-android）

### 发布模式
- 运行 `./gradlew :rn-plugin:rn-host:publishAar` 生成 AAR
- rn-android 自动使用 `rn-plugin/output/rn-host.aar`

## 文件说明

| 文件 | 说明 |
|------|------|
| `rn-host/ReactHostManager.kt` | RN 环境初始化和管理 |
| `rn-host/ReactViewManager.kt` | RN 视图嵌入管理 |
| `rn-host/ReactExtensions.kt` | 数据类型转换扩展 |
| `rn-android/RNPluginManager.kt` | 对外的统一入口 |
| `rn-android/bridge/MediaManagerModule.kt` | 媒体相关的 Native Module |
| `scripts/publish-aar.gradle.kts` | AAR 构建和发布脚本 |

## 常见问题

### Q: 如何添加自定义 Native Module?

在 `rn-android/src/main/java/com/wgt/rn_android/bridge/` 下：

1. 创建 Module 类继承 `ReactContextBaseJavaModule`
2. 在 `MediaManagerPackage` 中注册
3. 重新运行 `publishAar` 任务

### Q: 如何更新 React Native 版本?

修改 `gradle/libs.versions.toml`：

```toml
[versions]
react-native = "0.82.1"  # 修改为你需要的版本
```

### Q: Metro Server 无法连接?

确保：
1. 启动 Metro: `npx react-native start`
2. 设备/模拟器可以访问开发机
3. 检查 `BuildConfig.DEBUG` 是否为 true

### Q: AAR 构建失败?

检查：
1. 确保 Android SDK 路径正确配置
2. 运行 `./gradlew clean` 后重试
3. 检查 `local.properties` 中的 SDK 路径

## 下一步

1. 创建 React Native 项目并开发业务代码
2. 打包 RN bundle 到 Android assets
3. 在 Compose 中使用 `ReactNativeScreen` 组件
4. 根据需要扩展 `MediaManagerModule`
