package com.wgt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.wgt.feature.media.MediaService
import com.wgt.media.AlbumScreen
import com.wgt.media.AuthState
import com.wgt.media.FileManagementScreen
import com.wgt.media.LoginScreen
import com.wgt.media.MediaListScreen
import com.wgt.media.MediaViewModel
import com.wgt.media.RegisterScreen
import com.wgt.media.SettingsScreen
import com.wgt.media.SettingsState
import com.wgt.media.SplashScreen
import com.wgt.media.ThemeMode
import com.wgt.applyAmoledOverride

private val viewModel = MediaViewModel()

/**
 * 应用根 Composable。
 *
 * 主题色板由各平台 [resolveColorScheme] 决定：
 * - Android 12+（API 31）取系统壁纸动态取色（dynamicColor）；
 * - iOS 及低版本 Android 回退到内置中性色板。
 *
 * 主题模式来自 [SettingsState]（已持久化）：
 *   SYSTEM → 跟随系统
 *   LIGHT  → 强制浅色
 *   DARK   → 强制暗色
 *
 * 启动时先展示 [SplashScreen]（App 名称居中淡入，约 2s），完成后通过
 * [AnimatedContent] 交叉淡入淡出过渡到主界面，避免主界面突入。
 *
 * **认证路由**：未登录（[AuthState.isLoggedIn] == false）时显示登录/注册页；
 * 已登录则进入三 Tab 主界面（MEDIA/SETTINGS/ALBUM），与既有结构一致、不改动。
 * 401 拦截在 [MediaService] 内触发 [AuthState.clearSession] → [isLoggedIn] 翻转 →
 * 本 Composable 重组自动切回登录页，形成"401 → 清 token → 回登录"闭环。
 */
@Composable
@Preview
fun App() {
    val isDark = when (SettingsState.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AMOLED -> true
    }
    // AMOLED 模式：在深色色板基础上覆盖 background/surface 为纯黑，省 OLED 功耗。
    // 动态取色设备仍保留系统 accent，仅替换底色。
    val colors = if (SettingsState.themeMode == ThemeMode.AMOLED) {
        applyAmoledOverride(resolveColorScheme(dark = true))
    } else {
        resolveColorScheme(isDark)
    }

    // 状态栏图标外观跟随主题：浅色主题用深色图标，暗色用浅色图标。
    // Android 通过 WindowCompat 设置，iOS 为 no-op。
    applyStatusBarIconColor(isDark)

    // 把用户设置的后端地址注入 MediaService（feature-media 不反向依赖 composeApp，
    // 故用推模型）。直接读 SettingsState.backendUrl —— 它本身是 mutableStateOf 委托属性，
    // 在 @Composable 体内读取会建立 snapshot 订阅：设置页保存后值变化触发本 Composable
    // 重组，LaunchedEffect 的 key 随之改变并重新推送，保证运行时可变、即时生效（P0-1）。
    val backendUrl = SettingsState.backendUrl
    LaunchedEffect(backendUrl) {
        MediaService.setBackendUrl(backendUrl)
    }

    // 把当前 token 注入网络层（推模型，与 backendUrl 同款）。
    // 1) 注册 401 处理器：token 失效 → AuthState.clearSession → isLoggedIn 翻转 → 回登录页。
    // 2) 监听 AuthState.token 变化（登录/登出/401 清除都会改它），即时把首/最新 token 推给
    //    MediaService，使已登录用户首请求即带 Bearer、登出后请求不再带 token。
    // 启动时执行一次保证恢复的登录态生效（key 固定，块内读 AuthState.token 自动覆盖初始与变更）。
    LaunchedEffect(Unit) {
        MediaService.setUnauthorizedHandler { AuthState.clearSession() }
    }
    val authToken = AuthState.token
    LaunchedEffect(authToken) {
        MediaService.setAuthToken(authToken)
        // token 注入/登录成功后触发首次增量同步 + 自动备份幂等兜底。
        // 登出时 token 为空，onSessionReady 内部按 isLoggedIn 短路，不会发起请求。
        if (authToken.isNotEmpty()) viewModel.onSessionReady()
    }

    var showSplash by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf(Screen.MEDIA) }
    // 登录页内的二级视图：登录 ↔ 注册切替。未登录时展示此视图。
    var authView by remember { mutableStateOf(AuthView.LOGIN) }

    // 登录态：直接读 AuthState.isLoggedIn（基于 token 非空）。它由 mutableStateOf 委托，
    // 登录/登出/401 变更触发本 Composable 重组，从而在登录页与主界面间切换。
    val isLoggedIn = AuthState.isLoggedIn

    MaterialTheme(colorScheme = colors) {
        AnimatedContent(
            targetState = showSplash,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "splashToHome"
        ) { splash ->
            if (splash) {
                SplashScreen(onFinish = { showSplash = false })
            } else {
                if (!isLoggedIn) {
                    // 未登录：登录/注册页，互斥切替。
                    when (authView) {
                        AuthView.LOGIN -> LoginScreen(
                            onLoggedIn = { authView = AuthView.LOGIN },
                            onSwitchToRegister = { authView = AuthView.REGISTER }
                        )
                        AuthView.REGISTER -> RegisterScreen(
                            onRegistered = { authView = AuthView.LOGIN },
                            onSwitchToLogin = { authView = AuthView.LOGIN },
                            onBack = { authView = AuthView.LOGIN }
                        )
                    }
                } else {
                    // 已登录：保留既有三 Tab 结构不变。
                    when (screen) {
                        Screen.MEDIA -> MediaListScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { screen = Screen.SETTINGS },
                            onNavigateToAlbums = { screen = Screen.ALBUM },
                            onNavigateToFileManagement = { screen = Screen.FILE_MANAGEMENT }
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.MEDIA }
                        )
                        Screen.ALBUM -> AlbumScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.MEDIA }
                        )
                        Screen.FILE_MANAGEMENT -> FileManagementScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.MEDIA }
                        )
                    }
                }
            }
        }
    }
}

/** 顶层屏幕路由（已登录态）。 */
private enum class Screen { MEDIA, SETTINGS, ALBUM, FILE_MANAGEMENT }

/** 未登录态的二级视图：登录 / 注册切替。 */
private enum class AuthView { LOGIN, REGISTER }
