# React Native Test App

这是一个用于测试 rn-host / rn-android 集成的 React Native 测试项目。

## 功能特性

- ✅ 测试 Native Module 调用（getMediaList, selectMedia, uploadMedia, deleteMedia）
- ✅ 测试 Native → RN 事件通信
- ✅ 测试 RN → Native 方法调用
- ✅ 多页面导航测试
- ✅ 完整的日志系统

## 快速开始

### 1. 安装依赖

```bash
cd rn-plugin/rn-test-app

# 使用 Yarn
yarn install

# 或使用 NPM
npm install
```

### 2. 开发模式（使用 Metro Server）

```bash
# 启动 Metro
cd rn-plugin/rn-test-app
npm start

# 在 Android Studio 中运行 rn-plugin:rn-host:assembleDebug
# 或使用 react-native run-android（需要配置原生项目）
```

### 3. 打包 Bundle 到 AAR

```bash
# 1. 构建 Release Bundle
cd rn-plugin/rn-test-app
npm run bundle:android

# 2. 复制 bundle 到 rn-host
npm run copy:assets

# 3. 构建 rn-host AAR
cd ../..
./gradlew :rn-plugin:rn-host:publishAar

# 4. 现在 rn-android 会自动使用新的 AAR
```

## 项目结构

```
rn-test-app/
├── src/
│   ├── components/         # 通用组件
│   │   ├── Button.js
│   │   ├── Card.js
│   │   └── index.js
│   ├── modules/            # Native Module 封装
│   │   └── MediaManager.js
│   ├── screens/            # 页面
│   │   ├── HomeScreen.js           # 首页
│   │   ├── MediaGalleryScreen.js   # 媒体库测试
│   │   └── NativeModuleTestScreen.js # 原生模块测试
│   ├── utils/              # 工具
│   │   ├── Colors.js
│   │   └── Styles.js
│   └── App.js              # 主应用
├── scripts/                # 构建脚本
│   ├── copy-assets.js
│   └── setup.sh
├── index.js                # 入口文件
├── package.json
└── metro.config.js
```

## Native Module API

### MediaManager

```javascript
import MediaManager from './modules/MediaManager';

// 获取媒体列表
const mediaList = await MediaManager.getMediaList();

// 选择媒体
await MediaManager.selectMedia({ 
  mediaType: 'image', // 'image' | 'video' | 'all'
  maxCount: 5 
});

// 上传媒体
await MediaManager.uploadMedia(mediaId, { quality: 'high' });

// 删除媒体
await MediaManager.deleteMedia(mediaId);

// 监听事件
MediaManager.onMediaSelected((data) => {
  console.log('Media selected:', data);
});

MediaManager.onMediaUploaded((data) => {
  console.log('Media uploaded:', data);
});
```

## 测试页面

### 1. 首页 (HomeScreen)

- 显示 Native Module 连接状态
- 显示从 Native 传递的初始属性
- 导航到各个测试页面

### 2. 媒体库测试 (MediaGalleryScreen)

- 测试 `getMediaList()` - 获取媒体列表
- 测试 `selectMedia()` - 选择媒体
- 测试 `uploadMedia()` - 上传媒体
- 显示媒体列表和选中状态

### 3. 原生模块测试 (NativeModuleTestScreen)

- API 测试按钮组
- 测试数据输入
- 完整的调用日志
- 批量测试功能

## 与 rn-host 对接

### Bundle 加载流程

```
RN 项目打包
    ↓
npm run bundle:android
    ↓
生成 index.android.bundle
    ↓
npm run copy:assets
    ↓
复制到 rn-host/src/main/assets/
    ↓
./gradlew :rn-plugin:rn-host:publishAar
    ↓
生成 rn-plugin/output/rn-host.aar
    ↓
rn-android 使用 AAR
```

### 开发模式 vs 生产模式

**开发模式**（推荐开发时使用）：
- 使用 Metro Server 实时加载 JS
- 需要配置网络访问权限
- 支持热更新

**生产模式**（发布时使用）：
- 使用打包好的 bundle
- 内嵌在 AAR 中
- 无需网络

## 常见问题

### Q: Metro Server 连接失败？

检查以下几点：
1. 确保手机和电脑在同一网络
2. 检查防火墙设置
3. 尝试指定主机：`adb reverse tcp:8081 tcp:8081`
4. 使用 IP 地址访问：`npx react-native start --host 0.0.0.0`

### Q: Native Module 返回 null？

1. 确认 rn-host 已正确构建
2. 确认 rn-android 依赖了正确的 AAR
3. 检查 `MediaManagerModule` 是否在 Package 中注册
4. 重新构建并发布 AAR

### Q: 如何添加新的 Native Module？

1. 在 rn-android 中创建 Module 类
2. 在 Package 中注册
3. 在 RN 中创建对应的封装模块
4. 重新构建 AAR
5. 在 RN 中使用新模块

## 调试技巧

### 查看 Native 日志

```bash
# Android
adb logcat -s ReactNative:V ReactNativeJS:V MediaManager:V
```

### 调试 Native Module

```javascript
// 检查模块是否存在
console.log('MediaManager:', MediaManager);
console.log('NativeModules:', NativeModules);
```

### 网络请求调试

```bash
# 查看网络请求
adb shell setprop debug.firebase.analytics.app <package_name>
```

## 版本信息

- React Native: 0.82.1
- React: 18.3.1
- Target Android SDK: 36
- Min Android SDK: 24

## 相关链接

- [rn-host](../rn-host/) - React Native 宿主模块
- [rn-android](../rn-android/) - Android 集成模块
- [集成指南](../INTEGRATION_GUIDE.md) - 完整集成文档
