package com.wgt.architecture.annotation

/**
 * Feature 初始化器注解
 * 标记用于初始化 Feature 的函数
 * 
 * 使用示例：
 * ```kotlin
 * @FeatureInitializer(priority = 0)
 * fun InitPermissionFeature() {
 *     registerFeature { PermissionFeature() }
 * }
 * ```
 * 
 * @param priority 初始化优先级，数值越小越先初始化，默认为 100
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class FeatureInitializer(
    val priority: Int = 100
)

/**
 * Manager 初始化器注解
 * 标记用于初始化 Manager 的函数
 * 
 * 使用示例：
 * ```kotlin
 * @ManagerInitializer(priority = 0)
 * fun InitFeatureManager() {
 *     registerManager(Lifecycle.SINGLETON) {
 *         FeatureManager()
 *     }
 * }
 * ```
 * 
 * @param priority 初始化优先级，数值越小越先初始化，默认为 100
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class ManagerInitializer(
    val priority: Int = 100
)
