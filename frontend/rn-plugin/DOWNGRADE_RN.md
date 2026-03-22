# 降级 React Native 到 0.72.x (稳定版本)

RN 0.82 使用新架构，需要 `libreact_featureflagsjni.so` 等特殊 SO 库。
如果难以解决，建议降级到 0.72.x 稳定版本。

## 降级步骤

### 1. 修改版本号

`gradle/libs.versions.toml`:

```toml
[versions]
react-native = "0.72.15"
```

### 2. 修改依赖引用

旧版本使用不同的 Maven 坐标：

`rn-plugin/rn-host/build.gradle.kts`:

```kotlin
dependencies {
    // RN 0.72.x 使用 react-native-android
    implementation("com.facebook.react:react-native:0.72.15")
    implementation("com.facebook.react:hermes-engine:0.72.15")
    
    // 其他依赖...
}
```

### 3. 更新代码

RN 0.72.x 的 API 有所不同：

```kotlin
// 0.72.x 初始化方式
SoLoader.init(application, false)

// 不需要手动配置 Feature Flags
```

### 4. 重新提取 SO 库

```bash
# 清理 Gradle 缓存
rm -rf ~/.gradle/caches/modules-2/files-2.1/com.facebook.react

# 重新构建
./gradlew :rn-plugin:rn-host:assembleRelease

# 提取 SO
./rn-plugin/scripts/extract-so.sh
```

## 当前方案 (RN 0.82)

如果选择继续使用 RN 0.82，需要解决 `libreact_featureflagsjni.so` 问题：

### 方案 A: 从源码编译 RN

```bash
git clone https://github.com/facebook/react-native.git
cd react-native
./gradlew :ReactAndroid:assembleRelease
```

### 方案 B: 查找 Feature Flags AAR

```bash
# 搜索所有可能的包名
./gradlew dependencies | grep featureflags
```

### 方案 C: 创建空 SO 占位

如果该库只是可选功能，可以创建空 SO 避免崩溃：

```bash
# 创建空库 (临时方案)
echo "" | gcc -shared -o libreact_featureflagsjni.so -xc -
```

## 推荐方案

对于生产环境，建议：**降级到 RN 0.72.x**

原因：
1. 0.72.x 是长期支持版本，文档完善
2. 不需要处理新架构的兼容性问题
3. 社区支持更好，第三方库兼容性高
4. 构建流程更简单

## 快速降级命令

```bash
# 1. 修改版本
sed -i 's/react-native = "0.82.1"/react-native = "0.72.15"/' gradle/libs.versions.toml

# 2. 清理并重建
./gradlew clean :rn-plugin:rn-host:publishAar :composeApp:installDebug
```
