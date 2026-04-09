package com.wgt.feature.permission

/**
 * 权限类型枚举
 */
enum class PermissionType {
    /**
     * 存储权限 - 读取外部存储
     */
    STORAGE_READ,

    /**
     * 存储权限 - 写入外部存储
     */
    STORAGE_WRITE,

    /**
     * 相机权限
     */
    CAMERA,

    /**
     * 位置权限 - 精确位置
     */
    LOCATION_PRECISE,

    /**
     * 位置权限 - 粗略位置
     */
    LOCATION_COARSE,

    /**
     * 照片图库权限
     */
    PHOTO_LIBRARY,

    /**
     * 麦克风权限
     */
    MICROPHONE,

    /**
     * 联系人权限
     */
    CONTACTS,

    /**
     * 日历权限
     */
    CALENDAR,

    /**
     * 电话权限
     */
    PHONE,

    /**
     * 传感器权限
     */
    SENSORS,

    /**
     * 活动识别权限
     */
    ACTIVITY_RECOGNITION
}

/**
 * 权限状态
 */
enum class PermissionStatus {
    /**
     * 权限已授予
     */
    GRANTED,

    /**
     * 权限被拒绝
     */
    DENIED,

    /**
     * 权限需要解释（用户之前选择了"不再询问"）
     */
    SHOULD_SHOW_RATIONALE,

    /**
     * 权限状态未知（首次请求前）
     */
    UNKNOWN
}

/**
 * 权限请求结果
 */
data class PermissionResult(
    val permission: PermissionType,
    val status: PermissionStatus,
    val shouldShowRationale: Boolean = false
)

/**
 * 权限请求回调
 */
typealias PermissionCallback = (PermissionResult) -> Unit
