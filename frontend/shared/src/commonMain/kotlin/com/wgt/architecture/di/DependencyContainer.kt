package com.wgt.architecture.di

import kotlin.reflect.KClass

/**
 * 依赖生命周期枚举
 */
enum class Lifecycle {
    /**
     * 单例模式 - 整个应用生命周期内只创建一个实例
     */
    SINGLETON,
    
    /**
     * 瞬态模式 - 每次解析都创建新实例
     */
    TRANSIENT
}

/**
 * 依赖注册信息
 */
data class DependencyRegistration<T : Any>(
    val clazz: KClass<T>,
    val lifecycle: Lifecycle,
    val factory: () -> T,
    var instance: T? = null
)

/**
 * 依赖注入容器
 * 提供轻量级、可扩展的依赖注入功能
 */
object DependencyContainer {
    
    internal val registry = mutableMapOf<KClass<*>, DependencyRegistration<*>>()
    internal val resolvingStack = mutableSetOf<KClass<*>>()
    
    /**
     * 注册依赖项
     * @param T 依赖类型
     * @param lifecycle 生命周期模式
     * @param factory 实例工厂函数
     */
    fun <T : Any> register(
        clazz: KClass<T>,
        lifecycle: Lifecycle = Lifecycle.SINGLETON,
        factory: () -> T
    ) {
        registry[clazz] = DependencyRegistration(
            clazz = clazz,
            lifecycle = lifecycle,
            factory = factory
        )
    }
    
    /**
     * 注册依赖项（泛型版本）
     */
    inline fun <reified T : Any> register(
        lifecycle: Lifecycle = Lifecycle.SINGLETON,
        noinline factory: () -> T
    ) {
        register(T::class, lifecycle, factory)
    }
    
    /**
     * 注册单例依赖项
     * @param T 依赖类型
     * @param instance 单例实例
     */
    fun <T : Any> registerSingleton(clazz: KClass<T>, instance: T) {
        registry[clazz] = DependencyRegistration(
            clazz = clazz,
            lifecycle = Lifecycle.SINGLETON,
            factory = { instance },
            instance = instance
        )
    }
    
    /**
     * 注册单例依赖项（泛型版本）
     */
    inline fun <reified T : Any> registerSingleton(instance: T) {
        registerSingleton(T::class, instance)
    }
    
    /**
     * 解析依赖项
     * @param T 依赖类型
     * @return 解析后的实例
     * @throws DependencyResolutionException 如果依赖未找到或出现循环依赖
     */
    inline fun <reified T : Any> resolve(): T {
        return resolve(T::class)
    }
    
    /**
     * 解析依赖项（类型安全版本）
     * @param clazz 依赖类型
     * @return 解析后的实例
     * @throws DependencyResolutionException 如果依赖未找到或出现循环依赖
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(clazz: KClass<T>): T {
        // 检查循环依赖
        if (resolvingStack.contains(clazz)) {
            throw DependencyResolutionException("检测到循环依赖: ${resolvingStack.joinToString(" -> ")} -> ${clazz.simpleName}")
        }
        
        // 查找注册信息
        val registration = registry[clazz] as? DependencyRegistration<T>
            ?: throw DependencyResolutionException("未找到依赖: ${clazz.simpleName}")
        
        // 处理单例模式
        if (registration.lifecycle == Lifecycle.SINGLETON) {
            if (registration.instance == null) {
                resolvingStack.add(clazz)
                try {
                    registration.instance = registration.factory()
                } finally {
                    resolvingStack.remove(clazz)
                }
            }
            return registration.instance!!
        }
        
        // 处理瞬态模式
        resolvingStack.add(clazz)
        try {
            return registration.factory()
        } finally {
            resolvingStack.remove(clazz)
        }
    }
    
    /**
     * 检查依赖是否已注册
     * @param T 依赖类型
     * @return 是否已注册
     */
    fun <T : Any> isRegistered(clazz: KClass<T>): Boolean {
        return registry.containsKey(clazz)
    }
    
    /**
     * 检查依赖是否已注册（泛型版本）
     */
    inline fun <reified T : Any> isRegistered(): Boolean {
        return isRegistered(T::class)
    }
    
    /**
     * 清除所有注册的依赖项
     */
    fun clear() {
        registry.clear()
        resolvingStack.clear()
    }
    
    /**
     * 清除指定类型的依赖项
     * @param T 依赖类型
     */
    fun <T : Any> unregister(clazz: KClass<T>) {
        registry.remove(clazz)
    }
    
    /**
     * 清除指定类型的依赖项（泛型版本）
     */
    inline fun <reified T : Any> unregister() {
        unregister(T::class)
    }
    
    /**
     * 获取所有已注册的依赖类型
     * @return 已注册的类型列表
     */
    fun getRegisteredTypes(): List<KClass<*>> {
        return registry.keys.toList()
    }
}

/**
 * 依赖解析异常
 */
class DependencyResolutionException(message: String) : Exception(message)

/**
 * 依赖注入扩展函数和工具类
 */

/**
 * 简化的依赖解析扩展函数
 */
inline fun <reified T : Any> resolve(): T = DependencyContainer.resolve()

/**
 * 依赖注入构建器，支持链式调用
 */
class DependencyBuilder {
    inline fun <reified T : Any> singleton(noinline factory: () -> T) {
        DependencyContainer.register(Lifecycle.SINGLETON, factory)
    }
    
    inline fun <reified T : Any> transient(noinline factory: () -> T) {
        DependencyContainer.register(Lifecycle.TRANSIENT, factory)
    }
    
    inline fun <reified T : Any> singletonInstance(instance: T) {
        DependencyContainer.registerSingleton(instance)
    }
}

/**
 * 依赖注入配置函数
 */
fun dependencies(block: DependencyBuilder.() -> Unit) {
    val builder = DependencyBuilder()
    builder.block()
}

/**
 * 属性委托注入
 */
inline fun <reified T : Any> inject(): Lazy<T> {
    return lazy { DependencyContainer.resolve<T>() }
}

/**
 * 可空属性委托注入
 */
inline fun <reified T : Any> injectOrNull(): Lazy<T?> {
    return lazy {
        try {
            DependencyContainer.resolve<T>()
        } catch (e: DependencyResolutionException) {
            null
        }
    }
}
