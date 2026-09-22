# nightjar — Riddle Trail (v6 addition)

**Source of truth for:** the first-use riddle trail (spec.md v6 Boundaries, INV-11, gates
36–38). Companion docs: `design/screen-flow.md` (send/receive wireframes, the trail's shelf
and detail-screen layout) and `design/firefly-jar-identity.md` (the guidance-highlight rules
that govern how "one next step glows" is actually drawn — this doc says *what* glows and
*when*, that doc says *how*).

**Naming note:** this doc uses the current jar names throughout — "the art jar" (image
steganography, formerly "the framed jar") and "the meadow" (the detector, formerly "the
watching jar") — per the owner's 2026-09-21 rename, already landed on `feat/v6-sharing-trail`
commit `f743f42`. This worktree's own checked-out code (based on `37e2e22`) still says
"the framed jar"/"the watching jar" internally; nothing here depends on that lagging.

---

## Why this order, not the shelf order

Shelf order (top to bottom, `Module.entries`, gate-39): **singing, art, humming, meadow.**
That order exists to put the three creating jars ahead of the one that only watches — it's a
display convenience, not a lesson plan, and nothing requires the trail to walk it in the same
sequence.

The trail order below is **art → humming → singing → meadow → send → workshop** — the same
relative shape the spec's own suggestion used (framed → humming → singing → meadow → send →
workshop), carried forward under the new name. Reason it doesn't match the shelf: **the
meadow's whole reason to exist is that it flags the singing jar's own signal** (INV-4 — "the
detector can identify the app's own Module 3 transmission"; `AcousticDetector`'s KDoc: "scores
whether nightjar's own Module 3 FSK tone signature ... is present"). The meadow has no thesis
about images or audio-stego at all — it is deaf to both. Putting singing immediately before
meadow lets the fourth step say, truthfully, "that same sound you just heard — the meadow can
hear it too," instead of asking the user to recall a lesson from two jars ago. Art and humming
go first because they're the two techniques a receiving-and-sending user is statistically more
likely to actually use (photos and voice clips travel through messaging apps; two-phone
acoustic transfer in the same room is the rarer case), and because each of their catches is a
simple tap with no timing pressure — good first reps before the meadow's more
detection-shaped, less-instant fourth step.

---

## What "catch" means in the trail (a design decision, stated once)

Every practice firefly is **pre-embedded**, not typed in live by the user. This isn't a
convenience — it's required by INV-11 ("no riddle text is shown from anywhere but a successful
decode"). If the trail let the user see the riddle text in a payload field before encoding it,
the riddle would already be spoiled before any decode ran. So for all three creating jars, the
trail's one interaction is **`look for fireflies`** (the existing extract/decode verb each jar
detail screen already has) — never `catch a firefly` (embed). The riddle is already sitting in
a real carrier (a bundled asset, produced once by the production encoder, analogous to how the
bundled sample cover images/clips already ship in the tree) the moment the app is installed or
the trail is restarted; tapping `look for fireflies` runs the real, unmodified decode path over
it and the riddle text appears exactly the way any other decoded firefly's text would.

This also settles the "does `catch a firefly` (embed) get taught?" question: yes, but not
inside the three creating-jar steps — it's taught for real at the **send** step, where `hide
one in a photo` is a genuine, live encode using the user's own picture. Teaching decode three
times and encode once (with a real payload, not a canned one) is a better ratio than teaching
both three times each — the trail's job is orientation, not exhaustive coverage, and the
workshop is still sitting right there afterward for anyone who wants to try `catch a firefly`
on Module 2 or Module 3 directly.

The **singing jar** needs one more note: its normal `look for fireflies` is a live speaker→mic
round trip. The practice firefly does **not** require this — the bundled asset is a WAV, and
the jar-mode `look for fireflies` verb decodes it directly (the same "feed an existing signal
to `decode()`" path the technical screen's `import` action already establishes for Module 3 —
screen-flow.md Screen 2, Tasks #31/#32/#36 drift note), not a live capture. No sound plays, no
mic opens, for this one step. The very next real tap of `look for fireflies` on that same jar
(after the practice firefly is caught) goes back to being the real thing — live listen, same as
today. This is a design decision for the implementer to confirm is buildable as scoped, not
something this doc can verify itself.

---

## Step sequence

| # | Jar / screen | Glows next | Carrier + technique | Completion |
|---|---|---|---|---|
| 1 | the art jar | `look for fireflies` row | Module 1, **exact** (raw-pixel LSB) | extract succeeds, riddle shown |
| 2 | the humming jar | `look for fireflies` row | Module 2, **spectrogram-LSB** | extract succeeds, riddle shown |
| 3 | the singing jar | `look for fireflies` row | Module 3, acoustic FSK, decoded from a bundled WAV (no live listen) | decode succeeds, riddle shown |
| 4 | the meadow | `watch` row | — (meadow holds no fireflies) | real flagged rising edge, or honest skip |
| 5 | firefly detail → send | `send this firefly` row | the art jar's practice firefly | share sheet opens |
| 6 | the shelf wordmark | `hold to open workshop` hint | — | long-press reveals `Screen.Picker` |

---

## Step 1: the art jar

**Technique:** Module 1's **exact** technique (raw-pixel LSB — `ImageStegoCarrier.kt`: one
least-significant bit in each of R, G, B per pixel, alpha untouched, a 7-byte header
(magic `0x4E`, version, 4-byte length, header CRC-8) and a 4-byte payload CRC-32 trailer). Not
the new sturdy technique — sturdy's whole point is surviving recompression on the way *out* of
the app, which has nothing to teach about a payload that never leaves the device. Exact is
also the simplest technique to show visually (the bit-plane toggle already exists and is the
cleanest "here's literally where it hid" demonstration in the app).

**Riddle payload** (142 bytes UTF-8, budget ≤ 200):
> i live in the last bit of red, green, and blue, never alpha, never loud enough to see. tip
> the bit-plane and find me. next, a secret in sound.

**Plain gloss** (ordinary UI copy, not part of the carrier, no byte budget):
> hidden one bit at a time in the image's own colors. open the bit-plane view to see the
> layer where it hides.

**Glows next:** the `look for fireflies` action row on the art jar's detail screen (existing
row, existing verb — see `design/firefly-jar-identity.md` v6 addendum for the highlight
treatment itself).

**Completion condition:** the user taps `look for fireflies`; `ImageStegoCarrier.decode()`
succeeds against the pre-loaded working image; the riddle text renders in the firefly detail
the same way any decoded text does. The glow moves to the humming jar.

---

## Step 2: the humming jar

**Technique:** Module 2's **spectrogram-LSB** (`AudioStegoCarrier.kt`: QIM on log-magnitude,
1024-sample frames, eligible bins starting at bin 32 — 1500 Hz exactly at the app's 48 kHz
sample rate, i.e. "above 1.5 kHz"). Chosen over phase-inversion and MFSK for this step because
it's the highest-capacity of the three on the bundled 5 s covers (457 bytes vs. 51 and 37 —
`AudioStegoScreen.kt`'s own `AudioStegoScreenTest` KDoc), the richest one to visualize (the v5
spectrogram + difference view both exist for it already), and it's the technique gate-19/26's
honesty work is actually about — the riddle gets to make a true, specific claim the other two
techniques can't back up as cleanly.

**Riddle payload** (80 bytes UTF-8, budget ≤ 80):
> above 1.5 khz, i nudge how loud a bin looks. silence gives me away. now, a song.

**Fallback riddle** (34 bytes UTF-8, budget ≤ 36 — for the bundled cover/strength combination
that lands closest to spectrogram-LSB's floor once W1-5 actually measures it; swap in if the
primary doesn't fit):
> silence gives me away. try a song.

Both versions stay honest about the one thing gate-19/26 fought to get right: the nudge itself
is sub-perceptual, but a silent stretch of the cover isn't — the codec has to add real,
visible brightening there because there's nothing to nudge (`CarrierInsightViews.kt`'s own
caption language, "where the cover was silent there was nothing to nudge, so it had to add
faint ..."). "silence gives me away" is that fact in five words, not an invented one.

**Plain gloss:**
> hidden in the loudness of frequencies above 1.5 khz. the difference view shows exactly
> which bins moved.

**Glows next:** the `look for fireflies` action row on the humming jar's detail screen.

**Completion condition:** the user taps `look for fireflies`; `AudioStegoCarrier.decode()`
(spectrogram-LSB) succeeds against the pre-loaded working clip. The glow moves to the singing
jar.

---

## Step 3: the singing jar

**Technique:** Module 3's acoustic FSK modem (`AcousticCarrier.kt`), decoded from a bundled
WAV rather than a live listen (see § above). Reed-Solomon FEC and the header/CRC framing are
the two facts worth surfacing — they're what makes this jar different from the other two (a
lossy medium that has to correct for itself, not just hide in one).

**Riddle payload** (77 bytes UTF-8, budget ≤ 80):
> i'm tones, one pitch for every four bits, mended by reed-solomon. now: watch.

**Fallback riddle** (29 bytes UTF-8, budget ≤ 36):
> four bits a pitch. now watch.

**Plain gloss:**
> sent out loud as tones between about 2 and 6 khz, each pitch standing for four bits.
> the meadow listens for exactly these tones.

**Glows next:** the `look for fireflies` action row on the singing jar's detail screen.

**Completion condition:** the user taps `look for fireflies`; `AcousticCarrier.decode()`
succeeds against the bundled WAV. The glow moves to the meadow.

---

## Step 4: the meadow

The meadow never holds a firefly (`JarRole.WATCHING`) — there's no riddle payload to embed,
and no firefly-detail popup to reveal one in. This step's whole content is a live detection
event, which is also the app's actual thesis (INV-4), so it earns being the one non-riddle step
in the trail rather than being forced into the same shape as the other three.

**Design intent (for the implementer to confirm is buildable, not something this doc can
verify):** the moment the user taps `watch`, and only while this trail step is active, the
meadow screen itself plays the singing jar's practice tone back through the device speaker a
short beat later (on the order of 1–2 s after `watch` starts) — no second button, no second
screen, nothing the user has to hold in their head. The meadow is already listening; the phone
supplies its own signal. This is the literal, single-device version of INV-4's thesis: the app
hears its own transmission. It requires no navigation away from the meadow screen (which would
tear down the listen session under the current single-`Screen`-state navigation model —
screen-flow.md § Navigation model) and no second physical device.

- **Branch A — it works:** `AcousticDetector` flags a rising edge while `watch` is running.
  The step completes the instant that happens — same "flagged" visual language the meadow
  already uses (`AccentSignal`/warm-styled glow-strength number), no extra copy layered on
  top. Copy: *(reuses the existing flagged state — no new string needed for the success case
  itself.)*
- **Branch B — honest, it doesn't:** if nothing flags within a bounded window (candidate: 20 s,
  matching the acoustic modem's own `MAX_LISTEN_SECONDS` precedent — screen-flow.md Screen 2),
  an inline note appears under the readout, same voice contract as every other failure copy in
  this app (what's true, two sentences max, no apology):
  > the meadow can't always hear its own song on one phone — the speaker and mic don't always
  > overlap enough. skip this step, or open the singing jar and try `catch a firefly` for
  > real.
  This is a genuinely open empirical question — whether the self-loop reliably triggers
  `AcousticDetector`'s flag threshold on the Pixel 6a's own speaker/mic geometry is exactly
  what gate-36's on-device verification has to measure, not something this design doc can
  assert. If W1's measurement finds it never reliably works, Branch A should be cut and the
  meadow's step should open directly on Branch B's honest copy instead of auto-playing anything
  — the honesty bar here is the same one gate-19/26 already set for the spectrogram caption:
  say what's actually true, not what would be nicest.
- **Skip:** available from the first moment this step is active (not gated behind a failed
  attempt) — see § Skip below.

**Glows next:** the `watch` row.

**Completion condition:** a real flagged rising edge during an active trail-watch session, or
an explicit skip. The glow moves to the send step.

---

## Step 5: send

No new riddle — this step exercises the real `send this firefly` verb (spec.md v6, gate-33) on
a firefly the user has already caught, not a fresh encode. The **art jar's** practice firefly
is the one the trail points at: it's the simplest artifact to hand to a share sheet (an image,
universally supported by every target app), and because it was caught via the **exact**
technique, this is also where the trail teaches the one genuinely load-bearing send-time fact
without inventing a special case for it — exact fireflies have to travel as a file for their
bits to survive, and the send screen's existing advisory copy (see `screen-flow.md`'s v6
addition, § Send flows) says so the same way it would for any other exact firefly.

**Glows next:** the `send this firefly` row on the art jar's practice firefly's detail popup.

**Completion condition:** the share sheet opens (`ACTION_SEND` fires) — the trail doesn't wait
to find out what the user actually does with it once it's off-device; that's out of the app's
hands entirely, same as any other share. The glow moves to the workshop hint.

---

## Step 6: workshop

**Glows next:** the shelf wordmark's existing subtitle line — while the trail is active it's
replaced, not appended to (see § Wordmark hint below), and on this final step it reads as a
direct invitation rather than a generic instruction:

> hold to see how it really works

replacing the default `hold to open workshop` for the duration of this one step only.

**Completion condition:** a long-press on the wordmark reveals `Screen.Picker`. The trail is
now complete; the wordmark subtitle reverts to `hold to open workshop` and stays that way
(there's nothing left to glow).

---

## Wordmark hint (shared across all six steps)

Rather than adding new chrome, the trail's "what do I do next" text reuses the one line that
already exists in that exact spot — `JarShelfScreen.kt`'s `"hold to open workshop"` subtitle
under the wordmark (`JarType.WordmarkSubtitle`, `JarTextTertiary`). While the trail is active
it's replaced by a step-specific line; it is never shown alongside the default text, and
never adds a second line, an icon, or an arrow:

| Step | Wordmark subtitle (replaces the default) |
|---|---|
| 1 (art) | `try the art jar` |
| 2 (humming) | `try the humming jar` |
| 3 (singing) | `try the singing jar` |
| 4 (meadow) | `try the meadow` |
| 5 (send) | `send what you caught` |
| 6 (workshop) | `hold to see how it really works` |

The long-press gesture itself keeps working normally throughout (it's the same wordmark, same
gesture, same reveal) — steps 1–5 don't disable the reveal, they just don't have anything to
say about it yet.

---

## Skip

A plain inline text link, appearing only while a trail step is active, immediately beside the
wordmark subtitle (small, `JarTextTertiary`, same weight as `clear history`) — never a dialog,
never a confirm step, matching gate-38's "no scrim, modal, coach-mark bubble" rule for the
guidance system as a whole. Skipping isn't destructive (no firefly or record is deleted; the
trail state simply stops advancing and every practice firefly already caught stays exactly
where it is), so it doesn't need a confirmation the way `clear history` does.

Copy: **`skip the trail`**

Tapping it ends the trail immediately (no partial-skip of just one step — "skip the trail" per
gate-36's "skip and replay work" phrasing means the whole thing, not step-by-step dismissal,
which would need its own per-step UI this design deliberately doesn't add). The wordmark
subtitle reverts to `hold to open workshop`.

---

## Start the trail again

Lives in the **workshop** (`Screen.Picker`, the technical module list), as a plain footer link
below the four rows, in the same `labelLarge`/`TextSecondary` styling `ModulePicker.kt`'s own
`back to the jar` link already uses. Not on the shelf: the shelf's footer already carries
`clear history` (a real, destructive, confirmed action), and stacking a second footer action
there risks exactly the kind of "two things that both sound like resets" confusion this app's
copy voice tries hard to avoid. The workshop is also where the trail's own final step already
lands the user, so it's a natural place to find the door back in.

Copy: **`start the trail again`**

Re-seeds one fresh practice firefly per creating jar and resets trail state to step 1,
regardless of whether the previous run's practice fireflies were ever released. If the user
never released them, this can leave two (or more) practice-labelled fireflies sitting in the
same jar's swarm after repeated restarts — left as-is rather than deduplicated. That's a real,
visible, honest rough edge in the spirit of the doctrine's "one rough edge per shipping
screen" rule, not a bug worth quietly engineering around: each one is clearly labelled (see
§ Practice-firefly labelling in `firefly-jar-identity.md`'s v6 addendum) and releases the same
way any firefly does.

---

## Release practice fireflies

No new UI. Gate-36's "practice fireflies are labelled and can be released like any other" is
satisfied entirely by the **existing** per-firefly delete gesture — long-press a swarm
thumbnail, or the firefly detail popup's `let this firefly go` link (`JarDetailScreen.kt`
:371/:791 in this worktree's checkout) — with one addition: a small label in the detail popup
identifying it as a practice firefly (see the identity-doc addendum). No separate "release all
practice fireflies" bulk action — three individual, already-familiar taps is not a burden worth
a second bulk-delete affordance next to `clear history`.

---

## Suggested `strings.xml` keys

All v6 copy belongs in `strings.xml` per gate-38 ("all v6 copy lives in `strings.xml`"),
including pre-v6 hard-coded jar-mode strings this task touches directly (moving the *rest* of
the pre-v6 hard-coded copy into `strings.xml` stays out of scope per spec.md's v6 addition,
out-of-scope list).

```
trail_riddle_art                  "i live in the last bit of red, green, and blue, never
                                    alpha, never loud enough to see. tip the bit-plane and
                                    find me. next, a secret in sound."
trail_gloss_art                   "hidden one bit at a time in the image's own colors. open
                                    the bit-plane view to see the layer where it hides."
trail_riddle_humming              "above 1.5 khz, i nudge how loud a bin looks. silence
                                    gives me away. now, a song."
trail_riddle_humming_fallback     "silence gives me away. try a song."
trail_gloss_humming               "hidden in the loudness of frequencies above 1.5 khz. the
                                    difference view shows exactly which bins moved."
trail_riddle_singing              "i'm tones, one pitch for every four bits, mended by
                                    reed-solomon. now: watch."
trail_riddle_singing_fallback     "four bits a pitch. now watch."
trail_gloss_singing               "sent out loud as tones between about 2 and 6 khz, each
                                    pitch standing for four bits. the meadow listens for
                                    exactly these tones."
trail_meadow_honest_fallback      "the meadow can't always hear its own song on one phone —
                                    the speaker and mic don't always overlap enough. skip
                                    this step, or open the singing jar and try \"catch a
                                    firefly\" for real."
trail_hint_art                    "try the art jar"
trail_hint_humming                "try the humming jar"
trail_hint_singing                "try the singing jar"
trail_hint_meadow                 "try the meadow"
trail_hint_send                   "send what you caught"
trail_hint_workshop               "hold to see how it really works"
trail_skip                        "skip the trail"
trail_restart                     "start the trail again"
trail_practice_label              "a practice firefly from the trail"
```

Riddle strings are listed here as plain resource text for review/translation purposes; how
they actually reach the production encoder at seed time (a raw asset generated from these
strings at build time, vs. a runtime encode-on-first-install call reading the string resource
directly) is an implementation decision for W1, not fixed by this doc.

---

## Probe surface reminder

Not new information — spec.md's v6 Probe contract addition already covers this — but worth
restating here since this doc is what names the exact steps: the trail's `COVERT_DEBUG` state
(current step, steps done, skipped) should be able to name these six steps by the identifiers
above (`art`, `humming`, `singing`, `meadow`, `send`, `workshop`), not by ordinal position —
gate-39 already establishes "nothing persists an enum ordinal" as the pattern to follow for
`Module`, and the trail's own step state should hold itself to the same rule for the same
reason (a future reorder of the trail shouldn't silently remap anyone's in-progress state).

---

## Open items for the owner

- Whether the meadow's self-loop (Branch A, § Step 4) is real enough to ship as the default
  path is a W1 measurement question, not a design one — this doc specs both branches so either
  answer is buildable without a doc revision.
- The send step (§ Step 5) always points at the art jar's practice firefly specifically. If the
  owner would rather it be "whichever jar you finish third" (order-dependent, since this trail
  is fixed-order that's moot today, but would matter if steps 1–3 ever become reorderable),
  that's a one-line change to this doc's own step 5 section, not a structural one.

## Owner decisions (2026-09-22)

- Riddle and gloss wording above **approved as written** (after the orchestrator's three
  accuracy fixes, commit d6473d6). Gate-37's final sign-off still happens on the phone (W3-3).
- Practice fireflies are **generated at runtime** (first launch and "start the trail again")
  by the production encoders from the `strings.xml` riddle text, into private app storage —
  not shipped as pre-encoded assets. INV-11 holds either way (the text is only ever shown
  from a decode); runtime generation keeps the wording editable in one place and ready for
  translation.

## Welcome + game layer (owner direction, 2026-09-22)

Owner feedback on the first on-device pass: "should probably have some guiding welcome text for
the users so they understand. the glow is a good start. make it feel like a game kindof." The
owner chose **trail with progress + rewards**: a quest feel, with no scores or badges. It stays
inside the jar identity: lowercase, no exclamation marks, no emoji, no checkmark glyphs, no
carousel or mascot. Every celebration is drawn with the existing glow primitives.

### Verb rule (owner direction, applies app-wide)

You **create** fireflies (embedding: amber) and **catch** other people's (decoding or
receiving: cyan). "catch" never describes making one. The embed verb "catch a firefly"
becomes **"create a firefly"**, and creation success reads **"you created one — N bytes"**.
"look for fireflies", "catch from a photo or file" and "you caught one" stay, because those
are receiving. Received fireflies are labelled "caught", not "spotted" (the modem's receive
result, the detail label, the jar subtitle and the TalkBack description). Only the meadow keeps
"spotted": it notices fireflies and never keeps one.

### Welcome card (first launch, on the shelf, in place, not a modal)

Shown above the jars while the trail hasn't been begun or skipped (`welcomeSeen` flag in the
trail store).

- title: `welcome to the night jar`
- body: `every firefly here carries a hidden message. three practice fireflies are hiding in the jars. find them to learn how it works, then create your own and send it to a friend.`
- actions: `begin` (dismisses the card; the art jar starts glowing) · `skip` (skips the trail)

### Progress constellation (under the wordmark while the trail is active)

Six small firefly dots, one per step, plus `trail` and `N of 6`. A completed step's dot is lit
amber and the rest are dim. When a step completes, its dot does one soft halo pulse
(`drawAmbientHalo`/`fireflyAlpha`, ~800 ms), which is static when animations are off. TalkBack
reads: `trail, N of 6 done`.

### Quest lines (one line on the active step's screen, above its actions)

| step | quest line |
|---|---|
| art | `a practice firefly is hiding in this picture. tap look for fireflies to catch it.` |
| humming | `a practice firefly is hiding in this recording. look for it.` |
| singing | `a practice firefly is hiding in this song. look for it.` |
| meadow | `the meadow notices fireflies as they pass. tap watch, and the phone will sing one for it.` |
| send | `now create one of your own. in the art jar, hide one in a photo and send it to a friend.` |
| workshop | `one secret left. hold the name at the top of the shelf.` |

### Reward lines (shown once, where the step completed, beside the dot's pulse)

| step done | reward line |
|---|---|
| art | `found one. the humming jar is glowing now.` |
| humming | `found another. the singing jar is glowing now.` |
| singing | `that's all three. the meadow is waiting.` |
| meadow | `the meadow noticed. now create one of your own.` |
| send | `sent. one secret left.` |
| workshop (finale) | `the trail is done. every jar is yours now.` |

At the finale all six dots are lit together. The constellation stays on the shelf until the
user dismisses it (`close`), then hides. "start the trail again" resets everything, including
`welcomeSeen`.

### Send step change

Step 5 is now **create one of your own**, not re-send the practice firefly. Its glow target is
the art jar's `hide one in a photo` row, and it completes when the share sheet opens from
`hide one in a photo`. Sending an existing firefly with `send this firefly` also counts, so the
user is never stuck.
