package com.devfahim00.netcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF57C8FF)
val AccentSoft = Color(0xFF5EEAD4)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06222F),
    secondary = AccentSoft,
    background = Color.Black,
    surface = Color(0xFF0A0E14),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB8C4D0)
)

@Composable
fun NetCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
