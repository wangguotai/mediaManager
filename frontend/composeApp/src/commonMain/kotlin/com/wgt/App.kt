package com.wgt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.wgt.media.MediaListScreen
import com.wgt.media.MediaViewModel
import com.wgt.media.SplashScreen

private val viewModel = MediaViewModel()

/**
 * 应用根 Composable。
 *
 * 主题色板由各平台 [resolveColorScheme] 决定：
 * - Android 12+（API 31）取系统壁纸动态取色（dynamicColor）；
 * - iOS 及低版本 Android 回退到内置中性色板（[FallbackLightColors]/[FallbackDarkColors]）。
 *
 * 启动时先展示 [SplashScreen]（App 名称居中淡入，约 2s），完成后通过
 * [AnimatedContent] 交叉淡入淡出过渡到 [MediaListScreen]，避免主界面突入。
 */
@Composable
@Preview
fun App() {
    val isDark = isSystemInDarkTheme()
    val colors = resolveColorScheme(isDark)
    var showSplash by remember { mutableStateOf(true) }

    MaterialTheme(colorScheme = colors) {
        AnimatedContent(
            targetState = showSplash,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
            label = "splashToHome"
        ) { splash ->
            if (splash) {
                SplashScreen(onFinish = { showSplash = false })
            } else {
                MediaListScreen(viewModel = viewModel)
            }
        }
    }
}
