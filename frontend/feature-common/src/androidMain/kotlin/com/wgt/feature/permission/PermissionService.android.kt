package com.wgt.feature.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.platform.getCurrentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android平台权限服务实现
 * 直接使用Android API请求权限
 */
class AndroidPermissionService(private val context: Context) : PermissionService {

    private val permissionStatusFlows = mutableMapOf<PermissionType, MutableStateFlow<PermissionStatus>>()

    override suspend fun checkPermission(permission: PermissionType): PermissionStatus {
        val androidPermission = permission.toAndroidPermission()
        val result = ContextCompat.checkSelfPermission(context, androidPermission)
        return when (result) {
            PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED
            else -> PermissionStatus.DENIED
        }
    }

    override suspend fun checkPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionStatus> {
        return permissions.associateWith { checkPermission(it) }
    }

    override suspend fun requestPermission(permission: PermissionType): PermissionResult {
        val currentStatus = checkPermission(permission)
        
        // 如果已经授予权限，直接返回
        if (currentStatus == PermissionStatus.GRANTED) {
            return PermissionResult(permission, PermissionStatus.GRANTED, false)
        }

        val androidPermission = permission.toAndroidPermission()
        val activity = AppContext.getCurrentActivity()
        
        if (activity == null) {
            // 没有活跃的Activity，返回需要请求的状态
            return PermissionResult(permission, PermissionStatus.DENIED, false)
        }

        // 请求权限
        val granted = requestPermissionInternal(activity, androidPermission)
        
        // 更新状态流
        val newStatus = if (granted) {
            PermissionStatus.GRANTED
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission)
            if (!shouldShowRationale) {
                // 用户选择了"不再询问"，权限被永久拒绝
                PermissionStatus.DENIED
            } else {
                PermissionStatus.DENIED
            }
        }
        
        permissionStatusFlows[permission]?.value = newStatus
        
        return PermissionResult(permission, newStatus, false)
    }
    
    /**
     * 内部方法：使用Activity Result API请求权限
     */
    private suspend fun requestPermissionInternal(activity: Activity, permission: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (activity !is ComponentActivity) {
                // 如果不是ComponentActivity，无法使用Activity Result API
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val launcher = activity.activityResultRegistry.register(
                "permission_request_${System.currentTimeMillis()}",
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                continuation.resume(granted)
            }

            continuation.invokeOnCancellation {
                launcher.unregister()
            }

            launcher.launch(permission)
        }
    }

    override suspend fun requestPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionResult> {
        val activity = AppContext.getCurrentActivity()
        
        if (activity == null) {
            // 没有活跃的Activity，返回所有权限都需要请求的状态
            return permissions.associateWith { 
                PermissionResult(it, PermissionStatus.DENIED, false) 
            }
        }

        // 转换为Android权限字符串
        val androidPermissions = permissions.map { it.toAndroidPermission() }.toTypedArray()
        
        // 请求权限
        val results = requestPermissionsInternal(activity, androidPermissions)
        
        // 构建结果映射
        return permissions.associateWith { permission ->
            val androidPermission = permission.toAndroidPermission()
            val granted = results[androidPermission] ?: false
            val status = if (granted) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
            
            // 更新状态流
            permissionStatusFlows[permission]?.value = status
            
            PermissionResult(permission, status, false)
        }
    }
    
    /**
     * 内部方法：使用Activity Result API请求多个权限
     */
    private suspend fun requestPermissionsInternal(
        activity: Activity, 
        permissions: Array<String>
    ): Map<String, Boolean> {
        return suspendCancellableCoroutine { continuation ->
            if (activity !is ComponentActivity) {
                // 如果不是ComponentActivity，无法使用Activity Result API
                continuation.resume(permissions.associateWith { false })
                return@suspendCancellableCoroutine
            }

            val launcher = activity.activityResultRegistry.register(
                "permissions_request_${System.currentTimeMillis()}",
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                continuation.resume(results)
            }

            continuation.invokeOnCancellation {
                launcher.unregister()
            }

            launcher.launch(permissions)
        }
    }

    override fun observePermission(permission: PermissionType): Flow<PermissionStatus> {
        return permissionStatusFlows.getOrPut(permission) {
            MutableStateFlow(PermissionStatus.UNKNOWN)
        }
    }

    override suspend fun shouldShowRationale(permission: PermissionType): Boolean {
        val androidPermission = permission.toAndroidPermission()
        
        // 检查权限是否已经被授予
        val isGranted = ContextCompat.checkSelfPermission(context, androidPermission) ==
                PackageManager.PERMISSION_GRANTED

        if (isGranted) {
            return false // 权限已授予，不需要显示解释
        }

        // 获取当前Activity来检查是否应该显示解释
        val activity = AppContext.getCurrentActivity() ?: return false
        
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission)
    }

    override suspend fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 将通用权限类型转换为Android权限字符串
     */
    private fun PermissionType.toAndroidPermission(): String {
        return when (this) {
            PermissionType.STORAGE_READ -> Manifest.permission.READ_EXTERNAL_STORAGE
            PermissionType.STORAGE_WRITE -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            PermissionType.CAMERA -> Manifest.permission.CAMERA
            PermissionType.LOCATION_PRECISE -> Manifest.permission.ACCESS_FINE_LOCATION
            PermissionType.LOCATION_COARSE -> Manifest.permission.ACCESS_COARSE_LOCATION
            PermissionType.PHOTO_LIBRARY -> Manifest.permission.READ_EXTERNAL_STORAGE
            PermissionType.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            PermissionType.CONTACTS -> Manifest.permission.READ_CONTACTS
            PermissionType.CALENDAR -> Manifest.permission.READ_CALENDAR
            PermissionType.PHONE -> Manifest.permission.READ_PHONE_STATE
            PermissionType.SENSORS -> Manifest.permission.BODY_SENSORS
            PermissionType.ACTIVITY_RECOGNITION -> Manifest.permission.ACTIVITY_RECOGNITION
        }
    }
}

// 在Android平台，我们需要通过依赖注入获取Context
internal actual val permissionService: PermissionService
    get() = AndroidPermissionService(AppContext.applicationContext)
