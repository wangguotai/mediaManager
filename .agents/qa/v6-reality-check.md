# V6 Sprint Reality-Check 代码审查报告

> 审查对象：commit `b2032da`（v6-sprint）相对基线 `6161bbc` 的全部改动
> 审查角色：reality-checker（默认"需要改进"，要求压倒性证据才允许上线）
> 审查日期：2026-07-30
> PRD 基线：`docs/PRD-v6.md` §2.1–§2.8
> 审查方法：逐文件 `git diff` + 源码交叉验证 + `go build`/`go vet`/`go test` 实跑

---

## 构建与测试实跑结果

| 检查 | 命令 | 结果 |
|---|---|---|
| backend 编译 | `cd backend && go build ./...` | ✅ PASS（exit 0） |
| ops-server 编译 | `cd ops-server && go build ./...` | ✅ PASS（exit 0） |
| backend vet | `go vet ./internal/gateway/ ./internal/storage/` | ✅ clean |
| ops vet | `go vet ./internal/relay/ ./internal/storage/ ./internal/admin/` | ✅ clean |
| gateway 限速测试 | `go test ./internal/gateway/ -run 'LoginRate|UploadConcurrency|ClientIP'` | ✅ PASS |

> 注：`go vet` 无法检出本文标记的 P0/P1 并发数据竞争（跨结构体字段的锁不一致），需 `go test -race` 才能暴露。frontend Kotlin 编译未在本环境实跑（无 Android SDK/iOS toolchain）。

---

## P0 — 阻断（上线前必须修复）

### P0-1. relay.go `enterPair` 跨锁读 `slot.closed`，与 `endSession`/`CloseSession` 写者数据竞争

- **文件**：`ops-server/internal/relay/relay.go:301`
- **问题描述**：
  `enterPair` 在持有 `s.mu`（Service 锁）但**未持有 `slot.mu`** 的情况下读取 `slot.closed`：
  ```go
  // line 301
  if !ok || slot.closed {
  ```
  而 `slot.closed` 的写者都持 `slot.mu`：
  - `endSession`（line 472-475）：`s.mu.Lock()` → `slot.mu.Lock()` → `slot.closed = true`
  - `CloseSession`（line 159）：`slot.mu.Lock()` → `found.closed = true`
  
  这是一个真实的 **data race**：一条 goroutine 在 `enterPair` 里无锁读 `slot.closed`，另一条在 `endSession`/`CloseSession` 里持 `slot.mu` 写。`go vet` 不报（字段在 `s.mu` 区段内），但 `go test -race` 会爆。后果：并发关闭与新建 pair 时 `slot.closed` 可能读到脏值，导致已关闭槽位被误判为可用 → 新连接挂入已结束会话的槽位 → 字节落账错乱 / pair 错配 / 乃至 panic。
- **修复建议**：`enterPair` line 301 读 `slot.closed` 前先 `slot.mu.Lock()`，读完 `Unlock()`；或把 `closed` 改为 `atomic.Bool`，所有读写统一走原子操作。注意 `endSession` 现为 `s.mu → slot.mu` 嵌套，`CloseSession` 仅 `slot.mu`，需统一锁序避免死锁。

### P0-2. `accessLogMiddleware` 永远记录不到 user_id（注释承诺与实现矛盾）

- **文件**：`backend/internal/gateway/middleware.go:138`（读 `userIDFromContext(r.Context())`）
- **问题描述**：
  中间件链为 `CORS → requestID → accessLog → auth → mux`。`accessLogMiddleware` 调 `next.ServeHTTP(rec, r)`，传入的是**原始 `r`**。内层 `authMiddleware` 做的是：
  ```go
  ctx := service.WithUserID(r.Context(), userID)
  next.ServeHTTP(w, r.WithContext(ctx))   // line 243 —— 造了一个 *新* Request 传给 mux
  ```
  `r.WithContext(ctx)` 返回新 Request 值，**不修改原始 `r`**。`accessLogMiddleware` 手上的 `r` 的 context 自始至终未被注入 user_id。于是 `accessLogMiddleware` 在 `next` 返回后读 `userIDFromContext(r.Context())`（line 138）**恒为空串** → 所有已认证请求的访问日志 `user` 字段记为 `"anon"`。
  
  代码注释（middleware.go:120-127）明确声称"auth 已注入 context，可在 next 返回后读到"——**这是错误的**。PRD §2.6 验收"结构化日志带 user_id（脱敏）"实际未达成：脱敏后的 user 标签对认证请求也永远是 `anon`，可观测性验收项失效。
- **修复建议**：让 `accessLogMiddleware` 不依赖原始 `r`，而是从 `rec` 拦截的响应或显式回传取 user_id；最干净的做法是把 `requestIDMiddleware` 改为返回带"可写 context slot"的 wrapper，或把 auth 提到 accessLog 之前（但那样未认证请求也进 accessLog，需另行处理）。推荐：在 `authMiddleware` 内把 user_id 同时写入 response header（如内部 `X-User-Tag`）供 accessLog 读取后删除；或用 `*statusRecorder` 额外携带一个 `userID` 字段，由 auth 中间件写回。

---

## P1 — 重要（功能缺陷 / 验收点未真正达成）

### P1-1. 复合游标后端实现了，客户端完全没采用 —— `syncCursor` 仍只存毫秒

- **文件**：
  - 前端：`frontend/feature-media/src/commonMain/kotlin/com/wgt/feature/media/MediaService.kt:710-731`（`getSyncChanges` 只解析 `next_cursor: Long`，**忽略 `next_cursor_id`**）
  - `MediaViewModel.kt:215,393`（`syncCursor: Long`，`saveSyncCursor(Long)` 只存毫秒）
  - 后端：`backend/internal/gateway/sync_handlers.go:99`（`ListMediaChanges(..., sinceID, ...)`）、`repository.go:380`（复合游标 SQL）
- **问题描述**：
  PRD §2.7 要求"`(updated_at, id)` 复合严格大于，next_cursor 取本页末条 `(updated_at, id)`；wire 协议向下兼容（cursor 可选带 id）"。后端完整实现了：响应新增 `next_cursor_id`，`parseSinceCursor` 支持 `ms|id`，`ListMediaChanges`/`CountMediaChanges` 走复合分支。**但客户端从不读 `next_cursor_id`、从不传 `ms|id`**：
  - `SyncChangesResult` 无 `nextCursorId` 字段；
  - `getSyncChanges` 的 `parameter("since", since)` 只传 `Long`；
  - VM `syncCursor` 是 `Long`，`SettingsState.syncCursor` 存 `"sync_cursor"` 字符串化的毫秒。
  
  因此生产环境**永远走 `sinceID==""` 的退化分支**（纯时间戳严格大于），复合游标修复**对终端用户无效**。当批量导入或并发写入产生相同 `updated_at` 时，同时间戳边界仍会重/漏 —— PRD §2.7 的核心问题并未真正解决。服务端单测 `TestListMediaChangesCompoundCursor` 绿，但那只验后端 SQL，未覆盖端到端客户端路径。
- **修复建议**：客户端 `SyncChangesResult` 增 `nextCursorId: String`；`getSyncChanges` 解析 `next_cursor_id`；`MediaViewModel.syncCursor` 改为持 `(ms, id)` 二元组（或 `String` 形如 `"ms|id"`），`SettingsState.saveSyncCursor` 同步存 id；`getSyncChanges` 传 `since = "$ms|$id"`。否则后端复合游标代码是死代码。

### P1-2. PRD §2.1 自动备份核心验收不可达成 —— 无 WorkManager / 无 iOS BGTask

- **文件**：`frontend/composeApp/src/commonMain/kotlin/com/wgt/media/MediaViewModel.kt:38,494-502`
- **问题描述**：
  PRD §2.1 验收标准明确："锁屏+WiFi+充电→拍照→不开 App，15min 内云端可见（Android）；iOS 系统调度后可见。" 待办 2/3 要求："Android 用 WorkManager 替代 viewModelScope 30s 轮询，进程被杀可由系统调度唤醒；iOS 注册 BGProcessingTaskRequest。"
  
  实测：`AUTO_BACKUP_INTERVAL_MS = 30_000L` 未改，`autoBackupJob = viewModelScope.launch { ... delay(AUTO_BACKUP_INTERVAL_MS) }` 仍是前台进程内 30s 轮询。全仓 grep `WorkManager`/`PeriodicWorkRequest`/`BGProcessingTask`/`BackgroundTasks` **零命中**（仅 iOS `BackupPolicy.ios.kt:19` 注释里提到一句"由系统 BGProcessingTask 调度"，但无任何实现）。
  
  后果：进程被杀（锁屏后系统回收）即停备份，**验收标准"不开 App 15min 内云端可见"根本无法达成**。本次只补了 WiFi/充电策略前置检查（`shouldBackupByPolicy`）与视频备份解禁，PRD §2.1 的 1/3 项未做。
- **修复建议**：（1）Android 引入 WorkManager `PeriodicWorkRequest`（15min，Constraint NETWORK_UNMETERED + 可选 CHARGING），把 `checkAndBackupNewLocalMedia` 移入 Worker；（2）iOS 注册 `BGProcessingTaskRequest`（identifier `com.wgt.media.backup`），Info.plist 配 background mode；（3）前台即时协程保留作"快速备份"路径。至少需标注本项为"未完成、降级交付"，不得在 QA 报告里声明 §2.1 通过。

### P1-3. PRD §2.1 备份状态/进度 UI 未实现

- **文件**：`frontend/composeApp/src/commonMain/kotlin/com/wgt/media/SettingsScreen.kt`、`MediaViewModel.kt`
- **问题描述**：
  PRD §2.1 待办 5："通知栏/小部件展示「备份中 N/M · 已暂停（非 WiFi）」；设置页展示「待备份 N 项」「上次备份时间」。" grep `待备份`/`上次备份`/`backupStatus`/`pendingBackup`/`lastBackup`/`Notification`/`isBackingUp` 在设置页与 VM **零命中**（仅 VM:579 一句注释"不清空待备份项"）。验收点未达成。
- **修复建议**：至少在设置页加"待备份 N 项 / 上次备份时间"两行只读状态；通知栏进度可作为后续项，但 PRD 既列则不应静默缺失。

### P1-4. PRD §2.7 密码复杂度未实现

- **文件**：`backend/internal/auth/auth.go:281`（`const minPasswordLength = 8`）
- **问题描述**：
  PRD §2.7："密码复杂度：v5-security 只改了长度 8，PRD 要求加复杂度。" 实测仍是 `len(pw) < 8` 单一长度校验，无大小写/数字/符号复杂度要求。grep `complex`/`uppercase`/`strongPassword` 零命中。BootstrapAdmin 密码已是 `randomBytes(16)` 随机（auth.go:406），符合"随机 16+"；但用户自设密码复杂度未加。
- **修复建议**：在 `Register`/`ChangePassword` 增复杂度校验（如至少含字母+数字，或用 zxcvbn 评分）；或在 PRD/QA 报告显式降级标注。

### P1-5. `apiUserBindServer` 对不存在的 account_id 返回 200 `bound`（ErrAccountNotFound 分支是死代码）

- **文件**：`ops-server/internal/admin/write_handlers.go:108-115`；`ops-server/internal/storage/repository.go:55-62`（`SetOpAccountServer`）
- **问题描述**：
  `apiUserBindServer` 调 `SetOpAccountServer`，并尝试 `errors.Is(err, auth.ErrAccountNotFound)` 回 404。但 `SetOpAccountServer` 实现：
  ```go
  _, err := s.db.ExecContext(ctx, `UPDATE op_account SET server_id = ? WHERE id = ?`, ...)
  ```
  SQLite `UPDATE` 命中 0 行**不报错**，`err` 为 nil。故传入任意不存在的 `account_id`，handler 走到 line 116 返回 `200 {"status":"bound"}`，**误报绑定成功**。`ErrAccountNotFound` 分支永远不可达，是死代码。运营前端"绑定 server"操作对错误账号 id 静默成功，数据不一致。
- **修复建议**：`SetOpAccountServer` 用 `res.RowsAffected()` 检查，0 行返回 `ErrAccountNotFound`（`GetOpAccountServerID` 已有此错误，可前置调它校验存在性）。

### P1-6. iOS `isOnWifi()` 恒返回 true —— "仅 WiFi 备份"在 iOS 上完全失效

- **文件**：`frontend/composeApp/src/iosMain/kotlin/com/wgt/media/BackupPolicy.ios.kt:28-31`
- **问题描述**：
  `actual fun isOnWifi(): Boolean { return true }` 直接返回 true。用户在 iOS 上开启"仅 WiFi 备份"后，移动数据下 `shouldBackupByPolicy()` 仍返回 true，**备份照跑、流量被偷跑**——正是 PRD §2.1 默认仅 WiFi 要规避的场景。注释自承"NWPathMonitor 异步，简化策略"，但这等价于功能未实现。PRD §2.1 验收对 iOS 同样要求"非 WiFi 暂停"。
- **修复建议**：用 `NWPathMonitor` 异步采样当前 path 类型，缓存最近一次结果供同步查询；或至少用 `SCReachability` 同步 API。当前实现不应在 QA 报告中声称 iOS 满足"仅 WiFi"策略。

---

## P2 — 建议（不阻断，但应修复）

### P2-1. `UploadConcurrencySlotConcurrentSafety` 测试在已满槽上 `ReleaseUploadSlot` 可能"凭空释放"

- **文件**：`backend/internal/gateway/ratelimit.go:142-150`（`ReleaseUploadSlot`）、`ratelimit_test.go:155-165`
- **问题描述**：
  `ReleaseUploadSlot` 用 `select { case <-v.(chan struct{}): default: }`，非阻塞 receive。`TestUploadConcurrencySlotConcurrentSafety` 在已占满 3 槽后，让 50 个 goroutine 各自 `AcquireUploadSlot`（必失败）+ `ReleaseUploadSlot`。但 `ReleaseUploadSlot` 无条件从 channel 取令牌——在已满（3 令牌在 channel 里）状态下，goroutine 的 `Release` 会取走一个令牌，使后续 `Acquire` 可能成功，50 个 goroutine 互相交错 acquire/release。测试注释承认"不校验精确计数"，只验不 panic/不死锁。这没真正验证"不超发"。生产 handler 路径的 acquire/release 是成对的（defer），没问题；但测试未提供有意义的并发正确性保证。
- **修复建议**：测试改为量化断言——用一个 counter 记录"同时持有的令牌数峰值"，断言峰值 ≤ `uploadConcurrentMax`。

### P2-2. `migrateFromOldDedupStore` 写 `"[]"` 覆盖旧文件，但若迁移失败旧文件仍在、下次重试

- **文件**：`frontend/composeApp/src/commonMain/kotlin/com/wgt/media/SyncComponents.kt:46-74`
- **问题描述**：
  迁移逻辑读旧 `dedup_sha256.json` → 合并到 `seen` → `persist()` → 写 `"[]"` 覆盖。若 `persist()` 之后、写 `"[]"` 之前崩溃，下次启动会重读旧文件再合并（幂等，因为 `seen.add` 去重）。但 `catch(e)` 分支只记日志不写 `"[]"`，意味着**解析异常的旧文件会每次启动都尝试迁移**，且若 JSON 损坏则永远迁移不过去。影响低（只多一次读盘），但不够干净。
- **修复建议**：迁移成功后用原子写（先写临时文件再 rename）覆盖 `"[]"`；解析失败也写 `"[]"` 视为放弃迁移。

### P2-3. `BackupPolicy.android.kt` `appContext` 用 `runCatching` 包 `AppContext.isInitialized` 访问，失败宽松返回 true

- **文件**：`frontend/composeApp/src/androidMain/kotlin/com/wgt/media/BackupPolicy.android.kt:13-15,20,31`
- **问题描述**：
  `appContext` 取不到时 `isOnWifi`/`isCharging` 返回 true（宽松）。这在 App 未初始化的边缘时刻（如 WorkManager 在 App onCreate 前调度）会让备份无视策略执行。当前 WorkManager 尚未引入（见 P1-2），此路径暂不触发；但一旦引入 WorkManager，需保证 Worker 执行时 AppContext 已初始化。
- **修复建议**：Worker 中用 `ApplicationContext`（WorkManager 注入的）而非全局 `AppContext`；或在 `isOnWifi` 返回 true 前日志告警"AppContext 未初始化，策略检查跳过"。

### P2-4. `iOS saveMediaToGallery` 临时文件名拼接未对 `filename` 做清洗

- **文件**：`frontend/feature-common/src/iosMain/kotlin/com/wgt/feature/gallery/PhotoGalleryService.ios.kt:118`
- **问题描述**：
  `tmpPath = "${NSTemporaryDirectory()}mm_download_${NSUUID().UUIDString}_${filename}.${ext}"`，`filename` 直接拼入路径。若 `filename` 含 `/` 或 `..`，可能写到预期目录外（虽然 NSTemporaryDirectory 限定了前缀，但 `../` 仍可逃逸）。云端返回的 `filename` 来自用户上传，不可信。Android 侧 `sanitizeDisplayName` 做了扩展名补全但未过滤路径分隔符；Android 用 `MediaStore.MediaColumns.DISPLAY_NAME`（不接受路径），相对安全；iOS 直接拼路径风险更高。
- **修复建议**：iOS 侧对 `filename` 做 `replace(Regex("[^A-Za-z0-9._-]"), "_")` 清洗后再拼路径。

### P2-5. `recordRequest` 用 `http.StatusText(rec.status)` 作 status 标签，未知码返回空串

- **文件**：`backend/internal/gateway/middleware.go:162`
- **问题描述**：
  `RecordRequest(r.Method, normalizePath(r.URL.Path), http.StatusText(rec.status), latency)`。`http.StatusText` 对非标准码（如某些反代注入的 5xx、或自定义码）返回空串，导致 metrics 出现 `status=""` 标签序列，稀释聚合。建议直接用数字字符串 `"429"` 作标签，更稳定且省一次转换。
- **修复建议**：`status` 标签改用 `strconv.Itoa(rec.status)`。

### P2-6. `normalizePath` 未覆盖 `/api/media/stream/<id>/` 带尾斜杠及 `/api/sync/changes` 等已分页路径

- **文件**：`backend/internal/gateway/metrics.go:195-210`
- **问题描述**：
  `normalizePath` 只折叠 5 个 `/api/media/{stream,thumbnail,metadata,video-info,album}/` 前缀。`/api/sync/changes`、`/api/device/{register,list}` 等固定路径原样返回（OK）。但若客户端请求 `/api/media/stream/<uuid>/`（带尾斜杠）会命中前缀、折叠为 `/api/media/stream/:id`（OK）。真正遗漏：`/api/media/upload`（无 id，原样 OK）、`/api/media/list`（OK）。无明显高基数遗漏，但 `metrics.go:195` 注释列了 `album/abc` 而 PRD 未确认有该端点——属注释与实际路由的轻微出入。
- **修复建议**：无功能影响，建议注释对齐实际路由表。

### P2-7. `apiActiveSessions` 取最近 200 条会话再内存过滤 `ended_at` 零值 —— 活跃会话多时可能漏

- **文件**：`ops-server/internal/admin/write_handlers.go:145`
- **问题描述**：
  `ListAllRelaySessions(ctx, 200)` 取最近 200 条（按时间倒序），再过滤 `EndedAt.IsZero()`。若历史会话堆积且最近 200 条中混入多条已结束会话，活跃会话可能被截断或完全漏掉（极端：最近 200 条全已结束，但第 201 条是活跃的——虽不太可能，但逻辑不严谨）。更稳妥是 storage 层直接 `WHERE ended_at = ''` 过滤。
- **修复建议**：`storage` 增 `ListActiveRelaySessions` 方法直接 SQL 过滤 `ended_at IS NULL OR ended_at = ''`，避免内存过滤与 limit 截断风险。

### P2-8. `recordSyncChanges` 与 `hasMore` 语义可能让 `sync_changes_served_total` 重复计入重试

- **文件**：`backend/internal/gateway/sync_handlers.go:131`
- **问题描述**：
  `RecordSyncChanges(len(items))` 每次请求累计返回的变更条目数。客户端失败页不推进游标、下次重试从原 since 续拉，后端会再次返回相同条目并再次计数 → `sync_changes_served_total` 重复累加重试的部分。作为"拉取量"指标这或许可接受（确实多 serve 了一次），但若用于"去重后的真实变更吞吐"则偏高。需在 RUNBOOK 指标解读里注明。
- **修复建议**：文档注明该指标含重试；或改为按 media_id 去重计数（复杂，非必要）。

### P2-9. `accessLogMiddleware` 未记录响应体大小；`statusRecorder.Write` 未追踪 bytes 写入

- **文件**：`backend/internal/gateway/middleware.go:104-110`
- **问题描述**：
  `statusRecorder.Write` 只标 `wrote`，不累加字节数。访问日志缺 `bytes_sent` 字段，排障时无法定位大响应。`metrics` 也无 `http_response_size_bytes` 指标。PRD §2.6 验收"访问日志记录 method/path/status/latency/user_id（脱敏）"未要求 body size，但常见可观测实践会记。
- **修复建议**：`statusRecorder` 增 `bytesWritten int64`，`Write` 累加；日志加 `bytes` 字段。

### P2-10. PRD §2.5 nearestNeighbor 单线程像素循环仍纯 Go，未限并发 / 未对超大图降级（待办之一）

- **文件**：`backend/internal/service/media_service.go:441-442,1339,1645`
- **问题描述**：
  PRD §2.5 待办："nearestNeighbor 纯 Go 单线程像素循环，大图缩略图慢 → 限并发（信号量）或对超大图跳过/降级。" 实测已有 `thumbGenSem`（line 441 限制 2 并发）与 line 1318"极端大图"降级路径——**此项实际已做**，PRD 行号漂移导致列为待办。本审查确认 §2.5 已达成，建议 PRD 更新。此条非缺陷，仅记录核对结论。
- **修复建议**：无代码改动；建议在 QA 报告标注 §2.5 已完成。

---

## 总评

**FAIL**

理由：

1. **P0-1**（relay 数据竞争）与 **P0-2**（访问日志 user_id 永远为 anon）是真实代码缺陷，前者破坏中继会话状态机正确性，后者直接使 PRD §2.6 可观测性验收点失效。
2. **P1-1**（复合游标客户端未采用）使 PRD §2.7 同步游标稳健性修复在生产环境无效——后端代码是死路径，验收报告不得声称 §2.7 已解决。
3. **P1-2/P1-3**（无 WorkManager/BGTask、无备份状态 UI）使 PRD §2.1 核心验收"锁屏不开 App 15min 内云端可见"不可达成，§2.1 实际仅完成 WiFi/充电策略检查与视频备份解禁两子项。
4. **P1-4/P1-5/P1-6**（密码复杂度、bind 静默成功、iOS WiFi 失效）各自使对应 PRD 验收点未真正达成。

正面：后端/ops-server 编译与 vet clean，限速单测全绿，复合游标 SQL 与单测正确，去重合一迁移幂等，批量下载 + 大图降采样 + 视频备份解禁实现到位，ops 运营写端点 + 前端写操作页齐全，迁移幂等。但 reality-checker 默认"需要改进"，以上 P0/P1 未达"压倒性证据允许上线"的门槛。

**建议处置**：修复 P0-1、P0-2 后可降为 CONDITIONAL PASS 推进灰度；P1 项需在 QA 报告（`docs/V5-QA-REPORT.md`）显式标注"未完成/降级"，不得声明对应 PRD 章节通过，或在下一 hotfix sprint 补齐 P1-1（客户端复合游标）与 P1-2（WorkManager/BGTask）后再全量验收。
