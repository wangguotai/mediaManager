package com.wgt.media

import android.content.Intent
import androidx.core.content.FileProvider
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import java.io.File

/**
 * Android 平台分享实现。
 *
 * 单个分享用 Intent.ACTION_SEND；批量分享用 ACTION_SEND_MULTIPLE + ArrayList<Uri>。
 * 字节流先写入缓存文件，再通过 FileProvider 生成 content:// URI 传给 Intent，
 * 避免 FileUriExposedException（Android 7+ 禁止 file:// URI 跨进程传递）。
 *
 * 依赖：AndroidManifest.xml 中需声明 FileProvider（本项目已配置）。
 */
actual fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String) {
    val context = AppContext.applicationContext

    val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    val shareFile = File(shareDir, filename)
    shareFile.writeBytes(mediaBytes)

    val authority = "${context.packageName}.fileprovider"
    val contentUri = FileProvider.getUriForFile(context, authority, shareFile)

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooserIntent = Intent.createChooser(shareIntent, "分享").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooserIntent)
}

/**
 * Android 批量分享：ACTION_SEND_MULTIPLE，一次系统分享面板处理所有文件。
 *
 * 混合类型时 type 用通配符，全图片用 image 类型，全视频用 video 类型。
 */
actual fun shareMediaBatch(items: List<ShareMediaItem>) {
    if (items.isEmpty()) return

    val context = AppContext.applicationContext
    val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    val authority = "${context.packageName}.fileprovider"

    val uris = items.map { item ->
        val file = File(shareDir, item.filename)
        file.writeBytes(item.bytes)
        FileProvider.getUriForFile(context, authority, file)
    }

    val allImages = items.all { it.mimeType.startsWith("image/") }
    val allVideos = items.all { it.mimeType.startsWith("video/") }
    val sharedType = when {
        allImages -> "image/*"
        allVideos -> "video/*"
        else -> "*/*"
    }

    val shareIntent = if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = sharedType
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = sharedType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }.apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooserIntent = Intent.createChooser(shareIntent, "分享").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooserIntent)
}
