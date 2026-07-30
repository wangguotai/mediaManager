# 存储服务端部署指南（DEPLOY-SERVER）

> 受众：自托管用户（把媒体存到自己机器上的人）
> 对应组件：`backend/`（Go + SQLite，REST :8080，进程内 gRPC :50051 不对容器外暴露）
> 配套 docs：`DEPLOY-OPS.md`（运营方）、`DEPLOY-COMPOSE.md`（全栈一键）、`SECURITY.md`、`RUNBOOK.md`

本篇覆盖：Docker 一键 → 裸机 systemd → 全配置项表 → 首次超管引导 → 数据卷与备份 → 升级 → TLS 反代。

---

## 一、10 分钟跑通（Docker 最小步骤）

```bash
# 1. 拉代码
git clone <repo> media-manager && cd media-manager/backend

# 2. 生成强 JWT 密钥，写进 compose 的 environment（生产必做）
export JWT=$(openssl rand -hex 32)

# 3. 一键起容器（首次会自动构建镜像）
MM_JWT_SECRET="$JWT" docker compose up -d --build

# 4. 抓首次启动日志里的一次性超管 token（密码不入日志）
docker compose logs media-server | grep -A8 "INITIAL ADMIN"

# 5. 用 token 登录拿新 token、立即改密
TOKEN="<上一步日志里的 token>"
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\"}"   # 注：实际用 username+password；首次密码由 bootstrap 生成，
                                #     但日志不打印。可用下方「引导凭据」方式接管。

# 6. 健康检查
curl http://localhost:8080/healthz
```

> **关于首次登录**：后端首次启动（user 表为空）会自动创建超管 `admin` 并在日志里打印一次性 **token**（不打印明文密码）。这个 token 可直接带 `Authorization: Bearer <token>` 调任何需鉴权端点，**但建议立刻用 `POST /api/auth/change-password` 设新密码**（见 §四）。如果你想要可预期的密码，构建/启动时传 `MM_BOOTSTRAP_ADMIN_USERNAME` + `MM_BOOTSTRAP_ADMIN_PASSWORD`（见 §三）。

成功标志：`/healthz` 返回 `{"status":"ok",...}`；`docker compose ps` 显示 `healthy`。

---

## 二、裸机 systemd 部署

适合不想用 Docker、或要把后端跑在 NAS/树莓派上的用户。

### 2.1 编译

```bash
# 需 Go 1.23+（go.mod 声明 1.23；1.24 也验证过）+ ffmpeg/ffprobe
cd backend
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /usr/local/bin/media-server ./cmd/server
```

> `CGO_ENABLED=0` 可行：SQLite 用纯 Go 的 `modernc.org/sqlite`，无 CGO 依赖。ffmpeg/ffprobe 仍是视频缩略图抽帧和信息解析的硬依赖，系统必须装（`apt install ffmpeg` / `brew install ffmpeg`）。

### 2.2 配置文件

```bash
install -d /var/lib/media-manager /etc/media-manager
cp backend/config.example.yaml /etc/media-manager/config.yaml
# 编辑 /etc/media-manager/config.yaml：至少设 jwt_secret、data_dir、allow_signup
```

最小可用 `config.yaml`（其余字段省略即用默认）：

```yaml
port: "8080"
data_dir: /var/lib/media-manager/data
jwt_secret: "<openssl rand -hex 32 的输出>"
allow_signup: off
```

### 2.3 systemd unit

`/etc/systemd/system/media-manager.service`：

```ini
[Unit]
Description=Media Manager storage backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=media
Group=media
ExecStart=/usr/local/bin/media-server
WorkingDirectory=/var/lib/media-manager
Environment=MM_CONFIG=/etc/media-manager/config.yaml
# 也可全用环境变量，省去 config.yaml：
# Environment=MM_PORT=8080
# Environment=MM_DATA_DIR=/var/lib/media-manager/data
# Environment=MM_JWT_SECRET=...
# Environment=MM_ALLOW_SIGNUP=off
Restart=on-failure
RestartSec=3
# 资源/安全加固
LimitNOFILE=65536
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/media-manager

[Install]
WantedBy=multi-user.target
```

启用：

```bash
useradd -r -d /var/lib/media-manager -s /usr/sbin/nologin media
chown -R media:media /var/lib/media-manager
systemctl daemon-reload
systemctl enable --now media-manager
journalctl -u media-manager -f | grep -A8 "INITIAL ADMIN"   # 抓首次超管 token
```

### 2.4 非特权运行 + ffmpeg

Docker 镜像内以 `uid 10001 app` 运行；裸机同理用 `media` 用户。确保 `media` 对 `data_dir` 有读写权限，对 ffmpeg 二进制有执行权限即可。

---

## 三、配置项完整参考

覆盖优先级：**代码默认 < `config.yaml` < `MM_*` 环境变量**（见 `internal/config/config.go::ApplyEnv`）。Docker 部署通过 compose 的 `environment` 段注入 `MM_*`，任何字段都可临时覆盖而无需改文件。

### 3.1 backend `config.yaml` 字段表

| 字段 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `port` | `MM_PORT` | `8080` | REST gateway 监听端口（纯端口号）。gRPC `:50051` 仅进程内部，不在此配置、不对容器外暴露。 |
| `data_dir` | `MM_DATA_DIR` | `./data` | 运行期数据根目录。其下布局见 §五。 |
| `db_path` | `MM_DB_PATH` | `<data_dir>/media.db` | SQLite 元数据库路径。可设绝对路径放独立位置。 |
| `ops_server_url` | `MM_OPS_SERVER_URL` | 空（回退 `OPENCLAW_GATEWAY_URL` 再回退 `http://127.0.0.1:8090`） | ops-server 地址，用于 `/api/openclaw/command` 转发 + 启动自动 `POST /op/server/register` + WS 长连。留空不影响媒体主功能（桥接端点返回 502）。 |
| `jwt_secret` | `MM_JWT_SECRET` | 空 → 进程级 32 字节随机（重启失效） | JWT HS256 密钥。**生产必须显式配置**足够随机的长字符串。 |
| `jwt_ttl_seconds` | `MM_JWT_TTL_SECONDS` | `604800`（7 天） | token 有效期。 |
| `allow_signup` | `MM_ALLOW_SIGNUP` | `off` | 注册策略：`off`（禁注册，最安全）/ `first`（仅库空时允许首位注册者即 admin）/ `open`（任意人可注册为 user）。 |
| — | `MM_BOOTSTRAP_ADMIN_USERNAME` | `admin` | 首次启动（库空）创建的超管用户名。库非空则幂等跳过。 |
| — | `MM_BOOTSTRAP_ADMIN_PASSWORD` | 随机 16 字节 | 首次启动超管密码。留空则随机生成（日志只打 token 不打密码）。 |
| — | `MM_TLS_CERT` / `MM_TLS_KEY` | 空 | 可选原生 HTTPS 证书路径。生产推荐走反代（见 §七），此选项供无反代场景。 |

> `MM_TLS_CERT/MM_TLS_KEY` 为当前 PRD 预留项；若无此原生 HTTPS 支持编译进当前版本，请统一用反代（§七）。

### 3.2 `allow_signup` 三态详解

- `off`（默认，最安全）：禁止自助注册。但**首次启动 user 表为空时会自动 bootstrap 一个超管**（见 §四），避免没人能登录的死锁。
- `first`：仅在库为空时允许注册，首位注册者自动授予 admin 角色，之后关闭。适合"不想靠 bootstrap、想自己注册首超管"的场景。
- `open`：任意人可注册（角色固定为 user）。仅适合受信内网。

### 3.3 ops-server 相关环境变量（仅组网时需要）

见 `DEPLOY-OPS.md`。核心：`MM_OPS_HTTP_ADDR=:8090`、`MM_OPS_RELAY_ADDR=:18790`、`MM_OPS_JWT_SECRET`、`MM_OPS_BOOTSTRAP_ADMIN=user:pass`。

---

## 四、首次超管引导（空库自动创建）

### 4.1 机制

启动时若 user 表为空，`bootstrapAdmin`（`cmd/server/main.go`）自动创建首个超管账号并签发 token。库非空则幂等跳过（重启不重复建号、不重复打印）。

安全策略：**日志只打印 username + 一次性 token，不打印明文密码**。密码由程序内部生成（随机 16 字节 hex）或由 `MM_BOOTSTRAP_ADMIN_PASSWORD` 指定，仅用于内部签发。

日志样例：

```
========================================================
 INITIAL ADMIN ACCOUNT CREATED (first run, empty user DB)
--------------------------------------------------------
  username: admin
  token   : eyJhbGciOiJIUzI1NiIs...
  expires : 2026-08-06T12:34:56Z
  note    : password is NOT logged — use the token above to
            login, then CHANGE it via POST /api/auth/change-password
--------------------------------------------------------
 allow_signup=off
========================================================
```

### 4.2 用 token 接管 + 改密

```bash
# 用打印的 token 直接调改密端点（change-password 需鉴权，token 即有效凭证）
curl -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer <日志里的 token>" \
  -H 'Content-Type: application/json' \
  -d '{"old_password":"<bootstrap 随机密码或你设的>","new_password":"MyNewStr0ng!Pass"}'
```

> 若你用 `MM_BOOTSTRAP_ADMIN_PASSWORD` 显式指定了密码，`old_password` 填该值即可；若用随机密码但没记下，可先用 token 调任何端点验证接管，再通过管理员建号流程重置（或清库重启引导——仅限无数据时）。

改密成功后旧 token 立即失效（密码哈希已变），需用新密码重新 `POST /api/auth/login` 获取新 token。

### 4.3 想跳过随机密码？显式 bootstrap

compose 或 systemd 启动前设：

```bash
MM_BOOTSTRAP_ADMIN_USERNAME=admin
MM_BOOTSTRAP_ADMIN_PASSWORD=Str0ngInit!Pass8
```

首次启动即用该凭据创建超管，之后用 `admin` + 该密码登录、改密即可。**注意**：该密码会出现在进程环境/compose 文件里，仅适合首次引导，改密后应清除该环境变量。

---

## 五、数据卷与备份

### 5.1 目录结构

`data_dir`（容器内 `/app/data`，裸机默认 `./data` 或 `/var/lib/media-manager/data`）布局：

```
data/
├── media.db                          # SQLite 元数据库（user/media/device 表，WAL 模式）
├── media.db-wal                      # WAL 日志（运行期产生，checkpoint 后回收）
├── media.db-shm                      # WAL 共享内存
├── cloud-images/                     # 全局共享的网盘图片源（公共，不按用户隔离）
│   └── *.jpg
└── users/<uid>/
    ├── uploads/                      # 该用户上传的原文件（按 media_id 命名）
    ├── thumbnails/                   # 该用户的缩略图缓存
    ├── metadata/                     # 媒体元数据
    ├── video-meta/                   # 视频信息（时长/分辨率，ffprobe 产出）
    ├── favorites.json                # 收藏持久化（JSON + RWMutex）
    └── albums.json                   # 相册持久化
```

> 按登录用户隔离：`users/<uid>/` 下互不可见（除非 cloud-images 公共源）。备份时按目录整体拷贝即可保留隔离。

### 5.2 Docker 卷

`backend/docker-compose.yml` 用命名卷 `media-data` 挂到 `/app/data`。

```bash
docker volume inspect media-data      # 看实际宿主机路径
docker run --rm -v media-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/media-data-$(date +%F).tgz -C /data .
```

### 5.3 SQLite 在线备份（推荐）

WAL 模式下直接 `cp media.db` 可能丢未 checkpoint 的页。用 SQLite 的 `.backup` 命令做在线一致性快照：

```bash
# 容器内（装有 sqlite3；镜像基于 debian-slim 可 apt 装，或用独立 alpine 容器）
docker exec -it media-server sqlite3 /app/data/media.db ".backup /app/data/backup-$(date +%F).db"

# 裸机
sqlite3 /var/lib/media-manager/data/media.db ".backup /var/lib/media-manager/data/backup-$(date +%F).db"
```

`.backup` 会在一个写事务里把数据库 + WAL 内容写成一个一致的新文件，可热备不锁写。

完整备份脚本（cron 每日）：

```bash
#!/bin/bash
set -e
DATA=/var/lib/media-manager/data
BK=/var/backups/media-manager
mkdir -p "$BK"
sqlite3 "$DATA/media.db" ".backup '$BK/media-$(date +%F-%H%M).db'"
tar czf "$BK/files-$(date +%F-%H%M).tgz" "$DATA/users" "$DATA/cloud-images"
# 保留 14 天
find "$BK" -mtime +14 -delete
```

### 5.4 恢复

```bash
systemctl stop media-manager
rm -f /var/lib/media-manager/data/media.db*           # 清旧库 + WAL
cp /var/backups/media-manager/media-2026-07-30-0300.db /var/lib/media-manager/data/media.db
tar xzf /var/backups/media-manager/files-2026-07-30-0300.tgz -C /var/lib/media-manager/data/
chown -R media:media /var/lib/media-manager/data
systemctl start media-manager
```

> 恢复后 JWT 密钥若未变，老客户端 token 仍有效；若同时换了 `jwt_secret`，所有客户端需重新登录。

更多维护（VACUUM / WAL checkpoint / 索引）见 `RUNBOOK.md`。

---

## 六、升级流程

### 6.1 Docker

```bash
cd backend
git pull
docker compose build --pull        # 重新构建镜像
docker compose up -d               # 滚动重启（数据卷保留）
docker compose logs media-server | tail -20
```

数据卷 `media-data` 跨升级保留，无需迁移。SQLite schema 由后端启动时自动迁移（若需要），通常无需手动 `ALTER`。

### 6.2 裸机

```bash
systemctl stop media-manager
# 备份（见 §5.3）——升级前必做
cd /opt/media-manager && git pull
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /usr/local/bin/media-server ./backend/cmd/server
systemctl start media-manager
journalctl -u media-manager -f
```

### 6.3 回滚

若新版本异常：

```bash
# Docker
docker compose down
git checkout <旧 commit>
docker compose build && docker compose up -d
# 数据卷已保留；若新版本改了 schema 且不兼容（罕见），用 §5.4 的备份恢复 media.db

# 裸机
systemctl stop media-manager
git checkout <旧 commit> && go build -o /usr/local/bin/media-server ./backend/cmd/server
systemctl start media-manager
```

> 回滚前若已用新版本写入数据，优先 §5.4 从升级前备份恢复 `media.db`，避免 schema 漂移。

---

## 七、TLS 反向代理（nginx）

后端默认监听明文 HTTP :8080；生产环境**强烈建议前置 nginx/Caddy 做 TLS 终止**（后端 CORS/限速/JWT 仍在后端侧生效）。

### 7.1 nginx 配置示例

```nginx
# /etc/nginx/sites-available/media-manager.conf
upstream media_backend {
    server 127.0.0.1:8080;
    keepalive 16;
}

server {
    listen 80;
    server_name media.example.com;
    # 非加密跳 HTTPS
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name media.example.com;

    # 证书（用 certbot / acme.sh 自动续签）
    ssl_certificate     /etc/letsencrypt/live/media.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/media.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    # 大文件上传：放宽默认 1MB 限制
    client_max_body_size 0;          # 0 = 不限（后端流式落盘已处理大文件）
    proxy_request_buffering off;     # 流式透传，避免 nginx 缓存整文件占内存

    location / {
        proxy_pass http://media_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 视频流（Range）与 WebSocket（若反代 ops-server）需要
        proxy_set_header Range $http_range;
        proxy_set_header Connection $connection_upgrade;

        # 大文件/流式超时放宽
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;         # 流式直发客户端
    }

    # /healthz 与 /metrics 可选放行（无认证）；生产可对 /metrics 加 IP 白名单
    location /metrics {
        allow 10.0.0.0/8;
        allow 192.168.0.0/16;
        deny all;
        proxy_pass http://media_backend;
    }
}
```

> 反代后客户端 App 里填的"服务端地址"应是 `https://media.example.com`（不再带 8080 端口）。

### 7.2 启用

```bash
ln -s /etc/nginx/sites-available/media-manager.conf /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
# 证书
certbot --nginx -d media.example.com
```

### 7.3 可选：后端原生 HTTPS

若不反代、需后端直接 HTTPS，设置 `MM_TLS_CERT` / `MM_TLS_KEY` 指向证书路径（当前版本是否编译启用以代码为准；未启用则走反代）。生产推荐始终走反代——便于续签、限流、加 WAF。

---

## 八、验证清单

部署完成后逐项确认：

- [ ] `curl http://<host>:8080/healthz` 返回 ok
- [ ] `docker compose ps` / `systemctl status media-manager` 服务 healthy/active
- [ ] 日志已抓到 INITIAL ADMIN token 并已 change-password
- [ ] 用新密码 `POST /api/auth/login` 拿到 token
- [ ] 带 token `GET /api/sync/usage` 返回用量
- [ ] `data_dir` 目录权限正确、`users/<uid>/uploads` 可写
- [ ] （生产）HTTPS 反代生效、`https://<host>/healthz` ok
- [ ] （组网）`MM_OPS_SERVER_URL` 指向 ops-server，见 `DEPLOY-OPS.md`

---

## 九、排障速查

| 现象 | 排查 |
|---|---|
| 启动日志无 INITIAL ADMIN | user 表非空（已引导过，正常）；或 bootstrap 失败看 `WARNING: admin bootstrap failed` |
| 视频缩略图 500 | ffmpeg/ffprobe 未装或不在 PATH（容器镜像已含；裸机需 `apt install ffmpeg`） |
| `/api/openclaw/command` 502 | `ops_server_url` 未配或 ops-server 不可达；不影响媒体主功能 |
| 上传 429 "too many concurrent uploads" | 单用户并发上传超 3（`uploadConcurrentMax=3`），稍候重试 |
| 登录 429 | 同 IP+username 1 分钟超 10 次（`loginRateMax=10`），等 1 分钟 |
| SQLite `database is locked` | 罕见（已 WAL + MaxOpenConns 10）；看 `db_pool_in_use_connections` 指标，必要时调 `RUNBOOK.md` WAL checkpoint |

更多排障见 `RUNBOOK.md`。
