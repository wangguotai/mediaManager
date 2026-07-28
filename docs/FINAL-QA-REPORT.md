# Final QA Report

> 验收人：reality-checker（体验验收专家）
> 验收日期：2026-07-28
> 验收范围：media-manager 全项目（对照 PRD-v2 全部功能 + Sprint 1 QA 遗留问题修复验证）
> 验收方法：双端编译（go build/vet + assembleDebug + compileKotlinIosArm64）、全源码审查、路径穿越/线程安全/跨端兼容性扫描、PRD 逐项核验

---

## 总评: CONDITIONAL PASS（有条件通过）

项目在双端编译、核心功能覆盖、Sprint 1 P0 修复方面取得了显著进展，但仍存在 2 个 P0 和若干 P1 遗留问题，在修复前不应作为"可用产品"对外发布。

---

## 双端编译状态

### 后端（Go）

| 步骤 | 结果 |
|---|---|
| `go build ./...` | ✅ PASS（exit 0） |
| `go vet ./...` | ✅ PASS（exit 0） |

**注意**：`backend/gen/` 目录（protobuf 生成代码）未预先存在于仓库中，需先运行 `bash scripts/generate_proto.sh`（需安装 protoc-gen-go / protoc-gen-go-grpc）。生成后编译与 vet 均洁净通过。建议将 `gen/` 目录提交到仓库或在 CI 中增加生成步骤，否则新开发者 clone 后无法直接构建。

### 前端（Kotlin Multiplatform）

| 步骤 | 结果 |
|---|---|
| `:composeApp:assembleDebug` | ✅ BUILD SUCCESSFUL（208 tasks，1m 1s） |
| `:composeApp:compileKotlinIosArm64` | ✅ BUILD SUCCESSFUL（含在上述 208 tasks 中） |

编译警告（非阻断）：
- `expect/actual classes` Beta 警告（SettingsStorage.kt，rn-module 共 8 处）
- `TabRow` deprecated 警告（建议迁移到 PrimaryTabRow/SecondaryTabRow）
- `CropOverlay.kt` 冗余 `else` 分支 ×2
- `MINI_KIND` deprecated 警告

### commonMain 纯净度

`grep -rn "import java\.\|import android\." frontend/composeApp/src/commonMain/` → **零匹配** ✅

commonMain 无任何 java.*/android.* 导入，KMP 跨端隔离合规。

---

## 功能完整性（对照 PRD 逐项）

### Sprint 0-2 已有功能

| PRD 功能项 | 状态 | 说明 |
|---|---|---|
| 三 Tab（本地/已上传/网盘） | ✅ | 三 Tab 切换 + Crossfade 动画 |
| 网格浏览 + 缩略图 + shimmer | ✅ | LazyVerticalStaggeredGrid + shimmer 占位 |
| 全屏预览（左右滑动 + 双击/捏合缩放） | ✅ | ZoomableImage 实现 |
| 搜索栏 + 类型筛选 FilterChip | ✅ | 可展开搜索 + debounce 300ms + ALL/IMAGE/VIDEO/FAVORITE |
| 按日期分组 + sticky header | ✅ | DateGroupedGrid（staggered grid 不支持 stickyHeader，改用 inline header） |
| 图片详情面板（EXIF / 上滑展开） | ✅ | DetailPanel + 拖拽手势 + EXIF 显示 |
| 设置页（后端地址/主题/关于） | ✅ | SettingsScreen 含后端地址 + 连通性测试 + 主题切换 + OpenClaw 入口 |
| 视频播放器（Android VideoView / iOS AVPlayer） | ✅ | 双端 actual 实现，进度条/暂停/释放齐全 |
| 后端 Go gRPC + REST | ✅ | gRPC + REST gateway，ffmpeg 缩略图，ffprobe 视频信息 |
| 图片编辑（裁剪/旋转） | ✅ | ImageEditor 支持裁剪框拖拽 + 90° 步进旋转 + 自由角度滑块 |
| 分享功能 | ✅ | 系统分享 Intent/ShareSheet |
| Splash Screen + Material3 动态色调 | ✅ | SplashScreen 淡入动画 + dynamicColor |
| 时区正确日期分组 | ✅ | systemTimeZoneOffsetMillis 平台 expect/actual，UTC+8 正确 |
| LRU 缓存 + 线程安全 | ✅ | BackendImageLoader LinkedHashMap + Mutex + putBounded 淘汰 |

### Night Sprint 功能（NS-01 ~ NS-09）

| 任务 | 状态 | 验证细节 |
|---|---|---|
| **NS-01: 后端地址真实生效** | ✅ 已修复 | MediaService `backendUrl` 可变变量 + `updateBackendUrl()` 推模型；VideoPlayer 读 `SettingsState.backendUrl`；deleteMedia/uploadMedia 异常返回 `false`（行 172/192）；连通性测试真实请求后端 |
| **NS-02: 搜索高亮 + OpenClaw 入口归位** | ✅ 已完成 | `highlightFilename()` 用 SpanStyle 高亮匹配子串；OpenClaw 入口移至 SettingsScreen §4；TopAppBar actions 仅搜索/刷新/设置 |
| **NS-03: 瀑布流 + 预览操作栏 + 长按震动** | ✅ 已完成 | LazyVerticalStaggeredGrid Adaptive(110dp)；圆角 16dp + shadow；底部操作栏编辑/分享/删除/详情；scaleIn/fadeIn 过渡；HapticFeedbackType.LongPress |
| **NS-04: 图片编辑 + 滤镜** | ✅ 已完成 | 裁剪框可拖拽调整；90° 步进 + 自由角度滑块；5 种滤镜（原图/黑白/暖色/冷色/复古）ColorMatrix 实现 |
| **NS-05: 批量操作 + 收藏** | ✅ 已完成 | 选择模式底栏全选/取消全选/批量分享/批量删除/批量上传；收藏 expect/actual FavoriteStore + 后端 API |
| **NS-06: 后端收藏 API + 元数据** | ✅ 已完成 | POST /api/media/favorite + GET /favorites + POST /favorite-batch；后端 FavoriteStore JSON 持久化；GetMediaList 支持 favorite=true 过滤；fillDimensions 填充 width/height/exif |
| **NS-07: 后端性能 + 缓存** | ✅ 已完成 | ThumbCache LRU（100 项/512KB 上限）；GetMediaList 30s TTL 缓存 + dirMtime 失效；video-meta 持久化；/healthz 返回 media_count/uptime/cache/favorite_count |
| **NS-08: 全量 QA 验收** | ✅ 本报告即产出 |
| **NS-09: Bug 修复** | ⚠️ 部分 | 多数 P0/P1 已修，但 2 个 P0 遗留（详见下文） |

---

## 已知问题（P0/P1/P2）

### P0（阻断性，必须修复方可发布）

#### P0-1. 上传接口前后端协议仍未对齐 — 前端发 JSON 数组、后端按 raw bytes 写盘

**证据**：
- 前端 `MediaService.kt:183-187`：仍用 `contentType(ContentType.Application.Json)` + `setBody(buildJsonObject { put("data", Json.encodeToJsonElement(fileData.toList())) })` — 把 ByteArray 编码为 JSON 数字数组。
- 后端 `server.go:354-356`：`io.ReadAll(r.Body)` 直接写盘，`os.WriteFile(uploadPath, body, 0644)`。

**后果**：落盘文件是 JSON 文本 `{"filename":...,"data":[...]}` 而非图片/视频字节。上传的"媒体"不可被 image.Decode/ffmpeg 识别，stream 端点返回 JSON 文本，缩略图生成失败。这是 Sprint 1 QA P0-2 的**未修复遗留**。

**修复方案**：前端 uploadMedia 改发 raw body（`setBody(fileData)` + `contentType(ContentType.Application.OctetStream)`），filename 走 query 参数（后端已从 `?filename=` 读取）。

**严重度**：P0。上传功能实际不可用（产出垃圾数据）。

#### P0-2. 后端 DeleteMedia 对不存在的 ID 仍返回 "success"

**证据**：`media_service.go` DeleteMedia 方法：遍历 `req.MediaIds`，若 glob 未匹配或 `os.Remove` 失败，`deletedCount` 不增；但最终无条件返回 `Status: "success"` + `DeletedCount: 0`。对完全不存在的 mediaId，前端收到 200 + success，无法区分"删了"和"没找到"。

**后果**：与前端 `deleteMedia` 返回 `response.status == HttpStatusCode.OK` 叠加，用户删除不存在的媒体也提示成功。虽不如 Sprint 1 的"假成功"严重（异常现在返回 false），但在数据正确性语义上仍是 P0。

**修复方案**：`deletedCount == 0` 时返回 `Status: "not_found"` 或 HTTP 404。

**严重度**：P0（数据语义正确性）。

### P1（重要，影响体验或稳定性）

#### P1-1. iOS 缩略图 `synchronous = true` 未修复 — 预览卡死风险

**证据**：`ThumbnailLoader.ios.kt:31,88` 仍为 `synchronous = true`。Sprint 1 QA P1-8 明确指出此问题，未修复。

**后果**：PHImageManager 同步模式在网络拉取 HEIC 原图且设备未就绪时回调可能不触发，`suspendCancellableCoroutine` 不 resume → 协程永久挂起 → 预览卡死。

**严重度**：P1。

#### P1-2. iOS VideoPlayer `NSURL.URLWithString()!!` 强解包未修复

**证据**：`VideoPlayer.ios.kt:78` `AVPlayer(uRL = NSURL.URLWithString(backendStreamUrl(media.id))!!)`。mediaId 含中文/空格/特殊字符时 NSURL 返回 null，`!!` 崩溃。mediaId 未做 URL encoding。Sprint 1 QA P2-1，升级为 P1（崩溃风险）。

**严重度**：P1。

#### P1-3. ViewModel scope 永不取消 — 协程泄漏

**证据**：`MediaViewModel.kt:45` `CoroutineScope(Dispatchers.Main)` 无 `onCleared()` 调用 `cancel()`。App.kt 顶层 `private val viewModel = MediaViewModel()` 是进程级单例。导航离开后挂起网络任务仍跑完写状态。

**严重度**：P1。

#### P1-4. LivePhotoHandler 每次点击 new 裸 CoroutineScope

**证据**：`LivePhotoHandler.kt:45` `CoroutineScope(Dispatchers.Default).launch { videoUrl = null // TODO: 后端 API 待实现 }` — 每点一次泄漏一个 scope，且只设 null 不做实际工作。Sprint 1 QA P1-6 未修复。

**严重度**：P1。

#### P1-5. 本地相册两端仍只取图片，不取视频

**证据**：
- Android `PhotoGalleryService.android.kt:32` 查 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`（仅图片）。
- iOS `PhotoGalleryService.ios.kt:53` `PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, ...)`（仅图片）。
- 本地 Tab 选 `VIDEO` 筛选永远空结果。Sprint 1 QA P1-7 未修复。

**严重度**：P1（功能缺失）。

#### P1-6. 上传文件名丢失 — 前端塞 JSON body、后端从 query 取

**证据**：前端 `MediaService.kt:185` 把 `filename` 放进 JSON body，但后端 `server.go:338` 从 `r.URL.Query().Get("filename")` 取。前端 URL 中未附加 filename query 参数。结果：后端 filename 始终为 `"upload.dat"`，文件名信息丢失。（与 P0-1 同一根因，但影响面不同。）

**严重度**：P1（_metadata 不正确）。

### P2（建议改进）

| # | 问题 | 说明 |
|---|---|---|
| P2-1 | `gen/` 目录未提交 | protobuf 生成代码不在仓库中，新开发者需手动运行 `generate_proto.sh`；建议提交或加 CI 步骤 |
| P2-2 | `TabRow` deprecated | MediaListScreen.kt:225 使用已废弃的 TabRow，建议迁移 PrimaryTabRow |
| P2-3 | `CropOverlay.kt` 冗余 else | 行 243/265 `when` 已 exhaustive，else 多余 |
| P2-4 | expect/actual classes Beta 警告 | SettingsStorage.kt + rn-module 共 8 处，建议加 `-Xexpect-actual-classes` |
| P2-5 | 后端 cloud list IMAGE 哨兵语义 | `FilterType=IMAGE` 作为"不过滤"哨兵，前端无法显式筛"仅图片排除视频" |
| P2-6 | 视频时长串行预取 | `prefetchVideoDurations` 串行 + 15s/项，N 个视频最坏 N×15s |
| P2-7 | `MainActivity.onCreate` 调用顺序 | `enableEdgeToEdge()` 在 `super.onCreate` 之前 |
| P2-8 | KLIB 重复库警告 | 编译时大量 `unique_name found in more than one library`，拖慢编译 |

---

## 代码质量评估

### 后端（Go）

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构清晰度 | A | gRPC + REST gateway 分层明确，service/gateway/cmd 职责清晰 |
| 错误处理 | B- | 大部分端点有错误处理，但 DeleteMedia 语义不精确（P0-2） |
| 线程安全 | A | sync.Mutex/RWMutex/atomic 使用正确，FavoriteStore/ThumbCache/ListCache 均有锁保护 |
| 安全性 | A- | 所有路径参数端点有 `..` 和 `/` 穿越检查（stream/thumbnail/metadata/video-info/favorite/favorite-batch） |
| 可观测性 | B+ | /healthz 返回 media_count/uptime/cache/favorite_count，ListCacheStats 有 hit/miss 计数 |
| 代码注释 | A | 中文注释详尽，每个函数有文档注释，设计决策有说明 |
| 测试覆盖 | F | 无测试文件（零 _test.go） |

### 前端（Kotlin Multiplatform）

| 维度 | 评分 | 说明 |
|---|---|---|
| 架构清晰度 | B+ | feature-media/feature-common/composeApp 模块化，但部分逻辑集中在 MediaListScreen（1600+ 行） |
| Compose 惯用法 | A- | remember(media.id) key 正确，derivedStateOf 缓存，AnimatedContent/Crossfade 使用规范 |
| 线程安全 | B | ViewModel 改用 Dispatchers.Main（Sprint 1 P1-2 已修复），但 scope 无 cancel（P1-3） |
| 内存管理 | B+ | BackendImageLoader LRU + Mutex + putBounded；但 fullImageCache 淘汰策略依赖同一个 maxSize 可能偏小 |
| 跨端一致性 | B | expect/actual 齐全，但 iOS synchronous=true（P1-1）和 NSURL!!（P1-2）未修复 |
| 代码注释 | A | 中文注释充分，设计意图清晰 |
| 测试覆盖 | F | 无测试 |

---

## 跨端兼容性

| 维度 | Android | iOS | 一致性 |
|---|---|---|---|
| VideoPlayer | VideoView + Range 支持 | AVPlayer + AVPlayerLayer | ✅ 控件同构 |
| 缩略图加载 | MediaStore | PHAsset | ✅ |
| 图片解码 | BitmapFactory/skia | skia | ✅ |
| 设置存储 | SharedPreferences | NSUserDefaults | ✅ |
| 动态色调 | dynamicColor (API 31+) | 回退中性色板 | ✅ 设计如此 |
| 时区 | TimeZone.getDefault().rawOffset | NSTimeZone | ✅ expect/actual 一致 |
| **后端地址** | SettingsState.backendUrl → MediaService + VideoPlayer | 同 | ✅ **NS-01 已修复** |
| **iOS NSURL!!** | — | `URLWithString()!!` 崩溃风险 | ❌ P1-2 |
| **iOS synchronous** | — | `synchronous = true` 卡死风险 | ❌ P1-1 |
| **本地视频** | MediaStore.Images 仅图片 | PHAssetMediaTypeImage 仅图片 | ❌ P1-5 两端均缺视频 |
| commonMain 纯净度 | — | — | ✅ 零 java.*/android.* 导入 |

---

## 路径穿越防护审计

| 端点 | 检查 | 结果 |
|---|---|---|
| `/api/media/stream/{id}` | `strings.Contains(mediaID, "..")` + `strings.Contains(mediaID, "/")` | ✅ |
| `/api/media/thumbnail/{id}` | 同上 | ✅ |
| `/api/media/metadata/{id}` | 同上 | ✅ |
| `/api/media/video-info/{id}` | 同上 | ✅ |
| `/api/media/favorite` (POST) | 同上（media_id 字段） | ✅ |
| `/api/media/favorite-batch` (POST) | 同上（遍历 media_ids 列表） | ✅ |
| `/api/media/delete` (POST) | 后端 service 层 `DeleteMedia` 有 `..`/`/` 检查 | ✅ |
| `/api/media/upload` | filename 从 query 取，用 `filepath.Ext()` 提取扩展名，不直接拼用户输入到路径 | ✅ |
| `/api/openclaw/command` | `strings.HasPrefix(req.Path, "/")` + `strings.Contains(req.Path, "..")` | ✅ |
| `resolveMediaPath` (内部) | `..`/`/` 检查 | ✅ |
| `loadVideoMeta`/`saveVideoMeta` | `..`/`/` 检查 | ✅ |

**结论**：所有用户输入到文件系统路径的入口均有路径穿越防护。✅

---

## 最终建议

### 必须修复（P0，阻断发布）

1. **上传协议对齐**：前端 `uploadMedia` 改发 raw body（`setBody(fileData)` + OctetStream），filename 走 query 参数。当前前端发 JSON 数组、后端按 raw bytes 写盘，上传产出垃圾文件。
2. **DeleteMedia 语义**：`deletedCount == 0` 时返回非 success 状态或 404，让前端能正确区分"已删除"与"不存在"。

### 重要修复（P1，影响体验/稳定性）

3. **iOS ThumbnailLoader** `synchronous = true` → `false`，避免预览卡死。
4. **iOS VideoPlayer** `NSURL.URLWithString()!!` → null 安全处理 + URL encoding。
5. **ViewModel scope** 添加 `onCleared { viewModelScope.cancel() }` 或绑定生命周期。
6. **LivePhotoHandler** 删除裸 `CoroutineScope(Dispatchers.Default).launch`，改用受控 scope 或直接删除 TODO 空逻辑。
7. **本地相册补视频**：Android `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` + iOS `PHAssetMediaTypeVideo`。

### 改进（P2）

8. 将 `backend/gen/` 提交到仓库或添加 CI 生成步骤。
9. 迁移 `TabRow` → `PrimaryTabRow`。
10. 添加单元测试/集成测试（当前零测试覆盖）。
11. 视频时长预取改并发限流。
12. 添加 `-Xexpect-actual-classes` 消除 Beta 警告。

### 总结

Sprint 1 QA 报告中的 4 个 P0 已修复 2 个（后端地址生效 P0-1 ✅、时区 P0-4 ✅），遗留 1 个（上传协议 P0-2 未修），新增 1 个（DeleteMedia 语义 P0-2）。P1 从 8 个减少到 6 个（remember key ✅、Dispatchers.Main ✅ 已修），但 iOS synchronous、NSURL!!、scope 泄漏、LivePhotoHandler、本地视频 5 项未修。

**在修复 P0-1（上传协议）和 P0-2（DeleteMedia 语义）后，项目可达 PASS。** 这两个问题的修复成本极低（各约 5 行代码），但影响面大。建议立即修复后重新验收。
