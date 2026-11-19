# Secure Keyboard

Secure Keyboard is an Android keyboard. It is a modified version of [HeliBoard](https://github.com/HeliBorg/HeliBoard), which is based on OpenBoard and the Android Open Source Project LatinIME. It is not the official HeliBoard.

The keyboard is built to run without a network. Release builds are checked for that. This repository is maintained by [zenv99](https://github.com/zenv99).

## Threat model

The keyboard sees what is typed into it and, while the dictation key is held, microphone audio. The goal is that this process does not send that material off the device, does not put it in Android backup or device transfer, and does not keep learning from password fields or fields that disallow personalized learning.

What you type is still delivered to the app you are typing in. That app, the operating system, and anyone with access to an unlocked device are outside this keyboard's control. There is no claim of protection against a compromised system.

## Privacy

These are properties of the source and of the CI checks, not an external audit.

- The source manifests do not declare `INTERNET`. `securekeyboard-project/ci/check_privacy_sources.py` fails the build if a source manifest adds it or any permission outside the allowlist. `securekeyboard-project/ci/check_no_internet.sh` fails the build if the merged release APK contains `INTERNET`.
- The permission allowlist is `RECORD_AUDIO`, `VIBRATE`, and `RECEIVE_BOOT_COMPLETED`, plus the AndroidX signature permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that the build tools merge in. `RECORD_AUDIO` is used by the dictation process. The keyboard starts that process only while the microphone key is held, and stops it when the key is released.
- Exported components are allowlisted and checked by the same source script. Details are in [SECURITY.md](SECURITY.md).
- `android:allowBackup` is `false`. `backup_rules.xml` and `data_extraction_rules.xml` exclude cloud backup and device-to-device transfer.
- There is no account, analytics, or crash-reporting SDK in the application. With no `INTERNET` permission the app cannot open a network connection of its own.
- Password fields, `IME_FLAG_NO_PERSONALIZED_LEARNING`, and the always-incognito setting block personalization: those fields are not learned from, and user-specific dictionaries are not used for suggestions.
- Learned words are files under the app's private storage. Settings, Text correction, Wipe everything learned deletes them. Words you restore with backspace after an autocorrection are stored in the same private area and are deleted by that wipe.
- Clipboard history is off unless you turn it on.

## Features

- Text replacement. Shortcuts expand to a phrase stored only on the device. Space or punctuation inserts the phrase. Backspace restores the shortcut.
- Space-bar trackpad. Holding or dragging the space bar moves the caret, including across lines.
- Autocorrect. Suggestions use the on-device dictionaries and key distance. Adjacent-key slips can be corrected. Words already in a dictionary, the user dictionary, a text replacement, or learned history are not replaced. `dont` can become `don't` when `dont` is not itself a dictionary word.
- Dictation. Holding the microphone key runs [whisper.cpp](https://github.com/ggml-org/whisper.cpp) on the device. The model file is `ggml-base.en-q5_1.bin`. CI checks its SHA-256 against `securekeyboard-project/asr/MODEL_SHA256` (`4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f`) before the APK is built. The app hashes the copy it loads and refuses a mismatch.
- Swipe typing uses the open-source decoder in this tree, not a closed-source library.

## Verify a download

Signed releases are GitHub pre-releases. Each one has an APK and `SHA256SUMS.txt`.

Signing certificate SHA-256, read from the signed APK with `apksigner verify --print-certs` (subject `CN=Secure Keyboard, O=zenv99`):

```
33eacd63355ee1b19abca1f997ea25a7946960bbf934411691273dde053c78d4
```

Check the APK hash against `SHA256SUMS.txt` before installing. In Obtainium or AppVerifier, compare the certificate SHA-256 above with the certificate of the APK you are about to install. A local `./gradlew :app:assembleRelease` is signed with that key only when `SK_KEYSTORE_FILE` and `SK_KEYSTORE_PASSWORD` are set. Otherwise the release build is signed with the debug key.

Application id of the signed build: `app.securekeyboard.keyboard`.

## Build

Requirements match CI: JDK 21, Android SDK platform 37, build-tools 37.0.0, NDK 28.0.13004108, CMake 3.22.1. The app compiles as Java 17.

```bash
git clone --recurse-submodules https://github.com/zenv99/Secure-Keyboard
cd Secure-Keyboard
./gradlew :app:assembleRelease
```

The release APK is under `app/build/outputs/apk/release/`. CI also runs unit tests, an emulator smoke test, and the privacy checks above. See [securekeyboard-project/README.md](securekeyboard-project/README.md) for those commands.

## Limitations

- No independent audit has been done.
- CI checks that the merged APK does not declare `INTERNET`. That is what makes GrapheneOS's per-app network toggle unable to grant this app a connection it did not declare. That toggle was not re-tested on a GrapheneOS phone for this tree.
- Native code uses the system allocator. Compatibility with GrapheneOS hardened_malloc was not run on a device, and it is not part of the API 34 emulator smoke test.
- Release APKs are not bit-identical across machines. Android Gradle Plugin still records build times.
- libFuzzer and ASan were not run on whisper.cpp or the dictionary JNI for this review.

## Security reports

See [SECURITY.md](SECURITY.md) for the review of permissions, native flags, models, and storage.

Report a vulnerability through [GitHub security advisories](https://github.com/zenv99/Secure-Keyboard/security/advisories/new) for this repository. Use a public issue only when the problem is already public. Do not send keystores, passwords, or message contents.

## License

[GNU General Public License v3.0](LICENSE) only.

Secure Keyboard is a modified version of HeliBoard (copyright Helium314 and contributors). HeliBoard is based on OpenBoard and the Android Open Source Keyboard (Apache License 2.0). This is not the official HeliBoard. The upstream readme is in [docs/HELIBOARD_README.md](docs/HELIBOARD_README.md). Other components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
