#!/usr/bin/env python3
"""
EchoScope – download hand-picked Wikimedia Commons files (originals from upload.wikimedia.org).

Wikimedia refuses uncached originals from shared build servers (429, "use thumbnail images in
sizes listed on https://w.wiki/GHai"), so files are fetched as derivatives, one by one with a long
pause: videos as the server's own transcodes (480p / 360p / 240p WebM), images as standard-size
thumbnails (1280 / 960 / 500 px) or the original when it is already cached. Licence wikitext is
read from the file page (action=raw). Output: temp_real/commons/files/<name> and temp_real/commons/meta.json.

Usage: python3 scripts/fetch_commons.py "File name 1.webm" "File name 2.jpg" ...
       python3 scripts/fetch_commons.py --list names.txt
"""

import hashlib
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DIR = os.path.join(ROOT, "temp_real", "commons", "files")
META = os.path.join(ROOT, "temp_real", "commons", "meta.json")
UA = "EchoScopeBuild/1.0 (offline medical education app; build script)"


def candidates(name: str) -> list[str]:
    u = name.replace(" ", "_")
    h = hashlib.md5(u.encode()).hexdigest()
    q = urllib.parse.quote(u)
    base = f"https://upload.wikimedia.org/wikipedia/commons"
    if name.lower().endswith((".webm", ".ogv")):
        return [f"{base}/transcoded/{h[0]}/{h[:2]}/{q}/{q}.{s}" for s in
                ("480p.vp9.webm", "480p.webm", "360p.vp9.webm", "360p.webm", "240p.vp9.webm", "240p.webm")]
    return [f"{base}/{h[0]}/{h[:2]}/{q}"] + [f"{base}/thumb/{h[0]}/{h[:2]}/{q}/{w}px-{q}" for w in (1280, 960, 500)]


def fetch(name: str) -> bytes:
    for url in candidates(name):
        try:
            data = get(url)
            time.sleep(10)
            return data
        except urllib.error.HTTPError as e:
            if e.code not in (404, 400):
                raise
            time.sleep(5)
    raise RuntimeError("no derivative for " + name)


def get(url: str) -> bytes:
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(int(e.headers.get("Retry-After", "120")) + 30)
    raise RuntimeError("rate limited: " + url)


def licence(wikitext: str) -> tuple[str, str]:
    t = wikitext.lower()
    author = re.search(r"\|\s*author\s*=\s*(.+)", wikitext, re.I)
    author = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]+)\]\]", r"\1", author.group(1)).strip() if author else ""
    for key, name in [("cc-by-sa-4.0", "CC BY-SA 4.0"), ("cc-by-sa-3.0", "CC BY-SA 3.0"), ("cc-by-sa-2.5", "CC BY-SA 2.5"),
                      ("cc-by-4.0", "CC BY 4.0"), ("cc-by-3.0", "CC BY 3.0"), ("cc-by-2.5", "CC BY 2.5"), ("cc-by-2.0", "CC BY 2.0"),
                      ("cc-zero", "CC0"), ("cc0", "CC0"), ("pd-self", "Public domain"), ("pd-", "Public domain"), ("gfdl", "GFDL")]:
        if key in t:
            return name, author
    return "", author


def main(names):
    os.makedirs(DIR, exist_ok=True)
    meta = json.load(open(META, encoding="utf-8")) if os.path.exists(META) else {}
    for name in names:
        name = name.removeprefix("File:").strip()
        path = os.path.join(DIR, name)
        if not os.path.exists(path):
            data = fetch(name)
            with open(path, "wb") as f:
                f.write(data)
        if name not in meta:
            raw = get("https://commons.wikimedia.org/w/index.php?" +
                      urllib.parse.urlencode({"title": "File:" + name, "action": "raw"})).decode("utf-8", "replace")
            lic, author = licence(raw)
            meta[name] = {"license": lic, "author": author or "CardioNetworks ECHOpedia",
                          "page": "https://commons.wikimedia.org/wiki/File:" + urllib.parse.quote(name.replace(" ", "_")),
                          "wikitext": raw[:1500]}
            time.sleep(3)
            with open(META, "w", encoding="utf-8") as f:
                json.dump(meta, f, ensure_ascii=False, indent=1)
        print(f"{meta[name]['license'] or '??':14} {os.path.getsize(path):>9}  {name}", flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[:1] == ["--list"]:
        args = [line.strip() for line in open(args[1], encoding="utf-8") if line.strip() and not line.startswith("#")]
    main(args)
