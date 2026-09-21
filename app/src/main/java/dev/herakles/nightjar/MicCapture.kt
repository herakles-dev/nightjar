package dev.herakles.nightjar

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** Logcat tag for AudioRecord source/effect fallback diagnostics — shared by every mic-owning controller. */
private const val TAG = "MicCapture"

/**
 * Shared `AudioRecord` acquisition + platform-DSP-effect suppression for every mic-owning
 * controller in this app ([dev.herakles.nightjar.modules.acoustic.AcousticModemController],
 * [dev.herakles.nightjar.modules.detector.DetectorController]).
 *
 * Originally lived only inside `AcousticModemController` (Task #26). `DetectorController` built a
 * bare `AudioSource.MIC` [AudioRecord] directly, with no UNPROCESSED/VOICE_RECOGNITION fallback and
 * no NoiseSuppressor/AGC/AEC suppression — exactly the platform speech DSP this file's own KDoc
 * already documents as capable of distorting or destroying pure-tone FSK/PSK signals, which is
 * precisely the tone-grid energy the detector's whole job is to measure (spec.md INV-4: the
 * detector must self-detect the app's own modem transmission). Review finding mic-3 flagged that
 * divergence; pulled the shared logic out here so both controllers use one implementation instead
 * of two that can drift apart.
 *
 * [openBestAudioRecord] never throws — a prior version's last-resort tier called `error(...)` on
 * total failure, which surfaced as an uncaught `IllegalStateException` all the way up through
 * `startRecording()`'s caller and crashed the app whenever the mic was held by a phone call or
 * another app (review findings mic-1/mic-2). Callers now get `null` back and are responsible for
 * surfacing a visible "mic busy" status instead.
 */
internal object MicCapture {

    /**
     * Task #26: picks a capture source that avoids platform speech-tuned DSP (noise
     * suppression / AGC / echo cancellation), which is well documented to distort or
     * destroy pure-tone FSK/PSK signals. Tiered fallback, each tier verified by actually
     * constructing the [AudioRecord] and checking [AudioRecord.STATE_INITIALIZED] rather
     * than trusting the source constant alone:
     *
     * 1. [MediaRecorder.AudioSource.UNPROCESSED] — raw samples, no DSP at all — only
     *    attempted when [AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED] reports
     *    device support.
     * 2. [MediaRecorder.AudioSource.VOICE_RECOGNITION] — much more broadly supported,
     *    also bypasses most speech-tuned processing (ggwave, the real prior art this
     *    design follows, uses the same workaround).
     * 3. [MediaRecorder.AudioSource.MIC] — last resort; logs a warning since this source
     *    is the one most likely to run the phone's call/voice DSP over the signal.
     *
     * Returns `null` — never throws — if every source fails to initialize (e.g. the mic is
     * held by a call or another app); callers must surface that as a visible "mic busy"
     * status rather than letting the exception that used to come out of here crash the app
     * (mic-1/mic-2 fixes).
     */
    fun openBestAudioRecord(appContext: Context, recordBufferBytes: Int): AudioRecord? {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val unprocessedSupported = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        if (unprocessedSupported) {
            buildAudioRecord(MediaRecorder.AudioSource.UNPROCESSED, recordBufferBytes)?.let {
                Log.i(TAG, "capture: using AudioSource.UNPROCESSED")
                return it
            }
        }
        buildAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, recordBufferBytes)?.let {
            Log.i(TAG, "capture: using AudioSource.VOICE_RECOGNITION")
            return it
        }
        val mic = buildAudioRecord(MediaRecorder.AudioSource.MIC, recordBufferBytes)
        if (mic == null) {
            Log.w(TAG, "capture: all AudioSources failed to initialize — mic is busy or unavailable")
        } else {
            Log.w(
                TAG,
                "capture: falling back to AudioSource.MIC — UNPROCESSED/VOICE_RECOGNITION " +
                    "unavailable on this device; platform speech DSP (noise suppression / AGC / " +
                    "echo cancellation) may distort the FSK/PSK tones",
            )
        }
        return mic
    }

    /** Constructs an [AudioRecord] on [source]; returns null (never throws) if unsupported. */
    private fun buildAudioRecord(source: Int, recordBufferBytes: Int): AudioRecord? {
        return try {
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(NightjarAcoustics.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(recordBufferBytes)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (unsupported: UnsupportedOperationException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    /**
     * Task #26 belt-and-suspenders: some OEMs apply NoiseSuppressor/AGC/AEC even on
     * VOICE_RECOGNITION (or, rarely, UNPROCESSED), so the source choice above isn't
     * sufficient on its own. Explicitly disables each effect if present on this capture
     * session.
     *
     * mic-8 fix: callers must call this from *inside* their own try/finally around the
     * already-constructed [AudioRecord] — not before it, as a prior version of the modem
     * controller did — so a throwing OEM effect factory can never leak that record before
     * its `finally` gets a chance to release it. Callers must `.release()` the returned
     * list once capture stops.
     */
    fun disablePlatformAudioEffects(sessionId: Int): List<AudioEffect> {
        val disabled = mutableListOf<AudioEffect>()
        disableEffectIfPresent("NoiseSuppressor", NoiseSuppressor.isAvailable(), disabled) {
            NoiseSuppressor.create(sessionId)
        }
        disableEffectIfPresent("AutomaticGainControl", AutomaticGainControl.isAvailable(), disabled) {
            AutomaticGainControl.create(sessionId)
        }
        disableEffectIfPresent("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable(), disabled) {
            AcousticEchoCanceler.create(sessionId)
        }
        return disabled
    }

    /** Logs availability/creation/disable outcome per effect type so field reports are diagnosable. */
    private inline fun disableEffectIfPresent(
        name: String,
        availableOnDevice: Boolean,
        disabled: MutableList<AudioEffect>,
        create: () -> AudioEffect?,
    ) {
        if (!availableOnDevice) {
            Log.i(TAG, "effects: $name not available on this device")
            return
        }
        val effect = create()
        if (effect == null) {
            Log.i(TAG, "effects: $name available but create() returned null for this session")
            return
        }
        effect.setEnabled(false)
        disabled += effect
        Log.i(TAG, "effects: $name found on this session, disabled")
    }
}
