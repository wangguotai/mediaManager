package com.wgt

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 跨平台主题入口：各平台 [actual] 决定是否使用系统动态取色（Android 12+），
 * 不支持的平台回退到下方中性配色。[App] 用统一签名取 [ColorScheme]，保持 commonMain 平台无关。
 *
 * @param dark 当前是否深色模式
 */
@Composable
expect fun resolveColorScheme(dark: Boolean): ColorScheme

/**
 * 明确的浅色色板：替代 Material3 默认紫色，给出更中性、克制的主色调，
 * 配合 enableEdgeToEdge 沉浸式，TopAppBar/Tab 背景 safeDrawing 延伸到状态栏。
 */
val FallbackLightColors = lightColorScheme(
    primary = Color(0xFF2C6E49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8DDBC),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4E6353),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE6D2),
    onSecondaryContainer = Color(0xFF0A1F13),
    tertiary = Color(0xFF38656A),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFFBFCFB),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFBFCFB),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9BF)
)

val FallbackDarkColors = darkColorScheme(
    primary = Color(0xFF8CC3A1),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0B4F32),
    onPrimaryContainer = Color(0xFFA8DDBC),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF222A23),
    secondaryContainer = Color(0xFF384538),
    onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA0CFD3),
    onTertiary = Color(0xFF003739),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE2E3E0),
    surface = Color(0xFF121412),
    onSurface = Color(0xFFE2E3E0),
    surfaceVariant = Color(0xFF404940),
    onSurfaceVariant = Color(0xFFC0C9BF),
    outline = Color(0xFF8A9389),
    // outlineVariant 与 surfaceVariant 区分开：用于细分隔线，略亮于 surface 但不明显。
    outlineVariant = Color(0xFF2A2E2A)
)

/** 平台不支持动态取色时使用的回退色板选择。 */
fun fallbackColorScheme(dark: Boolean): ColorScheme =
    if (dark) FallbackDarkColors else FallbackLightColors
