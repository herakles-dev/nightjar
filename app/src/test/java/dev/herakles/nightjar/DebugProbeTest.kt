package dev.herakles.nightjar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for the `COVERT_DEBUG` JSON shape. Exercises [toJson] directly against
 * hand-built [DebugProbe.ProbeState] values — this is the JSON-serialization fallback
 * verification path for when an adb connection isn't reachable to confirm live logcat output.
 *
 * Deliberately avoids `org.json`/Robolectric: [toJson] has no Android dependency (see its
 * KDoc), and plain string assertions keep this test runnable under any Gradle unit-test
 * variant without a stub/shadow concern (android.jar's `org.json` classes are not real
 * implementations outside Robolectric).
 */
class DebugProbeTest {

    @Test
    fun `empty state serializes all eight top-level fields as null`() {
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":null,\"last_send\":null,\"outgoing_file_count\":null," +
                "\"incoming\":null,\"trail\":null}",
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
                "\"detector_confidence\":null,\"screen\":null,\"stored_media\":null," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
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
                "\"detector_confidence\":null,\"screen\":null,\"stored_media\":null," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
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
                "\"timestamp_ms\":999},\"screen\":null,\"stored_media\":null," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
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
                "\"screen\":\"module_stub:IMAGE_STEGANOGRAPHY\",\"stored_media\":null," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
            toJson(DebugProbe.ProbeState(screen = "module_stub:IMAGE_STEGANOGRAPHY")),
        )
    }

    @Test
    fun `all eight fields populated at once round-trip independently`() {
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
            lastSend = DebugProbe.OutgoingSendState(
                technique = "IMAGE_STURDY",
                bytes = 51_200L,
                authority = "dev.herakles.nightjar.outgoing",
                timestampMs = 333L,
            ),
            outgoingFileCount = 2,
            incoming = DebugProbe.IncomingState(
                action = "SEND",
                sniffedType = "PNG",
                technique = "EXACT_LSB",
                outcome = "caught",
                timestampMs = 333L,
            ),
            trail = DebugProbe.TrailProbeState(
                currentStep = "humming",
                completedSteps = listOf("art"),
                skipped = false,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":{\"module\":\"ACOUSTIC_MODEM\",\"operation\":\"DECODE\"," +
                "\"success\":true,\"timestamp_ms\":111}," +
                "\"detector_confidence\":{\"module\":\"ACOUSTIC_DETECTOR\",\"confidence\":0.5," +
                "\"timestamp_ms\":222}," +
                "\"screen\":\"module_stub:ACOUSTIC_MODEM\"," +
                "\"stored_media\":{\"record_count\":7,\"records_with_media_count\":5," +
                "\"total_media_bytes\":4200000,\"orphan_file_count\":1}," +
                "\"last_send\":{\"technique\":\"IMAGE_STURDY\",\"bytes\":51200," +
                "\"authority\":\"dev.herakles.nightjar.outgoing\",\"timestamp_ms\":333}," +
                "\"outgoing_file_count\":2," +
                "\"incoming\":{\"action\":\"SEND\",\"sniffed_type\":\"PNG\"," +
                "\"technique\":\"EXACT_LSB\",\"outcome\":\"caught\",\"timestamp_ms\":333}," +
                "\"trail\":{\"current_step\":\"humming\",\"completed_steps\":[\"art\"]," +
                "\"skipped\":false}}",
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
                "\"screen\":\"$expectedEscaped\",\"stored_media\":null," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
            json,
        )
    }

    // --- stored_media (v4 addition) ---

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
                "\"total_media_bytes\":480000,\"orphan_file_count\":0}," +
                "\"last_send\":null,\"outgoing_file_count\":null,\"incoming\":null,\"trail\":null}",
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

    // --- last_send / outgoing_file_count (v6 addition) ---

    @Test
    fun `last send serializes technique, bytes, authority, timestamp`() {
        val state = DebugProbe.ProbeState(
            lastSend = DebugProbe.OutgoingSendState(
                technique = "AUDIO",
                bytes = 88_200L,
                authority = "dev.herakles.nightjar.outgoing",
                timestampMs = 1_700_000_000_500L,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":null," +
                "\"last_send\":{\"technique\":\"AUDIO\",\"bytes\":88200," +
                "\"authority\":\"dev.herakles.nightjar.outgoing\"," +
                "\"timestamp_ms\":1700000000500},\"outgoing_file_count\":null,\"incoming\":null," +
                "\"trail\":null}",
            toJson(state),
        )
    }

    @Test
    fun `outgoing file count serializes as a real zero, not null`() {
        val json = toJson(DebugProbe.ProbeState(outgoingFileCount = 0))
        assertEquals(true, json.contains("\"outgoing_file_count\":0"))
    }

    @Test
    fun `no send yet serializes last_send as an explicit null, not an omitted key`() {
        val json = toJson(DebugProbe.ProbeState(screen = "jar_shelf"))
        assertEquals(true, json.contains("\"last_send\":null"))
    }

    // --- incoming (v6 addition) ---

    @Test
    fun `incoming serializes action, sniffed type, technique, outcome for a caught firefly`() {
        val state = DebugProbe.ProbeState(
            incoming = DebugProbe.IncomingState(
                action = "SEND",
                sniffedType = "WAV",
                technique = "ACOUSTIC_MODEM",
                outcome = "caught",
                timestampMs = 555L,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":null,\"last_send\":null,\"outgoing_file_count\":null," +
                "\"incoming\":{\"action\":\"SEND\",\"sniffed_type\":\"WAV\"," +
                "\"technique\":\"ACOUSTIC_MODEM\",\"outcome\":\"caught\",\"timestamp_ms\":555}," +
                "\"trail\":null}",
            toJson(state),
        )
    }

    @Test
    fun `incoming with no technique serializes technique as an explicit null, not an omitted key`() {
        val json = toJson(
            DebugProbe.ProbeState(
                incoming = DebugProbe.IncomingState(
                    action = "VIEW",
                    sniffedType = "JPEG",
                    technique = null,
                    outcome = "squeezed",
                    timestampMs = 666L,
                ),
            ),
        )
        assertEquals(
            true,
            json.contains(
                "\"incoming\":{\"action\":\"VIEW\",\"sniffed_type\":\"JPEG\"," +
                    "\"technique\":null,\"outcome\":\"squeezed\",\"timestamp_ms\":666}",
            ),
        )
    }

    // --- trail (v6 addition) ---

    @Test
    fun `trail serializes current step, completed steps, and skipped`() {
        val state = DebugProbe.ProbeState(
            trail = DebugProbe.TrailProbeState(
                currentStep = "meadow",
                completedSteps = listOf("art", "humming", "singing"),
                skipped = false,
            ),
        )
        assertEquals(
            "{\"last_encode_decode\":null,\"detector_confidence\":null,\"screen\":null," +
                "\"stored_media\":null,\"last_send\":null,\"outgoing_file_count\":null," +
                "\"incoming\":null," +
                "\"trail\":{\"current_step\":\"meadow\"," +
                "\"completed_steps\":[\"art\",\"humming\",\"singing\"],\"skipped\":false}}",
            toJson(state),
        )
    }

    @Test
    fun `trail with no completed steps yet serializes as an empty array, not null`() {
        val json = toJson(
            DebugProbe.ProbeState(
                trail = DebugProbe.TrailProbeState(currentStep = "art", completedSteps = emptyList(), skipped = false),
            ),
        )
        assertEquals(
            true,
            json.contains("\"trail\":{\"current_step\":\"art\",\"completed_steps\":[],\"skipped\":false}"),
        )
    }

    @Test
    fun `a skipped trail with no current step serializes current_step as null and skipped as true`() {
        val json = toJson(
            DebugProbe.ProbeState(
                trail = DebugProbe.TrailProbeState(
                    currentStep = null,
                    completedSteps = listOf("art"),
                    skipped = true,
                ),
            ),
        )
        assertEquals(
            true,
            json.contains(
                "\"trail\":{\"current_step\":null,\"completed_steps\":[\"art\"],\"skipped\":true}",
            ),
        )
    }

    @Test
    fun `no trail progress yet serializes trail as an explicit null, not an omitted key`() {
        val json = toJson(DebugProbe.ProbeState(screen = "jar_shelf"))
        assertEquals(true, json.contains("\"trail\":null"))
    }
}
