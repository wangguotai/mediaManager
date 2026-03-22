# Assets 目录

这个目录用于存放 React Native Bundle 文件。

## 使用方法

### 方式 1：自动复制（推荐）

```bash
# 在 rn-test-app 目录下
cd rn-plugin/rn-test-app
npm run bundle:android
npm run copy:assets
```

### 方式 2：手动复制

```bash
# 构建 bundle
cd rn-plugin/rn-test-app
npx react-native bundle \
  --platform android \
  --dev false \
  --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res/

# 手动复制到 rn-host
cp android/app/src/main/assets/index.android.bundle \
   ../rn-host/src/main/assets/
```

### 方式 3：开发模式（使用 Metro Server）

开发模式下不需要复制 bundle，RN 会自动从 Metro Server 加载 JS 代码。

确保：
1. 启动 Metro：`npm start`
2. 设备和电脑在同一网络
3. 配置 `BuildConfig.DEBUG = true`

## 文件说明

| 文件 | 说明 |
|------|------|
| `index.android.bundle` | RN 打包后的 JS 代码 |
| `drawable-*/*` | 图片资源（RN 打包时生成） |

## 注意事项

1. 发布生产版本前必须确保 bundle 已复制到此目录
2. 每次修改 RN 代码后需要重新构建 bundle
3. 构建 AAR 前确认 bundle 文件存在且是最新版本
