package net.homelab.labeler.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with Material You dynamic colour.
 *
 * On Android 12+ the palette is derived from the user's wallpaper, which is
 * what makes an app look native on a Pixel rather than merely tidy. The static
 * schemes below are the fallback for older devices - a warm neutral, chosen to
 * suit an app whose subject is ink on white paper.
 */

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF7B5800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA6),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6B5D3F),
    secondaryContainer = Color(0xFFF5E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    outline = Color(0xFF7F7667)
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFF6BE48),
    onPrimary = Color(0xFF412D00),
    primaryContainer = Color(0xFF5D4200),
    onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = Color(0xFFD8C4A0),
    secondaryContainer = Color(0xFF52452A),
    onSecondaryContainer = Color(0xFFF5E0BB),
    background = Color(0xFF131316),
    surface = Color(0xFF131316),
    surfaceVariant = Color(0xFF4D4639),
    onSurfaceVariant = Color(0xFFD0C5B4),
    outline = Color(0xFF998F80)
)

@Composable
fun LabelerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(colorScheme = colors, content = content)
}
