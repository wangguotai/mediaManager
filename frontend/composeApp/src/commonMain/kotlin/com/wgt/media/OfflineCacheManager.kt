package com.wgt.media

import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.OfflineMediaItem
import com.wgt.platform.logger.logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val TAG = "OfflineCacheManager"

/**
 * 离线缩略图磁盘缓存管理 —— 平台无关的预缓存 / 离线读取 / 网络探测门面。
 *
 * 触发链：网络可用时上层调用 [prefetchOfflineThumbnails]，对 [MediaService.getOfflineManifest]
 * 返回的清单里每一项，按 [getThumbnail] 拉字节并写入 `{cacheDir}/offline_thumb_{id}.jpg`；
 * 离线或显示时，[getCachedThumbnailPath] 直接给出磁盘路径供 UI 加载本地图。
 *
 * 网络探测 [isOfflineMode] 走 [platformNetworkAvailable]：Android 用
 * `ConnectivityManager.getNetworkCapabilities(...)`，iOS 简单返回 true（详见各 actual 注释）。
 *
 * 平台差异收敛点：
 * - [getOfflineCacheDir]：Android=`context.cacheDir`，iOS=`NSTemporaryDirectory()`
 * - [platformWriteBytes] / [platformFileExists]：包装各平台文件 API，避免 common 引
 *   `java.io.File` 仅在 JVM 可用。
 *
 * 并发：[prefetchOfflineThumbnails] 内部对清单做并发下载（`async` + `awaitAll`），每项互不阻塞，
 * 单项失败仅记日志不抛 —— 任一缩略图失败不影响其余项的预缓存进度。
 */
object OfflineCacheManager {

    /** 缩略图缓存文件名前缀：`offline_thumb_{id}.jpg`。统一小写 id 防大小写文件系统差异。 */
    private const val FILE_PREFIX = "offline_thumb_"
    private const val FILE_SUFFIX = ".jpg"

    /**
     * 并发预缓存上限：8 项。
     *
     * 太大（如 32）会并发打满后端 HTTP 连接池或触发限流；太小（如 1）则清单 N 项需 N 个串行
     * 往返。8 在常见清单规模（几十到上百项）下兼顾吞吐与对后端的压力，与
     * [BackendImageLoader] 内存缓存 60 项的量级对齐，不致预缓存远超最近访问窗口。
     */
    private const val MAX_CONCURRENT_DOWNLOAD = 8

    /**
     * 预缓存给定离线清单的所有缩略图到磁盘。
     *
     * 对每项检查磁盘存在性，已缓存则跳过 —— 避免重复下载与无谓 QPS。未缓存项通过
     * [MediaService.getThumbnail] 取字节后调用 [platformWriteBytes] 落盘。
     * 单项任何异常（网络失败、IO 错误）仅记 error 日志并被 [async] 吞掉，
     * 不影响其他项；整体方法**永不抛异常**，便于在 UI 启动 / 后台刷新中无条件调用。
     *
     * @param items 来自 [MediaService.getOfflineManifest] 的清单；空时不做任何 I/O
     */
    suspend fun prefetchOfflineThumbnails(items: List<OfflineMediaItem>) {
        if (items.isEmpty()) return
        val cacheDir = getOfflineCacheDir()
        logger.info(TAG, "prefetch start: ${items.size} items, dir=$cacheDir")

        coroutineScope {
            items
                // 仅对"尚未缓存"的项发起下载，已存在的跳过以省 QPS 与流量。
                .filter { item -> getCachedThumbnailPath(item.id) == null }
                .chunked(MAX_CONCURRENT_DOWNLOAD)
                .forEach { batch ->
                    batch.map { item ->
                        async {
                            downloadOne(item)
                        }
                    }.awaitAll()
                }
        }
        logger.info(TAG, "prefetch done")
    }

    /**
     * 下载单个缩略图并落盘。失败仅记日志（见 [prefetchOfflineThumbnails] 注释）。
     * 在调用方协程上下文执行；HTTP 由 [MediaService.getThumbnail] 自管。
     */
    private suspend fun downloadOne(item: OfflineMediaItem) {
        try {
            // 调 BackendImageLoader 已用的 MediaService.getThumbnail（小尺寸），保持与在线路径一致。
            val bytes = MediaService.getThumbnail(item.id, size = "small")
            if (bytes == null || bytes.isEmpty()) {
                logger.info(TAG, "prefetch skip (empty bytes) id=${item.id}")
                return
            }
            val target = fileForId(item.id)
            platformWriteBytes(target, bytes)
            logger.info(TAG, "prefetch ok id=${item.id} bytes=${bytes.size} -> $target")
        } catch (e: Exception) {
            logger.error(TAG, "prefetch failed id=${item.id}: ${e::class.simpleName} ${e.message}")
            // 不抛：见 prefetchOfflineThumbnails 注释
        }
    }

    /**
     * 取离线缓存缩略图的本地路径，未命中返回 null。
     *
     * 调用方按路径用本地图片加载器（如平台 ImageDecoder）显图；离线模式下优先于
     * [BackendImageLoader.loadThumbnail]（后者走 HTTP 会失败）。
     *
     * 不会发起任何网络请求，仅基于 [getCachedThumbnailPath] 的平台文件存在性检查。
     *
     * @param mediaId 媒体 ID（与 [MediaService.getThumbnail] 入参一致）
     * @return 缓存文件绝对路径；缓存不存在则 null
     */
    fun getCachedThumbnailPath(mediaId: String): String? {
        val path = fileForId(mediaId)
        return if (platformFileExists(path)) path else null
    }

    /**
     * 当前是否处于\"离线模式\" —— 即网络不可用、应优先走本地缓存。
     *
     * 由平台 [platformNetworkAvailable] 实现：Android 用 ConnectivityManager；
     * iOS 当前简单返回 true（无可靠同步 API，且离线时若 [getCachedThumbnailPath] 命中仍可显图）。
     * UI 不应阻塞在 [isOfflineMode]，可作\"是否尝试预缓存\"/\"是否显示离线提示\"的启发式依据。
     */
    fun isOfflineMode(): Boolean = !platformNetworkAvailable()

    /**
     * 构造某 mediaId 对应的缓存文件绝对路径（不论是否已存在）。
     * 仅供本类内部及 expect 实现复用；外部用 [getCachedThumbnailPath] 拿\"存在才返回\"版本。
     */
    private fun fileForId(mediaId: String): String {
        val dir = getOfflineCacheDir().trimEnd('/', '\\')
        // id 含路径分隔符时仅取文件名部分，避免越出 cacheDir。
        val safeId = mediaId.substringAfterLast('/').substringAfterLast('\\')
        return "$dir/$FILE_PREFIX${safeId}$FILE_SUFFIX"
    }
}

/**
 * 平台返回用于离线缩略图缓存的目录绝对路径。
 *
 * Android: `context.cacheDir`（App 私有缓存目录，系统可在低存储时清理，无需权限）。
 * iOS: `NSTemporaryDirectory()`（App 沙箱临时目录，系统在低存储时可清理）。
 * 任一平台实现应保证目录存在（按需 mkdirs）。
 */
expect fun getOfflineCacheDir(): String

/**
 * 平台检查路径是否为已存在的文件。common 不能依赖 `java.io.File`，
 * 故收敛为 expect；缓存命中判断与 prefetch 跳过判断都依赖它。
 */
expect fun platformFileExists(path: String): Boolean

/**
 * 平台将字节数组写入指定路径（覆盖写）。common 不能依赖 `java.io.File`，
 * 故收敛为 expect。实现需保证父目录存在。
 */
expect fun platformWriteBytes(path: String, bytes: ByteArray)

/**
 * 平台网络可达性探测。Android 用 ConnectivityManager 检查是否有可用网络；
 * iOS 当前简单返回 true（详见各 actual 注释与降级策略）。
 */
expect fun platformNetworkAvailable(): Boolean
