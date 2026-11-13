# Privacy and security requirements

## Release invariants

- No `android.permission.INTERNET` in the merged release manifest.
- No analytics, ads, telemetry, crash upload, remote configuration, cloud inference or account SDK.
- No user text or audio transmitted off-device.
- Password fields: no suggestions, voice capture or personalization.
- Honor `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- Backup/device-transfer rules exclude learned words, clipboard contents and audio.
- No typed text, recognized speech, dictionary entries or audio metadata in release logs.
- Model artifacts and native dependencies are version-pinned and hash-verified.

## Data handling

Treat typed text, recognized speech, clipboard contents and learned vocabulary as sensitive. Keep them in app-private storage. If personalization persists, encrypt it using an Android Keystore-backed AES-GCM key and define deletion/key-rotation behavior. Never log plaintext. Clipboard history should be opt-in, encrypted, short-lived and disabled for sensitive fields.

## Threat model

Review accidental INTERNET permission from transitive manifest merging, plaintext logs/backups, unsafe exported components, compromised model/dependency artifacts and accidental learning from sensitive fields.

A missing INTERNET permission is a strong control, not a complete security audit. Review the complete build, dependencies, exported components, backup rules, signing and distribution pipeline.
