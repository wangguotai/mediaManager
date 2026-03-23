# 构建 RN AAR 指南

## 方式一：使用 MyApp 项目构建（推荐）

### 步骤 1：进入 MyApp Android 目录

```bash
cd /Volumes/ext/projects/media-manager/frontend/rn-plugin/MyApp/android
```

### 步骤 2：构建 rn-aar 模块

```bash
./gradlew :rn-aar:assembleRelease
```

### 步骤 3：AAR 会自动复制到 rn-host/libs

构建完成后，AAR 文件会自动复制到：
- `rn-plugin/rn-host/libs/rn-aar-release.aar`

### 步骤 4：构建主项目

```bash
cd /Volumes/ext/projects/media-manager/frontend
./gradlew :rn-plugin:rn-host:publishAar :composeApp:assembleDebug :composeApp:installDebug
```

---

## 方式二：手动复制 AAR

如果自动复制失败，可以手动复制：

```bash
# 从 MyApp 构建目录复制
cp /Volumes/ext/projects/media-manager/frontend/rn-plugin/MyApp/android/rn-aar/build/outputs/aar/rn-aar-release.aar \
   /Volumes/ext/projects/media-manager/frontend/rn-plugin/rn-host/libs/
```

---

## 方式三：使用 Gradle 缓存（备用）

如果 MyApp 构建失败，可以直接使用 Maven 依赖：

rn-host 会自动检测 AAR 是否存在，如果不存在会回退到 Maven 依赖。

---

## 构建脚本

使用提供的脚本一键构建：

```bash
cd /Volumes/ext/projects/media-manager/frontend/rn-plugin
./scripts/build-rn-aar.sh
```

## 验证 AAR

检查 AAR 是否正确生成：

```bash
ls -lh rn-host/libs/
```

应该看到：
- `rn-aar-release.aar` (约 100MB+)
