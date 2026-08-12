#!/usr/bin/env python3
"""Remove Termux:X11's stock preferences activity from the embedded AAR.

Every code path in the embedded viewer opens its settings with an explicit
``new Intent(context, LoriePreferences.class)``, so the only way to replace
that screen with the app's One UI settings is to own the class itself. The
app ships its own ``com.termux.x11.LoriePreferences`` activity; this script
removes the AAR's implementation so the two never collide at dex-merge time.

Only the activity classes are removed. ``LoriePreferences$PrefsProto`` and
its nested preference types stay: ``Prefs`` (used throughout the viewer for
every setting read) extends them.
"""

from __future__ import annotations

import hashlib
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
AAR_PATH = PROJECT_ROOT / "app/libs/termux-x11-lorie-arm64.aar"

CLASS_PREFIX = "com/termux/x11/LoriePreferences"
KEEP_MARKER = "$PrefsProto"


def log(message: str) -> None:
    print(f"[strip-x11-prefs] {message}", flush=True)


def is_activity_class(entry_name: str) -> bool:
    """The stock activity and its UI inner classes — never PrefsProto."""
    if not entry_name.startswith(CLASS_PREFIX) or not entry_name.endswith(".class"):
        return False
    return KEEP_MARKER not in entry_name


def rewrite_jar_without_activity(jar_bytes: bytes) -> tuple[bytes, list[str], list[str]]:
    removed: list[str] = []
    kept_prefs_classes: list[str] = []

    with tempfile.TemporaryDirectory() as scratch:
        source_path = Path(scratch) / "classes-in.jar"
        target_path = Path(scratch) / "classes-out.jar"
        source_path.write_bytes(jar_bytes)

        with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(
            target_path, "w", zipfile.ZIP_DEFLATED
        ) as target:
            for info in source.infolist():
                if is_activity_class(info.filename):
                    removed.append(info.filename)
                    continue
                if info.filename.startswith(CLASS_PREFIX):
                    kept_prefs_classes.append(info.filename)
                target.writestr(info, source.read(info.filename))

        return target_path.read_bytes(), removed, kept_prefs_classes


def main() -> int:
    if not AAR_PATH.is_file():
        log(f"AAR not found: {AAR_PATH}")
        return 1

    with tempfile.TemporaryDirectory() as scratch:
        rebuilt_path = Path(scratch) / "rebuilt.aar"

        with zipfile.ZipFile(AAR_PATH) as source:
            jar_bytes = source.read("classes.jar")
            new_jar, removed, kept = rewrite_jar_without_activity(jar_bytes)

            if not removed:
                log("nothing to remove — the AAR is already stripped")
                return 0
            if not any(KEEP_MARKER in name for name in kept):
                log("refusing to write: PrefsProto classes would be lost")
                return 1

            with zipfile.ZipFile(rebuilt_path, "w", zipfile.ZIP_DEFLATED) as target:
                for info in source.infolist():
                    payload = new_jar if info.filename == "classes.jar" else source.read(info.filename)
                    target.writestr(info, payload)

        shutil.copyfile(rebuilt_path, AAR_PATH)

    for name in removed:
        log(f"removed {name}")
    log(f"kept {sum(1 for name in kept if KEEP_MARKER in name)} PrefsProto classes")
    digest = hashlib.sha256(AAR_PATH.read_bytes()).hexdigest()
    log(f"new SHA-256: {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
