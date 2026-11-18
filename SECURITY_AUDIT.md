# Security audit

Reviewed 18 November 2026, before the space-bar trackpad and text-replacement changes in this pull request. Nothing in the tree looked like malware, a backdoor, or a hidden exfiltration path. Those two features were added after that conclusion. They do not add a permission, a network call, or a binary.

This is a source review plus the checks below. It is not a third-party penetration test, and it was not run on a phone.

## What this repository is

`zenv99/Secure-Keyboard` is HeliBoard with 141 commits on top. The merge base with `origin/upstream-main` is `3e91b4f1bf711a8bd3eb2d049c8b285fcc9cfa10` ("fix repeated next word suggestions", 7 November 2026). Upstream has no commits that this branch is missing. The fork is GPL-3.0. `LICENSE` is still the GPL, and the readme credits HeliBoard, OpenBoard, and the Android Open Source Keyboard.

The application id is already `app.securekeyboard.keyboard`. That is not `com.archonaerospace.securekeyboard`, so it was left unchanged. The launcher name is already "Secure Keyboard". `minSdk` is 21. `abiFilters` is `arm64-v8a` only.

## Difference from upstream HeliBoard

125 paths differ. Grouped by what they do:

- Branding and docs: readme, privacy policy, third-party notices, fastlane English listing, launcher icons, `docs/HELIBOARD_README.md`.
- Identity and build: `applicationId`, version from CI environment variables, arm64-only ABI, signing from GitHub secrets when present. `settings.gradle` adds the `:asr` module.
- On-device dictation: new `:asr` module (`AsrService` in process `:asr`), JNI bridge, `third_party/whisper.cpp` submodule, push-to-talk controller, mic permission activity, dictation log.
- Open-source swipe decoder: `latin/glide/GlideTyping.kt` and `EmojiWords.kt`. The old closed-source glide loader is still in `JniUtils` (see below).
- Privacy declarations: `allowBackup="false"`, `backup_rules.xml` and `data_extraction_rules.xml` exclude every backup domain. No `INTERNET` permission in the source manifests.
- Cursor and typing tweaks already on main: touchpad handler, touch-offset learner, suggestion strip, functional key row, backspace behavior.
- CI: `securekeyboard-quality.yml` and `securekeyboard-lint-tests.yml` replace the older debug-APK workflows. The quality workflow can publish a GitHub pre-release from `main`. This pull request does not publish anything.

Full name-status against `origin/upstream-main`:

```
D	.github/workflows/build-debug-apk.yml
D	.github/workflows/build-test-auto.yml
A	.github/workflows/securekeyboard-lint-tests.yml
A	.github/workflows/securekeyboard-quality.yml
M	.gitignore
A	.gitmodules
A	PRIVACY_POLICY.md
M	README.md
A	THIRD_PARTY_NOTICES.md
M	app/build.gradle.kts
M	app/proguard-rules.pro
A	app/src/androidTest/java/helium314/keyboard/smoke/KeyboardSmokeTest.kt
M	app/src/debug/res/values/clip_provider.xml
M	app/src/debug/res/values/gesture_data.xml
M	app/src/debug/res/values/strings.xml
A	app/src/debugNoMinify/AndroidManifest.xml
A	app/src/debugNoMinify/java/helium314/keyboard/smoke/KeyboardTestActivity.kt
M	app/src/main/AndroidManifest.xml
A	app/src/main/assets/emoji_words.tsv
M	app/src/main/assets/layouts/functional/functional_keys.json
M	app/src/main/java/helium314/keyboard/keyboard/KeyDetector.java
M	app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListener.java
M	app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt
M	app/src/main/java/helium314/keyboard/keyboard/KeyboardTheme.kt
M	app/src/main/java/helium314/keyboard/keyboard/MainKeyboardView.java
M	app/src/main/java/helium314/keyboard/keyboard/PointerTracker.java
A	app/src/main/java/helium314/keyboard/keyboard/TouchAdapter.kt
A	app/src/main/java/helium314/keyboard/keyboard/TouchOffsetLearner.kt
M	app/src/main/java/helium314/keyboard/keyboard/TouchpadHandler.java
M	app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/TextKeyData.kt
M	app/src/main/java/helium314/keyboard/latin/DictionaryFacilitatorImpl.kt
M	app/src/main/java/helium314/keyboard/latin/LatinIME.java
M	app/src/main/java/helium314/keyboard/latin/StuffReceiver.kt
M	app/src/main/java/helium314/keyboard/latin/common/Constants.kt
M	app/src/main/java/helium314/keyboard/latin/dictionary/Dictionary.java
M	app/src/main/java/helium314/keyboard/latin/dictionary/DictionaryCollection.java
M	app/src/main/java/helium314/keyboard/latin/dictionary/ReadOnlyBinaryDictionary.java
M	app/src/main/java/helium314/keyboard/latin/dictionary/UserBinaryDictionary.java
A	app/src/main/java/helium314/keyboard/latin/glide/EmojiWords.kt
A	app/src/main/java/helium314/keyboard/latin/glide/GlideTyping.kt
M	app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt
M	app/src/main/java/helium314/keyboard/latin/settings/Settings.java
M	app/src/main/java/helium314/keyboard/latin/settings/SettingsValues.java
M	app/src/main/java/helium314/keyboard/latin/spellcheck/AndroidWordLevelSpellCheckerSession.kt
M	app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt
M	app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt
M	app/src/main/java/helium314/keyboard/settings/SettingsContainer.kt
M	app/src/main/java/helium314/keyboard/settings/WelcomeWizard.kt
M	app/src/main/java/helium314/keyboard/settings/screens/AboutScreen.kt
M	app/src/main/java/helium314/keyboard/settings/screens/AppearanceScreen.kt
M	app/src/main/java/helium314/keyboard/settings/screens/TextCorrectionScreen.kt
A	app/src/main/java/helium314/keyboard/voice/DictationLog.kt
A	app/src/main/java/helium314/keyboard/voice/MicPermissionActivity.kt
A	app/src/main/java/helium314/keyboard/voice/PushToTalkController.kt
A	app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
A	app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png
M	app/src/main/res/drawable-v24/ic_launcher_background.xml
D	app/src/main/res/drawable/ic_launcher_foreground.xml
M	app/src/main/res/layout/suggestions_strip.xml
M	app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
M	app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
M	app/src/main/res/mipmap-hdpi/ic_launcher.png
M	app/src/main/res/mipmap-hdpi/ic_launcher_round.png
M	app/src/main/res/mipmap-mdpi/ic_launcher.png
M	app/src/main/res/mipmap-mdpi/ic_launcher_round.png
M	app/src/main/res/mipmap-xhdpi/ic_launcher.png
M	app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
M	app/src/main/res/mipmap-xxhdpi/ic_launcher.png
M	app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
M	app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
M	app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
M	app/src/main/res/values-land/config.xml
M	app/src/main/res/values-sw600dp-land/config.xml
M	app/src/main/res/values-sw600dp/config.xml
M	app/src/main/res/values-sw768dp-land/config.xml
M	app/src/main/res/values-sw768dp/config.xml
M	app/src/main/res/values/clip_provider.xml
M	app/src/main/res/values/config.xml
M	app/src/main/res/values/donottranslate.xml
M	app/src/main/res/values/gesture_data.xml
A	app/src/main/res/values/ids.xml
M	app/src/main/res/values/strings.xml
A	app/src/main/res/xml/backup_rules.xml
A	app/src/main/res/xml/data_extraction_rules.xml
A	app/src/release/res/values/strings.xml
M	app/src/test/java/helium314/keyboard/InputTest.kt
A	app/src/test/java/helium314/keyboard/TouchOffsetLearnerTest.kt
A	app/src/test/java/helium314/keyboard/keyboard/PointerTrackerBackspaceTest.kt
A	asr/build.gradle.kts
A	asr/consumer-rules.pro
A	asr/src/main/AndroidManifest.xml
A	asr/src/main/cpp/CMakeLists.txt
A	asr/src/main/cpp/whisper_jni.cpp
A	asr/src/main/java/app/securekeyboard/asr/AsrService.kt
A	asr/src/main/java/app/securekeyboard/asr/ModelStore.kt
A	asr/src/main/java/app/securekeyboard/asr/WhisperBridge.kt
A	assets/secure-keyboard-banner.png
A	assets/secure-keyboard-banner.svg
A	assets/secure-keyboard-icon.svg
A	docs/HELIBOARD_README.md
M	fastlane/metadata/android/en-US/full_description.txt
M	fastlane/metadata/android/en-US/images/featureGraphic.png
M	fastlane/metadata/android/en-US/images/icon.png
M	fastlane/metadata/android/en-US/short_description.txt
M	fastlane/metadata/android/en-US/title.txt
A	securekeyboard-project/README.md
A	securekeyboard-project/THIRD_PARTY_NOTICES.md
A	securekeyboard-project/asr/MODEL_SHA256
A	securekeyboard-project/ci/check_no_internet.sh
A	securekeyboard-project/ci/check_privacy_sources.py
A	securekeyboard-project/ci/emulator_smoke.sh
A	securekeyboard-project/ci/fetch_model.sh
A	securekeyboard-project/docs/ARCHITECTURE.md
A	securekeyboard-project/docs/AUTOCORRECT_BASELINE.md
A	securekeyboard-project/docs/PLAY_STORE.md
A	securekeyboard-project/docs/PRIVACY.md
A	securekeyboard-project/docs/ROADMAP.md
A	securekeyboard-project/eval/__init__.py
A	securekeyboard-project/eval/autocorrect_eval.py
A	securekeyboard-project/eval/sample_sentences.txt
A	securekeyboard-project/requirements-dev.txt
A	securekeyboard-project/tests/test_autocorrect_eval.py
M	settings.gradle
A	third_party/README.md
A	third_party/whisper.cpp
```

Added lines in Kotlin, Java, and the JNI bridge that mention a URL, a socket, or `System.load` are the HeliBoard and Secure Keyboard GitHub links in `Constants.kt`, and `System.loadLibrary("securekeyboard_whisper")`. No new HTTP client, socket, or analytics SDK was added.

## Binaries

The only `.jar` in the source tree is `gradle/wrapper/gradle-wrapper.jar`. There is no `.so`, `.aar`, or `.dex` checked in. Dictionary files under `app/src/main/assets/dicts/` are the usual AOSP binary word lists, not code. The whisper model is not in git. CI downloads `ggml-base.en-q5_1.bin` and checks it against `securekeyboard-project/asr/MODEL_SHA256` (`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`).

## Gradle wrapper

`gradle-wrapper.properties` pins `gradle-9.7.1-bin.zip` with

`acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`

That matches `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256`.

`gradle-wrapper.jar` SHA-256 is `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`, the same as the wrapper jar in the Gradle git tag `v9.7.1`.

## whisper.cpp pin

The submodule gitlink is `d1be6fde11ac6e0407606b4e42fe72d34add8037`. GitHub resolves the annotated tag `v1.9.5` to that commit ("whisper : bump version to 1.9.5"). The submodule directory was empty in this checkout until a build initializes it. The JNI bridge turns off GPU, timestamps, and ggml's backtrace `fork`/`exec`, and does not log audio or the transcript.

## Network and permissions

Source manifests request `RECORD_AUDIO` (the `:asr` library), `VIBRATE`, and `RECEIVE_BOOT_COMPLETED`. There is no `INTERNET` permission. AndroidX may still merge `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` into a built APK. That merged APK was not produced in this review.

Hardcoded `https://` strings are links (HeliBoard, this repo, dictionary host on Codeberg, gesture-research videos). Dictionary download is a link the user opens. The app cannot fetch it without `INTERNET`. `XLinkTest` uses `HttpURLConnection` and is a unit test excluded from the CI test filter.

`securekeyboard-quality.yml` on a push to `main` can create a GitHub pre-release. That is a release process, not hidden network code in the app. This pull request does not run that job and does not create a release.

## Voice, left as it is

Audio samples stay in memory and are zeroed after transcription. The model file is copied from assets into app-private storage after a SHA-256 check. That file is the model, not audio. The service unloads the model after 10 minutes idle (`IDLE_UNLOAD_MS = 600_000`), not 30 seconds. `DictationLog` stores timings, durations, word counts, and error codes in a private preference file. The callers do not pass the transcript or the audio. The log is not encrypted.

## Other notes, not treated as malware

- `JniUtils` can still load a user-supplied `libjni_latinime.so` when its SHA-256 matches the stored checksum, and it still tries `libjni_latinimegoogle`. That is the closed-source glide path. It was not removed here because swipe typing on the current build uses the open-source decoder when that library is absent, and the working path was left alone.
- Gesture-data gathering is the upstream research feature. It does not upload by itself. A user can attach a file to an email. There is no `INTERNET` permission.
- `commitChosenWord` can log the committed word when `DebugFlags.DEBUG_ENABLED` is on. That flag is the debug switch, not the release default.
- OSV (`https://api.osv.dev/v1/querybatch`) was queried for the direct Maven coordinates in the Gradle files. The only hit is `GHSA-r937-wjx7-w2jp` / `CVE-2026-53914` on `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0`: unsafe deserialization of Kotlin build-cache metadata, fixed in `2.4.20-Beta1`. That is a build-machine issue, not code that ships in the keyboard. The plugin was not upgraded in this pull request.

## Conclusion

No malicious code was found. The review did not stop the feature work.
