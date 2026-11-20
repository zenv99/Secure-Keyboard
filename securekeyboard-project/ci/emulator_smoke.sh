#!/usr/bin/env bash
# Runs inside the emulator: installs the debug keyboard, makes it the active keyboard, runs the smoke tests.
set -euo pipefail
PKG=app.securekeyboard.keyboard.debug
./gradlew --no-daemon :app:installDebugNoMinify
IME="$(adb shell ime list -a -s | tr -d '\r' | grep "$PKG" | head -1)"
echo "IME: $IME"
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
# Dictation hold, send, the trackpad, the plus menu, and the mic/Send face
# (Settings search and a field that never reports a selection) live in this package.
set +e
./gradlew --no-daemon :app:connectedDebugNoMinifyAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=helium314.keyboard.smoke
instrumented=$?
python3 securekeyboard-project/ci/device_release_checks.py
device_checks=$?
set -e
mkdir -p emulator-screenshots
adb shell ls -l /data/local/tmp/sk-*.png
adb shell 'ls /data/local/tmp/sk-*.png' | tr -d '\r' | while read -r remote; do
  adb pull "$remote" "emulator-screenshots/$(basename "$remote")"
done
for name in trackpad-left trackpad-right trackpad-up trackpad-down send-single settings-search-send settings-search-mic shopee-send shopee-mic unspecified-send unspecified-mic autocorrect-sentence \
  plus-legacy-before plus-legacy-after-4435 plus-legacy-after-4436 \
  plus-custom-before plus-custom-after-4435 plus-custom-after-4436 \
  webull-chrome-bar webull-chrome-bar-backspace webull-chrome-bar-retype \
  webull-chrome-page webull-chrome-page-backspace webull-chrome-page-retype; do
  test -s "emulator-screenshots/sk-${name}.png"
done
find emulator-screenshots -type f -name '*.png' -print
exit "$(( instrumented || device_checks ))"
