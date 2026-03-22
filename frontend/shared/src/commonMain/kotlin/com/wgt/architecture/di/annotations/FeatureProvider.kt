package com.wgt.architecture.di.annotations

import com.wgt.architecture.di.Lifecycle
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

/**
 * 标记一个类为Feature提供者
 * KSP将自动生成注册代码和扩展属性
 * 
 * 使用示例:
 * ```kotlin
 * @FeatureProvider
 * class PermissionFeature : Feature() {
 *     override val name = "PermissionFeature"
 * }
 * ```
 * 
 * 生成的代码:
 * ```kotlin
 * // 扩展属性
 * val IFeatureManager.permission: PermissionFeature by inject()
 * 
 * // 注册函数
 * fun registerAllFeatures() {
 *     registerFeature<PermissionFeature>(Lifecycle.SINGLETON) {
 *         PermissionFeature()
 *     }
 * }
 * ```
 * 
 * @property propertyName Feature的属性名称，默认使用类名转换
 * 例如: PermissionFeature -> permission
 * @property lifecycle 生命周期类型，默认为单例
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class FeatureProvider(
    /**
     * Feature的属性名称，默认使用类名转换
     * 例如: PermissionFeature -> permission
     * MediaFeature -> media
     * UserProfileFeature -> userProfile
     */
    val propertyName: String = "",
    
    /**
     * 生命周期类型
     * SINGLETON: 单例模式，整个应用生命周期内只创建一个实例
     * TRANSIENT: 瞬态模式，每次解析都创建新实例
     */
    val lifecycle: Lifecycle = Lifecycle.SINGLETON
)
