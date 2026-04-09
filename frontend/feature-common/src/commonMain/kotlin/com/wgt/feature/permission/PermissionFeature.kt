package com.wgt.feature.permission

import com.wgt.architecture.di.annotations.FeatureProvider
import com.wgt.architecture.feature.Feature
import com.wgt.platform.logger.logger

/**
 * 权限功能Feature
 * 提供跨平台的权限管理功能
 */
@FeatureProvider
class PermissionFeature() : Feature() {

    override val name: String = "PermissionFeature"
    private val TAG = "PermissionFeature"

    /**
     * 权限服务实例
     */
    val service: PermissionService = permissionService

    /**
     * 检查单个权限状态
     */
    suspend fun checkPermission(permission: PermissionType): PermissionStatus {
        return service.checkPermission(permission)
    }

    /**
     * 检查多个权限状态
     */
    suspend fun checkPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionStatus> {
        return service.checkPermissions(permissions)
    }

    /**
     * 请求单个权限
     */
    suspend fun requestPermission(permission: PermissionType): PermissionResult {
        return service.requestPermission(permission)
    }

    /**
     * 请求多个权限
     */
    suspend fun requestPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionResult> {
        return service.requestPermissions(permissions)
    }

    /**
     * 监听权限状态变化
     */
    fun observePermission(permission: PermissionType) = service.observePermission(permission)

    /**
     * 检查是否需要显示权限解释
     */
    suspend fun shouldShowRationale(permission: PermissionType): Boolean {
        return service.shouldShowRationale(permission)
    }

    /**
     * 打开应用设置页面
     */
    suspend fun openAppSettings() {
        service.openAppSettings()
    }

    /**
     * 检查是否所有权限都已授予
     */
    suspend fun allPermissionsGranted(permissions: List<PermissionType>): Boolean {
        return service.allPermissionsGranted(permissions)
    }

    /**
     * 检查是否有任何权限被拒绝
     */
    suspend fun anyPermissionDenied(permissions: List<PermissionType>): Boolean {
        return service.anyPermissionDenied(permissions)
    }

    /**
     * Feature初始化逻辑
     */
    override suspend fun onInitialize() {
        logger.info(TAG, "初始化权限功能")
        // 可以在这里进行权限功能的初始化工作
    }

    /**
     * Feature销毁逻辑
     */
    override suspend fun onDestroy() {
        logger.info(TAG, "销毁权限功能")
        // 可以在这里进行权限功能的清理工作
    }
}
