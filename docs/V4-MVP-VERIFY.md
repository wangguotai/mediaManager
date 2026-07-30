# V4 MVP 端到端验证报告

> 日期: 2026-07-30 12:15
> 验证人: OpenClaw

## 验证环境
- 后端: go run ./cmd/server (localhost:8080)
- 超管账号: admin (首次启动自动创建)

## 验证结果

| # | 步骤 | 结果 | 详情 |
|---|------|------|------|
| 1 | 健康检查 GET /healthz | ✅ PASS | status=ok, disk=19.66GB |
| 2 | 登录 POST /api/auth/login | ✅ PASS | 返回 JWT token |
| 3 | 上传 POST /api/media/upload | ✅ PASS | media_id + sha256 返回 |
| 4 | 增量同步 GET /api/sync/changes | ✅ PASS | changes=1, has_more=false |
| 5 | 媒体列表 GET /api/media/list | ✅ PASS | total=1 |
| 6 | 401 隔离（无 token） | ✅ PASS | {"error":"missing authorization header"} |
| 7 | 删除 POST /api/media/delete | ✅ PASS | deleted_count=1 |
| 8 | 墓碑同步（删除后查 changes） | ⚠️ GAP | 物理删除未生成软删除墓碑 |
| 9 | 用量 GET /api/sync/usage | ✅ PASS | total_bytes=218, file_count=1 |
| 10 | 缓存统计 GET /api/stats | ✅ PASS | list_cache + thumbnail_cache |
| 11 | 设备注册 POST /api/device/register | ✅ PASS | 返回 device_id |

## 结论

**10/11 通过，1 个已知 GAP（删除墓碑）**

MVP 主线基本打通：认证→上传→同步→列表→删除→用量→设备注册。
后端删除是物理删除，未实现软删除墓碑（PRD §3.4 要求），需要后续修复。

## 未验证项（需真机/公网）
- 客户端 App 登录流程
- 自动备份+同步
- 两设备同步
- 运营服务端中继
- 运营前端管理台
