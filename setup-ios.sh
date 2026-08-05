#!/usr/bin/env bash
# ============================================================
# Media Manager iOS 构建环境初始化脚本
#
# 解决 fresh clone / worktree 缺少 rn-sdk-test-link symlink 导致
# pod install 失败的问题。脚本幂等，可重复运行。
#
# 用法：
#   ./setup-ios.sh            # 检查环境 + 创建 symlink + pod install
#   ./setup-ios.sh skip-pods  # 仅创建 symlink，跳过 pod install
#
# 前置条件：
#   - Xcode 16.2+ (含 iOS 18.2 SDK)
#   - CocoaPods (sudo gem install cocoapods 或 brew install cocoapods)
#   - Node.js (rn_test monorepo 依赖)
#   - 同级目录存在 rn_test 项目 (../rn_test)
# ============================================================
set -euo pipefail

REPO=$(cd "$(dirname "$0")" && pwd)
RN_TEST_SRC="${RN_TEST_SRC:-$HOME/projects/rn_test}"
SYMLINK="$REPO/rn-sdk-test-link"
IOS_DIR="$REPO/frontend/iosApp"

# ---------- 颜色输出 ----------
info()  { printf "\033[1;34m[i]\033[0m %s\n" "$*"; }
ok()    { printf "\033[1;32m[✓]\033[0m %s\n" "$*"; }
warn()  { printf "\033[1;33m[!]\033[0m %s\n" "$*"; }
fail()  { printf "\033[1;31m[✗]\033[0m %s\n" "$*" >&2; exit 1; }

# ---------- 1. 环境检查 ----------
info "检查构建环境..."

command -v xcodebuild >/dev/null 2>&1 || fail "未找到 xcodebuild，请安装 Xcode (App Store)"
command -v pod        >/dev/null 2>&1 || fail "未找到 pod，请安装 CocoaPods: sudo gem install cocoapods"
command -v node       >/dev/null 2>&1 || fail "未找到 node，请安装 Node.js"

XCODE_VER=$(xcodebuild -version 2>/dev/null | head -1 | awk '{print $2}')
pod --version >/dev/null 2>&1 && POD_VER=$(pod --version) || POD_VER="?"
node --version >/dev/null 2>&1 && NODE_VER=$(node --version) || NODE_VER="?"

ok "Xcode ${XCODE_VER} / CocoaPods ${POD_VER} / Node ${NODE_VER}"

# ---------- 2. rn_test 项目检查 ----------
if [ ! -d "$RN_TEST_SRC" ]; then
    fail "rn_test 项目不存在: $RN_TEST_SRC
请确认 rn_test monorepo 已 clone 到该路径，或通过 RN_TEST_SRC 环境变量指定：
    RN_TEST_SRC=/path/to/rn_test ./setup-ios.sh"
fi
[ -d "$RN_TEST_SRC/packages/react-native" ] || fail "$RN_TEST_SRC 缺少 packages/react-native 目录，不是有效的 rn_test monorepo"
ok "rn_test 项目: $RN_TEST_SRC"

# ---------- 3. 创建 symlink (幂等) ----------
if [ -L "$SYMLINK" ]; then
    CURRENT=$(readlink "$SYMLINK")
    if [ "$CURRENT" = "$RN_TEST_SRC" ]; then
        ok "symlink 已存在且正确: rn-sdk-test-link -> $RN_TEST_SRC"
    else
        warn "symlink 指向 $CURRENT，更新为 $RN_TEST_SRC"
        rm "$SYMLINK"
        ln -s "$RN_TEST_SRC" "$SYMLINK"
        ok "symlink 已更新"
    fi
elif [ -e "$SYMLINK" ]; then
    fail "$SYMLINK 已存在但不是 symlink，请手动检查后删除"
else
    ln -s "$RN_TEST_SRC" "$SYMLINK"
    ok "symlink 已创建: rn-sdk-test-link -> $RN_TEST_SRC"
fi

# ---------- 4. pod install ----------
if [ "${1:-}" = "skip-pods" ]; then
    info "跳过 pod install (skip-pods)"
else
    cd "$IOS_DIR"
    if [ -d "Pods" ]; then
        info "Pods 目录已存在，如需刷新请删除后重跑: rm -rf frontend/iosApp/Pods frontend/iosApp/Podfile.lock"
        ok "pod install 跳过 (Pods 已存在)"
    else
        info "执行 pod install (可能需要数分钟)..."
        # CC/CXX 可能由 shell 环境(如 conda/nvm)注入, 导致 RN 编译用错 clang
        unset CC CXX 2>/dev/null || true
        pod install
        ok "pod install 完成"
    fi
fi

# ---------- 5. 构建提示 ----------
cat <<EOF

$(ok "iOS 环境初始化完成")

接下来构建 iOS App (模拟器 Debug):

  cd frontend/iosApp
  unset CC CXX
  xcodebuild build \\
    -workspace iosApp.xcworkspace \\
    -scheme iosApp \\
    -sdk iphonesimulator \\
    -configuration Debug \\
    CODE_SIGNING_ALLOWED=NO

或在 Xcode 中打开 frontend/iosApp/iosApp.xcworkspace 直接构建运行。

常见问题:
  - CC/CXX 已设置导致编译错误 → 构建前执行: unset CC CXX
  - symlink 丢失 → 重跑 ./setup-ios.sh
  - pod 版本冲突 → rm -rf frontend/iosApp/Pods frontend/iosApp/Podfile.lock && ./setup-ios.sh
EOF
