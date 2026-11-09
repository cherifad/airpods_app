package com.airbuds.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EFFF),
    onPrimaryContainer = Color(0xFF00315C),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E7FF),
    onSecondaryContainer = Color(0xFF1B1A52),
    tertiary = Color(0xFF30B0C7),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = CardLight,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFE5E5EA),
    error = AccentRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004E6E),
    onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFA7A5FF),
    onSecondary = Color(0xFF2A2A66),
    secondaryContainer = Color(0xFF3F3D7A),
    onSecondaryContainer = Color(0xFFE3E1FF),
    tertiary = Color(0xFF6FD6E8),
    onTertiary = Color(0xFF00363F),
    background = Ink,
    onBackground = Color(0xFFF5F5F7),
    surface = CardDark,
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF6961),
    onError = Color(0xFF3B0905),
)

private val BudsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun BudsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BudsTypography,
        shapes = BudsShapes,
        content = content,
    )
}
