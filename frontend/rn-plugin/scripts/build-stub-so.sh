#!/bin/bash

# 构建 libreact_featureflagsjni.so 占位库

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNI_LIBS_DIR="${SCRIPT_DIR}/../rn-host/src/main/jniLibs"
NDK_PATH="${ANDROID_NDK_HOME:-${HOME}/Library/Android/sdk/ndk/25.1.8937393}"

echo "=== 构建 libreact_featureflagsjni.so 占位库 ==="
echo "NDK 路径: ${NDK_PATH}"
echo "输出目录: ${JNI_LIBS_DIR}"

# 检查 NDK
if [ ! -d "$NDK_PATH" ]; then
    echo "❌ NDK 未找到: ${NDK_PATH}"
    echo "请设置 ANDROID_NDK_HOME 环境变量"
    exit 1
fi

# 创建输出目录
mkdir -p "${JNI_LIBS_DIR}"/{arm64-v8a,armeabi-v7a,x86,x86_64}

# 编译各架构
build_arch() {
    local arch=$1
    local toolchain_arch=$2
    local output_dir="${JNI_LIBS_DIR}/${arch}"
    
    echo ""
    echo "🔨 编译 ${arch}..."
    
    # 使用 NDK 的 clang 编译器
    "${NDK_PATH}/toolchains/llvm/prebuilt/darwin-x86_64/bin/${toolchain_arch}-linux-android21-clang" \
        -shared \
        -fPIC \
        -o "${output_dir}/libreact_featureflagsjni.so" \
        "${SCRIPT_DIR}/stub_featureflags.c" \
        2>/dev/null || echo "⚠️  ${arch} 编译失败，尝试其他方法"
}

# 为各架构编译
build_arch "arm64-v8a" "aarch64"
build_arch "armeabi-v7a" "armv7a"
build_arch "x86" "i686"
build_arch "x86_64" "x86_64"

echo ""
echo "✅ 构建完成"

# 显示结果
echo ""
echo "📋 输出文件:"
for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    file="${JNI_LIBS_DIR}/${arch}/libreact_featureflagsjni.so"
    if [ -f "$file" ]; then
        size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null || echo "0")
        echo "   ${arch}/libreact_featureflagsjni.so (${size} bytes)"
    else
        echo "   ${arch}/libreact_featureflagsjni.so (未生成)"
    fi
done
