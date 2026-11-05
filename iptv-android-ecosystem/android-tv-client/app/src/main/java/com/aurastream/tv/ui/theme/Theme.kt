package com.aurastream.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val AuraColorScheme = darkColorScheme(
    primary = AuraColors.NeonCyan,
    onPrimary = AuraColors.OnNeon,
    primaryContainer = AuraColors.NeonCyanDim,
    onPrimaryContainer = AuraColors.NeonCyan,
    secondary = AuraColors.NeonCyan,
    onSecondary = AuraColors.OnNeon,
    background = AuraColors.Canvas,
    onBackground = AuraColors.TextPrimary,
    surface = AuraColors.Surface,
    onSurface = AuraColors.TextPrimary,
    surfaceVariant = AuraColors.SurfaceHigh,
    onSurfaceVariant = AuraColors.TextSecondary,
    border = AuraColors.Outline,
    borderVariant = AuraColors.Outline,
    error = AuraColors.Error,
    inverseSurface = AuraColors.TextPrimary,
    inverseOnSurface = AuraColors.Canvas,
)

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuraColorScheme,
        typography = AuraTypography,
        content = content,
    )
}
