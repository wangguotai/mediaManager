# syntax=docker/dockerfile:1
#
# Media Manager 后端镜像 —— 两阶段构建。
#
# build context 为 backend/ 目录（即 docker-compose.yml 的 context: .）。
# builder: 用官方 golang 镜像编译纯 Go 静态二进制（modernc.org/sqlite 免 CGO）。
# runtime: debian-slim + ffmpeg/ffprobe（视频缩略图抽帧与视频信息解析依赖）+ curl（健康检查），
#          非 root 运行，数据落 /app/data（建议挂载卷持久化）。
#
# 配置约定：容器内默认不内置 config.yaml——config.Load 缺失文件即回退默认值，
# 实际配置通过 MM_* 环境变量（docker-compose 的 environment 段）注入，覆盖链：
#   代码默认 < config.yaml < MM_* 环境变量。
# 仍随镜像附 config.example.yaml 供参考/手动拷贝。

# ---------- builder ----------
FROM golang:1.24-bookworm AS builder

WORKDIR /src

# 先拷依赖清单，利用层缓存：go.mod/go.sum 不变则跳过下载。
COPY go.mod go.sum ./
# gen/ 是预生成的 protobuf 代码，编译必需但无需 protoc；直接随源码拷入。
COPY gen ./gen
# 拷源码（含 cmd / internal）。
COPY cmd ./cmd
COPY internal ./internal

# modernc.org/sqlite 为纯 Go，可关闭 CGO 静态构建；GOFLAGS=-mod=mod 兜底 go.sum 缺口。
RUN CGO_ENABLED=0 GOFLAGS=-mod=mod go build \
    -trimpath -ldflags="-s -w" \
    -o /out/media-server ./cmd/server

# ---------- runtime ----------
FROM debian:bookworm-slim AS runtime

# ffmpeg/ffprobe: 视频缩略图抽帧 + 视频信息解析的硬依赖（见接口契约"系统依赖"）。
# curl: HEALTHCHECK 探 /healthz。ca-certificates: 出站 HTTPS（如 OpenClaw 桥接）需要。
# tini: 作为 PID 1 正确转发信号，保证 SIGTERM 优雅退出。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        curl \
        ca-certificates \
        tini \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行：创建 app 用户与数据目录。
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/data \
    && chown -R app:app /app

WORKDIR /app

# 拷二进制与配置示例。
COPY --from=builder /out/media-server /app/media-server
COPY config.example.yaml /app/config.example.yaml

USER app

# REST gateway 端口（默认 8080，可由 MM_PORT 覆盖）。gRPC :50051 仅进程内部，不暴露。
EXPOSE 8080

# 数据卷挂载点：uploads / thumbnails / cloud-images / media.db / favorites.json 等均在其下。
VOLUME ["/app/data"]

# 用 tini 接管 PID 1。配置文件缺失时回退默认；实际配置靠 MM_* 环境变量注入。
ENTRYPOINT ["/usr/bin/tini", "--", "/app/media-server"]

# 健康检查：REST gateway /healthz 已豁免认证，7s 间隔，5 次失败才标 unhealthy。
# 用 shell 形式以便展开 MM_PORT（默认 8080）。
HEALTHCHECK --interval=7s --timeout=3s --start-period=5s --retries=5 \
    CMD curl -fsS http://127.0.0.1:${MM_PORT:-8080}/healthz || exit 1
