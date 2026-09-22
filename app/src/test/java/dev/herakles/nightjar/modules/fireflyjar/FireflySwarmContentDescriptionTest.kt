package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3 (on-device review, accessibility). [fireflySwarmContentDescription] is a plain
 * `FireflyRecord` -> `String` builder (no Compose/Android types), so the exact spoken label a
 * swarm tile exposes to TalkBack/UI automation is asserted here rather than eyeballed on device,
 * matching [FireflyCapacityLineTest]'s own plain-JVM convention for this screen's other user-
 * visible strings.
 */
class FireflySwarmContentDescriptionTest {

    private fun record(
        direction: String,
        timestampMillis: Long = 1_000L,
        payloadSizeBytes: Int = 14,
    ) = FireflyRecord(
        moduleId = "AUDIO_STEGANOGRAPHY",
        direction = direction,
        timestampMillis = timestampMillis,
        payloadSizeBytes = payloadSizeBytes,
        technique = "SPECTROGRAM_LSB",
        payloadPreview = "preview",
    )

    @Test
    fun `created firefly reads as created, with its time and byte count`() {
        // 1_000L ms epoch -- HH:mm is locale-stable, so only the byte count/phrasing is asserted
        // exactly; the time is asserted as present in the same call by re-deriving it, the same
        // discipline CarrierInsightCaptionsTest's natsToDb() helper uses.
        //
        // W2-5 (design/riddle-trail.md § Verb rule): "you caught" -> "you created" for a
        // CREATED (embedded) firefly -- "catch" now describes receiving only.
        val description = fireflySwarmContentDescription(record(direction = "CREATED", payloadSizeBytes = 14))

        assertEquals(
            "firefly you created, ${formatFireflyTimeForTest(1_000L)}, 14 bytes",
            description,
        )
    }

    @Test
    fun `received firefly reads as spotted`() {
        val description = fireflySwarmContentDescription(record(direction = "RECEIVED", payloadSizeBytes = 22))

        assertEquals(
            "firefly you spotted, ${formatFireflyTimeForTest(1_000L)}, 22 bytes",
            description,
        )
    }

    @Test
    fun `a one-byte payload is singular, matching fireflyByteLabel elsewhere on this screen`() {
        val description = fireflySwarmContentDescription(record(direction = "CREATED", payloadSizeBytes = 1))

        assertEquals(true, description.endsWith(", 1 byte"))
    }

    /** Independent re-derivation of [formatFireflyTime] -- this test measures the same "HH:mm"
     *  formatting from first principles rather than importing the production private function,
     *  so a broken formatter in the production file can't also break this check. */
    private fun formatFireflyTimeForTest(timestampMillis: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestampMillis))
}
