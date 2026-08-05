package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage D/4 (gate-19). Plain-JVM verification of the detail-screen capacity teaching string.
 * The three helpers are pure `Int`/`Long`/`Double` -> `String` formatters (no Compose/Android
 * types), so the exact user-visible claim ("hid N bytes in X MB · P% used") is asserted here
 * rather than eyeballed on device. Locale.US is baked into the helpers so these assertions are
 * stable regardless of the JVM's default locale.
 */
class FireflyCapacityLineTest {

    @Test
    fun `capacity line matches the canonical example exactly`() {
        // The string from the plan: 13 bytes in a 1.2 MB carrier is 0.001% used.
        assertEquals(
            "hid 13 bytes in 1.2 MB · 0.001% used",
            fireflyCapacityLine(payloadBytes = 13, mediaBytes = 1_200_000L),
        )
    }

    @Test
    fun `media size label uses SI KB and MB with a byte floor`() {
        assertEquals("1.2 MB", fireflyMediaSizeLabel(1_200_000L))
        assertEquals("480 KB", fireflyMediaSizeLabel(480_000L))
        assertEquals("900 bytes", fireflyMediaSizeLabel(900L))
        assertEquals("1 byte", fireflyMediaSizeLabel(1L))
    }

    @Test
    fun `percent formats sub-one-percent to three decimals and floors the vanishing case`() {
        assertEquals("0%", fireflyCapacityPercent(0.0))
        assertEquals("<0.001%", fireflyCapacityPercent(0.0004)) // rounds to 0.000 -> floored honestly
        assertEquals("0.118%", fireflyCapacityPercent(0.118))
        assertEquals("12.5%", fireflyCapacityPercent(12.5))
    }

    @Test
    fun `image carrier (tiny cover) reports a real non-zero percentage`() {
        // A 1-byte payload in an 11 KB bundled cover is 0.009% -- small but not vanishing.
        assertEquals(
            "hid 1 byte in 11 KB · 0.009% used",
            fireflyCapacityLine(payloadBytes = 1, mediaBytes = 11_000L),
        )
    }

    @Test
    fun `zero media bytes degrades to zero percent rather than dividing by zero`() {
        assertEquals(
            "hid 5 bytes in 0 bytes · 0% used",
            fireflyCapacityLine(payloadBytes = 5, mediaBytes = 0L),
        )
    }
}
