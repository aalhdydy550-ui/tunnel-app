package dev.aalhdydy550.tunnel.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Hamo-style palette, reimplemented (not copied): the original app's
 * brand is a bright yellow primary (#F8CE29, dark #F1C827) on a very
 * light background (#F8F9FF), with dark-navy accents for the big
 * connect button. Yellow buttons carry dark text.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFF8CE29),
    onPrimary = Color(0xFF1C1C1C),
    primaryContainer = Color(0xFFFFF3BF),
    onPrimaryContainer = Color(0xFF1C1C1C),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201D),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFEDEFF7),
    onSurfaceVariant = Color(0xFF44474E),
    error = Color(0xFFD93025),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF8CE29),
    onPrimary = Color(0xFF1C1C1C),
    primaryContainer = Color(0xFF5C4D00),
    onPrimaryContainer = Color(0xFFFFF3BF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFB2DFDB),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF1B1D21),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF2A2D33),
    onSurfaceVariant = Color(0xFFC2C5CC),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF1C1C1C),
)

/** App theme. Follows the system dark/light setting; no dynamic color. */
@Composable
fun TunnelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
