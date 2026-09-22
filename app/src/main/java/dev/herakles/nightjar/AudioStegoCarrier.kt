package dev.herakles.nightjar

import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Module 2 — audio steganography codec (architecture.md §7, spec.md v2 addition).
 *
 * One class parameterized by [technique], mirroring how [ImageStegoCarrier] takes a single
 * cover `Bitmap` rather than the app defining a class per cover image. All three
 * [AudioStegoTechnique] values are implemented (architecture.md §7 note on capacity: "this spec
 * deliberately does not pin exact bytes-per-second figures per technique").
 *
 * ## Phase-inversion technique
 *
 * This is the real dual-mono phase-cancellation trick documented in
 * `covert-data/library/03_audio_steganography.md`: "convert a stereo file to dual-mono (left and
 * right channels made identical), then invert the waveform polarity of only one channel, and mix
 * in a secondary low-volume signal ... Play the file normally in stereo and both channels sound
 * like ordinary program content with a faint artifact; sum it down to mono ... the identical
 * original content phase-cancels out, leaving only the secondary signal audible."
 *
 * [cover] (mono [PcmAudio], per [NightjarAcoustics.SAMPLE_RATE_HZ]) is this codec's *bound* input
 * signal, playing the role of the "program content" above. [encode] does not return mono — it
 * returns an **interleaved stereo** `ShortArray` (`out[2*i]` = left, `out[2*i+1]` = right, so
 * `out.size == 2 * cover.size`). This interleaving is this codec's own private internal contract
 * between its [encode]/[decode] — [PcmAudio] itself stays a bare `ShortArray` either way, exactly
 * the same way the app's other techniques each define their own private bit-layout convention:
 *
 *  - **Left channel**: [cover], byte-for-byte untouched — this is the "identical original
 *    content" the source describes.
 *  - **Right channel**: starts as the saturating negation of [cover] (the "invert the waveform
 *    polarity" step — see [negatedSample] for why this saturates instead of wrapping), then has a
 *    small constant amplitude offset ([MIX_AMPLITUDE], positive or negative) added across every
 *    sample of a [SEGMENT_SAMPLES]-sample segment — this is the "mix in a secondary low-volume
 *    signal" step, one payload bit per segment: `+MIX_AMPLITUDE` encodes a 0 bit,
 *    `-MIX_AMPLITUDE` encodes a 1 bit.
 *  - **Decode's phase-cancellation step**: summing the two channels sample-by-sample,
 *    `L[i] + R[i] = cover[i] + (-cover[i] + offset) = offset` (when no saturation clipping
 *    occurred) — the original program content cancels out exactly as the source describes, and
 *    only the small mixed-in offset survives. Summing that recovered offset over an entire
 *    [SEGMENT_SAMPLES]-sample segment gives a strongly-signed value (dominated by
 *    `SEGMENT_SAMPLES * MIX_AMPLITUDE`, not a near-zero tiebreak) whose sign alone recovers the
 *    bit: sum >= 0 -> bit 0, sum < 0 -> bit 1.
 *
 * [MIX_AMPLITUDE] = 64: comfortably small relative to full-scale PCM16 (32767 — about 0.2% of
 * it), so the mixed-in secondary signal reads as the "faint artifact" the source describes rather
 * than an audible tone, while still being far larger than any rounding noise, so a
 * [SEGMENT_SAMPLES]-sample segment's summed offset (up to `480 * 64 = 30720` in the clean case)
 * dominates the sign decision robustly.
 *
 * [SEGMENT_SAMPLES] = 480 (10 ms at [NightjarAcoustics.SAMPLE_RATE_HZ]) is kept from the original
 * capacity/robustness balance point: long enough that a whole-segment mixed-in offset reads as a
 * single low-energy artifact once every 10 ms rather than a per-sample buzz; short enough to give
 * a usable ~12.5 bytes/sec of capacity for a demo-length clip. Per architecture.md §7,
 * phase-inversion is expected to be far lower capacity than spectrogram-LSB for the same clip
 * length — this segment size is why. Whether that once-per-10ms artifact is actually inaudible at
 * default strength is gate-8's job (spec.md: "verified by ear on real hardware, not asserted"),
 * not a claim this codec makes for itself.
 *
 * **Known fidelity caveat**: if [cover] samples are already near full-scale ([Short.MIN_VALUE] /
 * [Short.MAX_VALUE]), the saturating negate and/or the saturating offset-add can clip, which
 * breaks *exact* phase cancellation for the affected samples (the recovered `L[i] + R[i]` sum
 * picks up leftover cover-signal energy instead of being purely `offset`). This codec does not
 * add any runtime handling beyond the saturating arithmetic it already performs — callers
 * supplying near-full-scale covers should expect reduced decode robustness near clipped samples.
 *
 * ## Spectrogram-LSB technique
 *
 * Genuinely frequency-domain, unlike [PHASE_INVERSION]'s time-domain trick: [cover] is split into
 * non-overlapping [FRAME_SIZE]-sample blocks, each forward-FFT'd ([fft]); a fixed,
 * content-independent range of the block's magnitude spectrum ([ELIGIBLE_BIN_START] onward,
 * [binsPerFrame] bins wide) is quantized and nudged to embed payload bits; the block is then
 * reconstructed with an inverse FFT ([ifft]) and rounded back to `Short` samples.
 * `covert-data/library/03_audio_steganography.md`'s account of "spectrogram LSB embedding" is
 * explicitly higher-capacity than phase-inversion, and this bins/frame budget is what delivers on
 * that for any reasonable [cover] length.
 *
 * **Why not a literal single-bit LSB flip on a raw FFT float**: it would not survive this
 * technique's own `encode()` round trip. [ifft] followed by rounding every reconstructed sample to
 * the nearest `Short` (PCM16 has no fractional samples) introduces small requantization noise in
 * the *reconstructed* spectrum that is generally larger than a raw float LSB, silently destroying a
 * naively-embedded bit before `decode()` ever recomputes the FFT. Instead this uses
 * **quantization-index modulation (QIM) on log-magnitude** (see [embedBitInBin]/[readBitFromBin]
 * and [QUANTIZATION_STEP]) — the standard way real spectral watermarking survives exactly this kind
 * of lossy round trip: the embedded bit is the parity of `round(ln(|X[k]|) / Δ)`, a discretized
 * quantity that round-trip noise cannot flip as long as it stays inside `Δ`'s bucket half-width.
 *
 * **Frame boundaries and windowing**: no window function (e.g. Hann) is applied — deliberately,
 * because with zero overlap between blocks, `ifft(fft(block))` of an **untouched** block
 * reconstructs the original samples up to pure floating-point rounding error (~1e-9 relative), far
 * below the 0.5-sample step needed to recover the exact original `Short`. That is what makes every
 * frame past the payload's last embedded bit — and the `cover.size % FRAME_SIZE` remainder samples
 * that fall outside any frame — come back bit-for-bit identical in [encode]'s output; a window
 * would break that property for no benefit, since there is no overlap-add reconstruction to manage
 * here in the first place.
 *
 * **Conjugate symmetry**: a real input's FFT satisfies `X[FRAME_SIZE - k] = conj(X[k])`. Every bin
 * [embedBitInBin] modifies, it also writes the mirror `X[FRAME_SIZE - k]` — skipping that would
 * corrupt [ifft]'s output with a spurious imaginary component. Bin 0 (DC) and bin `FRAME_SIZE / 2`
 * (Nyquist), which are real-valued and self-mirrored, are never touched (the eligible range starts
 * well past both and is capped well before the Nyquist bin).
 *
 * **Bit-to-(frame, bin) mapping**, exactly parallel to [PHASE_INVERSION]'s `bitIndex -> segment`
 * mapping: `frameIndex = bitIndex / binsPerFrame`, `binSlotInFrame = bitIndex % binsPerFrame`,
 * `binIndex = ELIGIBLE_BIN_START + binSlotInFrame`.
 *
 * **[stegoStrength]** (constructor parameter, default [DEFAULT_STEGO_STRENGTH], valid range
 * [MIN_STEGO_STRENGTH]..[MAX_STEGO_STRENGTH] for this technique) scales [binsPerFrame] — see its
 * own KDoc for the exact mapping. [encode] and [decode] MUST be called on instances constructed
 * with the *same* [stegoStrength] for a given carrier (it changes the bit-to-bin mapping exactly
 * like [technique] does) — a mismatched-strength decode is expected to fail the header magic/CRC
 * check like any other non-stego carrier, never to crash. [PHASE_INVERSION] and
 * [AudioStegoTechnique.MFSK] both ignore this constructor parameter entirely.
 *
 * ### Near-silent-frame skip (v2 format, deferred v6 follow-up)
 *
 * The scheme above embeds into every frame in sequence regardless of that frame's own loudness.
 * On a cover with genuine quiet passages (`AudioSampleCover.SPOKEN_WORD`'s burst/gap "syllable"
 * timing, whose gaps measure near-zero guard-band energy — see below), [LOG_MAGNITUDE_FLOOR]
 * forcing a handful of near-silent bins up to an audible floor produces a small click train
 * concentrated exactly in the quiet passages, worse contrast there than the same nudge buried in
 * louder content. Frame version 2 ([SPECTROGRAM_LSB_VERSION_2]) fixes this by skipping frames
 * that are themselves near-silent, leaving them byte-identical to the cover.
 *
 * **Eligibility signal, and why it's provably encode/decode-consistent:** a frame's *guard-band*
 * energy — the summed magnitude of bins `0 until ELIGIBLE_BIN_START` (see [guardBandEnergy]) —
 * against [SILENCE_GUARD_ENERGY_THRESHOLD]. Those bins are bins [embedBitInBin] never touches,
 * for *any* frame, eligible or not — so a frame's guard-band energy computed from [cover] at
 * encode time and computed from the resulting stego carrier at decode time are not merely close,
 * they are bit-for-bit the same input, always. There is no threshold-boundary edge case where
 * encode and decode could disagree about a frame's eligibility, unlike a whole-frame-energy or
 * time-domain-RMS signal would risk for an *eligible* (embedded-into) frame sitting near the
 * cutoff. [SILENCE_GUARD_ENERGY_THRESHOLD]'s own KDoc has the measured numbers behind 100,000.
 *
 * **Header/version bootstrapping:** a decoder cannot know a carrier's version before reading its
 * header, so the header's own frames (`0 until headerFrameCount`, see that function) are *always*
 * embedded densely, exactly like v1, regardless of loudness or version — the eligibility skip
 * only applies to the payload+trailer that follows, once the version byte has actually been read.
 * At 7 header bytes (56 bits) this is at most a handful of frames, bounded cost even if the cover
 * happens to open on silence.
 *
 * **Real, measured effect (offline JVM measurement, `AudioSampleCover.SOFT_SYNTH`/`SPOKEN_WORD`,
 * both at their default `stegoStrength`):** on `SPOKEN_WORD`'s genuine burst/gap silence, frames
 * that land in the payload+trailer region and inside a gap are now left completely untouched
 * (measured residual literally 0 vs. the old dense algorithm's ~-74 dBFS there) — the click-train
 * case this fix targets, fixed. **This does NOT fix `AudioStegoScreen.kt`'s documented "faint
 * crackle in the soft-synth cover's near-silent fade-in"** honesty caption: `SOFT_SYNTH`'s
 * fade-in falls inside the header's own always-dense leading frames (at this technique's default
 * `stegoStrength`, `headerFrameCount` alone spans the whole attack ramp), which the bootstrapping
 * constraint above deliberately never silence-gates. That caption stays accurate and unchanged.
 *
 * **Capacity is now content-dependent, not a pure arithmetic formula**, for this technique only —
 * [totalCapacityBytes] scans [cover] once (see [spectrogramLsbCapacityBytesV2]) rather than
 * computing `(numFrames * binsPerFrame) / 8`. [totalCapacityBytes]/[maxPayloadBytes]/[canEmbed]
 * are `by lazy` (harmless for [PHASE_INVERSION]/[MFSK], whose formulas stay O(1)) so a construct-
 * then-[decode] caller — the receive pipeline's auto-detect loop — never pays this scan's cost;
 * only a caller that actually reads [maxPayloadBytes] (the technique-picker UI's live capacity
 * counter) or calls [encode] does.
 *
 * **Backward compatibility (INV-9):** v1 carriers — every spectrogram-LSB firefly caught before
 * this fix — decode exactly as before, byte-for-byte the original dense algorithm; [encode]
 * always writes v2 going forward. [decodeSpectrogramLsb] accepts either version byte and branches
 * its payload+trailer extraction algorithm on which one the header actually declares.
 *
 * ## MFSK technique
 *
 * Trades capacity for resilience against in-clip noise/corruption, via multi-frequency on/off
 * tone keying additively mixed into [cover] plus real Reed-Solomon forward error correction
 * (reusing [dev.herakles.nightjar.ReedSolomon], the same GF(2^8) implementation Module 3's
 * [AcousticCarrier] already uses and tests) — unlike [PHASE_INVERSION]/[SPECTROGRAM_LSB], this
 * recovers a codeword with some symbol blocks corrupted, not just bit-exact round trips.
 *
 * **Measured lossy-codec survival (gate-35, task W0-C) — narrower than "robust" implies:** MFSK
 * round-trips through AAC-LC >= 128 kbps and MP3 128 kbps, but fails after Opus 16-64 kbps (both
 * voip and audio modes) and AAC 64-96 kbps. AAC at 64 kbps removes the near-ultrasonic tone band
 * ([MFSK_BASE_BIN] onward) entirely; the other failures are quantization noise beyond
 * Reed-Solomon's correction, not a clean binary pass/fail on "was it recompressed." This is why
 * `AudioStegoScreen.kt`'s "open a recording" never attempts a decode on a compressed file at
 * all — the earlier draft's "genuinely lossy-channel-robust" framing overclaimed relative to this
 * measurement, and has been replaced here and in that screen's copy.
 *
 * **Modulation**: [cover] is split into non-overlapping [FRAME_SIZE]-sample symbol blocks (the
 * same block size [SPECTROGRAM_LSB] uses). Each block carries exactly one byte via
 * [MFSK_TONE_COUNT] simultaneous, independently-on/off tone channels — bit `i` (0-7, MSB-first,
 * i.e. bit 0 = `0x80`) of the byte maps to tone channel `i`, present in the block's synthesized
 * signal iff that bit is 1. Tone channel `i`'s frequency is bin `[MFSK_BASE_BIN] + i` (bin width
 * `NightjarAcoustics.SAMPLE_RATE_HZ / FRAME_SIZE`) — [MFSK_BASE_BIN] = 420 (~19.7 kHz) sits
 * comfortably clear of Module 3's own NEAR_ULTRASONIC tone grid (bins 342-405,
 * `NightjarAcoustics.kt`) and well below the Nyquist bin (512), so this never collides with that
 * module's frequency plan even though both live in the same app.
 *
 * **Additive, not replacing**: each active tone's sine contribution (amplitude [TONE_AMPLITUDE])
 * is summed on top of [cover]'s own samples for that block — the cover's own content survives
 * underneath, exactly matching "mixed into an existing carrier" the way [PHASE_INVERSION]'s
 * secondary signal is mixed in, rather than synthesizing a fresh transmission the way Module 3's
 * live modem does.
 *
 * Clipping fix (design-v5.md §12.2): [TONE_AMPLITUDE] × up to [MFSK_TONE_COUNT] simultaneous
 * tones can sum past int16 range on its own. [encodeMfsk] computes the full (cover + tones)
 * signal for the whole clip in floating point, unclamped, and — only if that signal's peak
 * magnitude would exceed [MFSK_SAMPLE_CEILING] — applies a single constant gain to the *entire*
 * clip before rounding to `Short`. The whole clip is quieter by that one factor when a gain is
 * needed; [cover]'s own relative dynamics (including the untouched tail past the MFSK codeword)
 * are otherwise unchanged — there is no per-block/per-sample scaling, so there's no block-seam
 * artifact to introduce. (An earlier version of this fix used a per-block gain split instead;
 * that ducked the cover to a different, sometimes-zero gain every ~21ms — an audible click/
 * dropout pattern, and a real violation of "the cover's own content survives underneath" above —
 * so it was replaced with this single-gain approach.) A uniform gain never changes [decodeMfsk]'s
 * verdict either: its tone-vs-guard-bin-median margin is a *relative* comparison within each
 * block's own FFT, so scaling every sample by the same constant leaves every margin unchanged.
 *
 * **Click fix (Gate 8 round 4)**: the gain fix above stops [MFSK]'s samples from clipping, but
 * [encodeMfsk]'s per-block tone synthesis still switches each tone channel fully on or fully off
 * at every [FRAME_SIZE]-sample block boundary — a rectangular on/off gate. Owner-reported "crackle
 * and pops in the first few seconds" on real hardware traced (offline JVM measurement against this
 * class's own [encode]/[decode], not a device capture) to exactly that: on the SOFT_SYNTH sample
 * cover with a 30-byte payload, the < 16 kHz ("audible-band") part of `stego - gain*cover` pooled
 * across all 63 internal block boundaries measured -34.05 dBFS *before* the gain fix and -42.14
 * dBFS *after* it (gain alone helps some, since it also removed ~127 clipped samples), against
 * -85..-88 dBFS mid-symbol (i.e. away from any boundary) in both cases — a >40 dB boundary-vs-
 * mid-symbol gap either way, the signature of a hard edge, not of the near-ultrasonic tones simply
 * being audible (which would show up mid-symbol too). [mfskToneEnvelope] applies a short
 * raised-cosine (Hann) ramp to each tone's amplitude across a boundary where it actually turns on
 * or off (silent in the adjacent block, or the clip's first/last block) — the receiving side has
 * no reason to care, since [MFSK_BASE_BIN]-relative tone frequencies are always an integer number
 * of cycles per [FRAME_SIZE]-sample block, so a tone that *stays* on across a boundary is already
 * phase-continuous there and gets no ramp at all (full, constant amplitude throughout). A
 * [MFSK_RAMP_SAMPLES] = 32-sample ramp (a 32-value sweep, 8-128 samples, against both bundled
 * covers) was the smallest tested that pushed the boundary-locked audible-band level below -70
 * dBFS on both covers (SOFT_SYNTH -75.27 dBFS, SPOKEN_WORD -74.52 dBFS; a 33+ dB reduction from
 * the post-gain-fix baseline either way) while leaving [decodeMfsk]'s own per-tone-bin margin
 * comfortably clear of [MFSK_DETECTION_MARGIN_DB]: the weakened "meant to be on" bins stayed >= 20
 * dB over threshold and no "meant to be off" bin crossed it (0 false positives on either cover).
 * Ramps beyond that headroom are not free: the same sweep found silent-bin leakage from the
 * ramp's own wider spectral main lobe climbing with ramp length (bin spacing is only 46.875 Hz,
 * one FFT bin), crossing [MFSK_DETECTION_MARGIN_DB] and producing actual false-positive bit
 * flips (corrected-error counts rising, then `ReedSolomon.decode` failing outright) starting
 * around a 64-96 sample ramp on the SOFT_SYNTH cover — this is exactly why the ramp is tuned by
 * measurement rather than picked generously "to be safe." [decodeMfsk] itself needed no change.
 *
 * **Detection (decode)**: FFTs each candidate block (reusing [fft]) and reads tone channel `i` as
 * active if its magnitude exceeds the local noise floor (the median magnitude of a
 * [MFSK_GUARD_BINS]-wide guard band around the [MFSK_TONE_COUNT] tone bins, excluding those bins
 * themselves) by at least [MFSK_DETECTION_MARGIN_DB] dB — the same margin-over-median principle
 * [AcousticCarrier]'s own marker detector uses (its 15 dB default), reimplemented here
 * independently since that method is private to [AcousticCarrier].
 *
 * **Framing + FEC — single fixed-size Reed-Solomon block, deliberately no multi-block chunking**:
 * [buildFrame]'s usual header+payload+trailer byte layout is zero-padded up to exactly
 * [RS_DATA_BYTES] bytes, then `ReedSolomon.encode(paddedFrame, [RS_PARITY_BYTES])` produces a
 * fixed [MFSK_CODEWORD_BYTES]-byte codeword — one byte per symbol block, always
 * [MFSK_CODEWORD_BYTES] blocks regardless of actual payload length (short payloads just mean more
 * zero padding inside the RS-protected block; [buildFrame]'s own declared `length` field is what
 * decode trusts for the real payload boundary). This is why MFSK's capacity is a *fixed* ceiling
 * ([RS_DATA_BYTES] `- 11` bytes overhead) once [cover] is long enough to hold one codeword's worth
 * of symbol blocks, rather than scaling with any *additional* cover length the way
 * [PHASE_INVERSION]/[SPECTROGRAM_LSB]'s capacity does — a deliberate simplification for this demo
 * codec, not a limitation of Reed-Solomon itself. `ReedSolomon.decode` returning `null`
 * (uncorrectable — more than `[RS_PARITY_BYTES] / 2` byte errors) is what makes
 * `DecodeFailure.UNRECOVERABLE_FEC` genuinely reachable for this technique, unlike
 * [PHASE_INVERSION]/[SPECTROGRAM_LSB]/[ImageStegoCarrier], none of which apply any FEC (see the
 * "Framing" section below).
 *
 * ## Phase-coding technique (deferred v6 follow-up)
 *
 * The literature technique the HANDOFF.md deferred-follow-ups note was actually contrasting
 * [PHASE_INVERSION] against: Bender, Gruhl, Morimoto & Lu, "Techniques for Data Hiding," *IBM
 * Systems Journal* 35(3-4), 1996 — not documented in `covert-data/library/03_audio_steganography.md`
 * (that doc's own "phase inversion" section is [PHASE_INVERSION]'s dual-mono trick, a different,
 * simpler technique), so this section cites the source directly, matching the precedent
 * `covert-data/module_2_audio_steganography/README.md` already sets for citing an external paper
 * (Kirovski & Malvar) the local library doc doesn't cover.
 *
 * **Core idea**: unlike every other technique in this class, [PHASE_CODING] never touches
 * magnitude at all — only phase, and only in a bounded, DC-adjacent slice of the spectrum
 * ([PHASE_BASE_BIN] through `PHASE_BASE_BIN + PHASE_BITS_PER_GROUP - 1`). [cover] is split into
 * non-overlapping [FRAME_SIZE]-sample segments, grouped [PHASE_SEGMENTS_PER_GROUP] at a time. In
 * each group:
 *  1. The group's *first* segment carries [PHASE_BITS_PER_GROUP] payload bits — one per dedicated
 *     bin — via absolute phase substitution: bit 0 sets that bin's phase to `+PI/2`, bit 1 to
 *     `-PI/2` (Bender's own convention), magnitude left untouched.
 *  2. Every *other* segment in the group re-derives its new phase, per bin, as `newPhase[k] =
 *     newPhase[k-1] + (originalPhase[k] - originalPhase[k-1])` — i.e. it keeps its *original*
 *     phase delta relative to the (possibly-substituted) previous segment, rather than being
 *     touched directly. This is what lets an abrupt phase override at one segment fade back
 *     toward the cover's own natural phase trajectory across the rest of the group, instead of a
 *     single hard jump — the literature's own mechanism for keeping this audible-but-tolerable
 *     rather than an outright click, not a bolted-on ramp the way [MFSK]'s click fix needed one.
 *
 * **Why this is real "phase coding," not another [PHASE_INVERSION]**: [PHASE_INVERSION] never
 * computes or examines phase at all — it is a time-domain dual-mono amplitude trick. This
 * technique is genuinely frequency-domain, substitutes phase directly (not merely correlated with
 * it), and needs no second channel — [encode]'s output here is mono, [cover]'s own length,
 * exactly like [SPECTROGRAM_LSB].
 *
 * **Decode** only ever reads a group's *first* segment — the continuity segments exist purely for
 * encode-side smoothness and carry no bits of their own, so [decodePhaseCoding] never inspects
 * them. Reading is a sign check on the recomputed phase at each dedicated bin (`phase > 0` → bit
 * 0, matching encode's `+PI/2`; `phase <= 0` → bit 1) — a robust threshold, not a near-zero
 * tiebreak, mirroring [PHASE_INVERSION]'s own "robust sign check" reasoning: `+PI/2` and `-PI/2`
 * sit a full PI apart, so round-trip (`ifft`-then-round-to-`Short`-then-`fft`) phase noise would
 * have to be enormous to flip which side of zero a recovered phase lands on.
 *
 * **[PHASE_BASE_BIN] = 20** (~938 Hz at [NightjarAcoustics.SAMPLE_RATE_HZ]) — not
 * [SPECTROGRAM_LSB]'s `ELIGIBLE_BIN_START` = 32 (~1500 Hz), and not near [MFSK]'s ~19.7 kHz tone
 * band, so this technique's dedicated bins stay clear of both others' spectral real estate on a
 * cover this app also uses for them. Low-frequency, matching where the literature places phase
 * coding, but **not the lowest available bins** — an earlier choice of bin 4 (~188 Hz) measured
 * badly on `AudioSampleCover.SOFT_SYNTH` specifically: that cover's own 220 Hz fundamental sits
 * right inside bins 4-7, so forcing phase there collided with real, substantial musical energy
 * (offline JVM measurement: -16.87 dBFS whole-clip residual, a ~22,000-magnitude single-sample
 * jump at a segment boundary — a loud click, not the "faint" artifact every other technique in
 * this class settles for). Moving to bin 20-23, clear of SOFT_SYNTH's fundamental and its first
 * three harmonics (220/440/660 Hz), measured -43.75 dBFS and a ~1,100-magnitude worst jump on the
 * same cover — the same mistake [SPECTROGRAM_LSB]'s own `ELIGIBLE_BIN_START` KDoc already warns
 * about avoiding ("comfortably past DC and the highest-energy bass content"), just rediscovered
 * empirically here rather than anticipated. `AudioSampleCover.SPOKEN_WORD` (filtered noise, no
 * concentrated harmonics to collide with) improved more modestly, -36 to -43 dBFS depending on
 * strength -- consistent with the diagnosis: SPOKEN_WORD never had a bin-collision problem to fix
 * in the first place, since its energy is spread across the whole passband rather than
 * concentrated at a few frequencies.
 *
 * **Fidelity is measured, not owner-verified**: unlike [PHASE_INVERSION]/[SPECTROGRAM_LSB]/
 * [MFSK], no gate-8-style real-headphone listening pass has confirmed how this actually sounds --
 * the numbers above are offline residual/jump metrics, a proxy, not a substitute for an ear. Still
 * a real, measured improvement over the initial (badly broken) attempt, and still real, correct
 * literature-grounded phase coding (round-trip correctness was never in question, only fidelity).
 *
 * **Low, literature-honest capacity, by design**: [PHASE_BITS_PER_GROUP] = 4 bits cost
 * [PHASE_SEGMENTS_PER_GROUP] = 4 whole [FRAME_SIZE]-sample segments (4096 samples, ~85 ms at
 * [NightjarAcoustics.SAMPLE_RATE_HZ]) — three of every four segments carry zero payload bits of
 * their own, existing solely for phase continuity. This is not a missed optimization:
 * Bender's own paper describes phase coding's data rate as low *because* of exactly this
 * structure, and inventing a denser packing would stop this being the technique the deferred
 * follow-up asked for. Measured capacity on both 5 s bundled covers, screen default settings, is
 * in [AudioStegoScreenTest]/`AudioStegDetectorTest`'s own measured-capacity tables.
 *
 * ## Framing
 *
 * Identical shape to [ImageStegoCarrier]'s frame (architecture.md §5's acoustic framing adapted
 * to a wide length field, same as the image codec, since capacity scales with the cover rather
 * than being capped at a fixed size):
 *
 * ```
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ HEADER  (7 bytes)                                                       │
 * │   magic       1 byte   0x4E              ('N' — matches every other     │
 * │                                            module's framing magic)      │
 * │   version     1 byte   0x01                                             │
 * │   length      4 bytes  payload byte count, big-endian                   │
 * │   header_crc  1 byte   CRC-8 (poly 0x07) over the 6 preceding bytes      │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ PAYLOAD  (0..maxPayloadBytes bytes benign data)                          │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │ TRAILER  (4 bytes)                                                       │
 * │   payload_crc32  4 bytes  CRC-32 (IEEE 802.3) over the raw payload bytes │
 * └───────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * No forward error correction for [PHASE_INVERSION]/[SPECTROGRAM_LSB]: same as the image codec,
 * `DecodeResult.Success.correctedByteErrors` is always 0 for those two. [MFSK] is the exception —
 * it applies real Reed-Solomon FEC (see the "MFSK technique" section above) and reports the
 * actual `RsDecodeResult.correctedErrors` count.
 *
 * **Version byte, [SPECTROGRAM_LSB] only:** [PHASE_INVERSION] and [MFSK] always write and require
 * exactly [VERSION] (0x01) — their formats are untouched by the near-silent-frame-skip addition
 * above. [SPECTROGRAM_LSB] writes [SPECTROGRAM_LSB_VERSION_2] (0x02) and accepts either version on
 * decode; see that section for why.
 *
 * ## Capacity
 *
 * `maxPayloadBytes` is derived from [cover]'s *mono* sample count and [technique]'s embedding
 * rate, same shape as the image codec's bitmap-dimension-derived rule — this does NOT change just
 * because [PHASE_INVERSION]'s [encode] output is stereo-interleaved (`2 * cover.size` samples);
 * the capacity math is entirely about how many segments/frames fit in the cover's mono duration:
 *
 * ```
 * totalCapacityBytes (PHASE_INVERSION)  = floor(floor(cover.size / SEGMENT_SAMPLES) / 8)
 * totalCapacityBytes (SPECTROGRAM_LSB)  = floor((numFrames * binsPerFrame) / 8)
 *                                          where numFrames = cover.size / FRAME_SIZE
 * totalCapacityBytes (MFSK)             = RS_DATA_BYTES if cover has room for one full codeword's
 *                                          worth of symbol blocks, else 0 (fixed ceiling, see the
 *                                          "MFSK technique" section above)
 * maxPayloadBytes                       = max(0, totalCapacityBytes - 11)
 * ```
 *
 * All three techniques are implemented. Constructing an instance is always safe except
 * [stegoStrength] range validation for [SPECTROGRAM_LSB] (see its KDoc above).
 */
class AudioStegoCarrier(
    private val cover: PcmAudio,
    private val technique: AudioStegoTechnique,
    private val stegoStrength: Int = DEFAULT_STEGO_STRENGTH,
) : CovertCarrier<PcmAudio> {

    init {
        if (technique == AudioStegoTechnique.SPECTROGRAM_LSB) {
            require(stegoStrength in MIN_STEGO_STRENGTH..MAX_STEGO_STRENGTH) {
                "stegoStrength must be in $MIN_STEGO_STRENGTH..$MAX_STEGO_STRENGTH for " +
                    "SPECTROGRAM_LSB, was $stegoStrength"
            }
        }
    }

    override val descriptor = ModuleDescriptor(
        id = ModuleId.AUDIO_STEGO_CODEC,
        displayName = "Audio Steganography",
        domain = CarrierDomain.AUDIO,
        role = ModuleRole.CARRIER,
    )

    /**
     * Eligible bins per frame for [AudioStegoTechnique.SPECTROGRAM_LSB], scaled by [stegoStrength]:
     * `stegoStrength * BINS_PER_STRENGTH_LEVEL`, capped so `ELIGIBLE_BIN_START + binsPerFrame - 1`
     * can never reach the Nyquist bin (`FRAME_SIZE / 2`) even if the strength range or
     * [BINS_PER_STRENGTH_LEVEL] change later. [BINS_PER_STRENGTH_LEVEL] = 8 makes the four valid
     * strengths (1..4) span 8/16/24/32 bins per frame — a deliberately modest range for a demo
     * codec: even the top of it (32 bins nudged per 1024-sample frame) leaves the large majority of
     * each frame's spectrum untouched. Computed (harmlessly) for every [technique], but only
     * [AudioStegoTechnique.SPECTROGRAM_LSB] actually uses it.
     */
    private val binsPerFrame: Int = (stegoStrength * BINS_PER_STRENGTH_LEVEL)
        .coerceAtMost(FRAME_SIZE / 2 - ELIGIBLE_BIN_START - 1)

    /**
     * `by lazy`, not eager (near-silent-frame-skip addition): [SPECTROGRAM_LSB]'s branch now
     * scans [cover] ([spectrogramLsbCapacityBytesV2]) rather than a pure O(1) formula. Deferring
     * the scan to first access means a construct-then-[decode] caller — [IncomingRouter]'s
     * auto-detect loop, the only caller that never reads this property — pays nothing extra;
     * only a caller that reads [maxPayloadBytes] (the technique picker's capacity counter) or
     * calls [encode] triggers it. Harmless no-op change for [PHASE_INVERSION]/[MFSK], whose
     * formulas stay cheap O(1) arithmetic either way.
     */
    private val totalCapacityBytes: Int by lazy {
        when (technique) {
            AudioStegoTechnique.PHASE_INVERSION -> phaseInversionCapacityBytes(cover.size)
            AudioStegoTechnique.SPECTROGRAM_LSB -> spectrogramLsbCapacityBytesV2(cover)
            AudioStegoTechnique.MFSK -> mfskCapacityBytes(cover.size)
            AudioStegoTechnique.PHASE_CODING -> phaseCodingCapacityBytes(cover.size)
        }
    }

    override val maxPayloadBytes: Int by lazy { (totalCapacityBytes - FRAME_OVERHEAD_BYTES).coerceAtLeast(0) }

    /**
     * True if [cover] has enough raw capacity, at this instance's [technique] (and
     * [stegoStrength] for [AudioStegoTechnique.SPECTROGRAM_LSB]), to hold even an empty-payload
     * frame ([FRAME_OVERHEAD_BYTES] bytes). Same role as [ImageStegoCarrier.canEmbed] (codec-H02
     * is the audio-codec twin of codec-H01): `maxPayloadBytes == 0` used to be ambiguous between
     * "this cover can embed an empty payload" and "this cover cannot hold a frame at all," and
     * only the second case actually made every `encode()` call throw, including
     * `encode(ByteArray(0))`, from a second, buried capacity check the caller had no way to
     * predict from `maxPayloadBytes` alone. Each `encode*` function below now checks this first,
     * with a message that says so directly; callers (the screens) use it to show "this cover is
     * too small to hide anything" instead of a bare `0 / 0 bytes` counter.
     */
    val canEmbed: Boolean by lazy { totalCapacityBytes >= FRAME_OVERHEAD_BYTES }

    override fun encode(payload: ByteArray): PcmAudio = when (technique) {
        AudioStegoTechnique.PHASE_INVERSION -> encodePhaseInversion(payload)
        AudioStegoTechnique.SPECTROGRAM_LSB -> encodeSpectrogramLsb(payload)
        AudioStegoTechnique.MFSK -> encodeMfsk(payload)
        AudioStegoTechnique.PHASE_CODING -> encodePhaseCoding(payload)
    }

    override fun decode(carrier: PcmAudio): DecodeResult = when (technique) {
        AudioStegoTechnique.PHASE_INVERSION -> decodePhaseInversion(carrier)
        AudioStegoTechnique.SPECTROGRAM_LSB -> decodeSpectrogramLsb(carrier)
        AudioStegoTechnique.MFSK -> decodeMfsk(carrier)
        AudioStegoTechnique.PHASE_CODING -> decodePhaseCoding(carrier)
    }

    // --- Phase-inversion encode/decode ---

    /**
     * Builds the dual-mono, phase-inverted, secondary-signal-mixed stereo carrier — see the class
     * KDoc's "Phase-inversion technique" section. Returns an interleaved stereo [PcmAudio] of size
     * `2 * cover.size`.
     */
    private fun encodePhaseInversion(payload: ByteArray): PcmAudio {
        require(canEmbed) {
            "cover (${cover.size} samples, $totalCapacityBytes-byte phase-inversion capacity) " +
                "cannot hold even an empty payload frame ($FRAME_OVERHEAD_BYTES bytes) -- this " +
                "cover is too small to hide anything"
        }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds this ${cover.size}-sample cover's " +
                "phase-inversion capacity of $maxPayloadBytes bytes"
        }
        val frame = buildFrame(payload)
        val totalBits = frame.size * 8
        val requiredSamples = totalBits.toLong() * SEGMENT_SAMPLES
        check(requiredSamples <= cover.size) {
            "required samples ($requiredSamples) exceed cover size (${cover.size}) -- should be " +
                "unreachable once canEmbed and the payload-size check above both hold"
        }

        val n = cover.size
        val stego = ShortArray(n * 2)
        // Dual-mono base: left channel is the untouched cover; right channel is the cover's
        // saturating negation ("invert the waveform polarity of only one channel").
        for (i in 0 until n) {
            stego[2 * i] = cover[i]
            stego[2 * i + 1] = negatedSample(cover[i])
        }
        // Mix the secondary (payload) signal into the right channel only, one small constant
        // amplitude offset per SEGMENT_SAMPLES-sample segment ("mix in a secondary low-volume
        // signal"). It is this offset -- not the phase-cancelled program content -- that survives
        // decode's L+R sum.
        for (bitIndex in 0 until totalBits) {
            val start = bitIndex * SEGMENT_SAMPLES
            val end = start + SEGMENT_SAMPLES
            val offset = if (bitAt(frame, bitIndex) == 0) MIX_AMPLITUDE else -MIX_AMPLITUDE
            for (i in start until end) {
                val rIndex = 2 * i + 1
                stego[rIndex] = (stego[rIndex] + offset)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        return stego
    }

    /**
     * Recovers a payload from an interleaved-stereo [carrier] by summing L+R per sample (the
     * "sum it down to mono" phase-cancellation step) and reading each segment's summed sign — see
     * the class KDoc's "Phase-inversion technique" section.
     */
    private fun decodePhaseInversion(carrier: PcmAudio): DecodeResult {
        if (carrier.size % 2 != 0) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier has an odd sample count (${carrier.size}); a phase-inversion carrier is " +
                    "interleaved stereo (L/R pairs), so a valid one is always even-length",
            )
        }
        val monoEquivalentSamples = carrier.size / 2
        val carrierCapacityBytes = phaseInversionCapacityBytes(monoEquivalentSamples)
        if (carrierCapacityBytes < HEADER_BYTES) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier ($monoEquivalentSamples mono-equivalent samples) is too short to hold a header",
            )
        }

        val header = extractBytes(carrier, 0, HEADER_BYTES)
        if ((header[0].toInt() and 0xFF) != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no stego magic byte found")
        }
        if ((header[1].toInt() and 0xFF) != VERSION) {
            return DecodeResult.Failure(
                DecodeFailure.HEADER_INVALID,
                "unsupported frame version ${header[1].toInt() and 0xFF}",
            )
        }
        val declaredHeaderCrc = header[6].toInt() and 0xFF
        val computedHeaderCrc = crc8(header, 0, 6)
        if (declaredHeaderCrc != computedHeaderCrc) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }

        val length = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (length < 0) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "negative declared length $length")
        }
        val maxPayloadForCarrier = (carrierCapacityBytes - FRAME_OVERHEAD_BYTES).coerceAtLeast(0)
        if (length > maxPayloadForCarrier) {
            return DecodeResult.Failure(
                DecodeFailure.PAYLOAD_TOO_LARGE,
                "declared length $length exceeds this carrier's $maxPayloadForCarrier-byte capacity",
            )
        }

        val payload = extractBytes(carrier, HEADER_BYTES * 8, length)
        val trailer = extractBytes(carrier, (HEADER_BYTES + length) * 8, TRAILER_BYTES)
        val declaredCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = crc32Of(payload)
        if (declaredCrc32 != actualCrc32) {
            return DecodeResult.Failure(DecodeFailure.INTEGRITY_MISMATCH, "payload CRC-32 mismatch")
        }

        return DecodeResult.Success(payload = payload, correctedByteErrors = 0)
    }

    // --- Spectrogram-LSB encode/decode ---

    /**
     * Builds the QIM-on-log-magnitude spectrogram carrier — see the class KDoc's "Spectrogram-LSB
     * technique" section. Returns a mono [PcmAudio] the same length as [cover] (unlike
     * [encodePhaseInversion], no stereo interleaving is needed here).
     */
    private fun encodeSpectrogramLsb(payload: ByteArray): PcmAudio {
        require(canEmbed) {
            "cover (${cover.size} samples, $totalCapacityBytes-byte spectrogram-LSB capacity at " +
                "stegoStrength=$stegoStrength, after skipping near-silent frames) cannot hold " +
                "even an empty payload frame ($FRAME_OVERHEAD_BYTES bytes) -- this cover is too " +
                "small (or too quiet) to hide anything"
        }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds this ${cover.size}-sample cover's " +
                "spectrogram-LSB capacity of $maxPayloadBytes bytes at stegoStrength=$stegoStrength " +
                "(after skipping near-silent frames)"
        }
        val frame = buildFrame(payload, version = SPECTROGRAM_LSB_VERSION_2)
        val totalBits = frame.size * 8
        val headerBits = HEADER_BYTES * 8
        val numFrames = cover.size / FRAME_SIZE
        val headerFrames = spectrogramLsbHeaderFrameCount(binsPerFrame)

        // Start from an exact copy of `cover`: every skipped frame (near-silent, or past the
        // last embedded bit), and every `cover.size % FRAME_SIZE` remainder sample outside any
        // frame, is left untouched rather than round-tripped through fft/ifft -- both are
        // already bit-exact this way, cheaper too (skips FFTs that would carry no bits anyway).
        val out = cover.copyOf()
        var bitIndex = 0
        var frameIndex = 0

        // Phase 1 -- header, dense and unconditional of loudness (class KDoc "near-silent-frame
        // skip (v2)": a decoder can't know the version before reading the header, so the
        // header's own frames are never silence-gated at either end). The `frameIndex < numFrames`
        // bound is defensive -- unreachable via the public API, since canEmbed (backed by
        // spectrogramLsbCapacityBytesV2's own `numFrames < headerFrames -> 0` guard) already
        // refuses to reach this loop for a cover too short to hold a header.
        while (frameIndex < headerFrames && frameIndex < numFrames && bitIndex < headerBits) {
            val start = frameIndex * FRAME_SIZE
            val re = DoubleArray(FRAME_SIZE) { i -> cover[start + i].toDouble() }
            val im = DoubleArray(FRAME_SIZE)
            fft(re, im)
            var binSlot = 0
            while (binSlot < binsPerFrame && bitIndex < headerBits) {
                embedBitInBin(re, im, ELIGIBLE_BIN_START + binSlot, bitAt(frame, bitIndex))
                binSlot++
                bitIndex++
            }
            ifft(re, im)
            for (i in 0 until FRAME_SIZE) out[start + i] = roundToShort(re[i])
            frameIndex++
        }
        check(bitIndex == headerBits) {
            "cover ran out of frames while embedding the header ($bitIndex/$headerBits header " +
                "bits) -- should be unreachable once canEmbed holds"
        }

        // Phase 2 -- payload + trailer, silence-skip-aware: frames whose guard-band energy is
        // under SILENCE_GUARD_ENERGY_THRESHOLD are left completely untouched.
        while (frameIndex < numFrames && bitIndex < totalBits) {
            val start = frameIndex * FRAME_SIZE
            val re = DoubleArray(FRAME_SIZE) { i -> cover[start + i].toDouble() }
            val im = DoubleArray(FRAME_SIZE)
            fft(re, im)
            if (guardBandEnergy(re, im) >= SILENCE_GUARD_ENERGY_THRESHOLD) {
                var binSlot = 0
                while (binSlot < binsPerFrame && bitIndex < totalBits) {
                    embedBitInBin(re, im, ELIGIBLE_BIN_START + binSlot, bitAt(frame, bitIndex))
                    binSlot++
                    bitIndex++
                }
                ifft(re, im)
                for (i in 0 until FRAME_SIZE) out[start + i] = roundToShort(re[i])
            }
            frameIndex++
        }
        check(bitIndex == totalBits) {
            "cover ran out of eligible (non-silent) frames while embedding the payload+trailer " +
                "($bitIndex/$totalBits bits) -- should be unreachable once canEmbed and the " +
                "payload-size check above both hold"
        }
        return out
    }

    /**
     * Recovers a payload from a spectrogram-LSB [carrier] by recomputing each needed frame's FFT
     * and reading the log-magnitude quantization parity back out of each eligible bin — see the
     * class KDoc's "Spectrogram-LSB technique" section. Uses THIS instance's [stegoStrength] /
     * [binsPerFrame] to interpret [carrier]; a carrier encoded at a different [stegoStrength] (or
     * never encoded at all) reads back as garbage bits and is expected to fail the magic/CRC check
     * below rather than throw.
     */
    private fun decodeSpectrogramLsb(carrier: PcmAudio): DecodeResult {
        val numFrames = carrier.size / FRAME_SIZE
        val headerFrames = spectrogramLsbHeaderFrameCount(binsPerFrame)
        if (numFrames < headerFrames) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier ($numFrames usable $FRAME_SIZE-sample frames) is too short to hold a " +
                    "header at $binsPerFrame bins/frame (stegoStrength=$stegoStrength)",
            )
        }

        // Header: always dense, regardless of eventual version -- class KDoc "near-silent-frame
        // skip (v2)"'s bootstrapping note. Unchanged from the pre-v2 algorithm.
        val header = extractSpectrogramBytes(carrier, 0, HEADER_BYTES)
        if ((header[0].toInt() and 0xFF) != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no stego magic byte found")
        }
        val headerVersion = header[1].toInt() and 0xFF
        if (headerVersion != VERSION && headerVersion != SPECTROGRAM_LSB_VERSION_2) {
            return DecodeResult.Failure(
                DecodeFailure.HEADER_INVALID,
                "unsupported frame version $headerVersion",
            )
        }
        val declaredHeaderCrc = header[6].toInt() and 0xFF
        val computedHeaderCrc = crc8(header, 0, 6)
        if (declaredHeaderCrc != computedHeaderCrc) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }

        val length = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (length < 0) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "negative declared length $length")
        }
        // A coarse, cheap (O(1), no FFT) upper bound shared by both versions -- v2's real,
        // content-dependent capacity can only be <= this dense-formula ceiling, so rejecting
        // anything above it here is always safe, and bounds the ByteArray/IntArray allocations
        // below against a forged/corrupted declared length before any real work happens.
        val maxPossibleForCarrier = (spectrogramLsbCapacityBytes(numFrames) - FRAME_OVERHEAD_BYTES).coerceAtLeast(0)
        if (length > maxPossibleForCarrier) {
            return DecodeResult.Failure(
                DecodeFailure.PAYLOAD_TOO_LARGE,
                "declared length $length exceeds this carrier's $maxPossibleForCarrier-byte capacity",
            )
        }

        val payload: ByteArray
        val trailer: ByteArray
        if (headerVersion == VERSION) {
            // v1: dense, byte-for-byte the original algorithm (INV-9) -- unchanged.
            payload = extractSpectrogramBytes(carrier, HEADER_BYTES * 8, length)
            trailer = extractSpectrogramBytes(carrier, (HEADER_BYTES + length) * 8, TRAILER_BYTES)
        } else {
            // v2: silence-skip-aware, starting at the fresh frame boundary the (always-dense)
            // header left off at -- mirrors encodeSpectrogramLsb's phase 2 exactly.
            val body = extractSpectrogramBytesV2(carrier, headerFrames, length + TRAILER_BYTES)
                ?: return DecodeResult.Failure(
                    DecodeFailure.PAYLOAD_TOO_LARGE,
                    "declared length $length exceeds this carrier's eligible (non-silent) capacity",
                )
            payload = body.copyOfRange(0, length)
            trailer = body.copyOfRange(length, length + TRAILER_BYTES)
        }

        val declaredCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = crc32Of(payload)
        if (declaredCrc32 != actualCrc32) {
            return DecodeResult.Failure(DecodeFailure.INTEGRITY_MISMATCH, "payload CRC-32 mismatch")
        }

        return DecodeResult.Success(payload = payload, correctedByteErrors = 0)
    }

    /**
     * Total embeddable bytes for a [AudioStegoTechnique.SPECTROGRAM_LSB] carrier/cover with
     * `numFrames` usable [FRAME_SIZE]-sample frames at this instance's [binsPerFrame]:
     * `floor((numFrames * binsPerFrame) / 8)`. Used both for [cover]'s own capacity and, at decode
     * time, for a carrier's own frame count (which may differ from [cover]'s).
     */
    private fun spectrogramLsbCapacityBytes(numFrames: Int): Int = (numFrames * binsPerFrame) / 8

    /**
     * Real, content-dependent capacity for the v2 (near-silent-frame-skip) format: the header
     * itself always costs exactly `HEADER_BYTES*8` bits (dense, unconditional), but
     * payload+trailer capacity only counts [spectrogramLsbHeaderFrameCount]-and-later frames whose
     * [guardBandEnergy] clears [SILENCE_GUARD_ENERGY_THRESHOLD] -- mirrors [encodeSpectrogramLsb]'s
     * own two-phase walk exactly, so this is genuinely "how many bytes would
     * [encodeSpectrogramLsb] actually fit," not an approximation of it.
     *
     * Deliberately `HEADER_BYTES*8`, NOT `headerFrameCount * binsPerFrame`, as the header's own
     * cost: whenever `HEADER_BYTES*8` isn't an exact multiple of [binsPerFrame], the header's own
     * last frame has bin slots [encodeSpectrogramLsb]'s phase 1 never writes to (it stops the
     * instant `bitIndex == headerBits`, mid-frame) -- those leftover slots are wasted, not
     * available to payload+trailer either (phase 2 always starts at a fresh frame boundary).
     * Counting `headerFrameCount * binsPerFrame` here would overcount by exactly that waste,
     * letting [maxPayloadBytes] claim more than [encodeSpectrogramLsb] can actually fit -- caught
     * by this class's own round-trip tests as `encode()`'s `check(bitIndex == totalBits)`
     * assertion failing on an accepted-but-too-large payload.
     *
     * One FFT per candidate frame; [totalCapacityBytes] caches this (`by lazy`), so it only runs
     * once per instance.
     */
    private fun spectrogramLsbCapacityBytesV2(cover: PcmAudio): Int {
        val numFrames = cover.size / FRAME_SIZE
        val headerFrames = spectrogramLsbHeaderFrameCount(binsPerFrame)
        if (numFrames < headerFrames) return 0 // too short to even hold a header
        var totalBits = HEADER_BYTES * 8
        var frameIndex = headerFrames
        while (frameIndex < numFrames) {
            val start = frameIndex * FRAME_SIZE
            val re = DoubleArray(FRAME_SIZE) { i -> cover[start + i].toDouble() }
            val im = DoubleArray(FRAME_SIZE)
            fft(re, im)
            if (guardBandEnergy(re, im) >= SILENCE_GUARD_ENERGY_THRESHOLD) {
                totalBits += binsPerFrame
            }
            frameIndex++
        }
        return totalBits / 8
    }

    /**
     * Payload+trailer extraction for a v2 carrier, starting at frame [startFrame] (the fresh
     * frame boundary the always-dense header extraction left off at). Mirrors
     * [encodeSpectrogramLsb]'s phase 2 exactly: walks frames forward from [startFrame], reading
     * [binsPerFrame] bits from each frame whose [guardBandEnergy] clears
     * [SILENCE_GUARD_ENERGY_THRESHOLD], skipping the rest untouched. Returns `null` -- never
     * throws -- if [carrier] runs out of frames before [numBytes] bytes are read; the caller maps
     * that to [DecodeFailure.PAYLOAD_TOO_LARGE], same as every other capacity-exceeded case in
     * this codebase.
     */
    private fun extractSpectrogramBytesV2(carrier: PcmAudio, startFrame: Int, numBytes: Int): ByteArray? {
        val numFrames = carrier.size / FRAME_SIZE
        val totalBits = numBytes * 8
        val bits = IntArray(totalBits)
        var bitIndex = 0
        var frameIndex = startFrame
        while (frameIndex < numFrames && bitIndex < totalBits) {
            val start = frameIndex * FRAME_SIZE
            val re = DoubleArray(FRAME_SIZE) { i -> carrier[start + i].toDouble() }
            val im = DoubleArray(FRAME_SIZE)
            fft(re, im)
            if (guardBandEnergy(re, im) >= SILENCE_GUARD_ENERGY_THRESHOLD) {
                var binSlot = 0
                while (binSlot < binsPerFrame && bitIndex < totalBits) {
                    bits[bitIndex] = readBitFromBin(re, im, ELIGIBLE_BIN_START + binSlot)
                    binSlot++
                    bitIndex++
                }
            }
            frameIndex++
        }
        if (bitIndex < totalBits) return null

        val out = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            var value = 0
            for (b in 0 until 8) value = (value shl 1) or bits[i * 8 + b]
            out[i] = value.toByte()
        }
        return out
    }

    /**
     * Recovers one payload bit from [carrier] at bit position [bitIndex], using this instance's
     * [binsPerFrame]/[ELIGIBLE_BIN_START] bit-to-(frame, bin) mapping (class KDoc). Recomputes the
     * FFT of the containing frame fresh on every call, mirroring [decodePhaseInversion]'s per-bit
     * [readBit] -- simplicity over caching, since a full unit-test-sized carrier decode is still
     * fast in absolute terms.
     */
    private fun readSpectrogramBit(carrier: PcmAudio, bitIndex: Int): Int {
        val frameIndex = bitIndex / binsPerFrame
        val binSlot = bitIndex % binsPerFrame
        val bin = ELIGIBLE_BIN_START + binSlot
        val start = frameIndex * FRAME_SIZE
        val re = DoubleArray(FRAME_SIZE) { i -> carrier[start + i].toDouble() }
        val im = DoubleArray(FRAME_SIZE)
        fft(re, im)
        return readBitFromBin(re, im, bin)
    }

    private fun extractSpectrogramBytes(carrier: PcmAudio, startBitIndex: Int, numBytes: Int): ByteArray {
        val out = ByteArray(numBytes)
        var bitIndex = startBitIndex
        for (i in 0 until numBytes) {
            var value = 0
            repeat(8) {
                value = (value shl 1) or readSpectrogramBit(carrier, bitIndex)
                bitIndex++
            }
            out[i] = value.toByte()
        }
        return out
    }

    // --- MFSK encode/decode ---

    /**
     * Builds the MFSK stego carrier: RS-encodes a single fixed-size, zero-padded [buildFrame]
     * block into [MFSK_CODEWORD_BYTES] bytes, then tone-modulates each byte into one
     * [FRAME_SIZE]-sample symbol block, additively mixed into [cover] starting at sample 0 — see
     * the class KDoc's "MFSK technique" section. Returns a mono [PcmAudio] the same length as
     * [cover] (only the first `MFSK_CODEWORD_BYTES * FRAME_SIZE` samples are touched).
     */
    private fun encodeMfsk(payload: ByteArray): PcmAudio {
        require(canEmbed) {
            "cover (${cover.size} samples) cannot hold an MFSK codeword ($MFSK_CODEWORD_BYTES " +
                "symbol blocks of $FRAME_SIZE samples each) -- this cover is too small to hide " +
                "anything"
        }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds MFSK's fixed capacity of $maxPayloadBytes bytes"
        }
        val frame = buildFrame(payload)
        check(frame.size <= RS_DATA_BYTES) {
            "frame (${frame.size} bytes) exceeds RS_DATA_BYTES=$RS_DATA_BYTES -- should be " +
                "unreachable given the maxPayloadBytes check above"
        }
        val paddedFrame = frame.copyOf(RS_DATA_BYTES) // zero-padded to exactly RS_DATA_BYTES
        val codeword = ReedSolomon.encode(paddedFrame, RS_PARITY_BYTES)
        check(codeword.size == MFSK_CODEWORD_BYTES) {
            "unexpected RS codeword size ${codeword.size}, expected $MFSK_CODEWORD_BYTES"
        }
        val neededSamples = MFSK_CODEWORD_BYTES.toLong() * FRAME_SIZE
        check(neededSamples <= cover.size) {
            "needed samples ($neededSamples) exceed cover size (${cover.size}) -- should be " +
                "unreachable once canEmbed holds"
        }

        // Clipping fix (design-v5.md §12.2, revised after rev-2 MAJOR): TONE_AMPLITUDE *
        // MFSK_TONE_COUNT simultaneous active tones can sum past int16 range on its own -- for
        // byteValue == 0xFF (all 8 tones active), the actual measured peak is ~47876, i.e.
        // essentially the naive 8*TONE_AMPLITUDE bound (these 8 tones sit at adjacent FFT bins,
        // 46.875 Hz apart, so they drift back into near-alignment well within one 1024-sample
        // block) -- measured 96-141 saturated samples per stego on the two bundled covers
        // pre-fix. A first version of this fix used a PER-BLOCK gain split (tone-first, cover
        // fallback); that traded clipping for something worse -- 13-15 of 64 blocks ducking the
        // cover to a *different* gain each time, including coverGain == 0.0 in several blocks,
        // producing ~21ms-period block-boundary sample jumps up to 150x the cover's own natural
        // jump (audible clicks/dropouts, and a real violation of "the cover's own content
        // survives underneath" below). Pass 1: synthesize the full (cover + tones) signal for
        // the whole clip in floating point, unclamped -- tones only inside the touched span,
        // cover passed through unchanged everywhere else -- and find its single peak magnitude
        // across the ENTIRE clip, not per block.
        val combined = DoubleArray(cover.size) { i -> cover[i].toDouble() }
        for (blockIndex in 0 until MFSK_CODEWORD_BYTES) {
            val byteValue = codeword[blockIndex].toInt() and 0xFF
            // Neighbor bytes decide, per tone channel, whether this block's occurrence of that
            // tone is a boundary transition (needs a ramp) or a continuation (doesn't) -- see the
            // class KDoc's "Click fix" paragraph. A block with no previous/next neighbor (the
            // codeword's first/last block) is treated as silent on that side, so a tone active
            // only at the very start or end of the clip still ramps in/out rather than snapping.
            val prevByteValue = if (blockIndex > 0) codeword[blockIndex - 1].toInt() and 0xFF else 0
            val nextByteValue = if (blockIndex < MFSK_CODEWORD_BYTES - 1) codeword[blockIndex + 1].toInt() and 0xFF else 0
            val start = blockIndex * FRAME_SIZE
            for (i in 0 until FRAME_SIZE) {
                var toneSample = 0.0
                for (bit in 0 until MFSK_TONE_COUNT) {
                    val bitMask = 1 shl (MFSK_TONE_COUNT - 1 - bit) // MSB-first: bit 0 == 0x80
                    if (byteValue and bitMask != 0) {
                        val risingEdge = prevByteValue and bitMask == 0
                        val fallingEdge = nextByteValue and bitMask == 0
                        val envelope = mfskToneEnvelope(i, risingEdge, fallingEdge)
                        val freqHz = (MFSK_BASE_BIN + bit) * NightjarAcoustics.SAMPLE_RATE_HZ.toDouble() / FRAME_SIZE
                        toneSample += sin(2.0 * PI * freqHz * i / NightjarAcoustics.SAMPLE_RATE_HZ) * TONE_AMPLITUDE * envelope
                    }
                }
                combined[start + i] += toneSample
            }
        }
        var peak = 0.0
        for (value in combined) {
            val a = abs(value)
            if (a > peak) peak = a
        }

        // Pass 2: ONE constant gain for the whole clip -- 1.0 (no attenuation, the byte-exact
        // common case) whenever the clip's own peak already fits under MFSK_SAMPLE_CEILING,
        // otherwise ceiling/peak. Applying the same scalar to every sample (touched span AND the
        // untouched tail alike) is what makes this safe: decode()'s tone-vs-guard-bin-median
        // margin is relative, so a uniform gain never changes which bins read as "active," and
        // there is no per-block seam left to click at -- every adjacent-sample jump in the
        // output is bounded by `gain * (that jump in cover + that jump in the raw tone
        // waveform) + 1` (rounding), the same relationship the ungained cover already had,
        // just uniformly scaled. See [AudioStegoCarrierTest]'s no-discontinuity test.
        val gain = if (peak <= MFSK_SAMPLE_CEILING || peak == 0.0) 1.0 else MFSK_SAMPLE_CEILING / peak
        val out = ShortArray(cover.size)
        for (i in combined.indices) {
            out[i] = roundToShort(combined[i] * gain)
        }
        return out
    }

    /**
     * Recovers a payload from an MFSK [carrier]: demodulates [MFSK_CODEWORD_BYTES] symbol blocks
     * back into a codeword via per-tone magnitude-over-noise-floor thresholding
     * ([demodulateMfskBlock]), RS-decodes it (`null` -- uncorrectable -- becomes
     * [DecodeFailure.UNRECOVERABLE_FEC]), then parses the recovered frame exactly like
     * [decodePhaseInversion]/[decodeSpectrogramLsb] already do — see the class KDoc's "MFSK
     * technique" section.
     */
    private fun decodeMfsk(carrier: PcmAudio): DecodeResult {
        val neededSamples = MFSK_CODEWORD_BYTES.toLong() * FRAME_SIZE
        if (carrier.size < neededSamples) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier (${carrier.size} samples) is too short to hold an MFSK codeword " +
                    "($MFSK_CODEWORD_BYTES symbol blocks of $FRAME_SIZE samples each)",
            )
        }

        val receivedCodeword = ByteArray(MFSK_CODEWORD_BYTES) { blockIndex ->
            demodulateMfskBlock(carrier, blockIndex).toByte()
        }
        val rsResult = ReedSolomon.decode(receivedCodeword, RS_PARITY_BYTES)
            ?: return DecodeResult.Failure(
                DecodeFailure.UNRECOVERABLE_FEC,
                "Reed-Solomon could not correct the received MFSK codeword " +
                    "(more than ${RS_PARITY_BYTES / 2} byte errors)",
            )
        val paddedFrame = rsResult.data // RS_DATA_BYTES long, zero-padded past the real frame

        if ((paddedFrame[0].toInt() and 0xFF) != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no stego magic byte found")
        }
        if ((paddedFrame[1].toInt() and 0xFF) != VERSION) {
            return DecodeResult.Failure(
                DecodeFailure.HEADER_INVALID,
                "unsupported frame version ${paddedFrame[1].toInt() and 0xFF}",
            )
        }
        val header = paddedFrame.copyOfRange(0, HEADER_BYTES)
        val declaredHeaderCrc = header[6].toInt() and 0xFF
        val computedHeaderCrc = crc8(header, 0, 6)
        if (declaredHeaderCrc != computedHeaderCrc) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }
        val length = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (length < 0 || HEADER_BYTES + length + TRAILER_BYTES > RS_DATA_BYTES) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "invalid declared length $length")
        }
        val payload = paddedFrame.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
        val trailer = paddedFrame.copyOfRange(HEADER_BYTES + length, HEADER_BYTES + length + TRAILER_BYTES)
        val declaredCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = crc32Of(payload)
        if (declaredCrc32 != actualCrc32) {
            return DecodeResult.Failure(DecodeFailure.INTEGRITY_MISMATCH, "payload CRC-32 mismatch")
        }
        return DecodeResult.Success(payload = payload, correctedByteErrors = rsResult.correctedErrors)
    }

    /**
     * Fixed MFSK capacity: [RS_DATA_BYTES] once [cover] has room for one full codeword's worth of
     * [MFSK_CODEWORD_BYTES] symbol blocks, else 0 — see the class KDoc's "MFSK technique" section
     * for why this is a fixed ceiling rather than scaling with additional cover length.
     */
    private fun mfskCapacityBytes(sampleCount: Int): Int =
        if (sampleCount / FRAME_SIZE >= MFSK_CODEWORD_BYTES) RS_DATA_BYTES else 0

    /**
     * Demodulates symbol block [blockIndex] of [carrier] back into a byte: FFTs the block
     * (reusing [fft]), computes a local noise-floor median from the [MFSK_GUARD_BINS]-wide guard
     * band around the [MFSK_TONE_COUNT] tone bins (excluding those bins themselves), and reads
     * each tone bin as active (bit=1) if its magnitude exceeds that floor by at least
     * [MFSK_DETECTION_MARGIN_DB] — mirrors `AcousticCarrier.markerToneScore`'s margin-over-median
     * technique (that method is private to `AcousticCarrier`, so this is an independent copy
     * adapted for [MFSK_TONE_COUNT] simultaneous candidate bins instead of 2).
     */
    private fun demodulateMfskBlock(carrier: PcmAudio, blockIndex: Int): Int {
        val start = blockIndex * FRAME_SIZE
        val re = DoubleArray(FRAME_SIZE) { i -> carrier[start + i].toDouble() }
        val im = DoubleArray(FRAME_SIZE)
        fft(re, im)
        val magnitudes = DoubleArray(FRAME_SIZE / 2 + 1) { bin -> sqrt(re[bin] * re[bin] + im[bin] * im[bin]) }

        val toneBins = (0 until MFSK_TONE_COUNT).map { MFSK_BASE_BIN + it }.toSet()
        val guardRange = (MFSK_BASE_BIN - MFSK_GUARD_BINS) until (MFSK_BASE_BIN + MFSK_TONE_COUNT + MFSK_GUARD_BINS)
        val floorMagnitudes = guardRange
            .filter { it !in toneBins && it in magnitudes.indices }
            .map { magnitudes[it] }
        val floorDb = magnitudeDb(medianOf(floorMagnitudes))

        var byteValue = 0
        for (bit in 0 until MFSK_TONE_COUNT) {
            val toneDb = magnitudeDb(magnitudes[MFSK_BASE_BIN + bit])
            val active = (toneDb - floorDb) >= MFSK_DETECTION_MARGIN_DB
            byteValue = (byteValue shl 1) or (if (active) 1 else 0)
        }
        return byteValue
    }

    /** `20*log10(magnitude)`, floored so an exactly-silent bin doesn't produce `-Infinity`. */
    private fun magnitudeDb(magnitude: Double): Double = 20.0 * log10(max(magnitude, 1e-9))

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    // --- Phase-coding encode/decode ---

    /**
     * Builds the phase-substituted, delta-chained carrier -- see the class KDoc's "Phase-coding
     * technique" section. Returns a mono [PcmAudio] the same length as [cover] (like
     * [SPECTROGRAM_LSB], no stereo interleaving needed, unlike [PHASE_INVERSION]).
     */
    private fun encodePhaseCoding(payload: ByteArray): PcmAudio {
        require(canEmbed) {
            "cover (${cover.size} samples, $totalCapacityBytes-byte phase-coding capacity) " +
                "cannot hold even an empty payload frame ($FRAME_OVERHEAD_BYTES bytes) -- this " +
                "cover is too small to hide anything"
        }
        require(payload.size <= maxPayloadBytes) {
            "payload of ${payload.size} bytes exceeds this ${cover.size}-sample cover's " +
                "phase-coding capacity of $maxPayloadBytes bytes"
        }
        val frame = buildFrame(payload)
        val totalBits = frame.size * 8
        val numSegments = cover.size / FRAME_SIZE
        val numGroups = numSegments / PHASE_SEGMENTS_PER_GROUP
        check(totalBits <= numGroups.toLong() * PHASE_BITS_PER_GROUP) {
            "frame bits ($totalBits) exceed available groups " +
                "(${numGroups.toLong() * PHASE_BITS_PER_GROUP}) -- should be unreachable once " +
                "canEmbed and the payload-size check above both hold"
        }

        val out = cover.copyOf()
        var bitIndex = 0
        var groupIndex = 0
        while (groupIndex < numGroups && bitIndex < totalBits) {
            val groupStart = groupIndex * PHASE_SEGMENTS_PER_GROUP

            // Fresh FFT of every segment in this group, before any modification -- both the
            // magnitude (never touched) and the ORIGINAL phase (needed by every segment's own
            // delta-chain below, including segment 0's, before it gets overwritten) come from
            // this untouched pass.
            val re = Array(PHASE_SEGMENTS_PER_GROUP) { DoubleArray(FRAME_SIZE) }
            val im = Array(PHASE_SEGMENTS_PER_GROUP) { DoubleArray(FRAME_SIZE) }
            for (s in 0 until PHASE_SEGMENTS_PER_GROUP) {
                val start = (groupStart + s) * FRAME_SIZE
                for (i in 0 until FRAME_SIZE) re[s][i] = cover[start + i].toDouble()
                fft(re[s], im[s])
            }
            val originalPhase = Array(PHASE_SEGMENTS_PER_GROUP) { s ->
                DoubleArray(PHASE_BITS_PER_GROUP) { b -> atan2(im[s][PHASE_BASE_BIN + b], re[s][PHASE_BASE_BIN + b]) }
            }
            val magnitude = Array(PHASE_SEGMENTS_PER_GROUP) { s ->
                DoubleArray(PHASE_BITS_PER_GROUP) { b ->
                    val bin = PHASE_BASE_BIN + b
                    sqrt(re[s][bin] * re[s][bin] + im[s][bin] * im[s][bin])
                }
            }

            // Segment 0: substitute phase at each dedicated bin this group still has a bit for
            // (+PI/2 for bit 0, -PI/2 for bit 1); any bin beyond this group's own last bit (only
            // possible in the final, partially-filled group) keeps its original phase. Segments
            // 1..N-1: re-derive new phase per bin as newPhase[s] = newPhase[s-1] +
            // (originalPhase[s] - originalPhase[s-1]) -- the delta-chain that lets the group fade
            // back toward the cover's own natural phase trajectory (class KDoc). For a bin this
            // group never substituted, newPhase[0] == originalPhase[0], so the chain reproduces
            // every segment's own original phase exactly -- an untouched bin stays untouched
            // across the whole group, not just at segment 0.
            val bitsThisGroup = minOf(PHASE_BITS_PER_GROUP, totalBits - bitIndex)
            val newPhase = Array(PHASE_SEGMENTS_PER_GROUP) { DoubleArray(PHASE_BITS_PER_GROUP) }
            for (b in 0 until PHASE_BITS_PER_GROUP) {
                newPhase[0][b] = if (b < bitsThisGroup) {
                    if (bitAt(frame, bitIndex + b) == 0) PHASE_BIT_ZERO else PHASE_BIT_ONE
                } else {
                    originalPhase[0][b]
                }
                for (s in 1 until PHASE_SEGMENTS_PER_GROUP) {
                    newPhase[s][b] = newPhase[s - 1][b] + (originalPhase[s][b] - originalPhase[s - 1][b])
                }
            }
            bitIndex += bitsThisGroup

            // Apply: each segment's dedicated bins become (magnitude, new phase), mirrored for
            // conjugate symmetry (same requirement embedBitInBin's own KDoc documents for
            // SPECTROGRAM_LSB), then ifft + round + writeback for the whole group.
            //
            // Every segment's magnitude (not just segment 0's) is floored to
            // max(originalMagnitude, PHASE_MAGNITUDE_FLOOR) at bins this group actually carries a
            // bit on (b < bitsThisGroup): a near-silent bin's phase is numerically unstable
            // (atan2 of two near-zero floats is dominated by rounding noise), both here and when
            // [readPhaseCodingBit] recomputes it from the round-tripped carrier -- the same
            // failure mode SPECTROGRAM_LSB's LOG_MAGNITUDE_FLOOR exists to prevent for its own
            // log-index computation. `AudioSampleCover.SPOKEN_WORD` -- which has genuine
            // near-silent passages -- failed to decode its own header at all before this floor
            // existed. Flooring every segment independently (not forcing a shared value -- each
            // still keeps its own natural level once that level clears the floor) also removes
            // any risk of a segment-to-segment magnitude mismatch at these bins, though that
            // turned out NOT to be the real driver of this technique's larger fidelity problem --
            // see [PHASE_BASE_BIN]'s own KDoc for the actual cause (a bin-choice collision with
            // real cover content) and its fix.
            for (s in 0 until PHASE_SEGMENTS_PER_GROUP) {
                for (b in 0 until bitsThisGroup) {
                    val bin = PHASE_BASE_BIN + b
                    val mirror = FRAME_SIZE - bin
                    val mag = maxOf(magnitude[s][b], PHASE_MAGNITUDE_FLOOR)
                    val newRe = mag * cos(newPhase[s][b])
                    val newIm = mag * sin(newPhase[s][b])
                    re[s][bin] = newRe
                    im[s][bin] = newIm
                    re[s][mirror] = newRe
                    im[s][mirror] = -newIm
                }
                // Unused bins this group never substituted (only possible in the final,
                // partially-filled group) keep their bare original magnitude AND phase --
                // newPhase[s][b] already equals originalPhase[s][b] there (see above), so this
                // leaves them genuinely untouched, matching every other technique's own
                // "past the last embedded bit, byte-exact" contract.
                for (b in bitsThisGroup until PHASE_BITS_PER_GROUP) {
                    val bin = PHASE_BASE_BIN + b
                    val mirror = FRAME_SIZE - bin
                    re[s][bin] = magnitude[s][b] * cos(newPhase[s][b])
                    im[s][bin] = magnitude[s][b] * sin(newPhase[s][b])
                    re[s][mirror] = re[s][bin]
                    im[s][mirror] = -im[s][bin]
                }
                ifft(re[s], im[s])
                val start = (groupStart + s) * FRAME_SIZE
                for (i in 0 until FRAME_SIZE) out[start + i] = roundToShort(re[s][i])
            }

            groupIndex++
        }
        check(bitIndex == totalBits) {
            "cover ran out of groups while embedding ($bitIndex/$totalBits bits) -- should be " +
                "unreachable once canEmbed and the payload-size check above both hold"
        }
        return out
    }

    /**
     * Recovers a payload from a phase-coding [carrier] by reading, for each group, only its
     * *first* segment's phase at each dedicated bin -- see the class KDoc's "Phase-coding
     * technique" section for why the continuity segments carry no bits of their own and are
     * never inspected here.
     */
    private fun decodePhaseCoding(carrier: PcmAudio): DecodeResult {
        val numSegments = carrier.size / FRAME_SIZE
        val numGroups = numSegments / PHASE_SEGMENTS_PER_GROUP
        val carrierCapacityBytes = phaseCodingCapacityBytes(carrier.size)
        if (carrierCapacityBytes < HEADER_BYTES) {
            return DecodeResult.Failure(
                DecodeFailure.NO_PAYLOAD_FOUND,
                "carrier ($numGroups usable groups of $PHASE_SEGMENTS_PER_GROUP frames) is too " +
                    "short to hold a header at $PHASE_BITS_PER_GROUP bits/group",
            )
        }

        val header = extractPhaseCodingBytes(carrier, 0, HEADER_BYTES)
        if ((header[0].toInt() and 0xFF) != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.NO_PAYLOAD_FOUND, "no stego magic byte found")
        }
        if ((header[1].toInt() and 0xFF) != VERSION) {
            return DecodeResult.Failure(
                DecodeFailure.HEADER_INVALID,
                "unsupported frame version ${header[1].toInt() and 0xFF}",
            )
        }
        val declaredHeaderCrc = header[6].toInt() and 0xFF
        val computedHeaderCrc = crc8(header, 0, 6)
        if (declaredHeaderCrc != computedHeaderCrc) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "header CRC-8 mismatch")
        }

        val length = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (length < 0) {
            return DecodeResult.Failure(DecodeFailure.HEADER_INVALID, "negative declared length $length")
        }
        val maxPayloadForCarrier = (carrierCapacityBytes - FRAME_OVERHEAD_BYTES).coerceAtLeast(0)
        if (length > maxPayloadForCarrier) {
            return DecodeResult.Failure(
                DecodeFailure.PAYLOAD_TOO_LARGE,
                "declared length $length exceeds this carrier's $maxPayloadForCarrier-byte capacity",
            )
        }

        val payload = extractPhaseCodingBytes(carrier, HEADER_BYTES * 8, length)
        val trailer = extractPhaseCodingBytes(carrier, (HEADER_BYTES + length) * 8, TRAILER_BYTES)
        val declaredCrc32 = ((trailer[0].toLong() and 0xFF) shl 24) or
            ((trailer[1].toLong() and 0xFF) shl 16) or
            ((trailer[2].toLong() and 0xFF) shl 8) or
            (trailer[3].toLong() and 0xFF)
        val actualCrc32 = crc32Of(payload)
        if (declaredCrc32 != actualCrc32) {
            return DecodeResult.Failure(DecodeFailure.INTEGRITY_MISMATCH, "payload CRC-32 mismatch")
        }

        return DecodeResult.Success(payload = payload, correctedByteErrors = 0)
    }

    /**
     * Total embeddable bytes for a phase-coding carrier/cover of `sampleCount` samples:
     * `floor((numGroups * PHASE_BITS_PER_GROUP) / 8)`, where `numGroups = (sampleCount /
     * FRAME_SIZE) / PHASE_SEGMENTS_PER_GROUP` -- a whole number of groups only; a trailing
     * partial group (fewer than [PHASE_SEGMENTS_PER_GROUP] leftover segments) holds no capacity,
     * matching [decodePhaseCoding]'s own "usable groups" framing.
     */
    private fun phaseCodingCapacityBytes(sampleCount: Int): Int {
        val numSegments = sampleCount / FRAME_SIZE
        val numGroups = numSegments / PHASE_SEGMENTS_PER_GROUP
        return (numGroups * PHASE_BITS_PER_GROUP) / 8
    }

    /**
     * Recovers one payload bit from [carrier] at bit position [bitIndex]: FFTs the OWNING
     * group's *first* segment only (`groupIndex * PHASE_SEGMENTS_PER_GROUP`) and reads the sign
     * of the recomputed phase at the dedicated bin -- `> 0` is bit 0 (matching encode's
     * `PHASE_BIT_ZERO` = `+PI/2`), `<= 0` is bit 1 (matching `PHASE_BIT_ONE` = `-PI/2`). A robust
     * sign check, not a near-zero tiebreak: the two substituted values sit a full PI apart, so
     * round-trip phase noise would have to be enormous to flip which side of zero a recovered
     * phase lands on (class KDoc).
     */
    private fun readPhaseCodingBit(carrier: PcmAudio, bitIndex: Int): Int {
        val groupIndex = bitIndex / PHASE_BITS_PER_GROUP
        val binSlot = bitIndex % PHASE_BITS_PER_GROUP
        val bin = PHASE_BASE_BIN + binSlot
        val segmentStart = groupIndex * PHASE_SEGMENTS_PER_GROUP * FRAME_SIZE
        val re = DoubleArray(FRAME_SIZE) { i -> carrier[segmentStart + i].toDouble() }
        val im = DoubleArray(FRAME_SIZE)
        fft(re, im)
        val phase = atan2(im[bin], re[bin])
        return if (phase > 0.0) 0 else 1
    }

    private fun extractPhaseCodingBytes(carrier: PcmAudio, startBitIndex: Int, numBytes: Int): ByteArray {
        val out = ByteArray(numBytes)
        var bitIndex = startBitIndex
        for (i in 0 until numBytes) {
            var value = 0
            repeat(8) {
                value = (value shl 1) or readPhaseCodingBit(carrier, bitIndex)
                bitIndex++
            }
            out[i] = value.toByte()
        }
        return out
    }

    // --- Frame assembly (same shape as ImageStegoCarrier.buildFrame) ---

    /** [version] defaults to [VERSION] (0x01) -- every technique except [SPECTROGRAM_LSB] passes
     *  no argument here and keeps writing that, unchanged. [SPECTROGRAM_LSB] passes
     *  [SPECTROGRAM_LSB_VERSION_2] (near-silent-frame skip, class KDoc). */
    private fun buildFrame(payload: ByteArray, version: Int = VERSION): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        header[0] = MAGIC.toByte()
        header[1] = version.toByte()
        header[2] = (payload.size ushr 24).toByte()
        header[3] = (payload.size ushr 16).toByte()
        header[4] = (payload.size ushr 8).toByte()
        header[5] = payload.size.toByte()
        header[6] = crc8(header, 0, 6).toByte()

        val crc32 = crc32Of(payload)
        val trailer = byteArrayOf(
            (crc32 ushr 24).toByte(),
            (crc32 ushr 16).toByte(),
            (crc32 ushr 8).toByte(),
            crc32.toByte(),
        )
        return header + payload + trailer
    }

    // --- Bit-level segment I/O (shared indexing scheme between encode and decode) ---

    /**
     * Sign-flip a PCM16 sample, saturating instead of wrapping. Plain `-v` wraps for exactly
     * `Short.MIN_VALUE` (`-(-32768)` overflows back to `-32768` in two's complement), which would
     * silently fail to flip that one sample's contribution to the L+R phase-cancellation sum.
     */
    private fun negatedSample(v: Short): Short =
        (-v.toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /**
     * Recovers one payload bit from [carrier] (interleaved stereo) at segment [bitIndex]: sums
     * `L[i] + R[i]` (the phase-cancellation step) across the segment's [SEGMENT_SAMPLES] samples
     * and reads the sign of that sum -- dominated by `SEGMENT_SAMPLES * MIX_AMPLITUDE` in the
     * clean case, so this is a robust sign check, not a near-zero tiebreak. Sum >= 0 -> bit 0
     * (matching encode's `+MIX_AMPLITUDE`), sum < 0 -> bit 1 (matching `-MIX_AMPLITUDE`).
     */
    private fun readBit(carrier: ShortArray, bitIndex: Int): Int {
        val start = bitIndex * SEGMENT_SAMPLES
        val end = start + SEGMENT_SAMPLES
        var sum = 0L
        for (i in start until end) {
            sum += carrier[2 * i].toLong() + carrier[2 * i + 1].toLong()
        }
        return if (sum < 0) 1 else 0
    }

    private fun extractBytes(carrier: ShortArray, startBitIndex: Int, numBytes: Int): ByteArray {
        val out = ByteArray(numBytes)
        var bitIndex = startBitIndex
        for (i in 0 until numBytes) {
            var value = 0
            repeat(8) {
                value = (value shl 1) or readBit(carrier, bitIndex)
                bitIndex++
            }
            out[i] = value.toByte()
        }
        return out
    }

    private fun bitAt(bytes: ByteArray, bitIndex: Int): Int {
        val byteIndex = bitIndex / 8
        val bitInByte = 7 - (bitIndex % 8)
        return (bytes[byteIndex].toInt() ushr bitInByte) and 1
    }

    // --- Checksums ---

    /** CRC-8, poly 0x07, init 0x00, MSB-first — same polynomial as every other module's header_crc. */
    private fun crc8(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor HEADER_CRC8_POLY) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc
    }

    /** CRC-32 (IEEE 802.3, poly 0x04C11DB7) via the JDK's standard implementation. */
    private fun crc32Of(bytes: ByteArray): Long {
        val crc32 = CRC32()
        crc32.update(bytes)
        return crc32.value
    }

    companion object {
        /**
         * Samples per phase-inversion segment: 10 ms at [NightjarAcoustics.SAMPLE_RATE_HZ]. See
         * the class KDoc's "Phase-inversion technique" section for the capacity/robustness
         * rationale behind this specific value.
         */
        private const val SEGMENT_SAMPLES = 480

        /**
         * Amplitude of the secondary signal mixed into the right channel per segment (see the
         * class KDoc's "Phase-inversion technique" section for the full rationale): small enough
         * relative to full-scale PCM16 (32767) to be a genuinely low-volume mixed-in artifact,
         * large enough that a whole segment's summed offset dominates the sign decision robustly.
         */
        private const val MIX_AMPLITUDE = 64

        /** Header magic byte 'N' — matches every other module's framing (architecture.md §5). */
        private const val MAGIC = 0x4E

        /** Frame version. */
        private const val VERSION = 0x01

        /** Header size in bytes (magic, version, length[4], header_crc). */
        private const val HEADER_BYTES = 7

        /**
         * [SPECTROGRAM_LSB]'s v2 frame version -- near-silent-frame skip (class KDoc). Distinct
         * from [VERSION] (which [PHASE_INVERSION]/[MFSK] still write, and which older
         * spectrogram-LSB fireflies were caught under) so [decodeSpectrogramLsb] can tell which
         * payload+trailer extraction algorithm a given carrier needs.
         */
        private const val SPECTROGRAM_LSB_VERSION_2 = 0x02

        /**
         * How many of a [SPECTROGRAM_LSB] frame's [HEADER_BYTES]-holding leading frames the
         * header itself occupies, at [binsPerFrame] bits/frame -- `ceil(HEADER_BYTES*8 /
         * binsPerFrame)`. Both [encodeSpectrogramLsb] and [decodeSpectrogramLsb] treat this as the
         * fixed frame boundary where the always-dense header ends and (for v2) the silence-skip
         * payload+trailer walk begins -- any bin slots left over in the header's own last frame
         * are deliberately unused rather than reused for payload bits, so this boundary is a pure
         * function of [binsPerFrame] alone, computable identically by encode and decode without
         * needing to track exactly how many bits landed in that final header frame.
         */
        private fun spectrogramLsbHeaderFrameCount(binsPerFrame: Int): Int =
            (HEADER_BYTES * 8 + binsPerFrame - 1) / binsPerFrame

        /** Trailer size in bytes (payload_crc32). */
        private const val TRAILER_BYTES = 4

        /** Total non-payload overhead embedded alongside every payload. */
        private const val FRAME_OVERHEAD_BYTES = HEADER_BYTES + TRAILER_BYTES

        /** CRC-8 polynomial (0x07) protecting the header. */
        private const val HEADER_CRC8_POLY = 0x07

        /**
         * Total embeddable bytes for a *mono-equivalent* cover of `sampleCount` samples:
         * floor(floor(n/480)/8). Used both for [cover]'s own capacity and, at decode time, for a
         * carrier's mono-equivalent sample count (`carrier.size / 2`, since the carrier is
         * interleaved stereo) — this formula itself never changes based on channel layout.
         */
        private fun phaseInversionCapacityBytes(sampleCount: Int): Int =
            (sampleCount / SEGMENT_SAMPLES) / 8

        // --- Spectrogram-LSB constants ---

        /**
         * Samples per spectrogram-LSB frame: 1024, a power of two (required by [fft]/[ifft]'s
         * radix-2 Cooley-Tukey algorithm), giving ~21.3 ms per frame at
         * [NightjarAcoustics.SAMPLE_RATE_HZ] (48 kHz) -- long enough for a meaningful number of
         * well-separated frequency bins below the Nyquist bin, short enough to keep per-frame
         * embedding capacity ([binsPerFrame] bins) a small fraction of the frame's total bin count.
         */
        private const val FRAME_SIZE = 1024

        /**
         * First eligible bin for embedding: bin 32, i.e. `32 * NightjarAcoustics.SAMPLE_RATE_HZ /
         * FRAME_SIZE` = 1500 Hz exactly at 48 kHz -- comfortably past DC and the highest-energy bass
         * content, which is where `covert-data/library/03_audio_steganography.md` notes spectral
         * embedding needs to avoid perturbing audible, high-energy structure. Fixed and
         * content-independent: [encode] and [decode] always agree on it without deriving it from
         * the carrier.
         */
        private const val ELIGIBLE_BIN_START = 32

        /**
         * Bins granted per unit of [stegoStrength] (`binsPerFrame = stegoStrength *
         * BINS_PER_STRENGTH_LEVEL`, see [AudioStegoCarrier.binsPerFrame]'s KDoc). 8 is a
         * deliberately modest step size: across the valid strength range (1..4) it spans 8..32
         * bins per 1024-sample frame, leaving the large majority of each frame's spectrum untouched
         * even at maximum strength.
         */
        private const val BINS_PER_STRENGTH_LEVEL = 8

        /** Default [stegoStrength] when a caller doesn't specify one -- a middle-of-the-range value. */
        private const val DEFAULT_STEGO_STRENGTH = 2

        /** Minimum valid [stegoStrength] for [AudioStegoTechnique.SPECTROGRAM_LSB]. */
        private const val MIN_STEGO_STRENGTH = 1

        /** Maximum valid [stegoStrength] for [AudioStegoTechnique.SPECTROGRAM_LSB]. */
        private const val MAX_STEGO_STRENGTH = 4

        /**
         * Quantization step Δ (natural-log units) for the log-magnitude QIM embedding (class
         * KDoc's "Spectrogram-LSB technique" section). 0.12 sits inside the brief's suggested
         * 0.08-0.2 starting range: `exp(0.12) ≈ 1.127`, i.e. a bucket boundary is roughly a 12-13%
         * relative magnitude change away in either direction from a bucket's center -- comfortably
         * larger than the sub-percent relative magnitude perturbation that fft/ifft floating-point
         * error and int16 requantization noise introduce on a round trip (the whole point of QIM:
         * only a change that crosses a bucket boundary can flip the recovered bit), while still
         * small enough that the induced magnitude nudge stays a "low-volume" change relative to the
         * bin's own energy rather than an audible spike.
         */
        private const val QUANTIZATION_STEP = 0.12

        /**
         * Floor under a bin's magnitude before taking its natural log. This does more than avoid
         * `ln(0)` for an exactly-silent bin -- it is what keeps QIM reliable for a *near*-silent
         * bin too, and picking it too small (e.g. the naive `1.0`) is a real correctness bug, not
         * just a style choice: [encode]'s own `ifft` + round-to-`Short` step introduces genuine
         * reconstruction noise in every bin's magnitude, empirically on the order of single-digit
         * to low-double-digit magnitude units per bin (measured directly against this codec's own
         * [fft]/[ifft], consistent with the theoretical estimate `sqrt(FRAME_SIZE) * (per-sample
         * rounding std ~0.29) ~= 9.3` for i.i.d ±0.5 rounding error spread across the frame). For a
         * bin whose own natural magnitude is comparable to or smaller than that noise (which DOES
         * happen -- e.g. a near-null in the cover's spectrum at that exact bin/frame; this was
         * caught by an actual round-trip test failure during development, not a theoretical worry),
         * a floor of `1.0` leaves the QIM index computed from a magnitude the reconstruction noise
         * can trivially push across a `QUANTIZATION_STEP` bucket boundary. Flooring at 1000
         * instead -- roughly two orders of magnitude above the measured noise floor -- forces any
         * near-silent bin's *embedded* magnitude up to a level where that same noise is a small
         * fraction of a percent, restoring the safety margin [QUANTIZATION_STEP]'s own KDoc assumes.
         * The audible cost is negligible: boosting one bin from near-zero to ~1000-1100 contributes
         * a time-domain sinusoid of peak amplitude `2 * newMagnitude / FRAME_SIZE` ~= 2, dwarfed by
         * the cover's own full-scale range (Short.MAX_VALUE = 32767). Bins already above 1000
         * (the overwhelming majority in any cover with real signal content) are completely
         * unaffected by this floor, since `max(M, LOG_MAGNITUDE_FLOOR)` only ever changes anything
         * when `M` is already small.
         */
        private const val LOG_MAGNITUDE_FLOOR = 1000.0

        /**
         * Minimum [guardBandEnergy] (summed magnitude of bins `0 until ELIGIBLE_BIN_START`) for a
         * [SPECTROGRAM_LSB] frame to be eligible for v2 embedding -- near-silent-frame skip, class
         * KDoc. Measured against both bundled covers' real per-frame guard-band energy
         * (`AudioSampleCover.SPOKEN_WORD`/`SOFT_SYNTH`, 234 frames each, offline JVM measurement):
         * SPOKEN_WORD's genuine burst/gap silence gaps measured 0-7,126 (median of the whole
         * clip's frames: ~3.1M); the lowest energy measured on *any* frame containing real content
         * on either cover (SOFT_SYNTH's second frame, still inside its 0.4s attack ramp) was
         * 171,818. 100,000 sits with over an order of magnitude of margin below that real-content
         * floor and well over 10x above the measured silence ceiling -- comfortable headroom on
         * both sides of a cleanly bimodal distribution, not a threshold picked to just barely work.
         */
        private const val SILENCE_GUARD_ENERGY_THRESHOLD = 100_000.0

        /**
         * Summed magnitude of bins `0 until ELIGIBLE_BIN_START` of one frame's spectrum ([re]/
         * [im], already FFT'd) -- [embedBitInBin] never touches these bins, for any frame, so this
         * is the same value whether computed from [cover] at encode time or from the resulting
         * stego carrier at decode time (class KDoc's "why it's provably encode/decode-consistent"
         * paragraph). Used against [SILENCE_GUARD_ENERGY_THRESHOLD] to decide v2 frame eligibility.
         */
        private fun guardBandEnergy(re: DoubleArray, im: DoubleArray): Double {
            var sum = 0.0
            for (bin in 0 until ELIGIBLE_BIN_START) {
                sum += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
            return sum
        }

        // --- MFSK constants ---

        /**
         * Simultaneous, independently-on/off tone channels per symbol block; each block encodes
         * exactly one byte (bit `i`, MSB-first -- bit 0 == `0x80` -- maps to tone channel `i`).
         * 8 channels/8 bits keeps the byte-to-block mapping direct, no bit-packing across block
         * boundaries -- mirrors `bennjordan/AlphaSteg`'s own real 8-tone MFSK variant (1 byte per
         * block), confirmed via that tool's actual source rather than assumed.
         */
        private const val MFSK_TONE_COUNT = 8

        /**
         * First of [MFSK_TONE_COUNT] consecutive tone bins. Bin 420 at [FRAME_SIZE]=1024,
         * `NightjarAcoustics.SAMPLE_RATE_HZ`=48000 is `420 * 48000.0 / 1024 ~= 19687.5 Hz` --
         * comfortably clear of Module 3's own NEAR_ULTRASONIC tone grid (bins 342-405,
         * `NightjarAcoustics.kt`) so this technique's tones never collide with that module's
         * frequency plan, and well below the Nyquist bin (512) for a 1024-point real FFT.
         */
        private const val MFSK_BASE_BIN = 420

        /**
         * Amplitude of each active tone channel's sine contribution before [encodeMfsk]'s
         * whole-clip gain (summed across active channels onto the cover). Needs to clear
         * [MFSK_DETECTION_MARGIN_DB] reliably against typical cover energy in this high band;
         * 6000 (~18% of full-scale 32767) was sized against this class's own round-trip tests,
         * comfortably larger than [MIX_AMPLITUDE] since detection here relies on an absolute
         * presence/absence margin rather than a signed-sum trick. [MFSK_TONE_COUNT] simultaneous
         * tones at this amplitude can sum past int16 range on their own (design-v5.md §12.2,
         * measured 96-141 saturated samples per stego pre-fix; byteValue == 0xFF's actual
         * measured peak is ~47876 -- essentially the full 8 * TONE_AMPLITUDE bound, since these 8
         * adjacent-bin tones drift back into near-alignment within one 1024-sample block), which
         * is exactly why [encodeMfsk]'s whole-clip gain exists -- this constant never changes;
         * [MFSK_SAMPLE_CEILING] is what may attenuate its effective, post-gain amplitude.
         */
        private const val TONE_AMPLITUDE = 6000.0

        /**
         * Raised-cosine ramp length (samples) [mfskToneEnvelope] applies at a tone's on/off
         * transitions -- see the class KDoc's "Click fix" paragraph for the full sweep. 32
         * samples (0.67 ms, ~3% of one [FRAME_SIZE] block) was the smallest of {8, 16, 24, 32,
         * 40, 48, 56, 64, 96, 128} that pushed the boundary-locked audible-band residual below
         * -70 dBFS on *both* bundled sample covers, while keeping real headroom either side of
         * [MFSK_DETECTION_MARGIN_DB]: every still-active tone bin stayed >= 20 dB over threshold,
         * and no silent bin's ramp-induced spectral leakage got within 4 dB of crossing it (that
         * leakage climbs with ramp length and was measured to actually flip bits -- false-positive
         * "on" reads on bins meant to stay silent -- starting around a 64-96 sample ramp on
         * SOFT_SYNTH, which is why this constant is a measured value, not a round number picked
         * for looking safe).
         */
        private const val MFSK_RAMP_SAMPLES = 32

        /**
         * Ceiling the combined (cover + tones) signal's peak magnitude is kept at or under across
         * the *whole* MFSK-encoded clip, used by [encodeMfsk] to derive its single whole-clip
         * gain (`MFSK_SAMPLE_CEILING / peak`, applied uniformly, never per-block -- see that
         * function's and the class KDoc's "Clipping fix" notes for why a uniform gain, not a
         * per-block one, is required). Comfortably short of `Short.MAX_VALUE` (32767) so no
         * legitimate (non-clipped) sum can land exactly on the int16 saturation boundary either --
         * only genuine out-of-range arithmetic would, and this fix's whole point is that it never
         * happens.
         */
        private const val MFSK_SAMPLE_CEILING = 32000.0

        /**
         * Minimum dB a tone bin's magnitude must exceed the surrounding guard band's median
         * magnitude by, to be read as "active" during [demodulateMfskBlock] -- mirrors
         * `AcousticCarrier`'s own marker-detection margin (`NightjarAcoustics.
         * DETECTOR_TONE_MARGIN_DB` = 15.0 dB); the same value works here since both rely on the
         * same margin-over-median principle against the same FFT bin resolution.
         */
        private const val MFSK_DETECTION_MARGIN_DB = 15.0

        /**
         * Guard-band width (bins) on each side of the [MFSK_TONE_COUNT] tone bins used to
         * estimate [demodulateMfskBlock]'s local noise floor, excluding the tone bins themselves.
         */
        private const val MFSK_GUARD_BINS = 16

        /**
         * Fixed Reed-Solomon data-block size for MFSK -- a single block, deliberately no
         * multi-block chunking (class KDoc's "MFSK technique" section). 48 comfortably holds the
         * framing overhead ([FRAME_OVERHEAD_BYTES] = 11) plus a meaningful payload
         * (`maxPayloadBytes` = 37), and stays well under the GF(2^8) codeword-length ceiling
         * (~255 bytes) once [RS_PARITY_BYTES] is added.
         */
        private const val RS_DATA_BYTES = 48

        /**
         * Reed-Solomon parity bytes for MFSK's single block: corrects up to
         * `RS_PARITY_BYTES / 2` = 8 byte errors, the standard Reed-Solomon correction bound.
         */
        private const val RS_PARITY_BYTES = 16

        /**
         * Total MFSK codeword size in bytes -- also the number of symbol blocks a codeword needs,
         * one byte per block: [RS_DATA_BYTES] + [RS_PARITY_BYTES].
         */
        private const val MFSK_CODEWORD_BYTES = RS_DATA_BYTES + RS_PARITY_BYTES

        /**
         * Raised-cosine (Hann) amplitude multiplier for sample [n] (0-indexed within its
         * [FRAME_SIZE]-sample symbol block) of a tone that is active in this block -- see the
         * class KDoc's "Click fix" paragraph. Ramps 0 -> 1 over the first [MFSK_RAMP_SAMPLES]
         * samples when [risingEdge] (that tone channel was silent in the previous block, or this
         * is the codeword's first block), and 1 -> 0 over the last [MFSK_RAMP_SAMPLES] samples
         * when [fallingEdge] (silent in the next block, or this is the last block). A tone that
         * stays active across a boundary gets neither edge and plays through at full, constant
         * amplitude -- correct because [MFSK_BASE_BIN]-relative tone frequencies are always an
         * integer number of cycles per block, so the raw sinusoid is already phase-continuous
         * there (`sin` at sample [FRAME_SIZE] of one block equals `sin` at sample 0 of the next),
         * and no additional shaping is needed to avoid a discontinuity that was never there.
         */
        private fun mfskToneEnvelope(n: Int, risingEdge: Boolean, fallingEdge: Boolean): Double {
            var envelope = 1.0
            if (risingEdge && n < MFSK_RAMP_SAMPLES) {
                envelope = 0.5 * (1.0 - cos(PI * n / MFSK_RAMP_SAMPLES))
            }
            if (fallingEdge && n >= FRAME_SIZE - MFSK_RAMP_SAMPLES) {
                val samplesFromBlockEnd = n - (FRAME_SIZE - MFSK_RAMP_SAMPLES)
                val fallingEnvelope = 0.5 * (1.0 + cos(PI * samplesFromBlockEnd / MFSK_RAMP_SAMPLES))
                envelope = min(envelope, fallingEnvelope)
            }
            return envelope
        }

        /** Rounds to the nearest `Long` and saturate-clamps into `Short` range. */
        private fun roundToShort(value: Double): Short =
            Math.round(value).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()

        /**
         * Embeds [bit] into bin [bin] of the spectrum ([re]/[im]), quantization-index-modulating
         * its log-magnitude, and mirrors the result into bin `re.size - bin` to preserve the
         * conjugate symmetry a real-input FFT requires (class KDoc's "Conjugate symmetry"
         * paragraph). Steps exactly match the task brief's algorithm:
         *  1. `M = |X[bin]|`.
         *  2. `logM = ln(max(M, LOG_MAGNITUDE_FLOOR))`.
         *  3. `idx = round(logM / QUANTIZATION_STEP)`.
         *  4. target parity = `Math.floorMod(idx, 2)` -- NOT plain `idx % 2`, which can return a
         *     negative result for negative `idx` (log-magnitude is negative whenever `M < 1`) and
         *     would silently break the parity comparison below.
         *  5. If parity != [bit], `idx += 1`.
         *  6. `newM = exp(idx * QUANTIZATION_STEP)`.
         *  7. Preserve phase: `newX = (X / M) * newM` when `M > 0`; `newX = newM + 0i` when `M == 0`
         *     (no defined phase to preserve).
         */
        private fun embedBitInBin(re: DoubleArray, im: DoubleArray, bin: Int, bit: Int) {
            val mirror = re.size - bin
            val xRe = re[bin]
            val xIm = im[bin]
            val magnitude = sqrt(xRe * xRe + xIm * xIm)
            val logMagnitude = ln(max(magnitude, LOG_MAGNITUDE_FLOOR))
            var idx = Math.round(logMagnitude / QUANTIZATION_STEP)
            if (Math.floorMod(idx, 2L).toInt() != bit) {
                idx += 1
            }
            val newMagnitude = exp(idx * QUANTIZATION_STEP)
            val newRe: Double
            val newIm: Double
            if (magnitude > 0.0) {
                newRe = (xRe / magnitude) * newMagnitude
                newIm = (xIm / magnitude) * newMagnitude
            } else {
                newRe = newMagnitude
                newIm = 0.0
            }
            re[bin] = newRe
            im[bin] = newIm
            re[mirror] = newRe
            im[mirror] = -newIm
        }

        /**
         * Reads back the bit [embedBitInBin] wrote into bin [bin], recomputing the same
         * log-magnitude quantization index and parity from the (possibly received/round-tripped)
         * spectrum -- the mirror image of [embedBitInBin]'s steps 1-4.
         */
        private fun readBitFromBin(re: DoubleArray, im: DoubleArray, bin: Int): Int {
            val xRe = re[bin]
            val xIm = im[bin]
            val magnitude = sqrt(xRe * xRe + xIm * xIm)
            val logMagnitude = ln(max(magnitude, LOG_MAGNITUDE_FLOOR))
            val idx = Math.round(logMagnitude / QUANTIZATION_STEP)
            return Math.floorMod(idx, 2L).toInt()
        }

        // --- Phase-coding constants (deferred v6 follow-up; class KDoc's "Phase-coding
        // technique" section has the full design) ---

        /**
         * First of [PHASE_BITS_PER_GROUP] consecutive dedicated phase-coding bins (~938 Hz at
         * [NightjarAcoustics.SAMPLE_RATE_HZ]). Low-frequency (the literature's own placement for
         * phase coding), clear of both other techniques' spectral real estate on a cover this app
         * also uses for them -- but NOT the lowest available bins: bin 4 measured badly on
         * `AudioSampleCover.SOFT_SYNTH`, whose own 220 Hz fundamental collides with bins 4-7. See
         * class KDoc's full measured before/after for why 20, the same "avoid the highest-energy
         * content" reasoning [SPECTROGRAM_LSB]'s own `ELIGIBLE_BIN_START` KDoc already documents.
         */
        private const val PHASE_BASE_BIN = 20

        /** Dedicated phase-coding bins per group's first segment -- one payload bit per bin. */
        private const val PHASE_BITS_PER_GROUP = 4

        /**
         * [FRAME_SIZE]-sample segments per phase-coding group: 1 bit-carrying segment (the
         * group's first) plus 3 continuity segments that re-derive their phase from the cover's
         * own original inter-segment deltas rather than being substituted directly (class KDoc).
         * The literature's own low-data-rate trait, not a limitation of this implementation: most
         * of every group's samples carry zero bits of their own, existing purely so the phase
         * substitution fades back toward the cover's natural trajectory instead of snapping.
         */
        private const val PHASE_SEGMENTS_PER_GROUP = 4

        /** Substituted phase (radians) for bit 0 -- Bender et al. 1996's own convention. */
        private const val PHASE_BIT_ZERO = PI / 2.0

        /** Substituted phase (radians) for bit 1 -- a full PI from [PHASE_BIT_ZERO], the margin
         *  [readPhaseCodingBit]'s sign-check robustness relies on. */
        private const val PHASE_BIT_ONE = -PI / 2.0

        /**
         * Minimum magnitude a phase-coding bit-carrying bin's OUTPUT is floored to -- same role
         * as [LOG_MAGNITUDE_FLOOR] plays for [SPECTROGRAM_LSB]'s QIM, and for the identical
         * reason: [atan2] of a near-zero (real, imaginary) pair is dominated by floating-point/
         * round-trip noise, both when this class computes it here and when [readPhaseCodingBit]
         * recomputes it from the round-tripped carrier. Found empirically, not chosen
         * defensively: `AudioSampleCover.SPOKEN_WORD` -- which has genuine near-silent passages --
         * failed to decode its own header at all before this floor existed (offline JVM
         * measurement, not a hypothetical).
         *
         * 100, not [LOG_MAGNITUDE_FLOOR]'s 1000: measured to make no difference at all to the
         * boundary-jump fidelity problem [PHASE_BASE_BIN]'s KDoc describes (identical residual/
         * jump numbers at 1000 vs 100) -- confirming that artifact was never about this floor's
         * exact value (the floor was rarely even the binding constraint; real covers' natural
         * magnitude at these bins is usually well above either value), only about which bin was
         * chosen. Left at the smaller, still-safely-stable value rather than reverted, since
         * smaller is strictly less of an artificial magnitude change when the floor does bind.
         */
        private const val PHASE_MAGNITUDE_FLOOR = 100.0
    }
}

/**
 * The three audio steganography techniques Module 2 supports (spec.md v2 addition), one
 * [AudioStegoCarrier] class parameterized by this enum rather than a class per technique —
 * mirroring how [ImageStegoCarrier] takes one cover `Bitmap` rather than a class per cover image.
 */
enum class AudioStegoTechnique {
    /** Dual-mono phase-cancellation embedding — implemented, see [AudioStegoCarrier]'s class KDoc. */
    PHASE_INVERSION,

    /**
     * Spectrogram-domain quantization-index-modulation-on-log-magnitude embedding — implemented,
     * see [AudioStegoCarrier]'s class KDoc "Spectrogram-LSB technique" section.
     */
    SPECTROGRAM_LSB,

    /**
     * Multi-frequency on/off tone keying with real Reed-Solomon FEC — implemented, see
     * [AudioStegoCarrier]'s class KDoc "MFSK technique" section.
     */
    MFSK,

    /**
     * Literature phase coding (Bender, Gruhl, Morimoto & Lu 1996) — implemented, see
     * [AudioStegoCarrier]'s class KDoc "Phase-coding technique" section. Deferred v6 follow-up:
     * distinct from [PHASE_INVERSION], which is the simpler dual-mono phase-cancellation trick
     * `covert-data/library/03_audio_steganography.md` calls "the simple trick," not the DFT
     * segment-phase-substitution algorithm this technique actually implements.
     */
    PHASE_CODING,
}
