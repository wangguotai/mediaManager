package com.wgt.media

/**
 * 平台键值存储抽象 —— expect/actual 实现。
 *
 * 分两类存储：
 * - 普通设置（[getString]/[putString]）：Android 用 [android.content.SharedPreferences]，
 *   iOS 用 NSUserDefaults。存放非敏感项（后端地址、主题）。
 * - 敏感凭据（[getSecureString]/[putSecureString]/[removeSecureString]）：
 *   专门用于 JWT token 与用户名。Android 端落到 EncryptedSharedPreferences
 *  （AndroidKeystore 支撑），iOS 端落到 Keychain。两类底层实例互相独立，
 *  避免敏感凭据与普通明文设置混在同一存储。
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

    /**
     * 读取敏感字符串（凭据）。键不存在返回 [default]。
     *
     * 实现须落盘到平台安全存储（Android EncryptedSharedPreferences / iOS Keychain）。
     */
    fun getSecureString(key: String, default: String): String

    /**
     * 写入敏感字符串到平台安全存储。
     */
    fun putSecureString(key: String, value: String)

    /**
     * 删除敏感字符串。退出登录时清 token / 用户名调用。
     * 某些平台安全存储没有"写空串即清除"语义，故单独提供删除入口。
     */
    fun removeSecureString(key: String)
}

/**
 * 设置项键名常量。集中声明，避免散落各处的魔法字符串导致拼写不一致。
 */
object SettingsKeys {
    /** 后端 REST gateway 地址，如 `http://10.0.2.2:8080`。 */
    const val BACKEND_URL = "backend_url"

    /** 主题模式，取值为 [ThemeMode] 的 name 值。 */
    const val THEME_MODE = "theme_mode"

    /** JWT access token —— 经安全存储（EncryptedSharedPreferences / Keychain）落盘。 */
    const val AUTH_TOKEN = "auth_token"

    /** 当前登录用户名 —— 与 token 同落安全存储，供设置页展示与恢复登录态。 */
    const val AUTH_USERNAME = "auth_username"

    /** 当前登录用户 id（服务端分配），恢复登录态时回填 [AuthState]。 */
    const val AUTH_USER_ID = "auth_user_id"
}

/**
 * 主题模式。序列化方式：直接用 [name]（SYSTEM/LIGHT/DARK），
 * 反序列化在 [SettingsState] 中用 [valueOf] 容错处理。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
