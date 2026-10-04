# Contributing

Issues and pull requests are welcome.

## Setup

You need JDK 17 and the Android SDK (`sdk.dir` in `local.properties`).

## Before opening a PR

```bash
./gradlew lintDebug         # Android lint; errors fail the build
./gradlew test              # JVM unit tests
./gradlew jacocoTestReport  # coverage report: app/build/reports/jacoco/
./gradlew pitest            # optional, slow: mutation tests on the DSP core
```

CI runs lint, tests and a debug build on every pull request. Changes to the parsers also get a
short ClusterFuzzLite (Jazzer) fuzzing run; the targets are in `fuzz/`.

## Guidelines

- Keep detection and embedding code testable on the JVM, with no Android
  dependencies where avoidable.
- Add a test that fails without your change.
- This project is a research and educational demonstration; changes aimed at
  evading lawful monitoring won't be accepted.
