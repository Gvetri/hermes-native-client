package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Applies the Hermes design tokens for the current theme mode and follows the
 * system setting by default. Pass an explicit [darkTheme] value only where a
 * deliberate light or dark contract is required, such as a screenshot test.
 */
@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) HermesDarkTokens else HermesLightTokens
    CompositionLocalProvider(LocalHermesDesignTokens provides tokens) {
        MaterialTheme(
            colorScheme = tokens.colorScheme,
            typography = tokens.typography,
            shapes = tokens.shapes,
            content = content,
        )
    }
}
