package org.hermesnative.client.feature.entry.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The application-owned spacing scale. Shell surfaces use these steps instead of
 * literal values; the extracted Session surfaces still carry their original
 * literal spacing and move onto the scale as they migrate.
 */
@Immutable
data class HermesSpacing(
    val xs: Dp,
    val s: Dp,
    val m: Dp,
    val l: Dp,
    val xl: Dp,
)

/**
 * The application-owned elevation scale. [raised] marks the active conversation
 * pane on larger displays; every other surface stays on the base content plane.
 */
@Immutable
data class HermesElevation(
    val raised: Dp,
)

/**
 * The complete local design-token set for one theme mode: color, typography,
 * spacing, shape, and elevation. [HermesLightTokens] and [HermesDarkTokens] are
 * the two designed modes; the shell follows the system setting by default.
 */
@Immutable
data class HermesDesignTokens(
    val colorScheme: ColorScheme,
    val typography: Typography,
    val spacing: HermesSpacing,
    val shapes: Shapes,
    val elevation: HermesElevation,
)

private val HermesTypography = Typography()

private val HermesShapes = Shapes()

private const val LIGHT_PRIMARY_COLOR = 0xFF4E5D8C
private const val LIGHT_SECONDARY_COLOR = 0xFF5D5F71
private const val LIGHT_BACKGROUND_COLOR = 0xFFF9F9FF
private const val LIGHT_SURFACE_COLOR = 0xFFF9F9FF
private const val DARK_PRIMARY_COLOR = 0xFFB9C4FF
private const val DARK_ON_PRIMARY_COLOR = 0xFF1F2A58
private const val DARK_SECONDARY_COLOR = 0xFFC5C5DC
private const val DARK_BACKGROUND_COLOR = 0xFF121318
private const val DARK_SURFACE_COLOR = 0xFF121318

private val HermesLightColorScheme =
    lightColorScheme(
        primary = Color(LIGHT_PRIMARY_COLOR),
        onPrimary = Color.White,
        secondary = Color(LIGHT_SECONDARY_COLOR),
        background = Color(LIGHT_BACKGROUND_COLOR),
        surface = Color(LIGHT_SURFACE_COLOR),
    )

private val HermesDarkColorScheme =
    darkColorScheme(
        primary = Color(DARK_PRIMARY_COLOR),
        onPrimary = Color(DARK_ON_PRIMARY_COLOR),
        secondary = Color(DARK_SECONDARY_COLOR),
        background = Color(DARK_BACKGROUND_COLOR),
        surface = Color(DARK_SURFACE_COLOR),
    )

private val HermesSpacingScale =
    HermesSpacing(
        xs = 4.dp,
        s = 8.dp,
        m = 12.dp,
        l = 16.dp,
        xl = 24.dp,
    )

private val HermesElevationScale =
    HermesElevation(
        raised = 3.dp,
    )

val HermesLightTokens: HermesDesignTokens =
    HermesDesignTokens(
        colorScheme = HermesLightColorScheme,
        typography = HermesTypography,
        spacing = HermesSpacingScale,
        shapes = HermesShapes,
        elevation = HermesElevationScale,
    )

val HermesDarkTokens: HermesDesignTokens =
    HermesDesignTokens(
        colorScheme = HermesDarkColorScheme,
        typography = HermesTypography,
        spacing = HermesSpacingScale,
        shapes = HermesShapes,
        elevation = HermesElevationScale,
    )

/**
 * The current token set. Provided by [HermesTheme] and read by shell surfaces
 * that need spacing or elevation steps.
 */
val LocalHermesDesignTokens = staticCompositionLocalOf { HermesLightTokens }
