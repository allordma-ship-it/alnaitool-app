package com.sonopulse.echoguide

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Material 3 colour scheme derived from the semantic tokens (no hex codes here). */
private fun colorSchemeOf(c: EchoColors) = darkColorScheme(
    primary = c.primary,
    onPrimary = c.onAccent,
    primaryContainer = c.primary.copy(alpha = 0.22f),
    onPrimaryContainer = c.primary,
    secondary = c.normal,
    onSecondary = c.onAccent,
    secondaryContainer = c.normal.copy(alpha = 0.22f),
    onSecondaryContainer = c.normal,
    tertiary = c.caution,
    onTertiary = c.onAccent,
    background = c.background,
    onBackground = c.text,
    surface = c.card,
    onSurface = c.text,
    surfaceVariant = c.cardHigh,
    onSurfaceVariant = c.textMuted,
    surfaceContainer = c.card,
    surfaceContainerLow = c.card,
    surfaceContainerHigh = c.cardHigh,
    surfaceContainerHighest = c.cardHigh,
    outline = c.border,
    outlineVariant = c.border,
    error = c.critical,
    onError = c.onAccent
)

@Composable
fun EchoScopeTheme(colors: EchoColors = DarkEchoColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialTheme(colorScheme = colorSchemeOf(colors), typography = Typography(), content = content)
    }
}

// Short aliases used throughout the composables; every one reads the current theme.
internal val EchoBackground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.background
internal val EchoSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.card
internal val EchoSurfaceHigh: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.cardHigh
internal val EchoOutline: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.border
internal val EchoPrimary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.primary
internal val EchoNormal: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.normal
internal val EchoCaution: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.caution
internal val EchoCritical: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.critical
internal val EchoSky: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.info
internal val EchoText: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.text
internal val EchoMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.textMuted
internal val EchoOnAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.onAccent
internal val AtlasCard: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.atlasCard
