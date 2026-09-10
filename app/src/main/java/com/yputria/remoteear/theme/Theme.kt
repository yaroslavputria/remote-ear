package com.yputria.remoteear.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The design palette, available anywhere under [RemoteEarTheme].
 *
 * Defaults to dark rather than throwing: a missing provider should render a *legible* screen, not
 * crash the only status surface this product has.
 */
val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun RemoteEarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette

    // Material components (Slider, ripples) read the ColorScheme, so it is derived from the same
    // palette instead of being a second, drifting source of colour. There is no dynamicColor
    // parameter on purpose - see RemoteEarPalette.
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceContainer,
            onSurfaceVariant = palette.onSurfaceVariant,
            background = palette.surface,
            onBackground = palette.onSurface,
            outline = palette.outline,
            error = palette.error,
            errorContainer = palette.errorContainer,
            onErrorContainer = palette.onErrorContainer,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceContainer,
            onSurfaceVariant = palette.onSurfaceVariant,
            background = palette.surface,
            onBackground = palette.onSurface,
            outline = palette.outline,
            error = palette.error,
            errorContainer = palette.errorContainer,
            onErrorContainer = palette.onErrorContainer,
        )
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
