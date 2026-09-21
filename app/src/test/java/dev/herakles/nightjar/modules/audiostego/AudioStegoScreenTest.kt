package dev.herakles.nightjar.modules.audiostego

import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DetectionResult
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
    fun mfskExplainerStatesAudibleHighTonesAndLowestCapacity() {
        val text = techniqueExplainer(AudioStegoTechnique.MFSK)
        assertTrue("should say the tones are audible", text.contains("audible"))
        assertTrue("should name the tone-band frequency range", text.contains("19.7"))
        // MFSK's crackle was a separate encoder bug, not this technique's real tradeoff -- the
        // audibility explanation must not describe it as a feature of the technique.
        assertFalse("must not describe the MFSK crackle bug as a feature", text.contains("crackle"))
        assertTrue(
            "MFSK is the actual lowest-capacity technique (37B vs 51B/457B) -- its text should say so",
            text.lowercase().contains("lowest"),
        )
    }

    // ==========================================================================================
    // Capacity ordering: the ground truth the three tooltips' capacity claims are checked
    // against, computed from the real AudioStegoCarrier (not hardcoded or trusted from memory --
    // design-v5.md §2.7 review note: an earlier copy pass had phase-inversion and MFSK's
    // "lowest capacity" claim backwards).
    // ==========================================================================================

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
    // Detector caveats: must match AudioStegDetectorTest's tested evasion matrix, and must say
    // any anti-phase stereo (not just this app's own output) reads as phase-inversion.
    // ==========================================================================================

    @Test
    fun detectorCaveatStatesTargetedEvasionsAndAntiPhaseCaveat() {
        assertTrue("should say it only knows this app's three techniques", DETECTOR_CAVEAT.contains("three techniques"))
        assertTrue("should match the tested re-level/trim/re-compress evasions", DETECTOR_CAVEAT.contains("re-levelled"))
        assertTrue("should say any anti-phase stereo flags, not just this app's own", DETECTOR_CAVEAT.contains("anti-phase"))
        assertTrue("clear must not be overstated as 'nothing hidden'", DETECTOR_CAVEAT.contains("not nothing hidden"))
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
}
