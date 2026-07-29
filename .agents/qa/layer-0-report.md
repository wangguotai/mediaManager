# Layer 0 QA 报告

**审查对象**：`be-sqlite-schema` 任务分支 merge 至 main（`3b96ebd..b3687aa`）
**审查范围**：3 个提交、9 个文件、+949/-61 行
**审查日期**：2026-07-29
**审查方式**：go build / go vet / go test（含 -race）/ DSN PRAGMA 实证 / 静态逻辑核验

> 注：本任务原始 prompt 全文未在 `.agents/` 任何 yaml/task-board 中留存（task-board 仅存状态元数据 `merged`/`exit_code=0`）。验收标准依据取自提交 message、代码文档注释，以及下游任务（`be-jwt-auth`/`be-user-isolation`/`be-sync-api`）对该存储层字段的依赖约定（sha256、软删墓碑、user_id 隔离、usage 计量）。

---

## 总评: CONDITIONAL PASS

构建、vet、单测（含 race）全绿；三表 schema 字段与下游任务需求对齐；软删除/级联/时间往返逻辑正确并有测试覆盖。存在 1 项 P1（误导性错误信息 + 公开 API 健壮性缺口）与若干 P2，不影响当前部署链路（config 层已兜底），但应在存储层被下游正式接入前修复。任务为纯后端 Go，无前端/跨端代码，跨端兼容性检查项不适用。

---

## 通过项

1. **编译干净**：`go build ./...` exit 0，无警告；`go vet ./internal/storage ./internal/config` exit 0。modernc.org/sqlite v1.34.5 在 go 1.21 + GOTOOLCHAIN=local 下正常，未触发 toolchain 切换（与提交说明一致）。

2. **免 CGO，跨平台部署友好**：用 `modernc.org/sqlite` 纯 Go driver，Docker/交叉编译无需 CGO，正确规避了 `mattn/go-sqlite3` 的 CGO 依赖问题。

3. **CRUD 全覆盖且测试通过**：`go test ./internal/storage ./internal/config -count=1` 全绿；`-race` 下 `TestCascadeDelete` 亦通过。覆盖了 create/get/get-by-username/list/duplicate-unique/soft-delete 不出现在列表/Update 不复活软删/级联删除/config 默认派生。

4. **schema 字段与下游任务对齐**：
   - `media.sha256` + 软删 `deleted` 列 → 支撑 `be-sync-api` 的 `(user_id,sha256)` 去重秒传与软删墓碑；
   - `user` 表 `username UNIQUE` + `password_hash` + `role` → 支撑 `be-jwt-auth` 登录/注册/超管；
   - `device` 表 → 支撑 `be-sync-api` 的 device register/list；
   - 三表均带 `user_id` 外键 `ON DELETE CASCADE` → 支撑 `be-user-isolation` 用户隔离。

5. **软删除语义正确**：`MarkDeleted` 仅置 `deleted=1`；`UpdateMedia` 显式不碰 `deleted` 列（测试 `TestMediaCRUDSoftDelete` 验证 Update 不会复活已软删记录），与"元数据更新不得复活软删"的设计一致。

6. **时间列策略自洽**：TEXT(RFC3339Nano) 存储避免 driver 对 INTEGER→time.Time 的不一致行为；`timeFromVal` 解析失败返回零值而非报错，实现"单坏行不阻断列表"的容错策略，与列表接口语义一致。

7. **并发写安全**：`SetMaxOpenConns(1)` 串行化写，规避 SQLite "database is locked"；WAL + busy_timeout(5000) 经实证在文件库下生效（`PRAGMA journal_mode=wal`）。

8. **config 层默认值兜底完整**：文件缺失/字段留空均回退默认；`ResolveDataDir`/`ResolveDBPath` 确保目录就绪；`db_path` 留空派生为 `<data_dir>/media.db`，与 `config.example.yaml` 口径一致（测试 `TestLoadEmptyDBPathDerives` 覆盖）。

9. **SQL 注入安全**：所有查询均用 `?` 参数化，无字符串拼接 SQL。

10. **资源释放**：`rows.Close()` 用 `defer` 兜底（`scanMediaRows`/`ListUsers`/`ListDevicesByUser`）；`Open` 失败路径均 `db.Close()`；`Store.Close()` 空指针安全。

---

## 问题清单(按严重程度 P0/P1/P2)

### P0 — 无

### P1

#### P1-1 `storage.Open` 对父目录缺失返回误导性错误，且公开 API 健壮性不足
- **位置**：`backend/internal/storage/db.go` `Open()` + `PingContext`
- **现象**：当 `dbPath` 的父目录不存在时，`Ping` 返回 `unable to open database file: out of memory (14)`。SQLite 错误码 14 实为 `SQLITE_CANTOPEN`，但 modernc driver 的 message 误导成 "out of memory"，排查者会被引向完全错误的方向（内存问题）。
- **实证**：用 `file:<不存在子目录>/t.db?...` DSN 调用 `Open`+`Ping`，稳定复现该 message。
- **影响**：当前部署链路中 `config.ResolveDBPath()` 会先 `os.MkdirAll(filepath.Dir(abs))`，故**实际不触发**。但 `Open` 是导出函数，下游/测试/运维若绕过 config 直接传入未就绪路径，会得到误导性报错。
- **建议**：`Open` 开头显式 `os.MkdirAll(filepath.Dir(dbPath), 0755)` 并在 DSN 失败时把底层错误包成 `open sqlite %s (path/dir may be missing): %w`，避免 "out of memory" 直接外泄。

### P2

#### P2-1 `:memory:` 库下 WAL 不生效，注释有夸大
- **位置**：`backend/internal/storage/db.go` `Open()` 注释 "WAL 提升并发读"
- **现象**：`:memory:` 库的 `journal_mode` 实测为 `memory` 而非 `wal`（SQLite 固有行为，内存库不支持 WAL）。文件库下 WAL 正常生效。
- **影响**：单测用 `t.TempDir()` 文件库，不受影响；仅注释口径对内存场景不严谨，不致功能错误。
- **建议**：注释补一句"WAL 仅对文件库生效；`:memory:` 库退化为 memory journal"。

#### P2-2 `UpdateMedia` "部分覆盖"语义易致字段误清零，仅靠注释警示
- **位置**：`backend/internal/storage/repository.go` `UpdateMedia()`
- **现象**：调用方若只想改 `filename`，传 `&Media{ID, Filename}` 会把 `Type/Size/Mime/Width/Height/SHA256` 全部以零值写回（覆盖原值）。文档注释已说明"部分覆盖语义…如需合并语义先 GetMedia 再改"，但函数签名无法在编译期阻止误用。
- **影响**：下游 `be-sync-api` 重命名/补字段场景若不先 Get，会丢元数据。属设计取舍，非 bug，但风险面广。
- **建议**：下游接入时强制"先读后写"封装，或提供 `PatchMedia` 增量接口；当前至少在 godoc 顶部加 `// WARNING:` 醒目提示。

#### P2-3 任务原始 prompt 未留存，验收标准不可回溯
- **位置**：`.agents/` 无 `be-sqlite-schema` 的 yaml 定义；`task-board.json` 仅存状态字段
- **现象**：无法逐条对照任务 prompt 的验收标准（如是否要求"建索引""提供 migration 版本号"等）。
- **影响**：QA 只能以提交 message + 下游依赖反推标准，存在盲区。
- **建议**：任务 prompt 应与状态一并归档到 task-board 或独立 yaml，保证可回溯。

---

## 建议修复项

| 优先级 | 项 | 动作 |
|---|---|---|
| P1 | `Open` 父目录缺失/误导错误 | `Open` 内 `MkdirAll` 父目录；包一层可读错误，屏蔽 "out of memory" |
| P2 | 内存库 WAL 注释夸大 | 注释补 WAL 适用范围说明 |
| P2 | `UpdateMedia` 误清零风险 | godoc 加 WARNING，或下游封装先读后写 / 提供 Patch 接口 |
| P2 | 任务 prompt 未归档 | 后续任务把 prompt 与状态一并落盘 |

---

## 验收标准逐项核对

| 检查项 | 结论 | 说明 |
|---|---|---|
| 1. 编译警告/潜在崩溃 | ✅ PASS | build/vet/test/race 全绿，无 nil 解引用/无未关闭 rows |
| 2. 功能逻辑缺陷（对验收标准） | ✅ PASS（带 P1/P2） | 字段对齐下游，软删/级联正确；`Open` 健壮性见 P1-1 |
| 3. 线程安全/内存泄漏 | ✅ PASS | MaxOpenConns=1 串行写，defer Close 完整，race 无告警 |
| 4. 跨端兼容性（verify_commands Android+iOS / commonMain 无 JVM API） | ⬜ 不适用 | 纯后端 Go 任务，无前端/跨端代码，无 verify_commands 跨端要求；下游 `be-jwt-auth` 等亦仅 `go build` |
| 5. 空数据/错误状态处理 | ✅ PASS（带 P1） | `ErrNotFound` 统一；空列表返回 nil slice 不报错；`Open` 错误信息误导见 P1-1 |
| 6. 硬编码值/配置不生效 | ✅ PASS | data_dir/db_path 均可配置且默认派生，无写死路径（`./data` 为合理默认） |

---

**结论**：本层 merge 可接受为 CONDITIONAL PASS。P1-1 建议在 `be-sqlite-schema` 被下游任务（`be-jwt-auth` 等）正式调用前修复，以免误导性错误信息在真实部署/调试中造成排查成本。其余 P2 可在后续迭代中处理。
