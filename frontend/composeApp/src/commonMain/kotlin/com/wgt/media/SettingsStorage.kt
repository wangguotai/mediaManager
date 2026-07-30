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

    /** 自动云备份开关（"true"/"false"）。默认关，用户在设置页主动开启。 */
    const val AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    /**
     * 已登记的设备 id（服务端分配，见 [MediaService.registerDevice]）。
     * 自动备份开启后首次注册获得，持久化以避免每次启动重复注册设备。
     */
    const val DEVICE_ID = "device_id"

    /**
     * 已上传内容的 SHA-256 指纹集合（逗号分隔），供 [Sha256Dedup] 去重。
     * 冷启动读回，避免重启后自动备份重传全库。
     */
    const val UPLOADED_SHA256 = "uploaded_sha256"

    /**
     * 增量同步游标（毫秒字符串）。记录上次 [MediaService.getSyncChanges] 推进到的
     * updated_at，冷启动续拉增量；0 表示从未同步。见 [SettingsState.syncCursor]。
     */
    const val SYNC_CURSOR = "sync_cursor"

    /**
     * 仅 WiFi 备份开关（V6 §2.1）。"true"时自动备份仅在 WiFi 网络下执行，移动数据下暂停。
     * 默认 "true"（对标小米「仅 WiFi 备份」默认策略，避免用户流量被偷跑）。
     */
    const val BACKUP_WIFI_ONLY = "backup_wifi_only"

    /**
     * 仅充电备份开关（V6 §2.1）。"true"时自动备份仅在充电状态下执行，电池供电时暂停。
     * 默认 "false"（不强制充电，WiFi 下即可备份；用户可按需开启省电）。
     */
    const val BACKUP_CHARGING_ONLY = "backup_charging_only"

    /**
     * 上次备份完成时间（epoch 毫秒字符串，PRD-v7 §1.5）。
     *
     * [MediaViewModel.checkAndBackupNewLocalMedia] 成功完成一轮后经
     * [SettingsState.saveLastBackupTime] 落盘，设置页读取并格式化展示。
     * 空串/0L 表示从未备份过。
     */
    const val LAST_BACKUP_TIME = "last_backup_time"
}

/**
 * 主题模式。序列化方式：直接用 [name]（SYSTEM/LIGHT/DARK），
 * 反序列化在 [SettingsState] 中用 [valueOf] 容错处理。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
