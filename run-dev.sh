#!/usr/bin/env bash
# ============================================================
# Media Manager 本地开发一键启动脚本
#
# 启动 ops-server(:8090) + backend(:8080)，后台运行。
# 前端 Android APK 需另建：cd frontend && ./gradlew :composeApp:assembleDebug
#
# 用法：
#   ./run-dev.sh          # 启动后端服务
#   ./run-dev.sh stop     # 停止后端服务
#   ./run-dev.sh status   # 查看运行状态
# ============================================================
set -euo pipefail

REPO=$(cd "$(dirname "$0")" && pwd)
PID_DIR="$REPO/.run"

mkdir -p "$PID_DIR"

start_svc() {
    local name=$1 dir=$2 cmd=$3 port=$4
    local pidfile="$PID_DIR/$name.pid"

    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "$name 已在运行 (PID $(cat "$pidfile"))"
        return 0
    fi

    echo "启动 $name (port $port)..."
    cd "$dir"
    nohup $cmd > "$PID_DIR/$name.log" 2>&1 &
    echo $! > "$pidfile"
    cd "$REPO"

    # 等待端口就绪
    for i in $(seq 1 15); do
        if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then
            echo "$name 就绪 (PID $(cat "$pidfile"))"
            return 0
        fi
        sleep 1
    done
    echo "警告: $name 端口 $port 未就绪，检查 $PID_DIR/$name.log"
    return 1
}

stop_svc() {
    local name=$1
    local pidfile="$PID_DIR/$name.pid"
    if [ -f "$pidfile" ]; then
        local pid=$(cat "$pidfile")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid"
            echo "$name 已停止 (PID $pid)"
        fi
        rm -f "$pidfile"
    else
        echo "$name 未在运行"
    fi
}

case "${1:-start}" in
    start)
        start_svc ops-server "$REPO/ops-server" "go run ./cmd/ops-server" 8090
        start_svc backend "$REPO/backend" "go run ./cmd/server" 8080
        echo ""
        echo "=== 服务已启动 ==="
        echo "后端:   http://127.0.0.1:8080  (healthz: curl http://127.0.0.1:8080/healthz)"
        echo "运维:   http://127.0.0.1:8090"
        echo ""
        echo "首次使用: POST http://127.0.0.1:8080/api/auth/register (allow_signup=first)"
        echo "日志:   .run/*.log"
        echo "停止:   ./run-dev.sh stop"
        ;;
    stop)
        stop_svc backend
        stop_svc ops-server
        ;;
    status)
        for name in ops-server backend; do
            local pidfile="$PID_DIR/$name.pid"
            if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
                echo "$name: 运行中 (PID $(cat "$pidfile"))"
            else
                echo "$name: 未运行"
            fi
        done
        ;;
    *)
        echo "用法: $0 {start|stop|status}"
        exit 1
        ;;
esac
