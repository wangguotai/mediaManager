package com.wgt.feature.gallery

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.wgt.platform.applicationContext
import com.wgt.platform.AppContext
import com.wgt.platform.getCurrentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import media.MediaMetadata
import media.MediaType
import java.io.File

internal class AndroidPhotoGalleryService(private val context: Context) : PhotoGalleryService {

    override suspend fun getMediaFromGallery(): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaMetadata>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(mimeTypeColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                val dateModified = cursor.getLong(dateModifiedColumn) * 1000
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)

                val isLivePhoto = detectLivePhoto(id, name)

                val mediaMetadata = MediaMetadata(
                    id = id.toString(),
                    filename = name ?: "unknown",
                    type = if (isLivePhoto) MediaType.LIVE_PHOTO else MediaType.IMAGE,
                    size = size,
                    mime_type = mimeType ?: "image/jpeg",
                    created_at = dateAdded,
                    updated_at = dateModified,
                    is_live_photo = isLivePhoto,
                    live_photo_video_id = if (isLivePhoto) "local_live_$id" else "",
                    width = width,
                    height = height
                )

                mediaList.add(mediaMetadata)
            }
        }

        return@withContext mediaList
    }

    /**
     * 检测是否为 Live Photo (Motion Photo)。
     *
     * 支持两种格式:
     * 1. MVIMG_*.jpg — 小米/Google Motion Photo，视频嵌在 JPEG 末尾，XMP 元数据标记
     * 2. 任何包含 GCamera:MicroVideo XMP 元数据的 JPEG
     */
    private fun detectLivePhoto(mediaId: Long, filename: String?): Boolean {
        // Fast path: MVIMG prefix is the common Xiaomi Motion Photo marker
        if (filename != null && filename.startsWith("MVIMG_")) {
            return true
        }
        // Slow path: read XMP metadata to check for MicroVideo
        return try {
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId
            )
            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                val header = ByteArray(8192)
                val read = stream.read(header)
                if (read > 0) {
                    val headerStr = String(header, 0, read, Charsets.ISO_8859_1)
                    headerStr.contains("GCamera:MicroVideo=\"1\"") ||
                        headerStr.contains("MicroVideo=\"1\"")
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 JPEG 文件中提取嵌入的 Motion Photo 视频数据。
     *
     * Google/Xiaomi Motion Photo 格式: JPEG 正常图像数据 + 附加的 MP4 视频数据。
     * 视频以标准 MP4 box 结构(ftyp→moov→…→mdat)嵌在 JPEG 尾部。
     *
     * 实现策略——反向搜索 ftyp box(不依赖 XMP MicroVideoOffset 的歧义语义):
     * 各厂商(GCamera/Xiaomi/Honor 等)的 MicroVideoOffset 含义不一致:
     * 有的表示从头算的绝对偏移，有的(如小米此机型)表示从文件末尾反向算的视频长度，
     * 直接使用极易多截/少截出垃圾前缀。改为从文件尾部向前找 MP4 ftyp box，
     * 并验证自此点(含前 4 字节 size 字段)解析 box 链能连续到达文件末尾，从而精确定位
     * 完整 MP4 起点。该方法对各家 Live Photo 格式均鲁棒。
     *
     * @param mediaId MediaStore 图片 ID
     * @return 提取的 MP4 视频字节，null 表示不是 Live Photo 或提取失败
     */
    override suspend fun getLivePhotoVideoData(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val id = mediaId.toLong()
            val filePath = queryDataPath(id) ?: return@withContext null
            val file = File(filePath)
            if (!file.exists()) return@withContext null

            // 读取整个文件(Motion Photo 视频部分通常占后半，整体读入便于 box 链验证)。
            val data = file.readBytes()
            val mp4Start = findMp4StartByReverseSearch(data)
            if (mp4Start < 0) return@withContext null
            data.copyOfRange(mp4Start, data.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过 MediaStore.DATA 查询给定 mediaId 的原始文件路径。
     * 必须用文件路径直接读取：ContentResolver.openInputStream 返回的是压缩/裁剪版本，
     * 对于 Motion Photo 会丢失尾部嵌入的视频及 XMP 微视频标记。
     */
    private fun queryDataPath(mediaId: Long): String? {
        return try {
            val dataColumn = arrayOf(MediaStore.Images.Media.DATA)
            val selection = "${MediaStore.Images.Media._ID} = ?"
            val selectionArgs = arrayOf(mediaId.toString())
            var filePath: String? = null
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                dataColumn, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    filePath = cursor.getString(0)
                }
            }
            filePath
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMediaData(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                mediaId.toLong()
            )

            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 删除本地图片。Android 10+ scoped storage 下，非 owner 应用直接
     * [ContentResolver.delete] 会抛 [RecoverableSecurityException]（API 29）或需通过
     * [MediaStore.createDeleteRequest]（API 30+）发起可恢复删除请求，由用户在系统弹窗授权。
     *
     * 策略按 API 分流：
     * - API 30+：先收集所有目标 uri，对其中 [ContentResolver.delete] 立即成功的直接删掉；
     *   剩余需授权的用 [MediaStore.createDeleteRequest] 一次性批量发起授权请求，用户同意后
     *   由系统删除。授权通过即视为删除成功。
     * - API 29：逐个 delete，捕获 [RecoverableSecurityException]，用其 userAction.intentSender
     *   发起授权，授权后再 delete 重试。
     * - API ≤28：scoped storage 之前，直接 delete 即可。
     *
     * 授权交互通过命令式 ActivityResultLauncher（StartIntentSenderForResult）发起并 suspend 等待
     * 结果，对 commonMain/ViewModel 透明。需当前 Activity 为 ComponentActivity（MainActivity 满足）。
     */
    override suspend fun deleteMedia(mediaIds: List<String>): Int = withContext(Dispatchers.IO) {
        if (mediaIds.isEmpty()) return@withContext 0
        var deleted = 0
        // 解析所有有效 mediaId → uri（跳过非法 id）
        val uris = mediaListToUris(mediaIds)
        if (uris.isEmpty()) return@withContext 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：先删可直接删的，剩余用 createDeleteRequest 批量授权。
            val pending = mutableListOf<Uri>()
            for (uri in uris) {
                val immediate = tryDeleteUri(uri)
                if (immediate) {
                    deleted++
                } else {
                    pending.add(uri)
                }
            }
            if (pending.isNotEmpty()) {
                val granted = requestDeleteConsent(pending)
                if (granted) deleted += pending.size
            }
        } else {
            // API 29 及以下：逐个处理，API 29 非授权项靠 RecoverableSecurityException 拿 IntentSender。
            for (uri in uris) {
                deleted += deleteWithRecoverableConsent(uri)
            }
        }
        deleted
    }

    /** 把 mediaId 列表转为 MediaStore content uri 列表，跳过无法解析为 Long 的 id。 */
    private fun mediaListToUris(mediaIds: List<String>): List<Uri> {
        return mediaIds.mapNotNull { id ->
            val longId = id.toLongOrNull() ?: return@mapNotNull null
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, longId)
        }
    }

    /** 直接尝试删除单个 uri，成功返回 true（含异常视为不可直接删，返回 false）。 */
    private fun tryDeleteUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * API 29 路径：尝试 delete；若抛 [RecoverableSecurityException]，取其 IntentSender
     * 发起授权，用户同意后重试 delete。其它异常或用户拒绝返回 0。
     *
     * 注意：[RecoverableSecurityException] 仅 API 29+ 存在，此处用通用异常捕获 + instanceof
     * 判断(而非直接 catch 该类型)，避免在 API <29 设备上因异常类不存在导致类加载问题。
     */
    private suspend fun deleteWithRecoverableConsent(uri: Uri): Int {
        return try {
            if (context.contentResolver.delete(uri, null, null) > 0) 1 else 0
        } catch (e: Exception) {
            // API 29(Q) 对非 owner 媒体抛 RecoverableSecurityException，含授权 IntentSender。
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val intentSender = e.userAction.actionIntent.intentSender
                if (requestIntentSender(intentSender) && tryDeleteUri(uri)) 1 else 0
            } else {
                0
            }
        }
    }

    /**
     * API 30+ 路径：用 [MediaStore.createDeleteRequest] 对一组 uri 生成批量删除授权请求，
     * 启动系统弹窗，suspend 等待用户结果。返回是否授权成功。
     */
    private suspend fun requestDeleteConsent(uris: List<Uri>): Boolean {
        val intentSender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        return requestIntentSender(intentSender)
    }

    /**
     * 命令式注册 StartIntentSenderForResult launcher，启动 [intentSender] 并 suspend 等待结果。
     * 复用 PermissionService 的 ActivityResultRegistry 模式，无需 UI 持有 launcher。
     * 当前 Activity 必须是 ComponentActivity；无可用 Activity 时返回 false。
     */
    private suspend fun requestIntentSender(intentSender: android.content.IntentSender): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val activity = AppContext.getCurrentActivity()
            if (activity == null || activity !is ComponentActivity) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            val launcher = activity.activityResultRegistry.register(
                "delete_consent_${System.currentTimeMillis()}",
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                continuation.resume(result.resultCode == Activity.RESULT_OK)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    /**
     * 从 JPEG 数据中反向搜索并定位嵌入 MP4 的真实起点。
     *
     * 算法：从文件尾部向前查找所有 "ftyp" 标记，对每个候选(其 box 起点 = 标记位置 - 4，
     * 因为 ftyp 字符串前面是 4 字节 box size 字段)尝试解析完整 box 链；若链能连续
     * 推进到数据末尾，则该候选即 MP4 真实起点。从尾部开始找首个通过验证的候选，
     * 避开 JPEG 图像数据中偶发的 ftyp 字节误匹配。
     *
     * box size 处理：size==0 表示该 box 延伸至文件末尾；size==1 表示使用紧随其后的
     * 8 字节 64 位扩展 size(部分厂商 mdat 用此格式)。这两类均正确解析。
     *
     * @return MP4 起点(含首个 box 的 4 字节 size 字段)在 data 中的偏移；未找到返回 -1
     */
    private fun findMp4StartByReverseSearch(data: ByteArray): Int {
        val ftyp = byteArrayOf(0x66, 0x74, 0x79, 0x70) // "ftyp"
        var searchFrom = data.size
        while (searchFrom > 0) {
            val ftypPos = indexOfFtyp(data, ftyp, searchFrom)
            if (ftypPos < 0) break
            searchFrom = ftypPos // 下一轮继续向前找更早的候选
            val boxStart = ftypPos - 4
            if (boxStart < 0) continue
            if (validateMp4BoxChain(data, boxStart)) {
                return boxStart
            }
        }
        return -1
    }

    /** 在 [0, until) 范围内从后向前查找 ftyp 标记，未找到返回 -1。 */
    private fun indexOfFtyp(data: ByteArray, ftyp: ByteArray, until: Int): Int {
        var i = until - 4
        while (i >= 0) {
            if (data[i] == ftyp[0] && data[i + 1] == ftyp[1] &&
                data[i + 2] == ftyp[2] && data[i + 3] == ftyp[3]
            ) {
                return i
            }
            i--
        }
        return -1
    }

    /**
     * 验证 [start] 处是否为合法 MP4 起点：从此处按 box 链解析，每个 box 的 size 合法，
     * 链能连续推进到数据末尾(允许中间若干个 box，最多 64 个以防病态数据)。
     */
    private fun validateMp4BoxChain(data: ByteArray, start: Int): Boolean {
        val n = data.size
        var off = start
        var boxes = 0
        // 首个 box 必须是 ftyp ('f','t','y','p')
        if (off + 8 > n) return false
        if (data[off + 4] != 0x66.toByte() || data[off + 5] != 0x74.toByte() ||
            data[off + 6] != 0x79.toByte() || data[off + 7] != 0x70.toByte()
        ) return false
        while (off < n) {
            if (off + 8 > n) return false
            val size = ((data[off].toInt() and 0xFF) shl 24) or
                ((data[off + 1].toInt() and 0xFF) shl 16) or
                ((data[off + 2].toInt() and 0xFF) shl 8) or
                (data[off + 3].toInt() and 0xFF)
            var realSize = size.toLong()
            if (size == 1) {
                // 64 位扩展 size
                if (off + 16 > n) return false
                realSize = 0
                for (k in 8 until 16) {
                    realSize = (realSize shl 8) or (data[off + k].toLong() and 0xFF)
                }
            } else if (size == 0) {
                // box 延伸至文件末尾
                realSize = (n - off).toLong()
            }
            if (realSize < 8) return false
            if (off + realSize > n) return false
            off += realSize.toInt()
            boxes++
            if (boxes > 64) return false
        }
        return off == n
    }
}

internal actual val photoGalleryService: PhotoGalleryService
    get() = AndroidPhotoGalleryService(AppContext.applicationContext)
