package dev.herakles.nightjar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Low-capacity payload-size sweep extending ImageSteganalysisTest
 * coverage. ImageSteganalysisTest already measures confidence at about 15% and about 90% of
 * carrier capacity on both bundled sample cover images (see confidenceIsHigherAfterEmbedding...
 * and detectsAModerateEmbeddingRateNearStegExposesBenchmarkAverage). This file adds payload
 * sizes below 5% of capacity to see whether detection confidence drops meaningfully at low
 * embedding rates, and records the actual measured confidence honestly, even when detection is
 * weak or fails to separate from the clean baseline -- that outcome is a real finding about the
 * limits of the windowed chi-square PoV technique (see the ImageSteganalysis KDoc on window
 * granularity: MIN_WINDOW_SAMPLES=1024 channel samples per window, MIN_SUSTAIN_WINDOWS=3
 * consecutive windows), not a test failure to hide by tuning FLAG_THRESHOLD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageSteganalysisCapacitySweepTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val detector = ImageSteganalysis()

    private fun loadCover(resId: Int): Bitmap =
        checkNotNull(BitmapFactory.decodeResource(context.resources, resId)) {
            "failed to decode sample cover image resource " + resId
        }

    @Test
    fun lowCapacityPayloadSweepAcrossBothCoversReportsActualConfidence() {
        val covers = listOf("gradient" to R.drawable.stego_cover_gradient, "mosaic" to R.drawable.stego_cover_mosaic)
        val capacityFractions = listOf(0.01, 0.02, 0.04)
        val rows = mutableListOf<String>()
        var seed = 100L

        for ((coverName, resId) in covers) {
            val cover = loadCover(resId)
            val cleanResult = detector.analyze(cover)
            assertTrue("expected clean confidence in [0,1] for " + coverName, cleanResult.confidence in 0f..1f)

            for (fraction in capacityFractions) {
                val carrier = ImageStegoCarrier(cover)
                val payloadSize = (carrier.maxPayloadBytes * fraction).toInt().coerceAtLeast(1)
                val payload = randomPayload(seed, payloadSize)
                seed += 1
                val stego = carrier.encode(payload)
                val stegoResult = detector.analyze(stego)

                assertTrue("expected stego confidence in [0,1] for " + coverName + " at fraction " + fraction, stegoResult.confidence in 0f..1f)
                val estimated = stegoResult.estimatedPayloadBytes
                if (estimated != null) {
                    assertTrue("expected a non-negative payload estimate", estimated >= 0)
                }

                val separated = stegoResult.confidence > cleanResult.confidence
                val delta = stegoResult.confidence - cleanResult.confidence
                rows += "cover=" + coverName + " capacityPct=" + (fraction * 100) + " payloadBytes=" + payloadSize +
                    " cleanConfidence=" + cleanResult.confidence + " stegoConfidence=" + stegoResult.confidence +
                    " delta=" + delta + " separatedFromClean=" + separated + " flagged=" + stegoResult.flagged
            }
        }

        println("\n=== ImageSteganalysis low-capacity payload sweep ===")
        rows.forEach(::println)
    }

    private fun randomPayload(seed: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return bytes
    }
}
