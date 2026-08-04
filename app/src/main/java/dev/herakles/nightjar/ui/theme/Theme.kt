package dev.herakles.nightjar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Locked palette — no Material You, no dynamicDarkColorScheme(), no wallpaper binding.
// Dark-only: no light theme defined, no values-night/ override. isSystemInDarkTheme()
// is a footgun here; we hardcode dark. (android-designer doctrine, CLAUDE.md)

private val NightjarColorScheme = darkColorScheme(
    background = BgBase,
    surface = BgSurface,
    surfaceVariant = BgSurface,

    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,

    // "primary" is NOT an accent slot. AccentSignal (Color.kt, Task #17) is never
    // bound into the color scheme — mapping primary to TextPrimary keeps M3
    // components from auto-injecting cyan into button fills, focused outlines, or
    // checked states. AccentSignal only shows up through explicit `color =` calls
    // on the two status words it's reserved for (same discipline Relay's identity.md
    // documents for AccentRecord).
    primary = TextPrimary,
    onPrimary = BgBase,
    primaryContainer = BgSurface,
    onPrimaryContainer = TextPrimary,

    secondary = TextSecondary,
    onSecondary = BgBase,
    secondaryContainer = BgSurface,
    onSecondaryContainer = TextSecondary,

    // Tertiary unused — collapse to surface so nothing auto-assigned leaks through.
    tertiary = BgSurface,
    onTertiary = TextPrimary,

    error = Danger,
    onError = TextPrimary,
    errorContainer = BgSurface,
    onErrorContainer = Danger,

    outline = BorderDefault,
    outlineVariant = BorderDefault,

    scrim = BgBase,
    inverseSurface = TextPrimary,
    inverseOnSurface = BgBase,
    inversePrimary = BgBase,
)

@Composable
fun NightjarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightjarColorScheme,
        typography = NightjarTypography,
        content = content,
    )
}
