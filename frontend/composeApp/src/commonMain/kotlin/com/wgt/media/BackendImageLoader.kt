package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "BackendImageLoader"

/** 每个缓存（缩略图 / 原图）最多保留的条目数，超出按 LRU 淘汰最久未使用项。 */
private const val MAX_CACHE_ENTRIES = 50

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
 * 缩略图与原图各自上限 [MAX_CACHE_ENTRIES] 项，防止媒体量增大后内存无界增长。
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
     * 插入条目并在超出 [MAX_CACHE_ENTRIES] 时淘汰头部（最久未使用）。
     * 调用方需在 [cacheLock] 内调用。
     */
    private fun <K, V> LinkedHashMap<K, V>.putBounded(key: K, value: V) {
        put(key, value)
        if (size > MAX_CACHE_ENTRIES) {
            val eldest = keys.first()
            remove(eldest)
        }
    }

    /**
     * 加载缩略图。走 `GET /api/media/thumbnail/{id}?size=medium`。
     *
     * @param mediaId 后端媒体 id（网盘图片为去扩展名的文件名）
     * @return 解码后的 [ImageBitmap]；网络失败或解码失败返回 null
     */
    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        // 命中缓存直接返回（并提升为最近使用），避免滚动/回滑重复请求。
        val cached = cacheLock.withLock { thumbnailCache.access(mediaId) }
        if (cached != null) return cached
        return try {
            val bytes = MediaService.getThumbnail(mediaId, size = "medium")
            val decoded = decodeImageBitmap(bytes)
            if (decoded != null) cacheLock.withLock { thumbnailCache.putBounded(mediaId, decoded) }
            decoded
        } catch (e: Exception) {
            logger.error(TAG, "loadThumbnail failed for $mediaId: ${e.message}")
            null
        }
    }

    /**
     * 加载原图。走 `GET /api/media/stream/{id}`（后端直接以文件字节返回）。
     *
     * @param mediaId 后端媒体 id
     * @return 解码后的 [ImageBitmap]；失败返回 null
     */
    suspend fun loadFullImage(mediaId: String): ImageBitmap? {
        val cached = cacheLock.withLock { fullImageCache.access(mediaId) }
        if (cached != null) return cached
        return try {
            val bytes = MediaService.getMediaStream(mediaId)
            val decoded = decodeImageBitmap(bytes)
            if (decoded != null) cacheLock.withLock { fullImageCache.putBounded(mediaId, decoded) }
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
