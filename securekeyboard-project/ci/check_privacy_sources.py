#!/usr/bin/env python3
"""Fail closed if source privacy declarations or backup exclusions regress."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
LEGACY_DOMAINS = {"root", "file", "database", "sharedpref", "external"}
MODERN_DOMAINS = LEGACY_DOMAINS | {
    "device_root", "device_file", "device_database", "device_sharedpref",
}

def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)

manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
app = manifest.find("application")
if app is None:
    fail("application element missing")
if app.get(ANDROID + "allowBackup") != "false":
    fail("android:allowBackup must be false")
if app.get(ANDROID + "fullBackupContent") != "@xml/backup_rules":
    fail("legacy fullBackupContent rules not attached")
if app.get(ANDROID + "dataExtractionRules") != "@xml/data_extraction_rules":
    fail("Android 12+ dataExtractionRules not attached")
for permission in manifest.findall("uses-permission"):
    name = permission.get(ANDROID + "name")
    if name == "android.permission.INTERNET":
        fail("INTERNET permission declared in source manifest")
    if name == "android.permission.ACCESS_BACKGROUND_LOCATION":
        fail("ACCESS_BACKGROUND_LOCATION must never be declared")

legacy_path = ROOT / "app/src/main/res/xml/backup_rules.xml"
legacy = ET.parse(legacy_path).getroot()
if legacy.tag != "full-backup-content":
    fail("unexpected legacy backup rules root")
legacy_excluded = {
    item.get("domain") for item in legacy.findall("exclude")
    if item.get("path") == "."
}
if not LEGACY_DOMAINS.issubset(legacy_excluded):
    fail(f"legacy backup exclusions incomplete: {sorted(LEGACY_DOMAINS - legacy_excluded)}")

modern_path = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
modern = ET.parse(modern_path).getroot()
if modern.tag != "data-extraction-rules":
    fail("unexpected data extraction rules root")
for section_name in ("cloud-backup", "device-transfer"):
    section = modern.find(section_name)
    if section is None:
        fail(f"{section_name} section missing")
    excluded = {
        item.get("domain") for item in section.findall("exclude")
        if item.get("path") == "."
    }
    if not MODERN_DOMAINS.issubset(excluded):
        fail(f"{section_name} exclusions incomplete: {sorted(MODERN_DOMAINS - excluded)}")

# Permissions that may appear in source manifests. Anything else fails the build.
# RECORD_AUDIO is used only while the dictation button is held (see AsrService).
# VIBRATE is key and dictation haptic feedback.
# RECEIVE_BOOT_COMPLETED restores the setup-icon state after reboot. The receiver
# ignores every action that is not a protected system broadcast.
ALLOWED_PERMISSIONS = {
    "android.permission.RECORD_AUDIO",
    "android.permission.VIBRATE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    # Asked only when the user taps Location. Never background.
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
}
# Exported components. A new exported component must be added here on purpose.
# Services must also carry an android:permission.
ALLOWED_EXPORTED = {
    "LatinIME": "android.permission.BIND_INPUT_METHOD",
    "helium314.keyboard.latin.LatinIME": "android.permission.BIND_INPUT_METHOD",
    ".spellcheck.AndroidSpellCheckerService": "android.permission.BIND_TEXT_SERVICE",
    "helium314.keyboard.latin.spellcheck.AndroidSpellCheckerService": "android.permission.BIND_TEXT_SERVICE",
    "helium314.keyboard.settings.SettingsActivity": None,  # launcher
    "helium314.keyboard.settings.SettingsActivity2": None,  # open a .dict the user picked
    ".spellcheck.SpellCheckerSettingsActivity": None,  # system spell-checker settings
    "helium314.keyboard.latin.spellcheck.SpellCheckerSettingsActivity": None,
    "SystemBroadcastReceiver": None,  # protected system broadcasts only
    "helium314.keyboard.latin.SystemBroadcastReceiver": None,
}

def local_name(value: str) -> str:
    return value or ""

manifests = [manifest_path, ROOT / "asr/src/main/AndroidManifest.xml"]
for path in manifests:
    if not path.is_file():
        continue
    tree = ET.parse(path).getroot()
    for permission in tree.findall("uses-permission"):
        name = permission.get(ANDROID + "name")
        if name == "android.permission.INTERNET":
            fail(f"INTERNET permission declared in {path}")
        if name == "android.permission.ACCESS_BACKGROUND_LOCATION":
            fail(f"ACCESS_BACKGROUND_LOCATION declared in {path}")
        if name not in ALLOWED_PERMISSIONS:
            fail(f"permission {name} in {path} is not on the allowlist")
    for tag in ("activity", "service", "receiver", "provider"):
        for element in tree.iter(tag):
            if element.get(ANDROID + "exported") != "true":
                continue
            name = element.get(ANDROID + "name")
            if name not in ALLOWED_EXPORTED:
                fail(f"exported {tag} {name} in {path} is not on the allowlist")
            required_permission = ALLOWED_EXPORTED[name]
            if tag == "service" and element.get(ANDROID + "permission") != required_permission:
                fail(f"exported service {name} must require {required_permission}")

secret_suffixes = {".jks", ".keystore", ".p12", ".key"}
for path in ROOT.rglob("*"):
    if "third_party" in path.parts or "build" in path.parts or ".git" in path.parts:
        continue
    if path.suffix.lower() in secret_suffixes:
        fail(f"secret-looking file in the tree: {path.relative_to(ROOT)}")

print("PASS: source manifest declares no INTERNET permission and disables backup/device transfer")
print("PASS: permissions and exported components match the allowlist; no keystore is committed")
