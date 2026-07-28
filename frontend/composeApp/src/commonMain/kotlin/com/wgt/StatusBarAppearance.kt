package com.wgt

import androidx.compose.runtime.Composable

/**
 * 跨平台状态栏图标外观：控制状态栏图标用深色还是浅色。
 *
 * - Android：通过 [WindowCompat] 设置 `isAppearanceLightStatusBars`。
 * - iOS：no-op，状态栏样式由系统/Info.plist 管理。
 *
 * @param isDark 当前是否暗色主题。true → 浅色图标；false → 深色图标。
 */
@Composable
expect fun applyStatusBarIconColor(isDark: Boolean)
