package com.wgt.feature.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import media.MediaMetadata
import media.MediaType
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.appendData
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeExact
import platform.Photos.PHPhotoLibrary
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal class IOSPhotoGalleryService : PhotoGalleryService {

    private val imageManager = PHImageManager.defaultManager()

    override suspend fun getMediaFromGallery(): List<MediaMetadata> = withContext(Dispatchers.Default) {
        // Request authorization if needed
        val authorized = requestPhotoLibraryAuthorization()
        if (!authorized) {
            return@withContext emptyList<MediaMetadata>()
        }

        val mediaList = mutableListOf<MediaMetadata>()

        val fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = listOf(
            NSSortDescriptor("creationDate", false)
        )

        // Fetch all images
        val imageFetchResult = PHAsset.fetchAssetsWithMediaType(
            PHAssetMediaTypeImage,
            fetchOptions
        )

        imageFetchResult.enumerateObjectsUsingBlock { asset, _, _ ->
            val phAsset = asset as PHAsset
            val mediaMetadata = convertPHAssetToMediaMetadata(phAsset)
            mediaList.add(mediaMetadata)
        }

        return@withContext mediaList
    }

    override suspend fun getMediaData(mediaId: String): ByteArray? = withContext(Dispatchers.Default) {
        val asset = fetchAssetById(mediaId)
        if (asset == null) {
            return@withContext null
        }

        getImageData(asset)
    }

    override suspend fun getLivePhotoVideoData(mediaId: String): ByteArray? = withContext(Dispatchers.Default) {
        val asset = fetchAssetById(mediaId)
        if (asset == null) {
            return@withContext null
        }

        getLivePhotoVideoDataFromAsset(asset)
    }

    override suspend fun deleteMedia(mediaIds: List<String>): Int = withContext(Dispatchers.Default) {
        // iOS: 需要 PHAssetDeleteRequest，暂返回 0
        0
    }

    /**
     * 把字节流写入系统相册 —— 用 PHPhotoLibrary.performChanges + PHAssetCreationRequest。
     *
     * iOS Photos API 的便利构造器只接受文件 URL（creationRequestForAssetFromVideoAtFileURL /
     * creationRequestForAssetFromImageAtFileURL），不接受裸 NSData，因此先用 posix C stdio
     * 把字节写到临时文件（绕开 K/N NSData 构造 API 的命名差异），再以 fileURL 创建资产，
     * 完成后删除临时文件。performChanges 是异步批处理，用 suspendCancellableCoroutine
     * 桥接协程：成功 resume(true)、失败 resume(false)。
     *
     * 先确保相册授权，未授权直接失败（不在此处弹框打断批量流程）。
     *
     * @return true 写入成功，false 失败（含未授权、平台拒绝等）
     */
    override suspend fun saveMediaToGallery(data: ByteArray, filename: String, mimeType: String): Boolean =
        withContext(Dispatchers.Default) {
            if (!requestPhotoLibraryAuthorization()) return@withContext false
            val isVideo = mimeType.startsWith("video/")

            // 写临时文件：Photos API 要求文件 URL。用 UUID 保证唯一，posix C stdio 写字节。
            val ext = if (isVideo) "mp4" else extensionForMime(mimeType)
            val tmpPath = "${NSTemporaryDirectory()}mm_download_${NSUUID().UUIDString}_${filename}.${ext}"
            if (!writeBytesToFile(data, tmpPath)) return@withContext false
            val fileURL = NSURL.fileURLWithPath(tmpPath)

            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                    if (isVideo) {
                        PHAssetCreationRequest.creationRequestForAssetFromVideoAtFileURL(fileURL)
                    } else {
                        PHAssetCreationRequest.creationRequestForAssetFromImageAtFileURL(fileURL)
                    }
                }) { success, _ ->
                    cont.resume(success)
                }
            }

            // 清理临时文件（无论成功与否，忽略删除失败）。
            remove(tmpPath)
            ok
        }

    /**
     * 用 posix fopen/fwrite/fclose 把字节写入指定路径。
     * 返回 true 成功；任一步失败返回 false（含 fp==null）。
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun writeBytesToFile(bytes: ByteArray, path: String): Boolean {
        val fp = fopen(path, "wb") ?: return false
        try {
            if (bytes.isEmpty()) return true
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.toULong(), bytes.size.toULong(), fp)
            }
            return written.toInt() == bytes.size
        } finally {
            fclose(fp)
        }
    }

    /** 按 mimeType 推断图片扩展名（视频固定 mp4，已在调用处处理）。 */
    private fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    private suspend fun requestPhotoLibraryAuthorization(): Boolean = suspendCancellableCoroutine { continuation ->
        val currentStatus = PHPhotoLibrary.authorizationStatus()

        when (currentStatus) {
            PHAuthorizationStatusAuthorized -> {
                continuation.resume(true)
            }
            PHAuthorizationStatusNotDetermined -> {
                PHPhotoLibrary.requestAuthorization { status ->
                    continuation.resume(status == PHAuthorizationStatusAuthorized)
                }
            }
            else -> {
                continuation.resume(false)
            }
        }
    }

    private fun convertPHAssetToMediaMetadata(asset: PHAsset): MediaMetadata {
        val localIdentifier = asset.localIdentifier
        val filename = getAssetFilename(asset)
        val creationDate = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L
        val modificationDate = asset.modificationDate?.timeIntervalSince1970?.toLong() ?: 0L

        // Check if this is a Live Photo by looking for paired video resource
        val isLivePhoto = checkIfLivePhoto(asset)

        // Determine MIME type based on file extension
        val mimeType = when {
            filename.endsWith(".heic", ignoreCase = true) -> "image/heic"
            filename.endsWith(".heif", ignoreCase = true) -> "image/heif"
            filename.endsWith(".png", ignoreCase = true) -> "image/png"
            filename.endsWith(".gif", ignoreCase = true) -> "image/gif"
            filename.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }

        // Get file size from PHAssetResource
        val fileSize = getAssetFileSize(asset)

        // Get image dimensions from PHAsset
        val width = asset.pixelWidth.toInt()
        val height = asset.pixelHeight.toInt()

        return MediaMetadata(
            id = localIdentifier,
            filename = filename,
            type = if (isLivePhoto) MediaType.LIVE_PHOTO else MediaType.IMAGE,
            size = fileSize,
            mime_type = mimeType,
            created_at = creationDate,
            updated_at = modificationDate,
            is_live_photo = isLivePhoto,
            live_photo_video_id = if (isLivePhoto) "${localIdentifier}_video" else "",
            width = width,
            height = height
        )
    }

    private fun getAssetFilename(asset: PHAsset): String {
        val resources = PHAssetResource.assetResourcesForAsset(asset)
        if (resources.isNotEmpty()) {
            val resource = resources.firstOrNull() as? PHAssetResource
            return resource?.originalFilename ?: "unknown"
        }
        return "unknown"
    }

    private fun getAssetFileSize(asset: PHAsset): Long {
        val resources = PHAssetResource.assetResourcesForAsset(asset)
        if (resources.isNotEmpty()) {
            val resource = resources.firstOrNull() as? PHAssetResource
            val size = resource?.valueForKey("fileSize") as? Number
            return size?.toLong() ?: 0L
        }
        return 0L
    }

    private fun checkIfLivePhoto(asset: PHAsset): Boolean {
        val resources = PHAssetResource.assetResourcesForAsset(asset)
        var hasPairedVideo = false
        resources.forEach { resource ->
            val res = resource as PHAssetResource
            if (res.type == PHAssetResourceTypePairedVideo) {
                hasPairedVideo = true
            }
        }
        return hasPairedVideo
    }

    private fun fetchAssetById(mediaId: String): PHAsset? {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(mediaId), null)
        return fetchResult.firstObject as? PHAsset
    }

    private fun nsDataToByteArray(data: NSData): ByteArray {
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        return data.bytes!!.readBytes(size)
    }

    private suspend fun getImageData(asset: PHAsset): ByteArray? = suspendCancellableCoroutine { continuation ->
        val options = PHImageRequestOptions()
        options.deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
        options.resizeMode = PHImageRequestOptionsResizeModeExact
        options.synchronous = false
        options.networkAccessAllowed = true

        imageManager.requestImageDataForAsset(
            asset,
            options
        ) { data, _, _, _ ->
            if (data != null) {
                val byteArray = nsDataToByteArray(data)
                continuation.resume(byteArray)
            } else {
                continuation.resume(null)
            }
        }
    }

    private suspend fun getLivePhotoVideoDataFromAsset(asset: PHAsset): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val resources = PHAssetResource.assetResourcesForAsset(asset)
            var videoResource: PHAssetResource? = null

            resources.forEach { resource ->
                val res = resource as PHAssetResource
                if (res.type == PHAssetResourceTypePairedVideo) {
                    videoResource = res
                }
            }

            if (videoResource == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val resourceManager = PHAssetResourceManager.defaultManager()
            val options = PHAssetResourceRequestOptions()
            options.setNetworkAccessAllowed(true)

            val mutableData = NSMutableData()

            resourceManager.requestDataForAssetResource(
                videoResource,
                options,
                { data ->
                    data?.let {
                        mutableData.appendData(it)
                    }
                },
                { error ->
                    if (error != null) {
                        continuation.resume(null)
                    } else {
                        continuation.resume(nsDataToByteArray(mutableData))
                    }
                }
            )
        }
}

internal actual val photoGalleryService: PhotoGalleryService = IOSPhotoGalleryService()
