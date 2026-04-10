package com.wgt.architecture.di.annotations

import com.wgt.architecture.di.Lifecycle
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

/**
 * 标记一个类为 Manager 提供者
 * KSP 将自动生成注册函数
 * 
 * 使用方式：
 * 1. 在 commonMain 中声明 expect class
 * 2. 在 expect class 上添加 @ManagerProvider 注解
 * 3. 在各平台（androidMain/iosMain）提供 actual 实现
 * 4. 确保 expect/actual 类都有 companion object { fun getInstance() }
 * 
 * 使用示例:
 * ```kotlin
 * // commonMain
 * @ManagerProvider(
 *     initFunctionName = "initRnManager",
 *     interfaceClass = "com.wgt.rn_module.IRnManager"
 * )
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
 * 生成的代码:
 * ```kotlin
 * // 注册函数
 * fun initRnManager() {
 *     registerManager<IRnManager>(Lifecycle.SINGLETON) {
 *         RnManager.getInstance()
 *     }
 * }
 * ```
 * 
 * @property interfaceClass Manager 接口类名（全限定名），默认从实现类名推断
 * @property initFunctionName 注册函数名，默认 "init" + 类名
 * @property lifecycle 生命周期类型，默认为单例
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ManagerProvider(
    /**
     * Manager 接口类名（全限定名）
     * 例如："com.wgt.rn_module.IRnManager"
     * 默认推断：类名前加 "I" 前缀
     */
    val interfaceClass: String = "",
    
    /**
     * 初始化函数名
     * 例如："initRnManager"
     * 默认："init" + 类名
     */
    val initFunctionName: String = "",
    
    /**
     * 生命周期类型
     * SINGLETON: 单例模式（默认）
     * TRANSIENT: 瞬态模式
     */
    val lifecycle: Lifecycle = Lifecycle.SINGLETON
)
