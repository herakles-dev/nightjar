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
- **No `CovertDetector<PcmAudio>` binding ships with this addition.** A real audio-steganalysis
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

Gate-8 (fidelity — cover vs. stego indistinguishable by ear) and gate-9 (safety-scope +
anti-AI-tell close-out) remain open; both need a human listening pass, not something an agent can
self-certify.

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
family untouched, same restraint §7 exercises). One `fireflyDao.insert(...)` call sits in
each screen's controller, right after a successful encode/decode:
`AudioStegoController`, the acoustic modem's controller, and the image steganography
controller each gain one line in their success paths (embed → `CREATED`, extract/decode
→ `RECEIVED`; the acoustic modem's transmit → `CREATED`, listen-and-decode → `RECEIVED`).

**The log is shared, not per-surface.** Both the new jar-mode screens *and* the existing
four technical screens write to the same `FireflyDatabase` — a message embedded from the
plain technical picker still shows up as a firefly the next time jar mode is opened. One
history, two skins on top of it, not two parallel bookkeeping systems.

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
        "the picture jar", JarRole.CREATION),
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

**Concrete recipe for adding a new module's jar, once its `CovertCarrier` exists:** (1)
add one `Module` entry with its `jarName`/`jarRole`; (2) add one `when` branch in
`FireflyGlyphs.kt`; (3) add one `when` branch in `JarCatchFlows.kt` wiring its real
carrier. `JarShelfScreen`, `JarDetailScreen`, the `FireflyLog` schema, the mode-switch
mechanism, and the `DecodeFailure` → copy mapping (screen-flow.md § Screen 7, already
module-agnostic) all need zero changes.

---
