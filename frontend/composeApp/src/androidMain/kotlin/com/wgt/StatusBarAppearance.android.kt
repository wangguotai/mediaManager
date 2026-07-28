package com.wgt

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Android 实现：通过 [WindowCompat] 控制状态栏图标外观。
 *
 * 浅色主题 → isAppearanceLightStatusBars = true（深色图标）
 * 暗色主题 → isAppearanceLightStatusBars = false（浅色图标）
 */
@Composable
actual fun applyStatusBarIconColor(isDark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
        }
    }
}
