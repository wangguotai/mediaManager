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
import com.wgt.media.MediaListScreen
import com.wgt.media.MediaViewModel
import com.wgt.media.SettingsScreen
import com.wgt.media.SettingsState
import com.wgt.media.SplashScreen
import com.wgt.media.ThemeMode

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
 * [AnimatedContent] 交叉淡入淡出过渡到 [MediaListScreen]，避免主界面突入。
 */
@Composable
@Preview
fun App() {
    val isDark = when (SettingsState.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = resolveColorScheme(isDark)

    // 状态栏图标外观跟随主题：浅色主题用深色图标，暗色用浅色图标。
    // Android 通过 WindowCompat 设置，iOS 为 no-op。
    applyStatusBarIconColor(isDark)

    // 把用户设置的后端地址注入 MediaService（feature-media 不反向依赖 composeApp，
    // 故用推模型）。直接读 SettingsState.backendUrl —— 它本身是 mutableStateOf 委托属性，
    // 在 @Composable 体内读取会建立 snapshot 订阅：设置页保存后值变化触发本 Composable
    // 重组，LaunchedEffect 的 key 随之改变并重新推送，保证运行时可变、即时生效（P0-1）。
    //
    // 注意：此前误用 `remember { mutableStateOf(SettingsState.backendUrl) }`，那只
    // 在首次组合时拍一张快照、之后与 SettingsState 脱钩，地址变更永不传导，等于 P0-1 未解。
    val backendUrl = SettingsState.backendUrl
    LaunchedEffect(backendUrl) {
        MediaService.setBackendUrl(backendUrl)
    }

    var showSplash by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf(Screen.MEDIA) }

    MaterialTheme(colorScheme = colors) {
        AnimatedContent(
            targetState = showSplash,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "splashToHome"
        ) { splash ->
            if (splash) {
                SplashScreen(onFinish = { showSplash = false })
            } else {
                when (screen) {
                    Screen.MEDIA -> MediaListScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = { screen = Screen.SETTINGS },
                        onNavigateToAlbums = { screen = Screen.ALBUM }
                    )
                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MEDIA })
                    Screen.ALBUM -> AlbumScreen(
                        viewModel = viewModel,
                        onBack = { screen = Screen.MEDIA }
                    )
                }
            }
        }
    }
}

/** 顶层屏幕路由。 */
private enum class Screen { MEDIA, SETTINGS, ALBUM }
