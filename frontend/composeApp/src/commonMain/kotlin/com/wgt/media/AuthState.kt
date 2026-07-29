package com.wgt.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wgt.platform.logger.logger

private const val TAG = "AuthState"

/**
 * 认证状态单例 —— 进程内唯一，跨屏幕共享。
 *
 * 持有两个 Compose 可观察属性：
 * - [token]：当前 JWT access token。非空即视为已登录。
 * - [currentUsername]/[currentUserId]：当前登录用户名 / 用户 id，供设置页展示与 App 路由守卫。
 *
 * **持久化**：token / 用户名 / 用户 id 经 [SettingsStorage] 安全存储（Android
 * EncryptedSharedPreferences / iOS Keychain）。冷启动时 [init] 读回，恢复登录态——
 * 用户上次登录后不必重登。退出登录（[clearSession]）清空三者。
 *
 * **与网络层解耦**：MediaService 在 feature-media 模块，不反向依赖 composeApp。token 经
 * Compose 响应式通道流转——[App] 用 `LaunchedEffect(AuthState.token)` 监听它（mutableStateOf
 * 委托属性，变更触发重组），即时推给 `MediaService.setAuthToken`，使已登录用户首请求即带
 * `Authorization: Bearer`、登出/401 后请求不带 token。故 AuthState 本身只管状态与持久化，
 * 不持有网络层回调。
 *
 * **401 处理**：MediaService 检测到 401 时调用 App 注册的处理器（`AuthState.clearSession()`），
 * 随后 token 变空 → [isLoggedIn] 由 true 转 false → [App] 路由守卫切回登录页。
 * 如此"401 → 清 token → 回登录"闭环成立。
 */
object AuthState {
    private val storage = SettingsStorage()

    /** 当前 JWT token。空串视为未登录。 */
    var token by mutableStateOf(loadToken())
        private set

    /** 当前登录用户名。未登录为空串。 */
    var currentUsername by mutableStateOf(loadUsername())
        private set

    /** 当前登录用户 id（服务端分配）。未登录为空串。 */
    var currentUserId by mutableStateOf(loadUserId())
        private set

    /** 是否已登录：token 非空即视为是。供 App 路由守卫判断。 */
    val isLoggedIn: Boolean get() = token.isNotEmpty()

    private fun loadToken(): String =
        storage.getSecureString(SettingsKeys.AUTH_TOKEN, "")

    private fun loadUsername(): String =
        storage.getSecureString(SettingsKeys.AUTH_USERNAME, "")

    private fun loadUserId(): String =
        storage.getSecureString(SettingsKeys.AUTH_USER_ID, "")

    /**
     * 登录/注册成功后落地会话。持久化 token / 用户名 / 用户 id，刷新内存状态。
     * 状态变更由 Compose snapshot 捕获 → [App] 的 LaunchedEffect 推给网络层。
     *
     * @param token JWT access token
     * @param username 用户名
     * @param userId 服务端用户 id（可空——旧版后端可能不带）
     */
    fun saveSession(token: String, username: String, userId: String? = null) {
        this.token = token
        this.currentUsername = username
        if (userId != null) this.currentUserId = userId
        storage.putSecureString(SettingsKeys.AUTH_TOKEN, token)
        storage.putSecureString(SettingsKeys.AUTH_USERNAME, username)
        if (userId != null) storage.putSecureString(SettingsKeys.AUTH_USER_ID, userId)
        logger.info(TAG, "session saved for user=$username")
    }

    /**
     * 清除会话（退出登录 / 401 捕获）。删除安全存储中的 token / 用户名 / 用户 id，
     * 内存状态置空。调用后 [isLoggedIn] 为 false。
     */
    fun clearSession() {
        token = ""
        currentUsername = ""
        currentUserId = ""
        storage.removeSecureString(SettingsKeys.AUTH_TOKEN)
        storage.removeSecureString(SettingsKeys.AUTH_USERNAME)
        storage.removeSecureString(SettingsKeys.AUTH_USER_ID)
        logger.info(TAG, "session cleared")
    }
}
