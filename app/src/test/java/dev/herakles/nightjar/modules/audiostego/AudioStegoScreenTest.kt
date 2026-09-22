package dev.herakles.nightjar.modules.audiostego

import dev.herakles.nightjar.AudioStegDetector
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.CovertCarrier
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DetectionResult
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.WavFile
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit4, no Robolectric — every symbol under test here ([audioStegoTagline],
 * [techniqueExplainer], [DETECTOR_CAVEAT], [JAR_PEEK_CAVEAT], [isFireflyCatchEvent]) is pure
 * Kotlin string/logic with no Android dependency, same discipline [AudioStegDetectorTest] and
 * `ImageStegoScreenTest`'s non-Robolectric cases already follow.
 *
 * Task V5-6 (design-v5.md §2.7, spec.md gate-22/gate-23): this suite exists because the prior
 * screen copy made a promise the owner's Gate 8 headphone listening pass (Pixel 6a) disproved —
 * "playback proves it sounds unchanged" is false for phase-inversion (audibly wider/hollow) and
 * MFSK (tones audible near 19.7-20 kHz). Every assertion below checks the *replacement* copy
 * actually states what Gate 8 found, not just that a string exists.
 */
class AudioStegoScreenTest {

    // ==========================================================================================
    // Tagline: must no longer promise "unchanged", must tell the operator to listen and judge.
    // ==========================================================================================

    @Test
    fun taglineMakesNoUnchangedPromise() {
        val tagline = audioStegoTagline()
        assertFalse("tagline must not claim the clip sounds unchanged", tagline.contains("unchanged"))
    }

    @Test
    fun taglineTellsTheOperatorToListenAndJudge() {
        val tagline = audioStegoTagline()
        assertTrue("tagline should direct the operator to judge for themselves", tagline.contains("judge"))
        assertTrue("tagline should reference playing the clips", tagline.contains("play"))
    }

    // ==========================================================================================
    // Per-technique "?" tradeoff text: each must state its real, Gate-8-measured audibility.
    // ==========================================================================================

    @Test
    fun phaseInversionExplainerStatesHeadphoneAudibilityAndMonoFragility() {
        val text = techniqueExplainer(AudioStegoTechnique.PHASE_INVERSION)
        assertTrue("should call out headphone audibility", text.contains("headphones"))
        assertTrue("should describe the wide/hollow character Gate 8 found", text.contains("hollow"))
        assertTrue("should note summing to mono destroys it", text.contains("mono"))
        // A real, measured capacity bug the design-v5.md draft had backwards (design-v5.md §2.7
        // review note): phase-inversion is NOT the lowest-capacity technique -- see
        // mfskHasTheLowestCapacityOfTheThreeTechniques below. The tooltip must not claim it is.
        assertFalse(
            "phase-inversion's text must not claim to be the lowest-capacity technique",
            text.lowercase().contains("lowest capacity"),
        )
    }

    @Test
    fun spectrogramLsbExplainerStatesNearTransparencyAndFadeInCrackle() {
        val text = techniqueExplainer(AudioStegoTechnique.SPECTROGRAM_LSB)
        assertTrue("should say it's near-transparent on headphones", text.contains("headphones"))
        assertTrue("should call out the soft-synth fade-in crackle Gate 8 found", text.contains("crackle"))
        assertTrue("should say the spoken-word cover is indistinguishable", text.contains("indistinguishable"))
    }

    @Test
    fun mfskExplainerStatesAudibleHighTonesAndFixedLowCapacity() {
        val text = techniqueExplainer(AudioStegoTechnique.MFSK)
        assertTrue("should say the tones are audible", text.contains("audible"))
        assertTrue("should name the tone-band frequency range", text.contains("19.7"))
        // MFSK's crackle was a separate encoder bug, not this technique's real tradeoff -- the
        // audibility explanation must not describe it as a feature of the technique.
        assertFalse("must not describe the MFSK crackle bug as a feature", text.contains("crackle"))
        // MFSK is no longer the lowest-capacity technique -- PHASE_CODING (deferred v6
        // follow-up) measures lower. Its text must not overclaim "lowest" now that a fourth,
        // smaller technique exists; it should instead name what it IS lower than.
        assertFalse(
            "MFSK is no longer the lowest-capacity technique (phase-coding is) -- its text " +
                "must not claim to be",
            text.lowercase().contains("lowest"),
        )
    }

    // ==========================================================================================
    // Capacity ordering: the ground truth the four tooltips' capacity claims are checked
    // against, computed from the real AudioStegoCarrier (not hardcoded or trusted from memory --
    // design-v5.md §2.7 review note: an earlier copy pass had phase-inversion and MFSK's
    // "lowest capacity" claim backwards; the same discipline applies now that PHASE_CODING has
    // overtaken MFSK for the actual lowest spot).
    // ==========================================================================================

    @Test
    fun phaseCodingHasTheLowestCapacityOfTheFourTechniques() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val phaseCodingBytes = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_CODING).maxPayloadBytes
        val mfskBytes = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK).maxPayloadBytes

        assertTrue(
            "phase-coding ($phaseCodingBytes B) should be lower-capacity than MFSK ($mfskBytes B)",
            phaseCodingBytes < mfskBytes,
        )
    }

    @Test
    fun mfskHasTheLowestCapacityOfTheThreeTechniques() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val mfskBytes = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK).maxPayloadBytes
        val phaseInversionBytes = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION).maxPayloadBytes
        val spectrogramLsbBytes = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB).maxPayloadBytes

        assertTrue("MFSK ($mfskBytes B) should be lower-capacity than phase-inversion ($phaseInversionBytes B)", mfskBytes < phaseInversionBytes)
        assertTrue("phase-inversion ($phaseInversionBytes B) should be lower-capacity than spectrogram-LSB ($spectrogramLsbBytes B)", phaseInversionBytes < spectrogramLsbBytes)
    }

    // ==========================================================================================
    // Detector caveats: must match AudioStegDetectorTest's tested evasion matrix, and must not
    // overclaim past what AudioStegDetectorTest actually measures -- a prior copy pass claimed
    // "any anti-phase stereo reads as phase-inversion" (false: a plain polarity flip with
    // nothing mixed in reads clear per cleanCoversAndStereoVariantsAreNotFlagged's
    // "pure-inverted stereo" case) and "trimmed clips slip past it" (over-general: only an
    // off-grid trim evades per slsbOffGridTrimOf100SamplesEvadesDetection -- an on-grid,
    // frame-aligned trim is still flagged per slsbOnGridTrimOf1024SamplesIsStillFlagged). The
    // old `.contains("anti-phase")` / `.contains("re-levelled")` checks below would have passed
    // on both false claims, which is exactly what let the drift ship -- these assertions check
    // the caveat's actual claims, not just that a keyword appears somewhere in it.
    // ==========================================================================================

    @Test
    fun detectorCaveatStatesTargetedEvasionsAndAntiPhaseCaveat() {
        assertTrue("should say it only knows this app's three techniques", DETECTOR_CAVEAT.contains("three techniques"))
        assertTrue("should match the tested re-level evasion", DETECTOR_CAVEAT.contains("re-levelled"))
        assertTrue("clear must not be overstated as 'nothing hidden'", DETECTOR_CAVEAT.contains("not nothing hidden"))

        // Trim evasion is real only off-grid (slsbOffGridTrimOf100SamplesEvadesDetection); an
        // on-grid trim is still flagged (slsbOnGridTrimOf1024SamplesIsStillFlagged), so the
        // caveat must name "off-grid" specifically and must not claim trims in general slip past
        // it.
        assertTrue("should name off-grid trims specifically, not trims in general", DETECTOR_CAVEAT.contains("off-grid"))
        assertFalse(
            "must not claim ALL trims slip past it -- only off-grid ones do",
            DETECTOR_CAVEAT.contains(", trimmed") || DETECTOR_CAVEAT.contains(" trimmed "),
        )

        // Anti-phase: must not claim ANY/a plain anti-phase flip is flagged, and must say a
        // plain flip reads clear (cleanCoversAndStereoVariantsAreNotFlagged's "pure-inverted
        // stereo" case).
        assertTrue("should mention anti-phase stereo", DETECTOR_CAVEAT.contains("anti-phase"))
        assertFalse(
            "must not overclaim that ANY/a plain anti-phase flip is flagged",
            DETECTOR_CAVEAT.contains("any anti-phase"),
        )
        assertTrue(
            "should explicitly say a plain flip alone reads clear",
            DETECTOR_CAVEAT.contains("plain flip") && DETECTOR_CAVEAT.contains("reads clear"),
        )
    }

    /**
     * Ties [DETECTOR_CAVEAT]'s anti-phase claim directly to [AudioStegDetector]'s real behavior
     * (rather than trusting the copy's wording), mirroring
     * [dev.herakles.nightjar.AudioStegDetectorTest.cleanCoversAndStereoVariantsAreNotFlagged]'s
     * "pure-inverted stereo" case and
     * [dev.herakles.nightjar.AudioStegDetectorTest.antiPhaseStereoWithNoiseResidualIsFlagged].
     * If the detector's behavior on these two cases ever changes, this test -- not just the
     * string-content one above -- fails, so copy drift can't hide behind a passing keyword check.
     */
    @Test
    fun detectorCaveatAntiPhaseClaimMatchesRealDetectorBehavior() {
        val detector = AudioStegDetector()
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)

        // Plain polarity flip: R = -L exactly, nothing mixed in.
        val plainFlip = ShortArray(cover.size * 2)
        for (i in cover.indices) {
            plainFlip[2 * i] = cover[i]
            plainFlip[2 * i + 1] =
                (-cover[i].toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val plainResult = detector.analyze(
            WavFile.ParsedWav(sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ, numChannels = 2, samples = plainFlip),
        )
        assertFalse(
            "a plain polarity flip with nothing mixed in should read clear, matching the caveat's " +
                "'a plain flip alone reads clear' claim: $plainResult",
            plainResult.flagged,
        )

        // Anti-phase with a surviving residual mixed into L+R.
        val rng = Random(2024)
        val residualFlip = ShortArray(cover.size * 2)
        for (i in cover.indices) {
            residualFlip[2 * i] = cover[i]
            val r = (-0.97 * cover[i]) + rng.nextInt(-100, 101)
            residualFlip[2 * i + 1] = r.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val residualResult = detector.analyze(
            WavFile.ParsedWav(sampleRateHz = NightjarAcoustics.SAMPLE_RATE_HZ, numChannels = 2, samples = residualFlip),
        )
        assertTrue(
            "anti-phase stereo with something left in the mix should read flagged, matching the " +
                "caveat's claim: $residualResult",
            residualResult.flagged,
        )
    }

    @Test
    fun jarPeekCaveatIsHonestAboutScope() {
        assertTrue(JAR_PEEK_CAVEAT.contains("jar's own three tricks"))
    }

    // ==========================================================================================
    // gate-23 / INV-7: "a check or peek writes no firefly."
    // ==========================================================================================

    @Test
    fun catchAndReceiveEventsAreFireflyCatchEvents() {
        assertTrue(isFireflyCatchEvent(AudioStegoStatus.Embedded(payloadBytes = 5)))
        assertTrue(isFireflyCatchEvent(AudioStegoStatus.ExtractedSuccess(text = "hi")))
    }

    @Test
    fun aCheckOrPeekIsNeverAFireflyCatchEvent() {
        val clearResult = DetectionResult(confidence = 0.02f, flagged = false)
        val flaggedResult = DetectionResult(confidence = 0.97f, flagged = true, estimatedPayloadBytes = 12)

        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Analyzing))
        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Analyzed(clearResult)))
        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Analyzed(flaggedResult)))
    }

    @Test
    fun everyNonTerminalOrFailureStatusIsNeverAFireflyCatchEvent() {
        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Idle))
        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Embedding))
        assertFalse(isFireflyCatchEvent(AudioStegoStatus.Extracting))
        assertFalse(
            isFireflyCatchEvent(
                AudioStegoStatus.ExtractedFailure(reason = DecodeFailure.NO_PAYLOAD_FOUND, detail = null),
            ),
        )
    }

    // ==========================================================================================
    // gate-34: "open a recording" -- tryAllTechniques (technique-trial ordering/result mapping)
    // and isWavMagic (compressed-container detection by magic bytes), the two pieces of pure
    // logic W1-4 extracted out of AudioStegoController.openRecording so they're unit-testable
    // without a Compose/Robolectric harness.
    // ==========================================================================================

    private val realCarrierFactory: (PcmAudio, AudioStegoTechnique) -> CovertCarrier<PcmAudio> =
        { cover, technique -> AudioStegoCarrier(cover, technique) }

    @Test
    fun tryAllTechniquesMatchesPhaseInversionAndRecoversThePayload() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.PHASE_INVERSION)
            .encode("ravens".encodeToByteArray())

        val result = tryAllTechniques(realCarrierFactory, stego)

        assertTrue("expected a match, got $result", result is OpenRecordingResult.Matched)
        val matched = result as OpenRecordingResult.Matched
        assertEquals(AudioStegoTechnique.PHASE_INVERSION, matched.technique)
        assertEquals("ravens", matched.text)
    }

    @Test
    fun tryAllTechniquesMatchesSpectrogramLsbAndRecoversThePayload() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.SPECTROGRAM_LSB)
            .encode("the ravens have landed".encodeToByteArray())

        val result = tryAllTechniques(realCarrierFactory, stego)

        assertTrue("expected a match, got $result", result is OpenRecordingResult.Matched)
        val matched = result as OpenRecordingResult.Matched
        assertEquals(AudioStegoTechnique.SPECTROGRAM_LSB, matched.technique)
        assertEquals("the ravens have landed", matched.text)
    }

    @Test
    fun tryAllTechniquesMatchesMfskAndRecoversThePayload() {
        val cover = synthesizeSampleCover(AudioSampleCover.SOFT_SYNTH)
        val stego = AudioStegoCarrier(cover, AudioStegoTechnique.MFSK)
            .encode("hi".encodeToByteArray())

        val result = tryAllTechniques(realCarrierFactory, stego)

        assertTrue("expected a match, got $result", result is OpenRecordingResult.Matched)
        val matched = result as OpenRecordingResult.Matched
        assertEquals(AudioStegoTechnique.MFSK, matched.technique)
        assertEquals("hi", matched.text)
    }

    @Test
    fun tryAllTechniquesReturnsNoMatchForAnUnembeddedCover() {
        val cover = synthesizeSampleCover(AudioSampleCover.SPOKEN_WORD)

        val result = tryAllTechniques(realCarrierFactory, cover)

        assertTrue("a plain cover with nothing hidden must never report a match: $result", result is OpenRecordingResult.NoMatch)
    }

    @Test
    fun isWavMagicTrueForARealWavHeader() {
        val wav = WavFile.encodePcm16Mono(ShortArray(10), NightjarAcoustics.SAMPLE_RATE_HZ)
        assertTrue(isWavMagic(wav))
    }

    @Test
    fun isWavMagicFalseForCompressedContainerMagicBytes() {
        // ID3 (mp3 tag), OggS (ogg/opus), and a bare ftyp box (m4a/aac) -- none of these are
        // RIFF/WAVE, which is exactly the point: "open a recording" must route them to the
        // honest "arrived compressed" outcome instead of attempting a WAV parse.
        val id3 = "ID3".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        val oggS = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        val ftyp = ByteArray(4) + "ftyp".toByteArray(Charsets.US_ASCII) + ByteArray(12)
        assertFalse(isWavMagic(id3))
        assertFalse(isWavMagic(oggS))
        assertFalse(isWavMagic(ftyp))
    }

    @Test
    fun isWavMagicFalseForEmptyOrTooShortBytes() {
        assertFalse(isWavMagic(ByteArray(0)))
        assertFalse(isWavMagic(ByteArray(11))) // one byte short of the 12-byte RIFF....WAVE magic
    }
}
