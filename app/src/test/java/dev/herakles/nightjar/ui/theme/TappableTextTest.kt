package dev.herakles.nightjar.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner report: on the five technical screens (`ModulePicker`, `AcousticModemScreen`,
 * `ImageStegoScreen`, `AudioStegoScreen`, `DetectorScreen`), users could not tell tappable rows
 * apart from static text — both shared the same `TextPrimary`/`TextSecondary` colors.
 * [withTapAffordance] is the one shared primitive reserved exclusively for enabled, tappable
 * text (see its own KDoc for the full rationale and the alternatives rejected).
 */
class TappableTextTest {

    @Test
    fun `tappable text gets an underline`() {
        val style = TextStyle().withTapAffordance(tappable = true)
        assertEquals(TextDecoration.Underline, style.textDecoration)
    }

    @Test
    fun `non-tappable text gets no underline`() {
        val style = TextStyle().withTapAffordance(tappable = false)
        assertNull(style.textDecoration)
    }

    @Test
    fun `defaults to tappable for always-active rows that pass no argument`() {
        // Back links, the detector's listen-stop row, and info-toggle rows have no `enabled`
        // gate of their own -- they're tappable any time they're on screen, so the primitive's
        // default (no argument) must resolve the same as an explicit `true`.
        val style = TextStyle().withTapAffordance()
        assertEquals(TextDecoration.Underline, style.textDecoration)
    }

    @Test
    fun `only the decoration changes -- every other TextStyle property is preserved`() {
        // Regression guard for the actual bug class this primitive must not introduce: a row's
        // existing selected-vs-unselected color and weight are set independently at each call
        // site (e.g. CoverRow's `if (selected) TextPrimary else TextSecondary`) -- merging in the
        // affordance must never clobber them.
        val base = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)

        val tappable = base.withTapAffordance(tappable = true)
        assertEquals(base.fontSize, tappable.fontSize)
        assertEquals(base.fontWeight, tappable.fontWeight)
        assertEquals(base.color, tappable.color)
        assertEquals(TextDecoration.Underline, tappable.textDecoration)

        val static = base.withTapAffordance(tappable = false)
        assertEquals(base, static)
    }
}
