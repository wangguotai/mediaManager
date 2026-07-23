package com.wgt

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.wgt.media.MediaListScreen
import com.wgt.media.MediaViewModel

private val viewModel = MediaViewModel()

// 明确的浅色色板：替代 Material3 默认紫色，给出更中性、克制的主色调，
// 配合 enableEdgeToEdge 沉浸式，TopAppBar/Tab 背景 safeDrawing 延伸到状态栏。
private val LightColors = lightColorScheme(
    primary = Color(0xFF2C6E49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8DDBC),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4E6353),
    surface = Color(0xFFFBFCFB),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9BF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC3A1),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0B4F32),
    onPrimaryContainer = Color(0xFFA8DDBC),
    secondary = Color(0xFFB6CCB8),
    surface = Color(0xFF121412),
    onSurface = Color(0xFFE2E3E0),
    surfaceVariant = Color(0xFF404940),
    onSurfaceVariant = Color(0xFFC0C9BF),
    outline = Color(0xFF8A9389),
    outlineVariant = Color(0xFF404940)
)

@Composable
@Preview
fun App() {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        MediaListScreen(viewModel = viewModel)
    }
}
