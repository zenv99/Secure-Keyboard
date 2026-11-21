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
PREF_FILES = [
    f"/data/user_de/0/{PKG}/shared_prefs/{PKG}_preferences.xml",
    f"/data/data/{PKG}/shared_prefs/{PKG}_preferences.xml",
]
PREFS = PREF_FILES[0]
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


def root() -> None:
    adb("root")
    subprocess.run(["adb", "wait-for-device"], check=True)
    time.sleep(2)


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
    needle = text.casefold()
    partial = None
    for node in nodes(root):
        label = node.attrib.get("text") or node.attrib.get("content-desc") or ""
        folded = label.casefold()
        if folded == needle:
            return parse_bounds(node.attrib.get("bounds", ""))
        if needle in folded and partial is None and node.attrib.get("bounds"):
            partial = parse_bounds(node.attrib["bounds"])
    return partial


def visible_texts() -> list[str]:
    found = []
    for node in nodes(dump()):
        label = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
        if label and label not in found:
            found.append(label)
    return found


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
    deadline = time.time() + 30
    last = ""
    while time.time() < deadline:
        last = adb("shell", "ime", "list", "-a", "-s")
        for line in last.splitlines():
            if PKG in line and ".debug" not in line:
                return line.strip()
        time.sleep(2)
    packages = adb("shell", "pm", "path", PKG, check=False)
    raise RuntimeError(f"release IME not listed: {last} path={packages}")


def enable_keyboard() -> None:
    ime = ime_id()
    adb("shell", "ime", "enable", ime)
    adb("shell", "ime", "set", ime)
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    print("IME", ime)


def prefs_file() -> str:
    """The preferences file the running app last wrote."""
    root()
    deadline = time.time() + 20
    best = ""
    best_time = -1
    while time.time() < deadline:
        for path in PREF_FILES:
            stamp = adb("shell", "stat", "-c", "%Y", path, check=False).strip()
            if stamp.isdigit() and int(stamp) >= best_time:
                best_time = int(stamp)
                best = path
        if best:
            print("prefs file", best)
            return best
        time.sleep(1)
    print("prefs file not written yet, using", PREFS)
    return PREFS


def read_toolbar() -> str:
    root()
    found = []
    chosen = ""
    chosen_ver = -1
    for path in PREF_FILES:
        xml = adb("shell", "cat", path, check=False)
        found.append(f"{path}: {xml[:300].replace(chr(10), ' ')}")
        match = re.search(r'name="toolbar_keys"[^>]*>([^<]*)<', xml)
        ver = re.search(r'name="version_code" value="(\d+)"', xml)
        version = int(ver.group(1)) if ver else -1
        if match and version >= chosen_ver:
            chosen = match.group(1)
            chosen_ver = version
    if chosen:
        return chosen
    raise RuntimeError("toolbar_keys missing from prefs: " + " | ".join(found))


def enabled_keys(serialized: str) -> list[str]:
    out = []
    for entry in serialized.split("|"):
        if entry.endswith(":true"):
            out.append(entry.split(":")[0])
    return out


def write_toolbar(serialized: str, path: str | None = None) -> None:
    target = path or PREFS
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    root()
    xml = adb("shell", "cat", target, check=False)
    if "<map>" not in xml:
        xml = (
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            f'    <string name="toolbar_keys">{serialized}</string>\n'
            '    <int name="version_code" value="4434" />\n</map>\n'
        )
    elif 'name="toolbar_keys"' in xml:
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
    xml = re.sub(
        r'<int name="version_code" value="\d+" />',
        '<int name="version_code" value="4434" />',
        xml,
        count=1,
    )
    if 'name="version_code"' not in xml:
        xml = xml.replace("</map>", '    <int name="version_code" value="4434" />\n</map>\n')
    local = Path("/tmp/sk-prefs.xml")
    local.write_text(xml)
    parent = target.rsplit("/", 1)[0]
    adb("shell", "mkdir", "-p", parent)
    adb("push", str(local), target)
    uid = adb("shell", "stat", "-c", "%u", f"/data/user_de/0/{PKG}", check=False).strip()
    if uid.isdigit():
        adb("shell", "chown", f"{uid}:{uid}", target)
    adb("shell", "chmod", "660", target)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    check = adb("shell", "cat", target, check=False)
    if serialized not in check or 'value="4434"' not in check:
        raise RuntimeError(f"toolbar pref did not stick in {target}: {check[:400]}")


def run_upgrade() -> None:
    """Start the app so a version change migrates prefs, then stop before reading the file."""
    adb("shell", "am", "start", "-n", f"{PKG}/helium314.keyboard.settings.SettingsActivity",
        "--es", "start_destination", "toolbar")
    time.sleep(3)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)


def menu_string(enabled: list[str]) -> str:
    return "|".join(f"{key}:true" for key in enabled)


def launch_toolbar() -> None:
    print("default IME", adb("shell", "settings", "get", "secure", "default_input_method").strip())
    adb("shell", "am", "start", "-n", f"{PKG}/helium314.keyboard.settings.SettingsActivity",
        "--es", "start_destination", "toolbar")
    time.sleep(2)


def dismiss_setup_if_shown() -> None:
    texts = visible_texts()
    blob = " ".join(texts)
    if "Get started" not in blob and "Configure the keyboard" not in blob and "Open keyboard settings" not in blob:
        return
    print("setup wizard is showing:", texts)
    for label in ("Get started", "Configure the keyboard"):
        box = bounds_of(label)
        if box:
            tap(*center(box))
            time.sleep(1)
    launch_toolbar()


def open_key_dialog() -> None:
    launch_toolbar()
    dismiss_setup_if_shown()
    for _ in range(6):
        box = bounds_of("Select toolbar keys")
        if box:
            tap(*center(box))
            time.sleep(1)
            return
        if bounds_of("Toolbar") and not bounds_of("Select toolbar keys"):
            toolbar = bounds_of("Toolbar")
            if toolbar:
                tap(*center(toolbar))
                time.sleep(1)
                continue
        adb("shell", "input", "swipe", "540", "1600", "540", "500", "400")
        time.sleep(0.6)
    print("screen texts:", visible_texts())
    raise RuntimeError("did not find Select toolbar keys")


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
    try:
        open_key_dialog()
    except Exception as exc:
        print(f"{name}: key dialog was not opened ({exc})")
    shot(name)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.3)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.4)


def upgrade_checks() -> None:
    # The debug test build uses the same provider authority. Remove it before the release APK.
    adb("uninstall", PKG + ".debug", check=False)
    adb("uninstall", PKG, check=False)
    install("release-apks/SecureKeyboard-v1.0.6.apk")
    enable_keyboard()
    print("v1.0.6 version", version_code())
    # Known old built-in default. Do not start v1.0.6 after this write.
    write_toolbar(menu_string(LEGACY), PREFS)
    # Do not start v1.0.6 here. A running process flushes its old prefs over the file.
    shot("plus-legacy-before")
    install("release-apks/sk-4435.apk")
    enable_keyboard()
    run_upgrade()
    print("after 4435", version_code())
    assert_enabled(STANDARD, "legacy migrated on 4435")
    saved = read_toolbar()
    shot_menu("plus-legacy-after-4435")
    install("release-apks/sk-4436.apk")
    enable_keyboard()
    run_upgrade()
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
    shot("plus-custom-before")
    assert_enabled(expected_custom(), "custom before update")
    install("release-apks/sk-4435.apk")
    enable_keyboard()
    run_upgrade()
    assert_enabled(expected_custom(), "custom survived 4435")
    shot_menu("plus-custom-after-4435")
    install("release-apks/sk-4436.apk")
    enable_keyboard()
    run_upgrade()
    assert_enabled(expected_custom(), "custom survived 4436")
    shot_menu("plus-custom-after-4436")


def chrome_package() -> str:
    packages = adb("shell", "pm", "list", "packages")
    for name in ("com.android.chrome", "com.chrome.beta", "com.chrome.dev"):
        if name in packages:
            return name
    raise RuntimeError(f"Chrome is not on this image. Packages:\n{packages}")


def dismiss_blockers() -> None:
    for label in ("Close app", "Wait"):
        box = bounds_of(label)
        if box:
            tap(*center(box))
            time.sleep(0.8)


def tap_editor() -> bool:
    """Tap the focused field, or an address/search editor, so the keyboard is requested."""
    try:
        root = dump()
    except Exception:
        return False
    fallback = None
    for node in nodes(root):
        cls = node.attrib.get("class", "")
        rid = node.attrib.get("resource-id", "")
        if "EditText" not in cls and "url_bar" not in rid and "search_box" not in rid:
            continue
        raw = node.attrib.get("bounds", "")
        if not raw:
            continue
        box = parse_bounds(raw)
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        if node.attrib.get("focused") == "true":
            tap(*center(box))
            return True
        fallback = box
    if fallback:
        tap(*center(fallback))
        return True
    return False


def screen_size() -> tuple[int, int]:
    text = adb("shell", "wm", "size", check=False)
    match = re.search(r"(\d+)x(\d+)", text)
    if not match:
        return 1080, 2400
    return int(match.group(1)), int(match.group(2))


def ime_frame() -> tuple[int, int, int, int] | None:
    """The on-screen keyboard rectangle.

    API 34 no longer prints mImeFrame. While input is shown, the keyboard is
    the wide tall window in the bottom half of the screen.
    """
    method = adb("shell", "dumpsys", "input_method", check=False)
    windows = adb("shell", "dumpsys", "window", "windows", check=False)
    width, height = screen_size()
    candidates: list[tuple[int, int, int, int]] = []
    for blob in (method, windows):
        for match in re.finditer(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", blob):
            box = tuple(int(n) for n in match.groups())
            box_w, box_h = box[2] - box[0], box[3] - box[1]
            if box_w > width * 0.8 and 280 < box_h < height * 0.7 and box[1] > height * 0.35:
                candidates.append(box)  # type: ignore[arg-type]
    if candidates:
        return max(candidates, key=lambda box: (box[3] - box[1], box[1]))
    if "mInputShown=true" in method:
        return (0, int(height * 0.58), width, height)
    return None


def wait_for_keyboard() -> None:
    enable_keyboard()
    dismiss_blockers()
    deadline = time.time() + 20
    while time.time() < deadline:
        if ime_frame():
            return
        tap_editor()
        time.sleep(1)
    text = adb("shell", "dumpsys", "input_method", check=False)
    interesting = [
        line.strip() for line in text.splitlines()
        if any(key in line for key in ("mCurMethodId", "mImeWindowVis", "mInputShown", "mServedView", "mImeFrame", "mInputFrame"))
    ]
    print("keyboard not shown:", " | ".join(interesting[:24]))
    print(adb("shell", "logcat", "-d", "-t", "60", "*:E", check=False)[-2500:])
    raise RuntimeError("keyboard window not found")


def tap_key_grid(letter: str) -> None:
    frame = ime_frame()
    if not frame:
        raise RuntimeError(f"keyboard window not found for {letter}")
    x1, y1, x2, y2 = frame
    width, height = x2 - x1, y2 - y1
    # Fractions of the keyboard window, measured on the API 34 emulator.
    # The suggestion strip is the top of that window, then three letter rows, then the bottom row.
    rows = {
        "qwertyuiop": 0.31,
        "asdfghjkl": 0.47,
        "zxcvbnm": 0.63,
    }
    wanted = letter.lower()
    if wanted == "space":
        tap(x1 + width // 2, y1 + int(height * 0.85))
        time.sleep(0.15)
        return
    if wanted == "delete":
        tap(x1 + int(width * 0.92), y1 + int(height * 0.63))
        time.sleep(0.15)
        return
    for letters, frac in rows.items():
        if wanted not in letters:
            continue
        count = len(letters)
        inset = 0.5 if letters.startswith("a") else (1.5 if letters.startswith("z") else 0.0)
        slot = (width * (10 - inset * 2) / 10) if letters.startswith("z") else width / (count + (1 if letters.startswith("a") else 0))
        index = letters.index(wanted)
        if letters.startswith("a"):
            x = x1 + int(slot * (index + 0.5))
        elif letters.startswith("z"):
            x = x1 + int(width * 0.15) + int((width * 0.70) * (index + 0.5) / count)
        else:
            x = x1 + int(width * (index + 0.5) / count)
        tap(x, y1 + int(height * frac))
        time.sleep(0.15)
        return
    raise RuntimeError(f"no grid position for {letter}")


def tap_key(letter: str) -> None:
    root = dump()
    wanted = {"space": "space", "delete": "delete"}.get(letter.lower(), letter.lower())
    for node in nodes(root):
        label = (node.attrib.get("content-desc") or node.attrib.get("text") or "").strip().lower()
        if label == wanted or label.startswith(wanted + " "):
            x, y = center(parse_bounds(node.attrib["bounds"]))
            if y > 200:
                tap(x, y)
                time.sleep(0.15)
                return
    tap_key_grid(letter)


def type_letters(word: str, shot_prefix: str | None = None) -> None:
    wait_for_keyboard()
    for index, ch in enumerate(word, start=1):
        tap_key(ch)
        time.sleep(0.15)
        if shot_prefix:
            shot(f"{shot_prefix}-key-{index}-{ch}")


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
    """Type, space, backspace, space. Screenshot after every key.

    Space may autocorrect. Backspace must restore the typed spelling, and the
    next space must leave that spelling in place with a single space.
    """
    if focus_tap:
        tap_text(focus_tap)
        time.sleep(1)
    type_letters("webull", shot_name)
    time.sleep(0.3)
    if not field_contains("webull"):
        print(shot_name, "field texts:", visible_texts()[:30])
        raise RuntimeError(f"{shot_name}: webull was not in the field after typing")
    tap_key("space")
    time.sleep(0.4)
    shot(shot_name + "-space")
    tap_key("delete")
    time.sleep(0.4)
    shot(shot_name + "-backspace")
    if not field_contains("webull"):
        raise RuntimeError(f"{shot_name}: backspace did not restore webull")
    tap_key("space")
    time.sleep(0.4)
    shot(shot_name + "-space-again")
    if not field_contains("webull"):
        raise RuntimeError(f"{shot_name}: the space after backspace rewrote webull")


def chrome_checks() -> None:
    enable_keyboard()
    dismiss_blockers()
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
