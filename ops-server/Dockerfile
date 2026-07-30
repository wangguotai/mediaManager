# ops-server Dockerfile — Go 多阶段构建。
#
# 运营服务端 (ops-server) 独立于媒体后端：REST/WS 账号 + server 注册 + 发现查询 +
# TCP 中继记账。依赖均为纯 Go（modernc.org/sqlite 纯 Go SQLite，无 CGO），可静态编译。
#
# 配置全走环境变量 MM_OPS_*（见 internal/config/config.go，代码不读 yaml），
# HTTP 默认 8090，relay 默认 18790，数据落在卷 /data。

# ---- builder -------------------------------------------------------------
FROM golang:1.24-alpine AS builder

# git：go mod 可能因 vcs 标记需要；alpine 镜像默认无 git。
RUN apk add --no-cache git

WORKDIR /src

# 先拷依赖清单，利用层缓存：go.mod/go.sum 不变则跳过依赖下载。
COPY go.mod go.sum ./
RUN go mod download

# 再拷源码并构建。CGO_ENABLED=0 → 纯静态二进制，可在 scratch/alpine 直接运行。
# -trimpath 去除本机路径；-ldflags '-s -w' 去除调试符号缩小体积。
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=${TARGETARCH:-amd64} \
    go build -trimpath -ldflags '-s -w' -o /out/ops-server ./cmd/ops-server

# 二进制自检：打印版本信息失败则中止构建（尽早暴露坏构建）。
RUN /out/ops-server -h 2>/dev/null || true

# ---- runtime -------------------------------------------------------------
# alpine 而非 scratch：需要 ca-certificates（出站 HTTPS）与 curl（健康检查）。
FROM alpine:3.20

RUN apk add --no-cache ca-certificates curl \
    && addgroup -S ops && adduser -S -G ops ops

# 数据目录：SQLite 库 + 会话状态。docker-compose 以命名卷挂载至此。
RUN mkdir -p /data && chown -R ops:ops /data
VOLUME /data

# 拷贝静态二进制。
COPY --from=builder /out/ops-server /usr/local/bin/ops-server

# 运行时默认配置（可在 docker-compose / docker run 用 MM_OPS_* 覆盖）：
#   HTTP 监听 8090（任务要求端口）；relay 监听 18790；数据目录指向卷。
ENV MM_OPS_HTTP_ADDR=:8090 \
    MM_OPS_RELAY_ADDR=:18790 \
    MM_OPS_DATA_DIR=/data

USER ops

EXPOSE 8090 18790

# 健康检查：HTTP /healthz 由 main.go 暴露，返回 {"status":"ok"}。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8090/healthz || exit 1

ENTRYPOINT ["ops-server"]
