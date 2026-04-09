package com.wgt.feature.gallery

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import media.MediaMetadata
import media.MediaType
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.NSSortDescriptor
import platform.Foundation.appendData
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Photos.PHAsset
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
