#!/bin/bash

# 构建 libreact_featureflagsjni.so 脚本
# 从 React Native 源码构建

set -e

RN_VERSION="0.82.1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RN_SOURCE_DIR="${SCRIPT_DIR}/../rn-source/build/react-native-${RN_VERSION}"
OUTPUT_DIR="${SCRIPT_DIR}/../rn-host/src/main/jniLibs"

echo "=== 构建 libreact_featureflagsjni.so ==="
echo "RN 版本: ${RN_VERSION}"

# 下载 RN 源码（如果不存在）
if [ ! -d "$RN_SOURCE_DIR" ]; then
    echo "📥 下载 React Native 源码..."
    mkdir -p "$(dirname "$RN_SOURCE_DIR")"
    cd "$(dirname "$RN_SOURCE_DIR")"
    
    # 下载并解压
    curl -L "https://github.com/facebook/react-native/archive/refs/tags/v${RN_VERSION}.tar.gz" -o "react-native-${RN_VERSION}.tar.gz"
    tar -xzf "react-native-${RN_VERSION}.tar.gz"
    rm "react-native-${RN_VERSION}.tar.gz"
fi

echo "📂 RN 源码位置: ${RN_SOURCE_DIR}"

# 查找 featureflags 源码目录
FEATURE_FLAGS_DIR="${RN_SOURCE_DIR}/packages/react-native/ReactAndroid/src/main/jni/react/featureflags"

if [ ! -d "$FEATURE_FLAGS_DIR" ]; then
    echo "❌ 未找到 featureflags 源码目录"
    exit 1
fi

echo "✅ 找到 featureflags 源码: ${FEATURE_FLAGS_DIR}"

# 列出目录内容
ls -la "$FEATURE_FLAGS_DIR"

# 尝试找到 CMakeLists.txt 或 Android.mk
find "$RN_SOURCE_DIR/packages/react-native/ReactAndroid" -name "CMakeLists.txt" | grep -i feature | head -5

echo ""
echo "📋 注意: 从 RN 0.82 源码构建 SO 需要完整的 Android NDK 和构建环境"
echo "建议直接在 gradle 依赖中添加 featureflags 模块"
