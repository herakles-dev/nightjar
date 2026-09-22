# nightjar — Screen Flow
**Last updated:** 2026-08-03 (Task #28)

Scope: the module-picker home screen, its 3 stub destinations (all 3 now have real screens
built — see below), the Module 3 acoustic-modem screen shipped in Task #7, the Module 5
detector screen shipped in Task #10, the Module 1 image-steganography screen shipped in
Task #13, and the Module 2 audio-steganography screen (planned — v2 addition, see below).
See spec.md Boundaries and the task references below for what's still outstanding.

**Wiring correction (Task #17):** the sections below still describe each real screen as "not
yet wired into `MainActivity`," carried over from when tasks #7/#10/#13 were each written
standalone against their interfaces. By the time this task read the source, `MainActivity
.kt`'s `NightjarApp()` already routed all three `Screen` cases to their real screens (its own
KDoc credits "task #24" with that wiring) — the `when` branches described as future work below
are already live. Left the per-screen wiring notes in place as historical record of the
interface-first build discipline tasks #7/#10/#13 used; just flagging here that the "not yet
wired" framing itself is stale.

---

## Navigation model

No `androidx.navigation` dependency. A single `Screen` sealed interface held in
`remember { mutableStateOf(...) }` inside `NightjarApp()` in `MainActivity.kt` — matches
whisper-voice-app's MainActivity pattern (a nav-graph library would be overhead for a
tree this small). `BackHandler` returns to `Picker` from any stub; disabled on `Picker`
itself so the system back gesture there falls through to exit-app. **v2 addition:** a 4th
destination, `AUDIO_STEGANOGRAPHY`, joins the other three (5 screens total once wired).

**v3 addition (Firefly Jar, see Screens 6–7 below) changes the root of this diagram**,
not just its leaves: `Screen.JarShelf` becomes the new default/back-disabled root,
`Screen.Picker` (the entire diagram below, unchanged internally) becomes a child reached
only by a long-press on the shelf's wordmark, and a new `Screen.JarDetail(module)` sits
between the shelf and each creation-capable module's actual carrier logic.

```
                    ┌─────────────────┐
                    │  Screen.Picker   │  ← BackHandler disabled here
                    └────────┬─────────┘
                             │ tap a row
        ┌──────────────┬──────────────┬──────────────┐
        ▼              ▼              ▼              ▼
┌──────────────┐┌──────────────────┐┌──────────────┐┌──────────┐
│ACOUSTIC_MODEM ││IMAGE_STEGANOGRAPHY││AUDIO_STEGANOG-││ DETECTOR │
│               ││                   ││RAPHY          ││          │
└───────┬───────┘└─────────┬────────┘└───────┬───────┘└────┬─────┘
        │  tap "back" or system back / predictive-back gesture
        └───────────────────┴─────────────────┴────────────┘
                             ▼
                    ┌─────────────────┐
                    │  Screen.Picker   │
                    └─────────────────┘
```

**v3 addition — the full tree, root changed:**

```
                 ┌──────────────────────┐
                 │   Screen.JarShelf     │  ← NEW default root, BackHandler disabled here
                 │  (4 jar tiles)        │
                 └───────────┬────────────┘
              long-press wordmark │ tap a jar tile
        ┌───────────────────┴──────┐         ┌──────────────────────┐
        ▼                          ▼         ▼                      ▼
┌───────────────┐       ┌───────────────────────────────┐  ┌─────────────────┐
│ Screen.Picker  │       │ Screen.JarDetail(module)        │  │ (watching jar    │
│ (unchanged,    │       │ 1 of 3 creation-capable modules │  │  tile → same     │
│  diagram above)│       │ catch / look-for-fireflies /    │  │  JarDetail, no   │
│                │       │ firefly-detail popup             │  │  catch section)  │
└───────┬────────┘       └────────────────┬────────────────┘  └────────┬─────────┘
        │ "jar view" text tap             │ "back to the shelf"        │
        └──────────────┬───────────────────┴─────────────────┬─────────┘
                        ▼                                     ▼
                 ┌──────────────────────┐
                 │   Screen.JarShelf     │
                 └────────────────────────┘
```

---

## Screen 1: Module picker (home)

```
┌─────────────────────────────────┐
│                                   │
│  nightjar                        │  ← displayLarge, TextPrimary
│                                   │     24dp top / horizontal padding
│  acoustic modem                  │  ← bodyLarge, TextPrimary
│  send text as sound, phone to    │  ← labelSmall, TextSecondary (Task #28)
│  phone                           │     wrap-content row, 24dp horizontal /
│                                   │     10dp vertical padding, 2dp gap between
│  image steganography             │     the two lines
│  hide or extract text inside an  │
│  image                           │
│                                   │
│  audio steganography             │  ← v2 addition, planned — see Screen 5
│  hide text inside a music or     │
│  voice clip                      │
│                                   │
│  detector                        │
│  continuously listens for the    │
│  modem's signal                  │
│                                   │
└─────────────────────────────────┘
```

- Dense, scannable, no cards, no icons, no dividers — matches the whisper-voice-app
  Zeus-picker row precedent (`ZeusPicker.kt` `SessionRow`: "48dp, no dividers, no
  icons, no cards"). Each row does carry one small `ModuleGlyph` — a Canvas-drawn
  flat monochrome shape in `TextSecondary`, not a Material icon-pack glyph and not a
  literal pictogram (see `ModulePicker.kt` KDoc) — secondary to the label, which
  still carries all the actual information.
- **U-01 update (spec.md gate-12):** since Firefly Jar became the default launch screen
  (Screen 6), this screen is only reached via the jar shelf wordmark's long-press reveal —
  and until this update, the only way back out was the system/predictive back gesture, unlike
  every screen underneath it (each already has its own visible "back" link to here). A
  48dp-tall "back to the jar" link (`ModulePicker.kt`, same `labelLarge`/`TextSecondary`
  styling every technical screen's own "back" link uses) now sits above the wordmark,
  crossing back over the disguise boundary the same plain way stepping up one level within it
  already worked.
- Fixed 4-row list (v2 addition adds row 3). Order: Module 3 (acoustic modem — the
  primary gate-2 phone-to-phone demo) first, then Module 1 (image steganography),
  then Module 2 (audio steganography — grouped next to image stego since both are
  hide-in-cover-media carriers), then Module 5 (detector) last, since it's the
  analyzer rather than a carrier. Not user-reorderable; nothing to sort by
  frequency at this scope.
- Row labels are lowercase nouns ("acoustic modem", not "Acoustic Modem" or "Open
  Acoustic Modem") — matches the voice contract's settings-label convention.
- Whole row is the tap target (`Modifier.clickable` on the `Column`, not a separate
  button).
- **Task #28:** each row gained a one-line `description` under its label — real
  feedback said a first-time user had no idea what any of the three rows led to
  before tapping. Rows changed from a fixed 56dp-tall `Row` to a wrap-content
  `Column` (label + description, 2dp gap, 10dp vertical padding) to fit the second
  line; net row height lands close to the old 56dp by content alone, not a fixed
  value anymore. `Module.description` is the new field carrying the copy — see
  `ModulePicker.kt`.
- **v2 addition:** new `Module.AUDIO_STEGANOGRAPHY` case, label "audio
  steganography", description "hide text inside a music or voice clip" — same
  label/description shape Task #28 established. Needs a new `ModuleGlyph` case too;
  a starting concept is a waveform bar pattern (same family as the modem's glyph,
  since both are audio) with one bar carrying a small notch — schematic for "a
  second signal hidden inside a normal one" — but the actual shape is
  `android-designer`'s call, not locked here, same as `icon.md`'s own "placeholder
  pick pending user override" precedent for the launcher icon.

---

## Screens 2–4: Module stub

One composable, `ModuleStubScreen(module, onBack)`, parameterized by which module
was tapped. Identical layout for all three; only the title text differs.

```
┌─────────────────────────────────┐
│  back                            │  ← labelLarge, TextSecondary, 48dp tap target
│                                   │     top-left, tap or back-gesture → Picker
│                                   │
│                                   │
│         acoustic modem           │  ← displayLarge, TextPrimary, centered
│         not built yet            │  ← bodyLarge, TextSecondary, centered
│                                   │
│                                   │
│                                   │
└─────────────────────────────────┘
```

- "back" reads as a bare verb, lowercase, no "Tap to" prefix, no chevron/arrow
  glyph — text is the whole affordance.
- "not built yet" is the entire body copy. No "Coming soon!", no feature teaser, no
  progress indicator, no illustration. It's true and it's three words.
- Real content per module lands in later tasks:
  - **acoustic modem** → shipped Task #7, see below
  - **image steganography** → shipped Task #13, see below
  - **detector** → shipped Task #10, see below (architecture.md § Detector contract has
    the confidence-score rule)

`ModuleStubScreen` itself is unchanged and still exists — none of the three real screens
are wired into `MainActivity`'s `when` branch yet (each was built standalone against its
interfaces, per that task's brief; see each screen's own "wiring note" below). Until that
one-line swap happens per module, tapping any of the three picker rows still shows this
stub screen at runtime, even though the real screen exists in the tree and compiles.

---

## What Task #4 deliberately did not build

- No accent color, no per-module active-state visuals (recording pulse, encode
  progress, detector confidence meter) — those don't exist yet, and inventing
  placeholder motion for them now would be exactly the "polish ahead of the
  feature" this task was told to skip.
- No screen-transition animation between picker and stub — instant state swap.
- No app icon work — task #17 territory.

**Task #17 update:** the accent color now exists (`AccentSignal`, `#39C5CF`) and is
applied to the modem's `transmitting`/`listening` and the detector's/image-stego's
`flagged` states — see identity.md § Accent decision. Screen transitions stay instant
by final decision, not by deferral (see identity.md § Motion). The app icon is shipped
(see icon.md). The module picker itself still has no accent — a static 3-row list has
no active/flagged state to signal.

---

## Screen 2 (real): Acoustic modem (Module 3) — Task #7

Composable: `AcousticModemScreen(carrier: CovertCarrier<PcmAudio>, onBack: () -> Unit)`
in `app/src/main/java/dev/herakles/nightjar/modules/acoustic/AcousticModemScreen.kt`.
Pure/previewable content lives in the sibling `AcousticModemContent` composable (no
side effects, no audio, no permission logic — takes an explicit `ModemStatus`).

**Not yet wired into `MainActivity`'s `when` branch.** Tasks #5/#6 build the concrete
`AcousticCarrier : CovertCarrier<PcmAudio>`; this screen was built against the interface
only so it compiles standalone regardless of landing order (see the file's header KDoc).
Whoever lands last flips the `Screen.ModuleStub` case for `Module.ACOUSTIC_MODEM` in
`MainActivity.kt` to call `AcousticModemScreen(carrier = <concrete carrier>, onBack = ...)`
instead of `ModuleStubScreen`.

```
┌─────────────────────────────────┐
│  back                            │  ← labelLarge, TextSecondary, 48dp tap target
│                                   │
│  acoustic modem                  │  ← displayLarge, TextPrimary
│  works best within 1m, in a      │  ← labelSmall, TextSecondary (Task #28) — static,
│  quiet room.                     │     always visible, sourced from architecture.md
│                                   │     §7's AUDIBLE round-trip envelope, not guessed
│  payload                         │  ← labelLarge, TextSecondary (field label, noun)
│  ┌─────────────────────────────┐ │
│  │ text to send                │ │  ← BasicTextField, 1px BorderDefault box,
│  └─────────────────────────────┘ │     no M3 TextField chrome (no fill, no
│  18 / 1024 bytes                 │  ← labelSmall, TextSecondary     floating label, no focus-color ring)
│                                   │
│  transmit                        │  ← labelLarge, 48dp row, verb button
│  listen                          │  ← labelLarge, 48dp row, toggles to "stop"
│                                   │     while ModemStatus.Listening
│  [status word, or the received   │  ← see ModemStatus states below
│   text, or a decode-failure      │
│   message]                       │
└─────────────────────────────────┘
```

**Task #27 — the listening state itself, expanded** (real two-phone testing surfaced that the
bare "listening" word above left no way to tell whether the mic was capturing anything, how
long the window would run, or why it eventually failed):

```
│  listening                       │  ← labelLarge, AccentSignal (unchanged)
│  input level -38 dB              │  ← labelSmall, TextSecondary — live RMS dBFS readout,
│                                   │     updates ~12x/sec (same 85ms throttle the detector's
│                                   │     confidence readout already uses). "input level
│                                   │     appears once capture starts" for the ~85ms before
│                                   │     the first AudioRecord chunk lands.
│  12s left in listen window       │  ← labelSmall, TextSecondary — counts down from
│                                   │     MAX_LISTEN_SECONDS (20s), plain text ticking,
│                                   │     no progress bar/ring
```

Both are numeric text rows under the existing status word — no meter, no gauge, no waveform,
no animated bar, matching the detector screen's `ReadoutBlock` precedent (confidence percentage
+ threshold as plain labelSmall/TextSecondary rows) and identity.md's Motion decision (state
ticks as instant text swaps, same restraint as the detector's live confidence percentage).

**State machine** — `ModemStatus` sealed interface, 7 cases, one-to-one with the task
brief: `Idle | Encoding | Transmitting | Listening | Decoding | DecodedSuccess(text,
correctedByteErrors) | DecodedFailure(reason: DecodeFailure, detail)`.

- `transmit` is enabled only when idle-equivalent (`Idle` / `DecodedSuccess` /
  `DecodedFailure`) and the payload is non-empty and within `carrier.maxPayloadBytes`.
  Tapping it: encode → `AudioTrack` playback (`MODE_STATIC`, PCM16 mono at
  `NightjarAcoustics.SAMPLE_RATE_HZ`) → back to idle.
- `listen`/`stop` toggles a bounded `AudioRecord` capture window (20s max — see the
  `MAX_LISTEN_SECONDS` comment in the source for the architecture-grounded math), then
  runs `carrier.decode()` once over the captured buffer and lands on
  `DecodedSuccess`/`DecodedFailure`. RECORD_AUDIO is requested at runtime via
  `ActivityResultContracts.RequestPermission()`; a denial shows a plain inline note
  ("microphone permission needed to listen.") rather than blocking the rest of the screen.
- `DecodeFailure` → copy mapping (voice contract: what's wrong + what to do, two
  sentences max, no exclamation, no red/danger color — a decode failure isn't
  destructive):
  - `NO_PAYLOAD_FOUND` → "no signal found. move the phones closer and try again." **(Task #27:
    only when the user stopped the window early.)** If the full 20s window ran out with
    nothing decoded — `DecodedFailure.timedOut = true` — the message names the elapsed
    duration and the concrete things to check instead: "no signal detected in 20s. move
    phones closer and confirm the other phone actually transmitted." `timedOut` is derived
    in `AcousticModemController.capturePcm()` from whether the capture loop exited by
    filling its sample buffer (timed out) vs. `listening.get()` going false first (user
    tapped "stop").
  - `HEADER_INVALID` → "header didn't check out. try again."
  - `PAYLOAD_TOO_LARGE` → "declared payload is too large. frame rejected."
  - `UNRECOVERABLE_FEC` → "too much signal loss to correct. move closer or cut ambient noise."
  - `INTEGRITY_MISMATCH` → "checksum didn't match. the payload arrived corrupted."
- `DecodedSuccess` shows the recovered text directly (that *is* the feedback — no
  "Got it!"/checkmark on top of it) plus a quiet "N bytes corrected" note when FEC
  actually corrected something (`correctedByteErrors > 0`) — the one deliberate rough
  edge on this screen: real FEC arithmetic surfaced, not hidden.

**What Task #7 deliberately did not build:** no accent color anywhere on this screen
(none was defined project-wide at the time — see identity.md changelog); no motion
beyond default recomposition (state swaps are instant, same as Task #4's picker↔stub
precedent); no Module 5 detector cross-talk on this screen (the detector is a separate
module/task, even though it listens on the same band this modem transmits on).

**Task #17 update:** `transmitting`/`listening` (the two states where the speaker/mic is
physically live) now render in `AccentSignal` (`#39C5CF`) instead of `TextSecondary` —
`encoding`/`decoding` stay `TextSecondary`. Motion stays instant by final decision, not
deferral (see identity.md § Motion). See identity.md § Accent decision for full
rationale.

**Task #27 update:** `ModemStatus.Listening` now carries `levelDb: Double?` and
`remainingSeconds: Double`, rendered as two labelSmall/TextSecondary text rows under the
"listening" word (see the expanded wireframe above) — a real two-phone test found the bare
status word gave no feedback that the mic was capturing anything, how long the window would
run, or why it failed. `ModemStatus.DecodedFailure` gained a `timedOut: Boolean` field, used
only to pick between two `NO_PAYLOAD_FOUND` messages (see the `DecodeFailure` copy mapping
above). No accent, motion, or palette change — both additions are plain numeric text, same
restraint as the detector's live confidence percentage.

**Task #28 update:** the title block now carries a static one-line note, "works best within
1m, in a quiet room." (labelSmall/TextSecondary, 4dp under the title) — real feedback said
the app gave no idea what distance to use. The number is architecture.md §7's AUDIBLE-protocol
round-trip envelope (speaker→mic ≤1.0m, ambient noise <45 dBA), not an invented figure.
Deliberately separate from `ListeningBlock` (Task #27) — it's setup guidance true before,
during, and after any listen window, not a live reading, so it doesn't duplicate or clutter
the level/countdown rows.

**Tasks #31/#32/#36 (drift note, docs close-out):** the wireframe above predates three later
additions the wireframe never caught up to. A `ChannelSettingsBlock` (protocol/symbol-rate
selectors, same tap-to-switch row shape as Screen 4/5's cover selectors) now sits between the
payload field and the action rows. The action list grew from `transmit`/`listen` to five rows
— `transmit`, `save`, `share`, `import`, `listen`/`stop` — grouped into three clusters by
spacing alone (12dp between, 0dp within, no dividers/cards): transmit alone; save+share (both
write `encode()`'s output to a WAV file); import+listen (both feed a captured signal to
`decode()`, live mic capture vs. an existing file). `fileActionBusyLabel` gives save/share the
same plain status-word feedback (`saving`/`sharing`) transmit/import already had. See
`AcousticModemScreen.kt`'s `AcousticModemContent` KDoc for the full task-by-task rationale.

---

## Screen 3 (real): Detector (Module 5) — Task #10

Composable: `DetectorScreen(detector: CovertDetector<PcmAudio>, onBack: () -> Unit)` in
`app/src/main/java/dev/herakles/nightjar/modules/detector/DetectorScreen.kt`. Pure/
previewable content lives in the sibling `DetectorContent` composable (no side effects,
no audio, no permission logic — takes an explicit `DetectionResult?` + history list),
same split task #7 used for `AcousticModemContent`.

**Not yet wired into `MainActivity`'s `when` branch.** Task #9 already shipped the
concrete `AcousticDetector : CovertDetector<PcmAudio>`, but this screen was still built
against the interface only (per the task brief) so wiring stays a one-line change:
`Module.DETECTOR` currently routes to `ModuleStubScreen`; swap that one case to
`DetectorScreen(detector = AcousticDetector(), onBack = ...)`.

This is the defensive counterpart to the acoustic modem, not an offensive tool
(covert-data/CLAUDE.md §1) — it passively scores the mic stream for Module 3's own tone
signature and never decodes a payload (spec INV-4).

```
┌─────────────────────────────────┐
│  back                            │  ← labelLarge, TextSecondary, 48dp tap target
│                                   │
│  detector                        │  ← displayLarge, TextPrimary
│  confidence tracks match against │  ← labelSmall, TextSecondary (Task #28) — static,
│  the modem's own signal. runs    │     always visible, explains what the number
│  continuously while listening,   │     means and that it's continuous/passive
│  never decodes anything.         │
│                                   │
│  listen                          │  ← labelLarge, 48dp row, verb button; toggles
│                                   │     to "stop" while listening
│  confidence                      │  ← labelLarge, TextSecondary (field label)
│  63%                             │  ← displayLarge, TextPrimary — the live readout
│  flagged                         │  ← labelLarge, AccentSignal if flagged else
│                                   │     TextSecondary + "clear" (Task #17) — text
│                                   │     weight/color only, no glow, no pulse
│  flags at 20%                    │  ← labelSmall, TextSecondary — the detector's own
│                                   │     tunable flagThreshold, shown verbatim
│  [detail note, when present]     │  ← labelSmall, TextSecondary — the rough edge,
│                                   │     see below
│  history                         │  ← labelLarge, TextSecondary section header
│  14:22:03            63%         │  ← labelSmall rows, timestamp in monospace,
│  14:21:51            41%         │     recent-first, scrolls with the rest of
│  ...                             │     the column
└─────────────────────────────────┘
```

- `listen`/`stop` toggles a continuous `AudioRecord` capture loop — unlike the modem's
  bounded 20s "listen" window (one-shot `decode()` over a complete buffer), the detector
  is stateful and streaming by design (architecture.md §9, `CovertDetector` KDoc), so
  there's no fixed capture ceiling; the user starts and stops it explicitly.
  RECORD_AUDIO is requested at runtime via the same `ActivityResultContracts
  .RequestPermission()` flow the modem screen uses; a denial shows the same inline note
  ("microphone permission needed to listen.").
- **Live readout:** `confidence` updates continuously while listening (throttled to
  ~85ms per `AudioRecord.read()` chunk so Compose isn't asked to recompose every ~21ms
  analysis frame — see `DetectorController` KDoc). Before the first result arrives, the
  block shows a quiet placeholder ("confidence appears once you start listening")
  instead of a misleading 0%.
- **Flagged/clear state:** `AccentSignal` (`#39C5CF`) + "flagged" vs `TextSecondary` +
  "clear" (Task #17 — this screen's whole reason to exist is spec INV-4's self-detection
  proof, so "flagged" is the one state in the app that most earns the reserved accent).
  Still no shimmer/glow/pulse on the primary readout, and the confidence percentage
  itself stays `TextPrimary` regardless of flagged state (anti-AI-tell checklist).
- **The one deliberate rough edge:** `DetectionResult.detail` (when non-null) is shown
  verbatim under the readout — real analyzer output ("sustained tone-grid energy: N
  on-grid bin(s) >= 15.0 dB over floor"), not smoothed into a generic status word. Same
  move task #7 made surfacing the modem's "N bytes corrected" FEC count.
- **History:** a row is appended only on a flagged rising edge (clear → flagged), not on
  every analysis frame — "a simple scrolling history of past detections" reads as a log
  of discrete detection events, not a running trace of a ~12Hz analysis loop. Recent-
  first, capped at 50 rows. Timestamp (`HH:mm:ss`) is the one deliberate monospace use on
  this screen — the doctrine's "mono only where it carries information" rule, applied to
  the one field on this screen that's actually tabular/informational.

**What Task #10 deliberately did not build:** no accent color (none was defined
project-wide at the time); no motion beyond default recomposition, same restraint task #7
applied (state swaps and the live percentage update are instant text changes, no
`Crossfade`, no animated meter/gauge — a numeric meter with tick animation was considered
and skipped for the same reason task #7 skipped `Crossfade`: unverified
`androidx.compose.animation` availability wasn't worth risking under this task's
2-attempt build cap); no wiring into `MainActivity` (left as the one-line change noted
above, matching Task #7's precedent for the modem — since superseded, see the wiring
correction at the top of this file).

**Task #17 update:** `AccentSignal` now applies to the `flagged` word (see above). Motion
stays instant by final decision, not deferral (see identity.md § Motion).

**Task #28 update:** the title block now carries a static one-line note explaining what
`confidence` is (a live match score against the modem's own tone signature) and that it
runs continuously/passively (never decodes a payload) — real feedback said the app gave no
idea what the percentage implied. Static, not folded into `ReadoutBlock` — it's true whether
or not a listen session is active, unlike the live percentage itself.

---

## Screen 4 (real): Image steganography (Module 1) — Task #13

Composable: `ImageStegoScreen(carrierFactory: (Bitmap) -> CovertCarrier<Bitmap>, detector:
CovertDetector<Bitmap>, onBack: () -> Unit)` in
`app/src/main/java/dev/herakles/nightjar/modules/imagestego/ImageStegoScreen.kt`. Pure/
previewable content lives in the sibling `ImageStegoContent` composable (no side effects, no
bitmap decoding, no `Context` — takes an explicit `StegoStatus` + working `Bitmap`), the same
stateful-root/pure-content split tasks #7 and #10 established.

**Not yet wired into `MainActivity`'s `when` branch**, same as the modem/detector screens.
Both `ImageStegoCarrier` (task #11) and `ImageSteganalysis` (task #12) already exist in the
tree by this task, but the screen still takes them only as interface-typed parameters — no
`ImageStegoCarrier`/`ImageSteganalysis` reference anywhere in the file. `Module.IMAGE_STEGANOGRAPHY`
currently routes to `ModuleStubScreen`; swap that one case to call `ImageStegoScreen(carrierFactory
= { cover -> ImageStegoCarrier(cover) }, detector = ImageSteganalysis(), onBack = ...)`.

One interface adaptation this screen needed that the modem/detector screens didn't:
`CovertCarrier<Bitmap>` is bound to a specific cover image at construction
(`ImageStegoCarrier(coverImage: Bitmap)`), unlike the modem's stateless
`CovertCarrier<PcmAudio>`. Since the operator can switch between two bundled cover images,
the screen takes a factory function `(Bitmap) -> CovertCarrier<Bitmap>` instead of a single
fixed instance — still zero references to the concrete class, just rebuilding an
interface-typed instance per selected cover.

```
┌─────────────────────────────────┐
│  back                            │  ← labelLarge, TextSecondary, 48dp tap target
│                                   │
│  image steganography             │  ← displayLarge, TextPrimary
│                                   │
│  cover image                     │  ← labelLarge, TextSecondary (section label)
│  gradient                        │  ← labelLarge, 40dp row; TextPrimary if selected
│  mosaic                          │     else TextSecondary; tap to switch
│  [96x96 preview of the working   │  ← real device pixels, 1px BorderDefault box —
│   image, cover or stego]         │     LSB stego is visually identical to the eye,
│                                   │     which the preview itself demonstrates
│  payload                         │  ← labelLarge, TextSecondary (field label, noun)
│  ┌─────────────────────────────┐ │
│  │ text to hide                │ │  ← BasicTextField, 1px BorderDefault box, no M3
│  └─────────────────────────────┘ │     TextField chrome — same as the modem's field
│  18 / 3739 bytes                 │  ← labelSmall, TextSecondary — capacity is
│                                   │     per-cover (100x100 gradient/mosaic: 3739 B)
│  embed hides text in the image,  │  ← labelSmall, TextSecondary (Task #28) — one
│  extract reads it back, check    │     caption above the action group, 8dp bottom
│  scans for hidden data without   │     padding, explains what the 3 verbs below do
│  extracting it                   │
│  embed                           │  ← labelLarge, 48dp row, verb button
│  extract                         │  ← labelLarge, 48dp row, verb button
│  check for hidden data           │  ← labelLarge, 48dp row, verb button
│  [status word, recovered text,   │  ← see StegoStatus states below
│   extract-failure message, or    │
│   the confidence/flagged/detail  │
│   readout]                       │
└─────────────────────────────────┘
```

**State machine** — `StegoStatus` sealed interface, 8 cases: `Idle | Embedding | Extracting |
Analyzing | Embedded(payloadBytes) | ExtractedSuccess(text) |
ExtractedFailure(reason: DecodeFailure, detail) | Analyzed(result: DetectionResult)`. Three
independent one-shot actions (embed / extract / check for hidden data) share this one status
block, the same "single status area covers every action" shape the modem screen used for its
transmit/listen pair.

- `embed` is enabled only when idle-equivalent (`Idle` / `Embedded` / `ExtractedSuccess` /
  `ExtractedFailure` / `Analyzed`) and the payload is non-empty and within the selected
  cover's `maxPayloadBytes`. Tapping it always encodes into the pristine sample cover (never
  into an already-embedded working image), so repeated taps stay predictable instead of
  stacking frames. On success, the produced stego `Bitmap` becomes the new working image the
  preview, `extract`, and `check for hidden data` all operate on.
- `extract` and `check for hidden data` are enabled whenever idle-equivalent and always run
  against the current working image (the pristine cover before any `embed`, or the stego
  image after one) — so a user can demonstrably run either action on a clean image (extract
  fails with `NO_PAYLOAD_FOUND`; the detector reads low/clear) as well as on an embedded one.
- Switching the `cover image` selection resets the working image back to that cover's raw
  pixels and clears any in-progress status — a per-cover workflow needs this even though the
  modem/detector screens (a single fixed carrier for the whole screen's lifetime) never had
  to reset anything on a selection change.
- `ExtractedFailure` → copy mapping, same voice-contract shape as the modem's
  (what's wrong + what to do, two sentences max, no exclamation, no danger color — a failed
  extract is informational, not destructive), phrased for the image carrier rather than
  reusing the modem's acoustic-specific copy:
  - `NO_PAYLOAD_FOUND` → "no hidden payload found in this image."
  - `HEADER_INVALID` → "header didn't check out. this image may not hold a nightjar payload."
  - `PAYLOAD_TOO_LARGE` → "declared payload is too large for this image. frame rejected."
  - `UNRECOVERABLE_FEC` → "too much data loss to recover." (unreachable in practice — the
    image codec applies no FEC per `ImageStegoCarrier`'s KDoc — kept only because
    `DecodeFailure` is a shared enum across carriers and the `when` must stay exhaustive)
  - `INTEGRITY_MISMATCH` → "checksum didn't match. the payload was altered or corrupted."
- `ExtractedSuccess` shows the recovered text directly (that *is* the feedback, no
  "Got it!"/checkmark on top of it), same move task #7 made for the modem's decoded text.
- `Analyzed` renders the same readout shape `DetectorScreen`'s `ReadoutBlock` uses: confidence
  percentage in `displayLarge` (always `TextPrimary`), `flagged`/`clear` in `AccentSignal`/
  `TextSecondary` (Task #17, same treatment as the detector screen — no glow, no pulse, just
  the one reserved accent on the one word that matters), and the detector's own
  `detail`/`estimatedPayloadBytes` surfaced verbatim when present — real chi-square window/run
  output (e.g. "sustained PoV-equalization run: 5/32 windows..."), not smoothed into a generic
  status word. That verbatim detail line is the one deliberate rough edge on this screen, the
  same move task #7/#10 made surfacing the modem's FEC count and the detector's tone-grid note.
- Status words (`embedding`, `extracting`, `analyzing`) follow the same bare-present-participle,
  no-ellipsis, no-exclamation convention identity.md's changelog already settled for the
  modem's `encoding`/`transmitting`/`listening`/`decoding` — a terse system label, not an
  AI-chatbot loading indicator.

**What Task #13 deliberately did not build:** gallery picking (the task brief's own escape
hatch — `ActivityResultContracts.GetContent()` + `ContentResolver` stream handling +
persisted-permission bookkeeping is real complexity for no demo value when two representative
covers are already bundled); no accent color (none was defined project-wide at the time); no
motion beyond default recomposition (same restraint tasks #7/#10 applied — state swaps are
instant text changes, no `Crossfade`); no wiring into `MainActivity` (left as the one-line
change noted above, matching tasks #7/#10's precedent — since superseded, see the wiring
correction at the top of this file).

**Task #17 update:** `AccentSignal` now applies to the `flagged` word in the `check for hidden
data` readout (see above). Motion stays instant by final decision, not deferral (see
identity.md § Motion). Gallery picking is still out of scope.

**Task #28 update:** a static one-line caption now sits above the embed/extract/check action
group explaining what each of the three verbs does — real feedback said a first-time user had
no way to guess what the three buttons meant before tapping one. One shared caption above all
three rather than a line per row, since the three actions operate on the same working image
and read as one group.

**Owner-requested update (UX-IMAGES):** the 96×96 working-image preview now carries a subtle
tap affordance (a quiet corner glyph, not a button) — tapping it opens `FullscreenImageViewer`
(`app/src/main/java/dev/herakles/nightjar/ui/FullscreenImageViewer.kt`), a reusable modal
shared with the jar surface's own image carrier viewer (Screen 7). Pinch-to-zoom, pan
(bounds-constrained), double-tap to toggle fit/zoomed, and three dismiss paths (system back, a
tap on the image or scrim, or a small close glyph). Palette-neutral (near-black scrim) rather
than bound to either identity.md's or firefly-jar-identity.md's tokens, since a modal photo
viewer reads the same regardless of which surface opened it.

---

## Screen 5 (planned): Audio steganography (Module 2)

**v2 addition — spec only, not yet built.** Composable (proposed):
`AudioStegoScreen(carrierFactory: (cover: PcmAudio, technique: AudioStegoTechnique) ->
CovertCarrier<PcmAudio>, onBack: () -> Unit)` in
`app/src/main/java/dev/herakles/nightjar/modules/audiostego/AudioStegoScreen.kt`, following
the same stateful-root/pure-content split (`AudioStegoContent`) tasks #7/#10/#13 established.
Architecture rationale (why this reuses `CovertCarrier<PcmAudio>` with zero interface changes)
is in `architecture.md` § Module Interface §7.

Closest existing precedent is **Screen 4 (image steganography)**, not the acoustic modem:
this module hides a payload inside a *pre-existing* cover, rather than synthesizing carrier
audio fresh for live transmission. Where it differs from Screen 4: two selectors instead of
one (technique **and** cover clip, since Module 2 covers three distinct techniques rather than
one fixed algorithm with two sample images), a playback pair instead of a bitmap preview (audio
has no visual "look the same" proof — the equivalent claim is "sounds the same," so it must be
demonstrated by ear), and only two of the three action verbs (no `check for hidden data` — see
below).

```
┌─────────────────────────────────┐
│  back                            │  ← labelLarge, TextSecondary, 48dp tap target
│                                   │
│  audio steganography             │  ← displayLarge, TextPrimary
│  hide text inside a clip.        │  ← labelSmall, TextSecondary — static caption,
│  playback proves it sounds       │     same "static, always-visible, real-number-  
│  unchanged.                      │     over-vague-copy" convention as the other
│                                   │     three screens' Task #28 captions
│  technique                       │  ← labelLarge, TextSecondary (section label)
│  phase inversion                 │  ← labelLarge, 40dp row; TextPrimary if selected
│  spectrogram lsb                 │     else TextSecondary; tap to switch — same
│  mfsk (robust)                   │     shape as Screen 4's cover-image selector
│                                   │
│  cover clip                      │  ← labelLarge, TextSecondary (section label)
│  spoken word                     │  ← labelLarge, 40dp row; TextPrimary if selected
│  soft synth                      │     else TextSecondary; tap to switch
│                                   │
│  play cover        play working  │  ← labelLarge, 48dp verb rows — an A/B listening
│                                   │     test; no waveform, no meter, no scrubber
│                                   │     (same restraint as the modem/detector
│                                   │     screens' "no meter/gauge" precedent)
│  payload                         │  ← labelLarge, TextSecondary (field label, noun)
│  ┌─────────────────────────────┐ │
│  │ text to hide                │ │  ← BasicTextField, 1px BorderDefault box, no M3
│  └─────────────────────────────┘ │     TextField chrome — same as Screens 2 and 4
│  18 / 96 bytes                   │  ← labelSmall, TextSecondary — capacity is
│                                   │     per-technique AND per-cover, recomputed live
│                                   │     on either selector changing (extends Screen
│                                   │     4's per-cover-only capacity recompute)
│  embed hides text in the clip,   │  ← labelSmall, TextSecondary — caption above
│  extract reads it back           │     the action group, same shape as Screen 4's
│                                   │     Task #28 caption
│  embed                           │  ← labelLarge, 48dp row, verb button
│  extract                         │  ← labelLarge, 48dp row, verb button
│  [status word, recovered text,   │  ← see AudioStegoStatus states below
│   or an extract-failure message] │
└─────────────────────────────────┘
```

**Scoping decision, stated explicitly (matching this project's "what deliberately wasn't
built" discipline):** ships **embed + extract only**, not Screen 4's third `check for hidden
data` verb. That verb needs a real `AudioStegDetector : CovertDetector<PcmAudio>`
(phase-correlation for the phase-inversion technique, cepstral/spectral-anomaly for
spectrogram-LSB — `covert-data/library/06_detection_and_countermeasures.md`) that does not
exist yet. The existing `AcousticDetector` is scoped to Module 3's live tone-grid signature
specifically and does not generalize here — it is not reused. Building a real detector
alongside this addition is separate, substantial scope; shipping a stubbed/fake `check`
action would violate the project's own "no smoothed status word over real analyzer output"
rule (Screens 3/4's `detail` precedent) more than simply not having the verb yet.

**State machine** — `AudioStegoStatus` sealed interface, 6 cases, same shape as Screen 4's
`StegoStatus` minus the analyze-related cases: `Idle | Embedding | Extracting |
Embedded(payloadBytes) | ExtractedSuccess(text) | ExtractedFailure(reason: DecodeFailure,
detail)`.

- `embed` enabled only when idle-equivalent and the payload is non-empty and within the
  selected technique+cover's `maxPayloadBytes`. Always encodes into the pristine selected
  cover clip (never a previously-embedded working clip), same predictability rule Screen 4
  uses for repeated taps.
- `extract` enabled whenever idle-equivalent; always runs against the current working clip
  (pristine cover before any `embed`, or the stego clip after one).
- Switching **either** selector (technique or cover) resets the working clip to the selected
  cover's raw samples and clears in-progress status — extends Screen 4's single-selector
  reset rule to two independent selectors.
- `play cover` / `play working` are available in any idle-equivalent state; playing does not
  change `AudioStegoStatus` (listening is not itself an action with a result, same reasoning
  the modem/detector screens use for why "listening"/capture states are distinct from
  playback, which has none).
- `ExtractedFailure` → copy mapping, same voice-contract shape as Screens 2/4 (what's wrong +
  what to do, two sentences max, no exclamation, no danger color):
  - `NO_PAYLOAD_FOUND` → "no hidden payload found in this clip."
  - `HEADER_INVALID` → "header didn't check out. this clip may not hold a nightjar payload."
  - `PAYLOAD_TOO_LARGE` → "declared payload is too large for this clip. frame rejected."
  - `UNRECOVERABLE_FEC` → "too much signal loss to recover." (reachability depends on whether
    the MFSK implementation ships real FEC — an open call, see architecture.md §7; kept in
    the mapping regardless since `DecodeFailure` is a shared enum across every carrier and the
    `when` must stay exhaustive, same reasoning Screen 4 documents for its own unreachable case)
  - `INTEGRITY_MISMATCH` → "checksum didn't match. the payload was altered or corrupted."
- `ExtractedSuccess` shows the recovered text directly, no "Got it!"/checkmark — same move
  every other screen makes.
- Status words (`embedding`, `extracting`) follow the ratified bare-present-participle,
  no-ellipsis, no-exclamation convention (identity.md § Anti-AI-tell status).

**Real technical risk, flagged here rather than left to be discovered in field-testing (see
architecture.md §7 for the full note):** bundled cover clips must be pre-converted to
48kHz mono 16-bit PCM before bundling — `PcmAudio`'s sample-rate assumption is implicit in the
type, not enforced, and a mismatched cover would silently corrupt every technique's math.

**What this addition should deliberately not build, matching every prior screen's own
discipline:** no `check for hidden data` verb (see scoping decision above); no gallery picker
for cover clips (same escape hatch Screen 4 took for cover images — bundled samples only); no
accent color anywhere on this screen (this screen has no flagged/live-hardware state — accent
is reserved for the modem's transmitting/listening and the flagged word on Screens 3/4, neither
of which applies here); no motion beyond default recomposition (same restraint every other
screen settled at Task #17); no waveform/scrubber/level-meter on the playback rows (matches
the "no meter/gauge" restraint every other screen already established).

**v5 addition — built (design-v5.md §2, gate-22/23): the scoping decision above no longer
holds.** `AudioStegDetector : CovertDetector<WavFile.ParsedWav>` now exists (blind, stego-only,
targeted at this app's own three techniques — channel-polarity anti-correlation,
spectrogram-LSB's QIM lattice snapping, and MFSK's keyed 19.7–20.0 kHz tones; INV-7: it never
constructs a `CovertCarrier`, calls `decode`, or reads nightjar's frame header). A third action
row, `check for hidden data`, is wired on `MainActivity`'s injected detector — same three-verb
shape Screen 4 already established, same `Analyzed`-style readout (confidence percentage,
`flagged`/`clear`, the leading technique named in the detail line), plus a static honesty line
about the detector's own documented blind spots (`AudioStegoScreen.kt`'s
`DETECTOR_CAVEAT`/`JAR_PEEK_CAVEAT`). A check writes no `FireflyRecord`. The humming jar
(Screen 7) gets the same capability re-skinned as "peek inside," with a shorter version of the
same honesty line.

---

## Screen 6 (planned): Jar shelf (home, v3 addition — Firefly Jar)

**v3 addition — spec only, not yet built.** Composable (proposed): `JarShelfScreen(...)`
in `app/src/main/java/dev/herakles/nightjar/modules/fireflyjar/JarShelfScreen.kt`,
`JarShelfContent` pure/previewable sibling, same stateful-root/pure-content split every
other screen uses. Full data model and dependency plan: `architecture.md` § Firefly Jar.
Visual language (palette, glow, motion, copy voice) is **not** identity.md's — see
`design/firefly-jar-identity.md`, the sibling doc that owns this surface's doctrine.

Replaces `Screen.Picker` as the app's default launch destination (Navigation model,
above). Tiles are **derived from `Module.entries`, not hardcoded** (architecture.md § 6)
— today that's four tiles, one per module, but adding a fifth module never touches this
screen. Each tile is a jar silhouette holding firefly dots sized/colored from
`fireflyDao.observeByModule(module.name)`, or (for `JarRole.WATCHING` modules) the
dimmer no-dots treatment:

```
┌─────────────────────────────────┐
│                                   │
│   firefly jar                    │  ← wordmark, warm glow treatment (see
│   long-press to open the         │     firefly-jar-identity.md); labelSmall
│   workshop                       │     caption beneath, dim, always visible
│                                   │
│   [jar]  the singing jar         │  ← acoustic modem. Jar silhouette with
│          •• •                    │     firefly dots inside: amber = CREATED
│                                   │     (transmitted), cyan = RECEIVED (decoded)
│   [jar]  the framed jar          │  ← image steganography
│          •                       │
│                                   │
│   [jar]  the humming jar         │  ← audio steganography
│          •••                     │
│                                   │
│   [jar]  the watching jar        │  ← detector — dimmer treatment, no firefly
│          ·                       │     dots (re-skinned flagged-history count
│                                   │     instead, see Screen 7 below)
└─────────────────────────────────┘
```

- Tile order matches the existing module-picker's established order (spec.md/screen-flow
  precedent: modem, image, audio, detector) — no re-sorting, same "nothing to sort by
  frequency at this scope" reasoning Screen 1 already documents.
- Each jar's dot count/coloring is a live `Flow` read from `FireflyDao.observeAll()`
  (architecture.md §1) — recomputed on every collect, no manual refresh action needed.
- Tapping a jar tile → `Screen.JarDetail(module)`. The watching jar tile routes to the
  same `JarDetail` composable, which branches internally on whether the module is
  detector-shaped (architecture.md §3) to omit the catch section.
- **Long-press reveal:** long-pressing the "firefly jar" wordmark (not any jar tile —
  the wordmark specifically, so an accidental long-press on a jar tile while scrolling
  doesn't surprise-reveal technical mode) transitions to `Screen.Picker`
  (architecture.md §5). Foundation's `combinedClickable(onLongClick = ...)`, platform-
  default hold duration — no custom timer.
- Thematic jar names ("the singing jar" / "the framed jar" / "the humming jar" / "the
  watching jar") are the primary label on this surface; the technical module names stay
  reachable only inside `Screen.Picker`, matching the "full disguise" depth the user
  chose over a lighter re-skin-only option.

---

## Screen 7 (planned): Jar detail (per-module, v3 addition — Firefly Jar)

**v3 addition — spec only, not yet built.** Composable (proposed):
`JarDetailScreen(module: Module, onBack: () -> Unit)` in
`app/src/main/java/dev/herakles/nightjar/modules/fireflyjar/JarDetailScreen.kt`,
`JarDetailContent` pure/previewable sibling. One composable serves every module in the
registry, but **does not itself branch on which module it is** (architecture.md § 6) —
the shared shell (wordmark, firefly swarm or watching-jar readout, action rows) is
identical for all of them; the module-specific bits (jar name, glyph, and the catch
flow's selector fields) come from `module.jarName`/`module.jarRole` and a lookup into
`FireflyGlyphs.kt`/`JarCatchFlows.kt` — the only two places that branch per module, both
compiler-exhaustive `when`s in their own file (architecture.md § 6). This is a stronger
guarantee than `ModuleStubScreen`'s old "one composable, parameterized" shape, which
still branched on `module` internally for its title text — this screen doesn't branch at
all; it hosts what the registry hands it.

**Functionally, this is a themed skin over the exact same state machines and carrier
calls Screens 2, 4, and 5 already define** (`ModemStatus` / `StegoStatus` /
`AudioStegoStatus`, unchanged) — this screen does not reimplement embed/extract/transmit/
listen logic, it re-presents it. Softened copy per action, not new behavior:

```
┌─────────────────────────────────┐
│  ← back to the shelf             │
│                                   │
│  the singing jar                 │  ← module's thematic name (wordmark)
│  every firefly you've caught or  │  ← static caption, cute-but-accurate,
│  spotted here, sent through sound │     same "static, always-visible" convention
│                                   │     Task #28 established for the technical
│  ••   •    •••   •               │  ← scattered firefly dots, tap one for detail
│                                   │     (amber = caught/CREATED, cyan =
│                                   │     spotted/RECEIVED)
│  catch a firefly                 │  ← expands the technique/cover selectors +
│                                   │     payload field inline (same fields Screen
│                                   │     2/4/5 already define for this module),
│                                   │     confirm = the module's real embed/
│                                   │     transmit call
│  look for fireflies              │  ← the module's real extract/listen call,
│                                   │     no extra fields needed
│                                   │
│  [status/result — same voice     │  ← e.g. "you caught one — 42 bytes" instead
│   contract as the technical       │     of "Embedded", "nothing out there right
│   screens: what happened, plain   │     now" instead of "no hidden payload
│   language, no exclamation]       │     found" — see copy note below
└─────────────────────────────────┘
```

Tapping a firefly dot opens a detail popup:

```
┌─────────────────────────────────┐
│  ← back to the jar               │
│                                   │
│  a firefly you caught            │  ← or "a firefly you spotted" (RECEIVED)
│  14:22, sent through sound       │  ← timestamp + module's thematic channel
│                                   │     name, from FireflyRecord.timestampMillis
│  "the message text preview..."   │  ← FireflyRecord.payloadPreview, verbatim —
│                                   │     same "real data, not smoothed" rough-
│                                   │     edge discipline the technical screens
│                                   │     already apply to FEC counts / analyzer
│                                   │     detail strings
│  42 bytes                        │  ← FireflyRecord.payloadSizeBytes
└─────────────────────────────────┘
```

**The watching jar (detector) branch** omits the "catch a firefly" section entirely (the
detector never creates anything — architecture.md §3) and re-skins `DetectorContent`'s
existing live confidence readout + flagged-history list instead of showing firefly dots:

```
┌─────────────────────────────────┐
│  ← back to the shelf             │
│                                   │
│  the watching jar                │
│  hold it up and see if anything  │  ← static caption
│  glows nearby                    │
│                                   │
│  watch                           │  ← = the detector's existing `listen` verb,
│                                   │     toggles to "stop"
│  [glow strength — same 0-100      │  ← re-skin of the confidence percentage
│   number, warm-styled]            │     (DetectorContent's ReadoutBlock)
│                                   │
│  spotted:                        │  ← re-skin of the existing flagged-history
│  14:22   bright                  │     list (rising-edge events only, capped
│  14:21   faint                   │     at 50, recent-first — unchanged from
│                                   │     Screen 3)
└─────────────────────────────────┘
```

**Copy mapping** (softened, not renamed-for-its-own-sake — every mapped phrase still says
something true and specific, matching the voice contract every technical screen already
follows):

| Technical (Screens 2/4/5) | Jar mode |
|---|---|
| `embedded` / `Embedded(bytes)` | "you caught one — N bytes" |
| `ExtractedSuccess` / `DecodedSuccess` | recovered text shown directly (unchanged — this was already the honest move) |
| `NO_PAYLOAD_FOUND` | "nothing's glowing in here right now." |
| `HEADER_INVALID` | "that light doesn't look right — probably not one of yours." |
| `PAYLOAD_TOO_LARGE` | "too big to fit in the jar." |
| `UNRECOVERABLE_FEC` | "the light faded before it got here." |
| `INTEGRITY_MISMATCH` | "it flickered wrong on the way — the message got scrambled." |
| detector `flagged` | "something's out there" |
| detector `clear` | "all quiet" |

No exclamation points, no checkmarks, no "Yay!" — the copy is warmer than the technical
screens' bare system-label voice, but it still states a fact, same restraint applied with
a different register rather than abandoned.

**What this addition deliberately does not build:** no separate screen per action (catch/
look are inline-expanding sections on `JarDetailScreen`, not new screen navigations — the
"simple is better" call over a deeper screen tree); no re-implementation of any carrier
logic (calls the exact same `encode`/`decode`/`analyze` methods Screens 2/4/5 already
call); no per-firefly delete (only the shelf-level "clear jar history" action from gate-14
exists — deleting one firefly at a time is a real feature with its own edge cases, not
something this pass needs to invent).

**v4 addition — built (gate-18/19/20): a carrier viewer.** Tapping a firefly's detail popup now
also shows what it actually looked or sounded like — an IMAGE firefly renders its stego bitmap
(with the same tap-to-fullscreen affordance below) plus a two-way "image"/"bit-plane" toggle;
an AUDIO firefly gets play/stop on its stego clip plus a spectrogram, both honestly captioned
per technique (gate-19, see spec.md). A usage readout, an advisory threshold warning,
clear-all, and per-firefly delete round out storage governance — the per-firefly delete gap
the paragraph above once called out is now closed. Pre-migration or media-less records degrade
to the original text-only popup instead of erroring.

**v5 addition — built (design-v5.md §3/§4, gate-24/25/26): two more honest carrier views,**
placed beside the bit-plane/spectrogram toggle as one more instance of the same
"data-visualization" allowance (`design/firefly-jar-identity.md`). For spectrogram-LSB
fireflies, a cover-vs-stego **difference view** re-derives the app's own bundled cover
(no stored cover, no schema change) and shows exactly which cells the codec nudged vs. created
— withheld with a stated reason when no cover re-derives. For phase-inversion fireflies, an
**L/R polarity view** shows the stereo channels overlaid, L ≈ −R where the payload sits. Both
are gated on `FireflyRecord.technique` and the WAV's channel count, never on `Module` — no new
per-`Module` branch (architecture.md § 6). The watching jar also gains "peek inside" — the
humming jar's re-skin of the audio technical screen's new "check for hidden data" (see Screen 5
above); a peek writes no firefly.

**Owner-requested update (UX-IMAGES):** the carrier viewer's image render now carries the same
subtle tap-to-fullscreen affordance Screen 4 gained (see that section) — tapping it opens the
shared `FullscreenImageViewer`, palette-neutral rather than themed to either surface's own
identity doc, since a modal photo viewer should read the same however it was reached.

---

## v6 addition: sharing, receiving, and the meadow reorder

Scope: spec.md's v6 addition (sharing fireflies across channels + the first-use riddle trail),
gates 27–40. This section covers everything **except** the trail's own step-by-step content,
which lives in the new sibling doc `design/riddle-trail.md` (gates 36–38) — this section is
where that trail's steps 4–6 get their screen-level wireframes (the meadow, send, the wordmark
hint), since those are real screen layouts this doc already owns.

**Naming note (owner direction, 2026-09-21):** "the framed jar" is renamed **"the art jar"**
and "the watching jar" is renamed **"the meadow"** in all user-facing copy, already landed on
`feat/v6-sharing-trail` (commit `f743f42`). Every wireframe and copy table below uses the new
names. This worktree's own checkout predates that commit (see this doc's Screen 6/7 sections
above, which still say "framed"/"watching" — left as historical record, not corrected in
place, since gate-39's own audit is the mechanism that reconciles it against shipped code, not
a doc edit here).

### Shelf order (gate-39)

Tiles are derived from `Module.entries`, unchanged from Screen 6's original design (this was
never hardcoded). The v6 reorder swaps the detector to last:

```
old:  the singing jar · the framed jar · the watching jar · the humming jar
new:  the singing jar · the art jar     · the humming jar  · the meadow
```

Three creating jars now sit together at the top, the one jar that never holds a firefly sits
alone at the bottom — the shelf reads, top to bottom, as "things you fill, then the one thing
that just watches." Nothing persists an enum ordinal (verified before the reorder per gate-39),
so every firefly caught before this release keeps its jar regardless of where its tile now
sits.

---

### Receiving

Three entry points, all landing on the same underlying pipeline (spec.md v6, gate-31/32):

1. **Share-to** — another app's share sheet, targeting nightjar (`ACTION_SEND`, `image/*` or
   `audio/*`).
2. **Open-with** — a file manager or gallery app's "open with" chooser (`ACTION_VIEW`), same
   MIME types. Either entry point may arrive while the app isn't running at all, or while it's
   already open on some other screen — `MainActivity`'s `onCreate` and `onNewIntent` both route
   to the same handler either way.
3. **In-jar "catch from a photo or file"** — a fourth action row, alongside `create a firefly`
   / `look for fireflies`, on **all three creating jars** (art, humming, singing) — not a
   single shared shelf-level action. Placed last in each jar's action group. It opens the
   Android Photo Picker for image MIME types or the system document picker for audio, then
   runs the exact same auto-detect-and-route pipeline as share-to/open-with. **Why all three,
   identically, rather than one canonical spot:** a user who opens the humming jar because a
   friend said "I hid something in a voice memo" might actually have been sent a picture by
   mistake, or might not remember which kind of file it was — auto-detection means the entry
   point's own jar doesn't need to match the file's real technique, so there is no wrong jar
   to start from, and requiring the user to first guess correctly before they can even open
   the picker would undercut the entire point of auto-detection. The meadow does not get this
   action — it isn't a creating jar and never lands a caught firefly (`JarRole.WATCHING`
   unchanged).

```
┌─────────────────────────────────┐
│  ← back to the shelf             │
│                                   │
│  the humming jar                 │
│  ...                              │
│                                   │
│  create a firefly                │
│  look for fireflies              │
│  catch from a photo or file      │  ← NEW, v6 — opens Photo Picker (image/*)
│                                   │     or document picker (audio/*); result
│                                   │     routes to whichever jar it actually
│                                   │     matches, not necessarily this one
│                                   │
│  [outcome message — see below]   │
└─────────────────────────────────┘
```

No permission is added for any of the three entry points (INV-10) — the share sheet, the
Photo Picker, and the document picker are all permission-free content-resolver paths on
current Android.

### The five outcomes (INV-12)

Every incoming file resolves to exactly one of these; a message is shown only after its
checksum verifies (so "caught" is never shown on a false positive). Copy follows the existing
voice contract (what happened, plain language, two sentences max, no exclamation) and, for
`caught`, its own established pattern (`you caught one — N bytes, hidden in {channel}.`) —
receiving keeps "caught" under the v6 verb rule (`design/riddle-trail.md` § Verb rule: "catch"
never describes making one), distinct from jar-mode embed success, which now reads "you
created one — N bytes" (Screen 7's copy-mapping table predates this rule and still shows the
older "you caught one" embed copy as history).

| Outcome | When | Jar-voice copy |
|---|---|---|
| **caught** | technique auto-detected, decode succeeds, checksum verifies | `you caught one — N bytes, hidden in {channel}.` (lands in the matching jar's swarm as a RECEIVED firefly) |
| **squeezed** | the file arrived in a lossy container (JPEG/WebP picture, or compressed audio such as a voice note) and nothing decoded — it may have carried an exact firefly that the sending app squeezed away, or it may be an ordinary photo; the app can't tell, so the copy never asserts one was sent (INV-12) | title `no firefly came through`; picture: `this picture arrived compressed. if someone hid a firefly in it, the app it traveled through may have squeezed it away. ask them to send it as a file, or to use a sturdy firefly.` audio: `this recording arrived compressed, the way voice notes are. if someone hid a firefly in it, the compression may have squeezed it away. ask them to send it as a file or document.` (orchestrator correction 2026-09-22: the original draft asserted the bits "didn't survive") |
| **damaged** | the file matches a nightjar carrier's shape (right header/magic) but its checksum fails in a way recompression doesn't explain — a genuinely corrupted transfer | `this one got damaged on the way over. ask whoever sent it to send it again.` |
| **no firefly** | a normal, unmodified photo or audio file — decode attempted, no header found | `no firefly hiding in this file.` |
| **unsupported** | a file type nightjar doesn't parse at all (wrong MIME, unreadable image/audio) | `nightjar doesn't know this kind of file.` |

"Squeezed" is the one outcome this v6 addition is built to say plainly instead of masking as a
generic failure — spec.md's own framing: "the receiving side says plainly when an app squeezed
a firefly, instead of reporting a checksum error." Every other outcome above already follows
the existing `DecodeFailure` copy-mapping precedent (Screens 2/4/5) of "what's wrong, what to
do," just extended to file-arrival rather than an in-app action.

---

### Two send flows

#### "send this firefly" (firefly detail, any caught/spotted firefly)

```
┌─────────────────────────────────┐
│  ← back to the jar               │
│                                   │
│  a firefly you caught            │
│  14:22, sent through sound       │
│  "..."                            │
│  42 bytes                        │
│                                   │
│  send this firefly               │  ← NEW, v6 — 48dp row, same verb-button
│                                   │     shape every other action row uses
│  let this firefly go             │  ← existing delete affordance, unchanged
└─────────────────────────────────┘
```

Tapping `send this firefly`:

1. If the firefly's technique is **exact** (raw-pixel LSB — the only technique where recompression
   silently destroys the payload), a one-line advisory appears first, inline, no dialog:
   `hidden, not locked — and exact only survives as a file. pick "file" or "document" in the
   next screen, not "photo."` For every other technique (sturdy, all three audio, acoustic
   modem) the advisory is shorter, since only exact has a file-vs-photo trap: `hidden, not
   locked.`
2. The app writes a private cache copy via `FileProvider` (no MediaStore write — amended
   INV-5) and opens the system share sheet (`ACTION_SEND`).
3. After the share sheet returns (regardless of what the user actually did in it — that's
   outside the app's visibility), an optional, separate `keep a copy` link appears once,
   inline under the send row, for one interaction only — tapping it is the one explicit
   gesture that writes to `Pictures/Nightjar`/`Music/Nightjar`, matching the technical screens'
   existing save/share precedent. Not shown again once dismissed or used for that firefly.

#### "hide one in a photo" (art jar, own photo)

```
┌─────────────────────────────────┐
│  ← back to the shelf             │
│                                   │
│  the art jar                     │
│                                   │
│  hide one in a photo             │  ← NEW, v6 — opens Photo Picker
│                                   │
└─────────────────────────────────┘
        │ pick a photo
        ▼
┌─────────────────────────────────┐
│  ← back                          │
│                                   │
│  message                         │  ← field label, noun (voice contract)
│  ┌─────────────────────────────┐ │
│  │ text to hide                │ │
│  └─────────────────────────────┘ │
│                                   │
│  sturdy                          │  ← 40dp row, selected by default,
│  exact                           │     TextPrimary if selected else
│                                   │     TextSecondary — same tap-to-switch
│                                   │     shape as every other technique
│                                   │     selector in the app
│  64 / 118 bytes · 2.1 MB          │  ← capacity (per selected technique +
│                                   │     this specific photo) and resulting
│                                   │     file size, both real numbers,
│                                   │     recomputed live on either the
│                                   │     message or the technique changing
│                                   │     (Screen 4/5's capacity-recompute
│                                   │     precedent)
│  hidden, not locked.             │  ← static, always visible once a
│  sends as a file if you pick     │     technique is exact — see the
│  exact.                          │     advisory rule above; sturdy shows
│                                   │     just "hidden, not locked." alone
│  hide it                         │  ← 48dp verb row — encodes, then
│                                   │     immediately opens the share sheet
│                                   │     (no separate "embedded" pause
│                                   │     screen — the whole point of this
│                                   │     flow is getting to the share
│                                   │     sheet, not admiring the result)
└─────────────────────────────────┘
```

`sturdy` is selected by default (owner direction) — it's the technique built to survive what
messaging apps actually do to photos; `exact` is one tap away for anyone who specifically wants
byte-for-byte fidelity and already knows it has to travel as a file. Capacity is shown for
whichever is selected, same "real number over vague copy" rule Task #28 already established
project-wide.

---

### Meadow tile and shelf, revised

```
┌─────────────────────────────────┐
│                                   │
│   nightjar                       │  ← wordmark; subtitle is trail-state-
│   try the art jar                │     dependent while the trail is
│                                   │     active — see design/riddle-trail.md
│                                   │     § Wordmark hint; reverts to "hold
│                                   │     to open workshop" once done/skipped
│                                   │
│   [jar]  the singing jar         │  ← acoustic modem
│          •• •                    │
│                                   │
│   [jar]  the art jar             │  ← image steganography (renamed)
│          •                       │
│                                   │
│   [jar]  the humming jar         │  ← audio steganography
│          •••                     │
│                                   │
│   [field] the meadow             │  ← detector, moved to 4th; drops the
│           ·  ·                   │     jar-glass silhouette — see
│                                   │     firefly-jar-identity.md v6
│                                   │     addendum for the visual
│                                   │     treatment and why
└─────────────────────────────────┘
```

The meadow tile's actual visual redesign (drop the glass, what replaces it, why) is specified
in `design/firefly-jar-identity.md`'s v6 addendum, not here — this doc only records that the
tile's *position* changed and that its *shape* is no longer a jar silhouette, since both are
screen-flow-level facts (what's on the shelf, in what order) rather than palette/motion-level
ones.

---

### Workshop audio send/receive (gate-34)

The technical audio screen (Screen 5) gains two rows in its existing action-row group, save and
share for its stego WAV — matching the acoustic modem screen's existing `save`/`share` pair
(Tasks #31/#32/#36 drift note above) rather than inventing new verb shapes — plus a decode path
that tries all three techniques against an imported/received WAV and names which one matched:

```
│  embed                           │  ← existing
│  extract                         │  ← existing
│  check for hidden data           │  ← existing (v5)
│  save                            │  ← NEW, v6 — writes the working stego
│  share                           │  ← NEW, v6 — same share-sheet path as
│                                   │     jar-mode send, private cache file
│  import                          │  ← NEW, v6 — pick a WAV, try all three
│                                   │     techniques against it, "decoded
│                                   │     with {technique}" on success
```

This is the one place in the app where the technical screen, not the jar surface, gains a send/
receive capability — deliberate, per spec.md's v6 boundary: jar-mode send is a private
cache-file share (amended INV-5); the technical screens' save/share keeps writing to
`Pictures/Nightjar`/`Music/Nightjar` as it already did pre-v6, and the audio screen simply
catches up to the pattern the modem screen already established.

---

## Copy audit (appendix, gate-39/gate-40 support)

Two greps, run against this worktree's checkout (based on `37e2e22`, pre-dating the rename
commit `f743f42` the coordinator applied directly on `feat/v6-sharing-trail`). This table is a
**cross-check**, not the rename itself — the coordinator reports the rename is already done in
code: `Module.jarName` values are now `"the art jar"`/`"the meadow"`, shelf order is now
`ACOUSTIC_MODEM, IMAGE_STEGANOGRAPHY, AUDIO_STEGANOGRAPHY, DETECTOR`, `DetectorScreen.kt:289`
now reads `"the meadow needs microphone access to watch."`, and `@Preview` labels were renamed.
KDoc/comments were deliberately left as-is. Every row below was checked against that report;
none were found missed.

`grep -n -i "watching"` over `JarDetailScreen.kt`, `DetectorScreen.kt`, `JarShelfScreen.kt`,
`AcousticModemScreen.kt`, `ModulePicker.kt`, `FireflyGlyphs.kt`, `JarCatchFlows.kt`:

| File:line | Current text | Classification | Proposed |
|---|---|---|---|
| `ModulePicker.kt:66` | `Module.jarName = "the watching jar"` | **user-facing** | `"the meadow"` — **already applied** on `feat/v6-sharing-trail` per coordinator |
| `DetectorScreen.kt:323` | `"glow strength appears once you start watching"` | **user-facing** | no change — `"watching"` here is the verb form of the meadow's own `watch` action, not the retired jar name; the sentence carries no grammar dependency on "jar" and reads correctly for a meadow (you can watch a meadow) |
| `DetectorScreen.kt:351` | `"nothing spotted yet"` (matched via `JarWatchingDim` on the same line, not the string itself) | leave | n/a |
| `JarDetailScreen.kt:311` | `"hold it up and see if anything glows nearby"` (matched via `JarRole.WATCHING ->` on the same line, not the string itself) | leave, reviewed | no change — string references neither "jar" nor "watching"; already meadow-safe |
| `JarDetailScreen.kt:1840` | `@Preview(name = "Watching jar (detector)")` | dev-only preview label | **already applied** per coordinator ("@Preview labels renamed") |
| `JarDetailScreen.kt:1842` | `PreviewJarDetailWatching` (function identifier) | identifier | leave |
| `JarShelfScreen.kt:210–211, 220, 233` | KDoc comments ("the watching jar is the exception ...", "the watching jar never holds fireflies ...", "the watching jar's own 'watch' button tap ...") | KDoc/comment | leave |
| `JarDetailScreen.kt:95, 108, 125, 230, 235, 259, 317, 356–358, 371, 794, 1097, 1148, 1328, 1466` | imports/color refs (`JarWatchingDim`) and KDoc comments | identifier / KDoc | leave |
| `AcousticModemScreen.kt:88, 349, 789, 848` | import (`JarWatchingDim`), KDoc comments, and `"ready to catch"` (matched via color ref, not text) | identifier / KDoc / leave | leave |
| `DetectorScreen.kt:63, 172–173, 179, 250, 270, 275–276` | import (`JarWatchingDim`), KDoc comments (design-doc citations, `jarWatchFlow` naming note), and background-color refs (`JarWatchingDim.copy(alpha = ...)`) | identifier / KDoc | leave |
| `ModulePicker.kt:31, 34` | KDoc comment; `enum class JarRole { CREATION, WATCHING }` | KDoc / identifier | leave — spec.md explicitly keeps `JarRole.WATCHING`'s name (owner direction, 2026-09-21) |
| `JarCatchFlows.kt:18` | KDoc comment | KDoc | leave |
| `FireflyGlyphs.kt:41, 51, 64, 66, 257, 260, 273, 287, 294, 329` | imports (`JarGlassWatching`, `JarWatchingDim`), function name `drawWatchingJar`, KDoc comments | identifier / KDoc | leave — `drawWatchingJar` is the render function the meadow's own visual redesign (firefly-jar-identity.md v6 addendum) will replace or rename as implementation work, not a copy-audit item |

**Correction to the coordinator's `DetectorScreen.kt:289` note:** that line (`"the jar needs microphone access to watch."`, confirmed present at that exact line number in this worktree's checkout) does not actually contain the substring `"watching"` (`"watch."` has no `-ing`), so it would not have surfaced in a literal `grep -i "watching"` pass — it's a real rename regardless, just not one this specific grep would have caught on its own. Verified directly with a targeted `grep -n "microphone permission\|needs microphone" DetectorScreen.kt` in this worktree, which returned the exact pre-rename text the coordinator described as now-fixed. No discrepancy — this is a note on the audit method's own blind spot (a grep for one word can't catch every phrase that implies the retired name), not a missed string.

`grep -n -i "framed"` over the same files plus `ImageStegoScreen.kt`:

| File:line | Current text | Classification | Proposed |
|---|---|---|---|
| `ModulePicker.kt:65` | `Module.jarName = "the framed jar"` | **user-facing** | `"the art jar"` — **already applied** per coordinator |
| `JarDetailScreen.kt:1812` | `@Preview(name = "Framed jar (image steganography)")` | dev-only preview label | **already applied** per coordinator |
| `JarDetailScreen.kt:1814` | `PreviewJarDetailFramed` (function identifier) | identifier | leave |
| `ImageStegoScreen.kt:360` | KDoc: `"jar-framed catch flow for [Module.IMAGE_STEGANOGRAPHY] (\"the framed jar\", ...)"` | KDoc | leave — quotes the old name inside a comment; harmless, not user-facing |
| `ImageStegoScreen.kt:565` | KDoc: `"framed jar's three stacked rows"` | KDoc | leave |
| `AcousticModemScreen.kt:329` | KDoc: `"the real jar-framed flow"` | KDoc, unrelated meaning | leave — "framed" here means "structured," not a reference to the image jar's old name |
| `JarCatchFlows.kt:12` | KDoc: `"own jar-framed flow"` | KDoc, unrelated meaning | leave — same as above |

**Result:** zero user-facing strings found that the coordinator's rename missed. Both
`Module.jarName` occurrences (the only two places the retired names actually reached a real
screen) were already caught, and both `@Preview` display names were already renamed too.
`DetectorScreen.kt`'s permission-denial copy was already caught, though it wouldn't have
surfaced from this literal grep (see the correction note above — real rename, blind spot in
the method, not a miss). The two candidates worth a second look —
`DetectorScreen.kt:323`'s `"watching"` verb and `JarDetailScreen.kt:311`'s meadow caption —
were reviewed on grammar grounds and don't need changes; neither depends on the word "jar" or
the retired name, so both are filed under "leave" rather than "renamed."

Copy-audit count: **5 user-facing strings** (the two `Module.jarName` values, the one
`DetectorScreen.kt:323` verb-form string, and the two `@Preview` display-name literals — the
only rows in either table that aren't KDoc, a code comment, or a bare identifier); **53 leave**
(KDoc/comment/identifier instances across both tables, including the two rows — `DetectorScreen
.kt:351`, `JarDetailScreen.kt:311` — where the grep hit itself landed on an identifier
(`JarWatchingDim`, `JarRole.WATCHING`) even though the same line also carries an unrelated,
already-fine user-facing string).

---

