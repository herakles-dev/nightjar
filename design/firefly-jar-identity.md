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

- **Firefly blink:** each dot's alpha animates between 0.4 and 1.0 via
  `rememberInfiniteTransition` + `animateFloat(infiniteRepeatable(tween(...),
  RepeatMode.Reverse))`. Period randomized per dot in the 1.8–3.2s range, phase-offset by
  the dot's list index, so a jar's fireflies blink asynchronously rather than in unison —
  mimicking real, uncoordinated firefly flashing rather than a single pulsing UI element.
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

## Change log

| Date | Change | Reason |
|---|---|---|
| 2026-08-04 | Initial `firefly-jar-identity.md` — spec addition, Task #12 | New disguise-themed surface needs its own doctrine; identity.md stays authoritative for the technical screens underneath the long-press reveal. All values above are proposed starting points for the implementer/android-designer pass, same "placeholder pick pending user override" spirit `identity.md`'s own icon.md precedent uses — not locked. |
