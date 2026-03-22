package com.wgt.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wgt.feature.media.MediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import media.MediaMetadata

/**
 * Live 图处理组件
 */
object LivePhotoHandler {
    
    /**
     * 处理 Live 图点击事件
     */
    @Composable
    fun LivePhotoItem(
        media: MediaMetadata,
        viewModel: MediaViewModel,
        onMediaClick: (MediaMetadata) -> Unit,
        modifier: Modifier = Modifier
    ) {
        var showLivePhotoDialog by remember { mutableStateOf(false) }
        var videoUrl by remember { mutableStateOf<String?>(null) }
        
        Box(
            modifier = modifier
                .clickable { 
                    if (media.is_live_photo) {
                        // 如果是 Live 图，显示操作对话框
                        showLivePhotoDialog = true
                        // 异步获取视频 URL
                        CoroutineScope(Dispatchers.Default).launch {
                            videoUrl = MediaService.getLivePhotoVideoUrl(media.id)
                        }
                    } else {
                        // 普通图片直接处理点击
                        onMediaClick(media)
                    }
                }
        ) {
            // 这里可以放置实际的图片显示逻辑
            // 暂时使用占位符
            
            // Live 图操作对话框
            if (showLivePhotoDialog) {
                LivePhotoDialog(
                    media = media,
                    videoUrl = videoUrl,
                    onDismiss = { showLivePhotoDialog = false },
                    onSelect = { onMediaClick(media) }
                )
            }
        }
    }
    
    /**
     * Live 图操作对话框
     */
    @Composable
    private fun LivePhotoDialog(
        media: MediaMetadata,
        videoUrl: String?,
        onDismiss: () -> Unit,
        onSelect: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Live Photo 操作") },
            text = { 
                Text("这是一个 Live Photo，包含静态图片和动态视频。请选择操作：") 
            },
            confirmButton = {
                Button(onClick = { 
                    onSelect()
                    onDismiss()
                }) {
                    Text("选择图片")
                }
            },
            dismissButton = {
                if (videoUrl != null) {
                    Button(onClick = { 
                        // TODO: 播放视频
                        println("播放视频: $videoUrl")
                        onDismiss()
                    }) {
                        Text("播放视频")
                    }
                }
            }
        )
    }
    
    /**
     * 处理 Live 图上传
     */
    suspend fun handleLivePhotoUpload(
        imageData: ByteArray, 
        videoData: ByteArray?, 
        filename: String
    ): Boolean {
        // 先上传图片
        val imageSuccess = MediaService.uploadMedia(imageData, filename, isLivePhoto = videoData != null)
        
        if (imageSuccess && videoData != null) {
            // 如果图片上传成功且有视频数据，上传视频
            val videoFilename = filename.replaceAfterLast('.', "mov").replaceBeforeLast('.', "video_")
            return MediaService.uploadMedia(videoData, videoFilename, isLivePhoto = true)
        }
        
        return imageSuccess
    }
    
    /**
     * 处理 Live 图删除
     */
    suspend fun handleLivePhotoDelete(media: MediaMetadata): Boolean {
        val mediaIds = mutableListOf(media.id)
        
        // 如果是 Live 图，同时删除关联的视频
        if (media.is_live_photo && media.live_photo_video_id.isNotEmpty()) {
            mediaIds.add(media.live_photo_video_id)
        }
        
        return MediaService.deleteMedia(mediaIds)
    }
}
