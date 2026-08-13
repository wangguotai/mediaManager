// PRD-v12 拍摄: Android 相机 actual。
// 用 ACTION_IMAGE_CAPTURE 拉起系统相机,结果写入 FileProvider Uri,回调字节到 onCaptured。
package com.wgt.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberCameraLauncher(onCaptured: (ByteArray?) -> Unit): CameraCaptureLauncher {
    val context = LocalContext.current

    var tmpFile by remember { mutableStateOf<File?>(null) }
    var outputUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && outputUri != null) {
            val bytes = try {
                context.contentResolver.openInputStream(outputUri!!)?.use { it.readBytes() }
            } catch (e: Exception) { null }
            onCaptured(bytes)
        } else {
            onCaptured(null)
        }
    }

    return remember {
        CameraCaptureLauncher(launch = {
            val dir = File(context.cacheDir, "share") // FileProvider 仅授权 cache/share
            dir.mkdirs()
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            tmpFile = file
            outputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            launcher.launch(outputUri!!)
        })
    }
}