package com.drinkwatch.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Lime = Color(0xFF4ADE80)
private val Deep = Color(0xFF1B1033)
private val Ink = Color(0xFF241B3A)
private val Cream = Color(0xFFFBF7F3)

private val LightColors = lightColorScheme(
    primary = Color(0xFF137A4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F2DA),
    onPrimaryContainer = Color(0xFF04220F),
    secondary = Color(0xFF6B4EA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF241B3A),
    tertiary = Color(0xFF9A5B00),
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E9E2),
    onSurfaceVariant = Color(0xFF554B5F),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = Color(0xFF04120A),
    primaryContainer = Color(0xFF12603C),
    onPrimaryContainer = Color(0xFFC8F2DA),
    secondary = Color(0xFFCDBDFF),
    onSecondary = Color(0xFF1B1033),
    secondaryContainer = Color(0xFF43316E),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = Color(0xFFFFB870),
    surface = Deep,
    onSurface = Color(0xFFF3EEF8),
    surfaceVariant = Color(0xFF2B2244),
    onSurfaceVariant = Color(0xFFCFC5DA),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DrinkWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
