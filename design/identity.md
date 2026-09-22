# nightjar — Visual Identity
**Last updated:** 2026-08-03 (Task #28)
**Source of truth for:** palette, typography, motion, system bar treatment

---

## Status

Task #17 is the dedicated visual-identity pass, run once all three module screens
(modem #7, detector #10, image steganography #13) had real active states to design
around. It resolves every decision tasks #4/#7/#10/#13 explicitly deferred: the accent
color, the status-word phrasing judgment call, the system-font-vs-Inter call, and the
motion-deferral call. It also ships the app's first launcher icon (see `icon.md`) —
none existed before this task.

Everything below is now the settled baseline, not a placeholder. Where a decision
carried forward unchanged from an earlier task (e.g. mono-only-for-timestamps), it's
marked "ratified" rather than "changed."

---

## Palette

Locked to the android-designer default dark baseline (`/home/hercules/pixel6a/CLAUDE.md`
§ Color & Type Defaults) — not yet an app-specific override, because nothing in this
app has needed to diverge from the baseline yet.

| Token | Hex | Compose name | Role |
|---|---|---|---|
| bg-base | `#0D1117` | `BgBase` | Screen background, window background, cold-start frame |
| bg-surface | `#161B22` | `BgSurface` | Cards / sheets — not in active use yet, no elevated surfaces on these screens |
| text-primary | `#E6EDF3` | `TextPrimary` | Wordmark, module names, row labels |
| text-secondary | `#7D8590` | `TextSecondary` | "back" affordance, stub-screen note |
| **accent-signal** | **`#39C5CF`** | **`AccentSignal`** | **The covert channel is live — see below** |
| border | `#30363D` | `BorderDefault` | Defined, not in active use — the module list has no dividers |
| danger | `#DA3633` | `Danger` | Defined, not in active use anywhere yet |

### Accent decision (Task #17)

`AccentSignal` — `#39C5CF`, a cyan pulled from the same GitHub-dark scale the rest of
the palette already borrows from. Contrast against `BgBase`: **9.07:1**, clears WCAG AA
(4.5:1 for normal text) with wide margin — verified numerically, not eyeballed (see
verification note at the bottom of this file).

Chosen over an amber/gold candidate (reads as "warning," wrong connotation for an
informational flag) and over a green (reads as "success," which this app's doctrine
explicitly forbids defining a color for). Distinct from Relay's warm orange
(`#F0883E`) and from `Danger` red — each app in the pixel6a workspace picks its own
accent independently; nothing here is a shared cross-app token.

Reserved for exactly two moments, both literally "the covert channel is live," never
for a completion/confirmation state:

1. **Acoustic modem** — the `transmitting`/`listening` status word, i.e. the instant
   the speaker or mic is physically carrying the signal. `encoding`/`decoding` (CPU-
   only, no hardware I/O at that instant) stay `TextSecondary`.
2. **Detector + image steganography** — the `flagged` word only (not the confidence
   percentage, not `clear`). Spec INV-4 makes this the actual thesis of the app —
   self-detecting the app's own transmission is the defensive proof-of-concept
   nightjar exists to demonstrate — so "flagged" is the one state in the whole app
   that most earns a visual signal beyond text weight.

**Deliberately NOT applied to:** `DecodedSuccess`/`ExtractedSuccess`/`Embedded` text,
or the confidence percentage itself, even though this task's brief described the
target states loosely as "flagged/active/success." Doctrine's "do NOT define a
success color; silence is success" is a harder, more specific rule than that framing,
and it's still true here — recovered text appearing in `TextPrimary` already *is* the
feedback. This matches Relay's own precedent exactly: `AccentRecord` lands only on the
mic ring, never on the recovered transcript (Relay identity.md, Motion table: "Result
transcript fade" gets no accent, only default `TextPrimary`/`headlineSmall`).

`AccentSignal` is never bound into `NightjarColorScheme` (`primary` still maps to
`TextPrimary` — see Theme.kt) — it only appears through explicit `color =` calls on
the specific status words listed above, the same discipline Relay's `AccentRecord`
uses. No M3 component auto-injects it into a button fill, a focus ring, or a checked
state.

The module-picker home screen has no accent anywhere — it's a static 3-row list with
no "active" or "flagged" state to signal, so introducing color there would be
decoration, not information.

No success color. Silence is success.

---

## Typography

Font: **system default** (`FontFamily.Default` — Roboto on the Pixel 6a). **Decided,
Task #17: staying on system sans, not bundling Inter.** Relay bundles Inter because
it's a daily-driver personal tool where the transcript text itself is the product and
warrants a specific typographic voice (see its identity.md — Inter Semibold on the
hero transcript). nightjar's screens are dense operational readouts — byte counters,
confidence percentages, chi-square detail strings, FEC correction counts — not prose;
Roboto already renders that content legibly at the sizes in use, and bundling TTFs
adds asset weight and a font-loading path for a research/demo tool that has no
reason to look different from the platform it's running research on. Revisit only if
a future task adds real body-copy surfaces (a results/export screen, a session log)
where a chosen typographic voice would actually earn its keep.

Weights stay limited to 400/500/600 — no 300 (fragile on OLED), no 700+ (too heavy in
dark mode).

### Type scale (3 active positions, Task #4 scope)

| Compose slot | Size / Line-height | Weight | Use |
|---|---|---|---|
| `displayLarge` | 22sp / 28sp | 500 (Medium) | "nightjar" wordmark, module name on the stub screen |
| `bodyLarge` | 15sp / 22sp | 400 (Normal) | Module-picker row labels, stub-screen note |
| `labelLarge` | 13sp / 18sp | 500 (Medium) | The "back" affordance |

All remaining M3 Typography slots collapse to a bodyLarge-equivalent so nothing
falls through to an unstyled system default when a later task reaches for an
unused slot (see `Type.kt` for the full collapse list).

---

## Tappable-vs-static affordance (2026-09-22)

Owner report: on the five technical screens (module picker, acoustic modem, image
steganography, audio steganography, detector), users could not tell which text was
tappable versus purely informational. The diagnosis held up: `ActionRow`/`CoverRow`
(and their equivalents in every other technical screen file) render their **enabled**
tappable label in `labelLarge`/`TextPrimary` — the exact color `StatusBlock`'s static
result text also uses (a different type slot, `bodyLarge`, but the same color, which is
what a quick glance actually registers). A **disabled** row drops to `TextSecondary` —
the same color static secondary captions and descriptions already use. Color was being
asked to carry three things at once (selection state, enabled/disabled state, and
tappability) when it was only ever reserved for the first two.

**Decision:** a plain `TextDecoration.Underline`, applied through one shared primitive,
`TextStyle.withTapAffordance(tappable: Boolean = true)` in
`ui/theme/TappableText.kt`, reserved exclusively for text the user can act on *right
now*. Never applied to static/informational text, status words, or a row while it's
disabled.

Considered and rejected:
- **A trailing glyph** (e.g. a `›` appended to the label). Rejected: roughly half the
  rows this needs to cover are *selection* rows (`CoverRow`, `SettingOptionRow`,
  `SelectorRowWithInfo`'s option rows) rather than navigation rows — a forward-chevron
  on "cover image: mosaic" promises a drill-down that tapping it doesn't do (it just
  selects the option in place). One glyph, one meaning, wrong on half its own
  applications is worse than the ambiguity it would fix.
- **A new or reassigned color.** Rejected on the same discipline the accent-color
  decision above holds itself to: `AccentSignal` earns its existence with a measured
  contrast ratio and exactly two named states. This affordance has to sit on top of
  the selected/unselected and enabled/disabled color logic every row already carries,
  not replace or compete with it — a third color axis stacked on two already-overloaded
  ones would make the palette harder to reason about, not easier.
- **Underline** was the one option that costs nothing new in the palette: no icon or
  vector asset, no shadow/glow/gradient, no motion, and it is the oldest, plainest
  "this is a link" convention there is — older than the Material-3-sample look this
  project exists to avoid, not an imitation of it.

**Disabled rows get no distinct treatment of their own beyond losing the underline** —
they keep rendering exactly like static secondary text, which this task treats as the
correct reading rather than a gap: a disabled row is not actionable right now, so
looking like static text is honest, not a missed case. A separate "disabled but not
quite static" visual tier would be a fourth signal for a state this pass wasn't asked
to solve, and it's the kind of speculative decoration the "one rough edge, not a
polished one" discipline elsewhere in this file argues against adding pre-emptively.

Applied to every enabled tappable row on all five technical screens: `ModulePicker`'s
three module rows (label only — each row's one-line description stays plain, matching
the rule that the affordance marks the actionable word, not the whole row) plus its
"back to the jar" and "start the trail again" links; `AcousticModemScreen`'s
`ActionRow`/`SettingOptionRow` rows and its "back" link; `ImageStegoScreen`'s
`ActionRow`/`CoverRow` rows and its "back" link; `AudioStegoScreen`'s `ActionRow`,
`PlaybackVerb`, `SelectorRowWithInfo` (both its option label and its "?"/"close" info
toggle), `SectionLabelRow`'s info toggle, and its "back" link; `DetectorScreen`'s
listen/stop `ActionRow` and its "back" link. Rows with no `enabled` concept of their own
(back links, the detector's listen/stop row, the info toggles) pass no argument and
resolve to the primitive's default (`tappable = true`).

**Explicitly not touched:** the Firefly Jar surface (`JarActionRow`, `JarCoverRow`,
`JarFlowRow`, `JarSelectorRow`, `JarOptionRow`, and everything else under
`design/firefly-jar-identity.md`'s doctrine). That surface already signals its tappable
rows with tinted fill + border, has its own visual language by explicit owner
direction, and this task's scope is the technical screens only. The fullscreen-image
tap target (`ImageStegoScreen`'s cover preview, `FullscreenImageViewer.kt`) was also
left alone — it's an `Image`, not `Text`, so a text-decoration primitive doesn't apply
to it, and it already has its own owner-requested corner glyph affordance from a prior
task, outside this pass's "text-only" brief.

No new color token was needed — see § Palette above for why introducing one was
rejected. `Type.kt` was not touched; the primitive operates on whatever `TextStyle` a
row already resolves to, rather than adding a new type-scale slot.

---

## Compose theme wiring

`NightjarTheme` in `app/src/main/java/dev/herakles/nightjar/ui/theme/Theme.kt`:
- Uses `darkColorScheme()` with the palette locked above.
- `primary` mapped to `TextPrimary`, never to `AccentSignal` — keeps M3 defaults from
  auto-injecting cyan into button fills, focused outlines, or checked states.
  `AccentSignal` only reaches the screen through explicit `color =` calls on the two
  status words it's reserved for (see Palette § Accent decision above).
- `dynamicDarkColorScheme()` is not used. Dark-only, hardcoded —
  `isSystemInDarkTheme()` is a footgun here.

XML theme in `res/values/themes.xml`:
- Parent: `android:Theme.Material.NoActionBar` (pure Android dark, no light
  injection). **Fixed in Task #4** — the scaffold task (#1) left the parent as
  `Material.Light.NoActionBar`, which would have shown a white cold-start flash
  before Compose ever ran. Same bug whisper-voice-app hit and fixed on its own
  scaffold (see its identity.md changelog); nightjar's scaffold made the identical
  mistake independently.
- `windowBackground` / `statusBarColor` / `navigationBarColor`: all `@color/bg_base`
  (`#0D1117`).
- No `values-night/` override. No light variant. Dark-only.

---

## Motion

**Decided, Task #17: staying instant. No `Crossfade`, no animated meter, no tween on
any status-word or confidence-percentage change, anywhere in the app.** This closes
the "revisit at Task #17" note tasks #7 and #10 both left in the changelog below —
it's a final decision now, not a deferral.

Reasoning: doctrine's own reduce-motion rule says instant transitions are never a
compromise ("collapse to instant transitions; never 'ease-out but shorter' as a
compromise") — meaning instant-by-default was always an acceptable baseline, not a
placeholder waiting to be replaced. Weighed against that: every status change in this
app is either near-instantaneous already (encode/decode/embed/extract/analyze are all
sub-second CPU work) or is itself the informative event (a byte counter or confidence
percentage ticking during a multi-second listen/transmit *is* the information — animating
the number's transitions would decorate data, not clarify it). None of the three module
screens have a moment where a `Crossfade` or tween would communicate something a plain
text swap doesn't. Given that, and that no `androidx.compose.animation` dependency is
declared in `app/build.gradle.kts` (adding one is a real, if small, build-risk surface
this task didn't need to take on to make its actual decisions), staying instant is the
correct call, not just the cautious one.

Predictive back remains the one motion moment in the app, and it's the system's own —
not custom-built (see below).

System / predictive back: `BackHandler` in `MainActivity.kt` returns from any stub
screen to the picker. `android:enableOnBackInvokedCallback="true"` is set on
`<application>` in `AndroidManifest.xml` so the Android 14+ predictive-back
animation is available (not custom-built — it's the system's own swipe-preview).

---

## System bar treatment

- Edge-to-edge: `enableEdgeToEdge()` + `Modifier.windowInsetsPadding(WindowInsets.systemBars)`
  on the screen root, in `MainActivity.kt`.
- XML fallback in `themes.xml` sets status/nav bars to `#0D1117` for the pre-Compose
  frame — Compose's `enableEdgeToEdge()` takes over once it renders.

---

## Anti-AI-tell status

**Full checklist audit as of 2026-08-03 (Task #17), run against all four screens**
(module picker, acoustic modem, detector, image steganography) and the checklist in
`/home/hercules/pixel6a/CLAUDE.md`:

- [x] No emoji in UI labels — confirmed, none anywhere (checked every `Text(...)` call
  across all four screen files)
- [x] No "Listening…"/"Processing…"/"Analyzing…" gerunds — no screen uses an ellipsis
  anywhere. Status words (`encoding`, `transmitting`, `listening`, `decoding`,
  `embedding`, `extracting`, `analyzing`) stay as bare lowercase present-participles
  with no ellipsis, no capitalization, no exclamation — **ratified this task**, closing
  the "revisit at Task #17" note tasks #7/#13 left. The checklist's actual target is
  the ellipsis-suffixed chatbot-spinner pattern ("Thinking...", "Generating..."); a
  terse system label with no punctuation reads as a camera app's "recording" indicator,
  not that pattern. This is a final decision, not a deferral.
- [x] No "Got it!"/"Done!"/"Success!" affirmations — confirmed, none anywhere. Recovered
  text (`DecodedSuccess`/`ExtractedSuccess`) and embed confirmation (`Embedded`) render
  as plain fact statements with no exclamation, no checkmark glyph, no color change
- [x] No shimmer/glow/bloom on any primary control — confirmed, no `Brush`, no
  `graphicsLayer` blur/shadow, no animated gradient anywhere in the four screen files
- [x] No three-color gradient backgrounds — confirmed, every `Box`/screen root uses a
  flat `BgBase` fill only
- [x] No glassmorphism — confirmed, no `blur()`, no translucent card surfaces (no cards
  at all, in fact — every screen is a plain `Column`)
- [x] No Material You / `dynamicDarkColorScheme()` binding to wallpaper — confirmed,
  `NightjarTheme` uses `darkColorScheme()` with the locked palette; `AccentSignal`
  (Task #17's new addition) is likewise hardcoded, never wallpaper-derived
- [x] No bottom-sheet onboarding carousel — confirmed, none exists
- [x] No "Powered by X" badges — confirmed, none exists
- [x] No "Smart suggestions" sections — N/A, nothing in this app regurgitates input
- [x] No animated app-launch splash — confirmed, no `SplashScreen` API usage, no
  animated wordmark intro. (No splash at all, in fact — Compose renders directly onto
  the dark `windowBackground` fallback; that's the correct doctrine reading of "none
  unless required by the platform")
- [x] No "Discover"/"Trending"/"For You" sections — N/A, the module picker is a fixed
  3-row list, order set by spec.md's own module priority, not personalization
- [x] No 3-bullet TL;DR feature card grid on the first screen — confirmed, the picker is
  3 plain text rows, no cards, no feature copy
- [x] No "Excited to share…"/"Thrilled to announce…" — N/A, no README/release copy
  shipped by this task (README copy is docs-agent territory per this agent's
  responsibilities table)
- [x] No illustrated mascot on empty states — confirmed. Detector's "no detections yet"
  and the (still-reachable, unused-in-practice) `ModuleStubScreen`'s "not built yet" are
  both plain text, 8 words or fewer, no illustration
- [x] No "Rate this app" prompt — confirmed, none exists
- [x] No drop-shadow under cards in dark mode — confirmed, no cards exist; the image
  preview's `Bitmap` box uses a flat 1px `BorderDefault` stroke, no shadow modifier
- [x] No pure-white text (`#FFFFFF`) — confirmed, `TextPrimary` is `#E6EDF3` throughout,
  no screen introduces a raw white
- [x] No "v1.0.0"/"MVP" badge in the UI — confirmed. `versionName = "0.1.0"` exists only
  as internal Gradle build metadata, never rendered on any screen
- [x] No Apple-style soft-squircle icon with inner highlight — confirmed for the new
  icon: flat wave + sharp-cornered accent square, no highlight, no inner shadow (see
  `icon.md`)
- [x] One rough edge per shipping screen — confirmed present on 3 of 4: the modem's "N
  bytes corrected" FEC count, the detector's/image-stego's verbatim `DetectionResult
  .detail` analyzer strings. The module picker has none, deliberately — a plain fixed
  3-row list has nothing to smooth over in the first place; forcing an artificial rough
  edge onto a screen that's already this minimal would be decoration, not honesty

No new violations were introduced by this task's own changes (the accent color, the
icon, the documentation edits above).

**Re-run 2026-09-21 (gate-9, gate-14 close-out): extended to Module 2 (audio
steganography, `AudioStegoScreen.kt`) and every v3–v5 addition** — the original pass above
predates all of them. Checked the same items against `AudioStegoScreen.kt` (including the
v5 "check for hidden data" detector wiring and its `DETECTOR_CAVEAT`/`JAR_PEEK_CAVEAT`
honesty lines), `CarrierInsightViews.kt` (the v5 difference/polarity views), and
`ui/FullscreenImageViewer.kt` (the owner-requested fullscreen viewer, shared by both
surfaces): no emoji, no ellipsis-suffixed status words, no "Got it!"-style affirmation (the
one literal hit is this doc's own KDoc citation of the rule, not a violation), no
`Brush`/gradient/blur, no card surfaces, no pure-white text, no mascot/carousel/"Powered
by"/Discover-style section, no version badge. `FullscreenImageViewer.kt`'s two small
`RoundedCornerShape` clips (a close glyph, a corner tap-affordance icon) are not cards and
carry no shadow — outside what the checklist's "no glassmorphism/no drop-shadow" items were
written to catch. Zero violations found; this closes gate-9's "anti-AI-tell checklist
re-run against the new screen" for Module 2, which was never previously recorded as done
(the 2026-08-03 pass above only covers the original four screens).

**Safety-scope re-check (gate-9/gate-14, INV-1):** grepped the full `app/src/main/java` and
`app/src/test/java` trees for exploit/malware-adjacent content — no hits beyond incidental
variable names (`c2` as a cosine-wave term, `Stage C2` internal task labeling). Every
`payloadText` default in production code is either empty or a `@Preview`-only sample
string (`"the ravens have landed"`, `"wet-snacks-design"`); the user always supplies the
real payload. INV-1 (synthetic/benign payloads only) still holds.

**Re-run 2026-09-22 (tappable-vs-static affordance): re-checked all items against the new
`ui/theme/TappableText.kt` primitive and its five call sites** (`ModulePicker.kt`,
`AcousticModemScreen.kt`, `ImageStegoScreen.kt`, `AudioStegoScreen.kt`,
`DetectorScreen.kt`):
- No shimmer/glow/bloom, no gradient, no shadow — `TextDecoration.Underline` is a
  static text-style flag, not a drawn effect; nothing in `TappableText.kt` touches
  `Brush`, `graphicsLayer`, or any shadow modifier.
- No new color — the primitive never sets `color`; every row's existing selected/
  enabled color logic (`TextPrimary`/`TextSecondary`) is untouched, confirmed by the
  `TappableTextTest`'s "only the decoration changes" case.
- No icon/vector asset — the affordance lives entirely inside `TextStyle`, no `Icon`
  composable, no drawable, no `Canvas` glyph added anywhere.
- No motion — the underline is present or absent per composition, no animated
  reveal, no `Crossfade`, no tween; no new `androidx.compose.animation` usage was
  introduced by this change.
- No card/glassmorphism — this change adds no new surface, only a text-style flag on
  existing plain `Text`/`Box` rows.
- Doesn't leak into jar mode — grepped every edited file's diff for `Jar` (functions,
  imports, colors); zero touches to `JarActionRow`, `JarCoverRow`, `JarFlowRow`,
  `JarSelectorRow`, `JarOptionRow`, `JarType`, or any `firefly-jar-identity.md` token.
Zero violations found. This is the first checklist re-run to cover the tappable-
affordance decision; nothing prior addressed tappability signaling at all.

---

## Change log

| Date | Change | Reason |
|---|---|---|
| 2026-08-03 | Initial identity.md — Task #4 | Module-picker + 3 stub screens shipped; default dark baseline documented as working, not final |
| 2026-08-03 | `themes.xml` parent changed from `Material.Light.NoActionBar` to `Material.NoActionBar` | Task #1 scaffold left a Light parent; that would have injected a white cold-start window |
| 2026-08-03 | Added `colors.xml` with `bg_base` | `themes.xml` referenced `@color/bg_base` with no color resource defined yet |
| 2026-08-03 | Added `android:enableOnBackInvokedCallback="true"` to the manifest | Needed for Android 14+ predictive back on the picker → stub → picker flow |
| 2026-08-03 | Task #7 activates `bodySmall`/`labelSmall` on the acoustic-modem screen (byte counter, "N bytes corrected" note) | First real-content screen past the picker/stub baseline; both slots already collapsed to sane values in `Type.kt`, no new type work needed |
| 2026-08-03 | Task #7 status words ("encoding", "transmitting", "listening", "decoding") kept as bare present-participles, no ellipsis, no capitalization | Judgment call against the anti-AI-tell checklist's "no Listening…/Processing… gerunds" rule — that rule targets the ellipsis-suffixed chatbot-loading-indicator pattern specifically; the orchestrator's own task brief named these exact state words. A quiet lowercase status word with no ellipsis and no exclamation reads as a terse system label (camera apps say "recording"), not an AI thinking-spinner. Revisit at Task #17 if it doesn't hold up. |
| 2026-08-03 | Still no accent color — Task #7's live states (encoding/transmitting/listening/decoding) render in TextPrimary/TextSecondary only, no color-coded "recording" state | Task #17 owns the accent decision; inventing one now for a single module ahead of that pass would be exactly the premature-polish this project keeps deferring |
| 2026-08-03 | Still no motion beyond default recomposition on the acoustic-modem screen — state swaps (idle→encoding→transmitting etc.) are instant | Considered a 200ms ease-out `Crossfade` on the status block but skipped it: `androidx.compose.animation:animation`'s transitive availability wasn't verified against this project's Compose BOM, and risking the build over a polish detail wasn't worth it under this task's 2-attempt cap. Revisit at Task #17. |
| 2026-08-03 | Task #10's detector screen (Module 5) uses the project's first deliberate monospace text — the `HH:mm:ss` timestamp column in the detection-history rows | Doctrine: "mono ONLY where mono carries information (timestamps, hashes, IPs, session names)." The confidence percentage next to it stays in the default type — mono wasn't extended there since it's not tabular/information-bearing the way a timestamp is. Applied via a one-off `.copy(fontFamily = FontFamily.Monospace)` on `labelSmall`, no new `Typography` slot added. |
| 2026-08-03 | Still no accent color on the detector screen — the flagged/clear live state renders in TextPrimary ("flagged") vs TextSecondary ("clear") only, no color, no glow, no pulse | Same restraint as Task #7's modem states: task #17 owns the accent decision project-wide. A detector's flagged state is exactly the kind of surface that tempts a red/amber alert color; the anti-AI-tell checklist's "no shimmer/glow/bloom on the primary readout" and "danger is real-destructive-only" rules both argue against reaching for one here — a flag isn't destructive, it's informational. |
| 2026-08-03 | Detector screen (Task #10) surfaces `DetectionResult.detail` verbatim when present (e.g. "sustained tone-grid energy: 4 on-grid bin(s) >= 15.0 dB over floor") | The one deliberate rough edge on this screen, mirroring Task #7's "N bytes corrected" note — real analyzer output shown as-is rather than translated into a smoothed status phrase. |
| 2026-08-03 | Still no motion on the detector screen — the live confidence percentage updates as instant text on each `AudioRecord` read (~85ms cadence), no animated meter/gauge, no tick/count-up animation | Same reasoning as Task #7's `Crossfade` decision: `androidx.compose.animation` availability wasn't verified against this project's Compose BOM, not worth risking the build under the 2-attempt cap for a polish detail. Revisit at Task #17 alongside the modem's deferred motion work. |
| 2026-08-03 | Task #13's image-steganography screen adds the project's first raw `Bitmap` preview surface (96x96dp, 1px `BorderDefault` box, no drop shadow) | New surface type, not a new token — reuses `BorderDefault` exactly as the text-field boxes already do. The preview shows the actual working image (cover or stego) at real device pixels; showing the same pixels whether or not a payload is embedded is itself the point (`ImageStegoCarrier`'s own KDoc: raw-pixel LSB "is not detectable by eye"), so no annotation or overlay was added on top of it. |
| 2026-08-03 | Still no accent color on the image-steganography screen — `embedded`/recovered-text/`flagged`-or-`clear` all render in TextPrimary/TextSecondary only | Same restraint tasks #7/#10 applied to their own live states; task #17 owns the project-wide accent decision. |
| 2026-08-03 | Image-steganography screen (Task #13) reuses the bare-present-participle status-word convention (`embedding`, `extracting`, `analyzing`) Task #7's changelog entry already settled for the modem's `encoding`/`transmitting`/`listening`/`decoding` | Same judgment call, applied consistently rather than re-litigated: no ellipsis, no capitalization, no exclamation — a terse system label, not a chatbot loading indicator. |
| 2026-08-03 | Image-steganography screen (Task #13) surfaces `DetectionResult.detail` verbatim (e.g. "sustained PoV-equalization run: 5/32 windows...") in its `check for hidden data` result, same as the detector screen | The one deliberate rough edge on this screen, mirroring Task #10's detector-detail precedent — real chi-square analyzer output shown as-is. |
| 2026-08-03 | No gallery picker on the image-steganography screen — two bundled sample covers (`stego_cover_gradient.png`, `stego_cover_mosaic.png`, both 100x100) stand in for one | Task brief's own escape hatch: `ActivityResultContracts.GetContent()` + `ContentResolver` stream handling + persisted-permission bookkeeping is real complexity for a research-demo screen that already has two representative covers bundled from task #11. Revisit if a future task wants arbitrary user photos. |
| 2026-08-03 | **Task #17.** Added `AccentSignal` (`#39C5CF`) to `Color.kt`. Applied to the modem's `transmitting`/`listening` status words and to the `flagged` word on both the detector and image-steganography screens. Not applied anywhere else. | Closes the project-wide accent decision every prior task explicitly deferred. See Palette § Accent decision above for full rationale (contrast math, color choice, why "success" text stays unaccented). |
| 2026-08-03 | **Task #17.** Ratified the bare-present-participle, no-ellipsis status-word convention (`encoding`/`transmitting`/`listening`/`decoding`/`embedding`/`extracting`/`analyzing`) as final. | Closes tasks #7/#13's "revisit at Task #17 if it doesn't hold up" note — it holds up. See Anti-AI-tell status above. |
| 2026-08-03 | **Task #17.** Decided: stay on system default font (Roboto), do not bundle Inter. | See Typography § font decision above — nightjar's screens are operational readouts, not prose; Inter would earn its keep on a body-copy surface this app doesn't have yet. |
| 2026-08-03 | **Task #17.** Decided: stay on instant state transitions everywhere, no `Crossfade`/tween added. Closes tasks #7/#10's "revisit at Task #17" motion notes. | See Motion section above — doctrine's own reduce-motion rule already treats instant as an acceptable baseline, not a compromise; no state change in this app has a moment a tween would clarify rather than decorate, and no `androidx.compose.animation` dependency exists in `build.gradle.kts` to add build risk for. |
| 2026-08-03 | **Task #17.** Shipped the app's first launcher icon — adaptive foreground + monochrome layers, 5 flat mipmap sizes, `mipmap-anydpi-v26` descriptors, `ic_launcher_background` color, and `android:icon`/`android:roundIcon` wired into `AndroidManifest.xml` (neither attribute existed before this task). | No icon existed anywhere in the tree; see `icon.md` for the 3-candidate concept exploration and the shipped pick's full rationale. Per doctrine the final pick is normally the user's call — flagged as a placeholder pick pending user override, not a closed decision, since this task ran as a single autonomous pass with no interactive checkpoint. |
| 2026-08-03 | **Task #17.** Ran the full anti-AI-tell checklist against all four screens (module picker, modem, detector, image steganography) for the first time as a single pass, not per-task spot checks. | See Anti-AI-tell status above — zero new violations found; every item confirmed or explicitly marked N/A with reasoning. |
| 2026-08-03 | **Task #27.** Acoustic modem's `listening` state now shows a live input-level readout (`input level N dB`, RMS dBFS, ~85ms update cadence) and a remaining-time countdown (`Ns left in listen window`) as two labelSmall/TextSecondary rows under the status word. `DecodedFailure` gained a `timedOut` flag, giving the `NO_PAYLOAD_FOUND` case a more specific message when the full 20s window elapses with nothing decoded ("no signal detected in 20s. move phones closer and confirm the other phone actually transmitted.") vs. an early manual stop (unchanged shorter message). | Real two-phone test feedback: the person running it had no way to tell whether "listen" was picking anything up, how long the window would run, or why it failed — the screen just sat there. Both additions are plain numeric text at the same throttled cadence the detector's live confidence readout already established (Task #10) — no meter, no gauge, no animation added; the accent/motion/palette decisions this task ratified are unchanged. |
| 2026-08-03 | **Task #28.** Added one line of static, first-run guidance text to all four screens, all labelSmall/TextSecondary, all present-always (not tied to a live state): (1) module picker — each of the 3 rows gained a one-line `description` under its label ("send text as sound, phone to phone" / "hide or extract text inside an image" / "continuously listens for the modem's signal"), rows changed from a fixed-height `Row` to a wrap-content `Column` to fit the second line; (2) acoustic modem — "works best within 1m, in a quiet room." under the title, sourced from architecture.md §7's AUDIBLE-protocol round-trip envelope (speaker→mic ≤1.0m, ambient noise <45 dBA), not a made-up number; (3) image steganography — one caption above the embed/extract/check row group explaining what each of the three verbs does; (4) detector — one line under the title explaining the confidence number is a live match score against the modem's own signal and that it runs continuously/passively (never decodes). | Real user feedback: the app wasn't usable for a first-time user — no idea what distance to use, what the byte counters meant, or what a confidence number implied. All four additions are plain static text, no dialogs, no onboarding carousel, no tooltip/popover — matches the "terse inline text, not tutorial overlays" instruction and the existing screens' own established micro-copy voice (bare lowercase sentences, no exclamation, real numbers over vague ones). None of Task #27's live listening-state additions (level readout, countdown) were touched or duplicated — the new modem note sits above the payload field, entirely separate from `ListeningBlock`. |
| 2026-09-21 | **Gate-9/gate-14 close-out.** Re-ran the full anti-AI-tell checklist against Module 2 (`AudioStegoScreen.kt`, including its v5 detector wiring), `CarrierInsightViews.kt`'s v5 difference/polarity views, and the owner-requested `FullscreenImageViewer.kt` — none of which the 2026-08-03 Task #17 pass above could have covered. Also re-checked INV-1 (synthetic/benign payloads only) against the full `app/src/main/java`/`app/src/test/java` tree. | Gate-9 has required this re-run since Module 2 shipped (v2 addition) but it was never actually recorded as done. Zero violations found on either check; see Anti-AI-tell status above for the item-by-item results. |
| 2026-09-22 | **Workshop tappable-vs-static affordance.** Added `TextStyle.withTapAffordance(tappable: Boolean = true)` (`ui/theme/TappableText.kt`) — a plain `TextDecoration.Underline`, reserved exclusively for enabled tappable row labels. Applied across all five technical screens: `ModulePicker`'s module rows (label only) + "back to the jar"/"start the trail again" links; `AcousticModemScreen`'s `ActionRow`/`SettingOptionRow` + "back"; `ImageStegoScreen`'s `ActionRow`/`CoverRow` + "back"; `AudioStegoScreen`'s `ActionRow`/`PlaybackVerb`/`SelectorRowWithInfo` (label + info toggle)/`SectionLabelRow` (info toggle) + "back"; `DetectorScreen`'s listen/stop `ActionRow` + "back". No new color token, no icon, no motion. Firefly Jar mode (`Jar*` composables) untouched — it already signals tappability with tinted fill/border per its own doctrine. | Owner report: users couldn't tell tappable text from static text on the technical screens — `ActionRow`'s enabled label and `StatusBlock`'s static result text shared the same `TextPrimary` color, and a disabled row already shared `TextSecondary` with static secondary captions. Underline was chosen over a trailing glyph (wrong semantics on the screens' many *selection* rows, which don't drill down) and over a new/reassigned color (would stack a third meaning onto color axes already carrying selection-state and enabled-state). Disabled rows deliberately get no distinct treatment beyond losing the underline — a disabled row genuinely isn't actionable right now, so reading identically to static text is honest, not a gap. See § Tappable-vs-static affordance above for the full rationale and the Anti-AI-tell status re-run this task closes out. |

---

## Verification (Task #17)

`cd /home/hercules/pixel6a/nightjar && ./gradlew assembleDebug` — **succeeded on the
first attempt** (`BUILD SUCCESSFUL`, 37 actionable tasks). `app-debug.apk` produced at
`app/build/outputs/apk/debug/`.

Real device screenshots were not captured: `hek adb-status` reported `ADB: NOT
CONNECTED` and a follow-up `hek adb-connect` scan timed out with no device found (Hek
appears to be asleep or off Wi-Fi at the time this task ran) — no Preview-screenshot
tooling (Paparazzi/Roborazzi/AGP Screenshot Testing) is configured in this project
either, so Compose `@Preview` composables could not be rendered to images directly.
Per this task's own instructions, build-level verification (`assembleDebug` compiling
every screen file, including all `@Preview` functions, without error) is what this
pass relied on for the code changes. The icon's legibility at 24/48/96/192 was
verified separately by rendering the actual shipped PNG asset and visually inspecting
it (`design/icon-concepts/review/candidate_a_legibility_24_48_96_192.png`) — a real
check of the shipped bytes, just not a live-device or Compose-renderer check.

---

## Verification (Task #28)

`cd /home/hercules/pixel6a/nightjar && ./gradlew assembleDebug` — **succeeded on the
first attempt** (`BUILD SUCCESSFUL`, 37 actionable tasks, `compileDebugKotlin` re-ran
and passed).

Hek was reachable this time (`hek adb-status` → `ACTIVE`). Installed the rebuilt
`app-debug.apk` (`adb-new install -r`, `Success`) and drove all four screens live via
`hek screen`:
- Module picker: 3 rows, each with the new one-line description under its label —
  dense, no overlap, no truncation.
- Acoustic modem: "works best within 1m, in a quiet room." renders directly under the
  title, well clear of the payload field and transmit/listen rows below.
- Image steganography: the embed/extract/check caption wraps to 2 lines above the
  three action rows, doesn't crowd them.
- Detector: the confidence explanation renders under the title, well clear of the
  `listen` row and the readout/history blocks below.

No clutter, no overlap, no truncation on any of the four screens at real device
resolution (1080x2400). Screenshots not committed to the repo (raw device captures
land in `~/pixel6a/captures/`, outside this project's tree, matching prior tasks'
practice of not vendoring capture PNGs into `design/assets/`).

---

## Verification (Workshop tappable-vs-static affordance, 2026-09-22)

`./gradlew --console=plain -q testDebugUnitTest assembleDebug` — **succeeded on the
first attempt.** `534` JVM unit tests ran, `0` failed (4 of them new, in
`TappableTextTest.kt`); `app-debug.apk` produced at
`app/build/outputs/apk/debug/`.

No Paparazzi/Roborazzi/AGP Screenshot Testing is configured in this project (unchanged
since Task #17's verification note), so — same as every prior visual task recorded in
this changelog — build-level verification (every screen file, including its
`@Preview` composables, compiling clean) plus the pure-Kotlin unit test on the shared
primitive is what this pass relies on. Not verified live on-device this pass; the
underline is a one-line, well-understood `TextStyle` change with no layout, sizing, or
z-order implications, and every row's existing color/enabled logic is provably
untouched (`TappableTextTest`'s "only the decoration changes" case) — the same
risk profile prior instant-motion and type-scale decisions in this file relied on
`assembleDebug` alone for.
