# nightjar

An offline Android app (Pixel 6a) demonstrating live, room-local, phone-to-phone
covert data transmission over sound — paired with a real-time on-device
detector that can flag the very channel it transmits on. It unifies acoustic
FSK/PSK modem, image LSB steganography, and audio steganography, each with its
own steganalysis/detector counterpart, into one hands-on research/demo tool.
The default launch surface is "Firefly Jar," a disguise-themed front end where
every hidden/decoded payload is visualized as a firefly; the underlying
technical module screens (acoustic modem, image steganography, audio
steganography, detector) are reachable via a long-press reveal gesture.

## Project

- **App name / applicationId**: `dev.herakles.nightjar` (module name `app`, root project `nightjar`)
- **minSdk**: 31 · **targetSdk / compileSdk**: 35
- **Language**: Kotlin, Jetpack Compose, Room (persistence), KSP
- **Build tooling**: AGP 8.7.3, Kotlin 2.0.21

## Setup

1. Install an Android SDK (API 35) and note its path.
2. Create `local.properties` at the repo root (gitignored) with:
   ```
   sdk.dir=<path-to-your-android-sdk>
   ```
3. Build/run using the commands below. Debug builds use AGP's default
   auto-generated debug keystore (`~/.android/debug.keystore`) — no committed
   signing config needed.

## Build

```bash
./gradlew assembleDebug   # build debug APK (output: app-debug.apk)
./gradlew test            # run the JVM unit test suite
./gradlew build           # full build + checks
```

## Module Map

Top-level packages under `app/src/main/java/dev/herakles/nightjar/`:

- **`modules/fireflyjar`** — Firefly Jar: the default disguise-themed UI (jar shelf, per-module jar detail, firefly repository/log, media store, player)
- **`modules/acoustic`** — Acoustic FSK/PSK modem screen (speaker→mic transmission between two devices)
- **`modules/audiostego`** — Audio steganography screen (phase-inversion / spectrogram-LSB / MFSK embed-extract against a bundled cover clip)
- **`modules/imagestego`** — Image LSB steganography screen (encode/decode)
- **`modules/detector`** — Passive acoustic detector screen (spectral/energy anomaly listener, self-detects the modem's own signal)
- **`incoming`** — Unified "incoming payload" pipeline: sniffing, routing, decoding, and outcome messaging for received files/images
- **`picker`** — Module-picker home screen (the hidden, technical entry point)
- **`trail`** — Onboarding/practice "riddle trail": guided fireflies, constellation UI, progress/reward state
- **`share`** — Outbound sharing: FileProvider-based share intents, "keep a copy," send advice/guidance
- **`ui`** / **`ui/theme`** — Shared Compose UI (fullscreen image viewer, color/type/theme, workshop-styled buttons)

Root-level files (`AcousticCarrier.kt`, `AudioStegoCarrier.kt`, `ImageStegoCarrier.kt`,
`SturdyImageCarrier.kt`, `AcousticDetector.kt`, `AudioStegDetector.kt`, `ImageSteganalysis.kt`,
`CovertModule.kt`, `Fft.kt`, `Spectrogram.kt`, `WavFile.kt`, `MicCapture.kt`, etc.) implement the
core `CovertCarrier` signal-processing and steganalysis logic shared across the module screens.

## Tests

Unit tests live in `app/src/test` (plain-JVM, Robolectric-backed for Android
`Bitmap`/resource access), mirroring the main package structure, plus bundled
fixture images under `app/src/test/resources/`. Run them with:

```bash
./gradlew test
```

## Docs

- `spec.md` — product spec (intent, scope, module boundaries)
- `architecture.md` — technical architecture and module interfaces
- `HANDOFF.md` — current session handoff notes
