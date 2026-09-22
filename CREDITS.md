# Credits & Acknowledgments

nightjar stands on the shoulders of the researchers, engineers, and creators
named below — their published work, open-source code, and public writing made
this project possible, and any errors introduced in nightjar's own
reimplementations are ours alone.

- **Benn Jordan** (a.k.a. The Flashbulb) — for pioneering, publicly sharing,
  and popularizing practical audio data-hiding and data-over-sound work.
  nightjar's acoustic modem and its 8-tone MFSK audio-steganography variant
  are directly inspired by his projects **Wavest**, **BUM16**, and
  **AlphaSteg** (`github.com/bennjordan/AlphaSteg`). nightjar's
  implementations are independent reimplementations inspired by that work.

- **Georgi Gerganov** — for **ggwave** (`github.com/ggerganov/ggwave`, MIT).
  nightjar's acoustic FSK/modem design modernizes ggwave, and vendors/adapts
  its Reed–Solomon error-correction approach. See [NOTICE](NOTICE) for the
  required MIT license text.

- **Bender, Gruhl, Morimoto & Lu** — "Techniques for Data Hiding," *IBM
  Systems Journal* 35(3–4), 1996 — the foundational reference for the LSB and
  phase-coding techniques used here.

- **Kirovski & Malvar** — for audio watermarking / spread-spectrum techniques
  informing the audio-stego module.

- **StegExpose** — whose documented default thresholds informed the passive
  detector's steganalysis heuristics.

- **Silkscreen** by **Jason Kottke** — the bundled UI pixel font, used under
  the SIL Open Font License (see
  [`app/src/main/assets/LICENSE-Silkscreen-OFL.txt`](app/src/main/assets/LICENSE-Silkscreen-OFL.txt)).

Thank you all for the work that made this possible.
