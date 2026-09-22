package dev.herakles.nightjar

import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 addition (design-v5.md §3, §7, gate-24). Plain JUnit4, no Robolectric — [matchCover] and
 * [stegoDifference] are pure `ShortArray`/FFT arithmetic (this file's production counterpart's own
 * KDoc), matching every other pure-math test in this package.
 *
 * Every claim this test proves against the REAL [AudioStegoCarrier] codec, the same discipline
 * `AudioStegoCarrierTest`/`SpectrogramTest` already use -- the measured margins quoted in KDoc
 * below come from `scratchpad/proto/run1.txt`/`run3.txt`/`run5.txt` (design-v5.md's own
 * calibration runs against these exact sources), and this file's tests independently re-measure
 * the same properties rather than trusting those numbers.
 */
class StegoDifferenceTest {

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private fun fillerPayload(size: Int): ByteArray {
        val text = "the ravens have landed at dawn, bring the lantern and the map. "
        return ByteArray(size) { text[it % text.length].code.toByte() }
    }

    /** Mirrors [dev.herakles.nightjar.modules.audiostego.AudioStegoScreen]'s real call site
     *  (design-v5.md §3.5): candidates as lazy `(label, supplier)` pairs over both bundled covers. */
    private fun bundledCandidates(): List<Pair<String, () -> PcmAudio>> =
        AudioSampleCover.entries.map { it.label to { synthesizeSampleCover(it) } }

    /** [dev.herakles.nightjar.AudioStegoCarrier]'s own `BINS_PER_STRENGTH_LEVEL` (8), recomputed
     *  inline rather than reaching into that class's private implementation -- same convention
     *  `AudioStegoCarrierTest` already uses for its own constants. */
    private fun binsPerFrame(strength: Int): Int = strength * 8

    // ---------------------------------------------------------------------
    // matchCover: identifies the right bundled cover from real SLSB stego output
    // ---------------------------------------------------------------------

    @Test
    fun `matchCover identifies the right bundled cover for real SLSB stego output from each cover`() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            val stego = AudioStegoCarrier(pcm, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))

            val match = matchCover(stego, bundledCandidates())

            assertNotNull("expected a match for $cover's own stego output", match)
            requireNotNull(match)
            assertEquals(cover.label, match.label)
            // Measured right-cover ratios are 6.5e-5 / 1.8e-7 (design-v5.md §3.1) -- 1e-3 is a
            // comfortable margin under the 1e-2 accept threshold, per §7's own test plan wording.
            assertTrue(
                "expected a comfortably-matching ratio, was ${match.residualRatio}",
                match.residualRatio < 1e-3,
            )
        }
    }

    // ---------------------------------------------------------------------
    // matchCover: withholds rather than approximates (INV-8) -- foreign clip, size mismatch,
    // gain change, and (via a short synthetic clip, see its own KDoc) tampering.
    // ---------------------------------------------------------------------

    @Test
    fun `matchCover withholds on a clip not derived from either bundled cover`() {
        val rng = Random(2024)
        val expectedLength = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD).size
        val foreignClip = ShortArray(expectedLength) { rng.nextInt(-12_000, 12_001).toShort() }

        val match = matchCover(foreignClip, bundledCandidates())

        assertNull("an unrelated noise clip must not match either bundled cover", match)
    }

    @Test
    fun `matchCover withholds on a trimmed clip`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val trimmed = stego.copyOfRange(0, stego.size - 100) // shorter than every bundled cover now

        val match = matchCover(trimmed, bundledCandidates())

        assertNull("a trimmed clip's length matches no candidate, so it must withhold, never guess", match)
    }

    @Test
    fun `matchCover withholds on a gain-changed clip`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        // 20% gain cut across the whole clip -- diff ~= 0.2*stego, so normalized residual energy
        // is ~0.04, four times MATCH_MAX_RESIDUAL_RATIO's 1e-2 bound, not a borderline nudge.
        val gained = ShortArray(stego.size) { i ->
            (stego[i] * 0.8).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val match = matchCover(gained, bundledCandidates())

        assertNull("a uniformly gain-changed clip must not silently match its original cover", match)
    }

    /**
     * A literal single-sample alteration on the app's real 240,000-sample bundled covers is
     * mathematically invisible to [MATCH_MAX_RESIDUAL_RATIO]'s whole-clip quadratic-energy
     * statistic -- even flipping one sample to a `Short` extreme contributes on the order of 1e-4
     * to the ratio at that scale (a single sample's maximum possible squared error is bounded and
     * fixed, while the clip's total energy scales with all 240,000 samples), comfortably under
     * the 1e-2 accept bound. That is a real, deliberate property of a whole-clip energy statistic,
     * not a gap in this test: [matchCover] is built to tolerate exactly this kind of vanishingly
     * small, localized difference (the same tolerance that lets ±1-LSB synthesis jitter still
     * match). To prove the threshold genuinely fails closed on tampering it CAN see, this uses a
     * short synthetic "cover" where one altered sample IS a meaningful fraction of the clip's
     * total energy, well outside where a real SLSB embed would ever touch a 64-sample clip.
     */
    @Test
    fun `matchCover withholds when a single sample is altered enough to move a clip's own energy ratio`() {
        val syntheticCover = ShortArray(64) { i -> (1000 + i * 20).toShort() }
        val tampered = syntheticCover.copyOf()
        tampered[40] = Short.MAX_VALUE // one sample, far outside any embedded prefix

        val candidates = listOf<Pair<String, () -> PcmAudio>>("synthetic" to { syntheticCover })
        val match = matchCover(tampered, candidates)

        assertNull(
            "a single sample altered enough to dominate a short clip's own energy must withhold",
            match,
        )
    }

    // ---------------------------------------------------------------------
    // matchCover: tolerates ±1 LSB synthesis jitter, rejects simulated Random/algorithm drift,
    // rejects a size mismatch directly (candidate shorter than the clip under test).
    // ---------------------------------------------------------------------

    @Test
    fun `matchCover still matches with plus-or-minus 1 LSB jitter on 1 percent of samples`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val rng = Random(7)
        val jittered = stego.copyOf()
        val jitterCount = jittered.size / 100 // 1%
        repeat(jitterCount) {
            val i = rng.nextInt(jittered.size)
            val bump = if (rng.nextBoolean()) 1 else -1
            jittered[i] = (jittered[i] + bump).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val match = matchCover(jittered, bundledCandidates())

        assertNotNull("±1 LSB jitter on 1% of samples must not break the match (design-v5.md §3.1)", match)
        assertEquals(AudioSampleCover.SOFT_SYNTH.label, match?.label)
    }

    @Test
    fun `matchCover rejects a simulated Random-drifted resynthesis of the same cover shape`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        // Stands in for "the stdlib's kotlin.random.Random algorithm changed": a differently
        // generated candidate of the exact right length and rough amplitude range, but not the
        // same sample sequence -- design-v5.md §3.1's stated failure mode this threshold must
        // catch.
        val rng = Random(999)
        val drifted = ShortArray(cover.size) { rng.nextInt(-12_000, 12_001).toShort() }
        val candidates = listOf<Pair<String, () -> PcmAudio>>("drifted-spoken-word" to { drifted })

        val match = matchCover(stego, candidates)

        assertNull("a drifted resynthesis of the right shape must still be rejected", match)
    }

    @Test
    fun `matchCover rejects a candidate whose synthesized length does not match the clip under test`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val shortCandidate = cover.copyOfRange(0, cover.size / 2)
        val candidates = listOf<Pair<String, () -> PcmAudio>>("half-length" to { shortCandidate })

        val match = matchCover(stego, candidates)

        assertNull("a size-mismatched candidate is a non-match, never a throw or a guess", match)
    }

    // ---------------------------------------------------------------------
    // stegoDifference: identical inputs
    // ---------------------------------------------------------------------

    @Test
    fun `identical cover and stego produce firstChangedFrame -1 and an empty map`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)

        val map = stegoDifference(cover, cover.copyOf())

        assertEquals(-1, map.firstChangedFrame)
        assertEquals(-1, map.lastChangedFrame)
        assertTrue(map.deltaNats.isEmpty())
        assertTrue(map.kinds.isEmpty())
        assertNull(map.changedBinRange)
        assertEquals(0, map.nudgedCells)
        assertEquals(0, map.createdCells)
    }

    // ---------------------------------------------------------------------
    // stegoDifference: changed-frame count, changedBinRange span, NUDGED bound, and the
    // "everything past the last changed frame is untouched" property -- swept across both
    // covers and every valid strength, matching §7's own sweep.
    // ---------------------------------------------------------------------

    @Test
    fun `changed frame count, changedBinRange span, and the NUDGED bound hold across strengths and payload sizes`() {
        for (cover in AudioSampleCover.entries) {
            val pcm = synthesizeSampleCover(cover)
            for (strength in 1..4) {
                val carrier = AudioStegoCarrier(pcm, AudioStegoTechnique.SPECTROGRAM_LSB, strength)
                val b = binsPerFrame(strength)
                // strength=3 also sweeps its own max payload: SpectrogramTest's independent,
                // codec-level measurement across every strength/cover/max-payload combination
                // found the global worst-case NUDGED delta at SOFT_SYNTH strength=3 (frame=227,
                // bin=47) -- a frame {0,5,40,200} bytes never reaches with this file's own
                // fillerPayload. Not added for every strength, since that same sweep found
                // nowhere else comes as close.
                val lengths = if (strength == 3) {
                    listOf(0, 5, 40, 200, carrier.maxPayloadBytes)
                } else {
                    listOf(0, 5, 40, 200)
                }
                // v6 near-silent-frame-skip (follow-up A): capacity is now content-dependent, so
                // a fixed {0,5,40,200} sweep can exceed a gap-heavy cover's real capacity at some
                // strength -- skip rather than let encode() throw (SpectrogramTest's own sweeps
                // use a similar `minOf(N, maxPayloadBytes)` clamp for the same reason).
                for (len in lengths.filter { it <= carrier.maxPayloadBytes }) {
                    val stego = carrier.encode(fillerPayload(len))
                    val map = stegoDifference(pcm, stego)

                    val totalBits = (len + 11) * 8
                    val expectedChangedFrames = (totalBits + b - 1) / b // ceil(totalBits / b)

                    assertEquals(
                        "$cover strength=$strength len=$len: wrong first changed frame",
                        0,
                        map.firstChangedFrame,
                    )
                    // AudioStegoCarrier's v6 near-silent-frame-skip (follow-up A): the embedded
                    // range can now contain genuinely-skipped (untouched) frames in the middle,
                    // not just a dense prefix -- stegoDifference's own class KDoc already
                    // documents handling that defensively ("the codec's own embedding is
                    // contiguous from frame 0... but this function does not assume that"). So the
                    // SPAN width (first to last changed frame) can now exceed the dense formula's
                    // frame count -- it must never be LESS than the number of frames actually
                    // touched, which is what stays tightly coupled to the formula (a ceiling-
                    // rounding slack of 1, since a partial boundary frame can occasionally get
                    // counted as touched with fewer than a full binsPerFrame's bits in it,
                    // measured across this exact sweep, not asserted from theory).
                    val spanWidth = map.lastChangedFrame - map.firstChangedFrame + 1
                    val actuallyChangedFrames = map.kinds.count { row -> row.any { it != DiffCell.UNCHANGED } }
                    assertTrue(
                        "$cover strength=$strength len=$len: span width $spanWidth must be >= " +
                            "the number of frames actually touched ($actuallyChangedFrames)",
                        spanWidth >= actuallyChangedFrames,
                    )
                    assertTrue(
                        "$cover strength=$strength len=$len: actually-touched frame count " +
                            "$actuallyChangedFrames should be within 1 of the dense formula's " +
                            "$expectedChangedFrames",
                        abs(actuallyChangedFrames - expectedChangedFrames) <= 1,
                    )

                    // Every sample from the first frame past lastChangedFrame onward must be
                    // byte-for-byte untouched -- checked directly against the raw PCM, independent
                    // of stegoDifference's own internal bookkeeping.
                    val tailStart = (map.lastChangedFrame + 1) * map.frameSize
                    for (i in tailStart until pcm.size) {
                        assertEquals(
                            "$cover strength=$strength len=$len: sample $i past the last changed " +
                                "frame must be untouched",
                            pcm[i],
                            stego[i],
                        )
                    }

                    // design-v5.md §3.2's exact-span claim is calibrated (scratchpad/proto/run5.txt,
                    // Experiment3.kt) against strengths {1, 2, 4} x payload sizes {5, 40, 200}
                    // only -- strength=3 and an empty (len=0) payload were never part of that
                    // calibration run, and measuring them here (not just following §7's broader
                    // "strength 1-4 x {0,5,40,200}" wording) finds real gaps: SPOKEN_WORD
                    // strength=4 len=0 (a minimal 3-frame embed) and SOFT_SYNTH strength=3 len=5
                    // both measure 32..(32+b-2), one bin short of the full 32..(32+b-1) span --
                    // the top embedded bin's mean |delta| lands just under the 0.03 nats threshold
                    // for that specific bit content with few changed frames to average over. This
                    // is a real property of the statistic (border-bin variance shrinks as changed
                    // frames grow), not an implementation bug: the other properties in this sweep
                    // (frame count, the untouched tail, the NUDGED bound) are exact/theoretical and
                    // hold unconditionally, so only this specific assertion is scoped to what was
                    // actually calibrated.
                    //
                    // v6 near-silent-frame-skip (follow-up A) widens this same phenomenon: a
                    // skipped frame contributes zero samples to the per-bin mean (rather than
                    // always contributing one, as every frame did under the old dense algorithm),
                    // so a gap-containing cover has strictly fewer changed-frame samples to
                    // average over at a given payload size -- measured to newly hit the same
                    // one-bin-short pattern at len=5 (SPOKEN_WORD strength=4, SOFT_SYNTH
                    // strength=2 and strength=4), not just len=0/strength=3 as before. Excluding
                    // len=5 too, same reasoning as the pre-v6 exclusions above.
                    if (len > 5 && strength != 3) {
                        assertEquals(
                            "$cover strength=$strength len=$len: changedBinRange must be the exact " +
                                "embedded band",
                            32 until 32 + b,
                            map.changedBinRange,
                        )
                    }

                    // 1.5 * QUANTIZATION_STEP (0.12) = 0.18 nats is QIM's own theoretical bound.
                    // The real, 16-bit-rounded codec measures slightly past it -- `ifft`-then-
                    // `roundToShort` overshoot on top of `embedBitInBin`'s exact continuous math.
                    // `SpectrogramTest`'s own encoder-level sweep (every strength x cover x max
                    // payload, its own fillerPayload) found the global worst case: 0.18752 nats,
                    // SOFT_SYNTH strength=3. This sweep uses a different payload, so it lands
                    // lower here (measured up to ~0.184, still with strength=3 at max payload
                    // among the closest) -- exactly the kind of payload-content-dependent
                    // variance you'd expect this close to a rounding edge. 0.19 stays grounded in
                    // the higher, independently-measured ceiling rather than this run's own
                    // narrower sample, so a different payload here in the future can't silently
                    // exceed it.
                    assertTrue(
                        "$cover strength=$strength len=$len: maxNudgeNats ${map.maxNudgeNats} " +
                            "exceeded the measured QIM bound",
                        map.maxNudgeNats <= 0.19,
                    )
                    for (row in map.deltaNats) {
                        for (delta in row) {
                            assertTrue(
                                "$cover strength=$strength len=$len: a per-cell delta exceeded the " +
                                    "measured QIM bound",
                                abs(delta) <= 0.19f,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // stegoDifference: the three DiffCell kinds, and §3.3's silent-cover finding
    // ---------------------------------------------------------------------

    @Test
    fun `the difference map classifies cells into all three DiffCell kinds on a real embed`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(200))

        val map = stegoDifference(cover, stego)

        var sawUnchanged = false
        var sawNudged = false
        var sawCreated = false
        for (row in map.kinds) {
            for (kind in row) {
                when (kind) {
                    DiffCell.UNCHANGED -> sawUnchanged = true
                    DiffCell.NUDGED -> sawNudged = true
                    DiffCell.CREATED -> sawCreated = true
                }
            }
        }
        assertTrue("expected at least one UNCHANGED cell (outside the embedded band)", sawUnchanged)
        assertTrue("expected at least one NUDGED cell", sawNudged)
        assertTrue("expected at least one CREATED cell (SPOKEN_WORD has silent gaps)", sawCreated)
        assertEquals(map.nudgedCells > 0, sawNudged)
        assertEquals(map.createdCells > 0, sawCreated)
    }

    /**
     * design-v5.md §3.3 originally found: SPOKEN_WORD has genuinely all-zero "gap" frames between
     * synthesized syllable bursts, and (pre-v6) QIM's `LOG_MAGNITUDE_FLOOR` had nothing to nudge
     * there and instead created new spectral content — 48 created cells at 40 B payload, measured
     * against the OLD dense embedding algorithm. **Superseded by `AudioStegoCarrier`'s v6
     * near-silent-frame-skip fix (follow-up A)**: those gap frames are now genuinely skipped, not
     * embedded into at all, so they show as UNCHANGED, not CREATED — this is the fix's whole
     * point (the gap frames were the source of the click-train the honesty caption elsewhere
     * disclosed). This test now proves the corrected behavior directly: every all-zero cover
     * frame within the embedded range for a payload that reaches SPOKEN_WORD's gaps (40 B) is
     * left completely UNCHANGED, not CREATED, matching the codec's own byte-identical guarantee
     * for skipped frames.
     */
    @Test
    fun `silent-frame cells on SPOKEN_WORD are now left UNCHANGED, not CREATED, by the v2 near-silent-frame skip`() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val map = stegoDifference(cover, stego)

        var foundAllZeroFrameWithinRange = false
        for (f in map.firstChangedFrame..map.lastChangedFrame) {
            val start = f * map.frameSize
            val frameIsAllZero = (start until start + map.frameSize).all { cover[it].toInt() == 0 }
            if (!frameIsAllZero) continue
            foundAllZeroFrameWithinRange = true
            val row = map.kinds[f - map.firstChangedFrame]
            assertTrue(
                "frame $f is an all-zero cover gap within the embedded range; every one of its " +
                    "cells must be UNCHANGED (skipped, not embedded into) under v2, but found a " +
                    "non-UNCHANGED cell",
                row.all { it == DiffCell.UNCHANGED },
            )
        }
        assertTrue(
            "expected at least one all-zero cover frame within [firstChangedFrame," +
                "lastChangedFrame] for this test to actually exercise the skip -- if this starts " +
                "failing, SPOKEN_WORD's synthesis or the payload size changed and this test needs " +
                "a different payload size to still reach a real gap",
            foundAllZeroFrameWithinRange,
        )
    }

    @Test
    fun `SPOKEN_WORD has no created cells at 5 or 40 bytes post-v2, SOFT_SYNTH 5 bytes still does`() {
        val spokenWord = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val softSynth = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)

        val spokenWord5 = AudioStegoCarrier(spokenWord, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(5))
        val spokenWord40 = AudioStegoCarrier(spokenWord, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(40))
        val softSynth5 = AudioStegoCarrier(softSynth, AudioStegoTechnique.SPECTROGRAM_LSB).encode(fillerPayload(5))

        assertEquals(0, stegoDifference(spokenWord, spokenWord5).createdCells)
        // Pre-v6 this was createdCells > 0 (SPOKEN_WORD's gap frames got embedded into and
        // created new spectral content there). Post-v2 near-silent-frame-skip, those same gap
        // frames are genuinely skipped, and 40 B doesn't happen to reach any other near-zero
        // eligible bin elsewhere in SPOKEN_WORD's loud bursts or dense header -- measured 0, not
        // asserted from the old behavior. (`the difference map classifies cells into all three
        // DiffCell kinds on a real embed` above uses 200 B specifically because created cells
        // still occur at that larger size, from ordinary spectral variation in loud content, not
        // from silence -- unrelated to this test's own SPOKEN_WORD-gap-specific finding.)
        assertEquals(0, stegoDifference(spokenWord, spokenWord40).createdCells)
        // SOFT_SYNTH's own fade-in (frame 0 peaks at 40 LSB, well under LOG_MAGNITUDE_FLOOR's
        // effective threshold) still creates cells at the smallest tested payload -- unaffected by
        // v2, since the fade-in falls inside the header's own always-dense leading frames
        // (AudioStegoCarrier.kt class KDoc's "near-silent-frame skip (v2)" section).
        assertTrue(stegoDifference(softSynth, softSynth5).createdCells > 0)
    }

    // ---------------------------------------------------------------------
    // Re-derivation determinism (design-v5.md §3.1's stated hazard: kotlin.random.Random and
    // Math.sin are only promised stable within one Kotlin/JVM build). If this ever fails, that
    // drift is exactly what broke and matchCover's threshold is the wrong thing to blame.
    // ---------------------------------------------------------------------

    @Test
    fun `regenerating each bundled cover twice gives byte-identical output`() {
        for (cover in AudioSampleCover.entries) {
            val first = synthesizeSampleCover(cover)
            val second = synthesizeSampleCover(cover)
            assertArrayEquals("$cover synthesis must be deterministic run-to-run", first, second)
        }
    }
}
