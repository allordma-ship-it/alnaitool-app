package com.sonopulse.echoguide

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Material 3 colour scheme derived from the semantic tokens (no hex codes here). */
private fun colorSchemeOf(c: EchoColors) = if (c.isDark) darkSchemeOf(c) else lightSchemeOf(c)

private fun lightSchemeOf(c: EchoColors) = lightColorScheme(
    primary = c.primaryFill,
    onPrimary = c.onAccent,
    primaryContainer = c.primary.copy(alpha = 0.12f),
    onPrimaryContainer = c.primary,
    secondary = c.normalFill,
    onSecondary = c.onAccent,
    secondaryContainer = c.normal.copy(alpha = 0.12f),
    onSecondaryContainer = c.normal,
    tertiary = c.cautionFill,
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

private fun darkSchemeOf(c: EchoColors) = darkColorScheme(
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

/**
 * Typography tuned per language. Arabic gets ≥ 1.45× line height and font padding so stacked
 * diacritics (tashkeel) and descenders are never clipped in chips and badges; Latin scripts keep
 * the Material 3 metrics.
 */
internal fun echoTypography(language: AppLanguage): Typography {
    val base = Typography()
    if (language != AppLanguage.AR) return base
    fun TextStyle.arabic(): TextStyle = copy(
        lineHeight = 1.45.em,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
    )
    return Typography(
        displayLarge = base.displayLarge.arabic(), displayMedium = base.displayMedium.arabic(), displaySmall = base.displaySmall.arabic(),
        headlineLarge = base.headlineLarge.arabic(), headlineMedium = base.headlineMedium.arabic(), headlineSmall = base.headlineSmall.arabic(),
        titleLarge = base.titleLarge.arabic(), titleMedium = base.titleMedium.arabic(), titleSmall = base.titleSmall.arabic(),
        bodyLarge = base.bodyLarge.arabic(), bodyMedium = base.bodyMedium.arabic(), bodySmall = base.bodySmall.arabic(),
        labelLarge = base.labelLarge.arabic(), labelMedium = base.labelMedium.arabic(), labelSmall = base.labelSmall.arabic()
    )
}

@Composable
fun EchoScopeTheme(
    colors: EchoColors = DarkEchoColors,
    language: AppLanguage = AppLanguage.EN,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialTheme(colorScheme = colorSchemeOf(colors), typography = echoTypography(language), content = content)
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
internal val EchoPrimaryFill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.primaryFill
internal val EchoNormalFill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.normalFill
internal val EchoCautionFill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.cautionFill
internal val EchoCriticalFill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.echoColors.criticalFill

/**
 * Wraps numbers, units and signs in Unicode LEFT-TO-RIGHT ISOLATE … POP DIRECTIONAL ISOLATE so
 * "−1.8 cm²" or "+2.35" keep their order inside Arabic (RTL) sentences, cards and badges.
 */
fun ltr(text: String): String = "\u2066$text\u2069"
