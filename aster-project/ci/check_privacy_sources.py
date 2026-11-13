#!/usr/bin/env python3
"""Fail closed if source privacy declarations or backup exclusions regress."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
REQUIRED_DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
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
    if permission.get(ANDROID + "name") == "android.permission.INTERNET":
        fail("INTERNET permission declared in source manifest")

legacy_path = ROOT / "app/src/main/res/xml/backup_rules.xml"
legacy = ET.parse(legacy_path).getroot()
if legacy.tag != "full-backup-content":
    fail("unexpected legacy backup rules root")
legacy_excluded = {
    item.get("domain") for item in legacy.findall("exclude")
    if item.get("path") == "."
}
if not REQUIRED_DOMAINS.issubset(legacy_excluded):
    fail(f"legacy backup exclusions incomplete: {sorted(REQUIRED_DOMAINS - legacy_excluded)}")

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
    if not REQUIRED_DOMAINS.issubset(excluded):
        fail(f"{section_name} exclusions incomplete: {sorted(REQUIRED_DOMAINS - excluded)}")

print("PASS: source manifest declares no INTERNET permission and disables backup/device transfer")
