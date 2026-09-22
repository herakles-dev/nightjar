# nightjar — v6 Handoff (2026-09-22)

## Shipped

v6 scope ("sharing + first-use riddle trail," gates 27–40) is feature-complete on
`feat/v6-sharing-trail` — verified on real hardware (Pixel 6a "Hek" + a second phone) —
but the branch has **not** been merged to main yet:

- **Sturdy image carrier** (gates 27–30): a new pure-JVM `CovertCarrier`, distinct from the
  exact-LSB technique, that survives real-world compression — measured offline against JPEG
  recompression (q 50–95), downscale (1600/1080/640px), and 4:2:0 chroma subsampling across
  ≥ 20 real photo covers, with margins recorded in the test KDoc. Crops, rotation, heavy
  filters and screenshots fail cleanly (damaged or no firefly, never a wrong message).
  Channel survival was measured for real by the owner across Messenger, Facebook, MMS,
  Telegram and K-9 email, landing as a measured table in `architecture.md` that drives the
  in-app channel advice. Fidelity was eyeballed by the owner (gate-8 pattern) and the image
  check's JPEG false-flag rate was measured and disclosed. The exact-LSB frame and every
  audio format are byte-for-byte unchanged (pre-v6 fixtures still decode).
- **Receive pipeline** (gates 31–32): the manifest accepts `ACTION_SEND`/`ACTION_VIEW` for
  `image/*` and `audio/*`; technique is auto-detected (exact, sturdy, all three audio
  techniques, acoustic modem WAV) and routed into the right jar; "catch from a photo or file"
  added via Photo Picker/document picker with no new permission (INV-10). Honest, distinct
  failure outcomes for squeezed/damaged/no-firefly/unsupported inputs, verified by bridge
  intent injection and real shares from Fossify Gallery, Telegram and K-9.
- **Send pipeline** (gates 33–35): "send this firefly" / "hide one in a photo" (sturdy by
  default, exact optional) open the share sheet from a private `FileProvider` cache file,
  with capacity/size shown up front and cache files swept after. Workshop audio screen can
  save/share/decode a received WAV round-tripped between Hek and the second phone. MFSK's
  "lossy-channel robust" claim was measured against AAC/Opus transcodes and one real channel,
  with UI copy corrected to match.
- **Riddle trail** (gates 36–38): a fresh install (or "start the trail again") seeds one
  practice firefly per creating jar, each caught through the real production decode path
  (INV-11). One next step glows at a time, survives process death, and supports skip/replay.
  All four custom-drawn carrier views (bit-plane, spectrogram, difference, polarity) now have
  TalkBack descriptions (closes follow-up #12), and every riddle was checked against the code
  and capacity-tested before owner sign-off.
- **Welcome card, constellation + rewards, meadow/art jar** (gates 39–40): fresh installs see
  a welcome card (`begin` starts the trail, `skip` skips it); a constellation shows `N of 6`
  with one pulse per completed step; each step has a quest line and a reward line on
  completion. The framed jar was renamed to the art jar and the meadow added as a fourth jar
  (shelf/workshop order: singing, art, humming, meadow — pinned by a `Module.entries` test, no
  persisted ordinal). A grep-backed test enforces the create-vs-catch verb rule: creating a
  firefly is never "caught."
- **Post-gate-40 review & owner-reported fixes**: a review pass added crash guards (caught
  `IOException` on send/keep-a-copy), fixed outgoing audio to keep its real MIME/extension
  instead of assuming WAV, moved trail practice-catch decoding off the main thread, disabled
  back while a hide-in-photo send is in flight, and closed the export-MIME loop by having the
  sniffer identify real audio codecs (m4a/mp3/ogg/amr). Separately, the owner caught firefly
  messages silently truncating to 40 characters on-device (fixed) and directed two UI passes:
  subtle tappable-vs-static affordance across the technical screens, and — overriding the
  anti-AI-tell doctrine by explicit owner direction (2026-09-22) — real button chrome. Most
  recent commit fixes "back" being unresponsive on all 4 technical screens (owner report,
  on-device).

## Merge status

**Merged to `main` 2026-09-22** via PR #2 (`gh pr merge --merge`, same convention as PR #1) —
`e59038b`, 71 commits, clean merge, no conflicts. This happened on explicit owner direction
("merge and wrap up") ahead of gate-41's full close below; two of gate-41's five items were
completed as part of the merge (see next section), the rest remain open on `main`, not just
on a feature branch.

## Gate 41 — closed (5 of 5 items done)

Gate 41 ("safety and close") is the closing audit for the whole v6 sprint. Status as of the
2026-09-22 safety re-audit + remediation pass:

- ~~Doc reconciliation~~ — **done**. `architecture.md` had 6 confirmed drift points (stale
  "not built" status on shipped gates 8-9/22-25, a stale `Module` enum snippet, an undercounted
  exhaustive-`when` table, ~1900 undocumented lines across `incoming/`+`trail/`) — all fixed
  and verified against real code. `design/riddle-trail.md` and `spec.md` also reconciled
  (WorkshopButton doctrine override recorded, meadow self-loop open item resolved honestly:
  code has a determined default, hardware reliability still unmeasured). `design/screen-flow.md`
  and `design/firefly-jar-identity.md` were not re-audited this pass — still worth a look.
- ~~Full unit suite run to green~~ — **done**. `./gradlew compileDebugKotlin` clean,
  `./gradlew test` green, all 63 unit test files, 0 failures, run immediately before the merge.
- ~~Safety-scope checklist re-run + incoming-intent surface review~~ — **done**. Full audit of
  `incoming/` (the app's only externally-facing surface) found 2 HIGH, 3 MEDIUM, 4 LOW findings
  plus a test-coverage gap — all fixed and verified (`./gradlew test` green, 62 test files after
  adding `IncomingPipelineTest`):
  - **F-1 (HIGH)**: unbounded PCM accumulation in the compressed-audio decode was a
    decompression-bomb → uncaught `OutOfMemoryError` crash reachable from a shared file. Fixed
    with an 8MB hard cap (`AcousticModemScreen.kt`) plus a matching `OutOfMemoryError` catch.
  - **F-2 (HIGH)**: `IncomingPipeline.route` had no `Throwable`-level boundary — provider
    exceptions, OOM, or ENOSPC on persist could all crash the app instead of resolving to one of
    INV-12's outcomes. Fixed: the whole body now runs under one `catch (Throwable)`.
  - **F-3 (MEDIUM)**: "file too large" was shown to the user as "nightjar doesn't know this kind
    of file" — the real reason was computed and discarded. Fixed: new `IncomingOutcome.TooLarge`,
    distinct copy, `IncomingAndroidAdapters` now reports *why* a bounded read/decode failed.
  - **F-4 (MEDIUM)**: no free-space floor on persisted carriers — `FireflyMediaStore.write` now
    refuses (new `IncomingOutcome.OutOfSpace`) rather than risk ENOSPC mid-write. Deliberately a
    device-free-space floor, not a retention ceiling — `STORAGE_WARNING_THRESHOLD_BYTES`'s
    "retention is user-managed" stays intact for ordinary usage.
  - **F-5 (MEDIUM)**: unbounded per-pixel JNI `getPixel` scans (tens of millions of calls on a
    crafted 24MP image) in both image codecs. Fixed: `ImageStegoCarrier.extractBytes` now does
    one bulk `getPixels()` read per call instead of one JNI call per bit;
    `BitmapPixelSurface` (sturdy technique) now row-caches via `getPixels()`.
  - **F-6 (LOW)**: the receive path used the *non*-cancellable `AcousticCarrier.decode()`
    overload, unlike the modem screen's own (more trusted) file-picker import. Fixed: `routeWav`/
    `routeCompressedAudio` now thread cooperative cancellation through.
  - **F-7 (LOW)**: `routeWav` never padded samples to the frame boundary, so a re-saved/trimmed
    acoustic-modem WAV would silently report `NoFirefly`. Fixed: now pads for the modem loop only.
  - **F-8 (LOW)**: the compressed-audio branch opened the source `Uri` twice (TOCTOU) — a
    hostile provider could serve different bytes each time. Fixed: `decodeCompressedAudioToPcm`
    now has a `ByteArray` overload decoding from the pipeline's own already-read bytes.
  - **F-9 (LOW)**: the intent filter accepted `file://` with no scheme restriction. Fixed:
    `IncomingPipeline` now rejects anything but `content://` before any read.
  - **F-10**: `IncomingPipeline` itself had zero tests. New `IncomingPipelineTest.kt` covers the
    scheme check, `TooLarge`, the `Throwable` boundary, and a real Caught+persist round trip.
- ~~v1–v5 on-device spot check on Hek~~ — **done**. Live-checked on Hek: jar shelf, jar detail,
  firefly detail (capacity line, waveform/spectrogram toggle, playback), and all 4 workshop
  modules (acoustic modem, image steg, audio steg's 3 codecs, detector) — no crashes, no
  regressions found post-v6-merge.
- ~~covert-data module-1 cross-reference for the sturdy technique~~ — **done**. Was stale —
  documented exact-LSB only, predated sturdy, and described JPEG-survival as a DCT-coefficient
  (Javid) technique where sturdy actually implements spatial luminance-QIM + Reed-Solomon FEC
  (same goal, different mechanism). `module_1_image_steganography/README.md` (external repo,
  `~/covert-data`, commit `bcec6a5`) now records the divergence precisely, following module-2's
  own v5 cross-reference pattern; `architecture.md`'s "Sturdy image technique (v6)" section
  carries the reciprocal citation back.

**Gate 41 is fully closed.** All five items done: doc reconciliation, full unit suite green,
safety re-audit of the incoming-intent surface (10 findings fixed), v1–v5 on-device regression
check, and the covert-data module-1 cross-reference.

## Test coverage state

64 JVM unit test files under `app/src/test` (no `androidTest` yet) — 63 as of the v6 merge, plus
`IncomingPipelineTest` from the 2026-09-22 gate-41 safety remediation. v6 added dedicated
coverage alongside the existing per-module tests: `SturdyImageCarrierTest`,
`SturdyImageCarrierBundledCoverTest`, `SturdyImageSteganalysisRealPhotoTest` for the new
carrier; `IncomingRouterSturdyTest` and `FileSnifferTest` for the receive pipeline;
`TrailTargetsTest`, `TrailConstellationTest`, `TrailStateStoreTest`, `TrailVoiceTest`,
`PracticeFireflyGeneratorTest`, `TrailSourceScanTest` and `MeadowTrailTest` for the riddle
trail; `SendVoiceTest` and `SendAdviceTest` for the send pipeline; `IncomingPipelineTest` for
the receive pipeline's own entry point (gate-41 safety remediation). Full-suite closing run —
the kind gate-41 calls for — done 2026-09-22: `./gradlew test` green, 0 failures across all 64
files.

## Deferred follow-ups

- **True phase-coding codec for audio stego** — the current phase-inversion technique is a
  simpler stereo-invert trick, not full phase coding; a proper phase-coding implementation
  is real, separate scope.
- **SLSB near-silent-frame skip (versioned embedding format)** — spectrogram-LSB's QIM
  currently embeds into near-silent cover frames too, producing the small measured click
  train the honesty caption discloses; skipping those frames would need a new, versioned
  embedding format (existing caught fireflies depend on the current one).

(The v1–v5 close-out's third deferred item — Canvas carrier-view a11y labels — shipped in v6
gate-38 and is no longer open.)

## State

Spec at `spec.md` (26 gates across v1–v5, plus gates 27–41 for the v6 addition; 27–40 shipped,
41 not yet run), architecture and protocol parameters at `architecture.md`, design decisions
at `design/identity.md` (technical screens), `design/firefly-jar-identity.md` (jar surface,
now including the meadow/art-jar v6 addendum), `design/riddle-trail.md` (new in v6: riddle
copy, capacity checks, trail design), and `design/screen-flow.md` (all screens, including the
receive/send flows added in v6).
