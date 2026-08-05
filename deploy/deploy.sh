#!/usr/bin/env bash
# ============================================================
# Media Manager 一键部署脚本
#
# 功能：
#   1. 检查依赖（docker + openssl）
#   2. 若 .env 不存在，从 .env.example 拷贝
#   3. 若 .env 中 MM_JWT_SECRET / MM_OPS_JWT_SECRET 为空，自动生成随机密钥并写回
#   4. docker compose up -d --build（首次构建镜像；后续直接启动）
#   5. 打印首管账号获取方式与健康检查命令
#
# 用法：
#   ./deploy.sh            # 构建并启动
#   ./deploy.sh --no-build # 仅启动（镜像已存在）
#   ./deploy.sh down       # 停止并移除容器（保留数据卷）
#   ./deploy.sh logs       # 查看日志
#   ./deploy.sh status     # 查看容器状态
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- 依赖检查 ----
check_deps() {
    if ! command -v docker &>/dev/null; then
        err "未找到 docker，请先安装 Docker: https://docs.docker.com/get-docker/"
        exit 1
    fi
    if ! docker compose version &>/dev/null; then
        err "docker compose 子命令不可用，请安装 Docker Compose V2（随 Docker Desktop / CLI 插件分发）。"
        exit 1
    fi
    if ! command -v openssl &>/dev/null; then
        warn "未找到 openssl，将无法自动生成 JWT 密钥——请手动在 .env 中填写 MM_JWT_SECRET / MM_OPS_JWT_SECRET。"
    fi
}

# ---- 初始化 .env ----
init_env() {
    if [[ ! -f .env ]]; then
        if [[ -f .env.example ]]; then
            cp .env.example .env
            ok "已从 .env.example 创建 .env"
        else
            err ".env.example 不存在，无法初始化。请检查 deploy/ 目录完整性。"
            exit 1
        fi
    else
        info ".env 已存在，跳过拷贝"
    fi
}

# ---- 生成随机 JWT 密钥并写回 .env ----
gen_secret() {
    local key="$1"
    local val
    # 读取当前值（去掉可能的引号与空格）
    val=$(grep -E "^${key}=" .env 2>/dev/null | head -1 | sed -E "s/^${key}=//; s/^\"//; s/\"$//" | tr -d '[:space:]')
    if [[ -n "$val" ]]; then
        info "${key} 已有值，跳过生成"
        return
    fi
    if ! command -v openssl &>/dev/null; then
        warn "openssl 不可用，无法为 ${key} 生成随机密钥——请在 .env 中手动填写。"
        return
    fi
    local new_secret
    new_secret=$(openssl rand -hex 32)
    # 写入或替换该行
    if grep -qE "^${key}=" .env; then
        # sed -i 兼容 macOS (BSD) 与 GNU：用临时文件
        sed -i.bak "s|^${key}=.*|${key}=${new_secret}|" .env && rm -f .env.bak
    else
        echo "${key}=${new_secret}" >> .env
    fi
    ok "已为 ${key} 生成随机密钥并写入 .env"
}

# ---- 构建并启动 ----
up() {
    local build_flag=""
    if [[ "${1:-}" != "--no-build" ]]; then
        build_flag="--build"
        info "首次部署将构建镜像（如已构建可加 --no-build 跳过）"
    fi

    info "启动 docker compose ..."
    docker compose up -d ${build_flag}
    ok "容器已启动"

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Media Manager 部署完成${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "后端 API:    http://localhost:${MM_PORT:-8080}"
    echo -e "健康检查:    curl http://localhost:${MM_PORT:-8080}/healthz"
    echo -e "运营面板:    http://localhost:${MM_OPS_HTTP_PORT:-8090}"
    echo ""
    echo -e "${YELLOW}首次创建管理员账号：${NC}"
    echo -e "  docker compose logs media-server | grep -A8 'INITIAL ADMIN'"
    echo -e "  → 复制 token，在 App 设置页填入后端地址并登录"
    echo -e "  → 登录后调用 POST /api/auth/change-password 修改密码"
    echo ""
    echo -e "常用命令："
    echo -e "  ./deploy.sh status   # 查看容器状态"
    echo -e "  ./deploy.sh logs     # 查看日志"
    echo -e "  ./deploy.sh down     # 停止（保留数据）"
    echo ""
}

# ---- 主逻辑 ----
main() {
    check_deps

    case "${1:-up}" in
        up)
            init_env
            gen_secret MM_JWT_SECRET
            gen_secret MM_OPS_JWT_SECRET
            # 重新加载 .env 以读取端口等变量用于提示
            set -a; source .env; set +a
            up "${2:-}"
            ;;
        --no-build)
            init_env
            gen_secret MM_JWT_SECRET
            gen_secret MM_OPS_JWT_SECRET
            set -a; source .env; set +a
            up --no-build
            ;;
        down)
            info "停止并移除容器（数据卷保留）..."
            docker compose down
            ok "已停止"
            ;;
        logs)
            docker compose logs -f --tail=100
            ;;
        status)
            docker compose ps
            ;;
        restart)
            docker compose restart
            ok "已重启"
            ;;
        *)
            err "未知命令: $1"
            echo "用法: $0 [up|--no-build|down|logs|status|restart]"
            exit 1
            ;;
    esac
}

main "$@"
