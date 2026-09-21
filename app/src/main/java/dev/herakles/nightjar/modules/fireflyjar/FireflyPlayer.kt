package dev.herakles.nightjar.modules.fireflyjar

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio

/**
 * Shared `AudioTrack` playback transport — Stage C lift of the app's one piece of audio
 * stop/interrupt machinery out of `AudioStegoController` (see that class's KDoc,
 * `AudioStegoScreen.kt`), so a second screen (the carrier detail view) can play a caught clip
 * without a second/third `AudioTrack` construction path. `AudioStegoController` now
 * delegates its own "play cover"/"play working" transport to an instance of this class rather
 * than keeping its own copies of `activeAudioTrack`/`stopActiveTrack`/`buildAudioTrack`.
 *
 * Construction mirrors what `AudioStegoController.buildAudioTrack` (and, before it,
 * `AcousticModemController.playPcm`) already used verbatim: `MODE_STATIC`,
 * `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, `ENCODING_PCM_16BIT` at [NightjarAcoustics.SAMPLE_RATE_HZ],
 * `CHANNEL_OUT_MONO`/`CHANNEL_OUT_STEREO` selected by the caller's channel count.
 *
 * One clip plays at a time: [play] always stops/releases whatever's currently active before
 * building the next `AudioTrack` — the same cutover behavior `AudioStegoScreen.kt` already had
 * (tapping "play working" while "play cover" is still going cuts it off cleanly rather than
 * overlapping).
 *
 * Not itself Compose state. A caller building a live progress UI should read [progressFraction]
 * from inside a Composable that already recomposes every frame — e.g. one keyed off
 * [LocalFireflyClock] purely as a repaint tick. The clock's *value* must never be used to derive
 * progress: it's a free-running breathing-animation clock with no relationship to how far into
 * the clip playback actually is, and using it would make a progress line drift out of sync with
 * the audio. [progressFraction] derives the real position from
 * [AudioTrack.getPlaybackHeadPosition] against the clip's own frame count instead.
 *
 * F-04 fix: [play] requests transient audio focus (matching this class's own `USAGE_MEDIA`/
 * `CONTENT_TYPE_MUSIC` attributes, [buildAudioTrack]'s) before starting playback, and
 * [stopActiveTrack] always abandons it -- both the explicit [stop] path and the automatic
 * cutover [play] does for whatever clip was already active. Before this fix nothing in the app
 * requested focus at all, so a caught clip played through other apps' audio without ducking or
 * stopping for/against them. [audioManager] is optional (nullable) so a caller that can't resolve
 * one (or a future JVM-side test double) degrades to "plays without ever touching focus" rather
 * than crashing.
 */
class FireflyPlayer(private val audioManager: AudioManager? = null) {

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    /** Total frames (not samples — divided by channel count) in the clip [activeAudioTrack] is
     *  currently carrying, for [progressFraction]'s denominator. Meaningless once
     *  [activeAudioTrack] is null. */
    @Volatile
    private var activeFrameCount: Int = 0

    /** The focus grant [play] holds, if any -- released by [stopActiveTrack] via
     *  [AudioManager.abandonAudioFocusRequest]. Null whenever nothing is playing. */
    @Volatile
    private var activeFocusRequest: AudioFocusRequest? = null

    /**
     * True while a clip started by [play] is actually playing — i.e. [play] both received a
     * non-empty [PcmAudio] and successfully built/started an `AudioTrack`. Lets a caller tell a
     * genuine play from [play]'s silent no-op cases (empty clip, or a platform that rejects the
     * requested format — see [buildAudioTrack]'s null contract).
     */
    val isPlaying: Boolean
        get() = activeAudioTrack != null

    /**
     * Plays [pcm] ([channelCount] channels, PCM16 at [NightjarAcoustics.SAMPLE_RATE_HZ]) over
     * the speaker, stopping/releasing any currently-active clip first — one clip at a time. A
     * no-op past that stop-and-release step when [pcm] is empty or [buildAudioTrack] can't
     * produce a track for the requested format; check [isPlaying] afterward if the caller needs
     * to distinguish that from a genuine start.
     */
    fun play(pcm: PcmAudio, channelCount: Int) {
        stopActiveTrack()
        if (pcm.isEmpty()) return
        val track = buildAudioTrack(pcm, channelCount) ?: return
        activeFocusRequest = requestAudioFocus()
        activeAudioTrack = track
        activeFrameCount = pcm.size / channelCount
        track.write(pcm, 0, pcm.size)
        track.play()
    }

    /** Stops and releases the currently-active clip, if any. Safe to call when nothing is
     *  playing — mirrors [buildAudioTrack]'s already-stopped tolerance. */
    fun stop() {
        stopActiveTrack()
    }

    /**
     * Current playback progress as a 0f..1f fraction, derived from
     * [AudioTrack.getPlaybackHeadPosition] against the active clip's total frame count. Returns
     * 0f when nothing is playing — see this class's KDoc for why this must stay decoupled from
     * [LocalFireflyClock].
     */
    fun progressFraction(): Float {
        val track = activeAudioTrack ?: return 0f
        val frames = activeFrameCount
        if (frames <= 0) return 0f
        return (track.playbackHeadPosition.toFloat() / frames.toFloat()).coerceIn(0f, 1f)
    }

    /** Stops/releases the currently-active clip, if any. Call from `DisposableEffect.onDispose`. */
    fun release() {
        stopActiveTrack()
    }

    private fun stopActiveTrack() {
        activeAudioTrack?.let { track ->
            try {
                track.stop()
            } catch (alreadyStopped: IllegalStateException) {
                // Already stopped/uninitialized -- nothing to clean up.
            }
            track.release()
        }
        activeAudioTrack = null
        activeFrameCount = 0
        activeFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        activeFocusRequest = null
    }

    /**
     * Requests transient focus (this is a short caught-clip playback, not exclusive long-running
     * media) with the same [AudioAttributes] [buildAudioTrack] already builds the track with.
     * Returns null (and requests nothing) when [audioManager] wasn't resolved, or when the
     * platform denies the request -- either way [play] still plays the clip locally; a denied/
     * absent focus grant only means this app didn't ask the platform to duck or pause anything
     * else, not that playback itself fails.
     */
    private fun requestAudioFocus(): AudioFocusRequest? {
        val manager = audioManager ?: return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .build()
        return if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request else null
    }

    private fun buildAudioTrack(pcm: PcmAudio, channelCount: Int): AudioTrack? {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(NightjarAcoustics.SAMPLE_RATE_HZ)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (unsupported: UnsupportedOperationException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }
}
