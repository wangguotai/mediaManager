package com.wgt.media

import android.content.Intent
import androidx.core.content.FileProvider
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import java.io.File

/**
 * Android 平台分享实现 —— 用 Intent.ACTION_SEND 启动系统分享面板。
 *
 * 字节流先写入缓存文件，再通过 FileProvider 生成 content:// URI 传给 Intent，
 * 避免 FileUriExposedException（Android 7+ 禁止 file:// URI 跨进程传递）。
 *
 * 依赖：AndroidManifest.xml 中需声明 FileProvider（本项目已配置）。
 */
actual fun shareMedia(mediaBytes: ByteArray, filename: String, mimeType: String) {
    val context = AppContext.applicationContext

    // 写入缓存文件
    val shareDir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
    val shareFile = File(shareDir, filename)
    shareFile.writeBytes(mediaBytes)

    // 通过 FileProvider 获取 content URI
    val authority = "${context.packageName}.fileprovider"
    val contentUri = FileProvider.getUriForFile(context, authority, shareFile)

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // 启动 chooser Activity
    val chooserIntent = Intent.createChooser(shareIntent, "分享").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooserIntent)
}
