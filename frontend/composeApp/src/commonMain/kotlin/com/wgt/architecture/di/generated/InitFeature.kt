package com.wgt.architecture.di.generated

import com.wgt.feature.permission.initPermissionFeature
import com.wgt.feature.gallery.initGalleryFeature

/**
 * Feature 初始化函数
 * 
 * 此函数在应用启动时调用，用于初始化所有 Feature
 * KSP在处理 FeatureProvider 注解后，自动写入这些函数的代码，到InitFeature()方法中
 * 
 * 使用方式：
 * ```kotlin
 * fun initializeApplication() {
 *     InitManager()
 *     InitFeature()
 *     manager.feature.initialize()
 * }
 * ```
 */
fun InitFeature() {
    initGalleryFeature()
    initPermissionFeature()
    // KSP 会自动生成调用所有 initXXXFeature 的代码
}







