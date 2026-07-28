package com.wgt

import androidx.compose.runtime.Composable

/**
 * iOS 实现：no-op。状态栏样式由系统/Info.plist `UIStatusBarStyle` 管理，
 * Compose Multiplatform 目前无直接 API 覆盖，留空即可。
 */
@Composable
actual fun applyStatusBarIconColor(isDark: Boolean) {
    // no-op on iOS
}
