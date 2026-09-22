# nightjar — Architecture

This document is the authoritative parameter source for nightjar's signal-processing
modules. Downstream implementer tasks build directly against the concrete numbers
here — no further research or parameter selection is expected of them.

- Task #5 (acoustic encoder) implements the Tx path in [§ Acoustic Protocol](#acoustic-protocol).
- Task #6 (acoustic decoder) implements the Rx path in [§ Acoustic Protocol](#acoustic-protocol).
- Task #9 (acoustic detector) implements the passive listener in
  [§ Detector contract](#detector-contract-module-5).

Safety scope (from `/home/hercules/covert-data/CLAUDE.md` §2, and nightjar `spec.md`
INV-1/INV-2): all transmission stays inside the device's own speaker→mic path,
room-local, benign synthetic payloads only. No free-space RF path is ever invoked;
there is no radio parameter anywhere in this design.

---

## Acoustic Protocol

Module 3 is a one-way (broadcast) acoustic data-over-sound modem: one phone's
loudspeaker transmits, a second phone's microphone receives, in the same room. It
is a deliberate modernization of **[`ggerganov/ggwave`](https://github.com/ggerganov/ggwave)**
(the verified prior art; Benn Jordan's `Wavest`/`BUM16` are the acknowledged
descendants — see `covert-data/library/04_acoustic_ultrasonic_modem.md`). Where
nightjar deviates from ggwave, the deviation and its reason are called out inline.

### 0. Design anchor and scope narrowing vs. ggwave

ggwave ships ~12 protocol variants (Audible/Ultrasonic/Dual-Tone/Mono-Tone ×
Normal/Fast/Fastest). nightjar **narrows to exactly two protocols plus a symbol-rate
switch**, because the spec targets two known devices (Pixel 6a + one second phone),
not a broad compatibility matrix (spec.md Non-goals), and a smaller protocol surface
is easier to verify end-to-end and easier for the detector (#9) to model.

| Protocol | Purpose | Default? |
|----------|---------|----------|
| `AUDIBLE`         | Robust, hardware-independent default. Gate-2 phone-to-phone demo runs here. | **yes** |
| `NEAR_ULTRASONIC` | Quieter/less-audible variant. Optional; hardware-dependent per spec. | no |

Both protocols share one modulation engine (below) and differ only in their tone
band and tone count. A `symbolRate` switch (`NORMAL` default / `FAST`) trades data
rate for robustness — this is the "adjustable symbol rate" axis from the library.

### 1. Modulation scheme — multi-tone FSK

Retained from ggwave verbatim (it is the proven core): **multi-frequency
Frequency-Shift Keying**. Data is split into 4-bit nibbles; each nibble selects one
tone out of a contiguous 16-frequency group; several groups are emitted
**simultaneously**, so multiple nibbles ride each symbol.

- **PSK is defined as a documented-but-deferred option, not v1.** The library notes
  PSK suits quiet rooms, but robust carrier-phase recovery across two independent,
  unsynchronized phone clocks is materially harder than FSK's magnitude-only FFT
  demod. v1 ships FSK only; `modulation` is a named field reserved for a later PSK
  mode so the frame format does not change when it lands. (Deviation from BUM16,
  which exposes PSK; justified by v1 verification cost.)

Fixed DSP constants (identical for both protocols, chosen so each tone lands on
exactly one FFT bin — the key alignment property that makes demod a single-bin
magnitude read):

| Constant | Value | Notes |
|----------|-------|-------|
| Sample rate `SR` | **48000 Hz** | Android `AudioRecord`/`AudioTrack` universally support 48 kHz; ggwave default. Nyquist 24 kHz sits well above the top tone. |
| Samples per frame `NF` | **1024** | |
| FFT size | **1024** (real) | Bin width = `SR/NF` = **46.875 Hz** — equal to the tone spacing `dF`, so one tone = one bin. |
| Tone spacing `dF` | **46.875 Hz** | = ggwave `dF`. |
| Window | Hann, 50% overlap on Rx | overlap improves symbol-edge tolerance |

### 2. Tone plan (concrete frequencies)

Each protocol places its tone grid on an integer multiple of `dF` (i.e. on FFT bins),
so every emitted tone is bin-exact.

**`AUDIBLE` protocol** — identical band to ggwave's audible protocol (no reason to
deviate; it is squarely inside every phone speaker/mic response):

- Base frequency `F0` = **1875.000 Hz** (FFT bin 40 = 40 × 46.875).
- **6 simultaneous tones** → 6 nibbles → **3 bytes per symbol (Tx unit)**.
- Grid = 6 groups × 16 frequencies = **96 tones**, spanning `F0 … F0 + 95·dF` =
  **1875.000 – 6328.125 Hz** (bins 40–135). 4.5 kHz occupied bandwidth.
- Group _g_ (0–5), nibble value _v_ (0–15) → frequency = `F0 + (16·g + v)·dF`.

**`NEAR_ULTRASONIC` protocol** — **deviates from ggwave**, deliberately:

- ggwave ultrasonic uses `F0` = 15000 Hz with the full 6-tone/96-frequency layout,
  spanning 15000–19453 Hz. Measured reality (see § Frequency-response basis) is that
  typical Android speakers/mics roll off hard above ~19 kHz and the reliable,
  near-inaudible near-ultrasonic window is roughly **16.5–19.0 kHz**. ggwave's top
  tones (>19 kHz) land in that unreliable roll-off zone.
- nightjar therefore **drops to 4 simultaneous tones and raises `F0`** so the entire
  grid stays ≤ 19 kHz:
  - Base frequency `F0` = **16031.250 Hz** (FFT bin 342).
  - **4 simultaneous tones** → 4 nibbles → **2 bytes per symbol (Tx unit)**.
  - Grid = 4 groups × 16 frequencies = **64 tones**, spanning
    **16031.250 – 18984.375 Hz** (bins 342–405). ~2.95 kHz occupied bandwidth,
    entirely below the 19 kHz reliability ceiling.
  - Group _g_ (0–3), nibble value _v_ (0–15) → frequency = `F0 + (16·g + v)·dF`.
- **Cost of the deviation:** ⅓ lower raw throughput than a 6-tone band (2 B/sym vs 3),
  bought in exchange for keeping every tone inside the Pixel 6a's usable, quiet band.
  This is the capacity/robustness trade the library calls out; here robustness wins.

### 3. Symbol rate and throughput

Symbol duration = `framesPerTx · NF / SR`. Two settings; `NORMAL` is the default.

| `symbolRate` | `framesPerTx` | Symbol duration | AUDIBLE raw | NEAR_ULTRASONIC raw |
|--------------|---------------|-----------------|-------------|---------------------|
| `NORMAL` (default) | **9** | **192.0 ms** | **15.6 B/s** | **10.4 B/s** |
| `FAST`             | **6** | **128.0 ms** | 23.4 B/s     | 15.6 B/s            |

These are **channel (coded) byte rates**. After the 50% FEC of § 4, effective
*payload* throughput is roughly half: ≈ 7–10 payload B/s in AUDIBLE/NORMAL. That is
intentionally low — nightjar is a research/demo channel, not a file-transfer
replacement (spec.md Non-goals). The rates sit inside ggwave's stated 8–16 B/s
envelope, confirming the parameters are in a proven operating regime.

### 4. Reed-Solomon FEC

**Algorithm:** Reed-Solomon over **GF(2⁸)**, symbol = 1 byte, primitive polynomial
**0x11D** (`x⁸+x⁴+x³+x²+1`) — the standard field used by the ggwave vendored RS code
and virtually every off-the-shelf RS library, so #5/#6 can reuse existing code.

**Scheme — fixed-rate block coding (deviation from ggwave, justified):** ggwave sizes
its ECC as a single block scaled to total payload length. nightjar instead uses
**fixed-rate 50% blocking**, because deterministic, uniform blocks are simpler to
implement, stream, and unit-test for the lossless round-trip guarantee (INV-3), and
they degrade gracefully (a corrupted block fails locally rather than failing the whole
message).

- Data is split into blocks of **K = 32 data bytes**.
- Each block gets **2T = 16 parity bytes**, forming an **N = 48-byte codeword**:
  `RS(48, 32)` (a shortened RS(255,·) code).
- Corrects up to **T = 8 byte-errors per 48-byte codeword**.
- **Redundancy ratio = 16/32 = 50%; code rate = 32/48 ≈ 0.667.**
- The final block is shortened to the residual data length (parity still 16 bytes);
  decoder knows the true length from the header (§ 5), so no ambiguity.
- The 6-byte header (§ 5) is protected by its **own** shorter code, `RS(14, 6)`
  (8 parity bytes, corrects up to 4 byte-errors), because the length field must
  survive for the payload blocks to be parsed at all.

### 5. Payload framing

The logical frame below is assembled, then RS-encoded per § 4, then FSK-modulated per
§ 1–2. Multi-byte integers are **big-endian**. Acoustic markers bracket the byte stream.

```
┌───────────────────────────────────────────────────────────────────────┐
│ START MARKER   (acoustic, not bytes — see § 6)                          │
├───────────────────────────────────────────────────────────────────────┤
│ HEADER  (6 bytes, then RS(14,6))                                        │
│   magic       1 byte   0x4E            ('N')                            │
│   version     1 byte   0x01                                            │
│   mode        1 byte   0x00 AUDIBLE | 0x01 NEAR_ULTRASONIC             │
│   length      2 bytes  payload byte count, big-endian, 0..1024          │
│   header_crc  1 byte   CRC-8 (poly 0x07) over the 5 preceding bytes     │
├───────────────────────────────────────────────────────────────────────┤
│ PAYLOAD  (0..1024 bytes benign synthetic data, then RS(48,32) blocks)   │
├───────────────────────────────────────────────────────────────────────┤
│ TRAILER  (4 bytes, inside the payload FEC blocks)                       │
│   payload_crc32  4 bytes  CRC-32 (IEEE 802.3, poly 0x04C11DB7)          │
│                            over the raw (pre-FEC) payload bytes          │
├───────────────────────────────────────────────────────────────────────┤
│ END MARKER     (acoustic, not bytes — see § 6)                          │
└───────────────────────────────────────────────────────────────────────┘
```

- **`MAX_PAYLOAD` = 1024 bytes.** This is the "architect-defined max size" of INV-3.
  The 2-byte length field could express 65535, but the lossless guarantee is only
  claimed to 1024 bytes.
- **Recommended gate-2 demo payload ≤ 64 bytes** (a short text string) — well inside
  the guarantee and fast enough to demo live.
- **Integrity:** after RS decode, the receiver recomputes CRC-32 over the recovered
  payload and compares to `payload_crc32`. Match → deliver; mismatch → report decode
  failure (never deliver corrupt data). `header_crc` (CRC-8) gates header acceptance
  before any payload parsing.

### 6. Acoustic markers (start/end)

Retained from ggwave in concept (dedicated start/end sound markers so the receiver
knows the record window), specified concretely here:

- **START marker:** the band's two edge tones emitted **together** —
  AUDIBLE: 1875.000 Hz + 6328.125 Hz; NEAR_ULTRASONIC: 16031.250 Hz + 18984.375 Hz —
  held for **5 frames = 106.7 ms**.
- **END marker:** the band's two **center-adjacent** tones (bins `F0+31·dF` and
  `F0+32·dF`) emitted together for **5 frames = 106.7 ms**. Distinct from START so the
  receiver cannot confuse the two.
- Receiver arms on a START-marker match (both edge bins ≥ 15 dB over the running noise
  floor for ≥ 3 consecutive frames), records until END marker or `length`-implied
  symbol count is reached, then demodulates.

### 7. Round-trip envelope (INV-3)

The FEC-protected round-trip is claimed **lossless for payloads ≤ 1024 bytes** under:

| Parameter | AUDIBLE | NEAR_ULTRASONIC |
|-----------|---------|-----------------|
| Speaker→mic distance | ≤ **1.0 m** | ≤ **0.5 m** |
| Ambient noise | < **45 dBA** (quiet room) | < **45 dBA** |
| In-band SNR at mic | ≥ **20 dB** | ≥ **20 dB** |
| Speaker level | ≥ 70 dBA @ 0.5 m | near-max (compensates roll-off) |

Outside this envelope the channel degrades gracefully: per-block RS failures are
localized and surfaced as decode-failure reports (not silent corruption).

### 8. Frequency-response basis (why these bands)

The band choices are bounded by measured Android speaker/mic response, not assumed:

- Consumer smartphone speakers/mics have a **hard roll-off above ~19–20 kHz**; the
  reliable, near-inaudible near-ultrasonic window across common devices is
  **≈ 16.5–19.0 kHz**. Published near-ultrasonic phone links operate at
  **18.5–19.2 kHz** and succeed only up to **~19.7 kHz** on good hardware.
  nightjar's NEAR_ULTRASONIC band (16031–18984 Hz) sits entirely inside that window.
- BUM16's real near-ultrasonic choice (**16.5–19.0 kHz**) independently corroborates
  this window — nightjar's band is a bin-aligned subset of it.
- The AUDIBLE band (1875–6328 Hz) is in the flattest, most reproducible region of any
  phone transducer, which is why it is the robust default and the gate-2 channel.
- Pixel 6a specifics (Cirrus Logic CS35L41 amp) are consistent with this class;
  NEAR_ULTRASONIC remains explicitly **hardware-dependent** per spec — if the second
  device cannot cleanly reproduce/capture the 16–19 kHz band, AUDIBLE is the fallback
  and the loopback self-test (below) is how a device qualifies before a run.

### 9. Detector contract (Module 5)

Task #9 implements a **passive** listener that must self-detect nightjar's own
Module 3 transmission (INV-4) without decoding it. Concrete detection rule:

- Run the same **1024-point FFT at 48 kHz** over the mic stream.
- Maintain a per-bin running noise floor (median over a ~1 s window).
- **Flag** when **≥ 3 tones on the `dF` = 46.875 Hz grid**, within either the AUDIBLE
  band (bins 40–135) or the NEAR_ULTRASONIC band (bins 342–405), simultaneously exceed
  the noise floor by **≥ 15 dB** for **≥ 100 ms** (≈ 5 frames) — the START-marker and
  steady-symbol signature.
- Emit a **confidence score** in [0,1] = fraction of the last 20 analysis frames that
  met the flag condition; surface it via the `COVERT_DEBUG` logcat probe (spec.md
  Runtime Verification Surface). This is the defensive proof-of-concept: the app flags
  the very channel it transmits on.

### 10. Loopback self-test (device qualification)

Before relying on a device (esp. for NEAR_ULTRASONIC), run an on-device
speaker→own-mic loopback of a fixed 32-byte known payload at the selected protocol;
require a clean CRC-32 match. This is the primary validation method (from the module
README) and the go/no-go for whether a given phone supports a given band — no external
hardware, fully room-local, INV-2-safe.

---

## Module Interface

This section defines the shared Kotlin contract that all four signal-processing
implementations conform to. The contract lives in real, compilable code — not just this
prose — at:

- `app/src/main/java/dev/herakles/nightjar/CovertModule.kt` — the interface family.
- `app/src/main/java/dev/herakles/nightjar/NightjarAcoustics.kt` — the shared acoustic
  DSP constants (the single source of truth for §1–6, §9 above).

Tasks #5, #6, #9, #11, #12 build directly against these types.

### 0. Why two interfaces, not one

The four implementations split cleanly into two *shapes*, not one:

| Implementation | Task | Shape | Binds to |
|----------------|------|-------|----------|
| Acoustic FSK modem (Tx+Rx) | #5/#6 | reversible codec | `CovertCarrier<PcmAudio>` |
| Acoustic passive detector  | #9    | one-way analyzer | `CovertDetector<PcmAudio>` |
| Image LSB codec (enc+dec)  | #11   | reversible codec | `CovertCarrier<Bitmap>` |
| Image steganalysis         | #12   | one-way analyzer | `CovertDetector<Bitmap>` |

A **`CovertCarrier<C>`** is a reversible codec: `payload → carrier → payload`. It is the
natural home for the round-trip lossless guarantee (INV-3). A **`CovertDetector<S>`** is a
one-way analyzer: `sample → score`; it *never* recovers a payload — that is exactly the
passive, defensive posture of INV-4 and Module 5. Forcing both into a single interface
would hand every detector a meaningless `encode()` and every codec a meaningless
`analyze()`. So they are kept as **sibling interfaces under one marker supertype**
`CovertModule`, which carries a `ModuleDescriptor` (id / display name / domain / role) so
the module-picker home screen and the `COVERT_DEBUG` probe can enumerate and name every
module uniformly. That marker is the single "interface family."

**Trade-off documented:** the alternative — one `CovertModule { encode; decode; analyze }`
interface — was rejected because it would force half its surface to be a stub in every
implementation (LSP violation), and because a detector that *could* be handed a payload to
"encode" would blur the INV-4 line that the detector is passive-only. Two small interfaces
cost one extra type; they buy honest, non-stubbed contracts.

### 1. Carrier type parameterization

Both interfaces are generic over the carrier/sample type, so one contract spans sound and
images without a lowest-common-denominator blob type:

- `PcmAudio` is a `typealias` for `ShortArray` — 16-bit signed mono PCM at
  `NightjarAcoustics.SAMPLE_RATE_HZ` (48 kHz), matching Android `AudioRecord`/`AudioTrack`
  `ENCODING_PCM_16BIT` mono. No format conversion sits between the transport layer and the
  codec/detector.
- The image side binds `C`/`S` = `android.graphics.Bitmap` directly in the Module 1
  implementations.

Deliberately, **`CovertModule.kt` has no Android imports**: the contract is
platform-neutral, and the `Bitmap` binding happens only in the (later) Module 1 impls. This
is what lets the acoustic INV-3 loopback self-test (§10) run as a pure JVM unit test —
`decode(encode(payload))` with no device, speaker, or mic in the loop.

### 2. Codec contract (`CovertCarrier<C>`)

```
val maxPayloadBytes: Int
fun encode(payload: ByteArray): C            // throws IllegalArgumentException if too large
fun decode(carrier: C): DecodeResult
```

- `maxPayloadBytes` is the architect-defined INV-3 ceiling — `1024` for the acoustic modem
  (`NightjarAcoustics.MAX_PAYLOAD_BYTES`), capacity-derived from the bitmap for the image
  codec. `encode` must throw `IllegalArgumentException` when `payload.size` exceeds it.
- `encode` accepts only operator-supplied benign payloads (INV-1); the type system cannot
  enforce benignness, so the contract is stated in KDoc and no bundled-payload constant may
  exist in any implementation.
- **`decode` returns a structured `DecodeResult`, not a nullable `ByteArray?`.** This is a
  deliberate deviation from the simplest possible signature, justified by two spec forces:
  (a) §5 requires the decoder to *distinguish* delivery from failure and to *never* deliver
  corrupt data, and (b) the Runtime Verification Surface records the "last decode result" in
  the `COVERT_DEBUG` JSON — a bare `null` throws away the reason. `DecodeResult` is a sealed
  interface of `Success(payload, correctedByteErrors)` and `Failure(reason, detail?)`, where
  `DecodeFailure ∈ { NO_PAYLOAD_FOUND, HEADER_INVALID, PAYLOAD_TOO_LARGE, UNRECOVERABLE_FEC,
  INTEGRITY_MISMATCH }` maps 1:1 onto the acoustic failure points in §4–5 and applies equally
  to the image codec's header/length/checksum path.

### 3. Detector contract (`CovertDetector<S>`)

```
val flagThreshold: Float
fun analyze(sample: S): DetectionResult       // DetectionResult(confidence, flagged, estimatedPayloadBytes?, detail?)
```

- `confidence` is normalized to `[0,1]`; for the acoustic detector it is the fraction of the
  last `DETECTOR_CONFIDENCE_WINDOW_FRAMES` frames meeting the §9 tone-grid flag condition.
- `flagThreshold` is an explicit, tunable false-positive/false-negative knob: it unifies the
  §9 acoustic threshold and StegExpose's tunable detection threshold (library §06) into one
  named field. Implementers set `flagged = confidence >= flagThreshold`.
- `estimatedPayloadBytes` is an optional quantitative estimate (image steganalysis can
  estimate hidden length per StegExpose; the acoustic detector leaves it `null`).
- The acoustic detector is **stateful across successive windows** (running noise floor, §9);
  that state is held inside the instance. Each `analyze` call returns the confidence as of
  the sample it was handed. The streaming capture loop that feeds successive windows is the
  transport layer's job, not the interface's — keeping the two detectors symmetric.

### 4. Scope boundary — codec vs. transport

These interfaces are **pure codecs/analyzers over in-memory buffers**, not the audio I/O.
`AudioTrack`/`AudioRecord` playback and capture (the spec's "streaming audio-capture/decode
architecture") live in the UI/transport layer that *drives* these interfaces. Drawing the
line at `ByteArray ↔ PcmAudio` / `Bitmap` is what makes the modem unit-testable and keeps
the detector's statefulness an implementation detail rather than a contract concern.

### 5. Shared acoustic constants

`NightjarAcoustics` is a config-template object transcribing every fixed number from §1–6
and §9 (sample rate, frame/FFT size, bin width, the two `Protocol`s with their base bins and
tone counts, `SymbolRate`, RS block sizes, header/CRC constants, marker frames, detector
thresholds), plus a `binFrequencyHz(bin)` helper and per-protocol `toneFrequencyHz(group,
nibble)`. **The #1 integration hazard for this system is #5 (encoder), #6 (decoder), and #9
(detector) drifting apart on FFT size or band layout** — if they disagree by even one bin the
channel silently breaks. Giving all three one shared, compile-checked source of truth for
these numbers closes that hazard at the type level.

### 6. Verification

Both contract files compile cleanly against the project's Kotlin 2.0.21 toolchain (verified
by an isolated `K2JVMCompiler` pass emitting all 16 class files with exit 0). They carry no
implementations, as intended — tasks #5/#6/#9/#11/#12 provide those. A full
`./gradlew compileDebugKotlin` over the whole module additionally depends on every other
in-progress source file in the app compiling; the interface family itself is self-contained
and Android-import-free.

### 7. Module 2 addition — `AudioStegoCarrier<PcmAudio>`

Module 2 (audio steganography — covert-data's phase-inversion / spectrogram-LSB / MFSK
techniques, deferred to v2 in `HANDOFF.md`) needs **zero changes to the interface family
above.** It is architecturally closer to the image codec than to the acoustic modem: a
payload is hidden inside a *pre-existing* cover clip and recovered from that same clip
later, rather than synthesized fresh for live transmission. Binds as:

```
class AudioStegoCarrier(
    private val cover: PcmAudio,
    private val technique: AudioStegoTechnique,
) : CovertCarrier<PcmAudio>
```

- `AudioStegoTechnique` (new enum, `PHASE_INVERSION | SPECTROGRAM_LSB | MFSK`) parameterizes
  one class rather than three, mirroring how `ImageStegoCarrier` takes one cover `Bitmap`
  rather than the app defining a class per cover image.
- `maxPayloadBytes` is technique- **and** cover-dependent, same shape as the image codec's
  capacity-derived-from-bitmap rule (§2 above) — phase-inversion and MFSK are far lower
  capacity than spectrogram-LSB for the same clip length, matching the fidelity/capacity/
  survivability triangle documented in `covert-data/library/03_audio_steganography.md`.
  This spec deliberately does not pin exact bytes-per-second figures per technique; that's
  an implementation-time measurement against the actual bundled cover clips, not a number to
  invent here.
- `DecodeResult`/`DecodeFailure` are reused as-is, same as the image codec. `UNRECOVERABLE_FEC`
  is only reachable if the MFSK implementation ships real forward error correction — left as
  an open implementation call; if it doesn't, the case stays unreachable-but-present in the
  `when`, same as `ImageStegoCarrier`'s own documented case (§2 above, image codec applies no
  FEC either).
- New `ModuleId.AUDIO_STEGO_CODEC` entry in `CovertModule.kt` — one ID for the whole module
  (technique + cover selection are in-screen state, not separate module identities), same
  pattern `IMAGE_LSB_CODEC` already uses for its two bundled covers.
- **No audio-steganalysis detector ships with this addition.** *(Superseded by v5: the
  detector binds as `CovertDetector<WavFile.ParsedWav>`, not `CovertDetector<PcmAudio>` —
  see § 7.1 below.)* A real audio-steganalysis
  detector (phase-correlation for the Gen 1 technique, cepstral/spectral-anomaly for Gen 2 —
  see `covert-data/library/06_detection_and_countermeasures.md`) is deferred until this
  addition's codecs exist to validate against, per covert-data's own Module 5 sequencing note.
  The acoustic detector already in this app (`AcousticDetector`) is scoped to Module 3's live
  tone-grid signature specifically and does not generalize to file-embedded audio steganography
  — it is not reused here.
- **Real risk flagged before implementation, not discovered by field-testing later:** `PcmAudio`
  is a bare `ShortArray` with `NightjarAcoustics.SAMPLE_RATE_HZ` (48kHz mono PCM16) as an
  *implicit* contract, not enforced by the type. Bundled cover clips must be pre-converted to
  that exact format (`WavFile.kt` already in the tree should handle this) — a mismatched
  sample rate would silently corrupt every technique's math rather than throwing.

**Post-implementation update (gates 6–7 closed):** all three techniques are built and unit-tested
(110/110 tests passing) and the screen is wired and verified functional on-device. Two things this
section left open above were resolved during implementation — recorded here since gate-8's spec.md
language ("at default strength") otherwise has no defined referent, caught by adversarial-lite
review of this task:
- **Constructor gained a third parameter**: `AudioStegoCarrier(cover: PcmAudio, technique:
  AudioStegoTechnique, stegoStrength: Int = 2)`. Only `SPECTROGRAM_LSB` uses it — it scales how
  many spectrogram bins get nudged per frame (1–4, default 2), which is what gate-8's "default
  strength" refers to. `PHASE_INVERSION`/`MFSK` ignore it entirely. Full rationale in
  `AudioStegoCarrier.kt`'s own class KDoc.
- **The FEC open call is resolved in favor of real FEC**: `MFSK` reuses `AcousticCarrier`'s
  existing, already-tested `ReedSolomon`/`GF256` implementation (a single fixed RS(64,48) block —
  see the class KDoc's "MFSK technique" section) rather than leaving `UNRECOVERABLE_FEC`
  unreachable-but-present. Verified via a dedicated test that deliberately corrupts symbol blocks
  and confirms both the correction path and the give-up-cleanly path.

**Gate-35 correction (task W0-C, measured):** an earlier draft of this section's own reasoning —
and `AudioStegoCarrier.kt`'s class KDoc, and the technical screen's technique label — described
MFSK as "genuinely lossy-channel-robust." Measured against real codecs, that overclaims: MFSK
round-trips through AAC-LC ≥ 128 kbps and MP3 128 kbps, but fails after Opus 16–64 kbps (voip and
audio modes) and AAC 64–96 kbps. AAC at 64 kbps removes the near-ultrasonic tone band entirely;
the other failures are quantization noise past Reed-Solomon's correction capacity, not a clean
"recompressed = fails" rule. Real-world messaging voice notes are compressed audio in exactly the
range this doesn't survive, which is why `AudioStegoScreen.kt`'s v6 "open a recording" action
(gate-34) never attempts a decode on a non-WAV file at all rather than silently failing against a
doomed codec.

Gate-8 (fidelity — cover vs. stego indistinguishable by ear) and gate-9 (safety-scope +
anti-AI-tell close-out) remain open; both need a human listening pass, not something an agent can
self-certify.

### 7.1 v5 addition — `AudioStegDetector : CovertDetector<WavFile.ParsedWav>`

**Status: specified, not built** (spec.md gates 22–23). The binding deviates from the
`CovertDetector<PcmAudio>` shape the other audio detector uses, for one reason: `PcmAudio` is
mono by contract (§1), but phase-inversion output is interleaved stereo, and for that technique
the channel layout *is* the signal. A detector over a bare `ShortArray` would have to guess it.
`WavFile.ParsedWav(sampleRateHz, numChannels, samples)` already carries exactly the needed
triple, so no new type is introduced:

```kotlin
// app/src/main/java/dev/herakles/nightjar/AudioStegDetector.kt — pure JVM, no Android imports
class AudioStegDetector : CovertDetector<WavFile.ParsedWav> {
    override val descriptor = ModuleDescriptor(
        id = ModuleId.AUDIO_STEGANALYSIS,          // new enum entry, the only CovertModule.kt change
        displayName = "Audio Steganalysis (polarity / lattice / tone-band)",
        domain = CarrierDomain.AUDIO,
        role = ModuleRole.DETECTOR,
    )
    override val flagThreshold: Float = 0.85f
    override fun analyze(sample: WavFile.ParsedWav): DetectionResult
}
```

- **Posture: blind (stego-only) and targeted.** `analyze` sees only the clip under test. It knows
  the three codecs' public parameters (Kerckhoffs) but never the cover, a key, or the payload,
  and it never constructs a `CovertCarrier`, calls `decode`, or reads the frame header (INV-7).
  A cover-referenced detector was rejected: the app synthesizes both covers, so it would just
  re-synthesize and subtract — a known-cover attack only the embedder can mount.
- **Three statistics on the codec's own grid** (1024-sample frames from sample 0, rectangular
  window, the shared `fft()`): phase-inversion = inter-channel anti-correlation × a surviving
  L+R residual (runs only when `numChannels == 2`); spectrogram-LSB = QIM lattice snapping of
  `ln|X|` in bins 32–39; MFSK = keyed tones in bins 420–427 over the guard-band median.
  `confidence = max(applicable scores)`; `flagged = confidence >= flagThreshold`.
- `estimatedPayloadBytes` comes from the winning technique when flagged; it is always `null`
  for MFSK (inferring length there is one step from demodulating).
- Anti-phase stereo with anything added to one side is structurally the phase-inversion
  technique and scores as such. That is a documented false-positive class, and the UI copy says
  a polarity-flipped recording looks the same.
- The detector keeps private copies of the codec constants (Δ, magnitude floor, bin ranges)
  while `AudioStegoCarrier.kt`'s own are `private`. The § 5 drift hazard is closed by a
  calibration test that encodes through the real codec and fails with a named drift assertion.
- It reads ρ and the residual windows from `StereoPolarity` (§ 7.3), so the number the detector
  scores and the number the polarity view shows cannot disagree.
- Wiring: `AudioStegoScreen(carrierFactory, detector: CovertDetector<WavFile.ParsedWav>, onBack)`
  with the detector injected from `MainActivity`, mirroring `ImageStegoScreen`. The technical
  screen gains only a "check for hidden data" verb; the humming jar gains "peek inside". Both
  report through `DebugProbe.reportDetectorConfidence(ModuleId.AUDIO_STEGANALYSIS, …)` and
  write no `FireflyRecord` (same contract as the framed jar's peek, gate-13).

### 7.2 v5 addition — cover-vs-stego difference view (spectrogram-LSB)

**Status: specified, not built** (spec.md gate 24). Data flow, with no schema change (INV-8):

1. `JarDetailScreen` already reads the firefly's WAV (`repository.readMedia` →
   `WavFile.decodePcm16`). When `technique == "SPECTROGRAM_LSB"` and `numChannels == 1`, it
   continues off-main (`Dispatchers.Default`).
2. `matchCover(stegoMono, candidates)` re-synthesizes each bundled cover
   (`synthesizeSampleCover`, lazily) and accepts the unique candidate whose residual energy
   `E[(stego − c)²] / E[c²]` is below 1e-2 (−20 dB). A size mismatch is a non-match, never a
   throw. The threshold is deliberately not "exact": seeded `kotlin.random.Random` sequences
   and `Math.sin` ulps are not guaranteed stable across runtimes, so ±1-LSB jitter must still
   match while a changed algorithm must not.
3. On a match, `stegoDifference(cover, stego)` compares on the codec's own grid: a frame is
   changed iff `max|stego − cover| > 1 LSB`; in changed frames each bin 0–127 is NUDGED (cover
   had energy, `|Δ ln|X||` ≤ 1.5Δ), CREATED (cover was below the codec's magnitude floor and the
   codec had to add sound), or UNCHANGED.
4. Only the reduced `StegoDifferenceMap` is kept in state; the synthesized cover is dropped when
   the coroutine returns.
5. No match → the view is withheld with a stated reason, never approximated. Energy-ratio
   matching is not valid for MFSK (its tones outweigh the cover), so the view is offered for
   spectrogram-LSB only.

Rejected: a `Migration(2,3)` storing a cover id (it still needs the same re-derive-and-verify
step, so it adds brick risk for nothing), and storing cover WAVs (needs the migration plus
`allMediaPaths()` / reference-count changes, or the orphan sweep deletes live covers — INV-6).

### 7.3 v5 addition — L/R polarity view (phase-inversion)

**Status: specified, not built** (spec.md gate 25). No data-path change is needed: the
persisted phase-inversion WAV is already stereo end to end.
`AudioStegoCarrier.encodePhaseInversion` returns interleaved stereo →
`AudioStegoController.embed` sets `workingChannelCount = 2` → `jarCatchFlow`'s
`insertFireflyWithCarrier` writes `WavFile.encodePcm16Stereo` → `FireflyMediaStore` stores bytes
as-is → `WavFile.decodePcm16` returns `numChannels = 2` → `FireflyPlayer` plays
`CHANNEL_OUT_STEREO`. The gap is only visual: `spectrogram()` mono-mixes and `waveformPeaks()`
takes max-abs across channels, so no current view shows L and R apart.

`stereoPolarity(interleaved)` (pure JVM, `StereoPolarity.kt`) computes Pearson r(L, R), the
sum-to-difference energy ratio, per-10 ms-window rms and signed mean of L+R, and a normalized
20 ms zoom window of the loudest region. The view draws the L/R overlay (mirror-image cycles),
the L+R residual strip (the payload's ±steps, flat zero after it), and the correlation to 4
decimals. It is offered only when `technique == "PHASE_INVERSION"` and `numChannels == 2`.

**Placement (both views):** pure math in the root package beside `Spectrogram`/`LsbBitPlane`;
composables and caption builders in `modules/fireflyjar/CarrierInsightViews.kt`.
`JarDetailScreen`'s audio view toggle is keyed on `FireflyRecord.technique` and the WAV's
channel count — data fields, not `Module` — so the views add no per-`Module` branch
(Firefly Jar § 6). They live in the firefly detail only; technical Screen 5 keeps its
no-waveform restraint.

---

## Firefly Jar — Alternate Front-End (v3 addition)

A second, disguise-themed presentation layer over the same three `CovertCarrier`
implementations already shipped (acoustic modem, image steganography, audio
steganography) plus the detector's existing `CovertDetector`. **Needs zero changes to
the interface family** — same reasoning §7 already established for Module 2: this is a
new consumer of `CovertCarrier`/`CovertDetector`, not a new implementation shape. The
visual/UX design (wireframes, copy, the long-press mechanism's exact placement) lives in
`design/screen-flow.md` §§ Screen 6–7 and `design/firefly-jar-identity.md`; this section
is the data model and package/dependency plan those screens build against.

### 0. Package layout

New package: `app/src/main/java/dev/herakles/nightjar/modules/fireflyjar/`

- `FireflyLog.kt` — the Room entity, DAO, and database (§1 below).
- `JarShelfScreen.kt` / `JarShelfContent` — the home/shelf screen (4 jar tiles),
  following the existing stateful-root/pure-content split every other screen uses.
- `JarDetailScreen.kt` / `JarDetailContent` — per-module jar detail (firefly swarm,
  inline catch/look sections, firefly-detail popup).
- `FireflyGlyphs.kt` — Canvas-drawn jar silhouette + firefly glow-dot rendering,
  parallel to `ModulePicker.kt`'s existing `ModuleGlyph` Canvas-drawn shapes.

### 1. Data model — `FireflyRecord`

```kotlin
@Entity(tableName = "firefly_records")
data class FireflyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: String,        // Module.name (ModulePicker.kt's enum — see § 6, the
                                  // same enum the jar registry itself is keyed on)
    val direction: String,       // "CREATED" | "RECEIVED"
    val timestampMillis: Long,
    val payloadSizeBytes: Int,
    val technique: String?,      // e.g. AudioStegoTechnique.name; null where a module has
                                  // no sub-technique (acoustic modem, image steganography)
    val payloadPreview: String?, // first 40 chars of the embedded/recovered text — see § 4
)

@Dao
interface FireflyDao {
    @Insert suspend fun insert(record: FireflyRecord)
    @Query("SELECT * FROM firefly_records WHERE moduleId = :moduleId ORDER BY timestampMillis DESC")
    fun observeByModule(moduleId: String): Flow<List<FireflyRecord>>
    @Query("SELECT * FROM firefly_records ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<FireflyRecord>>          // shelf's per-jar aggregate counts
    @Query("DELETE FROM firefly_records") suspend fun clearAll()
}
```

`moduleId`/`direction`/`technique` are stored as plain strings (enum `.name`) rather than
Room type converters for enums — one fewer moving part for a 4-column table this small.
**Keying `moduleId` off `Module.name` (not a hardcoded string, not a new parallel ID) is
itself an extensibility decision** — see § 6: a new module's `Module` entry is the *only*
place its identity is declared, and this table inherits it for free.

**v4 (schema version 2):** the entity above is the v1 shape. v4 added three carrier columns
(`carrierKind`, `mediaPath`, `mediaBytes`) and five DAO queries through `MIGRATION_1_2`; see
§ 3.1 for the persistence design.

### 2. Dependency choices — favoring managed libraries over hand-rolling

Every prior module in this app deliberately hand-rolled its own infrastructure (own FFT
in `AudioStegoCarrier.kt`, own WAV assembly in `WavFile.kt`) because no well-fitting
library existed for those exact DSP needs. That reasoning does **not** carry over here —
Room and Compose's own animation library are the standard, well-supported, low-
maintenance-burden solutions for exactly what this addition needs, so this addition adds
them rather than reinventing either:

- **`androidx.room:room-runtime` + `room-ktx` + `room-compiler` (KSP)** — for `FireflyLog`
  (§1). Chosen over a hand-rolled flat-file/JSON store: a growing list of structured,
  queryable records is exactly Room's designed use case, and it's the first-party,
  generated-boilerplate, widely-documented Android answer — the "easily managed" choice,
  not the minimal-dependency-purist one this app has favored everywhere else.
- **`androidx.compose.animation:animation`** — for the firefly glow/blink motion (see
  `firefly-jar-identity.md` § Motion). This is the first `androidx.compose.animation`
  dependency anywhere in the app; every prior task that considered it (identity.md's
  Motion changelog, tasks #7/#10) explicitly declined to add it for the *technical*
  screens, reasoning that no state change there earned the risk. That reasoning is
  scoped to those screens specifically, not a project-wide ban — this addition's whole
  visual premise needs it, and `rememberInfiniteTransition` + `animateFloat` is
  first-party, well-documented code, not a new maintenance surface to own.

No other new dependencies. Canvas-drawn custom glyphs (jar silhouettes, firefly dots)
stay hand-rolled, same as `ModuleGlyph` — a generic icon-pack pull would read as more
generic, not less, even with the anti-AI-tell doctrine relaxed for this surface.

### 3. Where records get written — one shared log, two skins

Logging does **not** live inside any `CovertCarrier` implementation (keeps the interface
family untouched, same restraint §7 exercises).

*Reconciled with the code (gate-21):* the original v3 plan put one insert in every screen's
controller, technical screens included. That is not what shipped. Records are written only
by each creating module's jar flow — `jarCatchFlow` in `AcousticModemScreen.kt`,
`ImageStegoScreen.kt` and `AudioStegoScreen.kt` — through `FireflyRepository`, from a
status-keyed `LaunchedEffect` on the controller's terminal success state (embed/transmit →
`CREATED`, extract/decode → `RECEIVED`). The technical screens take no repository and write
no `FireflyRecord`; a message embedded from the technical picker does not appear as a
firefly. There is still one `FireflyDatabase`, so jar mode has a single history, but only
jar mode adds to it.

### 3.1 Carrier media persistence (v4)

- **Schema v2.** `FireflyRecord` gained `carrierKind: String?` (`"IMAGE"` | `"AUDIO"`, set by
  the catch site, never inferred), `mediaPath: String?` and `mediaBytes: Long` (default 0).
  `MIGRATION_1_2` adds the three columns with `ALTER TABLE`; no destructive fallback is
  registered. Schema export is on (`app/schemas/.../FireflyDatabase/1.json`, `2.json`).
  Pre-migration rows keep null carrier fields and render text-only.
- **What is stored.** Image jar: the stego PNG. Audio-stego jar: the working clip as a WAV
  (mono, or stereo for phase-inversion via `encodePcm16Stereo`). Acoustic modem jar: the
  transmitted PCM (`CREATED`) or the decoded capture (`RECEIVED`) as a mono WAV. Encoding runs on `Dispatchers.Default`. When there is
  no artifact to attach, the catch site falls back to a media-less `repository.insert`.
  The detector writes nothing (INV-4).
- **Where.** `FireflyMediaStore` writes to `filesDir/fireflies/`, never `MediaStore` or shared
  storage (INV-5). Files are **content-addressed** (SHA-256 of the bytes + extension), so an
  embed and its extract of the same clip share one file. The row stores the bare filename,
  re-resolved against `filesDir` on every access, because `filesDir` can move between installs.
- **Row/file coupling (INV-6)** lives in `FireflyRepository`, the only path that touches both
  stores:
  - `insertWithMedia` writes the file, then inserts the row, so a row can only name a file
    that finished writing. If the insert throws, the file is deleted unless another row
    references it.
  - `clearAll` and `deleteFirefly` delete files before rows. A crash then leaves rows
    pointing at missing files, which the UI tolerates, rather than files no row can name.
  - Because files are shared, every single-file delete is reference-counted
    (`countReferencesTo`). An unconditional delete would destroy another firefly's carrier.
  - `sweepOrphans` runs once per process at start-up (`MainActivity`, guarded against
    activity recreation). It deletes unreferenced files older than 5 minutes; the age floor
    keeps it from deleting an in-flight catch's file.
- **Read path.** `JarDetailScreen` receives `repository.readMedia` as a loader and decodes off
  the main thread (`BitmapFactory` / `WavFile.decodePcm16`). A missing file degrades to the
  text-only layout.
- **Retention surface — open.** The repository exposes `observeTotalMediaBytes()` and
  `deleteFirefly(id)`, but as of this revision no UI calls either. The shelf wires only
  clear-all. The usage readout, advisory warning and per-firefly delete remain gate-20 work.

**The detector is the deliberate exception.** Module 5 stays a `CovertDetector` — it
never creates or receives a payload (INV-4) — so it never writes a `FireflyRecord`. The
jar shelf's "watching jar" re-skins the detector's *existing* flagged-history mechanism
(`DetectorContent`'s recent-first, capped-at-50 list, already shipped) instead of forcing
detection events into the message-shaped `FireflyRecord` model. Same discipline § 0 above
already applies to the interface family itself (never hand a detector a meaningless
`encode()`), extended to this new data model too.

### 4. Safety-scope note (covert-data CLAUDE.md §1/§2)

This addition is a demonstration of UI-level covert presentation / app disguise as a
research subject — the same defensive posture as every other module here, not a hardened
operational disguise tool. Explicitly out of scope (see spec.md Boundaries): launcher-
icon swapping, app-name spoofing in the OS app list, PIN/decoy-content vault behavior.
The long-press reveal is a UX device for exploring the concept, not a security boundary —
anyone holding the unlocked phone already has full access to both surfaces either way.

`payloadPreview` (§1) is the first time this app persists user content across process
death — every prior module's state was in-memory only. Payloads are still constrained to
synthetic/benign text (INV-1, unchanged), so the risk profile doesn't materially change,
but gate-14 requires a visible clear-history action for basic data hygiene regardless.

### 5. Mode-switch mechanism

- `MainActivity.kt`'s `Screen` sealed interface gains `JarShelf` (new default initial
  value, replacing `Picker`) and `JarDetail(module: Module)`.
- `JarShelfScreen`'s wordmark uses `Modifier.combinedClickable(onClick = {}, onLongClick
  = onRevealTechnicalMode)` — Foundation's built-in long-press detection (platform-
  default ~500ms threshold), not a custom gesture timer. `onRevealTechnicalMode` sets
  `screen = Screen.Picker`.
- Reciprocal, and deliberately *not* hidden: `ModulePicker`'s wordmark gains a plain
  `labelSmall`/`TextSecondary` "jar view" text affordance that sets `screen =
  Screen.JarShelf`. Once inside technical mode you already know jar mode exists, so
  there's no disguise reason to hide the way back.
- `BackHandler`: `JarShelf` becomes the new back-disabled root (`Picker`'s old role);
  `Picker` now returns to `JarShelf` on back instead of exiting the app; `JarDetail` and
  the four technical screens return to their respective parent unchanged.

### 6. Designed for extension — adding a future module's jar

A real near-term case: Module 4 (video steganography) is currently out of scope (spec.md
Boundaries, "needs a non-mobile ML watermarking component") but not abandoned. This
addition is built so that when it — or any future `CovertCarrier`/`CovertDetector` — is
ready, giving it a jar does not mean touching `JarShelfScreen` or `JarDetailScreen`.

**One enum carries both identities.** `ModulePicker.kt`'s existing `Module` enum (label +
description, already the technical picker's own registry) gains two more constructor
fields (`JarRole` is a new 2-case enum, `{ CREATION, WATCHING }`, declared in
`FireflyLog.kt` alongside `FireflyRecord` — it's jar-mode's own concept, not something
`CovertModule.kt`'s existing interface family needs to know about):

```kotlin
enum class Module(
    val label: String,
    val description: String,
    val jarName: String,   // NEW — e.g. "the singing jar"
    val jarRole: JarRole,  // NEW — CREATION (has a "catch a firefly" flow) | WATCHING
                            //       (detector-shaped, re-skins its own history instead)
) {
    ACOUSTIC_MODEM("acoustic modem", "send text as sound, phone to phone",
        "the singing jar", JarRole.CREATION),
    IMAGE_STEGANOGRAPHY("image steganography", "hide or extract text inside an image",
        "the framed jar", JarRole.CREATION),
    DETECTOR("detector", "continuously listens for the modem's signal",
        "the watching jar", JarRole.WATCHING),
    AUDIO_STEGANOGRAPHY("audio steganography", "hide or extract text inside audio",
        "the humming jar", JarRole.CREATION),
}
```

This is deliberate over a second, parallel `JarModuleDescriptor` list: a required
constructor parameter means the compiler refuses to let a new `Module` entry compile
without also declaring its jar identity — the two can't drift apart the way two
independently-maintained lists eventually would. (This mirrors the shape
`CovertModule.kt`'s own `ModuleDescriptor` already uses for the interface-family probe —
same pattern, applied one layer up.)

**The shelf and detail chrome are generic, not per-module.** `JarShelfScreen` renders
`Module.entries.map { JarTile(it, fireflyDao.observeByModule(it.name)) }` — no `when` on
which module it is anywhere in that file. `JarDetailScreen(module: Module, ...)` renders
one shared shell (wordmark = `module.jarName`, firefly swarm or watching-jar readout
depending on `module.jarRole`) and hosts the *content* of the catch/look sections as
slots rather than branching internally.

**Exactly two places still branch per module — both compiler-enforced-exhaustive, both
one function in one file, so a missed case is a build error, not a silent gap:**

- `FireflyGlyphs.kt`, `fun DrawScope.drawJarGlyph(module: Module, stroke: Color)` — a
  `when(module)` returning that module's Canvas-drawn jar silhouette. Parallels
  `ModulePicker.kt`'s existing `drawAudioStegoGlyph`-style per-module glyph functions —
  same pattern, not a new one.
- `JarCatchFlows.kt`, `fun catchFlowFor(module: Module): (@Composable (onCaught: () ->
  Unit) -> Unit)?` — a `when(module)` returning that module's small catch-flow composable
  (technique/cover selectors + payload field, reusing exactly the selector-row
  composables Screens 4/5 already built, just re-skinned) wired to that module's real
  `CovertCarrier` construction, or `null` for `JarRole.WATCHING`. `JarDetailScreen` calls
  this once and hosts whatever it returns — it never itself knows a module's selector
  shape (zero selectors for the modem, one for image, two for audio today; a future
  module can have any shape without `JarDetailScreen` changing).

**Reconciled with the code (gate-21, re-checked for v5): the "exactly two" rule above does
not hold as written.** `grep -rn "when (module)" app/src/main/java` finds **five** exhaustive
per-`Module` `when`s. All five are compiler-enforced, so a new `Module` entry is still a build
error at every one of them, never a silent gap. But there are five sites to touch, not two,
and one of them sits in `JarShelfScreen`, which the design above said would never need touching.

| Site | Layer | Status |
|------|-------|--------|
| `JarCatchFlows.kt` `catchFlowFor` | jar | as designed |
| `FireflyGlyphs.kt` `defaultFireflies` | jar | as designed, relocated. `drawJarGlyph` itself is not a `when`; it special-cases `DETECTOR` with an `if` and delegates the per-module part to `defaultFireflies` |
| `JarShelfScreen.kt` `tileTint` | jar | **added past the budget** (per-jar tile colors) |
| `MainActivity.kt` picker routing (`Module` → `Screen`) | technical | predates jar mode; outside the rule's original scope |
| `picker/ModulePicker.kt` `ModuleGlyph` | technical | predates jar mode; outside the rule's original scope |

There are also **non-exhaustive** `Module` checks that the compiler will not flag when a module
is added:
- `module == Module.DETECTOR` in `FireflyGlyphs.kt` (both `drawJarGlyph` overloads) and
  `JarShelfScreen.kt` (`tileFireflies`, the count caption).
- `module == Module.ACOUSTIC_MODEM` in `JarDetailScreen.kt` (hero size, one layout branch).

The `DETECTOR` checks would be safer keyed on `jarRole == JarRole.WATCHING`, which
`JarDetailScreen` already does elsewhere.

What *does* hold: v4 added no per-`Module` branch (carrier kind is a record field), and the v5
views and detector add none either (they key on `FireflyRecord.technique` and channel count).
So the count is five before and after v5.

**Concrete recipe for adding a new module's jar, once its `CovertCarrier` exists** (corrected
to match the code):
1. Add one `Module` entry with its `jarName`/`jarRole`.
2. Add one branch in each of the five exhaustive `when`s above. The compiler lists them.
3. Review the non-exhaustive `DETECTOR`/`ACOUSTIC_MODEM` checks.

The `FireflyLog` schema, the mode-switch mechanism, and the `DecodeFailure` → copy mapping
(screen-flow.md § Screen 7, already module-agnostic) need zero changes.

---

## Sturdy image technique (v6)

> Task W0-A (design + offline measurement). Author: spec-architect-v11, revised 2026-09-22 (round 2,
> disguise retune). A second Module-1 image technique, sibling to the frozen exact-LSB carrier —
> **not** a change to it (INV-9). Its own magic tells it apart from every pre-v6 firefly. Every
> number below is measured offline by the prototype in `scratchpad/sturdy/` (`sturdy_carrier.kt` +
> `harness.kt`), re-measured with two JPEG encoders. Real-channel confirmation is gate-29's job.

### Scheme — SFLY (dither-QIM on a logical luminance grid)
1. **Luminance only.** Work on Y = 0.299R+0.587G+0.114B; leave Cb/Cr untouched (messaging apps
   subsample chroma 4:2:0, so only luma survives). A luma shift is applied by adding the same delta
   to R, G, B — this moves Y and leaves Cb/Cr exactly where they were.
2. **Logical grid, relative to dimensions.** A `GX × GY` grid: cell (gx,gy) owns source columns
   `[gx·W/GX, (gx+1)·W/GX)` and matching rows. Encoder and decoder both read each cell's **mean
   luminance**. A uniform downscale keeps which pixels fall in which cell, so the decoder recomputes
   the grid on whatever size it receives — no registration, no side channel.
3. **Dither QIM per cell.** Each coded bit is carried by one cell's mean luminance: bit 0 snaps the
   mean to the lattice `{k·Δ}`, bit 1 to `{k·Δ+Δ/2}`; the whole cell shifts by one flat luma delta
   to reach the target. A flat shift is DC-ish — JPEG's DC term and every resampler preserve it, and
   the eye tolerates it far better than the same energy as high-frequency noise.
4. **FEC + repetition + interleave.** The payload is wrapped in a self-describing frame, protected
   by Reed-Solomon over GF(256), and each coded bit is **repeated R times, dispersed across the grid**
   by a fixed interleave so a JPEG-block burst only nicks each symbol. The decoder soft-combines the
   copies (each weighted by its lattice-proximity confidence), hard-decides, RS-corrects, then checks
   CRC-32. RS + CRC make a damaged image decode to *damaged*, never a wrong message (INV-12).
5. **DC-offset search.** JPEG/resampling can shift absolute luminance, biasing every QIM decision the
   same way. The decoder tries a few DC-offset hypotheses (± fractions of Δ) and accepts the first
   whose RS **and** CRC-32 pass (false accept ≈ 2⁻³² per trial).

### Frame format (before FEC)
Own magic `0x53 0x46` (`"SF"`) — deliberately **not** exact-LSB's `0x4E` — so the two techniques
never collide and auto-detect can tell them apart (INV-9, INV-12).

| Field    | Bytes | Value / meaning                                             |
|----------|-------|------------------------------------------------------------|
| magic    | 2     | `0x53 0x46` (`"SF"`; distinct from exact-LSB `0x4E`)        |
| version  | 1     | `0x01`                                                      |
| length   | 2     | payload byte count, big-endian (== 64 this version)        |
| payload  | N     | N = 64 bytes (fixed → decoder geometry needs no side info)  |
| crc32    | 4     | CRC-32 (IEEE) over magic+version+length+payload, big-endian |

Frame = 5 + 64 + 4 = **73 bytes**, RS-encoded as one codeword `RS(73+48, 73) = RS(121, 73)`
(corrects up to 24 byte-errors).

### Shipped parameters (round-2 retune, measured)
| Param | Value | Reasoning |
|-------|-------|-----------|
| Grid `GX×GY` | **96 × 96** (9216 cells) | Enough cells for R=8 at this payload; at a 640 px long side each cell is ~7 px (~50 px averaged), which the DC/JPEG path preserves. |
| `Δ` (QIM step) | **10** (luma) | Round 1 shipped Δ=24 and was **visibly blocky** (coordinator round-2 finding). Δ=10 is the lowest step that still cleared MMS-like on all covers under both JPEG encoders (see matrix), and it cuts the artifact hard: PSNR 31.6→39.6 dB, max luma delta 12→5. |
| Max per-pixel luma delta | **5** (= Δ/2) | Worst-case flat shift to reach a lattice point (was 12). |
| RS parity | **48** → RS(121,73), t=24 | Half-rate FEC; a safety net on top of the repetition. |
| Repetition `R` | **8** | Soft-combining 8 dispersed copies is what lets Δ drop to 10 and still survive q50. |
| Payload | **64 bytes** (fixed) | Meets the ≥64-byte gate; fixed size keeps decoder geometry deterministic. |

Bit budget: coded 121 B = 968 bits × R=8 = 7744 cells of 9216.

### Covers
45 lossless images: **24 real Kodak photos** (kodim01–24, from the FFDNet mirror on GitHub — r0k.us
still returned HTTP 401; these are the real suite, but this mirror serves them center-cropped to
**500×500** rather than the original 768×512, which makes them a *small-image stress case*), plus 18
skimage natural photos and 3 synthetics. Sizes 191–1411 px.

### Measured survival matrix (round 2, Δ=10)
64-byte payload embedded, pushed through each channel, decoded; a cell counts covers whose payload
returned **byte-exact**. Measured with two independent encoders:

| Pipeline (long side × JPEG q, 4:2:0) | javax.imageio | libjpeg-turbo (`convert`) |
|--------------------------------------|:---:|:---:|
| orig × q95 / q80 / q70 / q50         | 45/45 each | — |
| 1080 px × q80(bicubic) / q70(bilinear) | 45/45 / 45/45 | — |
| 640 px × q70(bicubic) / q60(bilinear)  | 45/45 / 45/45 | — |
| **Facebook-like** (2048→q85→1080→q75)  | **45/45** | 41/45 |
| **MMS-like** (640 px, q50)             | **45/45** | **45/45** |
| mms_like, covers ≥ 640 px only         | **7/7** | — |

Note the encoder sensitivity: PIL/Pillow's q50 (a third encoder, used during the parameter search)
was harsher and scored MMS ~40/45 — the small 500 px Kodak crops are the ones on the edge. The
authoritative libjpeg-turbo path (what Android/Facebook use, via `convert -sampling-factor 4:2:0
-quality 50`) gives MMS **45/45**. The 4 FB-like misses under `convert` are all 500 px covers; on
covers ≥ 640 px (real phone-photo territory) FB-like is clean. **FEC margin** stayed large (RS
corrected 0 bytes on the surviving cases — the R=8 soft-combine clears the bits alone).

### Full-resolution gate-28 re-run (task W1-1, supersedes the 500 px Kodak caveat below)
The 500 px Kodak-crop caveat below predicted *better* survival at full resolution. Re-measured
offline (per `offline-kotlin-calibration.md`, driving the actual shipped `SturdyImageCarrier`
class, not a re-implementation) against **24 real, full-resolution Kodak photos (768×512/512×768,
`msdkhairi/kodak` on Hugging Face — a different reachable mirror; r0k.us still 401s)** — the honest
result is **mixed, not uniformly better**:

| Pipeline (long side × JPEG q, 4:2:0) | javax.imageio | libjpeg-turbo (`convert`) |
|--------------------------------------|:---:|:---:|
| orig × q95 / q80 / q70 / q50          | 24/24 each | — |
| 1600 px × q85(bicubic)                | 24/24 | — |
| 1080 px × q80(bicubic) / q70(bilinear) | 24/24 / 24/24 | — |
| 640 px × q70(bicubic) / q60(bilinear)  | **20/24** / **20/24** | — |
| **Facebook-like** (2048→q85→1080→q75)  | **24/24** | **24/24** |
| **MMS-like** (640 px, q50)             | **19/24** | **19/24** |

PSNR mean 39.7 dB (min 39.5) — matches the 500 px-crop measurement's 39.6/39.4 closely, confirming
visibility is stable across cover sets. Crop-10%/rotate-90° failure envelope: 24/24 NoFirefly, 0
Damaged, **0 wrong payloads** — that invariant holds exactly as before.

**The genuinely new finding:** the previous 500 px-crop matrix's "640 px" and "MMS-like" columns
never actually downscaled the Kodak covers at all — `resizeLong` is a no-op once an image is
already under its target long side, and 500 < 640. That matrix's 45/45 on those rows was really
measuring "JPEG-recompress a 500 px image at native size," not "downscale a real photo to 640 px
then recompress" — the exact scenario a phone photo sent by MMS goes through. At true full
resolution, the **640 px-long-side pipelines and MMS-like now show real, non-zero misses (17-21%
across 24 covers)**, both encoders agreeing exactly (19/24 each on MMS-like) — libjpeg-turbo is not
more forgiving here. Failures cluster in landscape (768×512) covers; none of the 6 portrait
(512×768) covers failed on any pipeline in this run. 1080 px and above remain fully clean (24/24).
This is a real, measured regression versus the prior table's optimistic 640/MMS numbers, not a
port bug — the shipped Δ=10/R=8 parameters were tuned against the smaller/cropped set; gate-29's
real-channel sends are the next, authoritative check, and a future retune (larger Δ, higher REP,
or the dark/bright-skip refinement) is the likely next step if gate-29 confirms this gap on real
sends. Reported here, not hidden, per this doc's own standard.

JPEG file size at 1600 px long side (send-quality justification, task W1-1's `STURDY_JPEG_QUALITY`
adapter constant) on these 24 covers (native ≤768 px, so no resize applied — these are native-size
JPEG bytes, a lower bound on what a true 1600 px photo would weigh): q85 mean 91 KB, q88 103 KB,
**q90 114 KB**, q92 126 KB, q95 165 KB. q92 costs ~11% more than q90 for no measured survival or
visibility gain; q90 is the better trade.

### Visibility (round 2)
Δ=10: **PSNR mean 39.6 dB (min 39.4), SSIM 0.964, max per-pixel luma delta 5, and max luma delta in
flat (std<3) 12×12 blocks = 5.** Eyeballed at 1:1 and 4×: the cup, saucer, wood, skin and fabric are
indistinguishable from the cover; the only residual is a **very faint mottling in deep-shadow flat
regions** (Weber's law — the eye is most sensitive to small absolute deltas in the dark). Three
cover-vs-sturdy side-by-sides and three 4× crops of each image's flattest region are in
`scratchpad/sturdy/eyecheck/` for gate-30.

### The core trade-off (measured, reported not hidden)
Three approaches were built and measured this round; **true invisibility and MMS-q50 survival are
mutually exclusive in a luminance-QIM scheme:**
- **All-cell, Δ=24 (round 1):** MMS/FB fully survive, but visibly blocky (PSNR 31.6, Δ/2=12).
- **All-cell, Δ=10 (shipped):** near-invisible except faint deep-shadow mottling; MMS/FB survive on
  all covers under two encoders. **Best balance — shipped.**
- **Perceptual masking (skip flat/dark cells so nothing shows there):** genuinely invisible on
  textured photos (PSNR 42.5, SSIM 0.986) **but** (a) needs texture to hide in, so it fails to embed
  64 bytes in smooth/dark-dominated images (e.g. Hubble field, moon), and (b) the smaller Δ it
  enables does **not** survive MMS q50 (≈40% MMS in tests). The mask is also fragile: a fine-texture
  activity map shifts under the embedding itself (≈6% of cells flip active-state), breaking a
  decoder that recomputes it; a fixed-point encode plus a blurred/brightness mask helps but does not
  recover MMS survival. **Not shipped.**
- **Brightness-only dark/bright skip on top of all-cell Δ=10** (a lighter refinement): removes the
  deep-shadow residual entirely (dark-region max delta 5→**0**, PSNR→40.2, SSIM 0.971) and is robust
  (brightness is low-frequency, stable to embedding and channel). Cost: it fails to embed on
  **dark-dominated images** (clean 42/45 — Hubble/moon), so it needs a graceful fallback (embed dark
  cells anyway when too few remain; decoder tries both, CRC disambiguates). **Recommended as a W1
  option, gated on gate-30:** if the owner finds the Δ=10 deep-shadow mottling visible at 1:1, enable
  it; otherwise keep the simpler universal all-cell path.

### Failure envelope (asserted, gate-28)
Across all 45 covers, **0 wrong payloads** in every case:

| Attack | Outcome |
|--------|---------|
| 10% crop | 45/45 NoFirefly |
| 90° rotation | 45/45 NoFirefly |
| screenshot-like (0.9 + border) | 45/45 NoFirefly |
| grayscale + 1.3× contrast | 44/45 NoFirefly, 1/45 still-correct |

Honest nuance (unchanged from round 1): SFLY lives in **luminance**, so a pure grayscale conversion
does not destroy a firefly; only a contrast/levels change that *scales* luma enough to break the
absolute Δ lattice does. The one still-correct case was the correct payload, never a wrong one.

### No false catch (gate-27)
**0 false catches across 463 clean, never-embedded images** (all covers untouched, JPEG-recompressed
and resized copies, random crops/rescales, and synthetic noise/gradients). Magic + version + CRC-32,
re-checked under every DC hypothesis, is what keeps a clean image reading as *no firefly*.

### Known risks / open items
- **Simulation ≠ the real apps.** Two library encoders (javax.imageio, libjpeg-turbo/`convert`) with
  plausible chains. Facebook/Messenger/Telegram pipelines are undisclosed and change. Gate-29 (owner
  sends from real accounts) is the only ground truth; the matrix is a strong prior, not a guarantee.
- **Kodak served at 500 px — re-run complete (task W1-1).** Re-measured against 24 real,
  full-resolution (768×512) Kodak photos; see "Full-resolution gate-28 re-run" above. Result was
  *not* uniformly better as predicted — 1080 px and above stayed fully clean, but the 640 px-long-
  side pipelines and MMS-like dropped to ~79-83% survival (from an apparent 45/45 that, on the 500
  px crops, had secretly never triggered an actual downscale). A real, measured gap for gate-29 to
  confirm or refute on real channels; a future retune is the likely next step if it holds.
- **Deep-shadow residual at Δ=10.** Faint mottling in large dark flats; gate-30 decides if it needs
  the brightness dark-skip refinement (which then can't embed in dark-dominated images without the
  fallback).
- **Small images.** Survival falls below ~512–640 px long side; the app should warn/refuse covers with
  a long side under ~640 px (a phone photo is far above this).
- **Grayscale/contrast honesty.** Grayscale alone preserves the payload; the receive-side copy must
  not claim a filtered image is necessarily clean.
- **Port note (W1-1).** Pure JVM, no android.*. In the app: back `PixelSurface` with a `Bitmap`
  adapter; **delete** the bundled `Gf256`/`Rs` and call the existing
  `dev.herakles.nightjar.ReedSolomon`/`GF256` (same GF, prim poly 0x11d) — do not add a second RS;
  map decode outcomes onto the existing `DecodeResult`/`DecodeFailure`. The fixed LCG interleave ports
  as-is.
- **No SFLY-specific detector this round.** A steganalysis detector purpose-built for SFLY is a
  follow-up (spec out-of-scope). Measured instead (task W1-1, gate-30): the *existing* image
  chi-square/PoV check (tuned for raw-pixel LSB) **does** flag sturdy output — 0.997/0.915
  confidence, both bundled covers, both a sustained whole-image run. Dither QIM's per-pixel
  rounding of a continuous shift is value-dependent in the same statistical way LSB replacement
  is, even though it never touches an LSB directly. The in-app copy must say sturdy *can* be
  flagged by the existing check, not that it evades detection.
  **Re-measured on real photos, not just the bundled covers (task W2-4, `SturdyImageSteganalysisRealPhotoTest.kt`):**
  the bundled-cover result does **not** generalize. Three real photographs (900x700, public-domain
  Unsplash/Lorem Picsum sources, `app/src/test/resources/sturdy_photos/`) run through the app's own
  send pipeline (`SturdyImageCarrier.encode` → `encodeSturdyJpeg` q90 → decode) are flagged only
  2/3 of the time (confidences 0.868/0.985/0.565, `flagThreshold` 0.85) — and, photo for photo, the
  **same** flagged/clear verdict lands on an unembedded copy of that photo pushed through the
  identical q90 JPEG export (0.966/0.989/0.469): the run this detector finds tracks that photo's
  own JPEG-quantization pattern, not whether anything is hidden in it. Even the pristine,
  never-exported working bitmap (the state right after tapping "embed", before any save/share) is
  only flagged 2/3 on real photos (0.905/0.943/0.492) — unlike the bundled covers' 2/2. After a
  real-channel-shaped recompression pass (`simulateFacebookLikeChannel`/`simulateMmsLikeChannel`,
  the same `JpegLumaSim.kt` utility gate-28/29 use), confidence collapses near zero for sturdy
  **and** clean alike, 4/4 pairs across all three photos — the check essentially stops flagging
  either one once a photo is actually sent. `ImageStegoScreen.kt`'s sturdy "check for hidden data"
  caption is written from these real-photo numbers, not the bundled-cover ones: it says the
  flagged/clear reading is unreliable for sturdy, not that sturdy reliably gets caught.
