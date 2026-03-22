#!/bin/bash

# React Native Test App 设置脚本
# 用于初始化项目并安装依赖

echo "=== React Native Test App Setup ==="
echo ""

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js is not installed. Please install Node.js >= 18"
    exit 1
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "❌ Node.js version must be >= 18. Current version: $(node -v)"
    exit 1
fi

echo "✅ Node.js version: $(node -v)"

# 检查 Yarn/npm
if command -v yarn &> /dev/null; then
    echo "✅ Yarn version: $(yarn -v)"
    PKG_MANAGER="yarn"
else
    echo "✅ NPM version: $(npm -v)"
    PKG_MANAGER="npm"
fi

# 安装依赖
echo ""
echo "📦 Installing dependencies..."
cd "$(dirname "$0")/.."

if [ "$PKG_MANAGER" = "yarn" ]; then
    yarn install
else
    npm install
fi

# 检查安装结果
if [ $? -ne 0 ]; then
    echo "❌ Failed to install dependencies"
    exit 1
fi

echo ""
echo "✅ Dependencies installed successfully!"
echo ""
echo "📋 Available commands:"
echo "  $PKG_MANAGER start          # Start Metro bundler"
echo "  $PKG_MANAGER run bundle:android   # Build Android bundle"
echo "  $PKG_MANAGER run copy:assets      # Copy bundle to rn-host"
echo ""
echo "🚀 Quick start:"
echo "1. Start Metro: $PKG_MANAGER start"
echo "2. In another terminal, build bundle: $PKG_MANAGER run bundle:android"
echo "3. Copy assets: $PKG_MANAGER run copy:assets"
echo "4. Build AAR: cd ../.. && ./gradlew :rn-plugin:rn-host:publishAar"
