package ru.tubek.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFE53935)
private val RedDark = Color(0xFFB71C1C)
private val BgDark = Color(0xFF0F0F0F)
private val SurfaceDark = Color(0xFF1A1A1A)
private val BgLight = Color(0xFFF7F7F7)
private val SurfaceLight = Color(0xFFFFFFFF)

private val DarkColors = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    secondary = Color(0xFFFF8A80),
    background = BgDark,
    surface = SurfaceDark,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = RedDark,
    onPrimary = Color.White,
    secondary = Red,
    background = BgLight,
    surface = SurfaceLight,
    onBackground = Color(0xFF121212),
    onSurface = Color(0xFF121212),
    error = Color(0xFFB00020)
)

@Composable
fun TubekTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Приложение по умолчанию в тёмной теме (как привычный видео-клиент)
    val colors = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
