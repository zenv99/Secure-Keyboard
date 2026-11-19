# Privacy and security requirements

## Release invariants

- No `android.permission.INTERNET` in the merged release manifest.
- No `android.permission.ACCESS_BACKGROUND_LOCATION`. Coarse and fine location are requested only when Location is tapped, for one fix, inserted as a maps link, and never stored.
- No analytics, ads, telemetry, crash upload, remote configuration, cloud inference or account SDK.
- No user text or audio transmitted off-device.
- Password fields: no suggestions, voice capture or personalization.
- Honor `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- Android backup disabled, with explicit empty cloud-backup and device-transfer rules.
- No typed text, recognized speech, dictionary entries or audio metadata in release logs.
- Model artifacts and native dependencies are version-pinned and hash-verified.

## Implemented in the current development branch

- The app manifest now sets `android:allowBackup="false"`.
- Android 12+ data extraction rules exclude all app data from cloud backup and device-to-device transfer.
- Legacy full-backup rules exclude all app data.
- CI inspects the built APK manifest and fails if INTERNET permission is present or backup protections are missing.

These controls are an initial privacy hardening step. They do not yet prove every dependency or code path is free of network behavior; a full dependency and source audit remains required.

## Data handling

Treat typed text, recognized speech, clipboard contents and learned vocabulary as sensitive. Keep them in app-private storage. If personalization persists, encrypt it using an Android Keystore-backed AES-GCM key and define deletion/key-rotation behavior. Never log plaintext. Clipboard history should be opt-in, encrypted, short-lived and disabled for sensitive fields.

## Threat model

Review accidental INTERNET permission from transitive manifest merging, plaintext logs/backups, unsafe exported components, compromised model/dependency artifacts and accidental learning from sensitive fields.

A missing INTERNET permission is a strong control, not a complete security audit. Review the complete build, dependencies, exported components, backup rules, signing and distribution pipeline.

## Learning how you type (on device)

"Learn how I type" (Settings > Text correction) measures where your taps land relative to the centre
of the key you hit and stores three numbers on this phone: average horizontal offset, average vertical
offset, and a tap count. No typed text, key sequences or timestamps are stored. The numbers live in a
private app file, are excluded from backups, never leave the device, and are wiped when you turn the
setting off. The correction is capped at 12% of a key's size and is not applied until 300 taps have
been seen.
