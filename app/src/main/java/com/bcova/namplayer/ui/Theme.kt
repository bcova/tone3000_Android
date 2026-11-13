package com.bcova.namplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark stage look with accents taken from the TONE3000 logo's yellow / red / blue.
private val Yellow = Color(0xFFFFEB3B)
private val Blue = Color(0xFF5C6BFF)
private val Red = Color(0xFFFF4D4D)

private val Colors = darkColorScheme(
    primary = Yellow,
    onPrimary = Color.Black,
    secondary = Blue,
    onSecondary = Color.White,
    tertiary = Red,
    error = Red,
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0E0E10),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF26262B),
    onSurfaceVariant = Color(0xFFB5B5BD),
    surfaceContainer = Color(0xFF1A1A1E),
    surfaceContainerHigh = Color(0xFF222227),
    surfaceContainerHighest = Color(0xFF2A2A30),
    outline = Color(0xFF4A4A52),
)

@Composable
fun NamPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
