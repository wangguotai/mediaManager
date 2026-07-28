# Sprint 1 体验验收报告

> 验收人：reality-checker（体验验收专家）
> 验收日期：2026-07-28
> 验收范围：Sprint 1 全部 7 个已 merge 任务（UI 精美化、搜索筛选、视频支持、设置页、日期分组、详情面板、服务端访问）
> 验收方法：逐文件代码审查 + 后端 `go build`/`go vet` + 前端 `compileDebugKotlinAndroid` 三次独立强制重编（含 `--rerun-tasks --no-build-cache`）+ 对照 PRD 逐项核验 + 只读 subagent 交叉复核（18 条候选发现逐一裁决）

诚实声明：本报告不因"构建通过"就放过。构建通过只能证明代码能编译，不能证明功能正确；而且"能编译"本身也经过三次重编确证。下方问题均在源码中找到行级证据，部分用重编/编译产物交叉验证。对 subagent 的候选发现做了裁决——采纳 14 条、否决 4 条（含 1 条 P0 误报），裁决理由均注明。

---

## 总评定: CONDITIONAL PASS（有条件通过）

Sprint 1 在"看得见的 UI 层"上达到了 PRD 的大部分验收标准——Splash、动态色调、动画、搜索筛选、日期分组 sticky header、视频播放器、详情面板上滑手势均已实现且**经三次强制重编验证编译通过**。但有 **4 个 P0 阻断性问题**（地址配置失效、上传协议错位、上传/删除假成功、日期分组时区错位）与若干 P1 问题，使多个核心功能在生产语义下**实际不工作或会出错**。

在修复 P0 之前，不应作为"可用产品"对外发布。修复 P0+P1 后可达 PASS。

---

## 通过项

- **Splash 启动屏**：`SplashScreen.kt` 实现"媒体管家"居中淡入 + scaleIn，约 2s 后 fadeOut，经 `AnimatedContent` 交叉淡入淡出到主界面。`App.kt:54-72` 衔接正确，无突入。✅ 符合 PRD"启动屏"。
- **Material3 动态色调**：`ColorSchemes.kt` + 平台 actual（`ColorSchemes.android.kt` 取 Android 12+ dynamicColor，iOS/低版本回退中性绿色板 `FallbackLightColors/DarkColors`）。`App.kt:48` 统一入口 `resolveColorScheme(isDark)`。✅ 符合"动态色调适配"。
- **暗色/亮色/跟随系统主题**：`SettingsState.themeMode`（SYSTEM/LIGHT/DARK）从 `SettingsStorage` 读回并驱动 `App.kt:43-47`。点选即落地，即时切换色板。✅ 主题切换真实生效。
- **网格项加载渐入动画（shimmer）**：`MediaListScreen.kt:997-1027` `ShimmerPlaceholder` 用 `rememberInfiniteTransition` 驱动高光左→右扫过，网格项加载态、全屏加载态、预览加载窄条三处复用。✅ 符合"网格项加载渐入动画"。
- **选中态动画**：`MediaGridItem` 选中 spring 缩放 1.04f + primary 边框 + 角落勾选 + 18% 蒙层。✅ 选择反馈动画。
- **Tab 切换动画**：`Crossfade(tween(280))` 包裹网格区。✅ 页面切换动画。
- **搜索栏可展开/收起 + debounce 300ms**：`SearchBar.kt` 收起态仅图标，展开态自动聚焦唤起键盘，`snapshotFlow{queryText}.distinctUntilChanged()` + `delay(300)` 去抖。✅ 符合"顶部搜索栏可展开/收起""按文件名关键词搜索"。
- **类型筛选**：`FilterChips.kt` 全部/图片/视频三选一，与搜索叠加生效（`MediaViewModel.filteredList` 派生）。✅ 符合"按类型筛选"。
- **搜索无结果占位**：`NoSearchResultView` 按关键词/类型/二者组合给出动态副文 + 清除筛选。✅ 空态处理完善。
- **日期分组 sticky header**：`DateGroupedGrid.kt` 用 `LazyVerticalGrid` + `stickyHeader`，吸顶标题带淡入+下落动画。**经三次强制重编验证 `stickyHeader` 调用可编译、机制可用**（详见"裁决记录 #1"）。✅ 机制符合"sticky header 悬停"。
- **视频网格标识**：`MediaGridItem` 视频项居中播放图标 + 右下时长徽标（`formatDuration`）。
- **视频播放器（Android）**：`VideoPlayer.android.kt` 用 `VideoView`，支持播放/暂停、可拖拽 `Slider`+`seekTo`、当前/总时长、`DisposableEffect` 释放。后端 `http.ServeFile` 原生支持 HTTP Range（`server.go:167`），可边下边播/拖拽。✅ 符合"播放器支持播放/暂停/进度条/全屏"。
- **视频播放器（iOS）**：`VideoPlayer.ios.kt` 用 `AVPlayer`+`AVPlayerLayer`+`UIKitView`，可拖拽进度条、`seekToTime`、`DisposableEffect` pause。两端控件同构。
- **详情面板上滑展开**：`DetailPanel.kt` 用 `Animatable` + `detectVerticalDragGestures`，松手 spring 吸附 0f/1f，半透明遮罩随展开度加深。✅ 符合"底部可展开详情面板""上滑展开"。
- **后端视频缩略图（ffmpeg）**：`media_service.go:498-555` 用 ffmpeg `-ss 00:00:01` 抽帧 + scale 缩放，缓存为 jpg。✅ 符合"后端支持视频缩略图生成（ffmpeg 抽帧）"。
- **后端 video-info（ffprobe）**：`media_service.go:575-642` + gateway `/api/media/video-info/{id}`（`server.go:173-198`），返回时长/分辨率/编码/容器，15s 超时。前端 `MediaViewModel.prefetchVideoDurations` 预取缓存。✅
- **后端构建洁净**：`go build ./...` 与 `go vet ./...` 均 exit 0，无警告无错误。
- **前端 commonMain→android 编译通过**：`compileDebugKotlinAndroid` 三次重编（含 `--rerun-tasks --no-build-cache`）均 `BUILD SUCCESSFUL`，**`error:` 行数 = 0**。
- **网盘图片/视频 list 真实加载**：`MediaService.getMediaList(cloud=true)` 附加 `q=source=cloud`，命中后端 `LocalCloudSource`（`cloud_source.go`），视频与图片扩展名同表收录。✅ 符合"网盘视频列表加载"。
- **错误状态分层**：`MediaListScreen` 的 `when` 优先级把"加载失败(ErrorStateView+重试)" / "加载中" / "真无数据" / "搜索无结果" 四态互斥处理，避免白屏误导。✅ 错误态处理较完善。
- **沉浸式 edge-to-edge**：`MainActivity.kt:12` `enableEdgeToEdge()`，内容延伸到系统栏；顶栏/网格区由 Material3 `Scaffold` 默认应用 `systemBars` inset（`MediaListScreen`/`SettingsScreen` 均用 Scaffold，未手写 windowInsets 但依赖 Scaffold 默认行为），状态栏空间已让出。✅ 符合"沉浸式状态栏"。

---

## 问题清单（按严重程度排序）

### P0 (阻断性)

#### P0-1. 后端地址配置功能形同虚设 —— 三套硬编码地址互不一致、均不读设置页
**证据（3 个独立硬编码来源）**：
1. `MediaService.kt:19` `private const val BASE_URL = "http://localhost:8080"` —— list/stream/thumbnail/delete/upload/openclaw 全请求（行 58/88/107/128/144/172）都拼这个常量，从不引用 `SettingsState.backendUrl`。全模块 grep 确认 `SettingsState`/`backendUrl` 在 `feature-media`、`feature-common` 内**零引用**。`SettingsState.saveBackendUrl` 只写 storage，没人读。
2. `VideoPlayer.kt:20` `internal const val VIDEO_BACKEND_BASE_URL = "http://10.0.2.2:8080"` —— `backendStreamUrl()`（行 29）与 `MediaViewModel.prefetchVideoDurations`（行 433）都用它。
3. `SettingsScreen.kt:100` 默认值 `http://10.0.2.2:8080`（与 #2 同值，与 #1 不同）。`BackendConnectivity.kt` 注释自承"MediaService 写死了 BASE_URL…无法复用设置页输入的地址"。

**连通性后果**：
- 图片列表/缩略图走 `localhost`：Android 模拟器需 `adb reverse`；iOS 模拟器 `localhost` 指向宿主机能直连，但 iOS 真机 `localhost` 指向自身必不通。
- 视频流走 `10.0.2.2`：**仅 Android 模拟器有效**，iOS 模拟器/真机一律不通 → **iOS 视频播放必然失败**。
- 同一 App 在同一设备上，图片能播视频不能播（或反之），且用户在设置页改地址后**毫无作用**。

PRD 验收标准"后端地址配置（输入框 + 保存 + 连通性测试）"只完成了"保存 + 连通性测试"，**最关键的"配置生效"未实现**。

**严重度**：P0。架构性断裂，全链路走死地址，用户无法自救。

---

#### P0-2. 上传接口前后端协议错位 —— 前端发 JSON、后端按 raw bytes 写盘，上传的"图片"实为 JSON 字符串
**证据**：
- 前端 `MediaService.kt:142-150`：`contentType(ContentType.Application.Json)` + `setBody(buildJsonObject { put("filename",...); put("is_live_photo",...); put("data", Json.encodeToJsonElement(fileData.toList())) })` —— 把整个字节数组编码成 JSON 数字数组塞进 body。
- 后端 `server.go:290-305`：`io.ReadAll(r.Body)` 把 body **整个当文件内容**写盘 `os.WriteFile(uploadPath, body, 0644)`，filename 从 query `?filename=` 取。

**后果**：写到 uploads 目录的"图片"文件其实是一段 JSON 文本 `{"filename":...,"data":[...]}`。后端日后 `image.Decode` 必失败，stream 端点返回的是 JSON 文本而非图片字节，缩略图生成也失败。即"上传"功能表面走通（HTTP 200 + 假成功），实际产出的是损坏文件。另外 filename 前端塞进 body 而后端从 query 取（`server.go:295`），文件名也丢失，落盘为 `upload.dat`。

**严重度**：P0。上传功能实际不可用（产出垃圾数据），且因 P0-3 假成功掩盖，用户无感知。

---

#### P0-3. 删除/上传异常时返回 `true`（假成功）+ 后端 delete 对不存在的 id 仍返 success
**证据**：
- `MediaService.kt:135` `deleteMedia` catch 块 `delay(300); return true`；`MediaService.kt:155` `uploadMedia` catch 块 `delay(1000); return true`。后端不可达/DNS/拒连/超时，前端一律提示"删除成功"/"上传成功"。
- `MediaViewModel.deleteSelectedMedia`（:376-379）收到 true 即清空 `selectedMediaIds` + 从 `mediaList` 滤除——但服务端文件没删→刷新后重现，用户被误导。
- 后端 `media_service.go:301` `DeleteMedia` 即便某 id 文件不存在仍返回 `Status:"success"` HTTP 200；且 REST delete（`server.go:264-282`）经 gRPC `DeleteMedia` 只 glob uploads 目录（`media_service.go:288`），**网盘(cloud)目录的媒体删不掉**但前端提示成功。

**后果**：用户数据安全/正确性受损。网盘 Tab 删除假成功；上传失败被掩盖；与 P0-2 叠加形成"上传一份损坏文件且被告知成功"的完整错误链。

**严重度**：P0（数据安全 + 正确性）。

---

#### P0-4. 日期分组按 UTC 划分，中国时区（UTC+8）下"今天/昨天"系统性错位，详情面板拍摄时间也错
**证据**：`MediaViewModel.kt:537` `private fun localTimeZoneOffsetMillis(epochMillis: Long): Long = 0L`。注释自承"固定 0 偏移作为近似，日界按 UTC 划分"。`epochDaysFromMillis`（:520-525）与 `relativeDateTitle`（:497-503）都基于这个 0 偏移。

**UTC+8 场景验证**：
- 北京时间 07:00 拍的照片 = UTC 前一天 23:00，`floor` 算到前一天 → 显示"昨天"，但用户直觉是"今天"。
- 跨午夜累积时，大量"今天"的媒体被计为"昨天"，分组顺序与标题都错。
- `DetailPanel.kt:279-287` `formatEpochMillis` 同样按 UTC 折算年月日和时分，详情面板"拍摄日期"显示的时间比本地少 8 小时（本地 20:00 显示成 12:00）。

**后果**：PRD P1 功能项"按时间分组展示"的分组正确性不达标。目标用户在中国（UTC+8），错误高频可感知。

**严重度**：P0。修复成本低（引入 `kotlinx-datetime` 或平台 actual 提供本地偏移），但必须修。

---

### P1 (重要)

#### P1-1. 详情面板"分辨率"永远显示 "—"、"EXIF 信息"永远显示"无 EXIF 信息" —— 前后端双向缺位
**证据**：
- 后端 `GetMediaList`（`media_service.go:175-183`）、`GetMediaMetadata`（:321-329）、`GetMediaStream` 的 metadata（:364-372）、`cloud_source.go:80-88` 构造 `MediaMetadata` 时**从不设置** `Width`/`Height`/`ExifData`/`IsLivePhoto`（仅缩略图响应 `GetThumbnailResponse` 填了 width/height，那是另一条消息）。
- 前端 `MediaService.parseMediaList`（`MediaService.kt:189-207`）解析时**也没解析 `exif_data` 字段**，构造 `MediaMetadata` 不传 `exif_data`，永远默认空 map。
- proto 字段存在（`MediaMetadata.kt` width tag=11、height tag=12、exif_data tag=8），但双向都不填/不读。
- 前端 `DetailPanel.kt:197-199` `分辨率 = if (width>0&&height>0) "W×H" else "—"` → 因后端给 0，**永远 "—"**；`:209` `if (exif_data.isNotEmpty())` → **永远 else"无 EXIF 信息"**；`MediaListScreen.kt:544` 预览顶栏 → **永远 "0x0"**。

**后果**：PRD 详情页验收标准"显示分辨率""显示 EXIF 信息"两项实际未达标。需后端接 EXIF 解析库 + 在 list/metadata 时解码图片头填 width/height，前端 parseMediaList 补 exif_data 解析。

**严重度**：P1。

---

#### P1-2. ViewModel 在 `Dispatchers.Default` 上直接写 Compose 状态 —— 快照线程安全隐患
**证据**：`MediaViewModel.kt:41` `viewModelScope = CoroutineScope(Dispatchers.Default)`。该 scope 的 `launch{}` 在 Default 线程上**直接赋值** `mutableStateOf`：`mediaList=...`（:206/247/296）、`isLoading`（:213）、`listLoadError`（:209/254/300）、`isCloudLoading`、`isGalleryLoading`、`videoDurations`（:438）、`selectedMediaIds.clear()`（:379）、`hasGalleryPermission`（:293）。

Compose `mutableStateOf` 对并发写不线程安全；从非 Main 线程持续写未包 `Snapshot.withMutableSnapshot{}`，存在 "snapshot race"——偶发重组丢失或潜在 `IllegalStateException`。

**严重度**：P1。非必现但真实存在，影响稳定性观感。

---

#### P1-3. 图片内存缓存无上限、无淘汰，且单例 + 非线程安全 map，长时浏览 OOM/ConcurrentModification 风险
**证据**：`BackendImageLoader.kt:25-26` `thumbnailCache`/`fullImageCache` 是普通 `mutableMapOf<String, ImageBitmap>`，put 后从不 remove，注释自承"缓存无上限"。`fullImageCache` 缓存**原图解码 bitmap**，单张几 MB，预览左右滑几十张即累积上百 MB。单例 object + 非 concurrent map + 多协程并发 put 有 `ConcurrentModificationException` 风险。`MediaViewModel.videoDurations` 同理增量复制整个 Map 且不清理。`MediaGridItem` 缩略图 `remember{}` 无 key 加剧内存压力。

**严重度**：P1。建议 LRU + size 上限，或至少 fullImageCache 限容，map 换 `ConcurrentHashMap` 或加锁。

---

#### P1-4. 网格项缩略图 `remember{}` 未带 mediaId key，LazyGrid 项复用导致缩略图错位/闪烁
**证据**：`MediaListScreen.kt:779-780` `var thumbnailBitmap by remember{...}` / `var isLoading by remember{...}` 无 key。LazyGrid 复用 Composable 实例时，新 media 先显示上一张的 `thumbnailBitmap`（错位），且 `isLoading` 沿袭 false 导致不显示 shimmer 而闪现旧图。对比 `ZoomableImage`（:603-607）正确用了 `remember(media.id)`。

**严重度**：P1。修复：`remember(media.id)`。

---

#### P1-5. 两个 ViewModel 的协程 scope 永不取消，泄漏
**证据**：`MediaViewModel.kt:41` `CoroutineScope(Dispatchers.Default)` 无 `SupervisorJob`/`cancel()`；`OpenClawViewModel.kt:29` `CoroutineScope(dispatchers.main)` 同样无取消。`App.kt:23` 顶层 `private val viewModel = MediaViewModel()` 是进程级单例，`viewModelScope` 永不 cancel。导航离开后挂起的网络任务仍跑完写状态，引用链保活 VM。

**严重度**：P1。建议绑定生命周期或显式 cancel 机制。

---

#### P1-6. LivePhotoHandler 每次点击 new 裸 CoroutineScope 跑空逻辑
**证据**：`LivePhotoHandler.kt:45-47` `CoroutineScope(Dispatchers.Default).launch { videoUrl = null // TODO: 后端 API 待实现 }` —— 每点一次 Live 图泄漏一个 scope，且只把 videoUrl 设 null 没做任何实际工作。`handleLivePhotoUpload`（:110-125）/`handleLivePhotoDelete`（:130-139）编造 video id（`${id}_video`）+ 依赖 P0-3 假成功 → 假数据链。

**严重度**：P1。删除裸 launch 或改用受控 scope。

---

#### P1-7. 本地相册两端数据源都只取图片，不取视频 —— VIDEO 筛选在本地 Tab 永远空
**证据**：iOS `PhotoGalleryService.ios.kt:52` `PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, ...)` 只拉图片；Android `PhotoGalleryService.android.kt:30` 查 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 也只图片。但前端筛选条有 `MediaFilterType.VIDEO`（`MediaViewModel.kt:87`），本地 Tab 选"视频"必为空结果。PRD 视频支持针对网盘视频有效，本地视频未覆盖。

**严重度**：P1。本地视频浏览未实现（PRD 视频验收主要指网盘，但本地 VIDEO 筛选项是死选项）。

---

#### P1-8. iOS 缩略图/原图加载 `PHImageRequestOptions.synchronous = true` 可致回调不 resume、预览卡死
**证据**：`ThumbnailLoader.ios.kt:31,88` `synchronous = true` + `networkAccessAllowed = true` + `deliveryMode = HighQualityFormat`。PHImageManager 同步模式在网络拉取 HEIC 原图且设备未就绪时回调可能迟迟不触发，`suspendCancellableCoroutine` 不 resume → 协程永久挂起 → 预览卡死。对比 `PhotoGalleryService.ios.kt:185` 的 `getImageData` 用 `synchronous = false`，两处不一致暗示 ThumbnailLoader 是误用。

**严重度**：P1。建议改 `synchronous = false`。

---

### P2 (建议改进)

- **P2-1. VideoPlayer(iOS) `NSURL.URLWithString(...)`!! 强解包**（`VideoPlayer.ios.kt:72`）：mediaId 含中文/空格时 NSURL 返回 null，`!!` 崩溃；mediaId 未做 URL encoding。建议 `URLWithString` 返回 null 时降级提示而非 crash。
- **P2-2. VideoPlayer(Android) 错误态无用户提示**（`VideoPlayer.android.kt:90-94`）：`onErrorListener` 返回 true 仅设 `isPrepared=false`，画面停在"加载中"转圈，用户误以为卡顿实为失败。建议加错误态 UI。
- **P2-3. 后端 cloud list 过滤哨兵用 IMAGE 而非 ALL**（`media_service.go:244` + `server.go:104`）：以 IMAGE 作为"未设过滤"哨兵，导致前端无法显式筛"仅图片排除视频"，与前端 `MediaFilterType.IMAGE`（不含视频）语义不一致。
- **P2-4. 启用视频时长串行预取**（`MediaViewModel.prefetchVideoDurations` :431）：串行 + 每项 15s 超时，N 个视频最坏 N×15s 才全部到位，时长徽标缓慢显现。建议并发 + 限流。
- **P2-5. `MainActivity.onCreate` `enableEdgeToEdge()` 在 `super.onCreate` 之前调用**（`MainActivity.kt:11-14`）：顺序反常，可能首次绘制 inset 未就绪瞬时闪烁。注：顶栏 inset 由 Scaffold 默认 `systemBars` 兜底，不会画到状态栏底下，故仅顺序瑕疵，非"适配缺失"。
- **P2-6. `SplashScreen.entered` 死变量**（`SplashScreen.kt:28`）：恒为 true，`visible && entered` 等价 `visible`，可删。
- **P2-7. `DateGroupHeader` 在组合体内直接赋值 `visible = true`**（`DateGroupedGrid.kt:112-113`）：非常规写法，建议 `LaunchedEffect(Unit){visible=true}`。
- **P2-8. `App.kt` 顶层 `private val viewModel = MediaViewModel()` 进程级单例**（`App.kt:23`）：与 P1-5 叠加，scope 永不 cancel。建议绑定生命周期。
- **P2-9. rn-module `expect/actual class` Beta 警告**（PlatformTypes.kt/RnManager.kt 共 8 处）：建议加 `-Xexpect-actual-classes` 或重构。
- **P2-10. KLIB 重复库警告**（shared/feature-common/rn-module 编译时大量 `unique_name=... found in more than one library`，androidx.* 与 org.jetbrains.compose.* 同名 klib）：拖慢编译、增体积，建议理顺依赖排除。
- **P2-11. 搜索结果高亮未实现**：`MediaGridItem` 文件名 `Text` 无高亮 spans，PRD 验收标准"搜索结果高亮"未覆盖。
- **P2-12. `OpenClawCommandDialog` 用 `derivedStateOf` 包已是 State 的属性**（`:40-41`）：`viewModel.result`/`isSending` 本身是 `mutableStateOf`，外层 derived 冗余且可能取过时值。
- **P2-13. `OpenClawCommandDialog` `Dialog(usePlatformDefaultWidth=false)` + `Surface.fillMaxSize()`**（`:43-56`）：桌面/大屏占满整屏。
- **P2-14. Android Live Photo 检测靠文件名启发式**（`PhotoGalleryService.android.kt:57-58` `name.contains("IMG_") && endsWith(".HEIC")`）：误判普通 HEIC 为 Live Photo，且 `live_photo_video_id = "${id}_video"` 编造，与 P0-3 叠加形成假数据链。
- **P2-15. OpenClaw 桥梁入口占据主 TopAppBar 操作位**（`MediaListScreen.kt:171-176`）：PRD Sprint 1 清单无此功能，属范围漂移，挤占设置/刷新空间。建议移至设置页/二级入口。

---

## 裁决记录（subagent 候选发现 18 条的逐条裁决）

为避免单一视角误判，引入只读 subagent 做交叉复核，产生 18 条候选发现。逐条裁决如下（采纳 14 / 否决 3 / 合并 1）：

| # | subagent 判定 | 裁决 | 依据 |
|---|---|---|---|
| 1 | P0-3 stickyHeader 缺 import，"确定编译不过" | **否决** | 三次强制重编（`--rerun-tasks --no-build-cache`）均 `BUILD SUCCESSFUL`、`error:` 行数 0、无 unresolved。subagent 为纯静态推断，对 Compose Multiplatform 1.10 包内扩展解析判断失误。stickyHeader 可编译可吸顶。 |
| 2 | P0-1 地址三套硬编码 | 采纳 → **P0-1** | 行级证据确凿，本人独立复核一致。 |
| 3 | P0-4 upload JSON vs raw bytes | 采纳 → **P0-2** | 独立复核 `MediaService.kt:144-150` 与 `server.go:290` 一致，协议确实错位。 |
| 4 | P0-5 delete/upload 假成功 | 采纳 → **P0-3**（升 P0） | 本人原判 P1，subagent 指出其涉数据安全 + 与网盘 delete 假成功联动，升 P0 更准确。 |
| 5 | P1-2 时区偏移恒 0 | 采纳 → **P0-4**（升 P0） | 本人原判 P0，一致。subagent 补充 DetailPanel 详情时间同错，已纳入。 |
| 6 | P1-6 exif 双向缺位 | 采纳 → **P1-1** | 本人已发现后端不填，subagent 补充前端 `parseMediaList` 也不解析 exif_data，双向缺位属实。 |
| 7 | P1-7 缓存无上限 + 非线程安全 map | 采纳 → **P1-3** | 本人已列缓存无上限，subagent 补充非 concurrent map 并发风险，合并。 |
| 8 | P1-9 两个 VM scope 不取消 | 采纳 → **P1-5** | 本人已列 MediaViewModel，subagent 补充 OpenClawViewModel，合并。 |
| 9 | P1-10 LivePhotoHandler 裸 scope | 采纳 → **P1-6** | 本人独立复核 `LivePhotoHandler.kt:45` 确认属实。 |
| 10 | P1-11 enableEdgeToEdge + "沉浸式适配缺失" | **部分否决** | 顺序问题属实（降 P2-5），但"沉浸式适配缺失/顶栏画到状态栏底"被否决：MediaListScreen/SettingsScreen 均用 Material3 `Scaffold`，默认应用 `systemBars` inset，顶栏已让出状态栏空间。subagent 未核实 Scaffold 默认行为，夸大了。 |
| 11 | P1-12 iOS synchronous=true | 采纳 → **P1-8** | iOS 平台 API 风险属实，subagent 与 PhotoGalleryService 对比佐证。 |
| 12 | P1-16 本地相册两端无视频 | 采纳 → **P1-7** | 本人未查 GalleryFeature，subagent 发现属实（iOS fetch Image only / Android Images URI）。真功能缺漏。 |
| 13 | P2-13 iOS NSURL!! 强解包 | 采纳 → **P2-1** | 属实。 |
| 14 | P2-14 Android VideoView 错误无提示 | 采纳 → **P2-2** | 属实。 |
| 15 | P2-15 Dialog fillMaxSize | 采纳 → **P2-13** | 属实。 |
| 16 | P2-17 Android Live Photo 启发式 | 采纳 → **P2-14** | 属实。 |
| 17 | P2-18 opportunistic+synchronous 缩略图模糊 | 采纳并并入 **P1-8/P2** | 与 synchronous 问题同源。 |
| 18 | P2-8 derivedStateOf 冗余 | 采纳 → **P2-12** | 属实。 |

**关键裁决**：唯一被否决的 P0 是 #1（stickyHeader 编译错误）。这是整个验收中最需诚实对待的一点——一个"看起来明显缺 import 所以必编译不过"的判断，被三次实际重编推翻。构建通过≠功能正确，但"看起来该编译不过"也不等于"真编译不过"，唯有实跑可裁决。

---

## 跨端一致性评估

| 维度 | Android | iOS | 一致性 |
|---|---|---|---|
| VideoPlayer | `VideoView`+Slider+seekTo | `AVPlayer`+AVPlayerLayer+`seekToTime`+Slider | ✅ 控件同构 |
| 缩略图加载 | MediaStore | PHAsset | ✅ expect/actual 齐全 |
| 图片解码 | BitmapFactory | skia | ✅ |
| 视频信息加载 | HttpURLConnection | NSURLSession | ✅ |
| 设置存储 | SharedPreferences | NSUserDefaults | ✅ |
| 连通性测试 | HEAD | HEAD | ✅ |
| 动态色调 | dynamicColor (API 31+) | 回退中性色板 | ✅ 设计如此 |
| commonMain 平台 API | — | — | ✅ 未见 commonMain 直接用 java.*/android.*；日期算法刻意用 Howard Hinnant 纯整数版规避 java.time（引出 P0-4 时区问题） |
| **后端地址** | localhost(MediaService) + 10.0.2.2(VideoPlayer) | 同左 | ❌ **跨端断裂**：iOS 视频流地址天生不可用（P0-1） |
| **本地视频** | MediaStore.Images 仅图片 | PHAssetMediaTypeImage 仅图片 | ❌ 两端本地相册都无视频（P1-7） |

**结论**：跨端 actual 实现齐全、签名对齐，iOS 端无已知编译缺失。**主要跨端风险在 P0-1 的地址硬编码（iOS 视频必败）与 P1-7 本地相册两端无视频**。即"跨端能编"≠"跨端能用"。

---

## 性能评估

- **网格滚动**：`LazyVerticalGrid` + `Adaptive(110dp)` + `key={it.id}` + `animateItem()` + shimmer 占位，首屏合格。但 P1-4 的 `remember` 缺 key 在快速滚动时引入错位闪烁。
- **缩略图加载**：后端缓存落盘（`{id}_{longEdge}`），二次命中直接读文件，合格。视频缩略图首次走 ffmpeg（百 ms~秒级），15s 超时兜底。
- **视频时长预取**：P2-4 串行 + 每项 15s，最坏 N×15s，徽标缓慢显现。
- **内存**：P1-3 无界缓存是主要 OOM 风险。
- **状态派生**：`filteredList` 用 `derivedStateOf` 缓存，设计正确。
- **编译性能**：P2-10 KLIB 重复库拖慢 metadata 编译。
- **iOS 缩略图**：P1-8 synchronous=true 预览卡死风险。

---

## 建议修复项（可放入 Sprint 2）

**必须（P0，阻断发布）**
1. 后端地址真正生效：`MediaService.BASE_URL` 与 `VideoPlayer.VIDEO_BACKEND_BASE_URL` 改为从 `SettingsState.backendUrl` 读取（运行时可变），统一单一地址来源；移除 `localhost`/`10.0.2.2` 双硬编码。验证改地址后图片+视频请求都切到新地址，iOS 视频可播。
2. 上传协议对齐：前端 `uploadMedia` 改发 raw body（`ByteArray`），filename 走 query（与后端 `handleMediaUpload` 一致）；或后端改收 multipart。二选一并对齐。
3. `MediaService.deleteMedia`/`uploadMedia` 异常分支返回 `false`；后端 `DeleteMedia` 对不存在的 id 不返 success；网盘 Tab 删除应作用于 cloud 目录或禁用该入口。
4. 日期分组时区：引入 `kotlinx-datetime` 的 `TimeZone.currentSystemDefault()` 取本地偏移，修正 `localTimeZoneOffsetMillis` 与 `DetailPanel.formatEpochMillis`，使"今天/昨天"与拍摄时间在 UTC+8 正确。

**重要（P1）**
5. 后端在 list/metadata/cloud list 填充 `width`/`height`（`image.DecodeConfig` 读头）与 `exif_data`（go exif 库）；前端 `parseMediaList` 补 exif_data 解析。让详情面板分辨率/EXIF 真显示。
6. ViewModel 状态写回切 `Dispatchers.Main`（或 `Snapshot.withMutableSnapshot`），消除快照竞态。
7. `BackendImageLoader` 缓存改 LRU + 上限 + 线程安全容器。
8. `MediaGridItem` 的 `thumbnailBitmap`/`isLoading` 改 `remember(media.id)`，修复滚动错位。
9. 两个 VM 的 scope 绑定生命周期或显式 cancel；`LivePhotoHandler` 删裸 `CoroutineScope`。
10. iOS `PHImageRequestOptions.synchronous` 改 false，避免预览卡死。
11. 本地相册数据源补视频（Android MediaStore.Video + iOS PHAssetMediaTypeVideo），让本地 VIDEO 筛选可用。

**改进（P2）**
12. 视频时长预取改并发限流。
13. 后端 cloud list 过滤哨兵从 IMAGE 改 ALL，与前端语义对齐。
14. iOS VideoPlayer 对 `NSURL.URLWithString` null 降级；Android VideoPlayer 错误态加 UI 提示。
15. 搜索结果文件名高亮（PRD 遗漏项补齐）。
16. rn-module expect/actual class 加 `-Xexpect-actual-classes`；清理 KLIB 重复库。
17. OpenClaw 入口移出主 TopAppBar，归入设置/二级入口；删 `derivedStateOf` 冗余包裹。
18. `MainActivity.onCreate` 调整 super 调用顺序；`SplashScreen.entered` 死变量删除；`App.kt` ViewModel 绑定生命周期。
19. Android Live Photo 检测改用 MotionPhoto/MediaStore 正规字段，停止编造 video id。

---

## 附：验证可复现命令

```bash
# 后端编译（exit 0，洁净）
cd backend && go build ./... && go vet ./...

# 前端强制重编（裁决 stickyHeader 等疑点的权威证据，三次均 BUILD SUCCESSFUL / error: 行数=0）
cd frontend && bash gradlew :composeApp:compileDebugKotlinAndroid --rerun-tasks --no-build-cache

# 确认 SettingsState 在请求层零引用（P0-1 证据）
grep -rn "SettingsState\|backendUrl" frontend/feature-media/src frontend/feature-common/src  # 应为空

# 确认后端不填 width/height/exif（P1-1 证据）
grep -n "Width:\|Height:\|ExifData" backend/internal/service/media_service.go backend/internal/service/cloud_source.go

# 确认 upload 协议错位（P0-2 证据）：前端发 JSON vs 后端 io.ReadAll 直写盘
sed -n '142,157p' frontend/feature-media/src/commonMain/kotlin/com/wgt/feature/media/MediaService.kt
grep -n "io.ReadAll\|os.WriteFile" backend/internal/gateway/server.go
```
