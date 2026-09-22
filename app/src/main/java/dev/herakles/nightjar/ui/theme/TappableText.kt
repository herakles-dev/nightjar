package dev.herakles.nightjar.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration

/**
 * The one shared tappable-vs-static affordance for the four/five technical screens under
 * `identity.md` (`ModulePicker`, `AcousticModemScreen`, `ImageStegoScreen`, `AudioStegoScreen`,
 * `DetectorScreen`). Owner report: on these screens, a full-width row rendering plain `Text` in
 * `TextPrimary`/`TextSecondary` gives the user no reliable way to tell "this responds to a tap"
 * from "this is just a label" — `StatusBlock`'s static result text and an enabled `ActionRow`'s
 * tappable label share the exact same color, and a disabled row already uses the same
 * `TextSecondary` a static secondary caption uses. Color was carrying two unrelated meanings
 * (selection state, and disabled-vs-enabled) *and* was expected to also carry a third
 * (tappable-vs-static) it was never reserved for.
 *
 * [withTapAffordance] adds exactly one new signal — a plain underline — reserved exclusively for
 * text the user can act on **right now**. It is never applied to static/informational text,
 * status words, or a row while `tappable` is false (typically because the row is disabled). This
 * mirrors the discipline `AccentSignal` already holds itself to (identity.md § Palette): one
 * signal, one meaning, applied everywhere that meaning is true and nowhere else.
 *
 * Why underline over the alternatives considered:
 * - **A trailing glyph** (e.g. a `›` appended to the label) was rejected. Half the rows this
 *   applies to are *selection* rows (`CoverRow`, `SettingOptionRow`, `SelectorRowWithInfo`'s
 *   option list) rather than navigation/action rows — a forward-chevron on "cover image: mosaic"
 *   would visually promise a drill-down that tapping it doesn't perform (it just selects the
 *   option in place). A single glyph meaning would have been dishonest on exactly half its
 *   applications, which is worse than the ambiguity it was meant to fix.
 * - **A new/reassigned color** was rejected outright per the accent-color precedent: `identity.md`
 *   justifies `AccentSignal` with a measured contrast ratio and two exhaustively-named states;
 *   this affordance needs to coexist with the *existing* selected/unselected (`TextPrimary`/
 *   `TextSecondary`) and enabled/disabled color logic every row already has, not replace or
 *   compete with it. Introducing a third color axis on top of two already-overloaded ones would
 *   make the palette harder to reason about, not easier.
 * - **Underline** costs nothing new in the palette, needs no icon/vector asset, has no shadow/
 *   glow/gradient/motion, and is the one link-affordance convention old enough to predate every
 *   Material-3-sample tell this project is built to avoid (it is, if anything, a *plainer* signal
 *   than a Material `TextButton`'s implicit color-only affordance — which is the point).
 *
 * Disabled rows deliberately get no distinct treatment of their own beyond losing the underline —
 * they keep rendering exactly like static secondary text, on purpose. A disabled row is not
 * actionable *right now*, so visually agreeing with static text is the honest reading, not a gap.
 * A separate "disabled-but-not-quite-static" treatment would be a fourth signal for a state this
 * task's brief did not ask to solve, and doctrine's "one rough edge, not a decorated one" leans
 * against adding it speculatively.
 *
 * Scope: the four/five technical screens only. The Firefly Jar surface (`JarActionRow`,
 * `JarCoverRow`, `JarFlowRow`, `JarSelectorRow`, `JarOptionRow`, …) has its own doctrine
 * (`design/firefly-jar-identity.md`) and already signals its tappable rows with tinted fill +
 * border — this primitive is not applied there and should not be.
 */
fun TextStyle.withTapAffordance(tappable: Boolean = true): TextStyle =
    if (tappable) copy(textDecoration = TextDecoration.Underline) else this
