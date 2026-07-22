# OpenClaw Bridge — 设计文档

> 状态：v1（2026-07-22）
> 关联代码：`backend/internal/gateway/server.go`、`backend/cmd/server/main.go`
> 配套 proto：`shared/proto/media.proto`（本任务同时新增 `GetMediaStream` / `GetThumbnail`）

## 1. 背景与目标

media-manager 是 KMP 跨端应用，前端（`frontend/`）通过 gRPC 与 Go 后端（`backend/`）通信。除媒体管理外，产品还需要调用本机运行的 **OpenClaw gateway**（默认 `http://127.0.0.1:18789`）完成系统能力（文件打开、索引、权限等）。

若让 KMP / Web 前端直连 OpenClaw，会有三个痛点：

1. **跨端不一致**：iOS / Android / JVM / Web 各自实现一份 HTTP 客户端，鉴权与错误处理漂移。
2. **拓扑泄露**：前端必须知道"本机 18789"这一私有地址，Dev/Prod 切换困难。
3. **缺乏治理**：没有统一超时、日志、限流入口；OpenClaw 异常直接打到前端。

目标：在 media-manager 后端加 **REST 桥 endpoint** `POST /api/openclaw/command`，把前端对 OpenClaw 的调用收口到 Go 后端。后端充当反向代理 + 治理层，前端只调用 media-manager 一个进程。

非目标：
- 不做 OpenClaw 协议的语义改写（请求/响应字节透传）。
- v1 不引入鉴权、限流、可观测性栈，但预留接入位。
- v1 不处理 OpenClaw 的 WebSocket / SSE 流（仅 plain HTTP 请求/响应）。

## 2. 架构

```
┌─────────────────┐     gRPC :50051     ┌──────────────────┐
│  KMP Frontend   │◄──────────────────►│  Go Backend      │
│  (Compose UI)   │                     │  - MediaService   │
│                 │     REST :8080      │  - OpenClaw Bridge│
│                 │◄──────────────────►│                    │
└─────────────────┘                     └────────┬─────────┘
                                                  │ HTTP (127.0.0.1)
                                                  ▼
                                        ┌──────────────────┐
                                        │  OpenClaw Gateway │
                                        │  127.0.0.1:18789  │
                                        └──────────────────┘
```

- gRPC `:50051` 保持原职责（媒体 CRUD + 新增的流/缩略图）。
- REST `:8080` 由 `internal/gateway.Server` 提供，两个路由：
  - `POST /api/openclaw/command` —— OpenClaw 桥
  - `GET /healthz` —— 存活探针
- 两个监听器在 `main.go` 中一起启动；gRPC 跑在 goroutine，REST 在主线程阻塞。

## 3. REST 契约

### 3.1 健康检查

```
GET /healthz
→ 200 "ok"
```

### 3.2 发送命令到 OpenClaw

```
POST /api/openclaw/command
Content-Type: application/json

{
  "path": "/api/v1/chat",
  "method": "POST",
  "body": { "message": "帮我搜索天气" }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | 是 | OpenClaw gateway 下的路径，必须以 `/` 开头，不允许包含 `..` |
| `method` | string | 否 | 大写 HTTP method；仅允许 `GET/POST/PUT/PATCH/DELETE`；缺省 `POST` |
| `body` | any(JSON) | 否 | 透传给上游的 JSON 体；为空则不带 body |

`path` 拼接规则：`strings.TrimRight(BaseURL, "/") + path`。
例：`BaseURL=http://127.0.0.1:18789`, `path=/api/v1/chat` → `http://127.0.0.1:18789/api/v1/chat`。

### 3.3 响应

桥统一以 HTTP 200 返回业务层结果，真正的上游状态码放在 JSON `status` 字段里。前端只需解析一种结构。

**JSON 响应：**
```json
{
  "status": 200,
  "content_type": "application/json",
  "body": { "ok": true },
  "upstream": "http://127.0.0.1:18789/api/v1/chat"
}
```

**非 JSON 响应：**
```json
{
  "status": 404,
  "content_type": "text/plain",
  "raw_body": "not found",
  "upstream": "http://127.0.0.1:18789/unknown"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | int | 上游响应状态码 |
| `content_type` | string | 上游 `Content-Type`（原样透传） |
| `body` | any(JSON) 或缺省 | 上游为 JSON 时（`application/json` 或 `+json`）作为结构化 JSON 透传 |
| `raw_body` | string 或缺省 | 上游非 JSON 时作为字符串透传 |
| `upstream` | string | 实际访问的上游 URL，便于排查 |

### 3.4 错误模型

桥自身错误（非上游错误）用非 200 状态码 + `{"error":"..."}` 返回：

| media-manager 状态码 | 触发条件 |
|---|---|
| 405 | 非 `POST` 调用该路由 |
| 400 | 请求体不是合法 JSON；`path` 非法；`method` 不在允许列表 |
| 500 | `OPENCLAW_GATEWAY_URL` 未配置或不是 http/https |
| 502 | 连不上 OpenClaw（连接拒绝、DNS、超时等）；附带 `detail` |

上游错误（例如 OpenClaw 返回 404/500）**不是**桥的错误，会以 200 + `status: <原码>` 透传给前端，前端按 `status` 字段自行分支。

## 4. 配置

通过环境变量配置，便于容器化与本地切换：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `REST_PORT` | `:8080` | REST 网关监听端口 |
| `OPENCLAW_GATEWAY_URL` | `http://127.0.0.1:18789` | OpenClaw gateway 基址 |
| （代码常量）`OpenClawConfig.Timeout` | `10s` | 单次上游请求超时；未来可改为 env |

## 5. 关键实现点

文件：`backend/internal/gateway/server.go`

- **反向代理 vs 透传 JSON**：未使用 `httputil.ReverseProxy`，因为我们要把上游响应重新包装成统一 schema（`status` + `body` + `upstream`），并区分 JSON / 非 JSON body。手写 `http.Client` + `io.LimitReader` 更直观。
- **超时**：`http.Client{Timeout: cfg.Timeout}` 控制整体；同时 `context.WithTimeout(r.Context(), ...)` 让取消可传播。
- **body 大小限制**：上游响应体被 `io.LimitReader(resp.Body, 8<<20)`（8 MiB）封顶，避免恶意/异常上游把内存吃满。
- **路径安全**：强制 `path` 以 `/` 开头且不含 `..`，防止目录穿越到 BaseURL 之外。
- **method 白名单**：仅放行 RESTful 5 个方法，阻止 `CONNECT`/`TRACE` 等。
- **Content-Type 透传策略**：上游 `Content-Type` 以原样字符串放进响应，前端可自行判断；`body` 是否结构化由 `isJSONContentType` 决定（`application/json` 或 `+json` 后缀）。
- **请求体来源**：`json.RawMessage` 而非 `map[string]any`，保证上游收到的是字节级原样 JSON，不重塑字段顺序或数字精度。
- **`ErrUpstreamUnavailable` sentinel**：已导出，供未来包引用稳定错误类型，而不必导入整个 handler。

## 6. 安全边界

- 桥只监听本机或内网；公网部署应在前置网关加鉴权（v1 不内建）。
- `OPENCLAW_GATEWAY_URL` 应只指向可信的 OpenClaw 实例；不得由前端传入（否则形成 SSRF）。
- v1 不做请求/响应日志记录；接入可观测栈前需评估是否含敏感字段（OpenClaw 可能返回文件路径、权限信息等）。
- body 大小封顶 8 MiB；若未来有合法大响应（如列表批量返回），需要显式调高并加分页。

## 7. 失败模式与处理

| 场景 | 桥行为 |
|---|---|
| OpenClaw 未运行 | 502 `failed to reach openclaw gateway` + detail |
| OpenClaw 响应超时 | 502（由 `http.Client.Timeout` 触发） |
| 上游 4xx/5xx | 200 透传，`status` 字段 = 原码 |
| 上游返回非 JSON | 200 透传到 `raw_body` |
| 上游 body > 8 MiB | 截断；后续若需要，应在 Content-Length 头校验阶段就拒绝 |
| 客户端 `path` 非法 | 400 `path must start with '/' ...` |
| 客户端 `method` 非法 | 400 `method not allowed` |
| BaseURL 未配置/非法 | 500 `openclaw base url not configured` |

## 8. 前端集成示例

```kotlin
// KMP 端调用 OpenClaw
suspend fun sendCommand(path: String, body: JsonObject): JsonObject {
    val response = httpClient.post("$baseUrl/api/openclaw/command") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("path", path)
            put("method", "POST")
            put("body", body)
        })
    }
    return response.body()
}
```

前端只看到一种响应 schema；失败分支按 `status` 字段（上游原码）处理，桥自身错误按 HTTP 非 200 处理。

## 9. 演进路线

1. **SSE 流式**：支持 OpenClaw 的流式响应（新增 `POST /api/openclaw/stream`，后端做双向流转发）。
2. **认证透传**：前端携带 token，bridge 转发到 OpenClaw（`X-Media-Manager-Token` 或 mTLS）。
3. **WebSocket**：实时双向通信。
4. **命令白名单**：把允许的 `path` 前缀列成配置，限制前端可调用的 OpenClaw 端点，降低 SSRF 面。
5. **可观测**：结构化日志（`path/method/status/duration`），prometheus 指标。
6. **gRPC↔REST 一致化**：若需求增加，用 grpc-gateway 自动从 proto 生成 REST，统一错误模型。

## 10. 验证清单

- [x] `go build ./...` 通过
- [x] `go vet ./...` 通过
- [x] `gofmt -l .` 无输出
- [x] `media.proto` 新增 `GetMediaStream` / `GetThumbnail` 与对应 message
- [x] `protoc` 重新生成 `backend/gen/{media.pb.go, media_grpc.pb.go}`
- [x] `MediaService` 实现两个新 RPC
- [x] `gateway.Server` 实现 `POST /api/openclaw/command` + `/healthz`
- [x] `main.go` 启动 gRPC + REST 双监听
