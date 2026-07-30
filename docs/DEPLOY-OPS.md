# 运营服务端部署指南（DEPLOY-OPS）

> 受众：运营方（提供公网中继与运营管理台的人）
> 对应组件：`ops-server/`（Go + SQLite + WebSocket，HTTP :8090 + TCP relay :18790）
> 配套 docs：`DEPLOY-SERVER.md`（自托管存储端）、`OPS-GUIDE.md`（运营管理员用法）、`DEPLOY-COMPOSE.md`（全栈一键）

本篇覆盖：ops-server 部署 → 运营账号创建与登录 → 服务端注册 → 流量看板解读 → 与 backend 组网。

---

## 一、10 分钟跑通

```bash
cd ops-server
cp .env.example .env
# 编辑 .env：设 MM_OPS_JWT_SECRET 与 MM_OPS_BOOTSTRAP_ADMIN
sed -i "s|^MM_OPS_JWT_SECRET=.*|MM_OPS_JWT_SECRET=$(openssl rand -hex 32)|" .env
sed -i "s|^# MM_OPS_BOOTSTRAP_ADMIN=.*|MM_OPS_BOOTSTRAP_ADMIN=admin:AdminStr0ng!8|" .env

docker compose up -d --build
docker compose logs -f ops-server
```

启动后：

- 运营管理台前端：`http://<host>:8090/admin/`（登录页）
- 健康检查：`GET http://<host>:8090/healthz` → `{"status":"ok"}`
- TCP 中继端口：`:18790`（供 backend 与客户端经中继互连）
- 数据卷：`ops-server-data` 挂载到容器 `/data`（SQLite 库 + 会话状态）

用 `admin / AdminStr0ng!8` 登录管理台，进入 dashboard。成功标志：`docker compose ps` 显示 `healthy`，`curl :8090/healthz` ok。

---

## 二、ops-server 部署详解

### 2.1 端口约定（与仓库一致）

| 端口 | 用途 |
|---|---|
| `8090` | HTTP：REST `/op/*` + admin `/admin/*`（含前端）+ `/healthz` |
| `18790` | TCP 中继（relay）：backend ↔ 客户端的 TURN 式转发 |

> 两个端口都需对外映射（Docker `ports: 8090:8090` + `18790:18790`）。relay 仅在可信网络/公网中继场景对外；纯内网可只映射 8090。

### 2.2 Docker 部署（推荐）

`ops-server/docker-compose.yml` 已自包含。关键配置走环境变量 `MM_OPS_*`（ops-server **不读 yaml**，全走环境变量，见 `internal/config/config.go`）。

```bash
cd ops-server
docker compose up -d --build
docker compose logs -f ops-server
docker compose down          # 停止，卷保留
docker compose down -v       # 连同 ops-server-data 卷一并删除（谨慎）
```

### 2.3 裸机部署

```bash
cd ops-server
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /usr/local/bin/ops-server ./cmd/ops-server
export MM_OPS_HTTP_ADDR=:8090
export MM_OPS_RELAY_ADDR=:18790
export MM_OPS_DATA_DIR=/var/lib/ops-server
export MM_OPS_JWT_SECRET=$(openssl rand -hex 32)
export MM_OPS_BOOTSTRAP_ADMIN=admin:AdminStr0ng!8
/usr/local/bin/ops-server
```

systemd unit（参考 `DEPLOY-SERVER.md` §二的写法，替换二进制路径与环境变量即可）。

### 2.4 健康检查

容器 `HEALTHCHECK` 已配 `curl :8090/healthz`。裸机可：

```bash
curl -fsS http://127.0.0.1:8090/healthz
```

---

## 三、运营账号创建与登录

### 3.1 首位 admin（bootstrap）

ops-server 启动时若无运营账号且设置了 `MM_OPS_BOOTSTRAP_ADMIN=user:pass`，则创建首位 admin（`signup=first` 模式下首位即 admin，见 `cmd/ops-server/main.go::bootstrapAdmin`）。已存在账号则幂等跳过。

```bash
# .env 或 environment
MM_OPS_BOOTSTRAP_ADMIN=admin:AdminStr0ng!8
MM_OPS_SIGNUP_MODE=first
```

> 该凭据仅用于首次引导；登录后建议在前端改密（或重建容器时去掉该环境变量）。

### 3.2 登录管理台

浏览器打开 `http://<host>:8090/admin/` → 登录页。输入 `admin / AdminStr0ng!8` → 提交 `POST /admin/login` → 成功后设置 cookie/返回 admin token，进入 dashboard。

### 3.3 注册模式（`MM_OPS_SIGNUP_MODE`）

| 值 | 行为 |
|---|---|
| `first`（默认） | 仅当尚无账号时允许注册，首位授予 admin；其后关闭 |
| `off` | 关闭注册，全部账号由 admin 创建/绑定 |

运营账号一般不开放自助注册，保持 `first` 或 `off`。

### 3.4 创建普通运营账号（可选）

如果前端支持（`POST /admin/api/user/bind-server` 等写端点，见 `OPS-GUIDE.md`），可由 admin 手动登记运营账号并绑定到某个 server。否则靠 bootstrap + first 模式即可满足单管理员场景。

---

## 四、服务端注册（让 backend 上线到 ops）

### 4.1 自动注册（推荐）

backend 启动时若配了 `MM_OPS_SERVER_URL`，会自动：

1. `POST /op/server/register` 拿 `{server_id, server_token}`
2. 用 `server_token` 维持 WS 长连（`GET /op/server/ws`）+ 25s 心跳，断线指数退避重连

backend 侧配置（见 `DEPLOY-SERVER.md` §三）：

```bash
# backend 的 .env / docker-compose environment
MM_OPS_SERVER_URL=http://ops.example.com:8090
```

组网验证：backend 容器日志应出现注册成功 + WS 已连接；ops 前端「服务端」页（`/admin/servers.html`）应可见该 backend 在线。

### 4.2 手动登记（admin 前端）

在管理台 `servers.html` 页点「手动登记 / 注册服务端」，调 `POST /admin/api/server/register`（带管理员鉴权），返回 `server_id` + `server_token`。可复制 token 供 backend 侧配置（若不走自动注册路径）。

### 4.3 直接 API 调用

```bash
# admin 鉴权调 admin 写端点
ADMIN_TOKEN="<登录后获取>"
curl -X POST http://<ops>:8090/admin/api/server/register \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"home-nas","base_url":"http://home-nas.local:8080"}'

# 或由 backend 直接调 op 端点（自动注册路径）
curl -X POST http://<ops>:8090/op/server/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"home-nas","base_url":"http://home-nas.local:8080"}'
# → {"server_id":"srv_...","server_token":"..."}
```

返回的 `server_token` 是 backend 维持 WS 长连的凭据，需妥善保管。

---

## 五、与 backend 组网（MM_OPS_SERVER_URL）

### 5.1 配置链路

```
backend (8080)  ──注册──▶  ops-server (8090)
     ▲                            │
     │   WS 长连 + 心跳           │
     └────────────────────────────┘
     │
客户端 App ──经中继 18790──▶ backend（路径 B：跨网时）
```

### 5.2 backend 侧

```yaml
# backend/config.yaml
ops_server_url: "http://ops.example.com:8090"
```

或环境变量：`MM_OPS_SERVER_URL=http://ops.example.com:8090`。

> Docker 部署时若 ops-server 在另一容器，用 compose 网络服务名（如 `http://ops-server:8090`，见 `DEPLOY-COMPOSE.md`）；若 ops 在宿主机、backend 在容器，用 `http://host.docker.internal:8090`（macOS）/ 对应宿主 IP（Linux）。

### 5.3 组网验证清单

- [ ] ops-server `:8090/healthz` ok
- [ ] backend 日志：`registered with ops server: server_id=...`
- [ ] backend 日志：`ops ws connected`（无重连报错）
- [ ] ops 前端「服务端」页：对应 backend 在线、最后心跳时间近
- [ ] 客户端登录运营账号 → 绑定 server → 请求连接 → 经中继 `18790` 转发到 backend `8080`
- [ ] 完成一次 `sync/changes` + 上传 → ops 前端「流量」页可见 bytes 计数

### 5.4 客户端侧

客户端 App 登录运营账号后绑定到某个 server（`POST /admin/api/user/bind-server` 或前端操作），再请求中继连接。详见 `USER-GUIDE.md` 与 `OPS-GUIDE.md`。

---

## 六、流量看板解读

ops 前端「流量」页（`/admin/traffic.html`）展示中继流量记账（`relay_session` 表）：

- **按 server 聚合汇总**：会话数、活跃会话数、总入站字节、总出站字节
- **最近会话明细**：session_id、server、对端、bytes_in、bytes_out、开始时间、时长、close_reason
- **活跃会话**（进行中，`ended_at` 为空）+ 「断开」按钮（`POST /admin/api/session/close`）

字段含义：

| 字段 | 含义 |
|---|---|
| `bytes_in` / `bytes_out` | 中继转发进/出的字节数（单会话） |
| `total_bytes_in/out` | 某 server 累计字节 |
| `session_count` | 某 server 历史会话总数 |
| `active_sessions` | 当前进行中会话数 |
| `close_reason` | 会话结束原因：`normal` / `peer_error` / `peer_absent` / `timeout` |

看板用法详见 `OPS-GUIDE.md`。流量数据源：`GET /admin/api/traffic/summary`、`GET /admin/api/sessions?limit=100`、`GET /admin/api/sessions/active`。

---

## 七、配置项完整参考（ops-server）

ops-server 全走环境变量（不读 yaml）。覆盖链：代码默认 < `MM_OPS_*`。

| 变量 | 默认 | 说明 |
|---|---|---|
| `MM_OPS_HTTP_ADDR` | `:8090` | HTTP 监听地址（REST/admin + /healthz） |
| `MM_OPS_RELAY_ADDR` | `:18790` | TCP 中继监听地址 |
| `MM_OPS_DATA_DIR` | `/data`（容器）/ `./ops-data` | 数据目录（SQLite 库 + 会话状态） |
| `MM_OPS_DB_PATH` | `<DATA_DIR>/ops.db` | SQLite 库路径 |
| `MM_OPS_JWT_SECRET` | 空 → 进程级随机（重启失效） | JWT HS256 密钥。**生产必填**，用 `openssl rand -hex 32`。 |
| `MM_OPS_JWT_TTL_SECONDS` | `604800`（7 天） | admin token 有效期 |
| `MM_OPS_SIGNUP_MODE` | `first` | 注册模式：`first`（首位即 admin）/ `off`（关闭） |
| `MM_OPS_BOOTSTRAP_ADMIN` | 空 | 首次启动创建首位 admin，格式 `user:pass`。无账号时生效，已存在则跳过。 |
| `MM_OPS_LOG_BYTES_VERBOSE` | `true` | 中继日志是否记录字节计数 |

> `.env.example` 有完整注释。docker-compose 的 `environment` 段会覆盖 `.env` 同名变量（用于固定容器内路径）；运行参数（JWT/bootstrap）放 `.env` 即可。

---

## 八、TLS / 公网暴露

ops-server 默认明文 HTTP。公网部署务必前置反代做 TLS（nginx/Caddy），与 `DEPLOY-SERVER.md` §七同理。`/op/server/ws` 等 WebSocket 端点需反代支持 WS Upgrade（见 `DEPLOY-COMPOSE.md` 反代配置）。

```nginx
location /op/ {
    proxy_pass http://127.0.0.1:8090;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;   # WS 长连
}
```

TCP relay `18790` 无法走 HTTP 反代，需直接暴露 TCP 端口（或用 4 层负载/`stream {}` 段转发）。公网中继场景确保防火墙放行 18790。

---

## 九、排障速查

| 现象 | 排查 |
|---|---|
| backend 日志 `ops register failed` | ops-server 不可达、`MM_OPS_SERVER_URL` 错；检查 `:8090/healthz` |
| backend WS 反复重连 | 网络中断或 ops 重启；指数退避会自愈，看 `reconnect` 日志 |
| ops 前端「服务端」页无在线 | backend 未注册或 WS 断开；看 backend 日志 `opsws` |
| relay 18790 连不上 | 端口未映射/防火墙拦截；`telnet <ops> 18790` |
| 流量页无数据 | 未发生中继会话；让客户端经中继完成一次上传后刷新 |
| admin 登录失败 | `MM_OPS_BOOTSTRAP_ADMIN` 未设或账号已存在但要新建；确认 `signup` 模式 |

更多运营管理操作见 `OPS-GUIDE.md`。
