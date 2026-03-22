package com.wgt.feature.permission

import kotlinx.coroutines.flow.Flow

/**
 * 权限服务接口
 * 提供跨平台的权限检查和请求功能
 */
interface PermissionService {
    
    /**
     * 检查单个权限状态
     */
    suspend fun checkPermission(permission: PermissionType): PermissionStatus
    
    /**
     * 检查多个权限状态
     */
    suspend fun checkPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionStatus>
    
    /**
     * 请求单个权限
     */
    suspend fun requestPermission(permission: PermissionType): PermissionResult
    
    /**
     * 请求多个权限
     */
    suspend fun requestPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionResult>
    
    /**
     * 监听权限状态变化
     */
    fun observePermission(permission: PermissionType): Flow<PermissionStatus>
    
    /**
     * 检查是否需要显示权限解释
     */
    suspend fun shouldShowRationale(permission: PermissionType): Boolean
    
    /**
     * 打开应用设置页面（引导用户手动授权）
     */
    suspend fun openAppSettings()
    
    /**
     * 检查是否所有权限都已授予
     */
    suspend fun allPermissionsGranted(permissions: List<PermissionType>): Boolean {
        val statuses = checkPermissions(permissions)
        return statuses.all { it.value == PermissionStatus.GRANTED }
    }
    
    /**
     * 检查是否有任何权限被拒绝
     */
    suspend fun anyPermissionDenied(permissions: List<PermissionType>): Boolean {
        val statuses = checkPermissions(permissions)
        return statuses.any { it.value == PermissionStatus.DENIED }
    }
}

/**
 * 期望的平台特定权限服务
 */
internal expect val permissionService: PermissionService
