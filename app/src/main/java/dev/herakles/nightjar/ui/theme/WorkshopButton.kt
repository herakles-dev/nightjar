package dev.herakles.nightjar.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * **Owner-directed doctrine override, 2026-09-22.** The owner tried the underline-only tappable
 * affordance ([TappableText.kt], now deleted — see below) on-device and rejected it: "not enough.
 * make them look like buttons. ignore the ai tell rules theyre tok strict." This is an explicit,
 * direct instruction to build real button chrome on the five technical screens
 * (`ModulePicker`, `AcousticModemScreen`, `ImageStegoScreen`, `AudioStegoScreen`,
 * `DetectorScreen`), overriding this app's general "no cards" / no-button-fill-surface
 * doctrine for this specific, scoped need. This is a recorded exception, not a silent departure
 * from doctrine, and it does not touch the Firefly Jar surface, which already has its
 * own tinted-fill/border button language and was never under the anti-AI-tell restriction this
 * override lifts.
 *
 * [workshopButton] is the one shared modifier every converted row uses, replacing each row's own
 * hand-rolled `Modifier.height(48.dp).then(if (enabled) Modifier.clickable(...) else Modifier)`
 * pattern. Three visual tiers, chosen to resolve a real WCAG-AA failure (see below), not just for
 * variety:
 *
 * 1. **Filled** (`enabled = true, filled = true`, the default) — [WorkshopButtonFill] background +
 *    [BorderDefault] 1dp border. For primary actions (`ActionRow`'s verbs — transmit, embed, send,
 *    listen, save, share) and the *currently selected* option in a picker row (`CoverRow`,
 *    `SettingOptionRow`, `SelectorRowWithInfo`'s main selector). Text stays [TextPrimary], exactly
 *    as before this override — 12.88:1 against [WorkshopButtonFill], comfortably clears WCAG AA.
 * 2. **Outlined** (`enabled = true, filled = false`) — border only, transparent fill (the screen's
 *    own [BgBase] shows through). For every "back" link, `ModulePicker`'s "start the trail again"
 *    footer link, `ModulePicker`'s per-module label chip, an *unselected-but-available* picker
 *    option, and the small "?"/"close" info toggles. Text stays [TextSecondary].
 *
 *    This tier exists because of a real contrast failure, not aesthetic variety: [TextSecondary]
 *    against [WorkshopButtonFill] measures **4.08:1**, under WCAG AA's 4.5:1 floor for
 *    non-large text (`labelLarge`/`labelSmall` are 13sp, not "large text" by the WCAG definition).
 *    [TextSecondary] against [BgBase] measures **5.07:1**, which clears it. Rather than invent a
 *    second, lighter secondary-text color just to sit on the fill (a fourth color axis this
 *    project's own accent-color and tappable-affordance precedents both argue against),
 *    unselected/secondary rows go outline-only: they still read unambiguously as
 *    real, bordered buttons (the owner's actual ask), and the selected/primary vs.
 *    unselected/secondary text-color distinction this app already relied on stays intact and
 *    legible. This also happens to give free hierarchy — filled reads as "the current choice" or
 *    "the main action," outlined reads as "also available" or "secondary" — which is a bonus, not
 *    the reason it exists.
 * 3. **Disabled** (`enabled = false`) — [BgSurface] fill (one step lighter than [BgBase], already
 *    defined for exactly this kind of surface — see its own KDoc), no border, [TextSecondary] text
 *    (unchanged from before this override). 4.64:1 against [BgSurface], clears AA. A real, if
 *    dim, filled rectangle — visually distinct from both the filled/outlined enabled tiers *and*
 *    from plain static text (which has no fill at all), closing the gap the previous underline-only
 *    pass explicitly left open ("a disabled row is not actionable right now, so looking like static
 *    text is honest" — no longer true once every enabled row has real chrome; a disabled row that
 *    looked identical to a caption would now read as broken, not honest).
 *
 * [compact] shrinks the internal padding (10dp/8dp instead of 16dp/12dp) for the handful of
 * 1-2-character toggle buttons (`SelectorRowWithInfo`'s "?"/"close", `SectionLabelRow`'s
 * "?"/"close") that would look absurd stretched to the same padding as a full verb button. It does
 * NOT shrink the minimum touch target — [heightIn] still enforces 48dp regardless, per this
 * project's 1.3x-accessibility-scale minimum.
 *
 * What's unchanged by this override: no icon/emoji, no gradient, no shimmer/glow/blur, no
 * `androidx.compose.animation` dependency (this modifier adds zero motion — chrome is present or
 * absent per composition, exactly like the underline it replaces), dark-only, no success color,
 * mono-only-for-timestamps.
 */
private val WorkshopButtonShape = RoundedCornerShape(8.dp)
private val WorkshopButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val WorkshopButtonCompactPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

fun Modifier.workshopButton(
    enabled: Boolean = true,
    filled: Boolean = true,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
): Modifier = this
    .heightIn(min = 48.dp)
    .clip(WorkshopButtonShape)
    .background(
        when {
            !enabled -> BgSurface
            filled -> WorkshopButtonFill
            else -> Color.Transparent
        },
    )
    .then(if (enabled) Modifier.border(width = 1.dp, color = BorderDefault, shape = WorkshopButtonShape) else Modifier)
    .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    .padding(if (compact) WorkshopButtonCompactPadding else WorkshopButtonPadding)

/**
 * **Owner report, 2026-09-22: "back" stopped responding to taps on every technical screen.**
 * Every one of the five screens shares one structural shape: `Box(fillMaxSize()) { <back-link
 * Box>; Column(fillMaxSize().verticalScroll(...).padding(top = 56.dp, ...)) { ...screen body... } }`
 * — the back link composed FIRST, the scrollable content Column composed SECOND (and so drawn,
 * and hit-tested, on top of it). Compose dispatches pointer input to overlapping siblings in a
 * `Box` in reverse composition order (last-composed first), so the scrollable Column's own
 * `verticalScroll` gesture detector — whose bounds are the FULL `fillMaxSize()` box regardless of
 * its content's top padding, since padding only moves where content *draws*, not where the
 * scrollable's own pointer input listens — was intercepting the tap before it ever reached the
 * back link's `clickable` underneath it. Confirmed by direct on-device tap testing: identical
 * code in `ModulePicker.kt`'s plain, non-overlapping `Column` back link (no competing sibling)
 * responded correctly every time; the same code as a Box-sibling to an overlapping scrollable
 * Column did not, on any of the four affected screens.
 *
 * This pre-dates today's button chrome — the same Box/Column overlap shape existed with the
 * older plain-text back link too — but wasn't user-visible: the old link's own `Modifier.height
 * (48.dp)` (a firm bound) vs. this override's `workshopButton`-driven `heightIn(min = 48.dp)`
 * changed enough about the composable's measured bounds, in a screen already this close to a
 * genuine Compose overlapping-sibling hit-test race, to tip it from "worked by luck" to "doesn't."
 *
 * Fix: call this composable LAST inside each screen's outer `Box`, after the scrollable Column,
 * not first — the standard, deterministic way to win the tie in Compose (last-composed sibling
 * gets touch-dispatch priority). Shared here rather than duplicated per screen since all four
 * affected screens use the exact same "back" label and styling.
 */
@Composable
fun WorkshopBackLink(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 24.dp, top = 8.dp)
            .workshopButton(filled = false, onClick = onBack),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = "back", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
    }
}
