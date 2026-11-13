# Aster Keyboard — HeliBoard development track

Aster is the working product name for an Android keyboard derived from this HeliBoard repository. The engineering goals are conservative, context-aware autocorrection; fully offline dictation using whisper.cpp; and local-only personalization.

## Repository baseline confirmed

This project is already inside the HeliBoard source tree. The current baseline inspected for this plan has:

- Android application module: `:app` (plus `:tools:make-emoji-keys`).
- Android Gradle Plugin: 9.1.1; Kotlin: 2.4.0; Gradle wrapper: 9.7.1.
- JDK source/target compatibility: Java 17.
- `compileSdk = 37`, `minSdk = 21`, `targetSdk = 37`.
- NDK: 28.0.13004108; existing native build uses ndk-build via `app/src/main/jni/Android.mk`.
- Existing application ID: `helium314.keyboard`.
- The current main manifest does not declare `android.permission.INTERNET`; this must remain true in the merged release manifest.

These are observations from the fork's current Gradle and manifest files, not a guarantee that a local Android build has passed. Preserve the existing minSdk and ABI support unless device testing justifies a deliberate change.

## Product requirements

1. Improve autocorrection without increasing false corrections on already-correct words.
2. Keep the existing HeliBoard IME responsive; no expensive inference on the key-event path.
3. Add opt-in, on-device Whisper dictation. No cloud transcription and no app network permission.
4. Keep personalization on-device, honor password and `IME_FLAG_NO_PERSONALIZED_LEARNING` fields, and provide clear deletion/opt-out controls.
5. Preserve HeliBoard's existing features, translations, build variants, notices, and license obligations.

## Current implementation status

The development branch now disables Android app backup and device-to-device data extraction, and CI checks those source declarations plus the built APK's merged manifest. The app still needs a successful CI build and device verification.

This folder is an engineering plan and integration scaffold. It is not yet a completed feature implementation or APK. The ASR service sample is deliberately isolated until the exact Android service lifecycle, manifest, native toolchain, model licensing, and device performance have been validated.

## Work order

See [roadmap](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md), [autocorrect baseline review](docs/AUTOCORRECT_BASELINE.md), [privacy requirements](docs/PRIVACY.md), and [third-party notices](THIRD_PARTY_NOTICES.md).

## Local validation

From the repository root, with the required Android SDK/NDK and JDK installed:

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s aster-project/tests -v
python3 aster-project/eval/autocorrect_eval.py aster-project/eval/sample_sentences.txt --seed 1
```

Do not report a successful Android build until those Gradle tasks have actually completed in CI or on a configured Android build machine.
