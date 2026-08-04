package dev.herakles.nightjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification for task #12 (Module 1 chi-square image steganalysis).
 *
 * Two groups of coverage:
 *  - Pure chi-square-distribution math (`chiSquareUpperTailP`), checked against well-known
 *    textbook critical values — this is what protects against the "p-value direction inverted"
 *    class of bug the KDoc explicitly calls out as a non-obvious sign convention.
 *  - End-to-end detection on task #11's two bundled sample cover images, both clean and after
 *    embedding a real payload via [ImageStegoCarrier], confirming the detector's confidence score
 *    is meaningfully higher for the embedded versions than the clean ones (the actual acceptance
 *    criterion for task #12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageSteganalysisTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val detector = ImageSteganalysis()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource $resId"
        }

    // --- Pure math: chi-square upper-tail p-value against known textbook critical values ---

    @Test
    fun chiSquareUpperTailPIsOneForAZeroStatistic() {
        assertEquals(1.0, detector.chiSquareUpperTailP(0.0, dof = 1), 1e-9)
        assertEquals(1.0, detector.chiSquareUpperTailP(-5.0, dof = 10), 1e-9)
    }

    @Test
    fun chiSquareUpperTailPMatchesTheDof1FivePercentCriticalValue() {
        // Textbook chi-square critical value: P(X >= 3.841 | dof=1) = 0.05.
        val p = detector.chiSquareUpperTailP(3.841, dof = 1)
        assertEquals(0.05, p, 0.002)
    }

    @Test
    fun chiSquareUpperTailPMatchesTheDof5FivePercentCriticalValue() {
        // Textbook chi-square critical value: P(X >= 11.070 | dof=5) = 0.05.
        val p = detector.chiSquareUpperTailP(11.070, dof = 5)
        assertEquals(0.05, p, 0.002)
    }

    @Test
    fun chiSquareUpperTailPMatchesTheDof127FivePercentCriticalValue() {
        // dof=127 is this detector's worst case (128 PoV pairs, all non-empty, minus 1).
        // Textbook chi-square critical value: P(X >= 154.3 | dof=127) = 0.05.
        val p = detector.chiSquareUpperTailP(154.3, dof = 127)
        assertEquals(0.05, p, 0.01)
    }

    @Test
    fun chiSquareUpperTailPDecreasesMonotonicallyWithTheStatistic() {
        val dof = 20
        val pSmall = detector.chiSquareUpperTailP(5.0, dof)
        val pMid = detector.chiSquareUpperTailP(20.0, dof)
        val pLarge = detector.chiSquareUpperTailP(60.0, dof)
        assertTrue("expected p($pSmall) > p($pMid) > p($pLarge)", pSmall > pMid && pMid > pLarge)
    }

    @Test
    fun chiSquarePValueForWindowIsHighForAPerfectlyEqualizedHistogram() {
        // Every pair (2i, 2i+1) appears exactly twice each -> h[2i] == h[2i+1] for all pairs ->
        // chi2 == 0 exactly -> p == 1.0. This is the synthetic "perfect embedding" case.
        val samples = IntArray(512) { it % 256 }
        val p = detector.chiSquarePValueForWindow(samples, 0, samples.size)
        assertEquals(1.0, p, 1e-9)
    }

    @Test
    fun chiSquarePValueForWindowIsLowForAHistogramConcentratedInOneValueOfEachPair() {
        // Every sample is an even value, so h[2i+1] == 0 for all i: maximally unequal pairs, the
        // synthetic "clearly not embedded" case.
        val samples = IntArray(512) { (it % 128) * 2 }
        val p = detector.chiSquarePValueForWindow(samples, 0, samples.size)
        assertTrue("expected a near-zero p-value for a maximally unequal PoV histogram, got $p", p < 0.01)
    }

    // --- End-to-end: task #11's two bundled sample cover images, clean vs. embedded ---

    @Test
    fun confidenceIsHigherAfterEmbeddingANearCapacityPayloadOnGradientCover() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = randomPayload(seed = 1, size = (carrier.maxPayloadBytes * 0.9).toInt())
        val stego = carrier.encode(payload)

        val cleanResult = detector.analyze(cover)
        val stegoResult = detector.analyze(stego)

        assertTrue(
            "expected stego confidence (${stegoResult.confidence}) > clean confidence (${cleanResult.confidence})",
            stegoResult.confidence > cleanResult.confidence,
        )
        assertTrue("expected the near-capacity embed to be flagged: $stegoResult", stegoResult.flagged)
    }

    @Test
    fun confidenceIsHigherAfterEmbeddingANearCapacityPayloadOnMosaicCover() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val carrier = ImageStegoCarrier(cover)
        val payload = randomPayload(seed = 2, size = (carrier.maxPayloadBytes * 0.9).toInt())
        val stego = carrier.encode(payload)

        val cleanResult = detector.analyze(cover)
        val stegoResult = detector.analyze(stego)

        assertTrue(
            "expected stego confidence (${stegoResult.confidence}) > clean confidence (${cleanResult.confidence})",
            stegoResult.confidence > cleanResult.confidence,
        )
        assertTrue("expected the near-capacity embed to be flagged: $stegoResult", stegoResult.flagged)
    }

    @Test
    fun estimatedPayloadBytesIsInTheRightBallparkForANearCapacityEmbed() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val carrier = ImageStegoCarrier(cover)
        val payloadSize = (carrier.maxPayloadBytes * 0.9).toInt()
        val payload = randomPayload(seed = 3, size = payloadSize)
        val stego = carrier.encode(payload)

        val result = detector.analyze(stego)

        val estimated = result.estimatedPayloadBytes
        assertTrue("expected a non-null length estimate for a near-capacity embed: $result", estimated != null)
        // Window granularity (>= MIN_WINDOW_SAMPLES channel samples per window) means this is an
        // estimate, not an exact figure -- assert it's the right order of magnitude, not exact.
        val frameBytes = payloadSize + 11 // header(7) + trailer(4), matching ImageStegoCarrier's framing
        assertTrue(
            "expected estimated payload ($estimated bytes) to be within a reasonable margin of the " +
                "actual embedded frame (~$frameBytes bytes)",
            estimated!! in (frameBytes / 2)..(frameBytes * 2),
        )
    }

    @Test
    fun detectsAModerateEmbeddingRateNearStegExposesBenchmarkAverage() {
        // library/06_detection_and_countermeasures.md cites StegExpose's own benchmark pool
        // averaging a 13.8% embedding rate -- exercise the detector at a comparable rate rather
        // than only at near-full capacity.
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val carrier = ImageStegoCarrier(cover)
        val payload = randomPayload(seed = 4, size = (carrier.maxPayloadBytes * 0.15).toInt())
        val stego = carrier.encode(payload)

        val cleanResult = detector.analyze(cover)
        val stegoResult = detector.analyze(stego)

        assertTrue(
            "expected stego confidence (${stegoResult.confidence}) > clean confidence (${cleanResult.confidence}) " +
                "even at a moderate ~15% embedding rate",
            stegoResult.confidence > cleanResult.confidence,
        )
    }

    // --- False-positive behavior on carriers with no embedded payload ---

    @Test
    fun cleanGradientCoverIsNotFlagged() {
        val cover = loadCover(R.drawable.stego_cover_gradient)
        val result = detector.analyze(cover)
        assertFalse("expected the untouched cover not to be flagged: $result", result.flagged)
    }

    @Test
    fun cleanMosaicCoverIsNotFlagged() {
        val cover = loadCover(R.drawable.stego_cover_mosaic)
        val result = detector.analyze(cover)
        assertFalse("expected the untouched cover not to be flagged: $result", result.flagged)
    }

    @Test
    fun aBlankAllZeroBitmapIsNotFlagged() {
        // Same deterministic "ordinary, un-embedded image" stand-in ImageStegoCarrierTest uses:
        // an all-zero bitmap's LSBs are all 0, which is maximally UNequal PoV pairs, not embedded.
        val blank = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val result = detector.analyze(blank)
        assertFalse("expected an all-zero bitmap not to be flagged: $result", result.flagged)
    }

    // --- Robustness on inputs too small for a full steganalysis window ---

    @Test
    fun aTinyBitmapDoesNotCrashAndIsNotFlagged() {
        val tiny = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val result = detector.analyze(tiny)
        assertFalse("expected a tiny bitmap not to be flagged: $result", result.flagged)
    }

    private fun randomPayload(seed: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return bytes
    }
}
