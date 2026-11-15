#!/usr/bin/env python3
"""
EchoScope – download the transthoracic view images of Echocardiographer.org for review.

Licence (https://www.echocardiographer.org/about-3, "Reuse of Materials"):
  "Feel free to use any of the content here and on my youtube channel for free, but please
   provide credit to this site and me or other authors."  – Atif Qasim, MD MSCE

For every TTE page in PAGES the script lists the Wix images in page order, downloads the
original file (static.wixstatic.com/media/<id>, without Wix's resize transform) into
temp_real/echoorg/<page>/ and writes temp_real/echoorg/manifest.json with the file name Wix
shows, the alt text and the nearest heading so each image can be reviewed before it is used.
YouTube videos embedded on the pages are listed by id only (not downloaded).

Usage: python3 scripts/fetch_echocardiographer.py
"""

from __future__ import annotations

import html
import json
import os
import re
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "temp_real", "echoorg")
SITE = "https://www.echocardiographer.org/"
UA = "Mozilla/5.0 (compatible; EchoScopeAssetFetcher/1.0; offline teaching app build script)"

PAGES = [
    "tte-plax", "tte-psax", "tte-apical4", "tte-apical5", "tte-apical2", "tte-apical-long-axis",
    "tte-subcostal", "tte-subcostal-greatvessels", "tte-suprasternal",
]


def get(url: str) -> bytes:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return r.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(3 * (attempt + 1))
    return b""


def images(page_html: str):
    """(media id + extension, display name, alt, preceding heading) in page order."""
    seen, out = set(), []
    for m in re.finditer(r'<img[^>]+>', page_html):
        tag = m.group(0)
        src = re.search(r'static\.wixstatic\.com/media/([0-9a-f]{6}_[0-9a-f]{32}~mv2\.(?:jpe?g|png|gif|webp))'
                        r'(?:/v1/[^/"]+/[^/"]+/([^"\s,]+))?', tag)
        if not src or src.group(1) in seen:
            continue
        seen.add(src.group(1))
        alt = html.unescape(re.search(r'alt="([^"]*)"', tag).group(1)) if 'alt="' in tag else ""
        name = urllib.parse.unquote(src.group(2) or "")
        before = re.sub(r"<[^>]+>", " ", page_html[max(0, m.start() - 6000):m.start()])
        before = re.sub(r"\s+", " ", html.unescape(before)).strip()
        out.append({"media": src.group(1), "name": name, "alt": alt, "context": before[-220:]})
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    manifest = {}
    for page in PAGES:
        text = get(SITE + page).decode("utf-8", "replace")
        body = re.sub(r"<script.*?</script>|<style.*?</style>", "", text, flags=re.S)
        items = images(body)
        videos = sorted(set(re.findall(r'(?:youtube\.com/(?:embed/|watch\?v=)|youtu\.be/)([A-Za-z0-9_-]{11})', text)))
        folder = os.path.join(OUT, page)
        os.makedirs(folder, exist_ok=True)
        for i, item in enumerate(items):
            ext = item["media"].rsplit(".", 1)[1]
            file = f"{i:02d}.{ext}"
            path = os.path.join(folder, file)
            if not os.path.exists(path):
                with open(path, "wb") as f:
                    f.write(get("https://static.wixstatic.com/media/" + item["media"]))
                time.sleep(0.5)
            item["file"] = os.path.relpath(path, OUT)
        manifest[page] = {"url": SITE + page, "images": items, "youtube": videos}
        print(f"{page}: {len(items)} images, {len(videos)} YouTube ids")
        time.sleep(1)
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
