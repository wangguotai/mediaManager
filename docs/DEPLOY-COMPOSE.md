# 全栈 Compose 一键部署（DEPLOY-COMPOSE）

> 受众：进阶用户（想一把把 backend + ops + 反代 + TLS 全拉起）
> 对应组件：`backend/` + `ops-server/` + nginx 反代
> 配套 docs：`DEPLOY-SERVER.md`、`DEPLOY-OPS.md`（单组件细节）、`SECURITY.md`

本篇提供一个**项目级** `docker-compose.yml`，把三组件 + nginx 反代 + TLS 合到一份编排，一键启动全栈。各子目录已自带各自的单组件 compose（`backend/docker-compose.yml`、`ops-server/docker-compose.yml`），本文是"全栈合一"方案。

---

## 一、10 分钟跑通

```bash
cd /Users/wgt/projects/media-manager

# 1. 生成两份强密钥
export BE_JWT=$(openssl rand -hex 32)
export OPS_JWT=$(openssl rand -hex 32)

# 2. 把下面 §二的全栈 compose 写到 deploy/docker-compose.yml，.env 写密钥
mkdir -p deploy
cat > deploy/.env <<EOF
BE_JWT_SECRET=${BE_JWT}
OPS_JWT_SECRET=${OPS_JWT}
OPS_DOMAIN=media.example.com
OPS_ADMIN=admin:AdminStr0ng!8
EOF

# 3. 一键起全栈
docker compose -f deploy/docker-compose.yml up -d --build

# 4. 抓 backend 首次超管 token
docker compose -f deploy/docker-compose.yml logs backend | grep -A8 "INITIAL ADMIN"

# 5. 验证
curl http://localhost:8080/healthz          # backend 直连（仅本机）
curl http://localhost:8090/healthz          # ops 直连（仅本机）
curl https://${OPS_DOMAIN}/healthz          # 经反代 TLS（需 DNS + 证书就绪）
```

> 若还没有域名/证书，可先把 nginx 的 `listen 443 ssl` 改成 `listen 80` 跑明文验证流程，证书就绪后再切 TLS（见 §四）。

成功标志：三个容器 `healthy`，backend 日志有 INITIAL ADMIN token，ops 前端 `https://<host>/admin/` 可登录。

---

## 二、全栈 docker-compose.yml

保存为 `deploy/docker-compose.yml`：

```yaml
# Media Manager 全栈一键部署：backend + ops-server + nginx(TLS 反代)。
#
# 用法：
#   cd deploy && docker compose up -d --build
#   docker compose logs -f
#   docker compose down          # 停止，卷保留
#   docker compose down -v       # 连同数据卷删除（谨慎）
#
# 网络：media-net 内部互访用服务名（backend / ops-server / nginx）。
# 对外：nginx 80/443（HTTP/HTTPS），ops relay 18790（TCP 中继，无法经 HTTP 反代）。
# backend 8080 / ops 8090 仅容器内可见（生产不直接对外）；本机调试可在下方 ports 取消注释。

services:
  # ---------- 存储服务端 ----------
  backend:
    build:
      context: ../backend
      dockerfile: Dockerfile
    image: media-manager:latest
    container_name: mm-backend
    restart: unless-stopped
    expose:
      - "8080"
    # 本机调试可临时映射（生产注释掉，只走反代）：
    # ports:
    #   - "8080:8080"
    environment:
      MM_PORT: "8080"
      MM_DATA_DIR: /app/data
      MM_DB_PATH: /app/data/media.db
      MM_JWT_SECRET: "${BE_JWT_SECRET}"
      MM_JWT_TTL_SECONDS: "604800"
      MM_ALLOW_SIGNUP: "off"
      # 组网：用 compose 服务名访问 ops-server
      MM_OPS_SERVER_URL: "http://ops-server:8090"
      # 首次超管（可选，留空则随机生成，看日志拿 token）
      MM_BOOTSTRAP_ADMIN_USERNAME: "admin"
      # MM_BOOTSTRAP_ADMIN_PASSWORD: "change-me-too"
    volumes:
      - media-data:/app/data
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8080/healthz"]
      interval: 7s
      timeout: 3s
      start_period: 5s
      retries: 5
    networks: [media-net]

  # ---------- 运营服务端 ----------
  ops-server:
    build:
      context: ../ops-server
      dockerfile: Dockerfile
    image: media-manager/ops-server:latest
    container_name: mm-ops
    restart: unless-stopped
    expose:
      - "8090"
    ports:
      - "18790:18790"   # TCP 中继必须直接对外（无法经 HTTP 反代）
    # 本机调试可临时映射 8090：
    # - "8090:8090"
    environment:
      MM_OPS_HTTP_ADDR: ":8090"
      MM_OPS_RELAY_ADDR: ":18790"
      MM_OPS_DATA_DIR: "/data"
      MM_OPS_JWT_SECRET: "${OPS_JWT_SECRET}"
      MM_OPS_SIGNUP_MODE: "first"
      MM_OPS_BOOTSTRAP_ADMIN: "${OPS_ADMIN}"
    volumes:
      - ops-data:/data
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8090/healthz"]
      interval: 30s
      timeout: 5s
      start_period: 10s
      retries: 3
    user: "ops"
    networks: [media-net]

  # ---------- nginx TLS 反代 ----------
  nginx:
    image: nginx:1.27-alpine
    container_name: mm-nginx
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./certs:/etc/nginx/certs:ro           # 证书目录（见 §四）
      - ./htpasswd:/etc/nginx/htpasswd:ro     # 可选：/metrics 基本认证
    depends_on:
      backend:
        condition: service_healthy
      ops-server:
        condition: service_healthy
    networks: [media-net]

volumes:
  media-data:
    name: media-data
  ops-data:
    name: ops-server-data

networks:
  media-net:
    name: media-net
    driver: bridge
```

> `depends_on: condition: service_healthy` 保证 nginx 等 backend/ops 健康后再启动。但注意 backend 首次启动会 bootstrap admin（几秒），nginx 拉起时 backend 可能还在打 token——属正常。

---

## 三、nginx 反代配置（`deploy/nginx.conf`）

```nginx
# /etc/nginx/conf.d/default.conf
upstream mm_backend { server backend:8080; keepalive 16; }
upstream mm_ops     { server ops-server:8090; keepalive 16; }

# HTTP → HTTPS 跳转
server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name media.example.com;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_session_cache shared:SSL:10m;

    # 大文件上传：不缓存整文件，流式透传
    client_max_body_size 0;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;

    # ---- 存储服务端（用户媒体 API）----
    location / {
        proxy_pass http://mm_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Range $http_range;
        proxy_set_header Connection $connection_upgrade;
    }

    # ---- 健康检查（公开）----
    location = /healthz {
        proxy_pass http://mm_backend;
    }

    # ---- /metrics（建议加 IP 白名单或 basic auth）----
    location /metrics {
        # 二选一：IP 白名单
        allow 10.0.0.0/8;
        allow 192.168.0.0/16;
        deny all;
        # 或 basic auth：
        # auth_basic "metrics";
        # auth_basic_user_file /etc/nginx/htpasswd;
        proxy_pass http://mm_backend;
    }

    # ---- 运营管理台 + op API（含 WebSocket）----
    location /admin/ {
        proxy_pass http://mm_ops;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location /op/ {
        proxy_pass http://mm_ops;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";   # WS Upgrade
        proxy_read_timeout 3600s;                # WS 长连
        proxy_send_timeout 3600s;
    }
}

# WebSocket Upgrade 映射（nginx 内建变量，需 http 块有 map，此处用 $connection_upgrade 须配合）
# 若 $connection_upgrade 未定义，可在 nginx.conf 顶部加：
# map $http_upgrade $connection_upgrade {
#     default upgrade;
#     '' close;
# }
```

> `$connection_upgrade` 需在 `nginx.conf` 的 `http {}` 顶部用 `map` 定义（见注释）。alpine 镜像默认 `nginx.conf` 无此 map，可在 `default.conf` 同目录加一个 `map.conf` 或直接改主 `nginx.conf`。

---

## 四、TLS 证书

### 4.1 用 acme.sh / certbot 签发

在宿主机（非容器内）签发，证书目录挂进 nginx 容器：

```bash
mkdir -p deploy/certs
# acme.sh 示例（DNS 验证适合通配符；HTTP 验证需 80 端口可达）
acme.sh --issue -d media.example.com --webroot /var/www/html \
  --key-file deploy/certs/privkey.pem \
  --fullchain-file deploy/certs/fullchain.pem \
  --reloadcmd "docker exec mm-nginx nginx -s reload"
```

或 certbot：

```bash
certbot certonly --standalone -d media.example.com
cp /etc/letsencrypt/live/media.example.com/fullchain.pem deploy/certs/
cp /etc/letsencrypt/live/media.example.com/privkey.pem  deploy/certs/
```

### 4.2 没有域名？自签证书临时验证

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout deploy/certs/privkey.pem -out deploy/certs/fullchain.pem \
  -subj "/CN=media.local" -addext "subjectAltName=IP:127.0.0.1"
```

nginx 配置仍用同一 `ssl_certificate` 指令。浏览器会警告但不影响接口验证。

---

## 五、卷映射、网络、健康检查

### 5.1 卷

| 命名卷 | 挂载点 | 内容 | 备份方式 |
|---|---|---|---|
| `media-data` | backend `/app/data` | SQLite + 用户文件 + cloud-images | `DEPLOY-SERVER.md` §五 |
| `ops-data` | ops `/data` | ops SQLite + 会话状态 | `sqlite3 ops.db .backup` |

```bash
docker volume inspect media-data ops-data
```

### 5.2 网络

- `media-net`（bridge）：容器间用服务名互访（`backend:8080`、`ops-server:8090`）。
- 对外：nginx `80/443`、ops relay `18790`。backend `8080`、ops `8090` 默认仅 `expose`（容器内可见），本机调试可临时加 `ports` 映射。

### 5.3 健康检查

| 服务 | 检查 | 阈值 |
|---|---|---|
| backend | `curl :8080/healthz` | 7s 间隔、5 次失败 unhealthy |
| ops-server | `curl :8090/healthz` | 30s 间隔、3 次失败 unhealthy |
| nginx | （无内置，依赖 upstream healthy） | — |

`docker compose ps` 看 STATUS 列是否有 `(healthy)`。

---

## 六、常用运维命令

```bash
# 启动 / 停止 / 重启
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml down
docker compose -f deploy/docker-compose.yml restart backend

# 查日志
docker compose -f deploy/docker-compose.yml logs -f backend
docker compose -f deploy/docker-compose.yml logs -f ops-server
docker compose -f deploy/docker-compose.yml logs -f nginx

# 进容器排障
docker exec -it mm-backend sh
docker exec -it mm-ops sh
docker exec -it mm-nginx sh

# 备份（热备 SQLite）
docker exec mm-backend sqlite3 /app/data/media.db ".backup /app/data/backup-$(date +%F).db"
docker exec mm-ops sqlite3 /data/ops.db ".backup /data/backup-$(date +%F).db"
docker run --rm -v media-data:/d -v $(pwd)/deploy/backup:/b alpine tar czf /b/media-$(date +%F).tgz -C /d .
```

---

## 七、升级

```bash
cd /Users/wgt/projects/media-manager
git pull
docker compose -f deploy/docker-compose.yml build --pull
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml logs -f backend | grep -A8 "INITIAL ADMIN"
```

卷跨升级保留。回滚见 `DEPLOY-SERVER.md` §六 / `RUNBOOK.md`。

---

## 八、排障速查

| 现象 | 排查 |
|---|---|
| nginx 502 Bad Gateway | backend/ops 未 healthy；`docker compose ps` 看状态，看 `docker logs mm-backend` |
| `https://<host>/healthz` 失败 | 证书未就绪/域名未解析；先用 `http://<host>:8080/healthz` 直连验证 backend |
| WS（`/op/server/ws`）无法建立 | nginx `/op/` 段缺 `Upgrade`/`Connection` 头；确认 `map` 块 |
| relay 18790 连不上 | `ports: 18790:18790` 未映射或防火墙拦截 |
| backend 日志 `ops register failed` | ops-server 未起来或 `MM_OPS_SERVER_URL` 拼错（应为 `http://ops-server:8090`） |
| 上传大文件 413 | nginx `client_max_body_size` 未设 0（默认 1MB）；或 `proxy_request_buffering` 未关 |
| `/metrics` 403 | IP 白名单未放行你的 IP，或 basic auth 未配 |
