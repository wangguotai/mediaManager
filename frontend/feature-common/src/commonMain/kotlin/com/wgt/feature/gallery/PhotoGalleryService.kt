package com.wgt.feature.gallery

import com.wgt.architecture.manager.claim.feature
import com.wgt.architecture.manager.manager
import com.wgt.feature.permission.PermissionStatus
import com.wgt.feature.permission.PermissionType
import com.wgt.feature.permission.permission
import media.MediaMetadata

/**
 * 照片图库服务接口
 * 权限相关的功能通过 permissionService 实现
 * 包内访问权限，相关能力通过 GalleryFeature 对外暴露
 */
internal interface PhotoGalleryService {
    /**
     * 检查是否有照片图库访问权限
     */
    suspend fun hasPermission(): Boolean {
        return manager.feature.permission.allPermissionsGranted(listOf(PermissionType.PHOTO_LIBRARY))
    }
    
    /**
     * 请求照片图库访问权限
     */
    suspend fun requestPermission(): Boolean {
        val result = manager.feature.permission.requestPermission(PermissionType.PHOTO_LIBRARY)
        return result.status == PermissionStatus.GRANTED
    }
    
    /**
     * 从本地照片图库获取媒体列表
     */
    suspend fun getMediaFromGallery(): List<MediaMetadata>
    
    /**
     * 获取媒体文件的字节数据
     */
    suspend fun getMediaData(mediaId: String): ByteArray?
    
    /**
     * 获取Live Photo的视频数据（如果存在）
     */
    suspend fun getLivePhotoVideoData(mediaId: String): ByteArray?

    /**
     * 删除本地照片图库中的媒体文件。
     * Android 通过 MediaStore 删除，iOS 通过 PHAsset 删除。
     * @return 成功删除的数量
     */
    suspend fun deleteMedia(mediaIds: List<String>): Int

    /**
     * 把字节流写入系统相册（批量下载场景：云端字节 → 本地相册）。
     *
     * 按 mimeType 区分图片 / 视频：Android 走 MediaStore.Images 或 MediaStore.Video 的
     * content uri + OutputStream；iOS 走 PHAssetCreationRequest。调用方负责传入正确的
     * mimeType（图片通常 image/jpeg、image/png 等；视频通常 video/mp4）。
     *
     * @param data     原始字节流
     * @param filename 展示名（不含扩展名亦可，mimeType 决定实际写入位置）
     * @param mimeType 标准 MIME 类型，决定图片 / 视频集合
     * @return true 写入成功，false 失败
     */
    suspend fun saveMediaToGallery(data: ByteArray, filename: String, mimeType: String): Boolean
}

/**
 * 期望的平台特定照片图库服务
 */
internal expect val photoGalleryService: PhotoGalleryService
