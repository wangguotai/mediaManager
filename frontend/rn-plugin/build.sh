#!/bin/bash

# React Native Plugin 一键构建脚本
# 将 rn-test-app 打包到 rn-host AAR

set -e  # 遇到错误立即退出

echo "======================================="
echo "  React Native Plugin Build Script"
echo "======================================="
echo ""

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed"
    exit 1
fi
echo "✅ Node.js: $(node -v)"

# 步骤 1: 安装依赖
echo ""
echo "📦 Step 1: Installing dependencies..."
cd rn-test-app

if [ ! -d "node_modules" ]; then
    echo "   Running npm install..."
    npm install
else
    echo "   node_modules already exists, skipping..."
fi

# 步骤 2: 构建 Bundle
echo ""
echo "📦 Step 2: Building Android bundle..."
npm run bundle:android

if [ ! -f "android/app/src/main/assets/index.android.bundle" ]; then
    echo "❌ Bundle build failed!"
    exit 1
fi
echo "✅ Bundle built successfully"

# 步骤 3: 复制到 rn-host
echo ""
echo "📦 Step 3: Copying assets to rn-host..."
npm run copy:assets

if [ ! -f "../rn-host/src/main/assets/index.android.bundle" ]; then
    echo "❌ Copy assets failed!"
    exit 1
fi
echo "✅ Assets copied successfully"

# 步骤 4: 构建 AAR
echo ""
echo "📦 Step 4: Building rn-host AAR..."
cd "$SCRIPT_DIR/../.."
./gradlew :rn-plugin:rn-host:publishAar

# 检查 AAR 是否生成
if [ ! -f "rn-plugin/output/rn-host.aar" ]; then
    echo "❌ AAR build failed!"
    exit 1
fi

echo ""
echo "======================================="
echo "✅ Build completed successfully!"
echo "======================================="
echo ""
echo "Output: rn-plugin/output/rn-host.aar"
echo ""
echo "Next steps:"
echo "1. The AAR is ready to use in rn-android"
echo "2. Build your Android app: ./gradlew :composeApp:assembleDebug"
echo ""
