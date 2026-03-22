#!/bin/bash

# 提取 React Native SO 库脚本
# 从 Gradle 缓存中提取 RN 的 SO 文件

set -e

RN_VERSION="0.82.1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNI_LIBS_DIR="${SCRIPT_DIR}/../rn-host/src/main/jniLibs"
GRADLE_CACHE="${HOME}/.gradle/caches/modules-2/files-2.1"

echo "=== 提取 React Native SO 库 ==="
echo "RN 版本: ${RN_VERSION}"
echo "目标目录: ${JNI_LIBS_DIR}"
echo ""

# 清理并创建目录
rm -rf "${JNI_LIBS_DIR}"
mkdir -p "${JNI_LIBS_DIR}"/{arm64-v8a,armeabi-v7a,x86,x86_64}

# 提取 react-android AAR
echo "📦 提取 react-android..."
REACT_AAR=$(find "${GRADLE_CACHE}" -name "react-android-${RN_VERSION}-release.aar" | head -1)

if [ -z "$REACT_AAR" ]; then
    echo "❌ 未找到 react-android AAR"
    echo "请先运行: ./gradlew :rn-plugin:rn-host:assembleRelease"
    exit 1
fi

echo "   找到: ${REACT_AAR}"

# 解压各架构的 SO 文件
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    echo "   📁 ${arch}/"
    unzip -j -q "${REACT_AAR}" "jni/${arch}/*.so" -d "${JNI_LIBS_DIR}/${arch}/" || true
done

# 检查 hermes-android
HERMES_AAR=$(find "${GRADLE_CACHE}" -name "hermes-android-${RN_VERSION}-release.aar" | head -1)
if [ -n "$HERMES_AAR" ]; then
    echo "📦 提取 hermes-android..."
    for arch in arm64-v8a armeabi-v7a x86 x86_64; do
        unzip -j -q "${HERMES_AAR}" "jni/${arch}/*.so" -d "${JNI_LIBS_DIR}/${arch}/" || true
    done
fi

# 统计结果
echo ""
echo "✅ SO 库提取完成"
echo ""
echo "📊 统计:"
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    count=$(find "${JNI_LIBS_DIR}/${arch}" -name "*.so" | wc -l)
    echo "   ${arch}: ${count} 个文件"
done

echo ""
echo "📋 文件列表:"
find "${JNI_LIBS_DIR}" -name "*.so" | while read f; do
    arch=$(basename "$(dirname "$f")")
    name=$(basename "$f")
    size=$(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null || echo "0")
    printf "   %-20s %-30s %8d bytes\n" "${arch}/" "${name}" "${size}"
done
