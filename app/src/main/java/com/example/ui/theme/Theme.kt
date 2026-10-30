package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CoastalDarkColorScheme = darkColorScheme(
    primary = LagoonTealDark,
    onPrimary = OnLagoonTealDark,
    primaryContainer = LagoonContainerDark,
    onPrimaryContainer = OnLagoonContainerDark,
    secondary = DriftwoodDark,
    onSecondary = OnDriftwoodDark,
    secondaryContainer = DriftwoodContainerDark,
    onSecondaryContainer = OnDriftwoodContainerDark,
    tertiary = CoralShellDark,
    onTertiary = OnCoralShellDark,
    tertiaryContainer = CoralContainerDark,
    onTertiaryContainer = OnCoralContainerDark,
    background = NightCoveBackground,
    onBackground = OnNightCoveBackground,
    surface = NightSandSurface,
    onSurface = OnNightSandSurface,
    surfaceVariant = NightWetSandVariant,
    onSurfaceVariant = OnNightWetSandVariant,
    outline = NightSandOutline
)

private val CoastalLightColorScheme = lightColorScheme(
    primary = LagoonTealLight,
    onPrimary = OnLagoonTealLight,
    primaryContainer = LagoonContainerLight,
    onPrimaryContainer = OnLagoonContainerLight,
    secondary = DriftwoodLight,
    onSecondary = OnDriftwoodLight,
    secondaryContainer = DriftwoodContainerLight,
    onSecondaryContainer = OnDriftwoodContainerLight,
    tertiary = CoralShellLight,
    onTertiary = OnCoralShellLight,
    tertiaryContainer = CoralContainerLight,
    onTertiaryContainer = OnCoralContainerLight,
    background = SandBackgroundLight,
    onBackground = OnSandBackgroundLight,
    surface = SandSurfaceLight,
    onSurface = OnSandSurfaceLight,
    surfaceVariant = WetSandSurfaceVariantLight,
    onSurfaceVariant = OnWetSandVariantLight,
    outline = SandOutlineLight
)

val CoastalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun BeaverBeachTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) CoastalDarkColorScheme else CoastalLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = CoastalShapes,
        content = content
    )
}
