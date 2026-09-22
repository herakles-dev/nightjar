package dev.herakles.nightjar.incoming

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.picker.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task W2-1 (spec.md gate-31/32/38, INV-12): [incomingOutcomeCopyFor] must map every
 * [IncomingOutcome] to distinct, non-empty copy that holds the jar voice rules (lowercase, no
 * exclamation, no emoji -- design/firefly-jar-identity.md). Six cases, not five: INV-12's five
 * outcomes plus [IncomingOutcome.Squeezed]'s own two-[SqueezedContainer] split
 * (design/screen-flow.md's v6 "Receiving" section calls for "distinct copy for lossy image vs
 * compressed audio").
 *
 * Same Robolectric-backed "resolve the real resource text, then check it" shape
 * [dev.herakles.nightjar.trail.TrailVoiceTest] already established for testing `strings.xml`
 * copy without a Compose test harness (none exists in this project).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingOutcomeCopyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** One representative [IncomingOutcome] per distinct copy case this task's brief calls out
     *  by name -- ordered map so failure messages read in the same order as that brief. */
    private val samplesByLabel: Map<String, IncomingOutcome> = linkedMapOf(
        "caught" to IncomingOutcome.Caught(
            module = Module.AUDIO_STEGANOGRAPHY,
            technique = "PHASE_INVERSION",
            payload = "hello".encodeToByteArray(),
            carrierBytes = ByteArray(0),
            extension = "wav",
        ),
        "squeezed_image" to IncomingOutcome.Squeezed(SqueezedContainer.LOSSY_IMAGE),
        "squeezed_audio" to IncomingOutcome.Squeezed(SqueezedContainer.COMPRESSED_AUDIO),
        "damaged" to IncomingOutcome.Damaged(),
        "no_firefly" to IncomingOutcome.NoFirefly,
        "unsupported" to IncomingOutcome.Unsupported(),
    )

    /** Resolves [copy]'s body to real text the same way [IncomingScreen] itself does --
     *  [IncomingOutcome.Caught]'s body takes two format args (payload bytes, carrier channel);
     *  every other outcome's body takes none. */
    private fun resolvedBody(outcome: IncomingOutcome, copy: IncomingOutcomeCopy): String =
        if (outcome is IncomingOutcome.Caught) {
            context.getString(copy.bodyRes, outcome.payload.size, outcome.module.jarChannel)
        } else {
            context.getString(copy.bodyRes)
        }

    @Test
    fun everyOutcomeMapsToNonEmptyCopy() {
        for ((label, outcome) in samplesByLabel) {
            val copy = incomingOutcomeCopyFor(outcome)
            val title = context.getString(copy.titleRes)
            val body = resolvedBody(outcome, copy)
            assertTrue("$label title must not be blank", title.isNotBlank())
            assertTrue("$label body must not be blank", body.isNotBlank())
        }
    }

    @Test
    fun sixCasesMapToDistinctBodyCopy() {
        val bodies = samplesByLabel.mapValues { (_, outcome) ->
            resolvedBody(outcome, incomingOutcomeCopyFor(outcome))
        }
        val distinctBodies = bodies.values.toSet()
        assertEquals(
            "expected all ${bodies.size} outcome bodies to be distinct, got: $bodies",
            bodies.size,
            distinctBodies.size,
        )
    }

    @Test
    fun everyOutcomeCopyIsLowercase() {
        for ((label, outcome) in samplesByLabel) {
            val copy = incomingOutcomeCopyFor(outcome)
            val title = context.getString(copy.titleRes)
            val body = resolvedBody(outcome, copy)
            assertEquals("$label title must be lowercase: \"$title\"", title.lowercase(), title)
            assertEquals("$label body must be lowercase: \"$body\"", body.lowercase(), body)
        }
    }

    @Test
    fun everyOutcomeCopyHasNoExclamationMark() {
        for ((label, outcome) in samplesByLabel) {
            val copy = incomingOutcomeCopyFor(outcome)
            val title = context.getString(copy.titleRes)
            val body = resolvedBody(outcome, copy)
            assertFalse("$label title must not contain '!': \"$title\"", title.contains("!"))
            assertFalse("$label body must not contain '!': \"$body\"", body.contains("!"))
        }
    }

    @Test
    fun everyOutcomeCopyHasNoEmoji() {
        for ((label, outcome) in samplesByLabel) {
            val copy = incomingOutcomeCopyFor(outcome)
            val title = context.getString(copy.titleRes)
            val body = resolvedBody(outcome, copy)
            assertFalse("$label title must not contain an emoji: \"$title\"", containsEmoji(title))
            assertFalse("$label body must not contain an emoji: \"$body\"", containsEmoji(body))
        }
    }

    /** Same emoji-range check as [dev.herakles.nightjar.trail.TrailVoiceTest] -- this project has
     *  no shared test-support source set, so the small range table is duplicated rather than
     *  extracted for one more call site. Deliberately does not flag the em dash (U+2014, used in
     *  `receive_squeezed_audio_body`) or curly/straight quotes -- only emoji-or-pictograph-
     *  specific ranges. */
    private fun containsEmoji(text: String): Boolean =
        text.codePoints().anyMatch { codePoint -> EMOJI_RANGES.any { range -> codePoint in range } }

    private companion object {
        val EMOJI_RANGES: List<IntRange> = listOf(
            0x1F300..0x1FAFF, // misc symbols & pictographs, emoticons, transport, supplemental symbols
            0x2600..0x26FF, // misc symbols (sun, umbrella, etc.)
            0x2700..0x27BF, // dingbats
            0x2B00..0x2BFF, // misc symbols and arrows (stars, etc.)
            0x1F1E6..0x1F1FF, // regional indicator symbols (flag emoji)
            0xFE0F..0xFE0F, // variation selector-16 (emoji presentation)
            0x200D..0x200D, // zero-width joiner (emoji sequences)
        )
    }
}
