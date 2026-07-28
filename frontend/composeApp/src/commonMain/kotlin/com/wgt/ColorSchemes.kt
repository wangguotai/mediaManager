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
    // outlineVariant 提升对比度：原 0xFF2A2E2A 与 surface(0xFF121412) 差异仅 ~1.2:1，
    // 细分隔线几乎不可见。提升到 0xFF343934 后约 2:1，作为非文本装饰元素符合 M3 规范。
    outlineVariant = Color(0xFF343934)
)

/**
 * AMOLED 纯黑主题色板：在深色基础上将背景/表面改为纯黑 (#000000)，
 * 省 OLED 面板功耗（像素关闭）。surfaceVariant 保留极深灰用于卡片层级区分。
 * 强调色（primary/secondary/tertiary）沿用深色色板，保持品牌一致性。
 *
 * 使用方式：[App] 在 ThemeMode.AMOLED 时调用 [applyAmoledOverride] 覆盖任意
 * 深色 ColorScheme 的背景/表面字段，兼容动态取色（Android 12+ 的 accent 保留）。
 */
val FallbackAmoledColors = darkColorScheme(
    primary = Color(0xFF8CC3A1),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0B4F32),
    onPrimaryContainer = Color(0xFFA8DDBC),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF222A23),
    secondaryContainer = Color(0xFF2A352A),
    onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA0CFD3),
    onTertiary = Color(0xFF003739),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE2E3E0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE2E3E0),
    surfaceVariant = Color(0xFF1A1E1A),
    onSurfaceVariant = Color(0xFFC0C9BF),
    outline = Color(0xFF8A9389),
    outlineVariant = Color(0xFF2A2E2A)
)

/**
 * 将任意深色 [ColorScheme] 的背景/表面字段覆盖为 AMOLED 纯黑配置。
 *
 * 用于 Android 12+ 动态取色：保留系统壁纸衍生的 accent 色，
 * 仅把 background/surface 改为 #000000、surfaceVariant 改为极深灰，
 * 兼顾省电与动态色调。
 *
 * @param base 已解析的深色 ColorScheme（动态或回退）
 * @return 覆盖后的 ColorScheme
 */
fun applyAmoledOverride(base: ColorScheme): ColorScheme = base.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF1A1E1A)
)

/** 平台不支持动态取色时使用的回退色板选择。 */
fun fallbackColorScheme(dark: Boolean): ColorScheme =
    if (dark) FallbackDarkColors else FallbackLightColors