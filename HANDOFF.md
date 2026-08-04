# nightjar — Sprint Close (2026-08-03)

## Shipped

All v1 scope complete and verified on real hardware, both devices (Pixel 6a "Hek" +
a second phone):

- **Module 3 — acoustic modem**: FSK/PSK data-over-sound, Reed-Solomon FEC,
  bin-exact tone plan (audible + near-ultrasonic bands). Real two-device
  transmission confirmed working end-to-end.
- **Module 5 — acoustic detector**: passive spectral-anomaly listener, self-detects
  the app's own Module 3 transmission (INV-4).
- **Module 1 — image steganography**: raw-pixel LSB codec, verified round-trip on
  real hardware (embed → detect → extract, exact payload recovered).
- **Module 5 — image steganalysis**: chi-square Pairs-of-Values detector. Found and
  documented a real, measured limitation: near-zero detection on structured/
  repetitive cover images at low embedding rates (folded into
  `covert-data/library/06_detection_and_countermeasures.md`).
- Module-picker shell, visual identity + app icon, safety-scope audit (clean —
  no exploit content, no RF paths, `RECORD_AUDIO` only), debug/verification
  probe (`COVERT_DEBUG` logcat tag).

## Real bugs found only by field-testing (not caught by 69 passing unit tests)

1. **AudioSource.MIC routed through speech-tuned AGC/noise-suppression**, mangling
   the pure-tone signal. Fixed: tiers `UNPROCESSED → VOICE_RECOGNITION → MIC`,
   explicitly disables NoiseSuppressor/AGC/AEC effects on the capture session.
2. **Marker search only checked frame-quantized (1024-sample-aligned) candidate
   positions.** A live capture's true transmission offset is essentially never
   frame-aligned — detection could still succeed (any span this it overlaps),
   but every subsequent symbol window was misaligned by up to ~1023 samples,
   corrupting the whole payload even though a signal was clearly present. Fixed
   with a sample-accurate marker-onset refinement; added the missing
   non-frame-aligned round-trip tests (500/777/1500-sample offsets) that would
   have caught this originally.
3. UX gap: "listen" gave no feedback during its ~20s window. Added a live dB
   level readout, countdown, and a specific no-signal timeout message.

## Deferred to v2

- ~~Module 2 (audio steganography — phase/spread-spectrum/spectrogram LSB)~~ — **built**:
  all 3 codecs (phase-inversion, spectrogram-LSB, MFSK w/ real Reed-Solomon FEC) implemented
  and unit-tested (110/110), screen wired and verified functional on-device (gates 6–7 closed
  per `spec.md`). Gate 8 (fidelity — cover vs. stego indistinguishable by ear) still needs a
  human listening pass; gate 9 (safety-scope + anti-AI-tell close-out) is open pending it.
- Module 4 (video steganography — needs a non-mobile ML watermarking component)
- Empirical distance/SNR envelope measurement for the acoustic channel (INV-3's
  1m/45dBA target is the working assumption from architecture.md, not pinned
  down to the meter via instrumented field testing)
- Image steganalysis threshold re-tuning against a larger, more diverse cover
  corpus (currently tuned against 2 synthetic 100×100 test images)

## State

23 of 25 originally-scoped tasks complete, plus 4 field-fix tasks (26-29) found
during real-device testing — all complete. Spec at `spec.md`, architecture and
protocol parameters at `architecture.md`, design decisions at
`design/identity.md` and `design/screen-flow.md`.
