# Delivery roadmap

## Gate 0 — establish baseline
- Build and install unmodified HeliBoard.
- Record baseline APK, manifest, tests, Gradle/AGP/JDK/NDK versions.
- Preserve upstream notices, translations, build variants and minSdk.

Exit: reproducible baseline build on a configured Android runner/device.

## Gate 1 — privacy and CI
- [x] Add no-INTERNET merged-APK check.
- [x] Add deterministic evaluation harness tests.
- [x] Disable Android backup and device-to-device data extraction.
- [x] Add source-level checks for privacy manifest and backup declarations.
- [ ] Confirm CI build, lint, unit tests and merged-APK privacy checks pass.
- [ ] Review exported components, logs, dependencies and merged manifests.

Exit: CI passes with a produced APK and all privacy checks green.

## Gate 2 — autocorrect measurement
- Adapt a host CLI to the actual HeliBoard correction engine.
- Use a rights-cleared, versioned corpus and calibrated touch-noise profiles.
- Measure noisy-word restoration separately from false corrections on clean words.
- Track language and field-type regressions.

Exit: repeatable metrics; no quality claims without measured evidence.

## Gate 3 — offline dictation
- Pin whisper.cpp and build JNI for arm64.
- Implement VAD, AudioRecord lifecycle, Binder callbacks, cancellation and model verification.
- Add microphone permission UX.
- Benchmark latency, memory, battery and thermals on real devices.

Exit: offline dictation works without blocking or crashing the IME.

## Gate 4 — personalization and release
- Encrypt local adaptation data with Android Keystore-backed AES-GCM.
- Add opt-out, delete-all and incognito behavior.
- Verify password and IME_FLAG_NO_PERSONALIZED_LEARNING handling.
- Test accessibility, field compatibility, backup exclusion and release artifacts.
- Complete independent security and license review.

Exit: signed beta with documented privacy behavior and corresponding source.

## Acceptance evidence

| Area | Evidence |
|---|---|
| Offline | merged APK manifest + dependency/network review |
| Autocorrect | corpus version, fix rate, false-correction rate |
| Responsiveness | p50/p95 candidate latency on named devices |
| Voice | offline test, latency, cancellation and crash recovery |
| Sensitive fields | automated and manual password/no-learning tests |
| Storage | encryption-at-rest and backup exclusion tests |
| Release | reproducible build notes, license inventory, security review |
