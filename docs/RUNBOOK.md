# 运维 Runbook（RUNBOOK）

> 受众：运维（负责 backend / ops-server 持续运行与排障的人）
> 对应组件：`backend/`（:8080）、`ops-server/`（:8090 + :18790）
> 配套 docs：`DEPLOY-SERVER.md`、`DEPLOY-OPS.md`、`SECURITY.md`、`OPS-GUIDE.md`

本篇覆盖：备份恢复 → SQLite 维护 → 升级回滚 → 排障（墓碑/中继/OOM）→ 监控指标解读（`/metrics`）。

---

## 一、10 分钟跑通（首次运维就位）

```bash
# 1. 确认两服务健康
curl http://<be>:8080/healthz   # → {"status":"ok",...}
curl http://<ops>:8090/healthz  # → {"status":"ok"}

# 2. 拉 Prometheus 指标快照
curl http://<be>:8080/metrics | head -40

# 3. 跑一次 SQLite 在线备份（见 §二）
docker exec mm-backend sqlite3 /app/data/media.db ".backup /app/data/daily-$(date +%F).db"

# 4. 跑一次 WAL checkpoint（见 §三）
docker exec mm-backend sqlite3 /app/data/media.db "PRAGMA wal_checkpoint(TRUNCATE);"

# 5. 看 backend 是否已向 ops 注册
docker exec mm-ops sqlite3 /data/ops.db "SELECT id,name,base_url FROM servers;"
```

成功标志：两个 healthz ok；metrics 有输出；备份文件生成；checkpoint 返回 `0`（无错误）；servers 表有 backend 记录。

---

## 二、备份恢复

### 2.1 数据目录结构

```
data/                                 # backend data_dir，容器内 /app/data
├── media.db                          # SQLite 元数据库（user/media/device 表，WAL 模式）
├── media.db-wal / media.db-shm       # WAL 日志与共享内存
├── cloud-images/                     # 全局共享网盘图片源
└── users/<uid>/
    ├── uploads/                      # 原文件
    ├── thumbnails/                   # 缩略图
    ├── metadata/ video-meta/         # 元数据
    ├── favorites.json                # 收藏持久化
    └── albums.json                   # 相册持久化

data/                                 # ops-server data_dir，容器内 /data
└── ops.db                            # ops SQLite（accounts/servers/devices/relay_session）
```

### 2.2 SQLite 在线备份（热备，推荐）

WAL 模式下直接 `cp media.db` 可能丢未 checkpoint 的页。用 `.backup` 在写事务里做一致快照：

```bash
# backend
docker exec mm-backend sqlite3 /app/data/media.db \
  ".backup '/app/data/backup-$(date +%F-%H%M).db'"

# ops-server
docker exec mm-ops sqlite3 /data/ops.db \
  ".backup '/data/backup-$(date +%F-%H%M).db'"

# 裸机
sqlite3 /var/lib/media-manager/data/media.db ".backup '/var/backups/media-$(date +%F).db'"
```

`.backup` 不阻塞写，可热备。

### 2.3 文件目录备份

```bash
# Docker（用独立容器挂载命名卷拷出）
docker run --rm -v media-data:/d -v /var/backups/mm:/b alpine \
  tar czf /b/files-$(date +%F).tgz -C /d users cloud-images

# 裸机
tar czf /var/backups/mm/files-$(date +%F).tgz \
  -C /var/lib/media-manager/data users cloud-images
```

> `thumbnails/` 是缓存，可不含备份（丢了后端会重新生成）。`uploads/` 是原文件，**必须备份**。

### 2.4 自动备份脚本（cron 每日）

`/usr/local/bin/mm-backup.sh`：

```bash
#!/bin/bash
set -euo pipefail
BK=/var/backups/media-manager
mkdir -p "$BK"
DATE=$(date +%F-%H%M)

# 1. SQLite 热备
docker exec mm-backend sqlite3 /app/data/media.db ".backup '/app/data/db-$DATE.db'"
docker exec mm-backend sh -c "mv /app/data/db-$DATE.db /tmp/ && cat /tmp/db-$DATE.db" > "$BK/media-db-$DATE.db" 2>/dev/null
docker exec mm-backend rm -f /tmp/db-$DATE.db

# 2. 文件目录
docker run --rm -v media-data:/d -v "$BK":/b alpine \
  tar czf "/b/files-$DATE.tgz" -C /d users cloud-images

# 3. ops 库
docker exec mm-ops sqlite3 /data/ops.db ".backup '/data/ops-$DATE.db'"
docker exec mm-ops sh -c "cat /data/ops-$DATE.db" > "$BK/ops-db-$DATE.db"
docker exec mm-ops rm -f /data/ops-$DATE.db

# 4. 保留 14 天
find "$BK" -mtime +14 -delete
echo "backup done: $BK (media-db-$DATE, files-$DATE, ops-db-$DATE)"
```

```bash
chmod +x /usr/local/bin/mm-backup.sh
# crontab -e
0 3 * * * /usr/local/bin/mm-backup.sh >> /var/log/mm-backup.log 2>&1
```

### 2.5 恢复

```bash
# 停服务
docker compose -f deploy/docker-compose.yml stop backend ops-server
# 或裸机 systemctl stop media-manager

# 清旧库 + WAL（backend）
docker run --rm -v media-data:/d alpine sh -c "rm -f /d/media.db*"
# 恢复 db
docker run --rm -v media-data:/d -v /var/backups/media-manager:/b alpine \
  cp /b/media-db-2026-07-30-0300.db /d/media.db
# 恢复文件
docker run --rm -v media-data:/d -v /var/backups/media-manager:/b alpine \
  tar xzf /b/files-2026-07-30-0300.tgz -C /d

# 起服务
docker compose -f deploy/docker-compose.yml start backend ops-server
```

> 恢复后 JWT 密钥未变则老 token 仍有效；若同时换了 `jwt_secret`，所有客户端需重新登录。

---

## 三、SQLite 维护

### 3.1 WAL 模式与 checkpoint

backend 默认 `PRAGMA journal_mode=WAL` + `MaxOpenConns=10`。WAL 日志（`media.db-wal`）会增长，需定期 checkpoint 回主库：

```bash
# 被动 checkpoint（默认自动）：每次 WAL ≥ 1000 帧 SQLite 自动 checkpoint
# 主动 TRUNCATE checkpoint：把 WAL 清回 0 字节
docker exec mm-backend sqlite3 /app/data/media.db "PRAGMA wal_checkpoint(TRUNCATE);"
# 返回：busy=0, log=N_pages, checkpointed=N_pages
```

建议每周或备份后跑一次 TRUNCATE。

### 3.2 VACUUM（重建数据库，回收空间）

```bash
# VACUUM 会重写整个 db 文件，需临时空间 ≈ db 大小，期间阻塞写
# 建议低峰或停服后做
docker compose stop backend
docker run --rm -v media-data:/d alpine sh -c \
  "apk add --no-cache sqlite && sqlite3 /d/media.db 'VACUUM;'"
docker compose start backend
```

> 大量删除后 `VACUUM` 回收空间；日常不必频繁（每月一次足够）。`VACUUM INTO 'new.db'` 可在线做一份压缩副本。

### 3.3 索引检查与重建

backend 启动时已自动创建索引（V5 性能项）：

- `media(user_id, sha256)` — 秒传去重
- `media(user_id, deleted, updated_at)` — 同步增量
- `media(user_id, updated_at)` — 列表分页
- `device(user_id)` — 设备列表

检查：

```bash
docker exec mm-backend sqlite3 /app/data/media.db \
  "SELECT name, tbl_name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;"
# 应至少看到 media_user_sha256 / media_user_del_upd / media_user_upd / device_user
```

如果索引丢失（异常），重启 backend 会重建。也可手动：

```sql
CREATE INDEX IF NOT EXISTS media_user_sha256 ON media(user_id, sha256);
CREATE INDEX IF NOT EXISTS media_user_del_upd ON media(user_id, deleted, updated_at);
CREATE INDEX IF NOT EXISTS media_user_upd ON media(user_id, updated_at);
CREATE INDEX IF NOT EXISTS device_user ON device(user_id);
```

### 3.4 查看表大小与碎片

```bash
docker exec mm-backend sqlite3 /app/data/media.db <<'SQL'
SELECT 'media', COUNT(*) FROM media WHERE deleted=0
UNION ALL SELECT 'media_tombstone', COUNT(*) FROM media WHERE deleted=1
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'devices', COUNT(*) FROM device;
.page_size 4096
PRAGMA page_count;        -- 总页数 × page_size = 文件大小
PRAGMA freelist_count;    -- 空闲页数（多说明碎片，VACUUM 回收）
SQL
```

### 3.5 ops-server SQLite

同理：

```bash
docker exec mm-ops sqlite3 /data/ops.db "PRAGMA wal_checkpoint(TRUNCATE); VACUUM;"
docker exec mm-ops sqlite3 /data/ops.db \
  "SELECT 'servers',COUNT(*) FROM servers UNION ALL SELECT 'relay_sessions',COUNT(*) FROM relay_session;"
```

> ops 库小，维护简单。`relay_session` 累积后可定期归档/清理旧会话（`DELETE FROM relay_session WHERE ended_at < ...`）。

---

## 四、升级回滚

### 4.1 升级流程

**升级前必做备份**（§二）。

```bash
# Docker 全栈
cd /Users/wgt/projects/media-manager
git pull
docker compose -f deploy/docker-compose.yml build --pull
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml logs -f backend | grep -A8 "INITIAL ADMIN"

# 单组件
cd backend && git pull && docker compose build --pull && docker compose up -d
```

裸机：

```bash
systemctl stop media-manager
# 备份
cd /opt/media-manager && git pull
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /usr/local/bin/media-server ./backend/cmd/server
systemctl start media-manager
journalctl -u media-manager -f
```

### 4.2 升级验证

- [ ] `healthz` ok
- [ ] 日志无 WARNING/ERROR
- [ ] `/metrics` 正常输出
- [ ] 登录、上传、list、sync/changes 各跑一次
- [ ] （组网）ops 前端服务端在线

### 4.3 回滚

若新版本异常：

```bash
# Docker
docker compose -f deploy/docker-compose.yml down
git checkout <旧 commit>
docker compose -f deploy/docker-compose.yml build && docker compose -f deploy/docker-compose.yml up -d
# 若新版本改了 schema 且旧版本不兼容：恢复升级前的 media.db 备份（§2.5）

# 裸机
systemctl stop media-manager
git checkout <旧 commit> && go build -o /usr/local/bin/media-server ./backend/cmd/server
systemctl start media-manager
# schema 不兼容则恢复备份
```

> 回滚前若新版本已写入数据，优先从升级前备份恢复 `media.db`，避免 schema 漂移导致旧版本读不了。

---

## 五、监控指标解读（配合 /metrics）

backend `GET /metrics` 输出 Prometheus 文本格式，**无认证**（生产建议反代加 IP 白名单或 basic auth，见 `SECURITY.md`）。ops 可能无独立 `/metrics`（以代码为准），主要靠 admin API。

### 5.1 指标清单

| 指标 | 类型 | 含义 |
|---|---|---|
| `http_requests_total{method,path,status}` | counter | 请求计数 |
| `http_request_duration_seconds{method,path,le}` | histogram | 请求延迟分布（5ms~10s 分桶） |
| `media_upload_bytes_total` | counter | 累计上传字节 |
| `sync_changes_served_total` | counter | sync/changes 服务过的变更条数 |
| `cache_hits_total{cache=list|thumb}` | counter | 缓存命中 |
| `cache_misses_total{cache=list|thumb}` | counter | 缓存未命中 |
| `cache_hit_ratio{cache=list|thumb}` | gauge | 缓存命中率（0~1） |
| `db_pool_open_connections` | gauge | DB 连接池当前打开数 |
| `db_pool_in_use_connections` | gauge | DB 连接池当前使用数 |
| `db_pool_max_open_connections` | gauge | DB 连接池上限（10） |
| `go_goroutines` | gauge | goroutine 数 |
| `go_memstats_alloc_bytes` | gauge | 堆已分配 |
| `go_memstats_sys_bytes` | gauge | 进程向 OS 索取内存 |
| `go_memstats_heap_inuse_bytes` | gauge | 堆 in-use |

### 5.2 Prometheus 抓取配置

```yaml
# prometheus.yml
scrape_configs:
  - job_name: media-manager
    static_configs:
      - targets: ['backend:8080']   # 或宿主 IP:8080
    metrics_path: /metrics
    scrape_interval: 15s
```

### 5.3 告警阈值建议

| 条件 | 阈值 | 含义 |
|---|---|---|
| `up{job="media-manager"} == 0` | 1 min | 服务不可达 |
| `rate(http_requests_total{status=~"5.."}[5m]) > 0.1` | 5 min | 5xx 错误率高 |
| `histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m])) > 2` | 5 min | P95 延迟超 2s |
| `db_pool_in_use_connections / db_pool_max_open_connections > 0.8` | 5 min | 连接池快满 |
| `go_goroutines > 1000` | 10 min | goroutine 泄漏 |
| `go_memstats_alloc_bytes > 1e9` | 10 min | 堆超 1GB |
| `cache_hit_ratio{cache="list"} < 0.5` | 30 min | 列表缓存命中率低（列表扫描频繁） |

### 5.4 /healthz

`GET /healthz`（无认证）返回 JSON 含 status/disk/memory/media_count。30s TTL 缓存（避免无认证端点被刷导致 IO 放大）。健康检查用 `/healthz`，监控用 `/metrics`。

### 5.5 /api/stats

`GET /api/stats`（需鉴权）返回缓存命中率统计，兼容旧端点。生产监控用 `/metrics` 即可。

---

## 六、排障

### 6.1 墓碑不一致（删除没同步到其他端）

**现象**：A 机删了照片，B 机"已上传"里还在。

**排查**：

```bash
# 1. 查 media.db 该条 deleted 标志
docker exec mm-backend sqlite3 /app/data/media.db \
  "SELECT id,user_id,deleted,updated_at FROM media WHERE id='<media_id>';"
# deleted=1 说明墓碑已写

# 2. 查 sync/changes 是否返回了这条墓碑
curl -s "http://<be>:8080/api/sync/changes?since=0" \
  -H "Authorization: Bearer <token>" | grep -A3 "<media_id>"
# 墓碑应出现在 changes 里（deleted=true）

# 3. 若墓碑未写：检查删除时是否 user_id 校验失败（横向越权被拒）
#    MarkDeletedForUser(userID, id) 双键校验，跨用户删除返回 403/404
```

**修复**：

- 墓碑已写但 B 机没同步：让 B 机下拉刷新/重开 App 触发增量同步。
- 墓碑未写（删除请求被拒）：确认删除者是该 media 的 owner，用正确账号删。
- 游标边界问题：（V5 已用 `(updated_at, id)` 复合游标消除同时间戳重/漏；若旧客户端只传 `since=ms` 无 id，升级客户端）

### 6.2 中继连不上（backend ↔ ops WS 断）

**现象**：backend 日志反复 `ops register failed` 或 `opsws disconnected`；ops 前端服务端页显示离线。

**排查**：

```bash
# 1. ops 可达性
curl http://<ops>:8090/healthz

# 2. backend 配置
docker exec mm-backend env | grep MM_OPS_SERVER_URL
# 应为 http://ops-server:8090（compose）或 http://<ops-host>:8090

# 3. 网络互通（compose 内）
docker exec mm-backend wget -qO- http://ops-server:8090/healthz
# 不通 → 检查 compose 网络、ops 容器是否在同一个 network

# 4. relay 端口
telnet <ops> 18790
# 不通 → 检查 ports 映射、防火墙

# 5. token 失效
# backend 日志若出现 401/403 → server_token 失效，重新注册
curl -X POST http://<ops>:8090/op/server/register \
  -H 'Content-Type: application/json' -d '{"name":"...","base_url":"..."}'
# 拿新 token，配回 backend（或让 backend 自动重注册：重启 backend）
```

**修复**：

- 网络问题：修 compose 网络/防火墙。
- token 失效：重启 backend（自动重注册），或 admin 在 ops 前端复制旧 token 重配。
- ops 重启：backend 指数退避自愈，通常无需干预。

### 6.3 OOM（内存溢出）

**现象**：backend 进程被 OOM-kill（`docker inspect` 看 OOMKilled=true），或恐慌日志 `runtime: out of memory`。

**排查**：

```bash
# 1. 内存指标
curl http://<be>:8080/metrics | grep go_memstats
# go_memstats_alloc_bytes / heap_inuse_bytes 持续涨 → 泄漏

# 2. 大图上传/缩略图
# nearestNeighbor 缩略图对超大图占内存；看上传是否伴随内存飙高
# 50MB 大图全尺寸解码会 OOM（前端已降采样，后端缩略图限并发）

# 3. goroutine 泄漏
curl http://<be>:8080/metrics | grep go_goroutines
# 持续涨不回落 → 泄漏

# 4. Docker 内存限制
docker stats mm-backend --no-stream
```

**处置**：

- 临时：重启 backend（`docker compose restart backend`），数据不丢。
- 大图缩略图 OOM：限制上传文件大小（nginx `client_max_body_size`），或等后端缩略图并发限制/降级补丁。
- 内存泄漏：抓 `curl /metrics` 历史曲线定位增长来源，提 issue。
- 调高容器内存限制：compose `mem_limit: 2g`（治标）。

### 6.4 database is locked

**现象**：`SQLITE_BUSY` / `database is locked` 错误。

**排查**：

- 已 WAL + MaxOpenConns 10 + busy_timeout，理论上罕见。
- 看指标 `db_pool_in_use_connections` 是否长期接近 10。

**处置**：

```bash
# WAL checkpoint 可能被长事务阻塞，主动 TRUNCATE
docker exec mm-backend sqlite3 /app/data/media.db "PRAGMA wal_checkpoint(TRUNCATE);"
# 看是否有长查询持有锁
docker exec mm-backend sqlite3 /app/data/media.db "PRAGMA busy_timeout;"
```

频繁出现则考虑升级硬件 IO 或排查是否有异常大批量写。

### 6.5 缩略图 500（ffmpeg 缺失）

**现象**：视频缩略图端点返回 500，日志 `ffmpeg not found`。

**处置**：

- Docker 镜像已含 ffmpeg，不应出现。
- 裸机：`apt install ffmpeg` 或 `brew install ffmpeg`，确保 `ffmpeg`/`ffprobe` 在 PATH。

### 6.6 上传 429 / 登录 429

| 现象 | 机制 | 处置 |
|---|---|---|
| 上传 `too many concurrent uploads` | 单用户并发上传超 3（`uploadConcurrentMax=3`） | 稍候重试，或减少并发 |
| 登录 429 | 同 IP+username 1 分钟超 10 次（`loginRateMax=10`） | 等 1 分钟 |

### 6.7 磁盘满

**现象**：上传失败、日志 `no space left on device`。

**处置**：

```bash
df -h
docker system df              # 看 docker 占用
# 清理：旧备份、thumbnail 缓存、VACUUM 回收
docker exec mm-backend sqlite3 /app/data/media.db "VACUUM;"
docker run --rm -v media-data:/d alpine rm -rf /d/users/*/thumbnails/*
# thumbnails 是缓存，可清，后端重新生成
```

---

## 七、日常运维 checklist

**每日**：
- [ ] `healthz` 两个服务 ok
- [ ] 备份脚本跑过（看 `/var/log/mm-backup.log`）
- [ ] `/metrics` 无 5xx 飙高

**每周**：
- [ ] WAL checkpoint TRUNCATE
- [ ] 看磁盘占用趋势
- [ ] ops `relay_session` 归档旧会话（可选）

**每月**：
- [ ] VACUUM（低峰）
- [ ] 索引检查
- [ ] 证书续期（若 90 天 Let's Encrypt）

**升级前**：
- [ ] 备份 media.db + ops.db + 文件目录
- [ ] 记录当前 commit hash（便于回滚）
- [ ] 通知用户短时不可用
