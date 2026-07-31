package com.wgt.media

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wgt.architecture.manager.claim.feature
import com.wgt.architecture.manager.manager
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.MediaSource
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import media.MediaMetadata
import media.MediaType
import com.wgt.feature.gallery.gallery
import com.wgt.feature.gallery.requestMediaDeletion
import com.wgt.base.network.platform
import kotlin.time.Clock

private const val TAG = "MediaViewModel"

/** 一天对应的毫秒数（UTC），用于把 epoch 毫秒折算为"日"边界做分组。 */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 时光相册每个月回忆卡片取前 N 张作为封面预览（PRD-v7 §1.4）。
 *
 * 4 张拼成 2×2 缩略图网格作为卡片封面，既给用户足够的"这个月发生了什么"的视觉印象，
 * 又控制单卡内存（4 个缩略图 ≈ 256KB）与横滚区域整体开销。不足 4 张取实际数量。
 */
private const val MEMORY_COVER_COUNT = 4

/**
 * 自动备份轮询间隔（毫秒）。
 *
 * 自动备份非实时事件驱动，以固定周期扫描图库差集。30s 在"及时性"与"电量/后端压力"间
 * 取平衡：用户拍照后最迟 30s 内进入上传流程；同时避免秒级轮询空耗。轮询体本身在
 * [Dispatchers.Main] 协程内、挂起于网络/IO 调用期间释放主线程，不阻塞 UI。
 */
private const val AUTO_BACKUP_INTERVAL_MS = 30_000L

/**
 * 按日期分组后的媒体集合：[title] 为人类可读的组标题（"今天"/"昨天"/"YYYY年MM月DD日"），
 * [items] 为该日期下的媒体（保持原 [mediaList] 的相对顺序，整体按日期倒序排列，最近的在最前）。
 *
 * 分组与标题生成见 [MediaViewModel.groupMediaByDate]。
 */
data class DateGroup(
    val title: String,
    val items: List<MediaMetadata>
)

/**
 * 时光相册的月份回忆卡片模型（PRD-v7 §1.4）。
 *
 * 由 [MediaViewModel.groupMediaByMonth] 基于云端媒体 [MediaViewModel.cloudMedia] 按
 * `created_at` 的年月聚合而成：
 * - [year]/[month]：月份标识，供详情页过滤同月媒体；
 * - [title]：人类可读标题，如「2026年7月」；
 * - [coverItems]：该月媒体前 4 张作为封面预览（不足 4 张取实际数量）；
 * - [totalCount]：该月媒体总张数，卡片角标展示。
 *
 * 与按日分组的 [DateGroup] 区分：DateGroup 按「天」聚合用于网格标题，
 * MemoryMonth 按「月」聚合用于「回忆」横滚卡片入口。
 */
data class MemoryMonth(
    val year: Int,
    val month: Int,
    val title: String,
    val coverItems: List<MediaMetadata>,
    val totalCount: Int
)

/**
 * 媒体管理视图模型
 */
class MediaViewModel {
    // 状态写回走主线程：launch 块内直接赋值 mediaList / isLoading 等 Compose 状态，
    // 必须在 Dispatchers.Main 上执行，避免从 Default 线程写 snapshot state 造成重组丢失
    // 或跨线程快照竞争。挂起点（MediaService / galleryFeature 等 suspend 调用）会自行
    // 切到 IO/网络线程，挂起期间 Main 释放，不阻塞 UI。
    private val viewModelScope = CoroutineScope(Dispatchers.Main)
    private val galleryFeature: com.wgt.feature.gallery.GalleryFeature by lazy {
        manager.feature.gallery
    }

    // 媒体列表状态
    var mediaList by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    // 当前 mediaList 的来源 —— 决定 UI 用本地相册加载器还是后端 HTTP 加载器解码缩略图/原图。
    // 切换 Tab / 加载完成时更新；默认 LOCAL（与初始 loadMediaFromGallery 一致）。
    var currentSource by mutableStateOf(MediaSource.LOCAL)
        private set

    // —— 搜索 & 筛选 ——
    // 搜索关键词：由搜索栏 debounce 后上抛。本地加速匹配，不额外请求后端；
    // 与 filterType 叠加生效，共同决定 [filteredList]。
    var searchQuery by mutableStateOf("")
        private set

    // 类型过滤维度：ALL=不过滤 / IMAGE=图片(含 Live Photo) / VIDEO=仅视频 / FAVORITE=仅收藏。
    var filterType by mutableStateOf(MediaFilterType.ALL)
        private set

    // —— 收藏 ——
    // 收藏的 mediaId 集合：从 [FavoriteStore] 本地持久化加载，UI 通过此集合判断星标状态。
    // toggleFavorite 时同步更新此集合 + 持久化 + 异步通知后端。
    var favoriteIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 经搜索关键词 + 类型筛选后的媒体列表，供网格直接渲染。
     *
     * 基于 [mediaList] 实时派生：任一输入（[searchQuery] / [filterType] / [mediaList] / [favoriteIds]）
     * 变化即重算。用 [derivedStateOf] 缓存，仅当结果列表实例变化时才触发 UI 重组，避免无谓重算。
     *
     * - 关键词匹配 [MediaMetadata.filename]（大小写不敏感、去首尾空格），命中子串即保留。
     * - 类型过滤遵循 [MediaFilterType] 注释：IMAGE 含 IMAGE 与 LIVE_PHOTO，VIDEO 仅 VIDEO，
     *   FAVORITE 只保留 id 在 [favoriteIds] 中的项。
     */
    val filteredList: List<MediaMetadata> by derivedStateOf {
        val q = searchQuery.trim()
        mediaList
            .asSequence()
            .filter { matchesFilter(it) }
            .filter { q.isEmpty() || it.filename.contains(q, ignoreCase = true) }
            .toList()
    }

    /**
     * 综合 [filterType] 判断该媒体是否应被保留。
     *
     * - ALL/IMAGE/VIDEO：按 [matchesTypeFilter] 做类型过滤。
     * - FAVORITE：不看类型，仅保留 id 在 [favoriteIds] 中的项。
     */
    private fun matchesFilter(media: MediaMetadata): Boolean {
        if (filterType == MediaFilterType.FAVORITE) {
            return favoriteIds.contains(media.id)
        }
        return matchesTypeFilter(media.type)
    }

    /**
     * 当前 [filterType] 是否接纳该媒体类型（不含 FAVORITE 维度，FAVORITE 由 [matchesFilter] 处理）。
     */
    private fun matchesTypeFilter(type: MediaType): Boolean = when (filterType) {
        MediaFilterType.ALL -> true
        // 图片维度：Live Photo 本质是带视频的图片，归图片浏览。
        MediaFilterType.IMAGE -> type == MediaType.IMAGE || type == MediaType.LIVE_PHOTO
        MediaFilterType.VIDEO -> type == MediaType.VIDEO
        MediaFilterType.FAVORITE -> true // 不在此层过滤，由 matchesFilter 按 favoriteIds 过滤
    }

    /**
     * 设置搜索关键词（由搜索栏 debounce 后调用）。
     *
     * 命名为 `applySearchQuery` 而非 `setSearchQuery`：后者会与 `searchQuery` 委托属性
     * 在 JVM 层合成的同名 setter 签名冲突（KMP commonMain 同名 var+setter 的已知 clash），
     * 故直接避开同名。`@JvmName` 为 JVM 注解，commonMain 不可用，故以改名解决。
     */
    fun applySearchQuery(query: String) {
        searchQuery = query
    }

    /**
     * 设置类型过滤维度（由筛选条调用）。同样以 `applyFilterType` 避名，规避与
     * `filterType` 委托属性合成 `setFilterType` 的 JVM 签名冲突。
     */
    fun applyFilterType(type: MediaFilterType) {
        filterType = type
    }

    /**
     * 清空搜索关键词与类型筛选，恢复无过滤态（切换 Tab / 收起搜索时调用）。
     */
    fun clearSearchAndFilter() {
        searchQuery = ""
        filterType = MediaFilterType.ALL
    }

    // 选中的媒体ID列表
    val selectedMediaIds = mutableStateListOf<String>()

    // 加载状态
    var isLoading by mutableStateOf(false)
        private set

    // 错误状态：一次性 Snackbar 消息（用于上传/删除等操作反馈，显示后即清）
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** V7：供 UI 层设置操作反馈消息 */
    fun showErrorMessage(msg: String) { errorMessage = msg }

    // 列表加载失败占位文案（持续性）。
    // 与 [errorMessage] 区分：errorMessage 是一次性 Snackbar，转瞬即逝；
    // listLoadError 在列表为空且加载失败时驱动网格区显示“加载失败 + 重试”占位，
    // 避免后端未启动时落入“暂无网盘图片”分支造成白屏/误导。
    // 重试成功或命中缓存（说明曾成功过）后清空。
    var listLoadError by mutableStateOf<String?>(null)
        private set

    // 上传状态
    var isUploading by mutableStateOf(false)
        private set

    // 上传进度：已上传数 / 总数。上传期间非 null，结束时复位为 null。
    // UI 据此显示进度对话框 "上传中 2/5..."。
    var uploadProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    // 删除状态
    var isDeleting by mutableStateOf(false)
        private set

    // 照片图库权限状态
    var hasGalleryPermission by mutableStateOf(false)
        private set

    // 照片图库加载状态
    var isGalleryLoading by mutableStateOf(false)
        private set

    // 网盘图片加载状态（第三个 Tab）
    var isCloudLoading by mutableStateOf(false)
        private set

    // 缓存管理
    private var cachedLocalMedia: List<MediaMetadata>? = null
    private var cachedUploadedMedia: List<MediaMetadata>? = null
    private var cachedCloudMedia: List<MediaMetadata>? = null

    // ---- 增量同步 / 自动备份 ----

    /**
     * 增量同步复合游标（"ms|id" 字符串，V6 §2.7）。初值取自 [SettingsState.syncCursor]
     * （持久化），随 [loadCloudChanges] 每页成功推进并回写。空串表示从未同步过
     * （下次全量拉取）。由 App 启动与进入"已上传"Tab 的 [loadCloudChanges] 消费。
     */
    var syncCursor: String = SettingsState.syncCursor
        private set

    /**
     * 云端媒体列表 —— [loadCloudChanges] 合并 sync/changes 增量后的产物，直接供"已上传" Tab 渲染。
     * 与 [mediaList] 区分：mediaList 是当前 Tab 的渲染源（可能指向本地图库/网盘/云端），
     * [cloudMedia] 始终是云端增量同步累积视图，切换到"已上传"Tab 时拷贝给 mediaList。
     */
    var cloudMedia by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    /** 云端增量同步进行中（驱动"已上传"Tab 的 loading 态）。 */
    var isSyncing by mutableStateOf(false)
        private set

    /**
     * 云端是否还有更多增量页未拉取（V6 §2.3 分页）。
     *
     * 后端 getSyncChanges 返回 hasMore=true 时表示后续还有变更页。云端 Tab 滚动到底时
     * 据此判断是否触发 [loadMoreCloudChanges] 续拉下一页；false 时不触发，避免无效请求。
     */
    var hasMoreCloudChanges by mutableStateOf(false)
        private set

    /** 云端用量（[loadSyncUsage] 成功后填充，设置页/已上传页可展示）。 */
    var cloudUsage by mutableStateOf<MediaService.SyncUsage?>(null)
        private set

    /**
     * 上传离线队列（弱网失败入队，恢复后重放）。UI 可观察 [UploadQueue.items] 展示待传数。
     */
    val uploadQueue = UploadQueue()

    /**
     * SHA-256 去重集合：增量同步返回的指纹 + 本设备已传指纹，自动备份时据此跳过已传图。
     *
     * V6 去重合一：使用 [Sha256Dedup.shared] 全局单例，与 [SyncManager] 共用同一份集合，
     * 不再维护独立实例。旧 [DedupStore] 已废弃删除。
     */
    private val dedup = Sha256Dedup.shared

    /**
     * 自动备份已知本地 id 快照。开启自动备份后首次建立；之后 [checkAndBackupNewLocalMedia]
     * 比对图库 id 与该快照，新出现的 id 视为本会话新增图，进入上传/离线队列。
     * 纯内存——进程重启后重新建立快照（避免重启即重传历史图）。
     */
    private val knownLocalIds: MutableSet<String> = HashSet()

    /** 自动备份轮询协程句柄，[startAutoBackup]/[stopAutoBackup] 管理其生命周期。 */
    private var autoBackupJob: Job? = null

    /** 设备注册进行中（去重并发注册）。 */
    private var isRegisteringDevice = false

    /**
     * 本地相册分页加载每页大小上限。
     *
     * MIUI 等系统在内存紧张时会杀进程，一次全量加载数千张照片的元数据
     * 加上后续缩略图解码会导致内存峰值过高。分页加载把每次元数据
     * 查询的结果限制在 [GALLERY_PAGE_SIZE] 内，显著降低首屏内存占用。
     */    private val galleryPageSize = 50

    /** 本地相册当前已加载的页数（0-based），用于 [loadMoreGallery] 增量加载。 */
    private var galleryPage = 0

    /** 本地相册是否还有更多数据可加载（万物尽之时置 false）。 */
    var hasMoreGallery by mutableStateOf(true)
        private set

    /**
     * 视频时长缓存：mediaId → 秒。`loadCloudMediaList` 加载视频列表后，后台逐个调用
     * 后端 `/api/media/video-info/{id}` 预取时长，供网格时长标签与 [VideoPlayer] 初始
     * 总时长复用，避免每次进入播放器都要等加载。失败项不入表，播放器再按实际播放获取。
     *
     * 用 mutableStateOf 包 Map 仅供 UI 观察刷新；内部换新实例触发重组（Kotlin Map 不可变，
     * 每次产出新 Map）。
     */
    var videoDurations by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    // ---- 相册状态 ----

    /** 相册列表（列表页渲染数据源）。 */
    var albumList by mutableStateOf<List<MediaService.Album>>(emptyList())
        private set

    /** V7 §2.3：被共享给当前用户的相册列表。 */
    var sharedAlbumList by mutableStateOf<List<MediaService.Album>>(emptyList())
        private set

    /** 相册列表加载中。 */
    var isAlbumLoading by mutableStateOf(false)
        private set

    /** 相册详情内的媒体列表（详情页渲染数据源）。 */
    var albumDetailMedia by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    /** 相册详情加载中。 */
    var isAlbumDetailLoading by mutableStateOf(false)
        private set

    /** "加入相册"选择对话框：非空时弹出，值为待加入的 mediaId。 */
    var pendingAddToAlbumMediaId by mutableStateOf<String?>(null)
        private set


    /** 内存警告回调句柄，ViewModel 销毁时需释放。 */
    private var memoryWarningHandle: MemoryWarningHandle? = null

    init {
        // 从本地持久化加载收藏 id 集合，让星标状态在重启后保持。
        favoriteIds = FavoriteStore.loadFavoriteIds()
        logger.info(TAG, "init favoriteIds=${favoriteIds.size}")
//        loadUploadedMediaList()
        loadMediaFromGallery()

        // 注册内存警告回调：系统内存压力时清空缓存，避免 OOM crash。
        // commonMain 无法直接使用 android/java API，通过 expect/actual 桥接。
        memoryWarningHandle = registerMemoryWarningCallback {
            clearCachesOnMemoryWarning()
        }

        // 冷启动恢复登录态时发起首次增量同步 + 启动自动备份（token 由 App 注入，
        // 已就绪即可发请求）。App 层在 token 注入/登录后会再调 [onSessionReady] 兜底。
        if (AuthState.isLoggedIn) {
            onSessionReady()
        }
    }

    /**
     * 登录态就绪后触发：App 启动 token 注入后、登录成功后、401 清会话后重新登录后调用。
     *
     * 触发两件事（幂等，重复调用无副作用）：
     * 1. [loadCloudChanges] —— App 启动增量拉取，用本地 [syncCursor] 续拉云端变更。
     * 2. 若 [SettingsState.autoBackupEnabled] —— 注册设备（按需）并启动自动备份。
     *
     * 不在 [init] 直接发请求：token 由 App 的 LaunchedEffect 异步注入 MediaService，
     * 启动顺序上无法保证 init 时 token 已就位。
     */
    fun onSessionReady() {
        // 后台拉一次增量（不阻塞 UI；失败静默，游标不前进，下次重试）。
        loadCloudChanges()
        // 同步用量供设置页/已上传页展示，失败静默。
        loadSyncUsage()
        if (SettingsState.autoBackupEnabled) {
            startAutoBackup()
        }
    }

    /**
     * 增量拉取云端变更并累积到 [cloudMedia]。
     *
     * 以持久化游标 [syncCursor] 为 since 起点调 [MediaService.getSyncChanges] 拉取**一页**
     * 增量变更，[mergeCloudChanges] 合并到 [cloudMedia]，推进游标并落盘。
     *
     * V6 §2.3 云端分页：不再 while 循环一次性拉完全部增量页——改为每次只拉一页（pageSize=100），
     * 拉完即停，[hasMoreCloudChanges] 记录后端是否还有更多。云端 Tab 滚动到底时经
     * [loadMoreCloudChanges] 触发再拉下一页，实现无限滚动分页体验。首次进入 Tab 或
     * App 启动时仍调本方法拉首页（秒开），后续页由用户滚动驱动。
     *
     * 失败页提前返回且不推进游标（下次重试从原位置续拉，避免丢增量）。指纹同步灌入
     * [dedup]（含墓碑剔除），使自动备份能跳过他设备已传图。
     *
     * 由 App 启动（[onSessionReady]）与进入已上传 Tab 时调用。重入安全：
     * [isSyncing] 置位期间直接返回，避免 Tab 来回切换并发起多次同步。网络/未登录失败静默。
     */
    fun loadCloudChanges() {
        // 非登录态不发请求（sync 端点需鉴权）。
        if (!AuthState.isLoggedIn) return
        if (isSyncing) return
        isSyncing = true
        viewModelScope.launch {
            try {
                val page = MediaService.getSyncChanges(since = syncCursor, pageSize = 100)
                    ?: return@launch // 网络/HTTP 错误：不推进游标，下次重试
                // 指纹灌入去重集合（含墓碑剔除），覆盖他设备已传内容。
                dedup.loadFromSync(page.changes)
                // 合并到云端累积视图（upsert + 软删移除）。
                cloudMedia = mergeCloudChanges(cloudMedia, page.changes)
                // 组装复合游标 "ms|id" 并推进落盘（V6 §2.7）。
                // nextCursor 为毫秒（Long），nextCursorId 为末条 id（String）。
                // 二者组装为 "ms|id" 传给后端，使下次走 (updated_at, id) 复合严格大于。
                if (page.nextCursor > 0L) {
                    val newCursor = if (page.nextCursorId.isNotEmpty()) {
                        "${page.nextCursor}|${page.nextCursorId}"
                    } else {
                        page.nextCursor.toString()
                    }
                    SettingsState.saveSyncCursor(newCursor)
                    syncCursor = newCursor
                }
                hasMoreCloudChanges = page.hasMore
                logger.info(TAG, "loadCloudChanges page done, cloud=${cloudMedia.size} cursor=$syncCursor hasMore=$hasMoreCloudChanges")
            } catch (e: Exception) {
                // 非预期异常（如合并逻辑）：记录，游标不变，下次续拉。
                logger.error(TAG, "loadCloudChanges failed: ${e::class.simpleName} ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    /**
     * 把一页变更合并进现有云端列表。
     *
     * - 删除项（[deleted]=true）：按 id 从列表移除；
     * - 非 deletion：按 id upsert（存在则替换为新版元数据，否则追加）。
     * 用 LinkedHashMap 保序：保留既有云端顺序，删除命中项，新增附加到末尾，已存在项就位更新。
     * 不依赖 java.util.LinkedHashMap 特殊 API，纯 Kotlin MutableMap 即可。
     */
    private fun mergeCloudChanges(
        existing: List<MediaMetadata>,
        changes: List<MediaService.SyncChange>
    ): List<MediaMetadata> {
        if (changes.isEmpty()) return existing
        // 以 id 为键的有序表：先装入既有项（保序），再按变更 upsert/删除。
        val byId = LinkedHashMap<String, MediaMetadata>()
        for (m in existing) byId[m.id] = m
        // 删除集：一次遍历变更收集被删 id，避免逐条改 map 引入中间态。
        val deletedIds = HashSet<String>()
        for (c in changes) {
            if (c.deleted) deletedIds.add(c.id) else byId[c.id] = c.toMediaMetadata()
        }
        if (deletedIds.isNotEmpty()) {
            val it = byId.entries.iterator()
            while (it.hasNext()) if (it.next().key in deletedIds) it.remove()
        }
        return byId.values.toList()
    }

    /**
     * 拉取云端用量并更新 [cloudUsage]（供设置页/已上传页展示)。未登录/失败静默，保留旧值。
     */
    fun loadSyncUsage() {
        if (!AuthState.isLoggedIn) return
        viewModelScope.launch {
            val usage = MediaService.getSyncUsage()
            if (usage != null) cloudUsage = usage
        }
    }

    /**
     * 进入/刷新"已上传"Tab 的云端视图入口。
     *
     * 把 [cloudMedia]（增量同步累积视图）立刻拷给 [mediaList] 供网格即时渲染，同时后台
     * 发起 [loadCloudChanges] 续拉最新增量——已有视图先上屏，同步完成后 [cloudMedia] 更新
     * 再二次刷新 mediaList，使"秒开 + 增量刷新"二者兼得。配合保留旧 [loadUploadedMediaList]
     * 作为不具备同步时的回退路径（当前不再被 Tab1 直接调用，留作全量列表兜底）。
     *
     * [forceRefresh] 透传给 [loadCloudChanges]——虽游标机制本身只拉增量，force 时附加
     * 用量刷新并加载，更彻底；不强制重置游标。
     */
    fun loadCloudViewForTab(forceRefresh: Boolean = false) {
        currentSource = MediaSource.BACKEND
        listLoadError = null
        // 先用已有云端累积视图上屏（秒开），空也不阻塞。
        mediaList = cloudMedia
        loadCloudChanges()
        if (forceRefresh) loadSyncUsage()
    }

    /**
     * 云端 Tab 滚动到底时触发续拉下一页增量（V6 §2.3 分页）。
     *
     * 仅当 [hasMoreCloudChanges] 为 true 且未在同步中时才触发，避免无效请求与重入。
     * 内部直接调 [loadCloudChanges]（它会从当前 [syncCursor] 续拉下一页）。
     */
    fun loadMoreCloudChanges() {
        if (!hasMoreCloudChanges || isSyncing) return
        loadCloudChanges()
    }

    /**
     * 开启自动备份：按需注册设备 + 启动后台轮询。
     *
     * 轮询协程周期性 [checkAndBackupNewLocalMedia]：比对图库 id 快照检测本会话新增图，
     * sha256 去重后经 [SyncManager.uploadLocal] 上传（带 sha256/client_id/taken_at 全量字段，
     * 使秒传/幂等/时序生效），失败由 [SyncManager] 入持久化离线队列 [OfflineQueueStore]。
     * 轮询前先 [replayOfflineUploads] 重放历史失败项（进程重启后也续传）。
     * 幂等：重复调用不重复起协程（[autoBackupJob] 非空即返回）。
     */
    fun startAutoBackup() {
        if (!AuthState.isLoggedIn) return
        if (autoBackupJob?.isActive == true) return
        // 首次建立已知本地 id 快照（避免把已存在的历史图全判为"新增"瞬时上传）。
        if (knownLocalIds.isEmpty()) {
            viewModelScope.launch { seedKnownLocalIdsSnapshot() }
        }
        registerDeviceIfNeeded()
        autoBackupJob = viewModelScope.launch {
            logger.info(TAG, "auto backup poll started")
            // 进程重启后首重放：把上一次弱网积压的持久化队列清一遍。
            replayOfflineUploads()
            while (isActive) {
                replayOfflineUploads()
                checkAndBackupNewLocalMedia()
                // 固定轮询间隔：频繁轮询耗电、过稀落后；30s 在两者间取平衡。
                delay(AUTO_BACKUP_INTERVAL_MS)
            }
        }
    }

    /** 停止自动备份轮询。取消协程并清空待传队列（切账号/关开关时调用）。 */
    fun stopAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = null
        uploadQueue.clear()
        // PRD-v7 §1.5：停止备份时取消进度通知，避免残留"备份中"通知。
        cancelBackupNotification()
        logger.info(TAG, "auto backup stopped")
    }

    /**
     * 按需注册当前设备（首次开启自动备份或 deviceId 为空时）。
     *
     * 用 [platform] 作为平台标记（"iOS"/"Android"），设备名取用户名+平台。成功后落地
     * 到 [SettingsState.deviceId]，避免每次启动重复注册。[isRegisteringDevice] 防并发。
     */
    private fun registerDeviceIfNeeded() {
        if (SettingsState.deviceId.isNotEmpty()) return
        if (isRegisteringDevice) return
        if (!AuthState.isLoggedIn) return
        isRegisteringDevice = true
        viewModelScope.launch {
            try {
                val id = MediaService.registerDevice(
                    deviceName = "${AuthState.currentUsername}-${platform()}",
                    platform = platform()
                )
                if (!id.isNullOrEmpty()) {
                    SettingsState.saveDeviceId(id)
                    logger.info(TAG, "device registered: $id")
                }
            } catch (e: Exception) {
                logger.error(TAG, "registerDevice failed: ${e.message}")
            } finally {
                isRegisteringDevice = false
            }
        }
    }

    /**
     * 建立已知本地 id 快照：把当前图库全部 id 灌入 [knownLocalIds]。
     *
     * 只在自动备份首次开启时调用一次。此后 [checkAndBackupNewLocalMedia] 以此为基线，
     * 新出现（不在此快照内）的 id 视为本会话新增图。进程重启后重新建立——
     * 避免重启即把全部历史图判为新增而瞬时重传。
     */
    private suspend fun seedKnownLocalIdsSnapshot() {
        if (!galleryFeature.hasPermission()) return
        try {
            val all = galleryFeature.getMediaFromGallery()
            knownLocalIds.addAll(all.map { it.id })
            logger.info(TAG, "known local snapshot size=${knownLocalIds.size}")
        } catch (e: Exception) {
            logger.error(TAG, "seed known local ids failed: ${e.message}")
        }
    }

    /**
     * 检测本地图库新增图片并增量上传（自动备份核心）。
     *
     * 比对当前图库全量 id 与 [knownLocalIds]：差集即新增图（含快照建立后用户拍的/导入的）。
     * 逐项取字节 → 算 sha256 → 经 [SyncManager.uploadLocal] 上传（**唯一上传通路**，带
     * sha256/client_id/taken_at 全量字段，使 (user_id,sha256) 秒传、client_id 幂等、
     * taken_at 时序生效）。uploadLocal 内部做本端去重（[Sha256Dedup]）与后端权威秒传，
     * 成功登记指纹，失败入持久化离线队列 [OfflineQueueStore]。
     *
     * 这里不再直接调 [MediaService.uploadMedia]（3 参版会丢全量字段），也不再用内存
     * [UploadQueue] 入队（杀进程即丢）。新增 id 无论上传成败都加入快照，避免下轮重复处理。
     *
     * 仅处理图片与 Live Photo（视频体积大、备份策略另议）；纯视频跳过。
     *
     * PRD-v7 §1.5 备份进度通知：开始/每张/暂停/完成各阶段经 [BackupStatusNotifier]
     * 顶层函数更新通知（Android 端 NotificationManager 进度条，iOS 空实现）。
     * 完成后调 [SettingsState.saveLastBackupTime] 落盘"上次备份时间"供设置页展示。
     */
    private suspend fun checkAndBackupNewLocalMedia() {
        if (!galleryFeature.hasPermission()) return
        // V6 §2.1：网络电量策略前置检查。仅 WiFi / 仅充电开关未满足时跳过本轮备份，
        // 不清空待备份项——下一轮轮询时条件满足即续传。
        if (!shouldBackupByPolicy()) {
            logger.info(TAG, "backup skipped by policy (wifiOnly=${SettingsState.backupWifiOnly} chargingOnly=${SettingsState.backupChargingOnly})")
            // PRD-v7 §1.5：策略不满足时通知"备份已暂停"，指明原因（非WiFi/非充电）。
            notifyBackupPaused(policyPauseReason())
            return
        }
        val current: List<MediaMetadata>
        try {
            current = galleryFeature.getMediaFromGallery()
        } catch (e: Exception) {
            logger.error(TAG, "backup scan failed: ${e.message}")
            return
        }
        val newOnes = current.filter { it.id !in knownLocalIds }
        if (newOnes.isEmpty()) return
        val total = newOnes.size
        // PRD-v7 §1.5：开始备份前发布初始进度通知（0/total）。
        notifyBackupProgress(0, total)
        var completed = 0
        for (m in newOnes) {
            // 无论后续上传成败，先记入快照，避免下轮重复处理同一项。
            knownLocalIds.add(m.id)
            // V6 §2.1：视频纳入自动备份（解除旧的 continue 跳过）。
            // 后端已流式落盘支持大文件（v5-perf），视频走同一 uploadLocal 通路，
            // 经 sha256 秒传去重，重复视频不重复落盘。
            try {
                val bytes = galleryFeature.getMediaData(m.id) ?: run {
                    // 取字节失败：finally 统一推进进度，此处仅 continue 跳过上传。
                    continue
                }
                val hash = computeSha256(bytes)
                // 本端去重命中（本设备或他设备已传过）：登记并跳过，连 uploadLocal 往返都省。
                // uploadLocal 内部也会再判一次 Sha256Dedup.shared，这里提前短路纯为省一次函数调用，
                // 两者读同一持久化集合，结果一致。
                if (dedup.contains(hash)) {
                    dedup.markUploaded(hash)
                    // 跳过上传：finally 统一推进进度。
                    continue
                }
                // 经 SyncManager 上传：带 sha256/client_id/taken_at，失败由其入 OfflineQueueStore。
                // client_id 用设备注册 id（幂等键）；taken_at 用图库拍摄时间（created_at，
                // Android=DATE_ADDED*1000），无可靠 EXIF DateTimeOriginal 解析时以此兜底，
                // 由后端在 taken_at<=0 时用上传时间。
                val ok = SyncManager.uploadLocal(
                    mediaId = m.id,
                    filename = m.filename,
                    data = bytes,
                    isLivePhoto = m.is_live_photo,
                    clientId = SettingsState.deviceId,
                    takenAt = m.created_at,
                    precomputedSha = hash
                )
                if (ok) {
                    // 登记指纹供下轮本端秒传（uploadLocal 内部也已登记 Sha256Dedup.shared，
                    // 此处同步本 ViewModel 的 dedup 视图，保持 loadCloudChanges 灌入与本地上传
                    // 两路指纹视图一致）。
                    dedup.markUploaded(hash)
                    // 上传成功后触发一次增量同步，让云端视图即时纳入新图（轻量：仅拉增量）。
                    loadCloudChanges()
                }
                // 失败：SyncManager.uploadLocal 已入 OfflineQueueStore，下一轮 replayOfflineUploads 重放。
            } catch (e: Exception) {
                logger.error(TAG, "backup upload failed id=${m.id}: ${e.message}")
            } finally {
                // PRD-v7 §1.5：每处理完一项（上传成功/去重跳过/失败/取字节失败）推进进度通知。
                // 放 finally 确保所有分支只推进一次，与 continue 配合不重复计数。
                completed++
                notifyBackupProgress(completed, total)
            }
        }
        // PRD-v7 §1.5：全部完成 → "备份完成"通知（内部 1s 后自动取消）+ 落盘上次备份时间。
        notifyBackupComplete()
        SettingsState.saveLastBackupTime(Clock.System.now().toEpochMilliseconds())
    }

    /**
     * 生成备份暂停原因文案（PRD-v7 §1.5）。优先 WiFi（更常见的策略项）。
     * 仅在 [shouldBackupByPolicy] 返回 false 时调用。
     */
    private fun policyPauseReason(): String = when {
        SettingsState.backupWifiOnly && !isOnWifi() -> "非WiFi"
        SettingsState.backupChargingOnly && !isCharging() -> "非充电"
        else -> "条件不满足"
    }

    /**
     * 重放持久化离线上传队列。
     *
     * 转发到 [SyncManager.replayOfflineQueue]：逐项从 [OfflineQueueStore] 取快照 → 经
     * galleryFeature 重读图库字节 → 走 [SyncManager.uploadLocal]（带原 sha256/client_id/taken_at）
     * 。成功撤离、源已删撤离、失败保留待下轮。本方法不另维护内存队列——以 [OfflineQueueStore]
     * 为唯一待办表，进程重启不丢。
     *
     * 由自动备份轮询每轮开头（[startAutoBackup]）及进程重启后首次调用。字节获取由本
     * ViewModel 提供（它持有 galleryFeature）。
     */
    private suspend fun replayOfflineUploads() {
        if (OfflineQueueStore.size() == 0) return
        SyncManager.replayOfflineQueue { mediaId -> galleryFeature.getMediaData(mediaId) }
        // 重放后若队列变空则无需进一步动作；若仍有项则下轮再试。
    }


    /**
     * 内存警告时清空图片缓存，释放内存。
     *
     * 清空 [BackendImageLoader] 的缩略图 LRU 缓存与原图缓存，
     * 避免系统内存压力下 OOM crash。清空后网格滚动 / 预览回滑会重新加载，
     * 以短暂的加载闪动换取内存安全。
     */
    private fun clearCachesOnMemoryWarning() {
        logger.info(TAG, "Memory warning: clearing image caches")
        BackendImageLoader.clearCaches()
    }

    /**
     * 切换媒体收藏状态。
     *
     * 1. 立即更新 [favoriteIds] 与本地持久化（UI 即时响应，不等网络）；
     * 2. 异步调用后端 POST /api/media/favorite 同步服务端状态（失败静默，本地状态仍生效）；
     * 3. 通过 [snackbarMessage] 发送收藏切换提示（"已收藏"/"已取消收藏"）。
     *
     * @param mediaId 目标媒体 ID
     */
    fun toggleFavorite(mediaId: String) {
        val newFav = !favoriteIds.contains(mediaId)
        favoriteIds = if (newFav) {
            favoriteIds + mediaId
        } else {
            favoriteIds - mediaId
        }
        // 持久化到本地存储
        FavoriteStore.saveFavoriteIds(favoriteIds)
        // Snackbar 反馈
        errorMessage = if (newFav) "已收藏" else "已取消收藏"
        // 异步同步后端
        viewModelScope.launch {
            try {
                MediaService.toggleFavorite(mediaId, newFav)
            } catch (e: Exception) {
                logger.error(TAG, "toggleFavorite backend sync failed: ${e.message}")
            }
        }
    }

    /**
     * 判断指定媒体是否已收藏。
     */
    fun isFavorite(mediaId: String): Boolean = favoriteIds.contains(mediaId)

    /**
     * 从网络加载媒体列表
     */
    fun loadUploadedMediaList(forceRefresh: Boolean = false) {
        // 如果有缓存且不需要强制刷新，直接使用缓存
        if (!forceRefresh && cachedUploadedMedia != null) {
            mediaList = cachedUploadedMedia!!
            currentSource = MediaSource.BACKEND
            listLoadError = null
            return
        }

        if (isLoading) return

        isLoading = true
        errorMessage = null
        listLoadError = null
        currentSource = MediaSource.BACKEND

        viewModelScope.launch {
            try {
                val uploadedMedia = MediaService.getMediaList()
                cachedUploadedMedia = uploadedMedia
                mediaList = uploadedMedia
            } catch (e: Exception) {
                mediaList = cachedUploadedMedia ?: emptyList()
                listLoadError = e.message ?: "加载媒体列表失败"
                errorMessage = "加载媒体列表失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 从后端加载网盘图片列表（第三个 Tab "网盘图片"）
     *
     * 与 [loadUploadedMediaList] 同样走 [MediaService.getMediaList]，但维护独立的
     * 缓存与加载状态（[cachedCloudMedia] / [isCloudLoading]），避免与"已上传"页
     * 互相串扰 mediaList 显示。
     */
    fun loadCloudMediaList(forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedCloudMedia != null) {
            // 命中缓存：曾经成功加载过，无需提示错误。
            mediaList = cachedCloudMedia!!
            currentSource = MediaSource.BACKEND
            listLoadError = null
            return
        }

        if (isCloudLoading) return

        isCloudLoading = true
        errorMessage = null
        listLoadError = null
        currentSource = MediaSource.BACKEND

        viewModelScope.launch {
            try {
                // cloud=true → 后端附加 q=source=cloud，命中 LocalCloudSource（data/cloud-images）。
                // 该源现同时收录图片与视频扩展名，故此 Tab 已天然包含 VIDEO 条目，无需额外请求。
                // 出错时 MediaService 不再回退 mock，直接抛出，这里捕获后置 listLoadError。
                val cloudMedia = MediaService.getMediaList(pageSize = 50, cloud = true)
                cachedCloudMedia = cloudMedia
                mediaList = cloudMedia
                // 列表就绪后后台预取视频时长（仅 VIDEO 项），供网格时长标签与播放器复用。
                prefetchVideoDurations(cloudMedia)
            } catch (e: Exception) {
                // 列表加载失败：保留 listLoadError 持续占位，而非落入“暂无网盘图片”误导。
                // mediaList 保持既有值（首次失败时为空），UI 由 listLoadError 驱动重试占位。
                mediaList = cachedCloudMedia ?: emptyList()
                listLoadError = e.message ?: "无法连接后端"
                errorMessage = "加载网盘图片失败: ${listLoadError}"
            } finally {
                isCloudLoading = false
            }
        }
    }

    /**
     * 从本地照片图库加载媒体（分页加载，每次最多 [galleryPageSize] 项）。
     *
     * 首次调用加载第一页；后续调用若已命中缓存则直接使用缓存。
     * 增量加载更多请调 [loadMoreGallery]。
     */
    fun loadMediaFromGallery(forceRefresh: Boolean = false) {

        // 如果有缓存且不需要强制刷新，直接使用缓存
        if (!forceRefresh && cachedLocalMedia != null) {
            mediaList = cachedLocalMedia!!
            currentSource = MediaSource.LOCAL
            listLoadError = null
            return
        }

        if (isGalleryLoading) return

        // 强制刷新时重置分页状态
        if (forceRefresh) {
            galleryPage = 0
            hasMoreGallery = true
        }

        isGalleryLoading = true
        errorMessage = null
        listLoadError = null
        currentSource = MediaSource.LOCAL

        viewModelScope.launch {
            try {
                // 检查权限
                if (!galleryFeature.hasPermission()) {
                    val granted = galleryFeature.requestPermission()
                    if (!granted) {
                        errorMessage = "需要照片图库访问权限"
                        return@launch
                    }
                }

                hasGalleryPermission = true
                // 分页加载：从第一页开始，最多 galleryPageSize 项。
                val galleryMedia = galleryFeature.getMediaFromGallery()
                val pagedMedia = galleryMedia.take(galleryPageSize)
                cachedLocalMedia = pagedMedia
                mediaList = pagedMedia
                galleryPage = 0
                hasMoreGallery = galleryMedia.size > galleryPageSize
                // 保留全量列表供 loadMoreGallery 增量切片
                allGalleryMedia = galleryMedia

            } catch (e: Exception) {
                mediaList = cachedLocalMedia ?: emptyList()
                listLoadError = e.message ?: "加载照片图库失败"
                errorMessage = "加载照片图库失败: ${e.message}"
            } finally {
                isGalleryLoading = false
            }
        }
    }

    /**
     * 全量相册数据（仅首次加载时持有，分页加载增量切片用）。
     * 放在 ViewModel 内存中但不直接暴露给 UI，避免一次性占用过高内存——
     * 实际渲染只取 [mediaList]（当前页 + 已加载页的合并）。
     */
    private var allGalleryMedia: List<MediaMetadata>? = null

    /**
     * 增量加载下一页本地相册媒体（每次最多 [galleryPageSize] 项）。
     *
     * 在 [loadMediaFromGallery] 首次加载后，UI 滚动到底部时调用此方法
     * 追加下一页。合并已加载部分与新页，保持 [mediaList] 增长。
     * [hasMoreGallery] 为 false 时不再加载。
     */
    fun loadMoreGallery() {
        if (isGalleryLoading || !hasMoreGallery) return
        val all = allGalleryMedia ?: return

        isGalleryLoading = true
        viewModelScope.launch {
            try {
                galleryPage++
                val fromIndex = galleryPage * galleryPageSize
                val toIndex = minOf(fromIndex + galleryPageSize, all.size)
                if (fromIndex >= all.size) {
                    hasMoreGallery = false
                    isGalleryLoading = false
                    return@launch
                }
                val nextSlice = all.subList(fromIndex, toIndex)
                val merged = (cachedLocalMedia ?: emptyList()) + nextSlice
                cachedLocalMedia = merged
                mediaList = merged
                hasMoreGallery = toIndex < all.size
            } catch (e: Exception) {
                errorMessage = "加载更多失败: ${e.message}"
            } finally {
                isGalleryLoading = false
            }
        }
    }

    /**
     * 上传选中的本地媒体到服务器
     *
     * 上传期间通过 [uploadProgress] 暴露实时进度（已传/总数），
     * UI 层据此显示进度对话框。完成后发送 Snackbar 结果消息。
     */
    fun uploadSelectedLocalMedia() {
        if (selectedMediaIds.isEmpty() || isUploading) return

        isUploading = true
        val totalCount = selectedMediaIds.size
        uploadProgress = 0 to totalCount

        viewModelScope.launch {
            try {
                var successCount = 0

                selectedMediaIds.toList().forEachIndexed { index, mediaId ->
                    uploadProgress = (index) to totalCount
                    val mediaData = galleryFeature.getMediaData(mediaId)
                    if (mediaData != null) {
                        val media = mediaList.find { it.id == mediaId }
                        if (media != null) {
                            // 手动上传同样透传 sha256/client_id/taken_at 走后端权威秒传：
                            // 服务端按 (user_id,sha256) 命中即秒传不落盘，省时间省流量。
                            // 与自动备份的区别：手动上传不走 SyncManager/离线队列——
                            // 失败直接报错给用户（errorMessage），不隐式入队后台重试，
                            // 符合"用户主动操作应有明确反馈"的预期。
                            val hash = computeSha256(mediaData)
                            val success = MediaService.uploadMedia(
                                fileData = mediaData,
                                filename = media.filename,
                                isLivePhoto = media.is_live_photo,
                                sha256 = hash,
                                clientId = SettingsState.deviceId,
                                takenAt = media.created_at
                            )
                            if (success) {
                                successCount++
                                // 登记指纹，后续自动备份可本端秒传跳过。
                                dedup.markUploaded(hash)
                            }
                        }
                    }
                    uploadProgress = (index + 1) to totalCount
                }

                if (successCount > 0) {
                    // 上传成功后重新加载列表
                    loadUploadedMediaList()
                    errorMessage = "成功上传 $successCount/$totalCount 个文件"
                } else {
                    errorMessage = "上传失败"
                }

            } catch (e: Exception) {
                errorMessage = "上传本地媒体失败: ${e.message}"
            } finally {
                isUploading = false
                uploadProgress = null
            }
        }
    }

    /**
     * 把当前媒体来源切到云端（BACKEND），不触发任何网络请求。
     *
     * 仅供"文件管理"页等独立云端列表复用 [deleteSelectedMedia] 时使用：该方法内部按
     * [currentSource] 分流，LOCAL 走系统相册删除、BACKEND 走 [MediaService.deleteMedia]。
     * 文件管理页的条目来自 [SyncManager.pullChanges]，删除必须命中云端分支，故删除前
     * 先调用本方法把来源固定为 BACKEND，避免把云端 id 误当作本地相册 id 处理。
     */
    fun setCurrentSourceBackend() {
        currentSource = MediaSource.BACKEND
    }

    /**
     * 开始选择模式并选中指定媒体（长按触发）。
     */
    fun startSelection(mediaId: String) {
        if (!selectedMediaIds.contains(mediaId)) {
            selectedMediaIds.add(mediaId)
        }
    }

    /**
     * 切换媒体选中状态
     */
    fun toggleMediaSelection(mediaId: String) {
        if (selectedMediaIds.contains(mediaId)) {
            selectedMediaIds.remove(mediaId)
        } else {
            selectedMediaIds.add(mediaId)
        }
    }

    /**
     * 全选当前 [filteredList] 中的所有媒体。
     *
     * 选择范围与当前可见列表一致（搜索/筛选后），避免选中不可见项造成困惑。
     * 若已全选则不重复操作。
     */
    fun selectAll() {
        val allIds = filteredList.map { it.id }
        if (selectedMediaIds.toSet() != allIds.toSet()) {
            selectedMediaIds.clear()
            selectedMediaIds.addAll(allIds)
        }
    }

    /**
     * 取消全选（清空选中列表）。
     */
    fun deselectAll() {
        selectedMediaIds.clear()
    }

    /**
     * 批量分享选中的媒体文件。
     *
     * 遍历选中项，依来源获取字节流（本地走 galleryFeature.getMediaData，
     * 后端走 BackendImageLoader 或 MediaService stream），再调 [shareMedia]。
     * 当前实现：逐个分享（系统分享面板每次处理一个文件），
     * 多选时依次弹出。后续可扩展为多文件分享（Android 支持 ACTION_SEND_MULTIPLE）。
     *
     * 分享开始时发送 "分享中..." 提示，全部完成后发送 "已分享" 提示。
     *
     * @param onShareStart 每个文件开始分享时的回调（UI 可显示提示）
     * @param onComplete 全部处理完毕的回调
     */
    fun shareSelectedMedia(
        onShareStart: (filename: String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        if (selectedMediaIds.isEmpty()) return

        errorMessage = "分享中..."
        viewModelScope.launch {
            val shareItems = mutableListOf<ShareMediaItem>()
            for (mediaId in selectedMediaIds) {
                val media = mediaList.find { it.id == mediaId } ?: continue
                try {
                    val bytes = when (currentSource) {
                        MediaSource.LOCAL -> galleryFeature.getMediaData(mediaId)
                        MediaSource.BACKEND -> {
                            // 后端图片：通过 BackendImageLoader 或直接 stream 获取字节
                            BackendImageLoader.loadFullImageBytes(media.id)
                        }
                    }
                    if (bytes != null) {
                        onShareStart(media.filename)
                        val mimeType = when (media.type) {
                            MediaType.VIDEO -> "video/mp4"
                            MediaType.IMAGE, MediaType.LIVE_PHOTO -> "image/jpeg"
                        }
                        shareItems.add(ShareMediaItem(bytes, media.filename, mimeType))
                    }
                } catch (e: Exception) {
                    errorMessage = "分享失败: ${e.message}"
                }
            }
            // 批量分享：一次系统分享面板处理所有文件，而非逐个弹出
            if (shareItems.isNotEmpty()) {
                shareMediaBatch(shareItems)
            }
            errorMessage = "已分享"
            onComplete()
        }
    }

    /**
     * 删除单个媒体（从预览界面调用）。
     *
     * 与 [deleteSelectedMedia] 不同，此方法不依赖选中状态：
     * 直接以传入的 mediaId 调 [MediaService.deleteMedia]，
     * 成功后从 [mediaList] 移除该条目并清理可能的选中态。
     */

    /**
     * V7 §1.2：为当前选中的云端媒体创建分享链接。
     * 仅对 BACKEND 源的 media 有效。
     * 成功后回调 onCreated(url, expiresAt)。
     */
    fun createShareLinkForSelected(
        expiresInHours: Int = 24,
        password: String? = null,
        onCreated: (url: String, expiresAt: Long) -> Unit = { _, _ -> },
        onError: (msg: String) -> Unit = {}
    ) {
        val ids = selectedMediaIds.toList()
        if (ids.isEmpty()) {
            onError("请先选择媒体")
            return
        }
        if (currentSource != MediaSource.BACKEND) {
            onError("仅云端媒体支持分享链接")
            return
        }
        viewModelScope.launch {
            try {
                val link = MediaService.createShareLink(ids, expiresInHours, password)
                if (link != null) {
                    onCreated(link.url, link.expiresAt)
                } else {
                    onError("创建分享链接失败")
                }
            } catch (e: Exception) {
                onError("分享失败: ${'$'}{e.message}")
            }
        }
    }

    /**
     * 删除单条媒体。
     */
    fun deleteSingleMedia(mediaId: String) {
        viewModelScope.launch {
            try {
                if (currentSource == MediaSource.LOCAL) {
                    val deleted = galleryFeature.deleteMedia(listOf(mediaId))
                    if (deleted == -1) {
                        // Android 10+：需要系统确认，拉起 createDeleteRequest 授权弹窗。
                        requestMediaDeletion(listOf(mediaId)) { granted ->
                            viewModelScope.launch {
                                try {
                                    if (granted > 0) {
                                        selectedMediaIds.remove(mediaId)
                                        loadMediaFromGallery(forceRefresh = true)
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "删除媒体失败: ${e.message}"
                                }
                            }
                        }
                        return@launch
                    }
                    if (deleted > 0) {
                        // 本地删除经系统授权（Android 10+ recoverable deletion），
                        // 以 MediaStore 为准重新加载，保证列表与系统一致；同时清理选中态。
                        selectedMediaIds.remove(mediaId)
                        loadMediaFromGallery(forceRefresh = true)
                    }
                } else {
                    val success = MediaService.deleteMedia(listOf(mediaId))
                    if (success) {
                        mediaList = mediaList.filter { it.id != mediaId }
                        selectedMediaIds.remove(mediaId)
                    }
                }
            } catch (e: Exception) {
                errorMessage = "删除媒体失败: ${e.message}"
            }
        }
    }

    /**
     * 批量删除选中的媒体
     *
     * 本地来源（LOCAL）下，Android 10+ scoped storage 对非 owner 媒体需走系统可恢复删除流程：
     * 先调 [GalleryFeature.deleteMedia] 探测，返回 -1 表示需要系统确认，随即调
     * [requestMediaDeletion] 拉起系统授权弹窗完成删除；非 -1 直接按返回数量处理。
     *
     * 删除完成后发送 Snackbar 结果消息（"已删除 N 项"）。
     */
    /**
     * V8：批量给选中项打标签——调后端 /api/media/tag/batch-add。
     */
    fun batchAddTagToSelected(tagName: String) {
        if (selectedMediaIds.isEmpty()) return
        val ids = selectedMediaIds.toList()
        viewModelScope.launch {
            val count = MediaService.batchAddTag(ids, tagName)
            if (count > 0) {
                deselectAll()
            }
        }
    }

    /**
     * V8：批量重命名选中项——调后端 /api/media/batch-rename。
     * 成功后刷新列表并退出选择模式。
     */
    fun batchRenameSelected(pattern: String) {
        if (selectedMediaIds.isEmpty()) return
        val ids = selectedMediaIds.toList()
        viewModelScope.launch {
            val result = MediaService.batchRename(ids, pattern)
            if (result != null) {
                // 刷新云端列表
                loadCloudMediaList()
                // 退出选择模式
                deselectAll()
            }
        }
    }

    fun deleteSelectedMedia() {
        if (selectedMediaIds.isEmpty() || isDeleting) return

        val deleteCount = selectedMediaIds.size
        isDeleting = true

        viewModelScope.launch {
            // -1 异步授权路径由回调自行收尾 isDeleting，同步路径在 finally 统一收尾。
            var deferredToAsync = false
            try {
                if (currentSource == MediaSource.LOCAL) {
                    val idsToDelete = selectedMediaIds.toList()
                    val deleted = galleryFeature.deleteMedia(idsToDelete)
                    if (deleted == -1) {
                        // Android 10+：需要系统确认，拉起 createDeleteRequest 授权弹窗。
                        deferredToAsync = true
                        requestMediaDeletion(idsToDelete) { granted ->
                            viewModelScope.launch {
                                try {
                                    if (granted > 0) {
                                        selectedMediaIds.clear()
                                        errorMessage = "已删除 $granted 项"
                                        loadMediaFromGallery(forceRefresh = true)
                                    } else {
                                        errorMessage = "删除失败，可能需要授权"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "删除媒体失败: ${e.message}"
                                } finally {
                                    isDeleting = false
                                }
                            }
                        }
                        return@launch
                    }
                    if (deleted > 0) {
                        // 本地批量删除经系统授权后，以 MediaStore 为准重新加载。
                        selectedMediaIds.clear()
                        errorMessage = "已删除 $deleted 项"
                        loadMediaFromGallery(forceRefresh = true)
                    } else {
                        errorMessage = "删除失败，可能需要授权"
                    }
                } else {
                    val success = MediaService.deleteMedia(selectedMediaIds.toList())
                    if (success) {
                        // 删除成功后更新列表
                        mediaList = mediaList.filter { it.id !in selectedMediaIds }
                        selectedMediaIds.clear()
                        errorMessage = "已删除 $deleteCount 项"
                    }
                }
            } catch (e: Exception) {
                errorMessage = "删除媒体失败: ${e.message}"
            } finally {
                // 异步授权路径已 deferredToAsync=true 并自行 return，isDeleting 交回调收尾；
                // 其余同步完成/异常路径在此统一重置。
                if (!deferredToAsync) {
                    isDeleting = false
                }
            }
        }
    }

    /**
     * 上传媒体文件
     */
    fun uploadMedia(fileData: ByteArray, filename: String, isLivePhoto: Boolean = false) {
        if (isUploading) return

        isUploading = true

        viewModelScope.launch {
            try {
                val success = MediaService.uploadMedia(fileData, filename, isLivePhoto)
                if (success) {
                    // 上传成功后重新加载列表
                    loadUploadedMediaList()
                }
            } catch (e: Exception) {
                errorMessage = "上传媒体失败: ${e.message}"
            } finally {
                isUploading = false
            }
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * 后台预取列表中视频项的时长。
     *
     * 仅对 `type == VIDEO` 且尚未在 [videoDurations] 中的项请求后端 video-info；
     * 串行请求（避免突发打满后端，ffprobe 每项含 15s 超时）。每拿到一项即换新 Map
     * 触发 UI 刷新，网格时长标签随到达逐步显现，播放器进入时也能拿到初始时长。
     * 非视频 / 请求失败被静默跳过（播放器会按实际播放兜底取时长）。
     */
    private fun prefetchVideoDurations(list: List<MediaMetadata>) {
        val toFetch = list.filter { it.type == MediaType.VIDEO && !videoDurations.containsKey(it.id) }
        if (toFetch.isEmpty()) return
        viewModelScope.launch {
            for (media in toFetch) {
                val info = try {
                    loadVideoInfo(currentBackendBaseUrl(), media.id)
                } catch (e: Exception) {
                    null
                }
                if (info != null && info.durationSeconds > 0) {
                    videoDurations = videoDurations + (media.id to info.durationSeconds)
                }
            }
        }
    }

    /**
     * 按 [created_at] 日期分组的媒体列表（计算属性）。
     *
     * - 组内保持原 [mediaList] 的相对顺序；
     * - 组与组按日期**倒序**（最近日期在最前），呼应"今天 → 昨天 → 更早"的时间线浏览直觉；
     * - 标题："今天"/"昨天"/"YYYY年MM月DD日"，"今天/昨天"基于**当前时间**与该日起点
     *   的整日差值判定（非简单的 24h 差值，跨午夜时不会错位）。
     *
     * 供 [DateGroupedGrid] 使用；搜索态下 UI 直接走平铺 [mediaList] 不经此属性，
     * 故分组只在非搜索场景生效。
     *
     * 注意：`created_at` 为 epoch **毫秒**（与后端 [MediaService] 解析口径一致）。
     */
    val groupedMediaList: List<DateGroup>
        get() = groupMediaByDate(mediaList)

    /**
     * 时光相册月份回忆卡片列表（PRD-v7 §1.4，计算属性）。
     *
     * 基于 [cloudMedia]（已上传/云端增量同步累积视图）按 `created_at` 的**年月**分组，
     * 每月取前 4 张作为封面预览，整体按年月**倒序**（最近月份在最前），供「已上传」Tab
     * 顶部的 [MemoryCardRow] 横滚卡片渲染，点击进入 [MemoryDetailScreen] 查看整月图片。
     *
     * 与 [groupedMediaList] 区别：
     * - 数据源固定为 [cloudMedia]（回忆是云端已上传内容的月份聚合），不随 Tab 切换而变；
     * - 粒度为「月」而非「日」，对应"2026年7月"级别的回忆卡片标题。
     *
     * `cloudMedia` 为空时返回空列表，UI 隐藏回忆卡片区域。
     *
     * 注意：`created_at` 为 epoch **毫秒**。月份分解复用 [civilFromDays]/[epochDaysFromMillis]
     * 既有算法（与 [groupMediaByDate] 同一时区口径），保证"2026年7月"标签与本地日历一致。
     */
    val memoryMonths: List<MemoryMonth>
        get() = groupMediaByMonth(cloudMedia)

    /**
     * 把云端媒体按 `created_at` 的年月分组，生成 [MemoryMonth] 列表。
     *
     * - 月份标识由 epoch 毫秒经 [epochDaysFromMillis] → [civilFromDays] 拆出年/月（与
     *   [groupMediaByDate] 同一时区 [systemTimeZoneOffsetMillis]，保证本地日历一致）；
     * - 每月取前 [MEMORY_COVER_COUNT]（4）张作为封面；不足按实际数量；
     * - 组按年月倒序（最近月份在最前），呼应时间线浏览直觉；
     * - 标题格式「YYYY年M月」（月份不补零，如「2026年7月」而非「07月」，更符合中文习惯）。
     */
    private fun groupMediaByMonth(list: List<MediaMetadata>): List<MemoryMonth> {
        if (list.isEmpty()) return emptyList()

        val tzOffsetMillis = systemTimeZoneOffsetMillis()

        // 按年月分组，保持组内原顺序；用 LinkedHashMap 按首次出现顺序保留月份次序。
        val byMonth = LinkedHashMap<Pair<Int, Int>, MutableList<MediaMetadata>>()
        for (m in list) {
            val days = epochDaysFromMillis(m.created_at, tzOffsetMillis)
            val (y, month, _) = civilFromDays(days)
            byMonth.getOrPut(y to month) { mutableListOf() }.add(m)
        }

        return byMonth.entries
            // 按年月倒序：最近月份在先。用 "YYYY-MM" 字符串做排序键（字典序与年月序一致），
            // 规避 KMP common 下 Pair 的 Comparable 实现不被 sortedByDescending 识别、
            // 以及 Int 解构 * 运算被误解析到 BigDecimal.times 扩展的两类类型推断歧义。
            .sortedByDescending { "${it.key.first}-${it.key.second}" }
            .map { (ym, items) ->
                val (y, m) = ym
                MemoryMonth(
                    year = y,
                    month = m,
                    title = "${y}年${m}月",
                    coverItems = items.take(MEMORY_COVER_COUNT),
                    totalCount = items.size
                )
            }
    }

    /**
     * 取指定年份+月份的云端媒体列表，供 [MemoryDetailScreen] 渲染整月回忆。
     *
     * 月份匹配口径与 [groupMediaByMonth] 一致（基于本地时区拆出的年月），
     * 保证点击「2026年7月」卡片后详情页看到的正是该月全部图片。
     *
     * @param year 年份（如 2026）
     * @param month 月份（1-12）
     * @return 该月云端媒体列表（保持 cloudMedia 原序）；无匹配返回空列表
     */
    fun getMediaByMonth(year: Int, month: Int): List<MediaMetadata> {
        if (cloudMedia.isEmpty()) return emptyList()
        val tzOffsetMillis = systemTimeZoneOffsetMillis()
        return cloudMedia.filter {
            val days = epochDaysFromMillis(it.created_at, tzOffsetMillis)
            val (y, m, _) = civilFromDays(days)
            y == year && m == month
        }
    }

    /**
     * 取指定年份+月份、按日分组的云端媒体分组列表，供 [MemoryDetailScreen] 用
     * [DateGroupedGrid] 渲染（保留「今天/昨天/YYYY年MM月DD日」的日内分组体验）。
     *
     * 先用 [getMediaByMonth] 过滤当月图片，再经既有 [groupMediaByDate] 按日聚合，
     * 复用与主网格一致的标题生成与倒序排序，避免详情页另造一套分组逻辑。
     */
    fun getGroupedMediaByMonth(year: Int, month: Int): List<DateGroup> {
        return groupMediaByDate(getMediaByMonth(year, month))
    }

    /**
     * 把媒体列表按 created_at 的日期分组并生成标题。
     *
     * 不直接依赖 java.time（commonMain 不可用），日期分解走天数折算：
     * 用 epoch 天数（millis / MILLIS_PER_DAY 向下取整）作为"日边界"。今天/昨天的
     * 语义对应当前 epoch 天数与媒体 epoch 天数之差为 0 / 1。
     *
     * "YYYY年MM月DD日" 的年月日分解用 civil_from_days（Howard Hinnant 的算法，
     * 纯整数运算、跨平台确定），由 [dateTitleFromEpochDays] 完成；时区取本机
     * （用本地时区偏移把 epoch 毫秒对齐到本地午夜），符合"今天"的用户直觉。
     * 时区偏移由 [systemTimeZoneOffsetMillis] 提供（平台 expect/actual）。
     */
    private fun groupMediaByDate(list: List<MediaMetadata>): List<DateGroup> {
        if (list.isEmpty()) return emptyList()

        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val tzOffsetMillis = systemTimeZoneOffsetMillis()
        // 今天所在的本日历日（用本地时区对齐到本地午夜后的 epoch 天数）。
        val todayDays = epochDaysFromMillis(nowMillis, tzOffsetMillis)

        // 按日期分组、保持组内原顺序；用 LinkedHashMap 保留首次出现顺序以便后续排序。
        val byDay = LinkedHashMap<Long, MutableList<MediaMetadata>>()
        for (m in list) {
            val days = epochDaysFromMillis(m.created_at, tzOffsetMillis)
            byDay.getOrPut(days) { mutableListOf() }.add(m)
        }

        // 组按日期倒序（最近在先）；组内顺序保持 mediaList 原序。
        return byDay.entries
            .sortedByDescending { it.key }
            .map { (days, items) ->
                DateGroup(
                    title = relativeDateTitle(days, todayDays),
                    items = items
                )
            }
    }

    private fun relativeDateTitle(itemDays: Long, todayDays: Long): String {
        val diff = todayDays - itemDays
        return when (diff) {
            0L -> "今天"
            1L -> "昨天"
            else -> dateTitleFromEpochDays(itemDays)
        }
    }

    /**
     * 由 epoch 天数生成本地化日期标题 "YYYY年MM月DD日"。
     * epoch 天数 → 儒略日 → 用 Howard Hinnant 的 civil_from_days 拆为年/月/日。
     */
    private fun dateTitleFromEpochDays(days: Long): String {
        // days 是自 1970-01-01 起的天数；civil_from_days 接受自 1970-01-01 起的天数。
        val (y, m, d) = civilFromDays(days)
        return "${y}年${m.pad2()}月${d.pad2()}日"
    }

    /**
     * epoch 毫秒 → 本地日历日（自 1970-01-01 起的天数，向负亦取整）。
     * [tzOffsetMillis] 把 UTC 毫秒平移到本地后再折算，保证"本地午夜"为日界。
     */
    private fun epochDaysFromMillis(epochMillis: Long, tzOffsetMillis: Long): Long {
        // 向下取整（含负数）：floor((millis + offset) / MILLIS_PER_DAY)
        val shifted = epochMillis + tzOffsetMillis
        return if (shifted >= 0) shifted / MILLIS_PER_DAY
        else (shifted - MILLIS_PER_DAY + 1) / MILLIS_PER_DAY
    }

    /**
     * Howard Hinnant civil_from_days：自 1970-01-01 起的天数 → (年, 月, 日)。
     * 纯整数运算，无平台依赖。详见 http://howardhinnant.github.io/date_algorithms.html
     */
    private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
        val z0 = z + 719468L
        val era = if (z0 >= 0) z0 / 146097 else (z0 - 146096) / 146097
        val doe = z0 - era * 146097                       // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365  // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)  // [0, 365]
        val mp = (5 * doy + 2) / 153                       // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()      // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()   // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m, d)
    }

    /** 十进制两位补零（1 → "01"）。commonMain 无 `String.format`，纯 Kotlin 实现。 */
    private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()

    /**
     * 获取选中的媒体数量
     */
    val selectedCount: Int
        get() = selectedMediaIds.size

    /**
     * 是否有选中的媒体
     */
    val hasSelection: Boolean
        get() = selectedMediaIds.isNotEmpty()

    /**
     * 是否有本地图库权限
     */
    val canAccessGallery: Boolean
        get() = hasGalleryPermission

    // ---- 相册操作 ----

    /**
     * 加载相册列表。
     *
     * @param forceRefresh true 绕过缓存强制请求后端
     */
    fun loadAlbums(forceRefresh: Boolean = false) {
        if (isAlbumLoading) return
        if (!forceRefresh && albumList.isNotEmpty()) return

        isAlbumLoading = true
        errorMessage = null
        viewModelScope.launch {
            try {
                albumList = MediaService.getAlbums()
            } catch (e: Exception) {
                errorMessage = "加载相册列表失败: ${e.message}"
            } finally {
                isAlbumLoading = false
            }
        }
    }

    // ============================================================
    // V7 §2.3：共享相册
    // ============================================================

    /** 加载被共享给当前用户的相册列表。 */
    fun loadSharedAlbums() {
        viewModelScope.launch {
            try {
                sharedAlbumList = MediaService.getSharedAlbums()
            } catch (e: Exception) {
                // 静默失败——共享相册是辅助功能
            }
        }
    }

    /**
     * 邀请用户共享相册。
     * @param albumId 相册 ID
     * @param username 被邀请用户名
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun shareAlbum(
        albumId: String,
        username: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val ok = MediaService.shareAlbum(albumId, username)
                if (ok) {
                    errorMessage = "已共享给 $username"
                    onSuccess()
                } else {
                    onError("共享失败")
                }
            } catch (e: Exception) {
                onError("共享失败: ${e.message}")
            }
        }
    }

    /**
     * 创建新相册。
     *
     * 成功后刷新列表并提示。
     */
    fun createAlbum(name: String) {
        if (isAlbumLoading) return
        isAlbumLoading = true
        viewModelScope.launch {
            try {
                val album = MediaService.createAlbum(name)
                if (album != null) {
                    albumList = albumList + album
                    errorMessage = "相册「${album.name}」已创建"
                } else {
                    errorMessage = "创建相册失败"
                }
            } catch (e: Exception) {
                errorMessage = "创建相册失败: ${e.message}"
            } finally {
                isAlbumLoading = false
            }
        }
    }

    /**
     * 删除相册。成功后从列表移除。
     */
    fun deleteAlbum(albumId: String) {
        viewModelScope.launch {
            try {
                val success = MediaService.deleteAlbum(albumId)
                if (success) {
                    albumList = albumList.filter { it.id != albumId }
                    errorMessage = "相册已删除"
                }
            } catch (e: Exception) {
                errorMessage = "删除相册失败: ${e.message}"
            }
        }
    }

    /**
     * 加载相册详情内的媒体列表。
     *
     * 通过 [MediaService.getAlbumDetail] 获取相册的 media_ids，
     * 再从后端全量媒体列表中筛选对应条目，保证只显示属于该相册的媒体。
     */
    fun loadAlbumDetail(albumId: String) {
        if (isAlbumDetailLoading) return
        isAlbumDetailLoading = true
        albumDetailMedia = emptyList()
        viewModelScope.launch {
            try {
                val detail = MediaService.getAlbumDetail(albumId)
                if (detail != null && detail.mediaIds.isNotEmpty()) {
                    // 从后端拉取全量列表，再按相册 mediaIds 过滤
                    val allMedia = MediaService.getMediaList(source = MediaSource.BACKEND, pageSize = 200)
                    val idSet = detail.mediaIds.toSet()
                    albumDetailMedia = allMedia.filter { it.id in idSet }
                } else {
                    albumDetailMedia = emptyList()
                }
            } catch (e: Exception) {
                errorMessage = "加载相册内容失败: ${e.message}"
            } finally {
                isAlbumDetailLoading = false
            }
        }
    }

    /**
     * 将指定媒体加入相册。
     *
     * 由网格长按菜单「加入相册」触发：先弹出相册选择列表（UI 层），
     * 选定后调此方法。
     */
    fun addMediaToAlbum(albumId: String, mediaId: String) {
        viewModelScope.launch {
            try {
                val success = MediaService.addMediaToAlbum(albumId, mediaId)
                if (success) {
                    errorMessage = "已加入相册"
                    // 刷新相册列表以更新计数
                    loadAlbums(forceRefresh = true)
                } else {
                    errorMessage = "加入相册失败"
                }
            } catch (e: Exception) {
                errorMessage = "加入相册失败: ${e.message}"
            }
        }
    }

    /**
     * 弹出「加入相册」选择对话框（由网格长按菜单触发）。
     */
    fun showAddToAlbumDialog(mediaId: String) {
        pendingAddToAlbumMediaId = mediaId
        // 确保相册列表是最新的
        if (albumList.isEmpty()) loadAlbums()
    }

    /**
     * 关闭「加入相册」对话框。
     */
    fun dismissAddToAlbumDialog() {
        pendingAddToAlbumMediaId = null
    }

    // ============================================================
    // V7 §2.4：存储清理建议
    // ============================================================

    /** 清理建议类型。 */
    enum class CleanupCategory { DUPLICATE, LARGE_FILE, OLD_PHOTO }

    /** 单条清理建议。 */
    data class CleanupSuggestion(
        val category: CleanupCategory,
        val media: MediaMetadata,
        val reason: String
    )

    /**
     * 分析 [cloudMedia] 生成清理建议：
     * - 疑似重复：filename + size 相同（不同 id）
     * - 大文件：size > 10MB 的 Top 10
     * - 老照片：created_at > 365 天前
     *
     * @return 按类别分组的建议列表
     */
    fun analyzeCleanupSuggestions(): List<CleanupSuggestion> {
        val media = cloudMedia
        if (media.isEmpty()) return emptyList()

        val suggestions = mutableListOf<CleanupSuggestion>()

        // 1. 疑似重复：filename + size 相同
        val grouped = media.groupBy { "${it.filename}_${it.size}" }
        grouped.filter { it.key != "_0" && it.value.size > 1 }.forEach { (_, group) ->
            // 保留第一个，其余标记为重复
            group.drop(1).forEach { m ->
                suggestions.add(CleanupSuggestion(CleanupCategory.DUPLICATE, m, "与 ${group.size} 个文件同名同大小"))
            }
        }

        // 2. 大文件 Top 10（> 10MB）
        val tenMB = 10L * 1024 * 1024
        media.filter { it.size > tenMB }
            .sortedByDescending { it.size }
            .take(10)
            .forEach { m ->
                suggestions.add(CleanupSuggestion(CleanupCategory.LARGE_FILE, m, "大文件 ${formatFileSize(m.size)}"))
            }

        // 3. 老照片（> 365 天前）
        val oneYearAgoMs = Clock.System.now().toEpochMilliseconds() - 365L * 24 * 60 * 60 * 1000
        media.filter { it.created_at > 0 && it.created_at < oneYearAgoMs }
            .forEach { m ->
                suggestions.add(CleanupSuggestion(CleanupCategory.OLD_PHOTO, m, "超过一年未访问"))
            }

        return suggestions
    }

    /** 格式化文件大小（commonMain 无 String.format，手动实现）。 */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toInt()} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${mb.toInt()} MB"
        val gb = mb / 1024.0
        return "${gb.toInt()} GB"
    }

    /**
     * 批量删除清理建议中选中的媒体（调 deleteMedia）。
     * @param ids 要删除的 media id 列表
     * @param onComplete 删除完成回调
     */
    fun deleteCleanupItems(ids: List<String>, onComplete: () -> Unit = {}) {
        if (ids.isEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            var deleted = 0
            for (id in ids) {
                try {
                    MediaService.deleteMedia(listOf(id))
                    cloudMedia = cloudMedia.filterNot { it.id == id }
                    deleted++
                } catch (e: Exception) {
                    // 继续删除其他项
                }
            }
            errorMessage = "已删除 $deleted/${ids.size} 项"
            onComplete()
        }
    }

    // ---- V7：后端重复文件检测 ----

    var duplicates by mutableStateOf<MediaService.DuplicateResult?>(null)
        private set

    fun loadDuplicates() {
        viewModelScope.launch {
            duplicates = MediaService.getDuplicates()
        }
    }

    // ---- V7：重命名媒体 ----

    fun renameMedia(mediaId: String, newName: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = MediaService.renameMedia(mediaId, newName)
            if (success) {
                // 更新本地 cloudMedia 中的 filename
                cloudMedia = cloudMedia.map {
                    if (it.id == mediaId) it.copy(filename = newName) else it
                }
            }
            onComplete(success)
        }
    }

    // ---- V7：从相册移除媒体 ----

    fun removeFromAlbum(albumId: String, mediaId: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = MediaService.removeMediaFromAlbum(albumId, mediaId)
            if (success) {
                // 更新本地 albumDetailMedia
                albumDetailMedia = albumDetailMedia.filter { it.id != mediaId }
            }
            onComplete(success)
        }
    }

    // ---- V7：批量添加媒体到相册 ----

    fun batchAddMediaToAlbum(albumId: String, mediaIds: List<String>, onComplete: (Int?) -> Unit = {}) {
        viewModelScope.launch {
            val added = MediaService.batchAddMediaToAlbum(albumId, mediaIds)
            if (added != null) {
                loadAlbumDetail(albumId)
            }
            onComplete(added)
        }
    }

    // ---- V7：设置相册封面 ----

    fun setAlbumCover(albumId: String, mediaId: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = MediaService.setAlbumCover(albumId, mediaId)
            onComplete(success)
        }
    }

    /**
     * V7：一键删除重复文件（保留每组最新的一份）。
     * 收集所有 delete_ids，调 deleteMedia 批量删除，然后刷新。
     */
    fun deleteDuplicates(onComplete: () -> Unit = {}) {
        val dup = duplicates ?: run { onComplete(); return }
        val toDelete = dup.groups.flatMap { it.media.drop(1).map { m -> m.id } }
        if (toDelete.isEmpty()) { onComplete(); return }
        viewModelScope.launch {
            var deleted = 0
            for (id in toDelete) {
                try {
                    MediaService.deleteMedia(listOf(id))
                    cloudMedia = cloudMedia.filterNot { it.id == id }
                    deleted++
                } catch (e: Exception) { /* 继续删除其他 */ }
            }
            errorMessage = "已删除 $deleted/${toDelete.size} 个重复文件"
            duplicates = MediaService.getDuplicates()  // 刷新
            onComplete()
        }
    }
}
