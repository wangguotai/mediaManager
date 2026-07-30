# Media Manager — PRD v5.0（体验打磨 + 中继跑通 + 安全加固 + 文档）

> 产品方向：对标「百度网盘 + 小米相册」。在 V4 已搭起的「自托管存储服务端 + 客户端 + 公网运营服务端 + 运营前端」骨架上，**把半成品收口、把核心路径真正跑通、把体验和安全补到位、把文档写出来**。
> 更新时间：2026-07-30
> 基线：V4 已合并 commits（f09eb3c 等）；客户端 auth/sync/file-mgmt/tombstone/dedup 已接入但含多处技术债。

---

## 0. 为什么有 V5：V4「名义完成」与「代码实际」的差距

V4-QA-REPORT 与 V4-MVP-VERIFY 对多项功能打了 ✅，但代码层探查显示若干项**乐观标注**——V5 的第一要务是把这些缺口摆上桌面再补，而不是在虚的地基上加新功能。下表是 V4 实际状态校准（附 `file:line` 锚点），并非否定 V4，而是 V5 的事实起点。

| V4 声称 | 代码实际 | 影响 |
|---|---|---|
| 客户端「上传去重 sha256 秒传」 | 自动备份主路径 `MediaViewModel.kt:563,610` 调 `uploadMedia` **只传 3 参**，sha256/client_id/taken_at 全丢默认值；带全量字段的 `SyncManager.uploadLocal`（`SyncManager.kt:56-86`）是**死代码无调用点** | 自动备份秒传/幂等/时序**实际失效**，每次重传全量字节 |
| 「云相册自动备份」 | 前台进程内 30s 协程轮询（`MediaViewModel.kt:38,467`）；**无 WiFi/充电策略、无 WorkManager、iOS 无后台任务**；进程被杀即停；视频跳过 | 与小米相册「自动备份」体验差距大，切后台/锁屏不备份 |
| 「离线上传队列」 | 实际在用 `UploadQueue` 内存版（`SyncComponents.kt:102`）**杀进程即丢**；持久化 `OfflineQueueStore`（JSON 文件）是**死代码** | 弱网失败的任务进程重启后丢失 |
| iOS「安全存储」 | `SettingsStorage.ios.kt:52` 存明文 `Documents/mm_secure.json`（**非 Keychain**，KDoc 撒谎）；`PersistentFileStore.ios.kt:5` 用 NSUserDefaults（明文、进 iCloud 备份）；Android 有明文降级（`SettingsStorage.android.kt:52-59`） | 凭据/索引未真正加密，与 PRD-v4 §4.1 承诺不符 |
| 运营「设备发现 + WebSocket 长连 / 信令」 | `discovery`/`signaling`/`ws` 三包是**孤儿库无接线**（`main.go` 不 import）；无 `/op/server/register`、无 WS、无设备注册/心跳端点；backend 侧也无注册上报代码 | 路径B（中继）整条链路**本机都跑不起来**，QA ✅ 不实 |
| 后端「软删除墓碑」 | SQLite 层 + `sync/changes` 消费侧实现且有测试；但产出侧 `server.go:492-497` **忽略 MarkDeleted 错误 + 不校验 user_id（横向越权）+ 与物理删除非原子** | 任何已认证用户可对任意 media_id 写墓碑；删后可能无墓碑导致他端不消失 |
| 后端「上传去重 (user_id,sha256)」 | 真实现（`server.go:544-559`），但 `internal/storage` **零索引**（带索引的 `internal/db` 是死代码）；`MaxOpenConns(1)` 全串行写 | 行数增长后线性退化；高频 sync 阻塞所有写 |
| 后端「安全（JWT/鉴权）」 | `config.yaml:38` `jwt_secret:"wangguotai"` 弱口令**入仓**；CORS `*`（`server.go:195`）；**无 rate limit / 无 TLS / 无改密端点**；密码长度下限 4；BootstrapAdmin 明文打密码日志（`main.go:159`） | 暴力破解、跨域、明文密钥泄露风险 |
| 后端「上传」 | REST `/api/media/upload` 整文件入内存 100MB 上限（`server.go:515`）；**无分块/续传**；gRPC 才分块 | 大文件占堆、并发叠加无上限 |
| 后端可观测性 | 仅 `log.Printf` + ad-hoc `/api/stats`、`/healthz`；无结构化日志/request id/Prometheus | 排障靠猜 |

> **V5 定位**：不铺新摊子。把上表每一行收口到「真跑通」，再在稳固地基上做对标百度网盘/小米相册的体验打磨、性能与安全加固，并交付部署文档与用户手册。核心可用路径仍是 PRD-v4 的**路径 A（自托管直连）优先、路径 B（中继）跑通即止**。

---

## 1. V5 原则与非目标

### 1.1 原则
1. **先收口再加新**：技术债务（死代码、孤儿包、横向越权、弱密钥）在加任何新体验前先修。
2. **本机可验优先**：验收主线尽量在单机/局域网可复现；中继「跑通」=本机两端口联调通过 + 公网真机标注，不追高可用。
3. **不动已验证的核心交互**：媒体浏览/编辑/收藏/相册/Live Photo/预览的交互契约不变，仅在数据通路与体验细节上打磨。
4. **零新重型依赖**：不引 WebRTC、不引 SQLDelight（客户端）、不上 SPA 框架。中继仍裸 TCP。

### 1.2 非目标（沿用 V4 §6.3 并补充）
1. 不保历史版本/回收站多代（编辑覆盖即最新）。
2. 不做双向实时编辑协同（增量备份 + 墓碑合并，非 CRDT）。
3. 不做文件夹层级（媒体扁平 + 相册分组）。
4. 不上 WebRTC/P2P（中继保持裸 TCP 信令 + 转发）。
5. 不做计费/套餐/多租户商业化（流量只计数）。
6. 不做账号多开/切换（客户端单账号单服务端）。
7. **不引客户端 SQLite**：本地索引继续用平台键值 + JSON，不引 SQLDelight/Room。
8. **不做大文件分片上传 API 的完整商业化方案**：V5 只把 REST 上传改为流式落盘 + 降内存，不做断点续传协议（非目标，留 V6）。
9. **中继不做 P2P 穿透**：V5 中继 = 发现 + 信令互换候选 + TCP 转发回退，不做 STUN/TURN 候选实测与 NAT 穿透。

---

## 2. 模块一：客户端体验打磨（对标百度网盘 / 小米相册）

> 目标：让「拍 → 自动备份 → 多端可见 → 文件管理」这条主线体验接近小米相册/百度网盘，而不是「能跑但不可靠」。

### 2.1 安全存储归一（收口技术债 #1）
- **iOS Keychain 迁移**：`SettingsStorage.ios.kt` 凭据从明文 `mm_secure.json` 迁回 **Keychain**（`kSecClassGenericPassword`）。修复 KN interop（PRD-v4 §4.1 已承诺但未做）。提供一次性迁移：首次启动读旧 JSON → 写 Keychain → 删 JSON。
- **iOS 索引存储对称化**：`PersistentFileStore.ios.kt` 从 NSUserDefaults 改为 **App 私有目录 JSON 文件**（与 Android `filesDir` 对齐），避免明文进 iCloud 备份。
- **Android 去降级**：`SettingsStorage.android.kt:52-59` 的明文 SharedPreferences 降级改为「记录错误并拒绝写入敏感项」，可用性不再凌驾于加密之上。
- **修文档撒谎**：`AuthState.kt:18`、`SettingsStorage.kt:11-12`、`BackendImageLoader.kt:125-127`（原图降采样）等 KDoc 与实现矛盾的注释一律校正。
- **验收**：iOS keychain 存取真机通过；冷启动 token/user_id 读回成功；旧版用户首次升级迁移无感。

### 2.2 上传路径统一与秒传真正生效（收口技术债 #2，核心）
- **删除死代码**：移除 `SyncManager.uploadLocal` / `replayOfflineQueue`（无调用点）或**接活**它替代 MediaViewModel 的简化路径——二选一，不留两套。V5 决策：**保留并接活 SyncManager 作为唯一上传通路**，删除 MediaViewModel 内联上传，统一经 SyncManager（带 sha256/client_id/taken_at）。
- **自动备份传全量字段**：备份路径必须把 `sha256`（已算好，`MediaViewModel.kt:560`）、`client_id`（device register 的 id）、`taken_at`（EXIF 拍摄时间）透传后端，使 `(user_id, sha256)` 秒传与游标时序对自动备份生效。
- **验收**：A 设备拍一张已存在图 → B 设备同图秒传不传字节（后端日志命中 `GetMediaByUserAndSHA256`）；`taken_at` 在 B 端 grouping 正确归到拍摄日。

### 2.3 自动备份补全（对标小米「云相册自动备份」）
- **网络/电量策略**：设置页增「仅 WiFi 备份」「仅充电备份」开关（默认仅 WiFi）。`checkAndBackupNewLocalMedia` 前置条件检查（Android `ConnectivityManager`/`BatteryManager`；iOS `NWPathMonitor`/`UIDevice.batteryState`）。
- **Android 后台任务**：用 **WorkManager**（`Constraint`：网络、充电可选）替代 `viewModelScope` 30s 轮询，进程被杀可由系统调度唤醒（周期 15min，Wi-Fi+充电约束）。前台快速备份仍走即时协程。
- **iOS 后台任务**：注册 `BGProcessingTaskRequest`（`com.wgt.media.backup`），在系统调度窗口执行增量备份；App 进入后台时 `beginBackgroundTask` 留 30s 窗口收尾。
- **视频备份**：解除「仅图片+Live Photo、视频跳过」（`MediaViewModel.kt:556-557`）的限制，视频纳入备份（大文件走 §2.7 流式）。
- **进度与状态**：通知栏/小部件展示「备份中 N/M · 已暂停（非 WiFi）」；设置页展示「待备份 N 项」「上次备份时间」。
- **验收**：锁屏 + WiFi + 充电 → 拍照 → 不打开 App，15min 内云端可见（Android）；iOS 系统调度后可见。

### 2.4 离线队列持久化（收口技术债 #3）
- **接活持久化队列**：`OfflineQueueStore`（JSON 文件）接为唯一待办表，或把内存 `UploadQueue` 改为持久化后端。决策：**保留 OfflineQueueStore**，SyncManager 失败即入其队，进程重启 `replayOfflineQueue` 重放。
- **`PendingUpload` 补字段**：`SyncComponents.kt:84` 的 `PendingUpload` 补 `takenAt`/`clientId`/`localUri`，避免重放时丢元数据。
- **验收**：弱网拍照几张 → 杀进程 → 恢复网络重启 App → 队列自动重放完成、云端可见。

### 2.5 去重索引归一与墓碑清理（收口技术债 #4）
- **两套合一**：废弃 `DedupStore`（JSON，死代码侧）或 `Sha256Dedup`（逗号串，活侧）之一。决策：**保留 `Sha256Dedup`**（已活、与 SettingsStorage 对齐），删除 `DedupStore`。
- **墓碑清理一致**：当前 `Sha256Dedup`「删除项指纹不剔除」（`SyncComponents.kt:54-67`）导致**删除后重传同图被前端误跳过**。改为：收到墓碑时从去重集合移除该 sha，使删除后可重新上传；后端侧秒传命中软删记录会复活（`server.go:553-556` UndeleteMedia 已支持），前后端语义对齐。
- **验收**：用户在文件管理删一图 → 在本地相册重新触发该图备份 → 云端再次可见（非被前端误判跳过）。

### 2.6 云端分页加载更多（对标百度网盘无限滚动）
- 已上传/网盘 Tab **接 `onLoadMore`**（当前 `MediaListScreen.kt:529,543` 仅 `selectedTab==0` 接）。云端分页基于 `sync/changes` cursor 增量 + `getMediaList` page/pageSize，提供滚动到底续拉。
- `loadUploadedMediaList`（`MediaViewModel.kt:674-703`）当前未传 page（`:692` 只取第 1 页 20 条）——改为分页拉取或彻底由 sync 增量视图驱动（增量视图已是主路径，保留 getMediaList 仅作全量兜底）。
- **验收**：已上传 Tab 滚动到底 → 平滑加载下一批，无卡顿、无重复。

### 2.7 文件管理补齐 + 大图/视频体验
- **批量下载**：`FileManagementScreen.kt` 当前只有批量删除，无下载。补批量下载到本地相册（复用现有下载流 + 写 MediaStore/PHPhotoLibrary），支持「下载原图/仅缩略图预览」。
- **大图降采样**：`BackendImageLoader.kt:136` 实际调无降采样 `decodeImageBitmap(bytes)`（KDoc 撒谎 2048px）。接入真正的降采样解码（按目标尺寸 inSampleSize / UIKit `downsampling`），控内存、防大图 OOM。
- **视频缩略图/首帧**：网格中视频项展示首帧 + 时长标签（后端 `GetVideoInfo` 已有，复用 `prefetchVideoDurations`）。
- **验收**：批量下载 N 张到本地相册成功；50MB 大图预览不 OOM、滑动流畅；视频网格有首帧与时长。

### 2.8 首次同步引导与空/错态（对标小米相册细节）
- 首次登录后「已上传」Tab 增引导态：「正在首次同步云端 N 项…」进度条，而非空白。
- 列表加载失败 `listLoadError` 占位已有（`loadCloudMediaList`），统一三 Tab 的重试交互（一键重试按钮）。
- 自动备份暂停原因可见化（非 WiFi/未充电/队列积压）。
- **验收**：新账号首次进入已上传 Tab 有同步进度；断网后有空态 + 重试。

### 2.9 Android 10+ recoverable deletion 真机验证（收口 V4 遗留）
- `ca16f9b` 已实现待真机验证。V5 在真机走完「本地删除 → 垃圾箱可恢复」链路并归档结果。

---

## 3. 模块二：运营服务端中继实际跑通

> 目标：把 `discovery`/`signaling`/`ws` 三个孤儿库接线，暴露缺失端点，让路径 B「客户端经运营服务端连到自己的存储服务端」**本机两端口联调可复现**。

### 3.1 接线孤儿包 + 暴露端点
- **WebSocket 端点**：加 `coder/websocket` 依赖（当前 `go.mod` 缺），在 ops-server `main.go` 注册 `/op/ws`（客户端）与 `/op/server/ws`（存储服务端）路由，接 `ws.NewHub`。
- **`POST /op/server/register`**：暴露 `auth.RegisterServer`（`auth.go:312` 已实现），存储服务端启动带 `MM_OPS_SERVER_URL` 时自动注册拿 `server_id/server_token`。
- **设备注册/心跳**：暴露 `POST /op/device/register`、`POST /op/device/heartbeat` 接 `discovery.RegisterDevice/Heartbeat`（`discovery.go:72,93`）。
- **信令触发**：WS 长连承载信令：客户端请求连接时，`signaling.Introduce`（`signaling.go:105`）互换候选地址；P2P 候选优先（V5 不实测穿透，候选仅作记录），失败回退 TCP 中继。

### 3.2 backend 侧补注册上报 + 信令客户端
- `backend` 启动时若配 `MM_OPS_SERVER_URL`：调用 `POST /op/server/register` 拿 token（当前两端都没接线），维持 WS 长连 + 心跳上报。
- 路径 B 下客户端经 ops-server 中继连 backend：backend 侧接信令，接受中继 TCP 入 连接（复用现有 REST server socket 或独立中继入口）。

### 3.3 中继骨架瑕疵修复
- `relay.go:359` `finishReason` 恒返回 `"closed"` → 区分 `timeout`/`peer_absent`/`normal`。
- `relay.go:296` 首端等待无超时（无限阻塞）→ 加超时（默认 30s）。

### 3.4 运营前端补写操作
- ops-frontend（vanilla JS）当前只读。补：
  - 「服务端注册」页：手动登记/查看 server、复制 server_token。
  - 「绑定」：op_user 绑定 server_id。
  - 「连接管理」：在线设备/活跃中继会话列表 + **主动断开**（调 relay 关闭）。
- 配 `POST /admin/api/server/register`、`POST /admin/api/session/close` 等写端点（带管理员鉴权）。

### 3.5 端口配置同步
- backend `docker-compose.yml:45` 注释仍写 18789，ops HTTP 已是 8090。统一到 8090（HTTP）+ 18790（relay），并在文档与 `.env.example` 对齐。

### 3.6 验收（本机两端口联调）
1. `docker compose up` ops-server（8090 + 18790）+ backend（8080，配 `MM_OPS_SERVER_URL=http://localhost:8090`）。
2. backend 启动自动 `/op/server/register` → ops 前端「服务端」页可见其在线。
3. 客户端登录 op 账号 → 绑定 server → 请求连接 → 经中继 TCP 18790 转发到 backend:8080。
4. 经中继通道完成一次 `sync/changes` + 上传，ops 前端「流量」页可见 bytes 计数。
5. 公网真机标注：跨网两台真机经公网 ops-server 中继互相同步（标为「需公网验证」项，不阻塞 V5 验收主线）。

---

## 4. 模块三：性能和安全加固

### 4.1 安全（收口技术债 #5，必做）
| 项 | 现状 | V5 目标 |
|---|---|---|
| JWT 密钥 | `config.yaml:38` `"wangguotai"` 入仓 | 仓库内**只留占位**，启动时空则生成随机密钥并提示用户在 config/env 设固定值；严禁明文密钥入仓 |
| 登录暴力 | 无 rate limit | `/api/auth/login` 按 IP+username 限速（如 10次/min），超限 429 |
| 上传滥用 | 无限速 | `/api/media/upload` 按用户限并发/限速 |
| CORS | `*`（`server.go:195`） | 白名单可配（`config.allowed_origins`），默认仅同源 |
| TLS | 无 | 提供反代 TLS 文档 + 可选 `MM_TLS_CERT/KEY` 原生 HTTPS；默认生产必经反代 |
| 改密 | 无端点 | `POST /api/auth/change-password`（PRD-v4 §3.2 已列未做） |
| 密码强度 | 下限 4 | 下限 8 + 复杂度；BootstrapAdmin 密码强制随机 16+ |
| 超管明文日志 | `main.go:159` 明文打密码 | 改为打印「请到 X 查看」或一次性安全文件，不落标准日志 |
| 墓碑横向越权 | `MarkDeleted` 不校验 user_id（`repository.go:215`） | 所有写墓碑/删除/元数据操作强制 `AND user_id=?`；`server.go:492-497` 不忽略错误，删文件与写墓碑在事务/顺序上保证最终一致（先写墓碑成功再删文件，失败可重试） |

### 4.2 性能（收口技术债 #6）
- **DB 索引**：`internal/storage` 补 `CREATE INDEX`：`media(user_id, sha256)`、`media(user_id, deleted, updated_at)`、`device(user_id)`。删除死代码 `internal/db`。
- **写并发**：`MaxOpenConns(1)`（`db.go:101`）改为多连接 + **WAL 模式**（`PRAGMA journal_mode=WAL`）+ `busy_timeout`，解除写串行。
- **REST 上传降内存**：`server.go:515` `io.ReadAll` 整文件入内存改为 **流式落盘**（`io.Copy` 到临时文件再 hash/入库），上限上调或去除 100MB 硬限；大文件不再占等量堆。gRPC 分块路径保留。
- **缩略图缩放**：`nearestNeighbor`（`media_service.go:1608`）纯 Go 单线程像素循环，大图慢 → 限并发（信号量）或对超大图跳过/降级。
- **`/healthz` 全量扫描**（`server.go:693` `countAllUserMedia`）：加缓存或降频，避免每次健康检查 IO 放大且无认证被刷。

### 4.3 可观测性（收口技术债 #7）
- **结构化日志**：引 `log/slog`（stdlib）替换 `log.Printf`，统一 request id 中间件注入。
- **指标**：加 `/metrics`（Prometheus 格式）暴露：请求计数/延迟、上传字节、cache 命中率、sync changes 拉取量、DB 连接池、中继会话/字节。移除或保留 ad-hoc `/api/stats`（保留作兼容）。
- **访问日志**：统一中间件记录 method/path/status/latency/user_id（脱敏）。

### 4.4 同步游标稳健性
- `updated_at` 游标同时间戳边界风险（`repository.go:334-337` 自认）：`next_cursor` 取「本页末条 `(updated_at, id)`」，`since` 比较改为 `(updated_at, id)` 复合严格大于，消除重/漏。wire 协议向下兼容（cursor 可选带 id）。

---

## 5. 模块四：部署文档和用户手册

> 现状：`docs/USAGE.md` 已有基础速查，但偏 API 速查，缺部署运维深度与终端用户引导。V5 交付完整文档集。

### 5.1 交付文档清单
| 文档 | 受众 | 内容要点 |
|---|---|---|
| `docs/DEPLOY-SERVER.md` | 自托管用户 | 存储服务端 Docker 一键 / 裸机 systemd / 配置项表 / 首次超管 / 数据卷与备份 / 升级 / TLS 反代 |
| `docs/DEPLOY-OPS.md` | 运营方 | ops-server 部署（8090+18790）/ 运营账号 / 服务端注册 / 流量看板 / 与 backend 组网 |
| `docs/DEPLOY-COMPOSE.md` | 进阶 | 全栈统一 docker-compose（backend + ops + 反代 + TLS）一键 |
| `docs/USER-GUIDE.md` | 终端用户 | 首次登录（填地址/扫码）/ 三 Tab/自动备份开关（WiFi/充电）/ 文件管理（下载/删除/用量）/ 多设备同步 / 排障（不同步、备份卡住） |
| `docs/OPS-GUIDE.md` | 运营管理员 | 运营前端各页用法 / 注册审批 / 服务端监控 / 连接管理 / 流量统计解读 |
| `docs/RUNBOOK.md` | 运维 | 备份恢复、SQLite 维护（VACUUM/索引）、升级回滚、排障（墓碑不一致、中继连不上、OOM）、监控指标解读 |
| `docs/SECURITY.md` | 所有部署方 | 密钥管理、TLS、CORS、限速、密码策略、凭据存储说明、已知安全边界 |

### 5.2 文档要求
- 每篇含「10 分钟跑通」最小步骤 + 完整参考。
- 命令可直接复制执行，端口/路径与本仓库一致（对齐 §3.5 端口同步）。
- 终端用户文档图文为主、零术语；运维文档含指标阈值与排障决策树。

---

## 6. V5 验收主线（端到端，可执行）

> 主线尽量本机/局域网可验；中继端到端与公网真机项单独标注。

1. **编译**：`cd backend && go build ./...`、`cd ops-server && go build ./...`、`cd frontend && sh gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64` 全通过。
2. **安全基线**：仓库内无明文 JWT 密钥（grep 无 `wangguotai` 等）；未带 token → 401；`/api/auth/login` 暴力限速 → 429；墓碑/删除跨用户操作被拒（403/404，`user_id` 校验生效）。
3. **路径 A 自托管直连**：
   - A 机登录 → 开「仅 WiFi+充电」自动备份 → 拍一张**已存在**图 → B 机秒传（后端日志命中去重，无字节落盘）。
   - 拍一张**新**图 → 锁屏不打开 App → Android 15min 内 / iOS 系统调度后云端可见 → B 机登录同账号自动同步下来。
   - 弱网杀进程后重启 → 离线队列重放完成。
   - 文件管理批量下载 N 张到本地相册、批量删除后两机均不显示（墓碑）。
   - 50MB 大图预览不 OOM；已上传 Tab 无限滚动分页流畅。
4. **路径 B 中继（本机两端口联调）**：按 §3.6 步骤，backend 自动注册 → 客户端经中继 TCP 完成 sync + 上传 → ops 前端流量计数可见。
5. **安全存储**：iOS 凭据在 Keychain、索引在私有文件（非 NSUserDefaults）；Android 无明文降级；旧版升级迁移无感。
6. **可观测性**：`/metrics` 暴露核心指标；结构化日志带 request id。
7. **文档**：§5.1 七篇文档齐全，终端用户按 `USER-GUIDE.md` 可在 10 分钟内完成首次登录+备份。
8. **公网真机标注项**（不阻塞主线）：跨网两真机经公网 ops-server 中继同步；iOS BGProcessingTask 系统调度备份；Android 10+ recoverable deletion。

---

## 7. 与 V4 的关系

V4 搭起了四层骨架（认证 + SQLite + 同步 + Docker + 运营服务端/前端 + 客户端 auth/sync/file-mgmt + tombstone + dedup），但骨架含死代码、孤儿包、横向越权、弱密钥与多处「简化版」未接活路径。V5 **不重写 V4 已有的媒体浏览/编辑/收藏/相册/Live Photo 核心**，而是：

- **收口**：把死代码（SyncManager/OfflineQueueStore/DedupStore/internal-db）、孤儿包（discovery/signaling/ws）、产出侧缺陷（墓碑越权/非原子）、安全基线（弱密钥/无限速/无 TLS）逐条修掉。
- **接活**：自动备份传全量字段使秒传真正生效；WorkManager/BGTask 后台备份；持久化离线队列；中继端到端接线跑通。
- **打磨**：云端分页、批量下载、大图降采样、首次同步引导——把体验从「能跑」推到「接近小米相册/百度网盘」。
- **加固**：索引 + WAL + 流式上传 + 结构化日志 + 指标。
- **成文**：部署/用户/运维/安全文档集。

V5 之后，路径 A 应达到「可交给非技术用户自托管日常使用」的成熟度，路径 B 达到「跨网可连」的最小可用。
