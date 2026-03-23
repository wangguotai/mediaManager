#!/bin/bash

# 构建 RN AAR 脚本
# 从 MyApp 项目构建 AAR 并复制到 rn-host

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MYAPP_DIR="${SCRIPT_DIR}/../MyApp/android"
RN_HOST_LIBS="${SCRIPT_DIR}/../rn-host/libs"

echo "=== 构建 React Native AAR ==="
echo ""

# 检查 MyApp 目录
if [ ! -d "$MYAPP_DIR" ]; then
    echo "❌ MyApp 目录不存在: ${MYAPP_DIR}"
    exit 1
fi

# 创建 rn-host/libs 目录
mkdir -p "${RN_HOST_LIBS}"

echo "📦 步骤 1: 构建 rn-aar 模块"
echo "   目录: ${MYAPP_DIR}"
cd "${MYAPP_DIR}"

# 构建 Release AAR
./gradlew :rn-aar:assembleRelease --console=plain

echo ""
echo "📋 步骤 2: 检查构建产物"

AAR_FILE="${MYAPP_DIR}/rn-aar/build/outputs/aar/rn-aar-release.aar"
if [ -f "$AAR_FILE" ]; then
    echo "   ✅ AAR 构建成功"
    ls -lh "$AAR_FILE"
    
    # 复制到 rn-host/libs
    echo ""
    echo "📋 步骤 3: 复制到 rn-host/libs"
    cp "$AAR_FILE" "${RN_HOST_LIBS}/rn-aar-release.aar"
    echo "   ✅ 复制完成: ${RN_HOST_LIBS}/rn-aar-release.aar"
else
    echo "   ❌ AAR 文件不存在: ${AAR_FILE}"
    exit 1
fi

echo ""
echo "📋 步骤 4: 构建主项目"
cd "${SCRIPT_DIR}/../.."
./gradlew :rn-plugin:rn-host:publishAar --console=plain

echo ""
echo "🎉 构建完成!"
echo ""
echo "下一步:"
echo "  ./gradlew :composeApp:assembleDebug :composeApp:installDebug"
