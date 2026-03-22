package com.wgt.architecture.lifecycle

/**
 * iOS应用生命周期状态
 */
enum class AppLifecycleState {
    /**
     * 应用已启动
     */
    LAUNCHED,
    
    /**
     * 应用进入前台
     */
    FOREGROUND,
    
    /**
     * 应用进入后台
     */
    BACKGROUND,
    
    /**
     * 应用即将终止
     */
    TERMINATING
}

/**
 * 应用生命周期观察者接口
 * 组件实现此接口以接收应用生命周期变化通知
 */
interface AppLifecycleObserver {
    /**
     * 应用生命周期状态变化回调
     * @param state 新的生命周期状态
     */
    fun onLifecycleStateChanged(state: AppLifecycleState) {}
    
    /**
     * 应用启动完成回调
     */
    fun onAppLaunched() {}
    
    /**
     * 应用进入前台回调
     */
    fun onAppForeground() {}
    
    /**
     * 应用进入后台回调
     */
    fun onAppBackground() {}
    
    /**
     * 应用即将终止回调
     */
    fun onAppTerminating() {}
}

/**
 * 应用生命周期管理器
 * 负责管理应用生命周期观察者并分发生命周期事件
 * 
 * 注意：Kotlin/Native 不支持 synchronized，此实现假设在主线程调用
 */
object AppLifecycleManager {
    private val observers = mutableListOf<AppLifecycleObserver>()
    private var currentState: AppLifecycleState? = null
    
    /**
     * 注册生命周期观察者
     * @param observer 要注册的观察者
     */
    fun registerObserver(observer: AppLifecycleObserver) {
        if (!observers.contains(observer)) {
            observers.add(observer)
            // 如果已有当前状态，立即通知新观察者
            currentState?.let { state ->
                observer.onLifecycleStateChanged(state)
                when (state) {
                    AppLifecycleState.LAUNCHED -> observer.onAppLaunched()
                    AppLifecycleState.FOREGROUND -> observer.onAppForeground()
                    AppLifecycleState.BACKGROUND -> observer.onAppBackground()
                    AppLifecycleState.TERMINATING -> observer.onAppTerminating()
                }
            }
        }
    }
    
    /**
     * 注销生命周期观察者
     * @param observer 要注销的观察者
     */
    fun unregisterObserver(observer: AppLifecycleObserver) {
        observers.remove(observer)
    }
    
    /**
     * 通知应用已启动
     */
    fun notifyAppLaunched() {
        updateState(AppLifecycleState.LAUNCHED)
        observers.forEach { it.onAppLaunched() }
    }
    
    /**
     * 通知应用进入前台
     */
    fun notifyAppForeground() {
        updateState(AppLifecycleState.FOREGROUND)
        observers.forEach { it.onAppForeground() }
    }
    
    /**
     * 通知应用进入后台
     */
    fun notifyAppBackground() {
        updateState(AppLifecycleState.BACKGROUND)
        observers.forEach { it.onAppBackground() }
    }
    
    /**
     * 通知应用即将终止
     */
    fun notifyAppTerminating() {
        updateState(AppLifecycleState.TERMINATING)
        observers.forEach { it.onAppTerminating() }
    }
    
    /**
     * 获取当前生命周期状态
     */
    fun getCurrentState(): AppLifecycleState? = currentState
    
    /**
     * 更新当前状态并通知观察者
     */
    private fun updateState(newState: AppLifecycleState) {
        currentState = newState
        observers.forEach { it.onLifecycleStateChanged(newState) }
    }
    
    /**
     * 清除所有观察者
     */
    fun clearObservers() {
        observers.clear()
        currentState = null
    }
}
