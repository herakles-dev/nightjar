package dev.herakles.nightjar.modules.fireflyjar

import dev.herakles.nightjar.SturdyImageCarrier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task W2-2 (spec.md gate-33): "hide one in a photo"'s pure logic -- byte-count/capacity
 * validation (including multi-byte UTF-8). Task W2-7 removed this flow's own space-padding now
 * that [SturdyImageCarrier] carries variable-length messages itself (see
 * `SturdyImageCarrierTest.kt` for that codec's own round-trip coverage at non-64 lengths); this
 * flow now just hands the carrier the typed message's raw UTF-8 bytes. No Robolectric needed --
 * none of this touches a `Bitmap`/`Context`.
 */
class HideInPhotoFlowTest {

    // ==========================================================================================
    // hidePhotoByteCount -- multi-byte UTF-8
    // ==========================================================================================

    @Test
    fun `ascii-only message counts one byte per character`() {
        assertEquals(5, hidePhotoByteCount("hello"))
    }

    @Test
    fun `an accented character counts two bytes, not one`() {
        // 'e' with an acute accent (U+00E9) encodes as 2 bytes in UTF-8.
        assertEquals(5, hidePhotoByteCount("café"))
    }

    @Test
    fun `a CJK character counts three bytes`() {
        assertEquals(3, hidePhotoByteCount("日")) // U+65E5, three UTF-8 bytes
    }

    @Test
    fun `an emoji counts four bytes (a surrogate pair, one code point)`() {
        assertEquals(4, hidePhotoByteCount("😀")) // U+1F600, four UTF-8 bytes
    }

    @Test
    fun `empty message counts zero bytes`() {
        assertEquals(0, hidePhotoByteCount(""))
    }

    // ==========================================================================================
    // hidePhotoCapacityBytes
    // ==========================================================================================

    @Test
    fun `sturdy capacity is always the fixed PAYLOAD_BYTES, regardless of exact capacity`() {
        assertEquals(SturdyImageCarrier.PAYLOAD_BYTES, hidePhotoCapacityBytes(HideTechnique.STURDY, 12345))
        assertEquals(SturdyImageCarrier.PAYLOAD_BYTES, hidePhotoCapacityBytes(HideTechnique.STURDY, 0))
    }

    @Test
    fun `exact capacity passes the picked photo's own computed capacity through unchanged`() {
        assertEquals(500, hidePhotoCapacityBytes(HideTechnique.EXACT, 500))
        assertEquals(0, hidePhotoCapacityBytes(HideTechnique.EXACT, 0))
    }

    // ==========================================================================================
    // canHidePhotoMessage
    // ==========================================================================================

    @Test
    fun `a non-empty message within capacity can be hidden`() {
        assertTrue(canHidePhotoMessage(messageBytes = 10, capacityBytes = 64))
        assertTrue(canHidePhotoMessage(messageBytes = 64, capacityBytes = 64))
    }

    @Test
    fun `an empty message cannot be hidden even with capacity to spare`() {
        assertFalse(canHidePhotoMessage(messageBytes = 0, capacityBytes = 64))
    }

    @Test
    fun `a message over capacity cannot be hidden`() {
        assertFalse(canHidePhotoMessage(messageBytes = 65, capacityBytes = 64))
    }

    @Test
    fun `zero capacity never allows hiding anything`() {
        assertFalse(canHidePhotoMessage(messageBytes = 1, capacityBytes = 0))
    }
}
