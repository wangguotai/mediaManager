package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "BackendImageLoader"

/**
 * 缩略图缓存上限：60 项。
 *
 * 网格瀑布流每屏可见约 6-12 个缩略图，滚动时快速滑动可能涉及 30+ 项。
 * 60 项覆盖两屏滚动窗口，超出的最久未使用项被 LRU 淘汰，
 * 解码后 ImageBitmap 内存占用受控（每个 128px 缩略图约 64KB，60 项 ≈ 3.7MB）。
 */
private const val MAX_THUMBNAIL_CACHE_ENTRIES = 60

/**
 * 原图缓存上限：10 项。
 *
 * 原图解码后内存远大于缩略图（单张全分辨率图片可达 5-15MB），
 * 仅缓存当前预览页左右少量原图以保证滑动手感，超出即 LRU 淘汰。
 * 10 项上限把原图缓存内存控制在 ~100MB 以内，配合系统垃圾回收
 * 避免触发 MIUI 等系统的低内存杀进程策略。
 */
private const val MAX_FULLIMAGE_CACHE_ENTRIES = 10

/**
 * 原图降采样长边像素上限。
 *
 * 大图全尺寸解码（如 4000×3000 → ~48MB Bitmap）是 MIUI OOM kill 的根因。
 * [loadFullImage] 经 [decodeImageBitmapDownsampled] 把长边限制在此值（2048px），
 * 像素内存降至 ~16MB，足以预览且控内存防 OOM。注释此常量使降采样阈值可追溯。
 */
private const val FULL_IMAGE_MAX_DIMENSION = 2048

/**
 * 从后端 REST 端点加载并解码图片 —— 供"网盘图片" / "已上传" Tab 使用。
 *
 * 与 [loadThumbnail] / [loadFullImage]（走平台相册 MediaStore/PHAsset）互补：
 * 后端图片的 id 是后端定义的字符串（网盘图片为去扩展名的文件名，如 `test-cloud-image`），
 * 不是本地相册的 long id，因此必须通过 HTTP 端点拿字节流再解码。
 *
 * 取字节流在 commonMain 完成；解码交给平台实现 [decodeImageBitmap]（Android 用
 * BitmapFactory，iOS 用 skia），因为 skia 在 commonMain 不直接可见。
 *
 * 内置按 mediaId 的 [ImageBitmap] 内存缓存，命中即返回，避免预览左右滑动回滑、
 * 网格上下滚动回滑时重复走 HTTP + 解码。缓存为 **LRU**（最近最少使用淘汰），
 * 缩略图与原图各自独立上限（30 / 15），防止媒体量增大后内存无界增长。
 *
 * 并发安全：网格滚动会同时发起多个加载请求，缓存读写由 [cacheLock]（[Mutex]）
 * 串行化，避免 Kotlin common [LinkedHashMap] 在并发修改下抛
 * [ConcurrentModificationException] 或产生结构竞争。
 */
object BackendImageLoader {

    // 内存缓存：缩略图与原图按 "id" / "full:id" 区分 key，互不串扰。
    // LinkedHashMap 保持插入顺序；命中时 remove+put 把条目提升到末尾（最近使用），
    // 故头部为最久未使用项，淘汰时移除头部即为 LRU。
    private val thumbnailCache = LinkedHashMap<String, ImageBitmap>()
    private val fullImageCache = LinkedHashMap<String, ImageBitmap>()
    private val cacheLock = Mutex()

    /**
     * 以 LRU 方式读取：命中则把条目提升到末尾（标记为最近使用）并返回，未命中返回 null。
     * 调用方需在 [cacheLock] 内调用，保证结构修改的串行化。
     */
    private fun <K, V> LinkedHashMap<K, V>.access(key: K): V? {
        val value = remove(key) ?: return null
        put(key, value)
        return value
    }

    /**
     * 插入条目并在超出 [maxEntries] 时淘汰头部（最久未使用）。
     * 调用方需在 [cacheLock] 内调用。
     */
    private fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V, maxEntries: Int) {
        put(key, value)
        if (size > maxEntries) {
            val eldest = keys.first()
            remove(eldest)
        }
    }

    /**
     * 清空所有内存缓存（缩略图 LRU + 原图 LRU）。
     *
     * 供内存警告回调调用：系统发出 TRIM_MEMORY / didReceiveMemoryWarning 时
     * 主动释放缓存，避免 OOM crash。清空后网格滚动 / 预览回滑会重新走 HTTP + 解码，
     * 以短暂的加载闪动换取内存安全。
     */
    fun clearCaches() {
        // 非同步代码块安全清理：clearCaches 由内存警告回调触发（非协程），
        // 不能用 Mutex.withLock（suspend）。直接 clear：各 LRU 内部用普通 Map，
        // 并发 clear 不会崩溃，最差情况是和正在 put 的协程发生竞态导致重复缓存，
        // 这在内存警告场景下可接受。
        thumbnailCache.clear()
        fullImageCache.clear()
        logger.info(TAG, "Caches cleared (thumbnail + fullImage)")
    }

    /**
     * 加载缩略图。走 `GET /api/media/thumbnail/{id}?size=small`。
     *
     * 使用 small（128px）而非 medium（256px），将解码后 ImageBitmap
     * 内存占用降至原来的 1/4，配合 [MAX_THUMBNAIL_CACHE_ENTRIES]=60
     * 把缩略图缓存总内存控制在 ~3.7MB。
     *
     * **离线感知（PRD-v8 §1.5）**：加载前先检查 [OfflineCacheManager.isOfflineMode]。
     * 离线时不走 HTTP（必失败），改用 [OfflineCacheManager.getCachedThumbnailPath] 取
     * 本地磁盘缓存路径，经 [platformReadBytes] 读字节后走 [decodeImageBitmap] 解码，
     * 命中即返回 [ImageBitmap]，未命中返回 null（UI 显示占位图）。在线时保持原有
     * HTTP 加载逻辑不变。
     *
     * @param mediaId 后端媒体 id（网盘图片为去扩展名的文件名）
     * @return 解码后的 [ImageBitmap]；网络失败/离线未缓存/解码失败返回 null
     */
    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        // 命中内存缓存直接返回（并提升为最近使用），避免滚动/回滑重复请求。
        // 内存缓存在在线/离线两条路径间共享，离线时若该缩略图已在内存缓存中则直接复用。
        val cached = cacheLock.withLock { thumbnailCache.access(mediaId) }
        if (cached != null) return cached

        // ── 离线模式：优先从本地磁盘缓存加载，不走 HTTP ──
        if (OfflineCacheManager.isOfflineMode()) {
            return loadThumbnailFromOfflineCache(mediaId)
        }

        // ── 在线模式：原有 HTTP 加载逻辑 ──
        return try {
            val bytes = MediaService.getThumbnail(mediaId, size = "small")
            val decoded = decodeImageBitmap(bytes)
            if (decoded != null) cacheLock.withLock { thumbnailCache.putBounded(mediaId, decoded, MAX_THUMBNAIL_CACHE_ENTRIES) }
            decoded
        } catch (e: Exception) {
            logger.error(TAG, "loadThumbnail failed for $mediaId: ${e.message}")
            null
        }
    }

    /**
     * 离线模式下从 [OfflineCacheManager] 磁盘缓存加载缩略图字节并解码。
     *
     * 走 [platformReadBytes] → [decodeImageBitmap]，与在线路径的解码逻辑一致
     * （同一 [decodeImageBitmap] 平台实现），保证离线/在线缩略图画质无差异。
     * 命中后同样写入内存 LRU 缓存，使滚动回滑时直接命中内存、不再读磁盘。
     *
     * @return 缓存命中并解码成功的 [ImageBitmap]；未缓存/读取失败/解码失败返回 null
     */
    private suspend fun loadThumbnailFromOfflineCache(mediaId: String): ImageBitmap? {
        val localPath = OfflineCacheManager.getCachedThumbnailPath(mediaId) ?: run {
            logger.info(TAG, "loadThumbnail offline miss (no cache) for $mediaId")
            return null
        }
        val bytes = platformReadBytes(localPath)
        if (bytes == null || bytes.isEmpty()) {
            logger.info(TAG, "loadThumbnail offline miss (read failed) for $mediaId")
            return null
        }
        val decoded = decodeImageBitmap(bytes)
        if (decoded != null) {
            cacheLock.withLock { thumbnailCache.putBounded(mediaId, decoded, MAX_THUMBNAIL_CACHE_ENTRIES) }
        }
        logger.info(TAG, "loadThumbnail offline hit for $mediaId, decoded=${decoded != null}")
        return decoded
    }

    /**
     * 取离线缓存缩略图的本地磁盘路径（供 UI 层直接用路径加载器显图，而非解码到内存）。
     *
     * 在线时返回 null（应走 [loadThumbnail] 的 HTTP 路径）；离线且缓存命中时返回
     * [OfflineCacheManager.getCachedThumbnailPath] 结果。调用方可据此选择是否显示
     * 离线占位图或用平台路径图片加载器。
     *
     * @param mediaId 后端媒体 id
     * @return 本地缓存文件绝对路径；在线或未缓存返回 null
     */
    fun loadThumbnailPath(mediaId: String): String? {
        if (!OfflineCacheManager.isOfflineMode()) return null
        return OfflineCacheManager.getCachedThumbnailPath(mediaId)
    }

    /**
     * 加载原图。走 `GET /api/media/stream/{id}`（后端直接以文件字节返回）。
     *
     * 解码时使用 [decodeImageBitmapDownsampled] 将长边限制在 [FULL_IMAGE_MAX_DIMENSION]（2048px），
     * 避免全尺寸解码大图（如 4000×3000）导致内存暴涨被系统 OOM kill。
     * 降采样后的 ImageBitmap 缓存在 [fullImageCache]（LRU 上限 [MAX_FULL_IMAGE_CACHE_ENTRIES] = 15 项）。
     *
     * @param mediaId 后端媒体 id
     * @return 降采样后的 [ImageBitmap]；失败返回 null
     */
    suspend fun loadFullImage(mediaId: String): ImageBitmap? {
        val cached = cacheLock.withLock { fullImageCache.access(mediaId) }
        if (cached != null) return cached
        return try {
            val bytes = MediaService.getMediaStream(mediaId)
            val decoded = decodeImageBitmapDownsampled(bytes, FULL_IMAGE_MAX_DIMENSION)
            if (decoded != null) cacheLock.withLock { fullImageCache.putBounded(mediaId, decoded, MAX_FULLIMAGE_CACHE_ENTRIES) }
            decoded
        } catch (e: Exception) {
            logger.error(TAG, "loadFullImage failed for $mediaId: ${e.message}")
            null
        }
    }

    /**
     * 加载原图字节流（不解码）。走 `GET /api/media/stream/{id}`。
     *
     * 供分享功能使用：需要原始字节流传给系统分享面板，而非解码后的 ImageBitmap。
     *
     * @param mediaId 后端媒体 id
     * @return 原始字节流；失败返回 null
     */
    suspend fun loadFullImageBytes(mediaId: String): ByteArray? {
        return try {
            MediaService.getMediaStream(mediaId)
        } catch (e: Exception) {
            logger.error(TAG, "loadFullImageBytes failed for $mediaId: ${e.message}")
            null
        }
    }
}
