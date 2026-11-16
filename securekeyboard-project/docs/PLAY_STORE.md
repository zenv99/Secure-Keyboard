# Google Play submission notes

## Account
- Create a Play developer account (one-time $25, ID verification). Decide: personal or organization (Archon Aerospace Inc. needs a D-U-N-S number).
- New personal accounts must run a closed test with at least 12 testers for 14 days before applying for production. Check the current rule in Play Console help.

## Build
- CI produces `secure-keyboard-release-aab` (signed with the upload key) on every push to main. Download it from the Actions run.
- Enrol in Play App Signing when creating the app. Google then re-signs what users download. Builds installed from Play and builds installed from GitHub/Obtainium have different signatures and cannot update each other.
- Package name: `app.securekeyboard.keyboard` (cannot be changed after the first upload).

## Store listing
- Title, short and full description: `fastlane/metadata/android/en-US/`.
- Icon: `images/icon.png` (512 px). Feature graphic: `images/featureGraphic.png` (1024x500).
- Phone screenshots (at least 2) must be taken on a real device. Not included.
- Privacy policy URL: https://github.com/zenv99/Secure-Keyboard/blob/main/PRIVACY_POLICY.md
- Category: Tools. Contact email is required by Play.

## Data safety form
- Does the app collect or share user data? **No.**
- Is data encrypted in transit? Not applicable (no data transmitted; no internet permission).
- Data deletion: not applicable; learned data is local and can be wiped in Settings > Text correction.

## Permissions declarations
- RECORD_AUDIO: used only while the user holds the dictation button, processed on-device.
- Keyboards (IMEs) get extra scrutiny: Play reviews that the keyboard does not transmit typed text. State that the app has no INTERNET permission.

## Honest claims
- Keep "not yet independently audited" in the listing.
- Do not use HeliBoard's name or logo. Credit appears in the About screen and README.
