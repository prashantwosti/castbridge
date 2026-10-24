package app.prashant.castbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colours, shared with the TV app and icon (blue → violet).
val BrandBlue = Color(0xFF3D7BFF)
val BrandViolet = Color(0xFF7A3DFF)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF1F3F86),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC6B3FF),
    secondaryContainer = Color(0xFF3B2A6E),
    onSecondaryContainer = Color(0xFFE8DDFF),
    background = Color(0xFF0E1018),
    onBackground = Color(0xFFE3E4EE),
    surface = Color(0xFF0E1018),
    onSurface = Color(0xFFE3E4EE),
    surfaceVariant = Color(0xFF242838),
    onSurfaceVariant = Color(0xFFB7BAD0),
    surfaceContainer = Color(0xFF171A26),
    surfaceContainerHigh = Color(0xFF1E2230),
    outline = Color(0xFF6D7196),
    outlineVariant = Color(0xFF34384E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001946),
    secondary = BrandViolet,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF22005D),
    background = Color(0xFFFAF9FF),
    surface = Color(0xFFFAF9FF),
    surfaceVariant = Color(0xFFE2E2EE),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainer = Color(0xFFEEEDF6),
    surfaceContainerHigh = Color(0xFFE8E7F1),
    outlineVariant = Color(0xFFC6C6D0),
)

@Composable
fun CastBridgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
