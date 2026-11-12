package com.vorticis.studio3d.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cores da família Vorticis: índigo/violeta do bloco do ícone + ciano do vórtice. */
private val Colors = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF06212B),
    primaryContainer = Color(0xFF3A2C86),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Color(0xFFB79CFF),
    tertiary = Color(0xFFFF8A3D),
    background = Color(0xFF0D0F1A),
    surface = Color(0xFF151827),
    surfaceVariant = Color(0xFF232842),
    onSurface = Color(0xFFE8EAF3),
    onSurfaceVariant = Color(0xFFA3A9C2),
    outline = Color(0xFF3A4060),
)

@Composable
fun VorticisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
