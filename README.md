# Secure Keyboard

A private Android keyboard. It works like the iOS keyboard, and nothing you type or say ever leaves your phone.

By Zen V. Based on [HeliBoard](https://github.com/HeliBorg/HeliBoard). Free software, GPL-3.0.

## What it does

- **Learning autocorrect.** Fixes mistyped words, including neighbouring-key slips, and learns how you type. Everything it learns stays on the device and can be wiped in Settings > Text correction.
- **Swipe typing.** Slide across the letters to type a word. The decoder is our own open-source code, not a closed-source library.
- **Push-to-talk dictation.** Hold the mic and speak. Speech is turned into text on the phone by [whisper.cpp](https://github.com/ggml-org/whisper.cpp) running the OpenAI Whisper base.en model. No audio is sent anywhere.
- **iOS-style cursor control.** Drag on the space bar to move the cursor smoothly in any direction.
- **Emoji suggestions.** Type a word like "sun" and get the matching emoji.
- **WhatsApp/iOS-style layout.** One emoji key and a large send key.

## Privacy

- The app has **no INTERNET permission**. It cannot connect to anything. The build pipeline checks this on every release and fails if it ever changes.
- No analytics, ads, telemetry, crash reporting, accounts or cloud features.
- Microphone permission is used only while you hold the dictation button.
- Android backup is disabled.
- Full details: [PRIVACY_POLICY.md](PRIVACY_POLICY.md) and [securekeyboard-project/docs/PRIVACY.md](securekeyboard-project/docs/PRIVACY.md).

**Not yet independently audited.** The code is public so anyone can review it, but no outside security firm has examined it.

## Install

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add the app with the URL `https://github.com/zenv99/Secure-Keyboard` and turn on "Include prereleases".
3. Enable Secure Keyboard in Settings > System > Keyboard.

Or download the APK from the [Releases page](https://github.com/zenv99/Secure-Keyboard/releases) and check it against `SHA256SUMS.txt`.

## Build

```
git clone --recurse-submodules https://github.com/zenv99/Secure-Keyboard
./gradlew :app:assembleRelease
```

Builds and checks run in GitHub Actions. See [securekeyboard-project/README.md](securekeyboard-project/README.md) for the development guide.

## License and credits

Secure Keyboard is licensed under the [GNU General Public License v3.0](LICENSE). It is a modified version of HeliBoard (copyright Helium314 and contributors), which is based on OpenBoard and the Android Open Source Keyboard. This is not the official HeliBoard. The original HeliBoard readme is kept in [docs/HELIBOARD_README.md](docs/HELIBOARD_README.md).

Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
