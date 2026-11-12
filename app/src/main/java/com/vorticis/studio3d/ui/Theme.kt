package com.vorticis.studio3d.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF6E9BFF),
    onPrimary = Color(0xFF0B1A3D),
    primaryContainer = Color(0xFF243A72),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF9C7CFF),
    background = Color(0xFF101217),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF242833),
    onSurface = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFFA5ABBA),
)

@Composable
fun VorticisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
