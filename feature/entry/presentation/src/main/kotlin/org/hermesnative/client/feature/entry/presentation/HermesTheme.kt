package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF4E5D8C),
        onPrimary = Color.White,
        secondary = Color(0xFF5D5F71),
        background = Color(0xFFF9F9FF),
        surface = Color(0xFFF9F9FF),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFB9C4FF),
        onPrimary = Color(0xFF1F2A58),
        secondary = Color(0xFFC5C5DC),
        background = Color(0xFF121318),
        surface = Color(0xFF121318),
    )

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
