package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6 addition. [audioSpectrogramContentDescription]
 * delegates to [audioSpectrogramCaption] for everything past the measured duration prefix, so
 * these tests assert the exact composed string against that same function's real output rather
 * than a duplicated literal -- the delegation itself is what guarantees this spoken label can
 * never claim more than [audioSpectrogramCaption]'s own visible text does (v5/v6 honesty rules).
 */
class AudioSpectrogramContentDescriptionTest {

    @Test
    fun `prefixes the measured duration onto the technique's own caption text, verbatim`() {
        for (technique in listOf("MFSK", null, "SPECTROGRAM_LSB", "PHASE_INVERSION")) {
            val description = audioSpectrogramContentDescription(technique, durationSeconds = 5.0)
            assertEquals(
                "spectrogram of a 5.0 second clip. ${audioSpectrogramCaption(technique).text}",
                description,
            )
        }
    }

    @Test
    fun `duration is formatted to exactly one decimal place, rounded not truncated`() {
        assertTrue(
            audioSpectrogramContentDescription("MFSK", durationSeconds = 2.0)
                .startsWith("spectrogram of a 2.0 second clip."),
        )
        assertTrue(
            audioSpectrogramContentDescription("MFSK", durationSeconds = 3.256)
                .startsWith("spectrogram of a 3.3 second clip."),
        )
    }

    @Test
    fun `an unrecognized technique still returns a well-formed sentence, never a crash`() {
        // audioSpectrogramCaption's else branch (unreachable in production today, present anyway
        // per that function's own KDoc) returns an empty caption text -- this must degrade to
        // "just the duration", never throw, for a technique this app doesn't actually emit.
        assertEquals(
            "spectrogram of a 1.0 second clip. ",
            audioSpectrogramContentDescription("SOMETHING_ELSE", durationSeconds = 1.0),
        )
    }

    @Test
    fun `never claims genuine visibility beyond what the technique's own caption states`() {
        // SPECTROGRAM_LSB's caption is the one honesty-sensitive case: it must keep
        // saying "most of the payload is nudges under 1.8 db a spectrogram can't show", never
        // drop that qualifier just because duration got prefixed onto it.
        val description = audioSpectrogramContentDescription("SPECTROGRAM_LSB", durationSeconds = 5.0)
        assertTrue(description.contains("a spectrogram can't show"))
    }
}
