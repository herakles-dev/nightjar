# nightjar — Sprint Close (2026-09-21)

## Shipped

v1 through v5 scope complete, plus a large post-v5 review pass — verified on real hardware
(Pixel 6a "Hek" + a second phone):

- **v1**: Module 3 acoustic modem (FSK/PSK over sound, Reed-Solomon FEC, phone-to-phone),
  Module 5 acoustic detector (self-detects Module 3's own signal), Module 1 image
  steganography (raw-pixel LSB) + its chi-square/RS steganalysis counterpart, module-picker
  shell, safety-scope audit.
- **v2**: Module 2 audio steganography — phase-inversion, spectrogram-LSB, and
  MFSK-robustness embed/extract against bundled cover clips.
- **v3**: Firefly Jar — an alternate, disguise-themed front-end (the app's default launch
  screen; the technical screens above become a long-press-reveal hidden mode).
- **v4**: Firefly carrier content — a caught firefly's actual carrier (image/audio) is
  persisted and viewable (bit-plane toggle, spectrogram, playback), plus user-governed
  storage (usage readout, threshold warning, clear-all, per-firefly delete).
- **v5**: `AudioStegDetector` — a blind `CovertDetector<WavFile.ParsedWav>` targeted at this
  app's own three audio-stego techniques (channel-polarity anti-correlation, QIM lattice
  snapping, keyed 19.7–20.0 kHz tones), wired as "check for hidden data" on the audio
  technical screen and "peek inside" on the humming jar — plus the two honest carrier views
  v4 deferred: a cover-vs-stego difference view for spectrogram-LSB fireflies and an L/R
  polarity view for phase-inversion fireflies.
- **This review pass**: a large bug-fix and honesty sweep across the modem/detector's shared
  mic-capture path (mic-busy crash, effects leak, double-tap race, background-stop),
  downsample OOM and MFSK clipping/gain fixes, an MFSK tone-edge click fix (see Gate 8
  below), several corrected honesty captions (spectrogram-LSB's dB figure, the detector's
  caveat copy, stale KDoc), and — owner-requested — a shared fullscreen image viewer with
  pinch-zoom/pan and subtle tap affordances on both the technical and jar-mode image carrier
  views.

## Gate 8 — the honest outcome

Gate 8 originally predicted cover and stego would be audibly indistinguishable across the
board. The owner's real headphone listening pass on Hek found that isn't uniformly true, and
the screen's own copy was corrected to say so rather than assert it:

- **Spectrogram-LSB and MFSK are effectively transparent** at default strength.
  MFSK needed a real fix first — Gate 8 round 4 found an audible tone-edge click/crackle in
  the first few seconds, traced to a hard rectangular on/off gate at each MFSK block boundary
  (not the near-ultrasonic tones themselves being audible — see `AudioStegoCarrier.kt`'s
  click-fix KDoc for the measured before/after). A raised-cosine ramp on tone on/off edges
  fixed it (boundary-locked audible-band level down to −74/−75 dBFS on both covers). SLSB's
  own click train is smaller and already disclosed in its own honesty caption rather than
  hidden.
- **Phase-inversion reads clearly wider/hollow by ear — audibly different from its cover by
  design, not a bug.** This is the same mono-mix cancellation mechanism that makes it
  genuinely visible on a spectrogram and detectable by `AudioStegDetector` (gate-22/23): the
  thing that exposes it to the ear is the same thing that exposes it to analysis.

## Deferred follow-ups

- **True phase-coding codec for audio stego** — the current phase-inversion technique is a
  simpler stereo-invert trick, not full phase coding; a proper phase-coding implementation
  is real, separate scope.
- **SLSB near-silent-frame skip (versioned embedding format)** — spectrogram-LSB's QIM
  currently embeds into near-silent cover frames too, producing the small measured click
  train the honesty caption discloses; skipping those frames would need a new, versioned
  embedding format (existing caught fireflies depend on the current one).
- **Canvas carrier-view a11y labels** — the bit-plane, spectrogram, difference, and polarity
  views are all custom `Canvas` draws with no accessibility labels yet.

## State

Spec at `spec.md` (26 gates across v1–v5), architecture and protocol parameters at
`architecture.md`, design decisions at `design/identity.md` (technical screens),
`design/firefly-jar-identity.md` (jar surface), and `design/screen-flow.md` (all 7 screens).
