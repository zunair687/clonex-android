package com.zee.clone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Zee Clone light theme: near-white background with a faint teal tint,
 * white cards, dark text, deep teal-green #00695C accent (FAB, + buttons,
 * badges). Based on the CloneX v3.0 light theme.
 */
private val ZeeCloneLightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFF4DB6AC),
    background = Color(0xFFF7FBFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAF3F1),
    onBackground = Color(0xFF14141A),
    onSurface = Color(0xFF14141A),
    onSurfaceVariant = Color(0xFF5A6B68),
    outline = Color(0xFFDCE8E6)
)

@Composable
fun ZeeCloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZeeCloneLightColors,
        content = content
    )
}
