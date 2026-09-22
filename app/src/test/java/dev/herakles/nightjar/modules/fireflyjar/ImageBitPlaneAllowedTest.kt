package dev.herakles.nightjar.modules.fireflyjar

import dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honesty fix (item 4): [imageBitPlaneAllowed] gates `FireflyDetailContent`'s
 * "image"/"bit-plane" toggle -- a bit-plane can't show where a sturdy firefly hides (it hides in
 * a cell's mean luminance via dither-QIM, never a pixel's least-significant bit), so a sturdy
 * firefly must not offer it. Pre-v6 exact fireflies (`technique == null`) are unchanged.
 */
class ImageBitPlaneAllowedTest {

    @Test
    fun `the sturdy technique is not allowed the bit-plane toggle`() {
        assertFalse(imageBitPlaneAllowed(SturdyImageFireflyDecoder.STURDY_TECHNIQUE))
    }

    @Test
    fun `a null technique -- the pre-v6 exact-LSB embed path -- keeps the toggle`() {
        assertTrue(imageBitPlaneAllowed(null))
    }

    @Test
    fun `any other technique value keeps the toggle`() {
        assertTrue(imageBitPlaneAllowed("PHASE_INVERSION"))
        assertTrue(imageBitPlaneAllowed("SPECTROGRAM_LSB"))
        assertTrue(imageBitPlaneAllowed("MFSK"))
    }
}
