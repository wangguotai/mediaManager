package com.wgt.architecture.di.annotations

import com.wgt.architecture.di.Lifecycle
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

/**
 * 标记一个类为 Manager 提供者
 * KSP 将自动生成注册函数
 * 
 * 使用方式：
 * 1. 在 commonMain 中声明 expect class，实现 IManager 的子接口
 * 2. 在 expect class 上添加 @ManagerProvider 注解（无需参数）
 * 3. 在各平台（androidMain/iosMain）提供 actual 实现
 * 4. 确保 expect/actual 类都有 companion object { fun getInstance() }
 * 
 * 使用示例:
 * ```kotlin
 * // commonMain
 * @ManagerProvider
 * internal expect class RnManager private constructor() : IRnManager {
 *     companion object {
 *         fun getInstance(): RnManager
 *     }
 * }
 * 
 * // androidMain
 * internal actual class RnManager private actual constructor() : IRnManager {
 *     actual companion object {
 *         actual fun getInstance(): RnManager = // Android 实现
 *     }
 * }
 * 
 * // iosMain
 * internal actual class RnManager private actual constructor() : IRnManager {
 *     actual companion object {
 *         actual fun getInstance(): RnManager = // iOS 实现
 *     }
 * }
 * ```
 * 
 * KSP 自动生成：
 * ```kotlin
 * // ManagerRegistrations.kt
 * fun initRnManager() {
 *     registerManager<IRnManager>(Lifecycle.SINGLETON) {
 *         RnManager.getInstance()
 *     }
 * }
 * ```
 * 
 * @property lifecycle 生命周期类型，默认为单例
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ManagerProvider(
    /**
     * 生命周期类型
     * SINGLETON: 单例模式（默认）
     * TRANSIENT: 瞬态模式
     */
    val lifecycle: Lifecycle = Lifecycle.SINGLETON
)
