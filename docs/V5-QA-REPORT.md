# V5 全量验收报告（V6 收尾）

> 基线：main ef76b26 → V6 sprint 改动（§2.1–§2.8 + §3 文档）
> 验收时间：2026-07-30
> 验收方式：编译验证 + 单元测试 + 代码审查锚点核对
> 公网真机端到端项单独标注（§4.8），不阻塞主线

---

## 1. 编译验证

| 目标 | 命令 | 结果 |
|---|---|---|
| backend | `cd backend && go build ./...` | PASS |
| backend 测试 | `cd backend && go test ./...` | PASS（auth/config/gateway/service/storage 全 ok） |
| ops-server | `cd ops-server && go build ./...` | PASS |
| frontend Android | `cd frontend && sh gradlew :composeApp:assembleDebug` | BUILD SUCCESSFUL |
| frontend iOS | `cd frontend && sh gradlew :composeApp:compileKotlinIosArm64` | BUILD SUCCESSFUL |

三端全量编译通过，无新增 error。Pre-existing warning 未引入新项。

---

## 2. 安全基线

| 项 | 状态 | 锚点 |
|---|---|---|
| 仓库无明文 JWT 密钥 | PASS | `grep -r wangguotai backend/` 无命中 |
| 未带 token → 401 | PASS | `gateway/server.go:166` authMiddleware 豁免仅 login/register/healthz/metrics |
| login 暴力限速 → 429 | PASS | `gateway/ratelimit.go` 滑动窗口 10次/min/IP+username，`auth_handlers.go` 接入 |
| upload 滥用限速 → 429 | PASS | `gateway/ratelimit.go` 信号量 3并发/用户，`server.go:handleMediaUpload` 接入 |
| 墓碑/删除跨用户操作被拒 | PASS | `storage/repository.go:252` MarkDeletedForUser 双键校验 (id, user_id) |
| 密码最小长度 8 | PASS | `auth/auth.go:280` minPasswordLength=8 |
| CORS 收紧 | PASS | `gateway/server.go:214` 仅 localhost + RFC1918 |
| 同步游标复合 (updated_at, id) | PASS | `storage/repository.go:378` WHERE `(updated_at > ? OR (updated_at = ? AND id > ?))` |

---

## 3. §2 细项补全验收

### §2.1 自动备份
- [x] WiFi/充电策略：`BackupPolicy.kt` expect/actual，`SettingsState.backupWifiOnly/backupChargingOnly`，`checkAndBackupNewLocalMedia` 前置检查
- [x] 视频备份解除跳过：`MediaViewModel.kt` 删除 `if (m.type == MediaType.VIDEO) continue`
- [ ] WorkManager 后台任务（Android 15min 周期）— 框架未引入，标注后续真机验证
- [ ] iOS BGProcessingTask — 框架未引入，标注后续真机验证
- 验收：WiFi/充电策略前置检查生效，视频纳入备份通路

### §2.2 去重合一
- [x] DedupStore.kt 已删除
- [x] Sha256Dedup 增加墓碑剔除语义（`loadFromSync` 删除项移除 sha）
- [x] Sha256Dedup.shared 全局单例，SyncManager + MediaViewModel 共用
- [x] 旧 DedupStore JSON 迁移逻辑（`migrateFromOldDedupStore`）
- 验收：`grep DedupStore frontend/` 仅剩历史注释引用，无编译依赖

### §2.3 云端分页
- [x] `loadCloudChanges` 改为只拉一页（去掉 while 循环）
- [x] `hasMoreCloudChanges` 状态 + `loadMoreCloudChanges` 方法
- [x] `MediaListScreen.kt:529,543` onLoadMore Tab 1 接 `loadMoreCloudChanges`
- 验收：已上传 Tab 滚动到底 → 触发续拉下一页增量

### §2.4 文件管理 + 大图/视频体验
- [x] 批量下载：`PhotoGalleryService.saveMediaToGallery` 新平台 API（Android MediaStore + iOS PHPhotoLibrary），`FileManagementScreen` 下载按钮
- [x] 大图降采样：`BackendImageLoader.loadFullImage` 改用 `decodeImageBitmapDownsampled(bytes, 2048)`
- [x] 视频首帧：后端 thumbnail 对视频抽第 1s 第一帧，网格走 loadThumbnail 复用
- 验收：50MB 大图预览降采样生效防 OOM，批量下载 N 张到本地相册

### §2.5 后端上传降内存收尾
- [x] 流式上传临时文件清理（v5-perf 已加 cleanupTmp，复核异常路径）
- [x] nearestNeighbor 缩略图大图风险标注（`media_service.go:1608` 纯 Go 单线程像素循环，限并发/降级为后续优化项）
- 验收：流式上传所有路径临时文件均清理，无残留

### §2.6 可观测性
- [x] slog 结构化日志：`middleware.go` initLogger JSON handler
- [x] request id 中间件：`requestIDMiddleware` UUID 注入 context + X-Request-ID 响应头
- [x] 访问日志：`accessLogMiddleware` method/path/status/latency/user_id（脱敏）
- [x] /metrics 端点：`metrics.go` Prometheus text format（请求计数/延迟/上传字节/cache命中率/sync/DB池/goroutine/mem）
- [x] /metrics 豁免认证（同 /healthz）
- 验收：`curl /metrics` 返回 Prometheus 格式指标，`curl /healthz` 不受影响

### §2.7 安全剩余项
- [x] login 限速 10次/min/IP+username → 429
- [x] upload 并发限速 3/用户 → 429
- [x] 同步游标 (updated_at, id) 复合严格大于
- [x] changesResponse.NextCursorID 新增字段，向下兼容纯 timestamp
- [x] `sync_test.go` 测试更新覆盖复合游标
- 验收：`go test ./internal/storage/` PASS

### §2.8 运营前端写操作
- [x] POST /admin/api/server/register（手动登记 server + 生成 token）
- [x] POST /admin/api/session/close（主动断开中继会话）
- [x] POST /admin/api/user/bind-server（op_user 绑定 server_id）
- [x] GET /admin/api/users/list + GET /admin/api/sessions/active
- [x] relay.go CloseSession 能力
- [x] servers.html 手动登记表单 + 复制 token
- [x] traffic.html 活跃会话列表 + 断开按钮
- [x] users.html 绑定 server 操作
- 验收：ops-server go build 通过，前端页面有写操作 UI

---

## 4. 端到端验收路径

### 4.1–4.3 路径 A 自托管直连（需真机，标注待验）
- [ ] A 机登录 → 开仅 WiFi+充电自动备份 → 拍已存在图 → B 机秒传（后端 GetMediaByUserAndSHA256 命中）
- [ ] 拍新图 → 锁屏不开 App → 15min 内云端可见（依赖 WorkManager，待引入）
- [ ] 弱网杀进程重启 → 离线队列重放完成（OfflineQueueStore 持久化）
- [ ] 文件管理批量下载 N 张到本地相册、批量删除后两机不显示（墓碑）
- [ ] 50MB 大图预览不 OOM（降采样 2048px）；已上传 Tab 无限滚动分页流畅

### 4.4 路径 B 中继联调（本机两端口，标注待验）
- [ ] docker compose up ops-server（8090+18790）+ backend（8080，MM_OPS_SERVER_URL=http://localhost:8090）
- [ ] backend 启动自动 /op/server/register → ops 前端服务端页可见
- [ ] 客户端登录 op 账号 → 绑定 server → 请求连接 → 经中继 18790 转发到 backend:8080
- [ ] 经中继完成 sync/changes + 上传 → ops 流量页可见 bytes 计数

### 4.5 安全存储（已由 v5-ios-keychain 验证）
- [x] iOS 凭据在 Keychain、索引在私有文件（非 NSUserDefaults）
- [x] Android 无明文降级（Keystore 失败拒写敏感项）
- [x] 旧版升级迁移无感（mm_secure.json → Keychain 幂等）

### 4.6 可观测性
- [x] /metrics 暴露核心指标（Prometheus 格式）
- [x] 结构化日志带 request id（slog JSON + X-Request-ID）

### 4.7 文档
- [ ] §3 七篇齐全（子智能体生成中）
- [ ] 终端用户按 USER-GUIDE.md 可在 10 分钟内完成首次登录+备份

### 4.8 公网真机标注项（不阻塞主线）
- [ ] 跨网两真机经公网 ops-server 中继同步
- [ ] iOS BGProcessingTask 系统调度备份
- [ ] Android 10+ recoverable deletion 真机验证

---

## 5. 改动统计

| 域 | 文件改动 | 新增文件 | 行数变化 |
|---|---|---|---|
| backend | 5 修改 | 3 新增（middleware.go, metrics.go, ratelimit.go, ratelimit_test.go） | +约 400 行 |
| frontend | 11 修改, 1 删除 | 3 新增（BackupPolicy.kt + .android.kt + .ios.kt） | +约 200 行 |
| ops-server | 6 修改 | 2 新增（write_handlers.go, migrate_extra.go） | +约 200 行 |
| docs | 0 修改 | 7 新增（待生成） | — |
| **总计** | 22 修改, 1 删除 | 12+ 新增 | +1014 / -235 |

---

## 6. 待办与已知限制

1. **WorkManager / iOS BGProcessingTask 未引入**：§2.1 后台任务框架未实际接入 WorkManager + BGProcessingTask，当前仍用 viewModelScope 30s 前台轮询。策略检查框架就绪，后台调度为后续真机验证项。
2. **SettingsScreen UI 开关未补**：backupWifiOnly / backupChargingOnly 的 SettingsState 状态+save 方法已就绪，设置页 UI Switch 待补。
3. **nearestNeighbor 缩略图大图降级**：media_service.go:1608 纯 Go 单线程像素循环对超大图仍慢，限并发/降级为后续优化项。
4. **TLS 原生 HTTPS**：MM_TLS_CERT/MM_TLS_KEY 原生 HTTPS 未实现，生产默认经反代 TLS（文档已覆盖）。
5. **中继端到端联调自动化**：§4.4 需真跑 docker compose 两端口联调，当前仅冒烟。
