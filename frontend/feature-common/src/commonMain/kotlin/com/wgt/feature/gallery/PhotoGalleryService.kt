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
}

/**
 * 期望的平台特定照片图库服务
 */
internal expect val photoGalleryService: PhotoGalleryService
