@file:OptIn(DelicateCoroutinesApi::class)

package com.wgt.feature.gallery

import android.app.Activity
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.platform.getCurrentActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 端 [requestMediaDeletion] 实现。
 *
 * - API ≥29（scoped storage）：用 [MediaStore.createDeleteRequest] 对全部目标 uri 生成一个
 *   批量删除 PendingIntent，借当前 Activity（须为 [ComponentActivity]）命令式注册
 *   StartIntentSenderForResult launcher 启动系统授权弹窗。用户同意后系统删除全部授权项，
 *   回调 [onResult] 传入选入数量；无可用 Activity 或用户拒绝时回调 0。
 * - API <29：scoped storage 之前，直接 [android.content.ContentResolver.delete] 逐个删除，
 *   完成后回调实际删除数量。
 *
 * 命令式 launcher 注册复用 ActivityResultRegistry 模式，无需 UI 持有 launcher，
 * 对 ViewModel 透明（MainActivity 为 ComponentActivity，满足要求）。
 */
actual fun requestMediaDeletion(mediaIds: List<String>, onResult: (Int) -> Unit) {
    val uris = mediaIds.mapNotNull { id ->
        val longId = id.toLongOrNull() ?: return@mapNotNull null
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, longId)
    }
    if (uris.isEmpty()) {
        onResult(0)
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+：createDeleteRequest 在 API 30+ 可用；API 29(Q) 无此 API，需走 RecoverableSecurityException。
        // createDeleteRequest 自 API 30(R) 起提供；对 API 29 退化为直接尝试 delete（大多数 owner 媒体可删）。
        requestDeleteViaSystem(uris, onResult)
    } else {
        // API ≤28：直接 delete。
        GlobalScope.launch(Dispatchers.Main) {
            val deleted = withContext(Dispatchers.IO) { deleteUrisDirectly(uris) }
            onResult(deleted)
        }
    }
}

/**
 * API 30+：用 [MediaStore.createDeleteRequest] 发起系统批量删除授权弹窗。
 * API 29：无 createDeleteRequest，直接尝试 delete（owner 项可删，非 owner 静默失败计入 0）。
 */
private fun requestDeleteViaSystem(uris: List<Uri>, onResult: (Int) -> Unit) {
    val activity = AppContext.getCurrentActivity()
    if (activity == null || activity !is ComponentActivity) {
        // 无可用 Activity，无法拉起授权弹窗；退化为直接尝试 delete（覆盖 owner 项）。
        GlobalScope.launch(Dispatchers.Main) {
            val deleted = withContext(Dispatchers.IO) { deleteUrisDirectly(uris) }
            onResult(deleted)
        }
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intentSender = MediaStore.createDeleteRequest(
            AppContext.applicationContext.contentResolver, uris
        ).intentSender
        startIntentSenderForResult(activity, intentSender) { granted ->
            onResult(if (granted) uris.size else 0)
        }
    } else {
        // API 29：无 createDeleteRequest，直接 delete（非 owner 抛 RecoverableSecurityException 视为失败）。
        GlobalScope.launch(Dispatchers.Main) {
            val deleted = withContext(Dispatchers.IO) { deleteUrisDirectly(uris) }
            onResult(deleted)
        }
    }
}

/**
 * 命令式注册 StartIntentSenderForResult launcher，启动 [intentSender] 并把授权结果
 * （true=用户同意）回传 [onResult]。launcher 在回调后自动 unregister。
 */
private fun startIntentSenderForResult(
    activity: ComponentActivity,
    intentSender: IntentSender,
    onResult: (granted: Boolean) -> Unit
) {
    val launcher = activity.activityResultRegistry.register(
        "media_delete_consent_${System.currentTimeMillis()}",
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onResult(result.resultCode == Activity.RESULT_OK)
    }
    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
}

/** 直接对一组 uri 调 [android.content.ContentResolver.delete]，返回成功删除数量。 */
private fun deleteUrisDirectly(uris: List<Uri>): Int {
    var deleted = 0
    val resolver = AppContext.applicationContext.contentResolver
    for (uri in uris) {
        try {
            if (resolver.delete(uri, null, null) > 0) deleted++
        } catch (e: Exception) {
            // 非 owner 媒体在 scoped storage 下会抛异常，计入失败（不中断其余项）。
        }
    }
    return deleted
}
