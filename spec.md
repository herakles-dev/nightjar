# nightjar — Spec

## Intent
A single Android app on the Pixel 6a ("Hek") and a second physical phone that
demonstrates live, room-local, phone-to-phone covert data transmission over
sound — paired with a real-time on-device detector that can flag the very
channel it transmits on. Built as a mobile signal-processing app with a
streaming audio-capture/decode architecture. Unifies Track 4's completed
research library (`/home/hercules/covert-data`) — acoustic modem, image LSB
steganography, and their steganalysis countermeasures — into one hands-on
research/demo tool, replacing the zero lines of PoC code that currently exist
anywhere in the covert-data research base.

## Boundaries
- **In scope (v1, shipped):** module-picker home screen; Module 3 acoustic FSK/PSK
  modem (encode+decode, Reed-Solomon FEC, room-local speaker→mic between two real
  devices); Module 5 acoustic detector (passive spectral/energy anomaly
  listener, self-detects Module 3's own signal); Module 1 image LSB
  steganography (encode/decode) + its chi-square/RS steganalysis counterpart;
  benign synthetic payloads only (text strings, small files)
- **In scope (v2 addition, this spec revision):** Module 2 audio steganography —
  phase-inversion, spectrogram-LSB, and MFSK-robustness embed/extract against a
  bundled cover clip, as a new `CovertCarrier<PcmAudio>` implementation plus a
  4th module-picker row. "Check for hidden data" is explicitly deferred — it
  needs a new `AudioStegDetector` that doesn't exist yet (see Module Interface
  §7 in `architecture.md`).
- **In scope (v3 addition, this spec revision):** Firefly Jar — an alternate,
  disguise-themed front-end. Every creation-capable module (acoustic modem,
  image steganography, audio steganography) becomes a jar; every embedded/
  transmitted payload ("created") or extracted/decoded payload ("received") is
  visualized as a firefly, color-coded by direction. Reuses all three existing
  `CovertCarrier` implementations with zero interface changes — a new
  persisted history log and two new screens (jar shelf, per-module jar detail)
  are the only new surface (see `architecture.md` § Firefly Jar). Becomes the
  app's default launch screen; the existing technical picker/screens (all
  four, including the detector) become a hidden mode reached by a long-press
  reveal gesture. The existing anti-AI-tell doctrine (`identity.md`) does not
  apply to this surface — see `design/firefly-jar-identity.md` for its own,
  deliberately different visual language (explicit user direction, not an
  oversight). The four technical screens are unchanged and remain fully
  functional side-by-side with this front-end.
- **In scope (v4 addition, this spec revision):** Firefly content — the carrier
  artifact behind each firefly is persisted alongside its `FireflyRecord`, so a
  caught firefly can be seen, heard, and understood rather than only counted.
  The stego image and the stego/transmitted audio clip are written app-private
  at catch time; the per-jar swarm becomes a browsable roster of thumbnails; the
  firefly detail gains a carrier viewer (image render, or waveform + playback), a
  "where it hid" visualization, and a capacity readout. Storage is user-governed,
  not silently unbounded: a total-usage readout, an advisory warning above a
  threshold, clear-all, and per-firefly delete. Carrier kind is a field on the
  record, not a per-`Module` branch — `architecture.md` § 6 still permits exactly
  two exhaustive per-module `when`s and this adds none. The detector is untouched:
  it creates no fireflies and therefore gains no media (INV-4).
- **In scope (v5 addition, this spec revision):** Module 2's "check for hidden
  data" counterpart, plus the two honest carrier views v4 deferred.
  - An `AudioStegDetector` (`CovertDetector<WavFile.ParsedWav>`, stego-only):
    blind in that it never sees the cover, a key or a decode, and targeted at the
    app's own three techniques — channel-polarity anti-correlation with a
    surviving L+R residual, QIM lattice snapping on the codec's own grid, and
    keyed tones at 19.7–20.0 kHz. Reachable from the audio technical screen
    ("check for hidden data") and the humming jar ("peek inside"). Any anti-phase
    stereo with something added to one side reads as consistent with
    phase-inversion, and the copy says a polarity-flipped recording looks the same.
  - A cover-vs-stego difference view for spectrogram-LSB fireflies, drawn
    against a cover re-derived from the bundled synthesizers — no stored cover,
    no schema change; withheld with a stated reason when no cover re-derives.
  - An L/R polarity view for phase-inversion fireflies (the persisted WAV is
    already stereo end-to-end).

  Both views live in the firefly detail only; the technical audio screen gains
  the detector verb and nothing else (Screen 5 keeps its no-waveform/no-meter
  restraint). View options key off `FireflyRecord.technique` and the WAV's channel
  count, never `Module` — the addition introduces no per-`Module` branch
  (`architecture.md` § Firefly Jar § 6). The spectrogram-LSB embedding format is
  unchanged: caught fireflies depend on it.
- **Out of scope:** Module 4 video steganography (needs a non-mobile ML
  watermarking component) — deferred, and still out of scope under v5; the
  `AudioStegDetector` counterpart for Module 2 is now in scope under the v5
  addition above; any real exploit/malware payload; upload to any third-party
  platform; free-space RF retransmission of any kind; real disguise/anti-
  forensics hardening for the Firefly Jar addition (launcher-icon swapping,
  app-name spoofing in the OS app list, PIN/decoy-content vault behavior) —
  this is a research demonstration of UI-level covert presentation as a
  technique (Track 4's subject matter turned on the app's own interface), not
  a hardened operational disguise tool; a persisted firefly history for the
  detector (Module 5) — it stays a passive one-way analyzer per INV-4, its
  existing flagged-detection history is re-skinned, not modeled as a firefly;
  a cross-jar library screen for the v4 addition — the per-jar swarm is the
  collection surface (explicit user direction); automatic eviction or capping of
  stored carrier media — retention is user-managed through the usage readout and
  clear controls, not silently enforced (explicit user direction); export or
  share of carrier media *from jar mode* — writing to `Pictures/Nightjar` and
  `Music/Nightjar` stays the technical screens' deliberate save/share gesture;
  the cover-vs-stego difference view and L/R polarity view v4 deferred are now
  in scope under the v5 addition above.
  v5 addition, out of scope: universal steganalysis of arbitrary audio (other
  tools, keyed or dithered QIM, re-compressed, trimmed or re-levelled files) —
  the detector is targeted and its copy says so; running the audio-stego
  detector on the acoustic modem's clips (`AcousticDetector` owns Module 3);
  persisting cover audio or a cover id — a `Migration(2,3)` was evaluated and
  rejected, since re-derivation plus verification is strictly cheaper; a "check
  this firefly" verb in the firefly detail; difference/polarity views on the
  technical screen; changing the spectrogram-LSB embedding format (including the
  silent-frame click train it produces — that is a gate-8 headphone-check item,
  not a codec change)
- **Crosses:** device speaker/mic (AudioRecord/AudioTrack); local
  filesystem/MediaStore for sample images; the existing `hek` ADB bridge for
  install + debug-state verification (not a runtime dependency); no
  backend/server — fully on-device

## Invariants
- INV-1: Encoder paths only ever accept operator-supplied benign payloads —
  no bundled real exploit/malware payload exists anywhere in the codebase
- INV-2: All acoustic transmission stays within the device's own speaker/mic
  frequency response — no external RF radio path is ever invoked
- INV-3: FEC-protected acoustic round-trip is lossless for payloads under the
  architect-defined max size at the architect-defined SNR/distance envelope
- INV-4: The detector can identify the app's own Module 3 transmission live —
  self-detectability is the defensive proof-of-concept this app exists for
- INV-5: Persisted carrier media stays app-private (`filesDir`) — it is never
  written to shared storage and never leaves the device except through the
  technical screens' existing, explicitly-invoked save/share action
- INV-6: No firefly media outlives its record and no record outlives its media —
  clearing (all, or one) removes rows and files together, and an orphan sweep
  reclaims any file a crash stranded between the two writes
- INV-7: Every Module 2 technique is self-detectable from the stego clip alone —
  `AudioStegDetector` flags the app's own output for all three codecs at every
  strength without the cover, a key, or a decode, and a check never writes a
  `FireflyRecord`. Module 2's counterpart to INV-4
- INV-8: Known-cover views never guess — a difference view is drawn only against
  a cover that re-derives to within −20 dB residual energy of the stego;
  otherwise it is withheld with a stated reason, never approximated. The v5
  addition leaves the Room schema at version 2

## Non-goals
- Not a Bluetooth/WiFi file-transfer replacement — throughput is
  intentionally low; this is a research/demo channel
- Not a production security product — no completeness claim beyond the
  documented steganalysis techniques
- No backend/server component in v1
- No broad device-compatibility matrix — targets the Pixel 6a + one second
  test device only
- Not a media gallery or file manager — the firefly collection exists to explain
  the covert channel that produced it, not to organize the user's media

## Runtime Verification Surface
- Probe contract: debug builds expose a logcat-tagged JSON state dump
  (`COVERT_DEBUG` tag) — last encode/decode result, detector confidence
  score, current module-picker screen — queryable via the existing `hek
  logcat` bridge, so verification never falls back to screenshot-only checks
- Probe contract (v4 addition): the same dump gains stored-media state — record
  count, count carrying media, total media bytes, and orphan-file count — so
  retention, clear-all, per-firefly delete and the orphan sweep are all assertable
  as queryable state rather than judged from a screenshot
- Probe contract (v5 addition): the technical screen's check and the humming
  jar's peek report detector confidence under `ModuleId.AUDIO_STEGANALYSIS`, so
  flagged/clear is assertable from `COVERT_DEBUG`, and a peek leaves the stored
  record count unchanged
- Waiver: N/A — probe required and defined above

## Agents
| Role | Agent | Why |
|------|-------|-----|
| architecture | spec-architect-v11 | FSK/PSK + FEC protocol design and the module-boundary contract are novel |
| implementation | spec-implementer-v11 | bulk Kotlin build across module-picker, modem, detector, image stego |
| visual/UX | android-designer | already scoped to this pixel6a workspace |
| testing | spec-tester-v11 | round-trip + detector accuracy verification on both physical devices |
| persistence (v4) | database-engineer | the Room v1→v2 migration is the one change that can brick launch for existing installs |
| steganalysis calibration (v5) | spec-tester-v11 | thresholds are measured against the real codec, not asserted |

## Gates
- gate-1: scaffolded, builds, module-picker navigates 3 empty stubs
- gate-2: two phones exchange a benign text payload over sound, same room
- gate-3: detector flags the app's own transmission live; image module round-trips
- gate-4: safety-scope compliance check passed; both modules polished, verified on-device
- gate-5: installed on both devices; covert-data repo cross-referenced; session closed

## Gates — v2 addition (Module 2 audio steganography)
- gate-6: all 3 codecs (phase-inversion, spectrogram-LSB, MFSK) pass a pure-JVM
  `decode(encode(payload)) == payload` round-trip unit test, no device required
- gate-7: audio-steganography screen wired into `MainActivity`; technique + cover
  selectors and embed/extract verbs functional against bundled sample clips,
  verified on Hek
- gate-8: playback (`play cover` / `play working`) confirms cover and stego clips
  are audibly indistinguishable at default strength for phase-inversion and
  spectrogram-LSB — the fidelity leg of the triangle, verified by ear on real
  hardware, not asserted
- gate-9: safety-scope compliance check passed (synthetic payloads only, no
  exploit content); anti-AI-tell checklist re-run against the new screen
- gate-10: covert-data's `module_2_audio_steganography/README.md` updated to
  point at nightjar as its hands-on home (matching modules 1/3/5's existing
  cross-reference); session closed

## Gates — v3 addition (Firefly Jar alternate front-end)
- gate-11: `FireflyRecord` data model + Room-backed log store implemented and
  unit-tested (insert, query-by-module, query-all, clear-all); zero changes to
  any existing `CovertCarrier`/`CovertDetector` implementation
- gate-12: jar shelf screen (4 tiles — 3 creation jars + the detector's
  watching jar) + per-module jar detail screen wired into `MainActivity`,
  becomes the app's default launch screen; long-press on the shelf's wordmark
  reveals the existing technical picker; a plain, visible affordance from
  technical mode returns to the jar shelf
- gate-13: every existing capability across all 4 modules is reachable from
  jar mode with accurate (if softened) framing — catch + look-for-fireflies on
  all 3 creation jars, image steganography's existing "check for hidden data"
  verb included, and the detector's passive listen/flag re-skinned as the
  watching jar — not a subset of what the technical screens already do
- gate-14: safety-scope compliance check passed — payload constraints
  unchanged (INV-1 still holds, synthetic/benign only); the new persisted
  firefly history (this app's first surface that keeps user content across
  restarts) reviewed for what it stores, and a clear-history action exists
- gate-15: covert-data cross-referenced (a short note in the relevant module
  README(s) or `RESEARCH_CONTEXT.md`); session closed

## Gates — v4 addition (firefly carrier content)
- gate-16: schema v2 lands safely — `FireflyRecord` gains its carrier fields, a
  `Migration(1,2)` ships, schema export is switched on, and a JVM test proves a
  v1 database holding rows opens, migrates, and keeps those rows with null
  carrier fields. The database is version 1 today with no migration and no
  destructive fallback, so this is the one change in the addition that can brick
  launch for anyone with existing fireflies — it lands alone, before any UI
- gate-17: carrier media is captured and provably intact — the three creating
  jars write their artifact at catch time, and a round-trip test shows the bytes
  the media store wrote decode back to a bitmap/PCM that still yields the
  original payload through that carrier's own `decode`; the detector still
  writes nothing (INV-4)
- gate-18: a caught firefly can be seen and heard — images render, audio plays
  with one clip at a time and stops on dispose, and pre-migration records with
  no media degrade to the existing text-only layout instead of erroring
- gate-19: the channel is legible, honestly — LSB bit-plane for images and a
  spectrogram for audio, with the two cases a spectrogram genuinely cannot show
  (spectrogram-LSB's sub-perceptual QIM, phase-inversion's stereo polarity)
  labeled as such rather than shipped with a visualization that implies the eye
  should catch something it cannot
- gate-20: retention is governed and honest — usage readout, advisory warning
  above threshold, clear-all removing rows *and* files, per-firefly delete, and
  an orphan sweep; INV-5/INV-6 hold under test, extending gate-14's retention
  review to cover carrier media
- gate-21: docs reconciled — `architecture.md` § 3/§ 6, `design/screen-flow.md`
  (its Screen 7 content list and its explicit per-firefly-delete deferral are
  both superseded), `design/firefly-jar-identity.md` (waveform/spectrogram/
  bit-plane are data visualizations, not decoration — confirm against the
  mascot/carousel/Discover ban); covert-data cross-referenced; session closed

## Gates — v5 addition (audio steganalysis + honest carrier views)
- gate-22: `AudioStegDetector` lands as a pure-JVM
  `CovertDetector<WavFile.ParsedWav>` that never constructs a `CovertCarrier`,
  calls `decode`, or reads nightjar's frame header (INV-7). Its calibration test
  encodes through the real `AudioStegoCarrier`, so a drifted detector constant
  fails loudly. At the shipped `flagThreshold`: flagged for all three techniques ×
  both bundled covers × spectrogram-LSB strengths 1–4 × payloads {0, 1, 5, 20,
  max}; not flagged for both clean covers and their stereo variants, digital
  silence, a steady 19.7 kHz tone, and 40 seeded noise covers; each documented
  evasion asserted missed, so the UI's "can't detect" copy stays true; anti-phase
  stereo with an added residual asserted flagged, so the "a polarity-flipped
  recording looks the same" copy stays true. Measured margins recorded in the
  test KDoc
- gate-23: "check for hidden data" on the audio technical screen (detector
  injected from `MainActivity`) and "peek inside" on the humming jar are wired;
  a check or peek writes no firefly; `COVERT_DEBUG` reports `AUDIO_STEGANALYSIS`
  confidence. On Hek, a clean cover reads clear and each technique's stego reads
  flagged with the right technique leading the detail — verified from the probe,
  not a screenshot
- gate-24: the difference view ships for spectrogram-LSB fireflies with no
  schema change (database stays at version 2; no `3.json` exists); the cover is
  re-derived and accepted only below −20 dB residual (INV-8). JVM tests prove
  changed frames equal embedded frames exactly, untouched frames are exactly
  unchanged, nudges are ≤ 1.5Δ, silent-cover cells are classified as created,
  and a drifted or wrong cover is rejected with the view withheld and its reason
  shown
- gate-25: the L/R polarity view ships for phase-inversion fireflies; the
  persisted WAV is proven stereo from catch through the media store to
  `decodePcm16`. JVM tests prove correlation ≤ −0.999, residual steps equal the
  embedded bit count exactly, and the zoom window shows L ≈ −R. Mono, MFSK and
  pre-migration fireflies gain no new option
- gate-26: honesty reconciled — the spectrogram-LSB caption no longer claims a
  spectrogram can't show it, with the silent-cover case measured and tested on
  both bundled covers; every v5 caption backed by a test; the spectrogram-LSB
  silent-frame click train (~−60 dBFS, 47 Hz) added to gate-8's headphone
  listening pass; `architecture.md` § 7 and `design/screen-flow.md` Screens 5/7
  updated, and `design/firefly-jar-identity.md`'s data-visualization allowance
  confirmed for the two new views; covert-data module-2 and library §06
  cross-referenced; session closed
