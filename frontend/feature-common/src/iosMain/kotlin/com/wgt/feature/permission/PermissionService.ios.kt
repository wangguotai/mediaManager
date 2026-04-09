package com.wgt.feature.permission

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.EventKit.EKEventStore
import platform.EventKit.EKEntityType
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.Contacts.CNContactStore
import platform.Contacts.CNAuthorizationStatus
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNAuthorizationStatusDenied
import platform.Contacts.CNAuthorizationStatusNotDetermined
import platform.Contacts.CNAuthorizationStatusRestricted
import platform.Contacts.CNEntityType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreMotion.CMMotionActivityManager
import platform.CoreMotion.CMAuthorizationStatus
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusDenied
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatusRestricted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS平台权限服务实现
 * 直接使用iOS原生API请求权限，无需外部handler
 */
class IOSPermissionService : PermissionService {
    
    private val permissionStatusFlows = mutableMapOf<PermissionType, MutableStateFlow<PermissionStatus>>()
    private val locationManager by lazy {
        CLLocationManager()
    }
    
    override suspend fun checkPermission(permission: PermissionType): PermissionStatus {
        return when (permission) {
            PermissionType.PHOTO_LIBRARY -> checkPhotoLibraryPermission()
            PermissionType.CAMERA -> checkCameraPermission()
            PermissionType.LOCATION_PRECISE, PermissionType.LOCATION_COARSE -> checkLocationPermission()
            PermissionType.MICROPHONE -> checkMicrophonePermission()
            PermissionType.CONTACTS -> checkContactsPermission()
            PermissionType.CALENDAR -> checkCalendarPermission()
            PermissionType.ACTIVITY_RECOGNITION -> checkActivityRecognitionPermission()
            else -> PermissionStatus.UNKNOWN
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
        
        // 请求权限
        val newStatus = when (permission) {
            PermissionType.PHOTO_LIBRARY -> requestPhotoLibraryPermission()
            PermissionType.CAMERA -> requestCameraPermission()
            PermissionType.LOCATION_PRECISE, PermissionType.LOCATION_COARSE -> requestLocationPermission()
            PermissionType.MICROPHONE -> requestMicrophonePermission()
            PermissionType.CONTACTS -> requestContactsPermission()
            PermissionType.CALENDAR -> requestCalendarPermission()
            PermissionType.ACTIVITY_RECOGNITION -> requestActivityRecognitionPermission()
            else -> currentStatus
        }
        
        // 更新状态流
        permissionStatusFlows[permission]?.value = newStatus
        
        return PermissionResult(permission, newStatus, false)
    }
    
    override suspend fun requestPermissions(permissions: List<PermissionType>): Map<PermissionType, PermissionResult> {
        return permissions.associateWith { requestPermission(it) }
    }
    
    override fun observePermission(permission: PermissionType): Flow<PermissionStatus> {
        return permissionStatusFlows.getOrPut(permission) {
            MutableStateFlow(PermissionStatus.UNKNOWN)
        }
    }
    
    override suspend fun shouldShowRationale(permission: PermissionType): Boolean {
        // iOS中权限请求通常不需要显示解释对话框
        return false
    }
    
    override suspend fun openAppSettings() {
        val url = NSURL.URLWithString("app-settings:")
        url?.let {
            UIApplication.sharedApplication.openURL(it)
        }
    }
    
    // ==================== 照片图库权限 ====================
    
    private fun checkPhotoLibraryPermission(): PermissionStatus {
        val status = PHPhotoLibrary.authorizationStatus()
        return mapPHAuthorizationStatus(status)
    }
    
    private suspend fun requestPhotoLibraryPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.requestAuthorization { status ->
                continuation.resume(mapPHAuthorizationStatus(status))
            }
        }
    }
    
    private fun mapPHAuthorizationStatus(status: PHAuthorizationStatus): PermissionStatus {
        return when (status) {
            PHAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            PHAuthorizationStatusDenied, PHAuthorizationStatusRestricted -> PermissionStatus.DENIED
            PHAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
    
    // ==================== 相机权限 ====================
    
    private fun checkCameraPermission(): PermissionStatus {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        return mapAVAuthorizationStatus(status)
    }
    
    private suspend fun requestCameraPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                continuation.resume(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }
    }
    
    private fun mapAVAuthorizationStatus(status: AVAuthorizationStatus): PermissionStatus {
        return when (status) {
            AVAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> PermissionStatus.DENIED
            AVAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
    
    // ==================== 麦克风权限 ====================
    
    private fun checkMicrophonePermission(): PermissionStatus {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)
        return mapAVAuthorizationStatus(status)
    }
    
    private suspend fun requestMicrophonePermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { granted ->
                continuation.resume(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }
    }
    
    // ==================== 位置权限 ====================
    
    private fun checkLocationPermission(): PermissionStatus {
        val status = CLLocationManager.authorizationStatus()
        return mapCLAuthorizationStatus(status)
    }
    
    private suspend fun requestLocationPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                override fun locationManager(manager: CLLocationManager, didChangeAuthorizationStatus: Int) {
                    val status = mapCLAuthorizationStatus(didChangeAuthorizationStatus)
                    if (status != PermissionStatus.UNKNOWN) {
                        continuation.resume(status)
                    }
                }
            }
            
            locationManager.delegate = delegate
            locationManager.requestWhenInUseAuthorization()
        }
    }
    
    private fun mapCLAuthorizationStatus(status: CLAuthorizationStatus): PermissionStatus {
        return when (status) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse -> PermissionStatus.GRANTED
            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> PermissionStatus.DENIED
            kCLAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
    
    // ==================== 联系人权限 ====================
    
    private fun checkContactsPermission(): PermissionStatus {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)
        return mapCNAuthorizationStatus(status)
    }
    
    private suspend fun requestContactsPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            val store = CNContactStore()
            store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ ->
                continuation.resume(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }
    }
    
    private fun mapCNAuthorizationStatus(status: CNAuthorizationStatus): PermissionStatus {
        return when (status) {
            CNAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            CNAuthorizationStatusDenied, CNAuthorizationStatusRestricted -> PermissionStatus.DENIED
            CNAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
    
    // ==================== 日历权限 ====================
    
    private fun checkCalendarPermission(): PermissionStatus {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        return mapEKAuthorizationStatus(status)
    }
    
    private suspend fun requestCalendarPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            val store = EKEventStore()
            store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, _ ->
                continuation.resume(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }
    }
    
    private fun mapEKAuthorizationStatus(status: EKAuthorizationStatus): PermissionStatus {
        return when (status) {
            EKAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            EKAuthorizationStatusDenied, EKAuthorizationStatusRestricted -> PermissionStatus.DENIED
            EKAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
    
    // ==================== 活动识别权限 ====================
    
    private fun checkActivityRecognitionPermission(): PermissionStatus {
        val status = CMMotionActivityManager.authorizationStatus()
        return mapCMAuthorizationStatus(status)
    }
    
    private suspend fun requestActivityRecognitionPermission(): PermissionStatus {
        // 活动识别权限需要实际查询数据才会触发请求
        // 这里返回当前状态
        return checkActivityRecognitionPermission()
    }
    
    private fun mapCMAuthorizationStatus(status: CMAuthorizationStatus): PermissionStatus {
        return when (status) {
            CMAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
            CMAuthorizationStatusDenied, CMAuthorizationStatusRestricted -> PermissionStatus.DENIED
            CMAuthorizationStatusNotDetermined -> PermissionStatus.UNKNOWN
            else -> PermissionStatus.UNKNOWN
        }
    }
}

internal actual val permissionService: PermissionService = IOSPermissionService()
