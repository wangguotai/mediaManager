package com.wgt.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.wgt.platform.logger.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGSize
import platform.Foundation.NSData
import platform.Foundation.getBytes
import platform.Photos.*
import platform.UIKit.UIImage
import kotlin.coroutines.resume

/**
 * iOS 平台缩略图加载器
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
    return try {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(mediaId), null)
        val asset = fetchResult.firstObject as? PHAsset ?: return null

        val imageOptions = PHImageRequestOptions()
        imageOptions.deliveryMode = PHImageRequestOptionsDeliveryModeOpportunistic
        imageOptions.resizeMode = PHImageRequestOptionsResizeModeFast
        imageOptions.synchronous = true
        imageOptions.networkAccessAllowed = true

        var result: platform.UIKit.UIImage? = null
        val targetSize = cValue<CGSize> {
            this.width = 256.0
            this.height = 256.0
        }

        PHImageManager.defaultManager().requestImageForAsset(
            asset,
            targetSize,
            0L,
            imageOptions
        ) { image, _ ->
            result = image
        }

        result?.let { uiImage ->
            // 将 UIImage 转换为 PNG Data，然后转换为 ImageBitmap
            val pngData = platform.UIKit.UIImagePNGRepresentation(uiImage)
            if (pngData != null) {
                val byteArray = nsDataToByteArray(pngData)
                org.jetbrains.skia.Image.makeFromEncoded(byteArray).toComposeImageBitmap()
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nsDataToByteArray(data: NSData): ByteArray {
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned ->
            data.getBytes(pinned.addressOf(0), size.toULong())
        }
    }
}

/**
 * iOS 平台完整图片加载器 - 优化版本
 * 使用 requestImageDataForAsset 直接获取原始图像数据，避免 UIImage 转换开销
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadFullImage(mediaId: String): ImageBitmap? {
    return try {
        val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(mediaId), null)
        val asset = fetchResult.firstObject as? PHAsset ?: return null

        // 使用 suspendCancellableCoroutine 正确处理异步回调
        val byteArray = suspendCancellableCoroutine<ByteArray?> { continuation ->
            val imageOptions = PHImageRequestOptions().apply {
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
                resizeMode = PHImageRequestOptionsResizeModeNone
                synchronous = true  // 使用同步模式确保数据在回调中有效
                networkAccessAllowed = true
            }

            PHImageManager.defaultManager().requestImageDataForAsset(
                asset,
                imageOptions
            ) { data, dataUTI, _, _ ->
                // 在回调中立即复制数据到 ByteArray，避免内存释放问题
                val bytes = data?.let { nsData ->
                    // 检查是否为 HEIC 格式
                    val isHEIC = dataUTI?.let { uti ->
                        val utiString = uti.toString()
                        utiString.equals("public.heic", ignoreCase = true) ||
                        utiString.equals("public.heif", ignoreCase = true)
                    } ?: false
                    if(isHEIC) {
                        try {
                            val uiImage = UIImage(nsData)
                            val jpegData = platform.UIKit.UIImagePNGRepresentation(uiImage)
                            jpegData?.let { nsDataToByteArray(it) }
                        } catch (e: Exception) {
                            logger.error("ThumbnailLoader", "Failed to convert HEIC to JPEG: ${e.message}")
//                            // 尝试直接使用原始数据
                            nsDataToByteArray(nsData)
                        }
                    } else {
                        // 非 HEIC 格式直接使用原始数据
                        nsDataToByteArray(nsData)
                    }
                }
                continuation.resume(bytes)
            }
        }

        byteArray?.let {
            // 直接使用原始数据解码，保持最高质量且速度最快
            org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        logger.error("ThumbnailLoader", "Failed to load full image: ${e.message}")
        e.printStackTrace()
        null
    }
}
