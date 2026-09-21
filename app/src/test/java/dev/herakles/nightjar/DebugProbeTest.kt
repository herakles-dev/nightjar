package dev.herakles.nightjar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for the `COVERT_DEBUG` JSON shape (task #18, spec.md "Runtime Verification
 * Surface"). Exercises [toJson] directly against hand-built [DebugProbe.ProbeState] values —
 * this is the JSON-serialization fallback verification path called out in the task brief for
 * when the `hek` bridge isn't reachable to confirm live logcat output.
 *
 * Deliberately avoids `org.json`/Robolectric: [toJson] has no Android dependency (see its
 * KDoc), and plain string assertions keep this test runnable under any Gradle unit-test
 * variant without a stub/shadow concern (android.jar's `org.json` classes are not real
 * implementations outside Robolectric).
 */
class DebugProbeTest {

    @Test
    fun `empty state serializes all four top-level fields as null`() {
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":null}",
            toJson(DebugProbe.ProbeState()),
        )
    }

    @Test
    fun `successful encode result serializes module, operation, success, timestamp`() {
        val state = DebugProbe.ProbeState(
            lastEncodeDecode = DebugProbe.EncodeDecodeResult(
                module = ModuleId.ACOUSTIC_MODEM,
                operation = DebugProbe.Operation.ENCODE,
                success = true,
                timestampMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":{\"module\":\"ACOUSTIC_MODEM\",\"operation\":\"ENCODE\"," +
                "\"success\":true,\"timestamp_ms\":1700000000000}," +
                "\"detector_confidence\":null,\"screen\":null,\"stored_media\":null}",
            toJson(state),
        )
    }

    @Test
    fun `failed decode result serializes success as false`() {
        val state = DebugProbe.ProbeState(
            lastEncodeDecode = DebugProbe.EncodeDecodeResult(
                module = ModuleId.IMAGE_LSB_CODEC,
                operation = DebugProbe.Operation.DECODE,
                success = false,
                timestampMs = 42L,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":{\"module\":\"IMAGE_LSB_CODEC\",\"operation\":\"DECODE\"," +
                "\"success\":false,\"timestamp_ms\":42}," +
                "\"detector_confidence\":null,\"screen\":null,\"stored_media\":null}",
            toJson(state),
        )
    }

    @Test
    fun `detector confidence serializes module, confidence, timestamp`() {
        val state = DebugProbe.ProbeState(
            detector = DebugProbe.DetectorState(
                module = ModuleId.ACOUSTIC_DETECTOR,
                confidence = 0.73f,
                timestampMs = 999L,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":null," +
                "\"detector_confidence\":{\"module\":\"ACOUSTIC_DETECTOR\",\"confidence\":0.73," +
                "\"timestamp_ms\":999},\"screen\":null,\"stored_media\":null}",
            toJson(state),
        )
    }

    @Test
    fun `no active detector serializes as an explicit null, not an omitted key`() {
        val json = toJson(DebugProbe.ProbeState(screen = "picker"))
        assertEquals(true, json.contains("\"detector_confidence\":null"))
    }

    @Test
    fun `screen serializes as a plain string`() {
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null," +
                "\"screen\":\"module_stub:IMAGE_STEGANOGRAPHY\",\"stored_media\":null}",
            toJson(DebugProbe.ProbeState(screen = "module_stub:IMAGE_STEGANOGRAPHY")),
        )
    }

    @Test
    fun `all four fields populated at once round-trip independently`() {
        val state = DebugProbe.ProbeState(
            lastEncodeDecode = DebugProbe.EncodeDecodeResult(
                ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.DECODE, true, 111L,
            ),
            detector = DebugProbe.DetectorState(ModuleId.ACOUSTIC_DETECTOR, 0.5f, 222L),
            screen = "module_stub:ACOUSTIC_MODEM",
            storedMedia = DebugProbe.StoredMediaState(
                recordCount = 7,
                recordsWithMediaCount = 5,
                totalMediaBytes = 4_200_000L,
                orphanFileCount = 1,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":{\"module\":\"ACOUSTIC_MODEM\",\"operation\":\"DECODE\"," +
                "\"success\":true,\"timestamp_ms\":111}," +
                "\"detector_confidence\":{\"module\":\"ACOUSTIC_DETECTOR\",\"confidence\":0.5," +
                "\"timestamp_ms\":222}," +
                "\"screen\":\"module_stub:ACOUSTIC_MODEM\"," +
                "\"stored_media\":{\"record_count\":7,\"records_with_media_count\":5," +
                "\"total_media_bytes\":4200000,\"orphan_file_count\":1}}",
            toJson(state),
        )
    }

    @Test
    fun `quotes, backslashes and newlines in a screen name are escaped for valid JSON`() {
        val raw = "weird \"name\" with \\backslash\\ and newline\nhere"
        val expectedEscaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        val json = toJson(DebugProbe.ProbeState(screen = raw))

        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null," +
                "\"screen\":\"$expectedEscaped\",\"stored_media\":null}",
            json,
        )
    }

    // --- stored_media (v4 addition, gate-20 probe contract) ---

    @Test
    fun `stored media serializes record count, media count, total bytes, orphan count`() {
        val state = DebugProbe.ProbeState(
            storedMedia = DebugProbe.StoredMediaState(
                recordCount = 12,
                recordsWithMediaCount = 9,
                totalMediaBytes = 480_000L,
                orphanFileCount = 0,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":{\"record_count\":12,\"records_with_media_count\":9," +
                "\"total_media_bytes\":480000,\"orphan_file_count\":0}}",
            toJson(state),
        )
    }

    @Test
    fun `stored media with zero records and zero bytes serializes as real zeros, not null`() {
        val json = toJson(
            DebugProbe.ProbeState(
                storedMedia = DebugProbe.StoredMediaState(
                    recordCount = 0,
                    recordsWithMediaCount = 0,
                    totalMediaBytes = 0L,
                    orphanFileCount = 0,
                ),
            ),
        )
        assertEquals(
            true,
            json.contains(
                "\"stored_media\":{\"record_count\":0,\"records_with_media_count\":0," +
                    "\"total_media_bytes\":0,\"orphan_file_count\":0}",
            ),
        )
    }
}
