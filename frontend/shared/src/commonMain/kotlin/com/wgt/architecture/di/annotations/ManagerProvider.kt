package com.wgt.architecture.di.annotations

import com.wgt.architecture.di.Lifecycle
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget

/**
 * 标记一个类为 Manager 提供者
 * KSP 将自动生成 Provider 代码和注册函数
 * 
 * 使用示例:
 * ```kotlin
 * @ManagerProvider(
 *     initFunctionName = "initRnManager",
 *     providerName = "RnManagerProvider"
 * )
 * internal class RnManager : IRnManager {
 *     // ...
 * }
 * ```
 * 
 * 生成的代码:
 * ```kotlin
 * // Expect Provider（commonMain）
 * expect object RnManagerProvider {
 *     fun initialize(app: Application)
 *     fun getManager(): IRnManager
 * }
 * 
 * // Actual Provider（androidMain）
 * actual object RnManagerProvider {
 *     private lateinit var application: Application
 *     actual fun initialize(app: Application) { application = app }
 *     actual fun getManager(): IRnManager = RnManager.getInstance(application)
 * }
 * 
 * // 注册函数
 * fun initRnManager() {
 *     registerManager<IRnManager>(Lifecycle.SINGLETON) {
 *         RnManagerProvider.getManager()
 *     }
 * }
 * ```
 * 
 * @property interfaceClass Manager 接口类名，默认从实现类名推断
 * @property initFunctionName 注册函数名，默认 init + 类名
 * @property providerName Provider 对象名，默认类名 + Provider
 * @property lifecycle 生命周期类型，默认为单例
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class ManagerProvider(
    /**
     * Manager 接口类名（全限定名）
     * 例如："com.wgt.rn_module.IRnManager"
     * 默认推断：类名去掉 "Manager" 后缀，加 "I" 前缀
     */
    val interfaceClass: String = "",
    
    /**
     * 初始化函数名
     * 例如："initRnManager"
     * 默认："init" + 类名
     */
    val initFunctionName: String = "",
    
    /**
     * Provider 对象名
     * 例如："RnManagerProvider"
     * 默认：类名 + "Provider"
     */
    val providerName: String = "",
    
    /**
     * 生命周期类型
     * SINGLETON: 单例模式（默认）
     * TRANSIENT: 瞬态模式
     */
    val lifecycle: Lifecycle = Lifecycle.SINGLETON,
    
    /**
     * 是否需要 Application 上下文
     * true: Provider 需要接收 Application 参数
     * false: Provider 无参构造
     */
    val requiresApplication: Boolean = true
)
