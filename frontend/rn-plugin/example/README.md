# React Native 集成示例

## 快速开始

### 1. 在 Application 中初始化

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 RN Plugin
        RNPluginManager.getInstance().initialize(this)
    }
}
```

### 2. 在 Compose 中嵌入 RN 视图

```kotlin
@Composable
fun MyScreen() {
    ReactNativeContainer(
        moduleName = "MyReactApp",
        initialProps = mapOf(
            "title" to "Hello from Native",
            "data" to someData
        )
    )
}
```

### 3. 从 Native 发送事件到 RN

```kotlin
// 发送事件
RNPluginManager.getInstance().sendEvent(
    "NativeEvent",
    mapOf("data" to "value")
)
```

### 4. 在 RN 中接收事件

```javascript
import { NativeEventEmitter, NativeModules } from 'react-native';

const { MediaManager } = NativeModules;
const eventEmitter = new NativeEventEmitter(MediaManager);

useEffect(() => {
    const subscription = eventEmitter.addListener('NativeEvent', (data) => {
        console.log('Received from native:', data);
    });
    
    return () => subscription.remove();
}, []);
```

### 5. 在 RN 中调用 Native 方法

```javascript
import { NativeModules } from 'react-native';

const { MediaManager } = NativeModules;

// 调用原生方法
const mediaList = await MediaManager.getMediaList();

// 选择媒体
await MediaManager.selectMedia({
    mediaType: 'image',
    maxCount: 5
});

// 上传媒体
await MediaManager.uploadMedia(mediaId, {
    quality: 'high'
});

// 删除媒体
await MediaManager.deleteMedia(mediaId);
```

## React Native 侧准备

### 1. 创建 RN 项目

```bash
npx @react-native-community/cli@latest init MediaManagerRN
```

### 2. 注册主组件

```javascript
// index.js
import { AppRegistry } from 'react-native';
import App from './App';

AppRegistry.registerComponent('MediaGallery', () => App);
```

### 3. 打包 bundle

```bash
# Android
npx react-native bundle \
    --platform android \
    --dev false \
    --entry-file index.js \
    --bundle-output android/app/src/main/assets/index.android.bundle \
    --assets-dest android/app/src/main/res/
```

## 注意事项

1. **Bundle 文件**: 需要将 `index.android.bundle` 放入 Android 项目的 assets 目录
2. **Metro Server**: 开发模式下需要启动 Metro: `npx react-native start`
3. **ProGuard**: 发布版本需要保留 RN 相关类
4. **内存管理**: 页面退出时记得清理 ViewManager

## 自定义 Native Module

参考 `rn-android/src/main/java/com/wgt/rn_android/bridge/MediaManagerModule.kt`

1. 创建 Module 类继承 `ReactContextBaseJavaModule`
2. 在 `MediaManagerPackage` 中注册
3. 在 `ReactHostManager` 中添加 Package
