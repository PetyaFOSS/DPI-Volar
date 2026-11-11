package com.example.dpi_volar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = WarmBrown,
    onPrimary = Color.White,
    primaryContainer = WarmBrownContainer,
    onPrimaryContainer = WarmBrownDeep,
    secondary = WarmBrownLight,
    onSecondary = Color.White,
    secondaryContainer = SoftBeige,
    onSecondaryContainer = WarmBrownDeep,
    tertiary = AccentGreen,
    onTertiary = Color.White,
    tertiaryContainer = AccentGreenContainer,
    onTertiaryContainer = AccentGreenDark,
    background = CreamBackground,
    onBackground = WarmBrownDark,
    surface = CreamSurface,
    onSurface = WarmBrownDark,
    surfaceVariant = CreamSurfaceVariant,
    onSurfaceVariant = WarmBrownLight,
    outline = OutlineWarm,
    outlineVariant = SoftBeige,
    error = ErrorWarm,
    onError = Color.White,
    errorContainer = ErrorContainerWarm,
    onErrorContainer = OnErrorContainerWarm
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = WarmBrownDeep,
    onPrimaryContainer = DarkPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnBackground,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiaryContainer,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkSurfaceVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer
)

@Composable
fun DPIVolarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
