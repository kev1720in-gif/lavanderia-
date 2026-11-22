package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val IndustrialDarkColorScheme = darkColorScheme(
    primary = ElectricSkyBlue,
    onPrimary = ObsidianBackground,
    primaryContainer = DeepCobaltContainer,
    onPrimaryContainer = IceWhiteText,
    secondary = CobaltBlue,
    onSecondary = IceWhiteText,
    secondaryContainer = ElevatedNavySurface,
    onSecondaryContainer = IceWhiteText,
    tertiary = CyanAccent,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = IceWhiteText,
    surface = DeepNavySurface,
    onSurface = IceWhiteText,
    surfaceVariant = CardNavySurface,
    onSurfaceVariant = SlateMutedText,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = DeepNavySurface,
    surfaceContainer = CardNavySurface,
    surfaceContainerHigh = ElevatedNavySurface,
    surfaceContainerHighest = BorderNavy,
    outline = BorderNavy,
    outlineVariant = ColorTokens.OutlineSubtle,
    error = RoseError,
    onError = IceWhiteText,
    errorContainer = RoseContainer,
    onErrorContainer = IceWhiteText
)

private object ColorTokens {
    val OutlineSubtle = androidx.compose.ui.graphics.Color(0xFF1D2F4F)
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LavanderiaControlTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IndustrialDarkColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
