#!/usr/bin/env python3
"""
EchoScope – fetch openly licensed, real echocardiography clips from Wikimedia Commons.

Searches the Commons MediaWiki API (generator=search in the File: namespace and
list=allimages by title prefix), keeps video files (.webm/.ogv/.mp4) and animated
GIFs whose title/description is about echocardiography, downloads them into
temp_real/, verifies them with ffprobe (width >= 320 px, duration >= 1.5 s) and
writes temp_real/manifest.json with licence and attribution for every file.

Wikimedia Commons content is freely licensed, but most files require attribution
(CC BY / CC BY-SA). The manifest keeps author, licence and source URL so the app
can credit them.

Usage: python3 scripts/download_real_echo.py [--max-mb 80]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(ROOT, "temp_real")
API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "EchoScopeAssetFetcher/1.0 (offline echocardiography teaching app build script)"

SEARCH_TERMS = [
    "echocardiography",
    "echocardiogram",
    "parasternal",
    "apical four chamber",
    "subcostal",
    "aortic valve ultrasound",
    "heart ultrasound",
    "transthoracic echocardiography",
    "suprasternal",
    "inferior vena cava ultrasound",
]
PREFIXES = ["Echocardiogra", "Echokardiogra", "Transthoracic", "Parasternal", "Apical", "Subcostal", "TTE"]

VIDEO_EXT = (".webm", ".ogv", ".ogg", ".mp4", ".gif")
# Title/description must mention the heart *and* ultrasound/echo to count as an echo clip.
ECHO_WORDS = re.compile(
    r"echocardi|echokardi|\btte\b|parasternal|apical (four|4|five|5|two|2|three|3)|four[- ]chamber|"
    r"4[- ]chamber|subcostal|suprasternal|plax|psax|transthoracic|cardiac ultrasound|heart ultrasound|"
    r"ultrasound of the heart|point[- ]of[- ]care.*heart",
    re.IGNORECASE,
)
EXCLUDE_WORDS = re.compile(r"fetal|fetus|foetal|obstetric|pregnan|transesophageal|\btee\b|\btoe\b|"
                           r"intracardiac|angiogra|\bmri\b|\bct\b|animation|3d render|cartoon",
                           re.IGNORECASE)

MIN_WIDTH = 320
MIN_DURATION = 1.5


REQUEST_GAP_S = 2.0      # Wikimedia rate-limits anonymous clients; keep requests well spaced
_last_request = 0.0


def fetch(url: str, timeout: int = 60, attempts: int = 8):
    """GET with pacing and exponential back-off on HTTP 429 / transient errors."""
    global _last_request
    for attempt in range(attempts):
        wait = REQUEST_GAP_S - (time.monotonic() - _last_request)
        if wait > 0:
            time.sleep(wait)
        _last_request = time.monotonic()
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as exc:
            if exc.code not in (429, 500, 502, 503, 504) or attempt == attempts - 1:
                raise
            retry_after = float(exc.headers.get("Retry-After") or 0)
            delay = min(120.0, max(retry_after, 5.0 * 2 ** attempt))
            print(f"   HTTP {exc.code}, retry in {delay:.0f}s", file=sys.stderr)
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == attempts - 1:
                raise
            delay = min(120.0, 5.0 * 2 ** attempt)
            exc_text = str(exc)
            print(f"   network error ({exc_text}), retry in {delay:.0f}s", file=sys.stderr)
            time.sleep(delay)
            continue
        time.sleep(delay)
    raise RuntimeError("unreachable")


def api(params: dict) -> dict:
    params = {**params, "format": "json", "formatversion": "2"}
    with fetch(API + "?" + urllib.parse.urlencode(params)) as resp:
        return json.load(resp)


IIPROP = "url|size|mime|mediatype|extmetadata"


def strip_html(text: str) -> str:
    return re.sub(r"<[^>]+>", "", text or "").strip()


def collect_pages(params: dict, limit_pages: int = 6) -> list[dict]:
    pages, cont = [], {}
    for _ in range(limit_pages):
        data = api({**params, **cont})
        pages.extend(data.get("query", {}).get("pages", []))
        if "continue" not in data:
            break
        cont = data["continue"]
    return pages


def search_candidates() -> dict[str, dict]:
    found: dict[str, dict] = {}

    for term in SEARCH_TERMS:
        print(f"search: {term!r}")
        pages = collect_pages({
            "action": "query",
            "generator": "search",
            "gsrsearch": f"{term} filetype:video|bitmap",
            "gsrnamespace": "6",
            "gsrlimit": "50",
            "prop": "imageinfo",
            "iiprop": IIPROP,
        })
        for p in pages:
            found.setdefault(p["title"], p)

    for prefix in PREFIXES:
        print(f"allimages prefix: {prefix!r}")
        pages = collect_pages({
            "action": "query",
            "generator": "allimages",
            "gaiprefix": prefix,
            "gailimit": "100",
            "prop": "imageinfo",
            "iiprop": IIPROP,
        })
        for p in pages:
            found.setdefault(p["title"], p)
    return found


def is_echo_video(page: dict) -> tuple[bool, str]:
    title = page["title"]
    if not title.lower().endswith(VIDEO_EXT):
        return False, "not a video/gif"
    info = (page.get("imageinfo") or [{}])[0]
    meta = info.get("extmetadata", {})
    text = " ".join([
        title,
        strip_html(meta.get("ImageDescription", {}).get("value", "")),
        strip_html(meta.get("Categories", {}).get("value", "")),
    ])
    if not ECHO_WORDS.search(text):
        return False, "not echocardiography"
    if EXCLUDE_WORDS.search(text):
        return False, "excluded modality/subject"
    duration = info.get("duration")
    if duration is not None and float(duration) <= 1.0:
        return False, f"too short ({duration}s)"
    if info.get("mime", "").startswith("image/") and not title.lower().endswith(".gif"):
        return False, "still image"
    return True, "ok"


def safe_name(title: str) -> str:
    name = title.split(":", 1)[1]
    return re.sub(r"[^A-Za-z0-9._-]+", "_", name)[:120]


def download(url: str, dest: str) -> None:
    with fetch(url, timeout=300) as resp, open(dest + ".part", "wb") as f:
        while chunk := resp.read(1 << 16):
            f.write(chunk)
    os.replace(dest + ".part", dest)


def ffprobe(path: str) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
         "-show_entries", "stream=codec_name,width,height,nb_read_packets,r_frame_rate:format=duration",
         "-of", "json", path],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        return {}
    data = json.loads(out.stdout or "{}")
    stream = (data.get("streams") or [{}])[0]
    duration = float(data.get("format", {}).get("duration") or 0)
    if not duration and stream.get("nb_read_packets") and stream.get("r_frame_rate"):
        num, den = (int(x) for x in stream["r_frame_rate"].split("/"))
        if num:
            duration = int(stream["nb_read_packets"]) * den / num
    return {
        "codec": stream.get("codec_name"),
        "width": stream.get("width") or 0,
        "height": stream.get("height") or 0,
        "frames": int(stream.get("nb_read_packets") or 0),
        "duration": round(duration, 2),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-mb", type=float, default=80.0, help="skip files larger than this")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    candidates = search_candidates()
    print(f"\n{len(candidates)} File: pages found, filtering for echocardiography videos…")

    manifest = []
    for title, page in sorted(candidates.items()):
        ok, reason = is_echo_video(page)
        if not ok:
            continue
        info = page["imageinfo"][0]
        size_mb = info.get("size", 0) / 1e6
        if size_mb > args.max_mb:
            print(f"skip (size {size_mb:.0f} MB): {title}")
            continue
        meta = info.get("extmetadata", {})
        local = os.path.join(OUT_DIR, safe_name(title))
        if not os.path.exists(local):
            print(f"download {size_mb:5.1f} MB  {title}")
            try:
                download(info["url"], local)
            except Exception as exc:
                print(f"   failed: {exc}")
                continue
        probe = ffprobe(local)
        verified = probe.get("width", 0) >= MIN_WIDTH and probe.get("duration", 0) >= MIN_DURATION
        manifest.append({
            "file": os.path.relpath(local, ROOT),
            "title": title,
            "source": info.get("descriptionurl"),
            "url": info.get("url"),
            "mime": info.get("mime"),
            "author": strip_html(meta.get("Artist", {}).get("value", "")),
            "credit": strip_html(meta.get("Credit", {}).get("value", "")),
            "license": meta.get("LicenseShortName", {}).get("value", ""),
            "license_url": meta.get("LicenseUrl", {}).get("value", ""),
            "description": strip_html(meta.get("ImageDescription", {}).get("value", ""))[:400],
            "probe": probe,
            "verified": verified,
        })

    with open(os.path.join(OUT_DIR, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(f"\n   {'file':60} {'WxH':>10} {'dur':>6}  licence")
    for m in manifest:
        p = m["probe"]
        flag = "OK " if m["verified"] else "-- "
        print(f"{flag}{os.path.basename(m['file'])[:60]:60} {p.get('width', 0):>4}x{p.get('height', 0):<5} "
              f"{p.get('duration', 0):>5}s  {m['license']}")
    verified = sum(m["verified"] for m in manifest)
    print(f"\n{len(manifest)} downloaded, {verified} pass ffprobe checks (>= {MIN_WIDTH}px, >= {MIN_DURATION}s)")


if __name__ == "__main__":
    main()
