# Security

Secure Keyboard is built to run on GrapheneOS. Nothing typed or said is sent off the phone. This note is the review done on 18 November 2026. Numbers below were produced by the commands in this tree, not estimated.

## What was checked, and what changed

### Permissions

Source manifests and the release APK are allowlisted.

| Permission | Where | Why it is there |
|---|---|---|
| `RECORD_AUDIO` | `:asr` manifest, merged into the APK | Push-to-talk dictation. `AsrService` opens the microphone on `MSG_START` and stops it on `MSG_STOP`, unbind, or process death. The keyboard only sends start while the mic key is held. |
| `VIBRATE` | app manifest | Key and dictation haptics. |
| `RECEIVE_BOOT_COMPLETED` | app manifest | `SystemBroadcastReceiver` restores the pre-Android 10 setup icon after boot. It now returns immediately for any other action. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | merged APK only, added by AndroidX | Signature permission so the library's non-exported receivers stay app-private. It is not in our source. |

There is no `INTERNET` permission. `securekeyboard-project/ci/check_privacy_sources.py` fails the build if a source manifest adds any other permission or a new exported component. `securekeyboard-project/ci/check_no_internet.sh` fails the release APK the same way. Both ran successfully in this review (`PASS` on the source check).

### Native code and models

`app/src/main/jni` (dictionary) and `asr` (whisper.cpp, submodule commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`, tag v1.9.5) are built with `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, and `-Wl,-z,relro,-z,now,-z,noexecstack`. The dictionary library also passes `-Wl,--build-id=none`.

`whisper_jni.cpp` refuses a buffer longer than 32 seconds of 16 kHz audio and a null sample pointer, instead of handing an unbounded array to Whisper.

The bundled model is `ggml-base.en-q5_1.bin`. `securekeyboard-project/ci/fetch_model.sh` checks its SHA-256 against `securekeyboard-project/asr/MODEL_SHA256` (`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`) before the APK is built. `ModelStore` hashes the asset again when copying it out of the APK and refuses to load a mismatch.

The swipe decoder in this tree is Kotlin (`GlideTyping`), not a closed native library. Zip-slip names for backup restore are rejected by `isSafeZipEntry`. `BackupPathsTest` includes 200 hostile names from a fixed seed and rejects `..`, absolute paths, and NUL.

A libFuzzer + ASan run of whisper.cpp was not executed here. The JNI bound above is the control that is in the build.

### Dependencies

Versions are pinned in `build.gradle.kts`, `app/build.gradle.kts`, and the whisper.cpp gitlink. On 18 November 2026 the [OSV](https://osv.dev) API (`POST /v1/query`, Maven ecosystem) returned no vulnerabilities for:

`com.android.tools.build:gradle:9.1.1`, `org.jetbrains.kotlin:kotlin-stdlib:2.4.0`, `androidx.core:core-ktx:1.17.0`, `androidx.recyclerview:recyclerview:1.4.0`, `androidx.autofill:autofill:1.3.0`, `androidx.viewpager2:viewpager2:1.1.0`, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`, `com.android.tools:desugar_jdk_libs:2.1.5`, `androidx.compose:compose-bom:2025.11.01`, `androidx.navigation:navigation-compose:2.9.8`, `sh.calvin.reorderable:reorderable:3.1.0`, `com.github.skydoves:colorpicker-compose:1.1.3`, `junit:junit:4.13.2`, `org.mockito:mockito-core:5.23.0`, `org.robolectric:robolectric:4.16.1`, `androidx.test:runner:1.7.0`, `androidx.test.ext:junit:1.3.0`, `androidx.test.uiautomator:uiautomator:2.3.0`, `androidx.test:core:1.7.0`.

The same API returned no vulnerabilities for whisper.cpp commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`.

### Password, incognito, and learned data

`PersonalizationPolicy` treats a password field, `IME_FLAG_NO_PERSONALIZED_LEARNING`, or the always-incognito setting as blocked. While blocked, suggestion lookup skips user-specific dictionaries (history, contacts, apps, the user's word list), and tap-offset learning does not run. History was already not written in that mode (`InputLogic.performAdditionToUserHistoryDictionary`).

Learned words are files whose names start with `UserHistoryDictionary` under the app's private files directory (and the device-protected files directory). `android:allowBackup="false"` plus `backup_rules.xml` and `data_extraction_rules.xml` exclude cloud backup and device transfer. Settings → Text correction → Wipe everything learned deletes those files immediately and clears the three tap-offset numbers. `LearnedDataFilesTest` creates a temp directory, writes a history file and `main_en.dict`, runs the wipe, and checks that only the history file is gone.

Clipboard history defaults to off (`Defaults.PREF_ENABLE_CLIPBOARD_HISTORY = false`). `ClipboardHistoryManager` does not read or store the clipboard unless that setting is on. `ClipboardDefaultTest` locks the default.

### Exported components

Allowed, and checked by CI:

- `LatinIME`, permission `BIND_INPUT_METHOD` (required for an IME).
- `AndroidSpellCheckerService`, permission `BIND_TEXT_SERVICE` (the system spell checker).
- `SettingsActivity` (launcher) and `SpellCheckerSettingsActivity` (opened by the system spell-checker screen).
- `SettingsActivity2`, so a `.dict` file the user opens can be imported. The `BROWSABLE` category was removed so a browser link is not a way in.
- `SystemBroadcastReceiver`, only for the protected system actions listed above.
- `AsrService` is `exported="false"` and runs in the `:asr` process.

No keystore, `.jks`, `.p12`, or `.key` file is in the repository. The source check fails if one is added. Release signing uses GitHub secrets `SK_KEYSTORE_B64` and `SK_KEYSTORE_PASSWORD` (alias `securekeyboard`). They are not in git.

### Upstream HeliBoard

A search of `HeliBorg/HeliBoard` commits for security, vulnerability, permission, backup, and CVE found one fix that applies here: commit `6cf3c1fdcf`, "Harden backup restore against path traversal (#2463)" (9 May 2026). The canonical-path check is already in `BackupRestorePreference`. This change routes it through `isSafeZipEntry` and adds `BackupPathsTest` so a `..` entry cannot regress. Older HeliBoard commits in that search are backup features or already-applied permission cleanups (`HIDE_SOFT_INPUT` removed, contacts permission removed), not missing patches.

### Releases and Obtainium

GitHub Actions writes `SHA256SUMS.txt` next to the APK on a signed main-branch release. The signing key is not in this repository. The certificate SHA-256 below was read from a signed release APK with `apksigner verify --print-certs` (subject `CN=Secure Keyboard, O=zenv99`):

```
33eacd63355ee1b19abca1f997ea25a7946960bbf934411691273dde053c78d4
```

Compare that value with what Obtainium or AppVerifier shows before trusting an update. APKs are not bit-identical across machines: Android Gradle Plugin still stamps build times. Native libraries pass `-Wl,--build-id=none` to avoid one source of difference.

### GrapheneOS

The network permission toggle cannot grant this app a connection it did not declare. That is the source allowlist and `check_no_internet.sh` on the merged release manifest, which CI runs before a release is published. It was not re-checked on a GrapheneOS phone in this review.

Native code uses the system allocator (C++ `new` / libc malloc via the NDK). The tree does not ship a replacement allocator and does not call `malloc_usable_size`. hardened_malloc compatibility was not executed on a GrapheneOS device. That still needs an on-device install: confirm the keyboard opens, a word is suggested, and dictation starts and stops with the mic key.

## Still open

- No independent audit by someone outside this repository.
- libFuzzer and ASan were not run on whisper.cpp or the dictionary JNI.
- The signing key remains only in GitHub secrets. The certificate SHA-256 from a signed release APK is in the section above and in the README.
- Bit-identical reproducible APKs are not claimed.
- GrapheneOS hardened_malloc and the network toggle were not confirmed on a phone.
- Clipboard rows saved by older builds, when history was on by default, stay on disk until the user turns the setting off (that clears unpinned clips) or clears app storage. New installs do not record clips unless the setting is turned on.
