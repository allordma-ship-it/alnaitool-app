#!/usr/bin/env python3
"""
EchoScope – catalogue of echocardiography media on Wikimedia Commons (API, no scraping).

Walks the echo categories (one level of sub-categories) and stores, for every file, its URL,
size, MIME type, duration, licence, author and description in temp_real/commons/catalog.json.
Files are then chosen by hand (scripts/fetch_commons.py) – only free licences (CC BY, CC BY-SA,
CC0, public domain) are used and credited in assets/commons/ATTRIBUTION.txt.
"""

import json
import os
import re
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "temp_real", "commons", "catalog.json")
API = "https://commons.wikimedia.org/w/api.php"
UA = "EchoScopeBuild/1.0 (offline medical education app; build script)"
CATEGORIES = ["Category:Echocardiography", "Category:Echocardiograms", "Category:Doppler echocardiography",
              "Category:Media from CardioNetworks ECHOpedia", "Category:Media from Cardiovascular Ultrasound",
              "Category:Respiratory maneuvers in echocardiography", "Category:Videos of echocardiography",
              "Category:Echocardiography videos"]


def api(**params):
    params.update(format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    time.sleep(1.5)   # be gentle with the API (429 otherwise)
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 – retry on 429 / network hiccups
            time.sleep(15 * (attempt + 1))
            err = e
    raise err


def members(cat, ns):
    out, cont = [], {}
    while True:
        d = api(action="query", list="categorymembers", cmtitle=cat, cmnamespace=ns, cmlimit=500, **cont)
        out += [m["title"] for m in d.get("query", {}).get("categorymembers", [])]
        if "continue" not in d:
            return out
        cont = {"cmcontinue": d["continue"]["cmcontinue"]}


def strip(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()


def main():
    cats, files = list(CATEGORIES), set()
    for c in CATEGORIES:
        try:
            cats += members(c, 14)
        except Exception:  # noqa: BLE001
            pass
    for c in dict.fromkeys(cats):
        try:
            files.update(members(c, 6))
        except Exception:  # noqa: BLE001
            pass
    files = sorted(files)
    catalog = {}
    for i in range(0, len(files), 20):
        d = api(action="query", titles="|".join(files[i:i + 20]), prop="imageinfo",
                iiprop="url|size|mime|extmetadata|mediatype", iiurlwidth=1280)
        for p in d["query"]["pages"]:
            if "imageinfo" not in p:
                continue
            ii = p["imageinfo"][0]
            md = ii.get("extmetadata", {})
            catalog[p["title"]] = {
                "url": ii["url"], "thumb": ii.get("thumburl"), "page": ii.get("descriptionurl"),
                "width": ii.get("width"), "height": ii.get("height"), "size": ii.get("size"),
                "mime": ii.get("mime"), "duration": ii.get("duration"),
                "license": md.get("LicenseShortName", {}).get("value"),
                "licenseUrl": md.get("LicenseUrl", {}).get("value"),
                "artist": strip(md.get("Artist", {}).get("value")),
                "description": strip(md.get("ImageDescription", {}).get("value"))[:400],
            }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)
    print(len(catalog), "files from", len(set(cats)), "categories")


if __name__ == "__main__":
    main()
