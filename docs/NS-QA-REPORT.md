# Night Sprint QA 报告

**审查范围:** fb9eca3..HEAD (3 merges)  
**审查日期:** 2026-07-28  
**审查员:** reality-checker (subagent)

## 总评: CONDITIONAL PASS

双端编译通过（go build/vet + Android assembleDebug + iOS compileKotlinIosArm64），无编译警告。三个任务的核心功能逻辑基本正确，但存在 1 个 P1 安全问题、2 个 P1 功能缺陷和若干 P2 代码质量问题，需修复后方可视为完整 PASS。

---

## 通过项

### 编译验证
- ✅ `go build ./...` — 无错误无警告
- ✅ `go vet ./...` — 无问题
- ✅ `:composeApp:assembleDebug` — BUILD SUCCESSFUL
- ✅ `:composeApp:compileKotlinIosArm64` — UP-TO-DATE（含 iOS 平台 Skia 滤镜实现）
- ✅ commonMain 无 `java.*` / `android.*` 导入，跨端兼容合规

### 后端缓存架构 (ns-backend-perf)
- ✅ ThumbCache LRU 实现正确：mutex 保护、evictLRU 逻辑清晰、totalBytes 统计一致
- ✅ 列表缓存用 dirMtime 失效策略合理——文件增删改 mtime 即触发缓存失效
- ✅ DeleteMedia 正确调用 invalidateListCache + 清理 video-meta 文件
- ✅ healthz 端点返回 media_count / uptime / cache 状态，结构合理

### 图片编辑器 v2 (ns-image-editor-v2)
- ✅ 滤镜 ColorMatrix 定义正确（4×5 row-major），5 种滤镜覆盖常见需求
- ✅ 跨平台滤镜实现完整：Android 用 ColorMatrixColorFilter + Canvas，iOS 用 Skia ColorFilter.makeMatrix
- ✅ 三模式切换（裁剪/旋转/滤镜）UI 结构清晰，CropOverlay 仅在 CROP 模式显示
- ✅ cropAndRotateImageBitmap 添加 colorMatrix 参数，expect/actual 签名一致

### 收藏前端 (ns-favorite-frontend)
- ✅ FavoriteStore 用 SettingsStorage（expect/actual）持久化，commonMain 安全
- ✅ 星标按钮 UI 交互合理：右上角半透明圆形背景，与左上角选中徽标不冲突
- ✅ toggleFavorite 先更新本地 UI 再异步同步后端，体验正确（乐观更新）
- ✅ FAVORITE 过滤维度与前三个维度互斥逻辑正确（matchesFilter 分支处理）

---

## 问题清单

### P0 — 阻塞

无。

### P1 — 需修复

#### P1-1: loadVideoMeta / saveVideoMeta 缺少路径穿越校验
**文件:** `backend/internal/service/media_service.go:1268, 1286`  
**问题:** `GetVideoInfo` 虽然通过 `resolveMediaPath` 查找文件（该函数有 `..` / `/` 检查），但 `loadVideoMeta` 和 `saveVideoMeta` 直接用 `filepath.Join(s.videoMetaDir, mediaID+".json")` 拼接路径。如果 `mediaID` 包含 `../`（从其他入口注入或未来代码变更），可读写 video-meta 目录之外的文件。  
**影响:** 潜在路径穿越，虽然当前 gateway 层有校验，但 service 层防御缺失。  
**修复:** 在 `loadVideoMeta` / `saveVideoMeta` 入口添加 `strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/")` 校验，或在 filepath.Join 后用 `filepath.Rel` 验证结果仍在 videoMetaDir 内。

#### P1-2: UploadMedia (gRPC) 和 handleMediaUpload (REST) 均未调用 invalidateListCache
**文件:** `backend/internal/service/media_service.go:528` (UploadMedia), `backend/internal/gateway/server.go:330` (handleMediaUpload)  
**问题:** 上传新文件后目录内容已变化，但未调用 `invalidateListCache()`。虽然 listCache 有 30s TTL + dirMtime 检查会最终失效，但 REST upload 直接写文件不经 service 层，dirMtime 变化检测依赖下次 stat——在 TTL 窗口内若 mtime 恰好被还原（极端场景）则返回旧列表。更重要的是，gRPC UploadMedia 在 service 层完成却完全没有失效调用，与 DeleteMedia 的处理不对称。  
**影响:** 上传后短期内列表可能不含新文件（最多 30s）。  
**修复:** 
- gRPC UploadMedia: 在 `currentFile.Close()` 后调用 `s.invalidateListCache()`
- REST handleMediaUpload: 透过 service 层接口或在 gateway 层补充一个 `CacheInvalidator` 接口调用

#### P1-3: server.lastCacheHit 字段声明但从未写入
**文件:** `backend/internal/gateway/server.go:42`  
**问题:** `lastCacheHit atomic.Bool` 声明并注释为 "tracks whether the last GetMediaList served from cache"，但全局搜索仅 1 处引用（声明行），从未被读写。healthz 的 cache 状态报告直接调用 `service.GetListCacheStats()`，未使用此字段。  
**影响:** 死代码，注释误导，可能是未完成的功能遗留。  
**修复:** 删除 `lastCacheHit` 字段及其注释，或补全其使用逻辑。

### P2 — 建议改进

#### P2-1: FilterOption 含 FloatArray 但用 data class + indexOf 查找
**文件:** `frontend/.../ImageEditor.kt:74, 478`  
**问题:** `data class FilterOption(val label: String, val matrix: FloatArray?)` — Kotlin data class 的 `equals()` 对 FloatArray 用引用比较（数组不走内容比较），`indexOf` 在列表中按 `equals` 查找。当前 `FILTER_OPTIONS.indexOf(opt)` 能工作是因为列表中的元素就是同一引用，但这属于脆弱模式——未来若有人构造同名 FilterOption 会导致 indexOf 失败。  
**修复:** 改用 `FILTER_OPTIONS.withIndex().first { it.value.label == opt.label }.index`，或将 FilterOption 改为 enum class。

#### P2-2: FavoriteStore 每次调用都 new SettingsStorage()
**文件:** `frontend/.../FavoriteStore.kt:29, 45`  
**问题:** `loadFavoriteIds()` 和 `saveFavoriteIds()` 每次都 `val storage = SettingsStorage()`。如果 SettingsStorage 构造有开销（平台实际实现可能初始化 SharedPreferences / NSUserDefaults 句柄），频繁 toggleFavorite 会产生不必要的对象创建。  
**修复:** 改为 `private val storage = SettingsStorage()` 对象级字段（object 单例持有）。

#### P2-3: ThumbCache.totalBytes 仅统计未用作淘汰依据
**文件:** `backend/internal/service/thumb_cache.go:18, 91`  
**问题:** `totalBytes` 字段在 Put/evictLRU 中维护，但淘汰策略仅基于 `len(c.items) >= c.maxItems`（条目数），未考虑 totalBytes。即 100 个 512KB 缩略图 = ~50MB 内存，虽在 maxItemSize 限制内，但缺少总内存上限。  
**影响:** 内存使用可能高于预期，但在当前 100 条 × 512KB 上限下可控。  
**修复:** 可选添加 `maxTotalBytes` 字段并在 Put 中同时检查 totalBytes 上限。低优先级。

#### P2-4: iOS saveImageBitmapToGallery 未实现
**文件:** `frontend/.../ImageProcessing.ios.kt:末尾`  
**问题:** iOS 端 `saveImageBitmapToGallery` 仅 log warning 并返回 null，用户在 iOS 上点保存不会有任何反馈。  
**影响:** iOS 用户保存编辑后图片功能不可用。  
**修复:** 用 `UIImageWriteToSavedPhotosAlbum` 或 Photos 框架实现。若为已知 backlog 项，应在 UI 上对 iOS 用户禁用保存按钮并提示。

#### P2-5: 健康检查 healthz 遍历目录统计文件数
**文件:** `backend/internal/gateway/server.go:handleHealthz`  
**问题:** 每次 /healthz 请求都 `os.ReadDir(s.uploadsDir)` 遍历全目录计数文件。uploads 目录文件多时（数千），高频健康检查会产生不必要 IO。  
**修复:** 可用 `atomic.Int64` 缓存计数，在 Upload/Delete 时增减；或直接去掉 media_count 字段（healthz 只需表态活着）。

---

## 建议修复项（优先级排序）

1. **P1-1** — loadVideoMeta/saveVideoMeta 添加路径校验（5 分钟）
2. **P1-2** — UploadMedia 添加 invalidateListCache 调用（3 分钟）
3. **P1-3** — 删除 lastCacheHit 死代码（1 分钟）
4. **P2-4** — iOS 保存图片至少在 UI 层提示不支持（10 分钟）
5. **P2-2** — FavoriteStore 复用 SettingsStorage 实例（2 分钟）
6. **P2-1** — FilterOption indexOf 改用 label 匹配（5 分钟）

---

## 构建结果附档

```
# Backend
$ go build ./...    → OK (无输出)
$ go vet ./...      → OK (无输出)

# Frontend
$ ./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64
  BUILD SUCCESSFUL in 2s
  208 actionable tasks: 17 executed, 191 up-to-date
```
