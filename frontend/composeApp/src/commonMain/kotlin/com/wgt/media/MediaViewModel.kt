package com.wgt.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wgt.architecture.manager.claim.feature
import com.wgt.architecture.manager.manager
import com.wgt.feature.media.MediaService
import com.wgt.platform.logger.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata
import com.wgt.feature.gallery.gallery

private const val TAG = "MediaViewModel"

/**
 * 媒体管理视图模型
 */
class MediaViewModel {
    private val viewModelScope = CoroutineScope(Dispatchers.Default)
    private val galleryFeature: com.wgt.feature.gallery.GalleryFeature by lazy {
        manager.feature.gallery
    }

    // 媒体列表状态
    var mediaList by mutableStateOf<List<MediaMetadata>>(emptyList())
        private set

    // 选中的媒体ID列表
    val selectedMediaIds = mutableStateListOf<String>()

    // 加载状态
    var isLoading by mutableStateOf(false)
        private set

    // 错误状态
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // 上传状态
    var isUploading by mutableStateOf(false)
        private set

    // 删除状态
    var isDeleting by mutableStateOf(false)
        private set

    // 照片图库权限状态
    var hasGalleryPermission by mutableStateOf(false)
        private set

    // 照片图库加载状态
    var isGalleryLoading by mutableStateOf(false)
        private set

    // 缓存管理
    private var cachedLocalMedia: List<MediaMetadata>? = null
    private var cachedUploadedMedia: List<MediaMetadata>? = null


    init {
        logger.info(TAG, "init")
//        loadUploadedMediaList()
        loadMediaFromGallery()
    }

    /**
     * 从网络加载媒体列表
     */
    fun loadUploadedMediaList(forceRefresh: Boolean = false) {
        // 如果有缓存且不需要强制刷新，直接使用缓存
        if (!forceRefresh && cachedUploadedMedia != null) {
            mediaList = cachedUploadedMedia!!
            return
        }

        if (isLoading) return

        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val uploadedMedia = MediaService.getMediaList()
                cachedUploadedMedia = uploadedMedia
                mediaList = uploadedMedia
            } catch (e: Exception) {
                errorMessage = "加载媒体列表失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 从本地照片图库加载媒体
     */
    fun loadMediaFromGallery(forceRefresh: Boolean = false) {
        // 如果有缓存且不需要强制刷新，直接使用缓存
        if (!forceRefresh && cachedLocalMedia != null) {
            mediaList = cachedLocalMedia!!
            return
        }

        if (isGalleryLoading) return

        isGalleryLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                // 检查权限
                if (!galleryFeature.hasPermission()) {
                    val granted = galleryFeature.requestPermission()
                    if (!granted) {
                        errorMessage = "需要照片图库访问权限"
                        return@launch
                    }
                }

                hasGalleryPermission = true
                val galleryMedia = galleryFeature.getMediaFromGallery()
                cachedLocalMedia = galleryMedia
                mediaList = galleryMedia

            } catch (e: Exception) {
                errorMessage = "加载照片图库失败: ${e.message}"
            } finally {
                isGalleryLoading = false
            }
        }
    }

    /**
     * 上传选中的本地媒体到服务器
     */
    fun uploadSelectedLocalMedia() {
        if (selectedMediaIds.isEmpty() || isUploading) return

        isUploading = true

        viewModelScope.launch {
            try {
                var successCount = 0
                val totalCount = selectedMediaIds.size

                for (mediaId in selectedMediaIds) {
                    val mediaData = galleryFeature.getMediaData(mediaId)
                    if (mediaData != null) {
                        val media = mediaList.find { it.id == mediaId }
                        if (media != null) {
                            val success = MediaService.uploadMedia(
                                mediaData,
                                media.filename,
                                media.is_live_photo
                            )
                            if (success) {
                                successCount++
                            }
                        }
                    }
                }

                if (successCount > 0) {
                    // 上传成功后重新加载列表
                    loadUploadedMediaList()
                    errorMessage = "成功上传 $successCount/$totalCount 个文件"
                } else {
                    errorMessage = "上传失败"
                }

            } catch (e: Exception) {
                errorMessage = "上传本地媒体失败: ${e.message}"
            } finally {
                isUploading = false
            }
        }
    }

    /**
     * 切换媒体选中状态
     */
    fun toggleMediaSelection(mediaId: String) {
        if (selectedMediaIds.contains(mediaId)) {
            selectedMediaIds.remove(mediaId)
        } else {
            selectedMediaIds.add(mediaId)
        }
    }

    /**
     * 批量删除选中的媒体
     */
    fun deleteSelectedMedia() {
        if (selectedMediaIds.isEmpty() || isDeleting) return

        isDeleting = true

        viewModelScope.launch {
            try {
                val success = MediaService.deleteMedia(selectedMediaIds.toList())
                if (success) {
                    // 删除成功后更新列表
                    mediaList = mediaList.filter { it.id !in selectedMediaIds }
                    selectedMediaIds.clear()
                }
            } catch (e: Exception) {
                errorMessage = "删除媒体失败: ${e.message}"
            } finally {
                isDeleting = false
            }
        }
    }

    /**
     * 上传媒体文件
     */
    fun uploadMedia(fileData: ByteArray, filename: String, isLivePhoto: Boolean = false) {
        if (isUploading) return

        isUploading = true

        viewModelScope.launch {
            try {
                val success = MediaService.uploadMedia(fileData, filename, isLivePhoto)
                if (success) {
                    // 上传成功后重新加载列表
                    loadUploadedMediaList()
                }
            } catch (e: Exception) {
                errorMessage = "上传媒体失败: ${e.message}"
            } finally {
                isUploading = false
            }
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        errorMessage = null
    }

    /**
     * 获取选中的媒体数量
     */
    val selectedCount: Int
        get() = selectedMediaIds.size

    /**
     * 是否有选中的媒体
     */
    val hasSelection: Boolean
        get() = selectedMediaIds.isNotEmpty()

    /**
     * 是否有本地图库权限
     */
    val canAccessGallery: Boolean
        get() = hasGalleryPermission
}
