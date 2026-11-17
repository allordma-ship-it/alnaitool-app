#!/usr/bin/env python3
"""
EchoScope – search open-science repositories for openly licensed, real TTE cine loops.

Sources: Zenodo REST API and Figshare API v2 (records/articles with attached video files).
Only files with a redistributable licence (CC0, CC BY, CC BY-SA, public domain) are listed;
NC/ND or unknown licences are reported but marked as not usable. Downloads go to
temp_real/hunt/<source>_<id>/ for manual frame-by-frame review before conversion.

Usage:
  python3 scripts/global_echo_hunter.py search            # list candidates
  python3 scripts/global_echo_hunter.py fetch <url> <name>  # download one file
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
HUNT = os.path.join(ROOT, "temp_real", "hunt")
UA = "EchoScopeAssetFetcher/1.0 (offline echocardiography teaching app build script)"
VIDEO = re.compile(r"\.(mp4|avi|webm|ogv|mov|gif|wmv|mpg|mpeg|zip)$", re.I)
OPEN = re.compile(r"cc[-_ ]?0|cc[-_ ]?by(?![-_ ]?n)|public domain|pddl|cc-by-sa|cc by-sa|creative commons attribution(?! non)", re.I)

QUERIES = [
    "echocardiography video", "echocardiogram video", "transthoracic echocardiography",
    "parasternal short axis aortic valve", "apical five chamber", "apical 5-chamber",
    "parasternal long axis", "subcostal echocardiography", "inferior vena cava ultrasound",
    "aortic valve short axis echocardiography", "echocardiography supplementary video",
]


def get(url: str, data: bytes | None = None, headers: dict | None = None):
    h = {"User-Agent": UA, **(headers or {})}
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=90) as r:
                return json.load(r)
        except Exception as exc:  # rate limits / transient errors
            if attempt == 4:
                print(f"   failed {url[:80]}: {exc}", file=sys.stderr)
                return None
            time.sleep(4 * (attempt + 1))


def zenodo(query: str):
    url = "https://zenodo.org/api/records?" + urllib.parse.urlencode({"q": query, "size": 50, "sort": "bestmatch"})
    d = get(url) or {}
    for hit in d.get("hits", {}).get("hits", []):
        lic = (hit.get("metadata", {}).get("license") or {}).get("id", "")
        title = hit.get("metadata", {}).get("title", "")
        for f in hit.get("files", []):
            key = f.get("key", "")
            if VIDEO.search(key):
                yield {
                    "source": "zenodo", "id": hit["id"], "title": title, "file": key,
                    "size": f.get("size", 0), "license": lic,
                    "url": f.get("links", {}).get("self", ""), "page": hit.get("links", {}).get("html", ""),
                    "creators": "; ".join(c.get("name", "") for c in hit.get("metadata", {}).get("creators", [])),
                }


def figshare(query: str):
    body = json.dumps({"search_for": query, "page_size": 50}).encode()
    arts = get("https://api.figshare.com/v2/articles/search", data=body, headers={"Content-Type": "application/json"}) or []
    for art in arts:
        if not re.search(r"echo|ultrasound|cardiac|heart|sonograph", art.get("title", ""), re.I):
            continue
        detail = get(f"https://api.figshare.com/v2/articles/{art['id']}") or {}
        lic = (detail.get("license") or {}).get("name", "")
        for f in detail.get("files", []):
            if VIDEO.search(f.get("name", "")):
                yield {
                    "source": "figshare", "id": art["id"], "title": art.get("title", ""), "file": f["name"],
                    "size": f.get("size", 0), "license": lic, "url": f.get("download_url", ""),
                    "page": detail.get("url_public_html", ""),
                    "creators": "; ".join(a.get("full_name", "") for a in detail.get("authors", [])),
                }
        time.sleep(0.5)


def search():
    seen, rows = set(), []
    for q in QUERIES:
        for src in (zenodo, figshare):
            for row in src(q):
                key = (row["source"], row["id"], row["file"])
                if key in seen:
                    continue
                seen.add(key)
                row["open"] = bool(OPEN.search(row["license"]))
                rows.append(row)
            time.sleep(1)
    os.makedirs(HUNT, exist_ok=True)
    with open(os.path.join(HUNT, "candidates.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    for r in rows:
        flag = "OPEN" if r["open"] else "----"
        print(f"{flag} {r['source']:8} {r['size'] / 1e6:7.1f}MB {r['license'][:18]:18} {r['file'][:40]:40} | {r['title'][:90]}")
    print(f"\n{len(rows)} video files, {sum(r['open'] for r in rows)} with an open licence")


def fetch(url: str, name: str):
    dest_dir = os.path.join(HUNT, name)
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, os.path.basename(urllib.parse.urlparse(url).path) or "file")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=600) as r, open(dest, "wb") as f:
        while chunk := r.read(1 << 16):
            f.write(chunk)
    print(dest, os.path.getsize(dest))


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "fetch":
        fetch(sys.argv[2], sys.argv[3])
    else:
        search()
