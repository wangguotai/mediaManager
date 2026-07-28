package com.wgt

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * iOS 实现：暂无等价的系统动态取色 API，统一回退到内置中性色板。
 */
@Composable
actual fun resolveColorScheme(dark: Boolean): ColorScheme = fallbackColorScheme(dark)
