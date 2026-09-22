package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P5 (on-device review, honesty). [fireflyImageContentDescription] is a plain `Boolean ->
 * String` builder (no Compose/Android types), so the exact spoken label the carrier image
 * exposes to TalkBack/UI automation is asserted here rather than eyeballed on device, matching
 * [ImageBitPlaneCaptionTest]'s own honesty check on the visible caption right next to it.
 */
class FireflyImageContentDescriptionTest {

    @Test
    fun `plain image branch names the image, not a claim about where the payload is`() {
        assertEquals("the image this firefly hid inside", fireflyImageContentDescription(showBitPlane = false))
    }

    @Test
    fun `bit-plane branch describes the color mapping, not where a payload bit lives`() {
        assertEquals(
            "this image's least-significant bit plane, white where a pixel's bit is 1, black where it's 0",
            fireflyImageContentDescription(showBitPlane = true),
        )
    }

    @Test
    fun `neither branch ever mentions a payload`() {
        // The honesty rule this function exists to enforce: a bright/white bit-plane pixel is
        // just a 1 bit, not evidence of a payload there -- the same deny-list intent as
        // ImageBitPlaneCaptionTest's "never implies the eye can spot a bright or dark patch".
        assertFalse(fireflyImageContentDescription(showBitPlane = false).contains("payload"))
        assertFalse(fireflyImageContentDescription(showBitPlane = true).contains("payload"))
    }
}
