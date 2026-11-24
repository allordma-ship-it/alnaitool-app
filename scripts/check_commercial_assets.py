#!/usr/bin/env python3
"""
EchoScope – gate for a future commercial release.

Checks the media licence registry (app/src/main/assets/media/media_registry.json) and, optionally,
a built APK / AAB:

  1. every entry whose status is not COMMERCIAL_ALLOWED must have a `replacement`,
  2. every replacement must itself be registered as COMMERCIAL_ALLOWED and exist in the assets,
  3. with --apk: no restricted original (NON_COMMERCIAL_ONLY / PERMISSION_REQUIRED /
     LICENSE_UNVERIFIED) may be packaged, and every media file in the package must be registered.

Exit code 0 = cleared for commercial distribution, 1 = blocking findings (listed).
Today's build is expected to FAIL this gate – it documents what still has to be replaced or licensed.

Usage:
  python3 scripts/check_commercial_assets.py
  python3 scripts/check_commercial_assets.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
REGISTRY = os.path.join(ASSETS, "media", "media_registry.json")
MEDIA_EXT = (".mp4", ".webm", ".jpg", ".jpeg", ".png", ".webp", ".svg", ".gif")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="built APK or AAB to inspect")
    args = ap.parse_args()

    entries = json.load(open(REGISTRY, encoding="utf-8"))["assets"]
    by_path = {e["path"]: e for e in entries}
    findings = []

    for e in entries:
        if e["commercialUse"] == "COMMERCIAL_ALLOWED":
            continue
        rep = e.get("replacement")
        if not rep:
            findings.append(f"{e['commercialUse']:20} {e['path']}  (no replacement)")
            continue
        target = by_path.get(rep)
        if target is None or target["commercialUse"] != "COMMERCIAL_ALLOWED":
            findings.append(f"REPLACEMENT_NOT_CLEARED {e['path']} -> {rep}")
        elif not e["path"].startswith("res/") and not os.path.exists(os.path.join(ASSETS, rep)):
            findings.append(f"REPLACEMENT_MISSING     {e['path']} -> {rep}")

    if args.apk:
        with zipfile.ZipFile(args.apk) as z:
            names = z.namelist()
        prefix = "base/assets/" if any(n.startswith("base/assets/") for n in names) else "assets/"
        packaged = [n[len(prefix):] for n in names if n.startswith(prefix) and n.lower().endswith(MEDIA_EXT)]
        for p in packaged:
            e = by_path.get(p)
            if e is None:
                findings.append(f"UNREGISTERED_IN_PACKAGE {p}")
            elif e["commercialUse"] != "COMMERCIAL_ALLOWED":
                findings.append(f"RESTRICTED_IN_PACKAGE   {p}  ({e['commercialUse']})")

    if findings:
        print(f"NOT cleared for commercial release – {len(findings)} finding(s):")
        for f in findings:
            print("  " + f)
        return 1
    print("Cleared: no restricted or unverified media in use" + (" or packaged." if args.apk else "."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
