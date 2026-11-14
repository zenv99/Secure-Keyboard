#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 <apk> [<apk> ...]" >&2; exit 2; fi
if ! command -v apkanalyzer >/dev/null 2>&1; then
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -n "$d" ] && [ -x "$d/cmdline-tools/latest/bin/apkanalyzer" ]; then
      PATH="$d/cmdline-tools/latest/bin:$PATH"; break
    fi
  done
fi
command -v apkanalyzer >/dev/null 2>&1 || {
  echo "apkanalyzer is required to verify the merged APK manifest." >&2
  exit 2
}
for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }
  permissions="$(apkanalyzer manifest permissions "$apk")"
  if grep -Fq "android.permission.INTERNET" <<<"$permissions"; then
    echo "FAIL: INTERNET permission found in $apk" >&2; exit 1
  fi
  # Only these permissions may ever ship. RECORD_AUDIO is for push-to-talk dictation (on device).
  unexpected="$(grep -Ev '^(android\.permission\.(RECEIVE_BOOT_COMPLETED|VIBRATE|RECORD_AUDIO)|.*\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)$' <<<"$permissions" || true)"
  if [[ -n "$unexpected" ]]; then
    echo "FAIL: unexpected permissions in $apk:" >&2; echo "$unexpected" >&2; exit 1
  fi
  manifest="$(apkanalyzer manifest print "$apk")"
  if ! grep -Fq 'android:allowBackup="false"' <<<"$manifest"; then
    echo "FAIL: allowBackup is not false in $apk" >&2; exit 1
  fi
  if ! grep -Eq 'android:dataExtractionRules="@' <<<"$manifest"; then
    echo "FAIL: data extraction rules are not declared in $apk" >&2; exit 1
  fi
  if ! grep -Eq 'android:fullBackupContent="@' <<<"$manifest"; then
    echo "FAIL: legacy full-backup rules are not declared in $apk" >&2; exit 1
  fi
  echo "PASS: no INTERNET permission; backup and device-transfer controls declared in $apk"
done
