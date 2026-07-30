# 运营管理员操作手册（OPS-GUIDE）

> 受众：运营管理员（在 ops-server 管理台做日常运营的人）
> 对应组件：ops-server 运营管理台 `http://<ops>:8090/admin/`
> 配套 docs：`DEPLOY-OPS.md`（部署）、`RUNBOOK.md`（运维排障）

本篇覆盖：运营前端各页用法 → 注册审批 → 服务端监控 → 连接管理 → 流量统计解读（含写操作页）。

---

## 一、10 分钟跑通

前置：ops-server 已部署（见 `DEPLOY-OPS.md`），你有 admin 账号。

```bash
# 1. 确认 ops-server 健康
curl http://<ops>:8090/healthz   # → {"status":"ok"}

# 2. 浏览器打开管理台
open http://<ops>:8090/admin/     # 登录页

# 3. 用 admin 账号登录（首次由 MM_OPS_BOOTSTRAP_ADMIN 创建）
#    用户名: admin  密码: <bootstrap 设的>

# 4. 进入 dashboard，应看到已注册的服务端、在线会话数

# 5. 让某个 backend 注册上来（backend 配 MM_OPS_SERVER_URL 后自动注册）
#    在「服务端」页应看到该 backend 在线
```

成功标志：登录后 dashboard 有数据；「服务端」页至少有一个在线 backend。

---

## 二、运营前端各页用法

管理台是纯 HTML/JS（vanilla），左侧导航五个页面：

| 页面 | URL | 用途 |
|---|---|---|
| Dashboard | `/admin/dashboard.html` | 总览：服务端数、在线会话、活跃用户、流量概要 |
| 服务端 | `/admin/servers.html` | 注册/查看/登记存储服务端、复制 server_token |
| 用户 | `/admin/users.html` | 运营账号列表、绑定 server |
| 流量 | `/admin/traffic.html` | 中继流量统计、会话明细、断开会话 |

> 所有页面的数据通过 `GET /admin/api/*` 拉取，写操作通过 `POST /admin/api/*`（带管理员鉴权 token，登录后自动带 cookie/header）。

### 2.1 Dashboard

打开即看全局：

- **服务端总数 / 在线数**：已注册的 backend 数、当前 WS 长连在线数
- **活跃会话数**：当前进行中的中继会话
- **运营用户数**：admin + 普通运营账号
- **流量概要**：总入/出字节、总会话数

数据源 `GET /admin/api/overview`。页面自动刷新。

### 2.2 服务端页（servers.html）

- **列表**：所有已注册 backend，含 `server_id`、`name`、`base_url`、在线状态、最后心跳时间
- **手动登记**：点"注册服务端"按钮 → 弹表单填 `name` + `base_url` → 调 `POST /admin/api/server/register` → 返回 `server_id` + `server_token`，可复制
- **复制 token**：每行有复制按钮，把 `server_token` 复制给 backend 侧配置（若不走自动注册）

写操作端点：`POST /admin/api/server/register`。

### 2.3 用户页（users.html）

- **运营账号列表**：`GET /admin/api/users/list`，含 `user_id`、`username`、`role`、绑定的 `server_id`
- **绑定 server**：选中用户 → 选 server → 调 `POST /admin/api/user/bind-server`，把运营账号绑定到某个 backend
- 创建普通运营账号：视前端是否提供（若 `signup=first` 已关闭，由 admin 手动建）

写操作端点：`POST /admin/api/user/bind-server`。

### 2.4 流量页（traffic.html）

详见 §五。

---

## 三、注册审批

### 3.1 服务端注册流程

backend 注册到 ops 有两种路径：

1. **自动注册**（推荐）：backend 配 `MM_OPS_SERVER_URL`，启动自动 `POST /op/server/register` → 拿 `server_token` → 维持 WS 长连。**无需 admin 审批**，注册即上线。
2. **手动登记**：admin 在「服务端」页点"注册服务端"手动填表，调 `POST /admin/api/server/register`，拿到 token 后人工配置给 backend。

### 3.2 是否需要审批？

当前设计是**注册即上线**（op 端点 `POST /op/server/register` 不需要 admin 审批）。若要限制谁能注册，靠网络层控制：只让可信 backend 能访问 `:8090/op/server/register`（防火墙/内网）。

> 如需"审批"语义，可在管理台把未识别的 server 标记/禁用（若前端支持 disable 字段；当前版本以在线状态为准），或后续扩展 admin 写端点。当前最小版本以"网络可达即可注册"为模型。

### 3.3 识别未授权注册

- 在「服务端」页看 `name` 和 `base_url`，不认识的就是异常。
- 异常的：通知运维在网络层封掉其来源 IP，或在数据库层禁用（需 DBA 操作 `ops.db`）。

---

## 四、服务端监控

### 4.1 在线状态

「服务端」页每行显示：

- **在线**：WS 长连当前是否连接（最近心跳在数秒内）
- **最后心跳时间**：超 30s 未心跳判为离线（backend 侧 25s 心跳）

### 4.2 backend 注册后 WS 断开怎么办

- backend 侧指数退避自动重连，通常自愈。
- 持续断开：看 backend 日志 `opsws` 报错（网络中断、ops 重启、token 失效）。
- token 失效：重新注册（`POST /op/server/register`）拿新 token，或 admin 在前端复制旧 token 重新配。

### 4.3 监控指标

ops-server 自身若暴露 `/metrics`（以代码为准；当前最小版主要靠 `/healthz` + admin API）。核心看：

- **在线服务端数**：dashboard 总览
- **活跃中继会话数**：流量页 `active_sessions`
- **流量趋势**：流量页汇总

backend 侧的 `/metrics`（Prometheus 格式，无认证）见 `RUNBOOK.md` §五。

---

## 五、连接管理（断开会话）

### 5.1 查看活跃会话

「流量」页底部"活跃会话"区，列出 `ended_at` 为空的进行中会话：

| ID | Server | 对端 | 流量 | 开始 | 时长 | 原因 | 操作 |
|---|---|---|---|---|---|---|---|

数据源 `GET /admin/api/sessions/active`。

### 5.2 主动断开

每行有"**断开**"按钮 → 调 `POST /admin/api/session/close`（body `{"session_id":"..."}`）→ ops-server 关闭该中继会话 → 两端收到关闭。

```bash
# 直接 API 调用
ADMIN_TOKEN="<登录 token>"
curl -X POST http://<ops>:8090/admin/api/session/close \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"<会话 ID>"}'
```

### 5.3 何时断开

- 会话异常长时间挂着（疑似僵尸）：断开让两端重连。
- 用户反馈中继卡死：断开后客户端会重新请求连接。
- 维护窗口：批量断开所有活跃会话，升级后自动重连。

### 5.4 会话结束原因（close_reason）

| 原因 | 含义 |
|---|---|
| `normal` | 正常关闭（任端主动断开） |
| `peer_error` | 对端出错 |
| `peer_absent` | 对端从未出现（首端等超 30s 超时） |
| `timeout` | 等待首端超时 |

看「原因」列判断异常。`peer_absent` 多说明一端连不上 backend，查 backend 在线状态。

---

## 六、流量统计解读（含写操作页）

### 6.1 流量页三块内容

1. **按 server 聚合汇总**：`GET /admin/api/traffic/summary`
   - `session_count`：历史会话总数
   - `active_sessions`：当前进行中
   - `total_bytes_in` / `total_bytes_out`：累计入/出字节
2. **最近会话明细**：`GET /admin/api/sessions?limit=100`
   - 单会话 `bytes_in` / `bytes_out`、`started_at`、`ended_at`、`close_reason`
3. **活跃会话**：`GET /admin/api/sessions/active`（可断开）

### 6.2 字段含义

| 字段 | 说明 |
|---|---|
| `bytes_in` | 中继从一端收到的字节数（单会话） |
| `bytes_out` | 中继转发到另一端的字节数（单会话） |
| `total_bytes_in/out` | 某 server 累计（所有会话求和） |
| `session_count` | 某 server 历史会话总数 |
| `active_sessions` | 当前未结束会话数 |
| `started_at` / `ended_at` | 会话起止时间（`ended_at` 空表示进行中） |
| `close_reason` | 结束原因（见 §5.4） |

### 6.3 解读要点

- **bytes_in ≈ bytes_out**：中继正常转发，进出应接近相等（协议开销略有差异）。
- **bytes_in 远大于 bytes_out**：对端接收慢或断开，中继积压。
- **active_sessions 持续增长不回落**：会话没正常关闭，可能有僵尸，考虑批量断开。
- **某 server 流量异常高**：看明细会话对端，判断是否异常大文件传输或滥用。
- **session_count 多但 bytes 很小**：频繁建链但没传数据，可能客户端反复重连（看 `close_reason=peer_absent`）。

### 6.4 写操作（本页新增）

「流量」页的写操作是**断开会话**（`POST /admin/api/session/close`），已在 §五详述。这是运营管理员对中继连接的直接控制手段。

> 注：当前版本流量只**计数**，不做计费/套餐/限流。流量数据用于运营观察与排障。

---

## 七、常用 API 速查（admin）

所有 `POST /admin/api/*` 需带管理员鉴权（登录后的 token，cookie 或 `Authorization: Bearer`）。

| 端点 | 方法 | 用途 |
|---|---|---|
| `/admin/login` | POST | 登录（username/password） |
| `/admin/api/overview` | GET | dashboard 总览 |
| `/admin/api/users` | GET | 运营账号统计 |
| `/admin/api/users/list` | GET | 运营账号列表（含 server_id） |
| `/admin/api/servers` | GET | 已注册服务端列表 |
| `/admin/api/sessions` | GET | 历史会话（?limit=） |
| `/admin/api/sessions/active` | GET | 活跃会话 + 在线设备 |
| `/admin/api/traffic/summary` | GET | 按 server 流量汇总 |
| `/admin/api/account` | GET | 当前登录账号信息 |
| `/admin/api/server/register` | POST | 手动登记服务端 |
| `/admin/api/session/close` | POST | 断开中继会话 |
| `/admin/api/user/bind-server` | POST | 绑定运营账号到 server |

### 7.1 登录拿 token 示例

```bash
curl -X POST http://<ops>:8090/admin/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"AdminStr0ng!8"}'
# → {"token":"...","...":"..."}
```

之后所有 admin API 带 `Authorization: Bearer <token>`。

---

## 八、排障速查

| 现象 | 排查 |
|---|---|
| 登录失败 | 账号密码错；或 `MM_OPS_BOOTSTRAP_ADMIN` 未设且 `signup=off` |
| 「服务端」页空 | 无 backend 注册；检查 backend `MM_OPS_SERVER_URL` 与 ops 可达性 |
| 服务端显示离线 | backend WS 断；看 backend 日志 `opsws` + ops 日志 |
| 流量页空 | 无中继会话发生；让客户端经中继完成一次操作 |
| 断开会话不生效 | 会话已自然结束；刷新页面看 `close_reason` |
| dashboard 不刷新 | 手动刷新页面；或 `GET /admin/api/overview` 直接调 |

运维排障（SQLite、中继连不上、OOM）见 `RUNBOOK.md`。
