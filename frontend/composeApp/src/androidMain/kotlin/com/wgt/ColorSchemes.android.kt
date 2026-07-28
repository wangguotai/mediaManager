package com.wgt

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android 实现：API 31+（Android 12）使用系统壁纸动态取色，
 * 低于 31 的版本回退到内置中性色板。
 *
 * 注意：[resolveColorScheme] 读取 [LocalContext]，故需在 @Composable 调用方上下文中使用。
 */
@Composable
actual fun resolveColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        fallbackColorScheme(dark)
    }
}
