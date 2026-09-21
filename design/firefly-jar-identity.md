# nightjar — Firefly Jar Visual Identity (v3 addition)
**Source of truth for:** the Firefly Jar surface only (`Screen.JarShelf`,
`Screen.JarDetail`) — `design/identity.md` remains the unchanged, authoritative doctrine
for the four technical screens (`Screen.Picker` and everything under it). This is a
sibling document, not a replacement.

---

## Status

The user's explicit direction (approved, see `spec.md` Boundaries § v3 addition): this
surface is a disguise — "a cute firefly collection app" — and identity.md's anti-AI-tell
checklist does not apply to it. That is a deliberate scope boundary, not an oversight:
`identity.md` stays fully in force for the technical screens underneath the long-press
reveal. Anyone touching this surface should read both documents, not just this one.

---

## Palette

A warm dusk-meadow palette, deliberately distinct from identity.md's cold GitHub-dark
scale — the two surfaces should not feel like the same app at a glance, since one is
disguising the other.

| Token | Hex | Compose name | Role |
|---|---|---|---|
| bg-dusk | `#161229` | `JarBgDusk` | Screen background — a deep night-sky indigo, warmer/purpler than `identity.md`'s `#0D1117` |
| bg-horizon | `#2A2450` | `JarBgHorizon` | Soft gradient toward a warm horizon glow at the bottom of the shelf/detail screens — the one deliberate departure from identity.md's flat-fill-only rule |
| text-primary | `#F5E6C8` | `JarTextPrimary` | Wordmark, jar names, firefly-detail text — a warm parchment tone |
| text-secondary | `#9B8FBF` | `JarTextSecondary` | Captions, "back" affordance, timestamps |
| **created-glow** | **`#FFC857`** | **`FireflyCreated`** | **A firefly you caught (embedded/transmitted)** |
| **received-glow** | **`#39C5CF`** | **`FireflyReceived`** | **A firefly you spotted (extracted/decoded)** — reuses `identity.md`'s `AccentSignal` value on purpose: the technical app's "the covert channel is live" cyan and this surface's "a message arrived" cyan are the same real event, seen through two skins |
| jar-glass | `#D9C9A3` at 35% alpha | `JarGlassOutline` | The jar silhouette's outline/glass — translucent, unlike identity.md's opaque 1px borders |
| watching-dim | `#6B6690` | `JarWatchingDim` | The watching jar's tile + its passive/no-firefly-dots treatment |

**Contrast, verified (Task #10):** `JarTextPrimary` `#F5E6C8` on `JarBgDusk` `#161229` is
13.6:1 — clears WCAG AAA (7:1). `JarTextSecondary` `#9B8FBF` on `JarBgDusk` is 6.1:1 —
clears WCAG AA (4.5:1) with margin, the bar this doc's own § Responsibilities table sets.
Both computed via the standard relative-luminance formula, same method `identity.md`'s
`AccentSignal` decision used.

### Sky gradient, revised 2026-08-04 (design package)

The two-stop `bg-dusk` → `bg-horizon` fill is superseded on the jar surface by a
four-stop sky, deeper at the zenith and warmer at the horizon:

| Stop | Hex | Compose name |
|---|---|---|
| 0% | `#060610` | `JarSkyZenith` |
| 40% | `#0A0A22` | `JarSkyUpper` |
| 70% | `#141035` | `JarSkyLower` |
| 100% | `#1E1548` | `JarSkyHorizon` |

`JarBgDusk`/`JarBgHorizon` are retained as constants but are no longer the jar
background. A third text tier arrives with the package:

| Token | Hex | Compose name | Role |
|---|---|---|---|
| text-tertiary | `#897FAA` | `JarTextTertiary` | Subtitles under titles, section labels, tile status captions, byte counters |

**Contrast re-verified against the new sky, and two tiers were corrected.** Worst case is
the `#1E1548` horizon stop at the bottom of the screen:

| Token | On `#060610` | On `#1E1548` | Verdict |
|---|---|---|---|
| `JarTextPrimary` `#F5E6C8` | 16.4:1 | 13.6:1 | clears AAA |
| `FireflyCreated` `#FFC857` | 13.1:1 | 10.9:1 | clears AAA |
| `FireflyReceived` `#39C5CF` | 9.7:1 | 8.0:1 | clears AAA |
| `JarTextSecondary` `#9B8FBF` | 6.8:1 | 5.6:1 | clears AA |
| `JarTextTertiary` `#897FAA` | 5.4:1 | 4.5:1 | clears AA — **corrected**, was `#7B6FA0` at 3.7:1 |
| `JarWatchingDim` `#8581A5` | 5.4:1 | 4.5:1 | clears AA — **corrected**, was `#6B6690` at 3.1:1 |

As drawn, both tiers failed: 3.7:1 and 3.1:1 against the horizon stop. The 3:1 large-text
exemption rescues neither, since both are used at 7–8sp. This was confirmed on device
before changing anything — the worst case was the acoustic catch screen's unselected
protocol/symbol-rate options (`near-ultrasonic`, `fast`), which in the tertiary tier were
barely legible as options at all, alongside `clear history` on the shelf.

The correction raises lightness while holding the package's hue and saturation exactly, so
the palette reads unchanged and only the dim text gains legibility. Deliberately **not**
corrected: `JarGlassWatching` and `JarRadarSweep` keep the original `#6B6690` base — they
are sub-25%-alpha decorative strokes rather than text, carry no contrast obligation, and
lightening them would visibly change how the watching jar reads.

Related and still open: the package's caption sizes are 7–8px read straight across to sp.
On device Silkscreen's blocky glyphs hold up better at that size than a proportional face
would, and the tiers above are legible post-correction — but 7sp remains small, and it's
the next thing to revisit if anyone reports strain.

**Reversed from identity.md, explicitly:**
- Gradient backgrounds — allowed (`bg-horizon`), was banned.
- Glassmorphism-adjacent translucent surfaces — allowed (`jar-glass`), was banned ("no
  cards at all, in fact").
- A defined success/positive-event color — `FireflyCreated`/`FireflyReceived` fire on
  every successful catch/spot, not reserved for one rare "live channel" moment the way
  `AccentSignal` is. identity.md's "no success color, silence is success" rule does not
  hold here on purpose: a firefly appearing *is* the success feedback, and it should
  read as one warmly, not silently.

---

## Typography

> **Superseded 2026-08-04 by the "Cozy Pixel Night" design package.** The surface now
> uses **Silkscreen** (a pixel display face, OFL), vendored at `res/font/`. The original
> reasoning is kept verbatim below because it was a real call with real tradeoffs, and
> the override should read as a decision rather than an accident — this doc's own change
> log invited it ("proposed starting points … not locked").
>
> What changed the answer: the design package doesn't use a display font for decoration,
> it uses one as the *entire* identity — the pixel face is what makes the disguise read
> as a cozy little collection game rather than a themed utility. That is a typographic
> voice the platform default genuinely cannot produce, which is precisely the bar the
> paragraph below sets. Asset cost came in at ~62 KB for both weights.
>
> **Scope of the reversal:** the pixel face is bound to the jar surface only, via
> `JarType` in `ui/theme/Type.kt`. `NightjarTypography` — the shared M3 typography that
> everything under the long-press reveal resolves through — is untouched and still
> Roboto at 400/500/600. identity.md's typography rule is not relaxed by this entry.
>
> Silkscreen ships with weights 400 and 700 only; the 300-Light option the paragraph
> below opens up is therefore not available on this surface anymore.
>
> **Sizes are ours, not the package's (revised on device, 2026-08-04).** The package's
> numbers are CSS px from a 375×812 design canvas. Reading them straight across to `sp` is
> geometrically defensible — that frame is about a phone's dp box — but it shipped captions
> at 7–8sp, and on a real Pixel 6a that is unreadable. The mockup was reviewed on a desktop,
> where the same values look comfortable. Nobody's error but the port's.
>
> The ramp is rebuilt around one fixed point: **`ActionTitle` at 16sp** ("catch a firefly",
> "look for fireflies"), the only size confirmed correct on device. Everything else is
> proportioned to it, which roughly doubles the small tiers:
>
> | Tier | Size | Roles |
> |---|---|---|
> | micro | 12sp | tile captions, screen subtitles, footers, meta labels, byte counters |
> | small | 13sp | back links, section labels, wordmark subtitle, timestamps |
> | base | **16sp** | tile titles, action titles, body copy, metadata values |
> | button | 18sp | primary button labels |
> | title | 24sp | detail-screen titles |
> | display | 28sp | the wordmark |
> | numeral | 36sp | the watching jar's live confidence figure |
>
> One structural bug fell out of the 1:1 port and is fixed here: `TileTitle` was 10sp while
> `ActionTitle` was 16sp, even though both are the primary label of a full-width tappable
> row. The shelf read as a weaker surface than the detail screens for no reason. They are
> now the same size, and that is the rule — a tappable row's primary label is 16sp wherever
> it appears.
>
> Tracking scales with the tier rather than holding the package's absolute px, so
> letter-spacing reads the same relative to the glyphs at every size.

**Staying on system default font (Roboto)** — same call identity.md made for the
technical screens, same reasoning: bundling a display/storybook font adds real asset
weight and a font-loading path this addition doesn't need to take on when weight, size,
letter-spacing, and italics on the existing system font already carry a warmer register.
This is the "easily managed" choice, consistent with architecture.md § Firefly Jar's
dependency reasoning (Room and Compose Animation are added because they're the standard
tool for the job; a bundled font is not — nothing here needs a specific typographic
voice the platform default can't already produce).

Weight range opens up beyond identity.md's 400/500/600-only rule: the wordmark and jar
names may use 300 (Light) for a softer feel, and short flavor lines may use italic —
both explicitly disallowed on the technical screens (300 was banned as "fragile on
OLED"; that reasoning was about the technical screens' small operational-readout text,
not this surface's larger display type).

---

## Motion

**Explicitly allowed here** — the one identity.md rule most worth naming as reversed,
since "instant everywhere" was identity.md's most emphatic, most-recently-ratified
decision (Task #17). Concrete, bounded motion, not "animation everywhere":

- **Firefly blink:** ~~each dot's alpha animates between 0.4 and 1.0 via
  `rememberInfiniteTransition` + `animateFloat(infiniteRepeatable(tween(...),
  RepeatMode.Reverse))`. Period randomized per dot in the 1.8–3.2s range, phase-offset by
  the dot's list index~~ — **revised 2026-08-04.** The design package drives every
  firefly from a single continuous frame clock (`withFrameNanos`) rather than named
  keyframe transitions: position comes from six layered sine/cosine terms and alpha from
  a soft-cubic shaping of a per-id phase (period `3.5 + (id%5)*0.6`s, range 0.05–0.65).
  The *intent* below is unchanged and is in fact what the package achieves more directly
  — fireflies drift and breathe asynchronously, uncoordinated, never in unison. What
  changed is that they also **move**, which keyframed alpha alone could not do. Exact
  formulas live in `DESIGN_SPEC.md` §4.3; the implementation is `FireflyGlyphs.kt`.
- **Screen transitions:** `JarShelf` ↔ `JarDetail` cross-fades (`AnimatedContent` or
  `Crossfade`, ~250ms) — allowed here, unlike identity.md's hard "no `Crossfade`
  anywhere" rule, because this surface's whole premise is a softer, less-instant feel.
- **Long-press reveal:** a brief (~200ms) fade-through on the transition into
  `Screen.Picker`, so the reveal doesn't feel like a hard mode-snap.
- Everything above uses `androidx.compose.animation`/`animation-core` — the official,
  well-documented Jetpack library (architecture.md § Firefly Jar § 2), not hand-rolled
  frame timers.

**Not** proposed: continuous ambient background motion (a drifting gradient, particle
effects beyond the firefly dots themselves) — a stretch idea, not required for gate-12/
13, and worth resisting until the bounded version above is built and looked at on a real
device.

---

## Anti-AI-tell status: intentionally not applied

Every item below is a deliberate reversal of `identity.md`'s checklist for this surface
specifically, recorded here so it reads as a documented decision rather than a missed
check:

- **Emoji** — permitted, though custom-drawn firefly/jar glyphs (`FireflyGlyphs.kt`) are
  the recommended default over generic emoji glyphs; a crafted asset still reads as more
  intentional than a 🔥/✨ pasted in, even with the rule relaxed.
- **Gradients** — permitted (`bg-horizon`, § Palette).
- **Glassmorphism / translucent surfaces** — permitted (`jar-glass`).
- **Shimmer/glow/bloom** — the entire point of this surface; every firefly dot is a
  glow.
- **Motion** — permitted, bounded (§ Motion above).
- **Data visualizations — always permitted, not a reversal at all.** The image bit-plane
  toggle, the audio spectrogram, and the v5 cover-vs-stego difference and L/R polarity views
  (`JarDetailScreen.kt`, `CarrierInsightViews.kt`, gate-19/24/25) render a real measurement
  of the actual firefly's carrier, not themed chrome — a different category from every
  reversal above, licensed by what this surface already does rather than by anything relaxed
  here: showing someone what a carrier's data literally contains was never what identity.md's
  checklist was written to prevent.
- **Success-affirmation copy** — softened, cute copy ("you caught one — N bytes") is
  permitted where identity.md would require a bare fact statement; still no
  exclamation points or checkmark glyphs (screen-flow.md § Screen 7 copy table) — warmth
  without becoming a congratulatory toast.
- **Still not permitted, unchanged from identity.md's reasoning even here:** an
  illustrated mascot, a bottom-sheet onboarding carousel, "Powered by X" badging,
  "Discover/Trending" sections — none of these serve this surface's actual premise
  either; relaxing the doctrine was scoped to what the firefly theme specifically needs
  (glow, motion, warmth), not a blanket "add anything cute" license.

---

## System bar treatment

Same edge-to-edge approach as identity.md (`enableEdgeToEdge()` +
`windowInsetsPadding(WindowInsets.systemBars)`), status/nav bar color set to
`JarBgDusk` (`#161229`) while `Screen.JarShelf`/`Screen.JarDetail` are active, swapping
to identity.md's `#0D1117` the instant `Screen.Picker` is reached — the system chrome
itself should shift with the disguise, not stay a fixed color across both surfaces.

---

## v6 addendum: guidance highlights, practice fireflies, send/receive tone, the meadow tile

Scope: spec.md's v6 addition (sharing + first-use riddle trail), gates 33/36/38/39. The
trail's own step-by-step content (which jar, which riddle, which action glows) lives in the
new sibling doc `design/riddle-trail.md` — this addendum is where that trail's *visual
mechanics* belong, since they're palette/motion-level decisions this doc already owns, plus
the meadow tile's redesign and the tone rules for the new send/receive copy.

**Naming note:** as of the owner's 2026-09-21 direction, "the framed jar" is "the art jar" and
"the watching jar" is "the meadow" everywhere below and throughout this addendum — see
`screen-flow.md`'s v6 section for the full rename note. The body of this document above this
addendum (§ Palette, § Typography, § Motion) still uses the pre-rename names in its own
historical examples and is left as-is, same reasoning `screen-flow.md` gives for not
retro-editing its own Screen 6/7 sections.

### Guidance-highlight rules (gate-38)

The trail needs to mark "the one next thing to try" without inventing a second visual
language on top of the one this doc already defines. Every rule below is a *reuse*
instruction, not a new primitive:

- **Which primitive, by surface:**
  - **Shelf tile** (pointing at a whole jar, e.g. "try the humming jar"): reuse
    `drawAmbientHalo` (`FireflyGlyphs.kt` :160) exactly as it already renders behind a jar that
    holds fireflies — soft radial gradient, `glowColor` at low opacity fading to transparent.
    The one deliberate exception: `drawAmbientHalo`'s existing rule is "callers with zero
    fireflies never reach this function" (its own KDoc, § 4.4: "no halo div at all"). Trail
    guidance is a second, explicit caller of the same function for the *target* tile
    specifically, regardless of whether that jar holds a firefly yet — the halo is doing a
    different job here (pointing, not indicating fullness), so it earns being called outside
    the rule written for the other job. Color: `FireflyCreated` (the same amber every "catch/
    embed" moment already uses) — never a new "guidance" hue.
  - **Action row** (pointing at a specific verb, e.g. `look for fireflies`, `watch`, `send
    this firefly`): reuse `fireflyAlpha` (`FireflyGlyphs.kt` :131) — the same soft-cubic
    breathing curve every firefly dot already animates on — applied to that row's existing
    fill/border opacity (`JarActionLookFill`/`JarActionLookBorder` for a look/watch target,
    `JarActionCatchFill`/`JarActionCatchBorder` for a catch/send target) instead of those
    tokens' normal fixed alpha. Call `fireflyAlpha(id = a fixed guidance id, t = clock)` with
    the shared frame clock every other firefly already reads from
    (`rememberFireflyClock()`/`withFrameNanos`, § Motion above) — not a new
    `rememberInfiniteTransition`, not a new tween.
  - **Wordmark subtitle** (the one-line "what's next" hint, `design/riddle-trail.md` § Wordmark
    hint): plain text swap, `JarType.WordmarkSubtitle`/`JarTextTertiary` — the same style the
    line already renders in. No glow on the text itself; the glow lives on the tile/row the
    text is pointing at, not on the words describing it.
- **One target at a time:** exactly one shelf tile OR one action row glows at any moment,
  never both simultaneously and never more than one of either — this is what "one next step
  glows at a time" (gate-38's own phrasing) means mechanically. The shelf shows a tile glow
  when the user is looking at the shelf and the current step's jar isn't open yet; the moment
  they open that jar's detail screen, the glow moves from the tile to the action row inside
  it (the shelf tile stops glowing the instant its detail screen is the one on top — it isn't
  drawn a second time on the way back either, once that step is done).
- **Stops on tap:** the moment the user taps the glowing target (or, for the meadow step, the
  moment a real detection fires — `design/riddle-trail.md` § Step 4), the glow for that target
  is removed immediately, not faded out — an instant state change, consistent with this app's
  standing motion doctrine (identity.md's "instant, not eased-out" rule, § Motion above: "the
  transition IS the feedback," not a decorated one) even though the breathing animation itself
  is an explicit, documented exception to that doctrine.
- **Static under reduce-motion:** when the system's reduce-motion / disable-animations flag is
  set, `fireflyAlpha`'s continuous breathing collapses to its own fixed midpoint (the function
  already ranges `0.35 ± 0.3`; render at a flat `0.5` — roughly the loop's time-average, not
  its peak, so a static highlight doesn't read louder than the moving one ever did) rather
  than disappearing. A highlight that vanishes under reduce-motion isn't accessible guidance,
  it's guidance that silently stops existing for exactly the users most likely to need a
  motion-free interface — this is the specific failure mode identity.md's own reduce-motion
  rule was written to prevent ("collapse to instant transitions; never 'ease-out but shorter'
  as a compromise" — the same "never quietly remove the affordance" spirit, applied here to a
  highlight instead of a screen transition).
- **TalkBack:** every glowing element's existing `contentDescription`/semantics gains one
  appended clause naming the guidance context — not a second, separate announcement. E.g. the
  humming jar's shelf tile: `"the humming jar, 0 fireflies"` (existing) becomes `"the humming
  jar, 0 fireflies, next step in the trail"` while it's the glow target; `look for fireflies`
  becomes `"look for fireflies, next step in the trail"`. Closes gate-38's "every highlight ...
  [has] TalkBack descriptions" requirement without inventing new semantics nodes.

### Practice-firefly labelling

A practice firefly is visually identical to a real one everywhere it appears as a dot (shelf
tile, swarm) — no special color, no border, no badge on the glyph itself; the whole point of
INV-11 is that it's a *real* firefly, caught through the *real* decode path, and treating it as
a lesser or differently-drawn object on the shelf would undercut that. The one place it
declares itself is the **firefly detail popup**, where a small caption appears above the
existing metadata row, using the existing `JarTextTertiary` tier (the same one "your fireflies"
/ tile captions already use — no new color):

```
a practice firefly from the trail
```

(`trail_practice_label`, `design/riddle-trail.md` § strings.xml keys). This sits above the
timestamp/channel `MetaCard` row, never replacing it — a practice firefly still shows a real
timestamp and a real channel, because it genuinely was caught at that time through that
channel. Release works identically to any other firefly (`hold one to let it go` / `let this
firefly go`, unchanged) — no separate "release practice fireflies" affordance exists, per
`design/riddle-trail.md` § Release practice fireflies.

### Send/receive tone

New copy this addition introduces (the five receive outcomes, the two send flows —
`screen-flow.md`'s v6 section has the full tables) follows the exact same voice contract every
prior addition already holds itself to, with one specific note worth stating rather than
assuming: **"hidden, not locked" is doing real work, not decoration.** Spec.md's own boundary
list is explicit that nightjar has no encryption or passwords (v6, out of scope: "a firefly is
hidden, not locked, and the copy says so") — so this phrase isn't a cute flourish, it's the one
sentence in the send flow that keeps a user from assuming a caught firefly is private to
whoever decodes it. It appears exactly once per send interaction (not repeated per screen), in
`JarTextTertiary`, same weight as every other static advisory line this app already uses
(Task #28's precedent: "static, always-visible, real-number-over-vague-copy"). The
`squeezed` outcome's copy is the other tone-sensitive case worth naming directly: it names the
sending app when the intent makes that recoverable, but it is never framed as that app's
*fault* ("X squeezed this" states a fact about lossy transport, not a complaint) — matches
this doctrine's existing "a decode failure isn't destructive" restraint, extended to a failure
that happens to be a different app's doing rather than the user's own mistyped payload.

### The meadow tile — dropping the jar glass

**Recommendation: drop the jar-glass silhouette for the meadow tile specifically, replacing it
with a low grass-line horizon and a handful of `distantBlink`-driven faint dots hovering above
it — reusing `JarNightSky.kt`'s existing distant-firefly primitive (`distantBlink`, :119)
rather than drawing new motion.**

Reasoning:

1. **Grammar follows the rename.** "The meadow" read against a dimmed jar silhouette doesn't
   parse — a jar is a container; a meadow is the opposite of one. The tile's own shape should
   agree with what the tile is now called, the same way `screen-flow.md`'s copy-audit work
   makes sure the *text* agrees.
2. **It draws `JarRole.WATCHING` more honestly than a jar ever could.** The current
   `drawWatchingJar` (`FireflyGlyphs.kt` :260) is a dim, glass-outlined jar with a radar sweep
   — but a jar, even an empty and dimmed one, still visually promises "a container that could
   hold something." The meadow never can (spec.md: "it stays a passive one-way analyzer ...
   its existing flagged-detection history is re-skinned, not modeled as a firefly"). An open
   field with no vessel at all is the more accurate picture of "notices, never keeps."
3. **It reuses a primitive instead of inventing one.** `distantBlink` already exists,
   already implements the "two-flash cycle ... period varies per firefly so the field never
   pulses in unison" behavior (its own KDoc, verbatim from `DESIGN_SPEC.md` §4.2), and already
   renders in the exact `FireflyCreated`/`FireflyReceived` alternation the background starfield
   uses. Reusing it for the meadow's up-close tile makes a genuine thematic point — the meadow
   watches the same dark, open space the whole shelf's background already is, magnified,
   rather than a differently-themed enclosure of its own. This is squarely inside this doc's
   own § Anti-AI-tell status allowance ("data visualizations ... render a real measurement,"
   extended here to "reused motion primitive," not new decoration) rather than a new glow
   invented for this one tile.
4. **It keeps the same restraint the rest of the shelf holds itself to.** This is a horizon
   line and a few dim, unsynchronized blinks — not an illustrated landscape, not a mascot
   scene. The bar stays "one shape, quiet," same as every jar silhouette already is.

**What stays:** `JarWatchingDim`/`JarGlassWatching`'s cool lavender-gray family remains the
meadow's own accent — the grass line itself, and the tile's border/fill tint
(`JarTileFillDim`/`JarTileBorderWatching`, unchanged), keep using that palette so the tile
still reads as the one dim, "asleep until tapped" surface on the shelf, distinct from the three
warm/lit creating jars. Only the *shape* changes — the color language that already distinguishes
"watching" from "creating" doesn't need to.

**What this doesn't decide:** the exact geometry (how tall the grass line sits in the tile's
box, how many distant blinks render, whether the radar-sweep glyph is dropped entirely or kept
as a secondary "actively watching" cue layered over the new field) is asset/implementation
work for `FireflyGlyphs.kt`'s eventual `drawMeadow` replacement, not fixed by this
recommendation. The one hard constraint this addendum does set: whatever replaces
`drawWatchingJar` must still fit the same call-site box every other jar glyph fits (the 56×68
viewBox convention at shelf scale, the larger detail-hero scale on `JarDetailScreen`), so the
shelf's row layout doesn't need touching for this one tile.

### Change log

| Date | Change | Reason |
|---|---|---|
| 2026-08-04 | Initial `firefly-jar-identity.md` — spec addition, Task #12 | New disguise-themed surface needs its own doctrine; identity.md stays authoritative for the technical screens underneath the long-press reveal. All values above are proposed starting points for the implementer/android-designer pass, same "placeholder pick pending user override" spirit `identity.md`'s own icon.md precedent uses — not locked. |
| 2026-08-04 | **"Cozy Pixel Night" design package integrated** — sprint-09, tasks #10–#15 | The user-supplied design-team package (`firefly-app-design-refresh`, v1 explored three directions, v2 committed to "Cozy Pixel Night" across nine screens) is the "user override" the row above anticipated. Four reversals, each recorded in place: **(1) Typography** — Silkscreen replaces system Roboto on this surface only; § Typography. **(2) Palette** — two-stop sky becomes four-stop, `JarTextTertiary` added; § Palette. **(3) Motion** — bounded keyframe blink becomes a continuous parametric frame clock that also moves the fireflies; § Motion. **(4) Contrast** — two dim text tiers failed the AA bar this doc sets; confirmed on device, then corrected by lightness alone with the package's hue/saturation held. Extracted spec: `sessions/nightjar/artifacts/design-refresh/DESIGN_SPEC.md`. Notably the package stayed on-palette: `#FFC857`, `#39C5CF`, `#F5E6C8`, `#9B8FBF`, `#6B6690` and `#D9C9A3` were already this doc's tokens. |
| 2026-09-21 | Data-visualization allowance made explicit — docs close-out (v5 review) | `JarDetailScreen.kt`/`CarrierInsightViews.kt` code comments had been citing a "data visualization, not decoration" framing as if quoted from this doc; the phrase was never actually here. Added a bullet to § Anti-AI-tell status saying so directly, so the citation is now true rather than paraphrase. |
| 2026-09-21 | **v6 addendum** — sharing + riddle trail, W0-B | Added § Guidance-highlight rules (reuses `drawAmbientHalo`/`fireflyAlpha`/the shared frame clock for the trail's "one next step glows" mechanic — no new primitive), § Practice-firefly labelling (one caption in the detail popup, existing release gesture, no new glyph treatment), § Send/receive tone ("hidden, not locked" is a real disclosure, not flavor text; `squeezed` copy names the transport, not a fault), and § The meadow tile (recommendation: drop the jar-glass silhouette, reuse `JarNightSky.kt`'s `distantBlink` for an open-field treatment instead of a bespoke new glow). Full step-by-step trail content lives in the new sibling doc `design/riddle-trail.md`, not here. Reflects the owner's "the art jar"/"the meadow" rename (2026-09-21, landed directly on `feat/v6-sharing-trail` commit `f743f42`) throughout. |
