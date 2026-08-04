package dev.herakles.nightjar.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System sans-serif for now (Roboto on the Pixel 6a — the "system-ui-sans" fallback
// the doctrine allows). No Inter TTFs bundled in this task; whisper-voice-app bundles
// Inter (see its design/identity.md) and task #17 decides whether nightjar follows
// suit. Weights limited to 400/500/600 either way — no 300 (fragile on OLED), no
// 700+ (too heavy in dark mode).

private val base = TextStyle(fontFamily = FontFamily.Default)

// Three active type-scale positions for this task's surfaces:
//   displayLarge — "nightjar" wordmark, module name on the stub screen
//   bodyLarge    — module-picker row labels, stub-screen note
//   labelLarge   — the "back" affordance
val NightjarTypography = Typography(
    displayLarge = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    labelLarge = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),

    // Collapse remaining M3 slots to a bodyLarge-equivalent so nothing falls through
    // to an unstyled system default when a later task reaches for an unused slot.
    displayMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    displaySmall = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    headlineSmall = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleLarge = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)
