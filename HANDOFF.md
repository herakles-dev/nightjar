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

`feat/v6-sharing-trail` is 63 commits ahead of `main`, a clean fast-forward with no
conflicts — but it has not been merged. Do not merge until gate-41 below is run.

## Gate 41 — not yet run (required before v6 is done)

Gate 41 ("safety and close") is the closing audit for the whole v6 sprint and has **not**
been executed yet. It is the next required step, not optional cleanup:

- Safety-scope checklist re-run (INV-1 benign payloads, INV-10 no new network/storage
  permission), plus a fresh review of the new incoming-intent surface specifically for
  malformed and oversized files.
- Doc reconciliation: `architecture.md`, `design/screen-flow.md` and
  `design/firefly-jar-identity.md` against the code as actually shipped (several of the above
  bullets — MIME handling, button chrome, back-button fix — landed after the docs were last
  touched).
- Full unit suite run to green (not just the tests touched by each individual gate).
- v1–v5 on-device spot check on Hek, to catch any regression the sharing/trail work
  introduced in the older modules.
- covert-data module-1 cross-reference for the sturdy technique (module-2/§06 were already
  cross-referenced in the v5 close-out; module-1 is v6's addition).

Until this runs, v6 should be considered feature-complete but **not** fully closed out.

## Test coverage state

63 JVM unit test files under `app/src/test` (no `androidTest` yet). v6 added dedicated
coverage alongside the existing per-module tests: `SturdyImageCarrierTest`,
`SturdyImageCarrierBundledCoverTest`, `SturdyImageSteganalysisRealPhotoTest` for the new
carrier; `IncomingRouterSturdyTest` and `FileSnifferTest` for the receive pipeline;
`TrailTargetsTest`, `TrailConstellationTest`, `TrailStateStoreTest`, `TrailVoiceTest`,
`PracticeFireflyGeneratorTest`, `TrailSourceScanTest` and `MeadowTrailTest` for the riddle
trail; `SendVoiceTest` and `SendAdviceTest` for the send pipeline. These pass per-gate as each
landed, but a full-suite closing run — the kind gate-41 calls for — has not been done since
the post-gate-40 review/fix commits landed.

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
