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
# Dictation hold, the send matrix, the trackpad, and the plus menu live in this package.
./gradlew --no-daemon :app:connectedDebugNoMinifyAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=helium314.keyboard.smoke
mkdir -p emulator-screenshots
adb shell ls -l /data/local/tmp/sk-*.png
for name in plus-menu plus-menu-scrolled trackpad symbols symbols-shifted; do
  adb pull "/data/local/tmp/sk-${name}.png" "emulator-screenshots/${name}.png"
done
find emulator-screenshots -type f -name '*.png' -print
test -n "$(find emulator-screenshots -type f -name '*.png' -print)"
