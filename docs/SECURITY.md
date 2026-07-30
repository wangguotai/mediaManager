# 安全指南（SECURITY）

> 受众：所有部署方（自托管用户 / 运营方 / 运维）
> 对应组件：`backend/`（:8080）、`ops-server/`（:8090 + :18790）、客户端 App
> 配套 docs：`DEPLOY-SERVER.md`、`DEPLOY-OPS.md`、`RUNBOOK.md`

本篇覆盖：密钥管理 → TLS → CORS → 限速 → 密码策略 → 凭据存储 → 已知安全边界。

---

## 一、10 分钟安全基线检查

```bash
# 1. JWT 密钥不是默认/空/弱口令
docker exec mm-backend env | grep MM_JWT_SECRET
# 应为 64 位 hex（openssl rand -hex 32），不是 change-me / wangguotai / 空

# 2. CORS 不开放给公网
curl -s -H "Origin: https://evil.com" https://<host>/healthz -I | grep -i access-control
# 对外网 Origin 不应回显 Access-Control-Allow-Origin

# 3. 限速生效
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://<host>/api/auth/login \
    -H 'Content-Type: application/json' -d '{"username":"x","password":"wrong"}'
done
# 前 10 次 400/401，第 11 次起 429

# 4. 无 token 被拒
curl -s -o /dev/null -w "%{http_code}\n" http://<host>/api/media/list
# → 401

# 5. 越权被拒（删别人的 media）
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://<host>/api/media/delete \
  -H "Authorization: Bearer <A用户token>" \
  -H 'Content-Type: application/json' -d '{"id":"<B用户的media_id>"}'
# → 403/404（MarkDeletedForUser 双键校验）

# 6. 仓库无明文密钥
cd /Users/wgt/projects/media-manager
git grep -nE 'wangguotai|change-me-to-a-long|jwt_secret:\s*"[^"]{1,20}"' -- backend/config*.yaml
# 应无命中（config.yaml 已清空，只留占位）
```

成功标志：6 项全部通过。任何一项不通过都应在上线前修复。

---

## 二、密钥管理

### 2.1 backend JWT 密钥（`jwt_secret` / `MM_JWT_SECRET`）

- **HS256 签名密钥**，签发所有用户 token。泄露 = 任何人可伪造任意用户 token。
- **生产必须显式配置足够随机的长字符串**：`openssl rand -hex 32`（64 位 hex，256 bit）。
- 留空时后端生成 32 字节进程级随机密钥（`internal/auth/auth.go`），**重启即失效**，所有已签发 token 全部作废——仅适合本地开发。
- 配置链：代码默认 < `config.yaml` < `MM_JWT_SECRET` 环境变量（Docker compose `environment` 段注入，不在镜像里）。
- **严禁明文密钥入仓**：仓库 `config.yaml` 只留空占位 + 注释指引，`config.example.yaml` 同样空占位。历史弱口令 `wangguotai` 已清除（V5 安全项）。

```bash
# 正确做法
export MM_JWT_SECRET=$(openssl rand -hex 32)
# 写进 compose environment 或 .env，.env 加入 .gitignore
echo "MM_JWT_SECRET=$MM_JWT_SECRET" >> deploy/.env
```

### 2.2 ops-server JWT 密钥（`MM_OPS_JWT_SECRET`）

- 同理，签发 admin token。生产 `openssl rand -hex 32`。
- ops-server 不读 yaml，全走 `MM_OPS_*` 环境变量。

### 2.3 轮换

- 轮换 `jwt_secret` 后所有已签发 token 立即失效，所有客户端需重新登录。
- 定期轮换（如每季度）或疑似泄露时立即轮换。
- 轮换前通知用户，避免大规模掉线。

### 2.4 bootstrap admin 密钥

- 首次启动空库自动创建超管：`MM_BOOTSTRAP_ADMIN_USERNAME`（默认 `admin`）+ `MM_BOOTSTRAP_ADMIN_PASSWORD`（留空则随机 16 字节 hex）。
- **日志只打印一次性 token，不打印明文密码**（`cmd/server/main.go::bootstrapAdmin`，V5 安全项）。
- 用 token 登录后**立即 `POST /api/auth/change-password` 改密**。
- 若用 `MM_BOOTSTRAP_ADMIN_PASSWORD` 显式指定，该密码会出现在 compose/环境里，**仅限首次引导**，改密后清除该环境变量。
- ops-server 同理：`MM_OPS_BOOTSTRAP_ADMIN=user:pass`，首次创建后去掉。

### 2.5 server_token（ops ↔ backend）

- backend 注册到 ops 后拿 `server_token`，用于维持 WS 长连（`GET /op/server/ws` 需 `requireServerTokenWS`）。
- 该 token 由 ops 签发，存 ops 侧。泄露可伪造 backend 注册。
- 在 ops 管理台可重新生成/复制（`POST /admin/api/server/register`）。
- 网络层限制只有可信 backend 能访问 `:8090/op/server/register`。

---

## 三、TLS 配置

### 3.1 默认明文，生产必须反代

backend 监听明文 HTTP :8080，ops 监听明文 HTTP :8090。**生产环境必须前置 nginx/Caddy 做 TLS 终止**。

反代配置示例见 `DEPLOY-SERVER.md` §七、`DEPLOY-COMPOSE.md` §三。要点：

- `listen 443 ssl http2`，证书用 Let's Encrypt（certbot/acme.sh 自动续签）。
- `ssl_protocols TLSv1.2 TLSv1.3`，禁用旧版。
- 大文件上传：`client_max_body_size 0` + `proxy_request_buffering off`（流式透传）。
- WebSocket（ops `/op/`）：`Upgrade`/`Connection` 头 + 长超时。
- `/metrics` 加 IP 白名单或 basic auth（无认证端点）。

### 3.2 可选原生 HTTPS

`MM_TLS_CERT` / `MM_TLS_KEY`（当前版本是否编译启用以代码为准）允许后端直接 HTTPS。不推荐生产用——反代更易管理续签/限流/WAF。

### 3.3 TCP relay 无法 TLS

ops relay `18790` 是裸 TCP，无法经 HTTP 反代做 TLS。若需加密中继流量，用 4 层 TLS 代理（如 `stream {}` 段 ssl_proxy）或 stunnel。当前最小版本中继为明文 TCP，**假设在可信网络或接受明文中继**。

---

## 四、CORS 策略

### 4.1 收紧策略（V5 安全项）

backend `corsMiddleware`（`internal/gateway/server.go`）只对以下 Origin 放行：

- **localhost**（任意端口）：`localhost` / `127.0.0.1` / `::1`
- **RFC1918 私网网段**：`10.0.0.0/8` / `172.16.0.0/12` / `192.168.0.0/16`

其余 Origin 一律**不回显** `Access-Control-Allow-Origin`，浏览器跨域请求被拒。

### 4.2 客户端 App 不受 CORS 限制

CORS 是浏览器安全策略。客户端 App（Android/iOS，Ktor/OkHttp/Darwin engine）发起的 HTTP 请求不受 CORS 约束。CORS 主要影响：

- 浏览器内管理台（ops admin 前端，同源访问 `:8090/admin/`，不受影响）
- 任何用浏览器 JS 调 backend API 的场景

### 4.3 公网部署的 CORS

公网反代后，App 直连 `https://<host>`，Origin 通常是 null（App 请求）或同源。私网 CORS 限制不影响 App 正常使用。若需浏览器跨域访问 backend（如 Web 管理台调 backend API），把 frontend 部署在同源，或扩展 CORS 白名单（当前代码固定私网，扩展需改 `isOriginAllowed`）。

> 已知限制：**当前 CORS 白名单不可配置**（代码硬编码 localhost + RFC1918）。公网纯浏览器场景需同源部署或反代改写。

---

## 五、限速

### 5.1 登录暴力防护

`/api/auth/login` 按 **(IP, username)** 滑动窗口限速（`internal/gateway/ratelimit.go`）：

- **窗口 1 分钟**（`loginRateWindow = time.Minute`）
- **最大 10 次/窗口**（`loginRateMax = 10`）
- 超限返回 **429 Too Many Requests**
- 窗口过期自动清理（每 5 分钟扫一次）

```bash
# 验证：同 IP+username 第 11 次返回 429
for i in $(seq 1 11); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://<host>/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"wrong"}'
done
# 1-10: 401，11: 429
```

### 5.2 上传并发限速

`/api/media/upload` 按 **user_id** 维护信号量（`uploadSemaphores`，buffered-channel）：

- **单用户最大并发 3**（`uploadConcurrentMax = 3`）
- 超限返回 **429** `"too many concurrent uploads, please retry shortly"`

```bash
# 验证：同一用户同时发起 4 个上传，第 4 个 429
```

### 5.3 ops-server 限速

ops-server 当前最小版无内置限速（admin 靠鉴权 + 网络层控制）。生产建议反代层对 `/admin/login` 加限速。

### 5.4 反代层补充限速

nginx 可补充全局限速（防止 DDoS）：

```nginx
limit_req_zone $binary_remote_addr zone=login:10m rate=10r/m;
location /api/auth/login {
    limit_req zone=login burst=5 nodelay;
    proxy_pass http://mm_backend;
}
```

---

## 六、密码策略

### 6.1 最小长度

- **最小 8 位**（`minPasswordLength = 8`，`internal/auth/auth.go`）。V5 从 4 提升到 8。
- 注册、改密、bootstrap 均强制校验：`validatePassword` 不通过返回 `ErrInvalidCredentials`（gateway 映射为 400）。

### 6.2 复杂度建议（推荐，非强制）

- 当前代码只强制长度 8。推荐用户设置包含**大小写字母 + 数字 + 符号**的强密码。
- 后端 bcrypt 哈希（`bcrypt.GenerateFromPassword`，`DefaultCost=10`），即使弱口令也不存明文，但弱口令仍易被离线爆破——建议运营时引导用户用强口令。
- 若需强制复杂度，扩展 `validatePassword` 加正则（当前未做，PRD §2.7 列为待办）。

### 6.3 哈希算法

- **bcrypt**（`golang.org/x/crypto/bcrypt`），cost = `bcrypt.DefaultCost`（10）。
- 登录用 `bcrypt.CompareHashAndPassword` 验证。
- 改密（`POST /api/auth/change-password`）：校验旧密码 → 新密码 bcrypt → 更新哈希。

### 6.4 改密端点

`POST /api/auth/change-password`（需鉴权，带 token）：

```bash
curl -X POST http://<host>/api/auth/change-password \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{"old_password":"<旧>","new_password":"<新至少8位>"}'
```

改密成功后旧 token 失效（密码哈希已变），需用新密码重新登录。

### 6.5 bootstrap admin 密码

- 留空 `MM_BOOTSTRAP_ADMIN_PASSWORD` → 随机 16 字节 hex，日志只打 token 不打密码。
- 显式指定 → 仅限首次引导，改密后清除环境变量。

---

## 七、凭据存储（客户端）

### 7.1 iOS — Keychain（V5 安全项）

- 凭据（token、user_id）存 **Keychain**（`kSecClassGenericPassword`），非 NSUserDefaults 明文。
- 索引文件存 App 私有目录 JSON（`Documents/`，非 NSUserDefaults，避免进 iCloud 备份）。
- 首次启动从旧 `mm_secure.json` 迁移到 Keychain 后删文件（幂等）。
- K/N interop 全路径释放（`CFRelease`）。

### 7.2 Android — EncryptedSharedPreferences

- 凭据存 **EncryptedSharedPreferences**（Android Keystore 加密）。
- Keystore 失败时**拒绝写入敏感项**（V5 去除了明文 SharedPreferences 降级，安全性优先于可用性）。
- 索引存 `filesDir` JSON。

### 7.3 token 持久化

- 登录后 token 持久化到上述安全存储，冷启动读回免重复登录。
- token TTL 默认 7 天（`jwt_ttl_seconds=604800`），过期需重新登录。
- 退出登录清除 token；改密使旧 token 失效。

### 7.4 已知限制

- iOS 部分 interop 曾有 KN 问题（V5 已修），若旧版本升级遇问题，重新登录即可。
- Android Keystore 失败（极少见，root/定制 ROM）会导致无法存凭据，App 会提示而非降级明文。

---

## 八、已知安全边界

部署方需知的当前限制与对应缓解：

### 8.1 默认无 HTTPS — 必须反代

- backend/ops 默认明文 HTTP。**生产必须前置 nginx/Caddy 做 TLS**（见 §三）。
- 不反代直接对公网暴露 :8080/:8090 = 凭据明文传输，**不可接受**。

### 8.2 私网 CORS 限制（不可配置）

- CORS 白名单硬编码为 localhost + RFC1918（见 §四）。
- 公网纯浏览器跨域调 backend API 需同源部署或反代改写；App 不受影响。
- 如需开放特定公网 Origin，改 `isOriginAllowed`（当前不可配置）。

### 8.3 /metrics 与 /healthz 无认证

- `GET /metrics`（Prometheus）与 `GET /healthz` 豁免鉴权（便于探活与抓取）。
- `/metrics` 暴露请求计数/延迟/内存等，**不含用户数据或密钥**，但可被用于侦察。
- 生产建议反代对 `/metrics` 加 IP 白名单或 basic auth（见 `DEPLOY-SERVER.md` §七）。
- `/healthz` 有 30s TTL 缓存，避免无认证被刷导致 IO 放大。

### 8.4 ops 注册无审批

- `POST /op/server/register` 注册即上线，不需 admin 审批。
- 限制谁能注册靠**网络层**（防火墙/内网，只让可信 backend 访问 :8090/op/）。
- 公网部署务必对 :8090/op/ 做来源限制。

### 8.5 TCP relay 明文

- relay :18790 是裸 TCP，无 TLS（当前最小版）。
- 明文假设在可信网络或接受明文中继。需加密用 4 层 TLS 代理/stunnel。

### 8.6 无多租户隔离

- backend 按用户隔离数据（`users/<uid>/` + SQL `user_id` 校验），但**不提供租户级加密/隔离**。
- 适合单组织自托管。多租户商业化非目标（PRD 非目标）。

### 8.7 无审计日志（当前）

- 有结构化访问日志（method/path/status/latency/user_id 脱敏）+ request id。
- 无独立的**安全审计日志**（谁删了什么、登录成功/失败明细）。如需合规审计，后续扩展。

### 8.8 SQL 注入

- 全部用参数化查询（`?` 占位），无字符串拼接 SQL。
- 用户输入仅作为参数传入，不经 SQL 解释。

### 8.9 越权防护

- 媒体删除/元数据写操作强制 `AND user_id=?`（`MarkDeletedForUser(userID, id)` 双键校验，V5 安全项）。
- 跨用户删/改返回 403/404，不可横向越权。
- 列表/流/缩略图均按 `user_id` 过滤 + 按 `user_id` 定位 `uploads` 目录。

### 8.10 速率限制无持久化

- login/upload 限速是**进程内**（滑动窗口 map / 信号量），重启清零。
- 单实例部署足够；多实例需在外部（如 Redis）做共享限速（当前非目标）。

---

## 九、安全加固 checklist（上线前）

- [ ] `MM_JWT_SECRET` / `MM_OPS_JWT_SECRET` 用 `openssl rand -hex 32`，非默认/弱口令
- [ ] 仓库无明文密钥（`git grep` 无命中）
- [ ] 反代 TLS（443），HTTP 80 跳 HTTPS
- [ ] 证书自动续签（certbot/acme.sh）
- [ ] `/metrics` 加 IP 白名单或 basic auth
- [ ] 防火墙只放行 443（+ 18790 若需中继）；8080/8090 不直接对公网
- [ ] ops `/op/server/register` 来源限制（只可信 backend）
- [ ] bootstrap admin 已改密，`MM_BOOTSTRAP_ADMIN_PASSWORD` 环境变量已清
- [ ] 登录限速验证（11 次 → 429）
- [ ] 上传并发限速验证（4 并发 → 429）
- [ ] 无 token → 401；越权删除 → 403/404
- [ ] 客户端凭据在 Keychain / EncryptedSharedPreferences（非明文）
- [ ] 定期备份（见 `RUNBOOK.md`）+ 备份加密
- [ ] 密钥轮换流程就绪（季度/泄露时）
