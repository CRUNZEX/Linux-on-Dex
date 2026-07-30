package com.crunzex.linuxondex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One UI 8.5-inspired design tokens.
 *
 * Not a pixel clone of Samsung's private SESL widgets — the goal is the same
 * visual language: vivid blue accent, very round corners, grouped cards on a
 * grey canvas, and a large lazy-collapsing title.
 */
object OneUiPalette {
    val Blue = Color(0xFF0381FE)
    val BlueDark = Color(0xFF3D9BFF)
    val LightCanvas = Color(0xFFF6F6F6)
    val LightCard = Color(0xFFFFFFFF)
    val DarkCanvas = Color(0xFF010101)
    val DarkCard = Color(0xFF17171A)
    val LightTextPrimary = Color(0xFF010101)
    val DarkTextPrimary = Color(0xFFFAFAFA)
    val LightTextSecondary = Color(0xFF75757D)
    val DarkTextSecondary = Color(0xFF9C9CA3)
    val SuccessGreen = Color(0xFF1AA260)
    val WarningOrange = Color(0xFFF5891D)
    val ErrorRed = Color(0xFFE2442F)
}

private val LightColors = lightColorScheme(
    primary = OneUiPalette.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEDFF),
    onPrimaryContainer = Color(0xFF00325E),
    background = OneUiPalette.LightCanvas,
    onBackground = OneUiPalette.LightTextPrimary,
    surface = OneUiPalette.LightCard,
    onSurface = OneUiPalette.LightTextPrimary,
    surfaceVariant = Color(0xFFEDEDF0),
    onSurfaceVariant = OneUiPalette.LightTextSecondary,
    error = OneUiPalette.ErrorRed,
    outlineVariant = Color(0xFFE3E3E8),
)

private val DarkColors = darkColorScheme(
    primary = OneUiPalette.BlueDark,
    onPrimary = Color(0xFF00325E),
    primaryContainer = Color(0xFF0B3766),
    onPrimaryContainer = Color(0xFFD6E8FF),
    background = OneUiPalette.DarkCanvas,
    onBackground = OneUiPalette.DarkTextPrimary,
    surface = OneUiPalette.DarkCard,
    onSurface = OneUiPalette.DarkTextPrimary,
    surfaceVariant = Color(0xFF232329),
    onSurfaceVariant = OneUiPalette.DarkTextSecondary,
    error = Color(0xFFFF6B55),
    outlineVariant = Color(0xFF2C2C33),
)

/** One UI uses much rounder corners than stock Material. */
private val OneUiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val OneUiTypography = Typography(
    // Large collapsed-header title.
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LinuxOnDexTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        shapes = OneUiShapes,
        typography = OneUiTypography,
        content = content,
    )
}
