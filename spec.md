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
- **Out of scope:** Module 4 video steganography (needs a non-mobile ML
  watermarking component) — deferred; an `AudioStegDetector` counterpart for
  Module 2 (phase-correlation / cepstral-anomaly analysis) — deferred until
  this addition's codecs exist to validate against, per covert-data's Module 5
  sequencing note; any real exploit/malware payload; upload to any third-party
  platform; free-space RF retransmission of any kind; real disguise/anti-
  forensics hardening for the Firefly Jar addition (launcher-icon swapping,
  app-name spoofing in the OS app list, PIN/decoy-content vault behavior) —
  this is a research demonstration of UI-level covert presentation as a
  technique (Track 4's subject matter turned on the app's own interface), not
  a hardened operational disguise tool; a persisted firefly history for the
  detector (Module 5) — it stays a passive one-way analyzer per INV-4, its
  existing flagged-detection history is re-skinned, not modeled as a firefly
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

## Non-goals
- Not a Bluetooth/WiFi file-transfer replacement — throughput is
  intentionally low; this is a research/demo channel
- Not a production security product — no completeness claim beyond the
  documented steganalysis techniques
- No backend/server component in v1
- No broad device-compatibility matrix — targets the Pixel 6a + one second
  test device only

## Runtime Verification Surface
- Probe contract: debug builds expose a logcat-tagged JSON state dump
  (`COVERT_DEBUG` tag) — last encode/decode result, detector confidence
  score, current module-picker screen — queryable via the existing `hek
  logcat` bridge, so verification never falls back to screenshot-only checks
- Waiver: N/A — probe required and defined above

## Agents
| Role | Agent | Why |
|------|-------|-----|
| architecture | spec-architect-v11 | FSK/PSK + FEC protocol design and the module-boundary contract are novel |
| implementation | spec-implementer-v11 | bulk Kotlin build across module-picker, modem, detector, image stego |
| visual/UX | android-designer | already scoped to this pixel6a workspace |
| testing | spec-tester-v11 | round-trip + detector accuracy verification on both physical devices |

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
