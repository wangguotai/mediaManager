# Media Manager — PRD v6.0（V5 sprint 收尾 + 漏项补全 + 端到端验收）

> 产品方向不变：对标「百度网盘 + 小米相册」。本文档是给下一位执行者（hermes）的干净交接。
> 更新时间：2026-07-30
> 基线：main ef76b26（V5 sprint 已完成 6/7 粗任务，main 干净，无残留 agent 分支）。

---

## 0. 这份 PRD 的来由

V5 sprint 用「Claude 自己接管 orchestrator 调度」的方式跑了 7 个粗任务，已完成 6 个（见 §1 已完成清单）。但 PRD-v5 的章节里还有一大批细项没被 sprint 的粗任务覆盖——sprint 任务是粗粒度收口，漏掉了若干 PRD-v5 明列的体验/性能/可观测/前端写操作项。

V6 不开新摊子，只做两件事：

1. 把 V5 sprint 漏掉的细项补完（§2，逐条带实测 file:line 锚点）。
2. 端到端验收 + 文档交付（§3 v5-docs、§4 v5-qa），把 V5 推到「可交给非技术用户自托管日常使用」的成熟度。

接手者第一动作：先 git log --oneline -10 看 main 现状，再按 §2 逐条 grep 校验锚点（PRD 行号偶有漂移，以实测为准），然后动手。DAG 与任务拆分建议见 §5。

---

## 1. V5 sprint 已完成（基线事实，别重做）

| 任务 | commit | 实测成果 |
|---|---|---|
| v5-security | dc0e7da | jwt 留空启动随机化+清明文；CORS 收紧为 localhost+RFC1918 回显具体 Origin；密码长度 4→8；POST /api/auth/change-password；BootstrapAdmin 不打明文密码只打 token；MarkDeletedForUser(userID,id) 双键校验防横向越权 |
| v5-ios-keychain | ef27ad2 | iOS 凭据明文 JSON→Keychain（kSecClassGenericPassword+CFDictionary+memScoped+CFRelease 全路径释放）；首次启动迁移 mm_secure.json→Keychain→删文件（幂等）；PersistentFileStore.ios NSUserDefaults→Documents JSON 文件；Android 去明文降级（Keystore 失败拒写敏感项） |
| v5-upload-path | 5a6e776 | 自动备份统一经 SyncManager.uploadLocal（传 sha256/client_id/taken_at）使秒传生效；接活 uploadLocal+replayOfflineQueue（非死代码）；修复隐藏缺陷：原 uploadMedia 吞异常返回 false 致弱网失败永不入队→现 success==false 即入 OfflineQueueStore；OfflineQueueStore 成唯一持久化待办表，内存 UploadQueue 退化为只读 UI 投影，PendingUpload 补 takenAt/clientId |
| v5-perf | 7fef3b2 | SQLite 补 4 索引（media(user_id,sha256)/(user_id,deleted,updated_at)/(user_id,updated_at)、device(user_id)）；MaxOpenConns 1→10+WAL 保留；REST 上传 io.ReadAll→流式 io.Copy 到临时文件+流式 hash+原子 rename；删 internal/db/ 死代码（-315 行）；healthz 30s TTL 缓存降频 |
| v5-ops-relay | ef76b26 | ops-server 接线 discovery/signaling/ws 三孤儿包+加 coder/websocket v1.8.15；暴露 POST /op/server/register、POST /op/device/{register,heartbeat}、GET /op/device/list、WS /op/ws、WS /op/server/ws；backend 新增 internal/opsclient/ 启动注册上报+WS 长连+25s 心跳+指数退避重连；relay.go finishReason 区分 normal/peer_error/peer_absent、waitPaired 加 30s 超时；端口统一 8090(HTTP)+18790(relay) |

**V5 sprint 未做的 2 个粗任务**（仍在 .agents/tasks-v5-sprint.yaml 里，V6 要做）：
- v5-docs（文档集，7 篇）→ 见 §3
- v5-qa（全量验收报告）→ 见 §4

---

## 2. V5 sprint 漏掉的细项补全（核心工作）

> 以下每条都经实测锚点核对（2026-07-30 @ ef76b26）。行号可能随后续改动漂移，动手前先 grep 复核。

### 2.1 自动备份补全（对标小米「云相册自动备份」，PRD-v5 §2.3 — 整段未做）

现状：`MediaViewModel.kt:469` autoBackupJob = viewModelScope.launch{...} 是前台进程内 30s 协程轮询（`:38` AUTO_BACKUP_INTERVAL_MS=30_000，`:477` delay）。无 WiFi/充电策略、无 WorkManager/iOS 后台任务；`:566` `if (m.type == MediaType.VIDEO) continue` 显式跳过视频；进程被杀即停。

待办：
1. 网络电量策略：设置页增「仅 WiFi 备份」「仅充电备份」开关（默认仅 WiFi）。checkAndBackupNewLocalMedia 前置条件检查（Android ConnectivityManager/BatteryManager；iOS NWPathMonitor/UIDevice.batteryState）。
2. Android 后台任务：用 WorkManager（Constraint：网络、充电可选）替代 viewModelScope 30s 轮询，周期 15min，进程被杀可由系统调度唤醒。前台快速备份仍走即时协程。
3. iOS 后台任务：注册 BGProcessingTaskRequest（com.wgt.media.backup），系统调度窗口执行增量备份；App 入后台 beginBackgroundTask 留 30s 窗口收尾。
4. 视频备份：解除 :566 的 `if (m.type == MediaType.VIDEO) continue`，视频纳入备份（大文件走 §2.5 流式，后端已流式落盘）。
5. 进度与状态：通知栏/小部件展示「备份中 N/M · 已暂停（非 WiFi）」；设置页展示「待备份 N 项」「上次备份时间」。
验收：锁屏+WiFi+充电→拍照→不开 App，15min 内云端可见（Android）；iOS 系统调度后可见。

### 2.2 去重两套合一 + 语义对齐（PRD-v5 §2.5）

现状（实测，注意与 PRD-v5 原文有出入）：
- `SyncComponents.kt:23` Sha256Dedup（逗号串持久化经 SettingsStorage.UPLOADED_SHA256）— MediaViewModel 灌入此集合
- `DedupStore.kt:29` DedupStore（JSON 数组 dedup_sha256.json 经 PersistentFileStore）— SyncManager.uploadLocal 用此；**且已实现墓碑剔除**（注释明确「墓碑 deleted=true 条目从集合移除其 sha」），刷新 sha256 字段时 UndeleteMedia 会复活
- 两套并存，双写、最终一致，但分散在两个类，语义重复

待办：废弃 DedupStore 或 Sha256Dedup 之一，决策保留 Sha256Dedup（已活、与 SettingsStorage 对齐、MediaViewModel 主用）。删除 DedupStore，把它的「墓碑剔除」语义并入 Sha256Dedup（接收墓碑时移除该 sha，使删除后可重新上传；后端秒传命中软删记录会复活，server.go UndeleteMedia 已支持）。前后端语义对齐。
验收：删除一图→在本地相册重新触发该图备份→云端再次可见（非被前端误判跳过）。

### 2.3 云端分页加载更多（PRD-v5 §2.6）

现状：`MediaListScreen.kt:529,543` onLoadMore 仅 selectedTab==0（本地相册）接 loadMoreGallery，云端 Tab（1 已上传、2 网盘）不接。`MediaViewModel.kt:673` loadUploadedMediaList 只调 `MediaService.getMediaList()` 不传 page（全量兜底）。

待办：已上传/网盘 Tab 接 onLoadMore。云端分页基于 sync/changes cursor 增量 + getMediaList page/pageSize，滚动到底续拉。loadUploadedMediaList 改分页拉取或彻底由 sync 增量视图驱动（增量视图已是主路径，MediaViewModel.kt:369 getSyncChanges since=cursor，:381 hasMore 判断——保留 getMediaList 仅作全量兜底）。
验收：已上传 Tab 滚动到底→平滑加载下一批，无卡顿、无重复。

### 2.4 文件管理补齐 + 大图/视频体验（PRD-v5 §2.7）

现状实测：
- `FileManagementScreen.kt` 只有批量删除，无批量下载（grep download 无命中）
- `BackendImageLoader.kt:112` loadThumbnail 调 `decodeImageBitmap(bytes)` 不降采样；`:136` loadFullImage **KDoc 撒谎**（注释 L124-126 说「用 decodeImageBitmapDownsampled 将长边限制在 2048px」但 L136 实际调 `decodeImageBitmap(bytes)` 无降采样）→ 大图全尺寸解码，OOM 风险
- 视频首帧/时长：MediaViewModel.kt:274 prefetchVideoDurations 已有（后端 GetVideoInfo 复用），网格时长标签已部分支持

待办：
1. 批量下载：FileManagementScreen 补批量下载到本地相册（复用现有下载流 + 写 MediaStore/PHPhotoLibrary），支持「下载原图/仅缩略图预览」。
2. 大图降采样真正生效：BackendImageLoader.loadFullImage 改调 `decodeImageBitmapDownsampled`（按 FULL_IMAGE_MAX_DIMENSION=2048 的 inSampleSize / UIKit downsampling），让注释承诺的降采样真正生效，控内存防大图 OOM。
3. 视频缩略图首帧：网格中视频项展示首帧 + 时长标签（后端 thumbnail 对视频已抽第 1s 第一帧，复用 loadThumbnail 即可；确认网格项对 VIDEO 类型走首帧缩略图而非占位）。
验收：批量下载 N 张到本地相册成功；50MB 大图预览不 OOM、滑动流畅；视频网格有首帧与时长。

### 2.5 后端上传降内存的收尾（PRD-v5 §4.2 续）

v5-perf 已把 REST 上传改流式落盘。剩：
- `media_service.go:1608` 附近 nearestNeighbor 纯 Go 单线程像素循环，大图缩略图慢 → 限并发（信号量）或对超大图跳过/降级。
- 确认流式上传的临时文件在所有路径（含秒传命中、错误）都清理（v5-perf 已加 cleanupTmp，复核异常路径无残留）。

### 2.6 可观测性（PRD-v5 §4.3 — 整段未做）

现状：仅 log.Printf（main.go 多处）+ ad-hoc /api/stats、/healthz。无结构化日志、无 request id、无 Prometheus。

待办：
1. 结构化日志：引 log/slog（stdlib）替换 log.Printf，统一 request id 中间件注入。
2. 指标：加 /metrics（Prometheus 格式）暴露请求计数/延迟、上传字节、cache 命中率、sync changes 拉取量、DB 连接池、中继会话/字节。/api/stats 保留作兼容。
3. 访问日志：统一中间件记录 method/path/status/latency/user_id（脱敏）。
验收：/metrics 暴露核心指标；结构化日志带 request id。

### 2.7 安全剩余项（PRD-v5 §4.1 续，v5-security 未覆盖）

v5-security 做了 jwt/CORS/密码/change-password/越权。剩：
- 登录暴力限速：/api/auth/login 按 IP+username 限速（如 10 次/min），超限 429。当前无限速（gateway/server.go grep rate/limiter 无命中）。
- 上传滥用限速：/api/media/upload 按用户限并发/限速。
- TLS：提供反代 TLS 文档（落到 §3 文档）+ 可选 MM_TLS_CERT/KEY 原生 HTTPS；默认生产必经反代。
- 密码复杂度：v5-security 只改了长度 8，PRD 要求加复杂度；BootstrapAdmin 密码强制随机 16+（v5-security 已是随机，确认长度）。
- 同步游标稳健性（PRD §4.4）：repository.go ListMediaChanges（:370）当前 updated_at 严格大于 sinceCursor，同时间戳边界有重/漏风险。改为 (updated_at, id) 复合严格大于，next_cursor 取本页末条 (updated_at, id)；wire 协议向下兼容（cursor 可选带 id）。

### 2.8 运营前端补写操作（PRD-v5 §3.4 — v5-ops-relay 明确未做）

现状：ops-server/internal/admin/static/ 有 index/dashboard/servers/users/traffic.html，vanilla JS，只读（fetch 多为 GET）。v5-ops-relay 只接线后端端点，没碰前端写操作。

待办补写：
- servers.html：手动登记/查看 server、复制 server_token（调 POST /op/server/register 或 admin 端点）。
- 绑定：op_user 绑定 server_id。
- 连接管理：在线设备/活跃中继会话列表 + 主动断开（调 relay 关闭）。traffic.html 已展示 close_reason（:130），补断开按钮。
- 配后端写端点：POST /admin/api/server/register、POST /admin/api/session/close 等（带管理员鉴权）。

### 2.9 Android 10+ recoverable deletion 真机验证（PRD-v5 §2.9，V4 遗留）

ca16f9b 已实现待真机验证。V5/V6 在真机走完「本地删除→垃圾箱可恢复」链路并归档结果（不阻塞主线）。

---

## 3. v5-docs：交付文档集（PRD-v5 §5）

> 现状：docs/USAGE.md 已有基础速查（偏 API 速查），缺部署运维深度与终端用户引导。读现有 USAGE.md/ARCHITECTURE.md 作为基础扩展。

交付清单（每篇含「10 分钟跑通」最小步骤 + 完整参考；命令可直接复制执行，端口/路径与仓库一致——对齐 §2.8 的 8090/18790）：

| 文档 | 受众 | 内容要点 |
|---|---|---|
| docs/DEPLOY-SERVER.md | 自托管用户 | 存储服务端 Docker 一键 / 裸机 systemd / 配置项表 / 首次超管 / 数据卷与备份 / 升级 / TLS 反代 |
| docs/DEPLOY-OPS.md | 运营方 | ops-server 部署（8090+18790）/ 运营账号 / 服务端注册 / 流量看板 / 与 backend 组网 |
| docs/DEPLOY-COMPOSE.md | 进阶 | 全栈统一 docker-compose（backend + ops + 反代 + TLS）一键 |
| docs/USER-GUIDE.md | 终端用户 | 首次登录（填地址/扫码）/ 三 Tab / 自动备份开关（WiFi/充电）/ 文件管理（下载/删除/用量）/ 多设备同步 / 排障（不同步、备份卡住）。图文为主、零术语 |
| docs/OPS-GUIDE.md | 运营管理员 | 运营前端各页用法 / 注册审批 / 服务端监控 / 连接管理 / 流量统计解读（含 §2.8 新增写操作页） |
| docs/RUNBOOK.md | 运维 | 备份恢复、SQLite 维护（VACUUM/索引）、升级回滚、排障（墓碑不一致、中继连不上、OOM）、监控指标解读（配合 §2.6 /metrics） |
| docs/SECURITY.md | 所有部署方 | 密钥管理、TLS、CORS、限速、密码策略、凭据存储说明（Keychain/EncryptedSharedPreferences）、已知安全边界 |

---

## 4. v5-qa：V5 全量验收（PRD-v5 §6 验收主线）

输出报告到 docs/V5-QA-REPORT.md。主线尽量本机/局域网可验；中继端到端与公网真机项单独标注。

1. 编译：cd backend && go build ./... ；cd ops-server && go build ./... ；cd frontend && sh gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64 全通过。
2. 安全基线：仓库内无明文 JWT 密钥（grep 无 wangguotai）；未带 token→401；/api/auth/login 暴力限速→429（依赖 §2.7）；墓碑/删除跨用户操作被拒（403/404，user_id 校验生效——验 MarkDeletedForUser）。
3. 路径 A 自托管直连：
   - A 机登录→开「仅 WiFi+充电」自动备份→拍一张已存在图→B 机秒传（后端日志命中 GetMediaByUserAndSHA256，无字节落盘）。
   - 拍一张新图→锁屏不开 App→Android 15min 内 / iOS 系统调度后云端可见→B 机登录同账号自动同步下来。
   - 弱网杀进程后重启→离线队列重放完成。
   - 文件管理批量下载 N 张到本地相册、批量删除后两机均不显示（墓碑）。
   - 50MB 大图预览不 OOM；已上传 Tab 无限滚动分页流畅。
4. 路径 B 中继（本机两端口联调）：docker compose up ops-server（8090+18790）+ backend（8080，配 MM_OPS_SERVER_URL=http://localhost:8090）。backend 启动自动 /op/server/register→ops 前端「服务端」页可见在线。客户端登录 op 账号→绑定 server→请求连接→经中继 TCP 18790 转发到 backend:8080。经中继通道完成一次 sync/changes + 上传→ops 前端「流量」页可见 bytes 计数。
5. 安全存储：iOS 凭据在 Keychain、索引在私有文件（非 NSUserDefaults）；Android 无明文降级；旧版升级迁移无感。
6. 可观测性：/metrics 暴露核心指标；结构化日志带 request id（依赖 §2.6）。
7. 文档：§3 七篇齐全，终端用户按 USER-GUIDE.md 可在 10 分钟内完成首次登录+备份。
8. 公网真机标注项（不阻塞主线）：跨网两真机经公网 ops-server 中继同步；iOS BGProcessingTask 系统调度备份；Android 10+ recoverable deletion。

---

## 5. DAG 任务拆分建议（给 hermes）

沿用 .agents/tasks-v5-sprint.yaml 的 DAG + worktree 隔离 + 串行 merge gate 模式（main 永远可构建）。建议拆分（按文件边界分摊，降低并行冲突）：

Layer 0（无依赖，并行）：
- v6-auto-backup（frontend/）§2.1：WorkManager + iOS BGTask + 视频备份 + WiFi/充电策略
- v6-observability（backend/）§2.6：slog + request id + /metrics + 访问日志
- v6-security-rest（backend/）§2.7：限速 login/upload + 同步游标 (updated_at,id) 复合

Layer 1（依赖 Layer0，并行）：
- v6-dedup-merge（frontend/）§2.2：两套合一（依赖 v6-auto-backup 对备份路径的改动稳定）
- v6-pagination（frontend/）§2.3：云端分页 onLoadMore
- v6-file-mgmt（frontend/）§2.4：批量下载 + 大图降采样真正生效 + 视频首帧
- v6-ops-frontend（ops-server/）§2.8：运营前端写操作 + admin 写端点

Layer 2（依赖 Layer1）：
- v5-docs（docs/）§3：七篇文档（依赖上述改动定型）

Layer 3（依赖 Layer2）：
- v5-qa（docs/）§4：全量验收报告

注意：v6-auto-backup、v6-dedup-merge、v6-pagination、v6-file-mgmt 都改 frontend/，文件边界重叠，不建议同层真并行——建议按 §5 的分层串行化或用 shared_files 预警 + merge gate rebase 兜底。V5 sprint 里 ops-relay 与 perf 同改 backend/ 靠「改不同函数」错开无冲突，frontend 这几个若改同一文件（MediaViewModel.kt/MediaListScreen.kt）需更小心，建议分到不同层。

每任务验证命令（merge gate 用）：
- backend：cd backend && go build ./... && go test ./...
- ops-server：cd ops-server && go build ./...
- frontend：cd frontend && sh gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64

---

## 6. 非目标与交接备忘

### 非目标（沿用 PRD-v5 §1.2）
不引 SQLDelight/Room、不上 WebRTC、不引 SPA、中继不实测 NAT 穿透、不做断点续传协议、不做历史版本/回收站多代、不触已验证的媒体浏览/编辑/收藏/Live Photo 核心交互契约。

### 交接备忘（实测所得，避坑）
1. orchestrator 目录 /Users/wgt/ai/agent-orchestrator 带尾随空格，命令须整体加引号。
2. PRD-v5 的 file:line 偶有漂移（如 MarkDeleted 实际在 internal/storage/repository.go 非 internal/repository/）。所有锚点动手前 grep 复核。
3. 去重有两套（Sha256Dedup + DedupStore），DedupStore 已实现墓碑剔除——别误以为没做。
4. v5-perf 已有 WAL（db.go:94 DSN 已含 journal_mode(WAL)），别重复加；internal/db 死代码已删。
5. v5-ops-relay 后端接线完成但**没做端到端联调自动化测试**（只冒烟），§4 验收主线 4 要真跑 docker compose 两端口联调。
6. 残留（非本 sprint，未动）：外部 worktree media-manager-worktree-claude（fix/3-issues-claude 分支）、3 个更早遗留 git stash。
7. V5 sprint 用「Claude 自己当调度器 + Agent 工具派发子智能体 + worktree 隔离 + 串行 merge gate」跑通，未用 orchestrator run 黑盒——若 hermes 用 orchestrator，tasks.yaml 格式与 DAG 照旧。
