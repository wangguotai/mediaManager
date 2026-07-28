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
import kotlinx.coroutines.launch
import media.MediaMetadata
import media.MediaType
import com.wgt.feature.gallery.gallery
import kotlin.time.Clock

private const val TAG = "MediaViewModel"

/** 一天对应的毫秒数（UTC），用于把 epoch 毫秒折算为"日"边界做分组。 */
private const val MILLIS_PER_DAY = 86_400_000L

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

    // 类型过滤维度：ALL=不过滤 / IMAGE=图片(含 Live Photo) / VIDEO=仅视频。
    var filterType by mutableStateOf(MediaFilterType.ALL)
        private set

    /**
     * 经搜索关键词 + 类型筛选后的媒体列表，供网格直接渲染。
     *
     * 基于 [mediaList] 实时派生：任一输入（[searchQuery] / [filterType] / [mediaList]）变化
     * 即重算。用 [derivedStateOf] 缓存，仅当结果列表实例变化时才触发 UI 重组，避免无谓重算。
     *
     * - 关键词匹配 [MediaMetadata.filename]（大小写不敏感、去首尾空格），命中子串即保留。
     * - 类型过滤遵循 [MediaFilterType] 注释：IMAGE 含 IMAGE 与 LIVE_PHOTO，VIDEO 仅 VIDEO。
     */
    val filteredList: List<MediaMetadata> by derivedStateOf {
        val q = searchQuery.trim()
        mediaList
            .asSequence()
            .filter { matchesTypeFilter(it.type) }
            .filter { q.isEmpty() || it.filename.contains(q, ignoreCase = true) }
            .toList()
    }

    /**
     * 当前 [filterType] 是否接纳该媒体类型。
     */
    private fun matchesTypeFilter(type: MediaType): Boolean = when (filterType) {
        MediaFilterType.ALL -> true
        // 图片维度：Live Photo 本质是带视频的图片，归图片浏览。
        MediaFilterType.IMAGE -> type == MediaType.IMAGE || type == MediaType.LIVE_PHOTO
        MediaFilterType.VIDEO -> type == MediaType.VIDEO
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


    init {
        logger.info(TAG, "init")
//        loadUploadedMediaList()
        loadMediaFromGallery()
    }

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
     * 从本地照片图库加载媒体
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
                val galleryMedia = galleryFeature.getMediaFromGallery()
                cachedLocalMedia = galleryMedia
                mediaList = galleryMedia

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
     * 上传选中的本地媒体到服务器
     */
    fun uploadSelectedLocalMedia() {
        if (selectedMediaIds.isEmpty() || isUploading) return

        isUploading = true

        viewModelScope.launch {
            try {
                var successCount = 0
                val totalCount = selectedMediaIds.size

                for (mediaId in selectedMediaIds) {
                    val mediaData = galleryFeature.getMediaData(mediaId)
                    if (mediaData != null) {
                        val media = mediaList.find { it.id == mediaId }
                        if (media != null) {
                            val success = MediaService.uploadMedia(
                                mediaData,
                                media.filename,
                                media.is_live_photo
                            )
                            if (success) {
                                successCount++
                            }
                        }
                    }
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
            }
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
     * @param onShareStart 每个文件开始分享时的回调（UI 可显示提示）
     * @param onComplete 全部处理完毕的回调
     */
    fun shareSelectedMedia(
        onShareStart: (filename: String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        if (selectedMediaIds.isEmpty()) return

        viewModelScope.launch {
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
                        shareMedia(bytes, media.filename, mimeType)
                    }
                } catch (e: Exception) {
                    errorMessage = "分享失败: ${e.message}"
                }
            }
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
    fun deleteSingleMedia(mediaId: String) {
        viewModelScope.launch {
            try {
                val success = MediaService.deleteMedia(listOf(mediaId))
                if (success) {
                    mediaList = mediaList.filter { it.id != mediaId }
                    selectedMediaIds.remove(mediaId)
                }
            } catch (e: Exception) {
                errorMessage = "删除媒体失败: ${e.message}"
            }
        }
    }

    /**
     * 批量删除选中的媒体
     */
    fun deleteSelectedMedia() {
        if (selectedMediaIds.isEmpty() || isDeleting) return

        isDeleting = true

        viewModelScope.launch {
            try {
                val success = MediaService.deleteMedia(selectedMediaIds.toList())
                if (success) {
                    // 删除成功后更新列表
                    mediaList = mediaList.filter { it.id !in selectedMediaIds }
                    selectedMediaIds.clear()
                }
            } catch (e: Exception) {
                errorMessage = "删除媒体失败: ${e.message}"
            } finally {
                isDeleting = false
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
}
