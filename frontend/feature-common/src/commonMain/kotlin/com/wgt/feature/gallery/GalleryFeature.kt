package com.wgt.feature.gallery

import com.wgt.architecture.di.annotations.FeatureProvider
import com.wgt.architecture.feature.Feature
import media.MediaMetadata

@FeatureProvider
class GalleryFeature() : Feature() {
    override val name: String = "GalleryFeature"
    private val TAG = "GalleryFeature"

    /**
     * 检查是否有照片图库访问权限
     */
    suspend fun hasPermission(): Boolean = photoGalleryService.hasPermission()

    /**
     * 请求照片图库访问权限
     */
    suspend fun requestPermission(): Boolean = photoGalleryService.requestPermission()

    /**
     * 从本地照片图库获取媒体列表
     */
    suspend fun getMediaFromGallery(): List<MediaMetadata> = photoGalleryService.getMediaFromGallery()

    /**
     * 获取媒体文件的字节数据
     */
    suspend fun getMediaData(mediaId: String): ByteArray? = photoGalleryService.getMediaData(mediaId)

    /**
     * 获取Live Photo的视频数据（如果存在）
     */
    suspend fun getLivePhotoVideoData(mediaId: String): ByteArray? = photoGalleryService.getLivePhotoVideoData(mediaId)

    /**
     * 删除本地照片图库中的媒体文件
     */
    suspend fun deleteMedia(mediaIds: List<String>): Int = photoGalleryService.deleteMedia(mediaIds)
}

/**
 * 全局入口：提取本地 Live Photo 嵌入的视频数据。
 * 在 commonMain 声明，各平台实现委托给 PhotoGalleryService。
 */
suspend fun getLocalLivePhotoVideoData(mediaId: String): ByteArray? = photoGalleryService.getLivePhotoVideoData(mediaId)
