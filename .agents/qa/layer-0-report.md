# Layer 0 QA 报告

> 审查范围：`git diff 826bd6e..main`（rn-hotupdate + bg-upload 两个 feature merge）
> 审查日期：2026-08-04
> 审查者：reality-checker（默认"需要改进"，要求压倒性证据才允许上线）
> 审查方法：逐文件静态审查 + 符号交叉验证（ensureBundleWithVersion / MediaService.uploadMedia / Sha256Dedup / GalleryFeature / SettingsState.deviceId / RNContainerManager 均已定位并核对签名）

## 总评: CONDITIONAL PASS

两个 feature 的架构设计合理、跨端 expect/actual 声明正确、UI 集成参数完备，**但存在 1 个 P0 阻断性逻辑错误**（重试上限为幻觉式约束，永久失败项会导致无限重试）和 1 个 P1 资源泄漏（observe 协程不终止）。修复 P0 后可上线。

---

## 通过项

1. **RN 热更新回退逻辑正确** — `ensureBundleWithVersion` 返回 null 时，`RnActivityScreen` 正确切到 `RnUpdateState.Fallback`，`RnContainer` 以 `bundleFilePath=null` 加载 assets 内置 bundle。版本比对逻辑（`RnBundleDownloader.kt:83` `localVersion == manifest.version`）正确，SHA256 校验完整。

2. **expect/actual 跨端声明正确** — `commonMain` 的 `PlatformRnView` expect 和 `UploadQueueManager` expect class 声明规范；Android/iOS actual 签名完全匹配（含新增 `bundleFilePath`/`bundleName` 参数）。iOS actual 无默认值符合 Kotlin 规则（expect 有默认值，actual 不允许有）。

3. **iOS 空实现可编译** — `UploadQueueManager.ios.kt` 的 `enqueueUploads` 为 no-op，`uploadState` 恒为 Idle，编译通过且无功能缺失（iOS 端由前台 `uploadSelectedLocalMedia` 兜底）。

4. **`MediaService.uploadMedia` 签名匹配** — UploadWorker 调用的 6 参数版本（fileData/filename/isLivePhoto/sha256/clientId/takenAt）与 `MediaService.kt:628` 的 suspend 函数签名完全对齐，均有默认值兜底。

5. **字节获取路径正确** — `manager.feature.gallery` 经 KSP 生成的 `IManager.feature` 扩展属性 → `IFeatureManager.gallery` 扩展属性 → `GalleryFeature.getMediaData(mediaId): ByteArray?`，调用链完整，与 MediaViewModel 同路径。

6. **去重逻辑正确** — UploadWorker 复用 `Sha256Dedup.shared.contains(hash)` 本端秒传短路 + `markUploaded(hash)` 登记指纹，与 `SyncManager` 共用同一份去重集合，逻辑一致。

7. **WorkManager 约束配置合理** — `NetworkType.CONNECTED` 联网约束、`BackoffPolicy.EXPONENTIAL` 10s 退避、普通 OneTimeWorkRequest（非 expedited）选择正确，符合"前台被杀续传"语义。

8. **空数据/错误状态处理安全** — `enqueueBackgroundUpload()` 对 `selectedMediaIds.isEmpty()` 和 `items.isEmpty()` 双重保护；`UploadWorker.doWork` 对 `parseInput` 空列表直接 `Result.success`；`getMediaData` 返回 null 时计 failed 不中断。

9. **UI 集成参数正确** — `SelectionBottomBar` 新增 `onBackgroundUpload: () -> Unit = {}` 和 `showBackgroundUploadButton: Boolean = false` 均有默认值，插入位置在最后一个无默认值参数（`isDeleting`）之前/有默认值参数之间，不破坏既有调用；调用处（`MediaListScreen.kt:578-584`）正确传递。

10. **依赖配置正确** — `libs.versions.toml` 声明 `androidx-work = "2.9.1"` + `androidx-work-runtime`（work-runtime-ktx），`build.gradle.kts` 在 androidMain dependencies 正确 implementation，版本 2.9+ 提供 `getWorkInfoByIdFlow` API。

11. **分隔符编码安全** — `\u0001`/`\u0002` 控制字符在实际文件名/mediaId 中不会出现，`parseInput` 与 `itemsToWorkData` 对称，格式错误返回空列表不 crash。

12. **`RnUpdateState` sealed interface 设计良好** — Checking/Ready(path,version)/Fallback 三态穷尽匹配，版本号在 TopAppBar 副标题展示（ready 显示"vX（热更新）"，fallback 显示"内置版本"），UX 清晰。

---

## 问题清单（按严重程度）

### P0（阻断性，必须修复）

#### P0-1: `MAX_RETRY_ATTEMPTS` 是幻觉式约束 —— 永久失败项导致无限重试

**文件**：`UploadWorker.kt:117-121` + `UploadWorker.kt:155-156` + `UploadQueueManager.android.kt:26-27`

**问题**：
代码注释反复声称"最多 `MAX_RETRY_ATTEMPTS`（5）次后 WorkManager 转 FAILED 终态"，但这是一个**完全虚假的约束**：

1. `MAX_RETRY_ATTEMPTS = 5` 常量声明后，**在 `doWork()` 中从未被使用**。`runAttemptCount` 仅用于日志输出（第 64、112 行），没有任何 `if (runAttemptCount >= MAX_RETRY_ATTEMPTS)` 判断。
2. WorkManager 的 `Result.retry()` 配合 `BackoffPolicy.EXPONENTIAL` **没有内置的"5 次后自动转 FAILED"机制**。WorkManager 会按指数退避无限重试。
3. 当存在永久失败的项时（如用户删除了照片，`getMediaData` 返回 null，或后端返回 4xx），每次 retry 都会对**整批所有项**重新执行。已成功的项虽能通过 `Sha256Dedup.shared.contains(hash)` 短路跳过，但永久失败的项每次都会失败 → `failed > 0` → `Result.retry()` → 无限循环。
4. `UploadQueueManager.mapWorkInfo` 中的 `WorkInfo.State.FAILED` 分支**永远不会到达**，`BackgroundUploadState.Failed` 状态**永远不会被触发**。UI 永远停留在 `Running` 状态。

**影响**：
- 电量消耗：无限重试持续唤醒设备执行注定失败的上传。
- 网络压力：对后端重复发送注定失败的请求。
- UX 损坏：用户看到"后台上传中"永远不结束，无失败反馈。
- `Sha256Dedup` 短路假设不成立：retry 时 `getMediaData` 对已删除的照片返回 null（不经过 sha256 计算），无法短路。

**修复建议**：
在 `doWork()` 中用 `runAttemptCount` 实际限制重试：
```kotlin
return if (failed == 0) {
    Result.success(progressOfWorkData(completed, total))
} else if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
    // 达上限，停止重试，标记失败终态
    logger.warning(TAG, "doWork: max retries reached, marking FAILED")
    Result.failure(progressOfWorkData(completed, total))
} else {
    Result.retry()
}
```
注意：改用 `Result.failure()`（非 retry）才能让 WorkInfo 进入 `FAILED` 终态，`mapWorkInfo` 才能映射为 `BackgroundUploadState.Failed`。

---

### P1（重要，影响体验）

#### P1-1: observe 协程泄漏 —— `observeScope` 永不 cancel，每次 enqueue 新增永不终止的协程

**文件**：`UploadQueueManager.android.kt:59` + `UploadQueueManager.android.kt:83-88`

**问题**：
1. `observeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` 是应用级作用域，**没有任何 cancel/close 调用**（已全局搜索确认）。
2. 每次 `enqueueUploads` 都 `observeScope.launch { workManager.getWorkInfoByIdFlow(request.id).collect { ... } }`。
3. `getWorkInfoByIdFlow` 返回的 Flow 在 WorkRequest 到达终态（SUCCEEDED/FAILED）后**不会自动 complete** —— 它持续 emit 数据库中持久化的 WorkInfo 状态。因此 collect 协程**永不终止**。
4. 用户多次点"后台上传"→ 多个协程永久挂起 → 协程泄漏。虽每个协程开销小，但长期积累仍有隐患。
5. `MediaViewModel` 无 `onCleared`/dispose 机制（已确认），`backgroundUploadQueue` 是 `by lazy` 单例，生命周期等于 ViewModel，无清理入口。

**影响**：协程缓慢泄漏，多批入队后 `_uploadState` 被多个已完成的旧 WorkRequest 的 observe 协程持续覆盖（虽然终态后 WorkInfo 不变，覆盖值相同，影响可控但逻辑不洁）。

**修复建议**：
在 collect 中检测终态后主动结束协程：
```kotlin
observeScope.launch {
    workManager.getWorkInfoByIdFlow(request.id).collect { info ->
        if (info == null) return@collect
        _uploadState.value = mapWorkInfo(info, items.size)
        // 终态后停止 observe，避免协程泄漏
        if (info.state == WorkInfo.State.SUCCEEDED ||
            info.state == WorkInfo.State.FAILED ||
            info.state == WorkInfo.State.CANCELLED) {
            return@collect  // 或 cancel() 当前协程
        }
    }
}
```

#### P1-2: Fallback 分支重复请求 `ensureBundleWithVersion` —— 已知失败仍再查一次

**文件**：`RnActivityScreen.kt` Fallback 分支 + `RnContainer.android.kt:94-97`

**问题**：
`RnActivityScreen` 的 `LaunchedEffect` 调用 `ensureBundleWithVersion(BUNDLE_NAME_ACTIVITY)` 返回 null（网络失败）→ 切到 `Fallback` → 调用 `RnContainer(bundleName = BUNDLE_NAME_ACTIVITY)`（`bundleFilePath` 默认 null）→ Android `PlatformRnView` 中 `overridePath = bundleFilePath ?: runCatching { ensureBundleWithVersion(queryName)?.path }.getOrNull()` → **再次发起网络请求查询 manifest**。

第一次查询已确认网络不可达/无缓存，第二次查询几乎必然也失败，造成：
- 重复网络请求（manifest 拉取 + 可能的 bundle 下载尝试）
- 额外延迟（用户已等了第一次查询，Fallback 后再等第二次）
- 后端不可达时两次 timeout 叠加

**影响**：网络异常时进入活动中心页面会有双倍加载延迟。

**修复建议**：
Fallback 分支不应传 `bundleName`（设为 null），直接走 assets 加载：
```kotlin
RnUpdateState.Fallback -> {
    RnContainer(
        componentName = "MediaManagerApp",
        bundleAssetName = "index.android.bundle",
        hostId = "activity-center",
        modifier = Modifier.fillMaxSize(),
        bundleFilePath = null,
        bundleName = null  // 不再兜底查询，直接用 assets
    )
}
```
或者在 `PlatformRnView` 中增加标记避免重复查询。`bundleName` 兜底查询逻辑适用于"常驻 Tab 等未预解析场景"，但 `RnActivityScreen` 已预解析过，不应再走兜底。

#### P1-3: `SettingsState.deviceId` 在 WorkManager 后台线程访问 Compose State —— 时序与线程安全隐患

**文件**：`UploadWorker.kt:59` `val clientId = SettingsState.deviceId`

**问题**：
`SettingsState.deviceId` 定义为 `var deviceId by mutableStateOf(loadDeviceId())`（`SettingsScreen.kt:87`），是 Compose `mutableStateOf`。在 WorkManager 后台线程（非主线程）中读取 Compose State 存在：
1. Compose State 非线程安全，跨线程读可能读到不一致值（虽然 `mutableStateOf` 内部有 snapshot 机制，但不保证非 UI 线程读取的安全性）。
2. WorkManager 可能在 App 启动后、UI 未初始化时被调度（进程重建场景），此时 `SettingsState` 可能未经过 `loadDeviceId()` 初始化流程。

**影响**：极端时序下 `clientId` 可能为空字符串或默认值，导致后端幂等键失效。

**修复建议**：
使用非 Compose 的持久化存储直接读取 deviceId（如 `SettingsStorage` SharedPreferences），不经过 `mutableStateOf`：
```kotlin
val clientId = SettingsStorage().getDeviceId()  // 或已有的同步读取方法
```

---

### P2（建议改进）

#### P2-1: `UploadWorker` retry 整批重跑 —— 已成功上传的项依赖 `Sha256Dedup` 短路，存在边界风险

**文件**：`UploadWorker.kt:66-110`

**问题**：
retry 时整批 items 重新执行。已成功上传的项靠 `Sha256Dedup.shared.contains(hash)` 短路跳过。但：
1. 如果 `Sha256Dedup` 的持久化（`SettingsStorage` 逗号分隔串）在 `markUploaded` 后未及时 flush，进程被杀重建后可能丢失已登记指纹 → 已传项被重新上传。
2. `Sha256Dedup.shared` 是 `by lazy` 单例，WorkManager 进程重建后需重新初始化，依赖 `SettingsStorage` 读取持久化数据。若读取失败则去重集合为空。

**建议**：确认 `Sha256Dedup.markUploaded` 的持久化是同步的；或在 retry 时只处理上次失败的项（需在 inputData 中记录失败项索引）。

#### P2-2: `mapWorkInfo` 中 ENQUEUED/BLOCKED 读 `_uploadState.value` 有时序模糊

**文件**：`UploadQueueManager.android.kt:107-109,119-120`

**问题**：
ENQUEUED 和 BLOCKED 状态返回 `_uploadState.value`（当前值），而不是显式构造状态。在多批并发场景下，`_uploadState.value` 可能已被另一批的 observe 协程覆盖，返回的值语义不明确。

**建议**：ENQUEUED 显式返回 `Running(completed = 0, total = fallbackTotal)`（已知入队总数），不依赖可变状态。

#### P2-3: `gradle.properties` 注释代理配置 —— 应已清理

**文件**：`frontend/gradle.properties:18-22`

**问题**：
代理配置从 `systemProp.http.proxyHost=127.0.0.1` 改为 `#systemProp.http.proxyHost=127.0.0.1`（注释掉）。这是开发环境配置，不应进入版本控制。说明 develop 时启用了本地代理（可能是 Charles/mitmproxy 抓包），现在注释掉了。

**建议**：确认此变更是有意为之（提交前已关代理），还是遗留。考虑用 `local.properties`（gitignore）管理代理配置。

#### P2-4: `UploadWorker` 无取消检查 —— `ensureActive`/`isStopped` 未使用

**文件**：`UploadWorker.kt:66-110`

**问题**：
`doWork()` 循环上传每项时未检查 `isStopped`（WorkManager 的取消信号）。如果系统或用户取消 WorkRequest，Worker 仍会跑完整批才返回。

**建议**：在 for 循环中加 `if (isStopped) return Result.failure()` 检查点，支持及时取消。

#### P2-5: 缺少单元测试 / 集成测试

**问题**：
两个 feature 均无测试代码。`UploadWorker.parseInput`/`itemsToWorkData` 的序列化、`mapWorkInfo` 的状态映射、`ensureBundleWithVersion` 的版本比对都是纯函数/可测逻辑，应有测试覆盖。

**建议**：至少覆盖 parseInput/itemsToWorkData 的编解码对称性、mapWorkInfo 各状态映射、空输入边界情况。

---

## 建议修复项

| 优先级 | 问题 | 修复要点 | 工作量 |
|--------|------|----------|--------|
| **P0** | MAX_RETRY_ATTEMPTS 幻觉式约束 | doWork 中加 `runAttemptCount >= MAX_RETRY_ATTEMPTS` → `Result.failure()` | 小 |
| P1 | observe 协程泄漏 | collect 中终态后 `return@collect` | 小 |
| P1 | Fallback 重复查询 | Fallback 分支 `bundleName = null` | 极小 |
| P1 | deviceId 跨线程读取 | 改用 SettingsStorage 直接读 | 小 |
| P2 | retry 整批重跑 | 确认 Sha256Dedup 持久化同步 / 记录失败项索引 | 中 |
| P2 | ENQUEUED 状态读可变值 | 显式返回 `Running(0, fallbackTotal)` | 极小 |
| P2 | 无取消检查 | for 循环加 `isStopped` 检查 | 极小 |
| P2 | 缺测试 | 补 parseInput/mapWorkInfo 单测 | 中 |

---

## 审查覆盖文件清单

| 文件 | 行数变更 | 状态 |
|------|----------|------|
| `build.gradle.kts` | +2 | ✓ 已审 |
| `RnContainer.android.kt` | +30/-7 | ✓ 已审 |
| `UploadQueueManager.android.kt` (new) | +128 | ✓ 已审 |
| `UploadWorker.kt` (new) | +189 | ✓ 已审 |
| `MediaListScreen.kt` | +22/-4 | ✓ 已审 |
| `MediaViewModel.kt` | +48 | ✓ 已审 |
| `RnActivityScreen.kt` | +120/-9 | ✓ 已审 |
| `RnContainer.kt` (commonMain) | +42/-12 | ✓ 已审 |
| `UploadQueueManager.kt` (commonMain, new) | +108 | ✓ 已审 |
| `RnContainer.ios.kt` | +11/-4 | ✓ 已审 |
| `UploadQueueManager.ios.kt` (new) | +32 | ✓ 已审 |
| `gradle.properties` | +4/-4 | ✓ 已审 |
| `libs.versions.toml` | +5 | ✓ 已审 |

**交叉验证的关键符号**：
- `ensureBundleWithVersion` → `RnBundleDownloader.kt:72` ✓ 签名+返回值确认
- `BundleResult(path, version)` → `RnBundleDownloader.kt:114` ✓
- `MediaService.uploadMedia(...)` 6 参数版 → `MediaService.kt:628` ✓ 签名完全匹配
- `Sha256Dedup.shared` → `SyncComponents.kt:30/137` ✓ `contains`/`markUploaded` 确认
- `sha256Hex(ByteArray)` → `Sha256.kt:12` expect ✓
- `SettingsState.deviceId` → `SettingsScreen.kt:87` ✓（但见 P1-3）
- `RNContainerManager.getInstance(app)` → `RNContainerManager.kt:25` ✓
- `manager.feature.gallery` → KSP 生成 `IManager.feature` + `IFeatureManager.gallery` ✓
- `GalleryFeature.getMediaData(mediaId)` → `GalleryFeature.kt:30` ✓
- `ic_cloud_upload` 资源 → 生成资源访问器 + 既有使用 ✓
- `MediaMetadata.filename/is_live_photo/created_at` → `MediaMetadata.kt:52` ✓
