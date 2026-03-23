# React Native AAR 构建与集成

## 项目结构

```
rn-plugin/
├── MyApp/                    # 标准 RN 项目（可独立运行）
│   └── android/
│       ├── app/              # 官方应用模块（可运行）
│       └── rn-aar/           # AAR 打包模块（新增）
├── rn-host/                  # 依赖 MyApp 生成的 AAR
│   └── libs/                 # AAR 存放目录
└── rn-android/               # 业务封装模块
```

## 构建步骤

### 1. 进入 MyApp 目录

```bash
cd /Volumes/ext/projects/media-manager/frontend/rn-plugin/MyApp/android
```

### 2. 构建 RN AAR

```bash
# 这会构建包含 RN 所有依赖的 AAR
./gradlew :rn-aar:assembleRelease
```

**注意**：首次构建可能需要 5-10 分钟，需要下载依赖。

### 3. 复制 AAR 到 rn-host

构建完成后，手动复制：

```bash
cp /Volumes/ext/projects/media-manager/frontend/rn-plugin/MyApp/android/rn-aar/build/outputs/aar/rn-aar-release.aar \
   /Volumes/ext/projects/media-manager/frontend/rn-plugin/rn-host/libs/
```

或者使用自动复制（已配置在 build.gradle 中）：

```bash
./gradlew :rn-aar:publishAarToRnHost
```

### 4. 构建主项目

```bash
cd /Volumes/ext/projects/media-manager/frontend
./gradlew :rn-plugin:rn-host:publishAar :composeApp:assembleDebug :composeApp:installDebug
```

## 验证

检查 AAR 是否存在：

```bash
ls -lh rn-plugin/rn-host/libs/
# 应该看到 rn-aar-release.aar (约 100MB+)
```

## 常见问题

### 1. 构建超时

RN 首次构建需要下载大量依赖，请确保网络畅通并耐心等待。

### 2. AAR 复制失败

手动复制命令：

```bash
cp rn-plugin/MyApp/android/rn-aar/build/outputs/aar/*.aar rn-plugin/rn-host/libs/
```

### 3. 依赖冲突

如果 rn-host 中已存在 AAR，会自动使用，不会下载 Maven 依赖。

## 技术说明

- **MyApp**: 标准 RN 项目，使用官方 `npx @react-native-community/cli` 创建
- **rn-aar 模块**: 在 MyApp 中新增，用于打包 RN 依赖为 AAR
- **rn-host**: 依赖 rn-aar 生成的 AAR，提供 ReactHostManager 等封装
- **依赖传递**: rn-aar 使用 `api` 配置，RN 依赖会传递给 rn-host
