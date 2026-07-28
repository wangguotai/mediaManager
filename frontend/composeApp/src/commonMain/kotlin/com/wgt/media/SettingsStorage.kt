package com.wgt.media

/**
 * 平台键值存储抽象 —— expect/actual 实现。
 *
 * Android 端用 [android.content.SharedPreferences]，iOS 端用 NSUserDefaults，
 * 两端共用同一组 [key] 常量（见 [SettingsKeys]），保证设置项跨平台语义一致。
 *
 * 单例：[SettingsStorage] 通过各平台实际实现以懒加载方式持有底层存储句柄，
 * 因此这里声明为 `expect class` 的单例对象引用——实际实现须提供线程安全的
 * `getString` / `putString`（SharedPreferences 与 NSUserDefaults 本身即线程安全）。
 *
 * 注意：本类仅做字符串存取；类型化读写（如 [ThemeMode]、后端地址校验等）由
 * [SettingsState] 负责，避免存储层与 UI 语义耦合。
 */
expect class SettingsStorage() {
    /**
     * 读取字符串。键不存在时返回 [defaultValue]。
     */
    fun getString(key: String, default: String): String

    /**
     * 写入字符串。底层存储是否立即落盘取决于平台实现
     *（Android 的 apply() 异步、iOS 的 synchronize 由系统接管）。
     */
    fun putString(key: String, value: String)
}

/**
 * 设置项键名常量。集中声明，避免散落各处的魔法字符串导致拼写不一致。
 */
object SettingsKeys {
    /** 后端 REST gateway 地址，如 `http://10.0.2.2:8080`。 */
    const val BACKEND_URL = "backend_url"

    /** 主题模式，取值为 [ThemeMode] 的 name 值。 */
    const val THEME_MODE = "theme_mode"
}

/**
 * 主题模式。序列化方式：直接用 [name]（SYSTEM/LIGHT/DARK），
 * 反序列化在 [SettingsState] 中用 [valueOf] 容错处理。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
