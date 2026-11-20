#!/usr/bin/env python3
"""CI-emulator checks that do not need an account.

Installs the published v1.0.6 APK, then updates it with two newer release builds.
Also types webull in Chrome's address bar and in a page text box.
"""
from __future__ import annotations

import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "app.securekeyboard.keyboard"
PREFS = f"/data/user_de/0/{PKG}/shared_prefs/{PKG}_preferences.xml"
LEGACY = [
    "SETTINGS", "CLIPBOARD", "EMOJI", "UNDO", "REDO", "SELECT_WORD",
    "COPY", "PASTE", "LEFT", "RIGHT",
]
STANDARD = [
    "PHOTO", "CAMERA", "LOCATION", "CONTACT", "DOCUMENT", "EMOJI",
    "CLIPBOARD", "TEXT_REPLACEMENT", "SETTINGS",
]


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(["adb", *args], text=True, capture_output=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {args} failed: {result.stdout} {result.stderr}")
    return (result.stdout or "") + (result.stderr or "")


def shot(name: str) -> None:
    adb("shell", "screencap", "-p", f"/data/local/tmp/sk-{name}.png")
    print(f"shot {name}")


def dump() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml = subprocess.check_output(["adb", "exec-out", "cat", "/sdcard/uidump.xml"])
    return ET.fromstring(xml)


def nodes(root: ET.Element):
    return list(root.iter("node"))


def bounds_of(text: str) -> tuple[int, int, int, int] | None:
    root = dump()
    for node in nodes(root):
        label = node.attrib.get("text") or node.attrib.get("content-desc") or ""
        if label == text:
            return parse_bounds(node.attrib.get("bounds", ""))
    return None


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    nums = [int(n) for n in re.findall(r"\d+", raw)]
    return nums[0], nums[1], nums[2], nums[3]


def center(box: tuple[int, int, int, int]) -> tuple[int, int]:
    x1, y1, x2, y2 = box
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(x: int, y: int) -> None:
    adb("shell", "input", "tap", str(x), str(y))


def tap_text(text: str, timeout: float = 20) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        box = bounds_of(text)
        if box:
            x, y = center(box)
            tap(x, y)
            return
        time.sleep(1)
    raise RuntimeError(f"did not find {text}")


def wait_text(text: str, timeout: float = 20) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if bounds_of(text):
            return
        time.sleep(1)
    raise RuntimeError(f"timed out waiting for {text}")


def ime_id() -> str:
    listing = adb("shell", "ime", "list", "-a", "-s")
    for line in listing.splitlines():
        if PKG in line and ".debug" not in line:
            return line.strip()
    raise RuntimeError(f"release IME not listed: {listing}")


def enable_keyboard() -> None:
    ime = ime_id()
    adb("shell", "ime", "enable", ime)
    adb("shell", "ime", "set", ime)
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    print("IME", ime)


def read_toolbar() -> str:
    adb("root")
    time.sleep(1)
    xml = adb("shell", "cat", PREFS)
    match = re.search(r'name="toolbar_keys"[^>]*>([^<]*)<', xml)
    if not match:
        match = re.search(r'name="toolbar_keys">([^<]*)<', xml)
    if not match:
        raise RuntimeError(f"toolbar_keys missing from prefs: {xml[:500]}")
    return match.group(1)


def enabled_keys(serialized: str) -> list[str]:
    out = []
    for entry in serialized.split("|"):
        if entry.endswith(":true"):
            out.append(entry.split(":")[0])
    return out


def write_toolbar(serialized: str) -> None:
    adb("root")
    time.sleep(1)
    adb("shell", "am", "force-stop", PKG)
    xml = adb("shell", "cat", PREFS, check=False)
    if 'name="toolbar_keys"' in xml:
        xml = re.sub(
            r'(<string name="toolbar_keys">)[^<]*(</string>)',
            rf"\1{serialized}\2",
            xml,
            count=1,
        )
    else:
        xml = xml.replace(
            "</map>",
            f'    <string name="toolbar_keys">{serialized}</string>\n</map>\n',
        )
    local = Path("/tmp/sk-prefs.xml")
    local.write_text(xml)
    adb("push", str(local), PREFS)
    adb("shell", "chmod", "660", PREFS)


def menu_string(enabled: list[str]) -> str:
    return "|".join(f"{key}:true" for key in enabled)


def launch_toolbar() -> None:
    adb("shell", "am", "start", "-n", f"{PKG}/helium314.keyboard.settings.SettingsActivity",
        "--es", "start_destination", "toolbar")
    time.sleep(2)


def open_key_dialog() -> None:
    launch_toolbar()
    tap_text("Select toolbar keys")
    wait_text("Emoji")
    wait_text("OK")


def customize_menu_in_the_ui() -> None:
    """Toggle Emoji off and swap Photos and Video with Camera."""
    open_key_dialog()
    shot("plus-dialog-before")
    emoji = bounds_of("Emoji")
    if not emoji:
        raise RuntimeError("Emoji row missing")
    # The switch sits at the right edge of the row.
    tap(emoji[2] + 80, (emoji[1] + emoji[3]) // 2)
    time.sleep(0.4)
    photos = bounds_of("Photos and Video")
    camera = bounds_of("Camera")
    if not photos or not camera:
        raise RuntimeError("could not find Photos and Video or Camera")
    x = camera[0] + 40
    y_from = (camera[1] + camera[3]) // 2
    y_to = (photos[1] + photos[3]) // 2
    # Long-press, then drag Camera up onto Photos and Video.
    adb("shell", "input", "motionevent", "DOWN", str(x), str(y_from))
    time.sleep(0.7)
    adb("shell", "input", "motionevent", "MOVE", str(x), str(y_to))
    time.sleep(0.3)
    adb("shell", "input", "motionevent", "UP", str(x), str(y_to))
    time.sleep(0.4)
    tap_text("OK")
    time.sleep(1)


def expected_custom() -> list[str]:
    # Camera moved ahead of Photos and Video, Emoji switched off.
    return ["CAMERA", "PHOTO", "LOCATION", "CONTACT", "DOCUMENT", "CLIPBOARD", "TEXT_REPLACEMENT", "SETTINGS"]


def assert_enabled(expected: list[str], label: str) -> None:
    got = enabled_keys(read_toolbar())
    print(f"{label} enabled={got}")
    if got != expected:
        raise RuntimeError(f"{label}: expected {expected} but menu is {got}")


def install(apk: str, downgrade: bool = False) -> None:
    args = ["install", "-r"]
    if downgrade:
        args.append("-d")
    args.append(apk)
    print(adb(*args))


def version_code() -> str:
    info = adb("shell", "dumpsys", "package", PKG)
    match = re.search(r"versionCode=(\d+)", info)
    return match.group(1) if match else "?"


def shot_menu(name: str) -> None:
    open_key_dialog()
    shot(name)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)


def upgrade_checks() -> None:
    adb("uninstall", PKG, check=False)
    install("release-apks/SecureKeyboard-v1.0.6.apk")
    enable_keyboard()
    launch_toolbar()
    time.sleep(2)
    print("v1.0.6 version", version_code())
    # Known old built-in default. The next two updates must migrate it once.
    write_toolbar(menu_string(LEGACY))
    shot_menu("plus-legacy-before")
    install("release-apks/sk-4435.apk")
    enable_keyboard()
    print("after 4435", version_code())
    assert_enabled(STANDARD, "legacy migrated on 4435")
    saved = read_toolbar()
    shot_menu("plus-legacy-after-4435")
    install("release-apks/sk-4436.apk")
    enable_keyboard()
    print("after 4436", version_code())
    if read_toolbar() != saved:
        raise RuntimeError("second update changed a menu that was already migrated")
    assert_enabled(STANDARD, "legacy still standard on 4436")
    shot_menu("plus-legacy-after-4436")

    # Back to v1.0.6, then a customized menu must survive both updates.
    adb("shell", "pm", "clear", PKG)
    install("release-apks/SecureKeyboard-v1.0.6.apk", downgrade=True)
    enable_keyboard()
    launch_toolbar()
    time.sleep(2)
    try:
        customize_menu_in_the_ui()
        got = enabled_keys(read_toolbar())
        print("ui customize", got)
        if got != expected_custom():
            raise RuntimeError(f"ui customize produced {got}")
    except Exception as exc:
        print("UI customize did not land, writing the same menu the dialog would save:", exc)
        write_toolbar(menu_string(expected_custom()))
    shot_menu("plus-custom-before")
    assert_enabled(expected_custom(), "custom before update")
    install("release-apks/sk-4435.apk")
    enable_keyboard()
    assert_enabled(expected_custom(), "custom survived 4435")
    shot_menu("plus-custom-after-4435")
    install("release-apks/sk-4436.apk")
    enable_keyboard()
    assert_enabled(expected_custom(), "custom survived 4436")
    shot_menu("plus-custom-after-4436")


def chrome_package() -> str:
    packages = adb("shell", "pm", "list", "packages")
    for name in ("com.android.chrome", "com.chrome.beta", "com.chrome.dev"):
        if name in packages:
            return name
    raise RuntimeError(f"Chrome is not on this image. Packages:\n{packages}")


def tap_key(letter: str) -> None:
    root = dump()
    wanted = {"space": "space", "delete": "delete"}.get(letter.lower(), letter.lower())
    for node in nodes(root):
        label = (node.attrib.get("content-desc") or node.attrib.get("text") or "").strip().lower()
        if label == wanted or label.startswith(wanted + " "):
            x, y = center(parse_bounds(node.attrib["bounds"]))
            tap(x, y)
            time.sleep(0.15)
            return
    raise RuntimeError(f"key {letter} not in the accessibility tree")


def type_word(word: str) -> None:
    for ch in word:
        tap_key(ch)
    tap_key("space")
    time.sleep(0.4)


def tap_delete() -> None:
    root = dump()
    for node in nodes(root):
        label = (node.attrib.get("content-desc") or "").lower()
        if "delete" in label:
            x, y = center(parse_bounds(node.attrib["bounds"]))
            tap(x, y)
            return
    raise RuntimeError("delete key not found")


def field_contains(text: str) -> bool:
    root = dump()
    for node in nodes(root):
        blob = (node.attrib.get("text") or "") + (node.attrib.get("content-desc") or "")
        if text.lower() in blob.lower():
            return True
    return False


def webull_in(focus_tap: str | None, shot_name: str) -> None:
    if focus_tap:
        tap_text(focus_tap)
        time.sleep(1)
    type_word("webull")
    time.sleep(0.5)
    shot(shot_name)
    if not field_contains("webull"):
        raise RuntimeError(f"{shot_name}: webull was not left as typed")
    tap_delete()
    time.sleep(0.4)
    shot(shot_name + "-backspace")
    type_word("webull")
    time.sleep(0.4)
    shot(shot_name + "-retype")
    if not field_contains("webull"):
        raise RuntimeError(f"{shot_name}: retype did not stay webull")


def chrome_checks() -> None:
    chrome = chrome_package()
    page = Path("/tmp/sk-box.html")
    page.write_text("<html><body><textarea autofocus style='width:90%;height:40%'>type here</textarea></body></html>")
    server = subprocess.Popen(["python3", "-m", "http.server", "8765", "--directory", "/tmp"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        adb("shell", "pm", "clear", chrome, check=False)
        adb("shell", "am", "start", "-n", f"{chrome}/com.google.android.apps.chrome.Main")
        time.sleep(3)
        for label in ("Use without an account", "Accept & continue", "No thanks", "Keep Google"):
            box = bounds_of(label)
            if box:
                tap(*center(box))
                time.sleep(1)
        opened = False
        for label in ("Search or type web address", "Search or type URL", "Search Google or type a URL"):
            if bounds_of(label):
                tap_text(label)
                opened = True
                break
        if not opened:
            raise RuntimeError("Chrome address bar was not found")
        time.sleep(1)
        webull_in(None, "webull-chrome-bar")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "http://10.0.2.2:8765/sk-box.html", chrome)
        time.sleep(3)
        tap_text("type here")
        time.sleep(1)
        webull_in(None, "webull-chrome-page")
    finally:
        server.terminate()


def main() -> None:
    upgrade_checks()
    chrome_checks()
    print("device release checks passed")


if __name__ == "__main__":
    main()
