package org.hermesnative.client.feature.entry.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight")
class HermesThemeTokensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun light_and_dark_token_sets_expose_the_hermes_design_scales() {
        listOf(HermesLightTokens, HermesDarkTokens).forEach { tokens ->
            assertEquals(4.dp, tokens.spacing.xs)
            assertEquals(8.dp, tokens.spacing.s)
            assertEquals(12.dp, tokens.spacing.m)
            assertEquals(16.dp, tokens.spacing.l)
            assertEquals(24.dp, tokens.spacing.xl)
            assertEquals(3.dp, tokens.elevation.raised)
            assertNotNull(tokens.typography.headlineMedium)
            assertNotNull(tokens.typography.bodyLarge)
            assertNotNull(tokens.shapes.medium)
        }
        assertNotEquals(HermesLightTokens.colorScheme.background, HermesDarkTokens.colorScheme.background)
        assertNotEquals(HermesLightTokens.colorScheme.primary, HermesDarkTokens.colorScheme.primary)
    }

    @Test
    fun theme_follows_the_system_light_setting_by_default() {
        var background: Color? = null
        var spacingXl = 0.dp
        composeTestRule.setContent {
            HermesTheme {
                background = MaterialTheme.colorScheme.background
                spacingXl = LocalHermesDesignTokens.current.spacing.xl
            }
        }

        assertEquals(Color(0xFFF9F9FF), background)
        assertEquals(24.dp, spacingXl)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun theme_follows_the_system_dark_setting_by_default() {
        var background: Color? = null
        composeTestRule.setContent {
            HermesTheme {
                background = MaterialTheme.colorScheme.background
            }
        }

        assertEquals(Color(0xFF121318), background)
    }

    @Test
    fun explicit_theme_selection_overrides_the_system_setting_in_both_directions() {
        var darkBackground: Color? = null
        var lightBackground: Color? = null
        composeTestRule.setContent {
            HermesTheme(darkTheme = true) {
                darkBackground = MaterialTheme.colorScheme.background
            }
            HermesTheme(darkTheme = false) {
                lightBackground = MaterialTheme.colorScheme.background
            }
        }

        assertEquals(Color(0xFF121318), darkBackground)
        assertEquals(Color(0xFFF9F9FF), lightBackground)
    }

    @Test
    fun material_theme_and_the_token_local_expose_the_same_hermes_tokens() {
        var materialColorScheme: Color? = null
        var tokenColorScheme: Color? = null
        var materialTypography: String? = null
        var tokenTypography: String? = null
        var materialShapes: String? = null
        var tokenShapes: String? = null
        composeTestRule.setContent {
            HermesTheme {
                materialColorScheme = MaterialTheme.colorScheme.background
                tokenColorScheme = LocalHermesDesignTokens.current.colorScheme.background
                materialTypography = MaterialTheme.typography.headlineMedium.toString()
                tokenTypography = LocalHermesDesignTokens.current.typography.headlineMedium.toString()
                materialShapes = MaterialTheme.shapes.medium.toString()
                tokenShapes = LocalHermesDesignTokens.current.shapes.medium.toString()
            }
        }

        assertEquals(tokenColorScheme, materialColorScheme)
        assertEquals(tokenTypography, materialTypography)
        assertEquals(tokenShapes, materialShapes)
    }
}
