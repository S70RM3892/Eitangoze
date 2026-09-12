package com.eitangoze.ui.theme

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

// Ink on paper. A vocabulary app is read for an hour at a time, so the palette
// stays quiet and the only saturated colours mark right and wrong.
private val Ink = Color(0xFF13181F)
private val Paper = Color(0xFFFBFAF7)
private val Accent = Color(0xFF1F5F8B)
private val AccentDark = Color(0xFF7FB8DC)
private val Right = Color(0xFF2E7D4F)
private val Wrong = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBF5),
    onPrimaryContainer = Color(0xFF10344B),
    secondary = Color(0xFF5B6570),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEFEDE8),
    onSurfaceVariant = Color(0xFF474C52),
    error = Wrong,
    tertiary = Right,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0A2333),
    primaryContainer = Color(0xFF17435E),
    onPrimaryContainer = Color(0xFFD3E7F3),
    secondary = Color(0xFFA8B2BD),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6E2DC),
    surface = Color(0xFF171C21),
    onSurface = Color(0xFFE6E2DC),
    surfaceVariant = Color(0xFF2A2F35),
    onSurfaceVariant = Color(0xFFC2C7CD),
    error = Color(0xFFF2B8B5),
    tertiary = Color(0xFF7FCF9F),
)

/** Green for a correct answer, red for a wrong one, in either theme. */
object Marks {
    val correct: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    val wrong: Color @Composable get() = MaterialTheme.colorScheme.error
}

@Composable
fun EitangozeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    LocalContext.current
    MaterialTheme(colorScheme = colors, content = content)
}
