package com.wgt.architecture.di.generated

/**
 * Manager 初始化函数
 *
 * 此函数在应用启动时调用，用于初始化所有标注了 @ManagerProvider 的 Manager
 * KSP 处理 @ManagerProvider 注解后，自动写入 initXXXManager() 的调用代码
 *
 * 使用方式：
 * ```kotlin
 * fun initializeApplication() {
 *     InitManager()
 *     InitFeature()
 * }
 * ```
 */
fun InitManager() {
    // KSP 会自动生成调用所有 initXXXManager() 的代码
}
