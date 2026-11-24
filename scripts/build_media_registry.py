#!/usr/bin/env python3
"""
EchoScope – media licence registry (assets/media/media_registry.json) and ASSET_LICENSES.md.

Every bundled video and image gets one entry: id, current path, original file name, type, source,
author, licence, commercial-use status, attribution / ShareAlike duties, replacement status and an
optional `replacement` path. The app resolves every media path through this registry
(MediaRegistry.resolve), so a restricted file can later be swapped by editing `replacement` here –
no UI or playback code changes. Files are NOT moved: their current paths stay valid.

Licence information comes only from what is already documented in the project:
  * echo_protocol_full.json (videoSource of each view, colourLoops, diagramCredit),
  * assets/loops/ATTRIBUTION.txt, assets/commons/ATTRIBUTION.txt, assets/echoorg/ATTRIBUTION.txt,
    assets/schema/ATTRIBUTION.txt,
  * the generating scripts for project-original artwork,
  * scripts/media_overrides.json – replacements and commercially licensed substitutes (hand-maintained).
Nothing is ever marked COMMERCIAL_ALLOWED unless its documented licence permits commercial use.

Statuses
  COMMERCIAL_ALLOWED   licence permits commercial use (attribution / ShareAlike duties may apply)
  NON_COMMERCIAL_ONLY  licence forbids commercial use (CC BY-NC)
  PERMISSION_REQUIRED  reuse allowed with credit, but commercial use not expressly granted
  LICENSE_UNVERIFIED   source or licence not confirmed

Usage: python3 scripts/build_media_registry.py
"""

from __future__ import annotations

import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
OUT = os.path.join(ASSETS, "media", "media_registry.json")
DOC = os.path.join(ROOT, "ASSET_LICENSES.md")
OVERRIDES = os.path.join(ROOT, "scripts", "media_overrides.json")
MEDIA_EXT = (".mp4", ".webm", ".jpg", ".jpeg", ".png", ".webp", ".svg", ".gif")

ECHOORG_TERMS = ('Echocardiographer.org "Reuse of Materials": "Feel free to use any of the content here and on my '
                 'youtube channel for free, but please provide credit to this site and me or other authors." '
                 "Commercial use is not expressly granted.")

LICENSE_URLS = {
    "CC BY-SA 3.0": "https://creativecommons.org/licenses/by-sa/3.0/",
    "CC BY-SA 4.0": "https://creativecommons.org/licenses/by-sa/4.0/",
    "CC BY-NC 4.0": "https://creativecommons.org/licenses/by-nc/4.0/",
    "CC BY 2.5": "https://creativecommons.org/licenses/by/2.5/",
    "Public domain": None,
}


def status_for(license_name: str) -> str:
    l = (license_name or "").upper()
    if not l or "UNKNOWN" in l or "UNVERIFIED" in l:
        return "LICENSE_UNVERIFIED"
    if "-NC" in l or " NC" in l:
        return "NON_COMMERCIAL_ONLY"
    if l.startswith("CC BY") or "PUBLIC DOMAIN" in l or l == "CC0" or "PROJECT ORIGINAL" in l:
        return "COMMERCIAL_ALLOWED"
    return "LICENSE_UNVERIFIED"


def entry(path, kind, source, author, license_name, *, url=None, original=None, status=None,
          attribution=None, modifications=None, note=None):
    st = status or status_for(license_name)
    share_alike = "BY-SA" in (license_name or "").upper()
    return {
        "id": re.sub(r"[^a-z0-9]+", "_", os.path.splitext(path)[0].lower()).strip("_"),
        "path": path,
        "originalFilename": original or os.path.basename(path),
        "type": kind,
        "source": source,
        "sourceUrl": url,
        "author": author,
        "license": license_name,
        "licenseUrl": LICENSE_URLS.get(license_name),
        "commercialUse": st,
        "attributionRequired": license_name not in ("Public domain", "EchoScope project original"),
        "shareAlike": share_alike,
        "attribution": attribution or (f"{author} · {license_name}" if author else license_name),
        "modifications": modifications,
        "replacementStatus": "KEEP" if st == "COMMERCIAL_ALLOWED" else
                             "REPLACE_OR_LICENSE_BEFORE_COMMERCIAL_RELEASE",
        "replacement": None,
        "note": note,
    }


def parse_blocks(path, keys):
    """'asset\\n  Key: value' blocks of an ATTRIBUTION.txt → {asset: {key: value}}."""
    out, cur = {}, None
    for line in open(path, encoding="utf-8"):
        s = line.rstrip("\n")
        if s and not s.startswith(" ") and re.match(r"^[a-z_]+/.+\.(mp4|jpg|png|webp)$", s.strip()):
            cur = s.strip()
            out[cur] = {}
        elif cur and ":" in s and s.startswith("  "):
            k, v = s.strip().split(":", 1)
            if k.strip() in keys:
                out[cur][k.strip()] = v.strip()
    return out


def main():
    db = json.load(open(DB, encoding="utf-8"))
    views = [v for w in db["windows"] for v in w["views"]]
    reg: dict[str, dict] = {}

    def add(e):
        reg.setdefault(e["path"], e)

    # 1. view cine loops (videoSource of each view)
    for v in views:
        s = v["videoSource"]
        add(entry(v["videoAsset"], "video", s.get("platform") or ("The POCUS Atlas" if "pocusatlas" in s.get("sourceUrl", "")
                  else "Wikimedia Commons"), s.get("author"), s.get("license"), url=s.get("sourceUrl"),
                  original=s.get("title"), modifications="scaled to 720 px, re-encoded H.264 MP4, audio removed"))
    # 2. colour Doppler loops
    for c in db.get("colorLoops", {}).values():
        add(entry(c["asset"], "video", "The POCUS Atlas", c["author"], c["license"], url=c["sourceUrl"],
                  original=c["title"], modifications="re-encoded H.264 MP4, audio removed"))
    # 3. Wikimedia Commons pathology loops and stills
    commons = parse_blocks(os.path.join(ASSETS, "commons", "ATTRIBUTION.txt"), {"File", "Author", "Licence", "Source"})
    for path, m in commons.items():
        kind = "video" if path.endswith(".mp4") else "image"
        add(entry(path, kind, "Wikimedia Commons", m.get("Author"), m.get("Licence"), url=m.get("Source"),
                  original=m.get("File"),
                  modifications="WebM converted to H.264 MP4 (max. 720p, no audio)" if kind == "video" else "scaled to max. 1280 px"))
    # 4. Echocardiographer.org images
    for line in open(os.path.join(ASSETS, "echoorg", "ATTRIBUTION.txt"), encoding="utf-8"):
        m = re.match(r"^(echoorg/\S+)\s+<-\s+(\S+)\s+\((.*)\)\s*$", line.strip())
        if m:
            add(entry(m.group(1), "image", "Echocardiographer.org", "Atif Qasim, MD MSCE",
                      "Echocardiographer.org reuse-with-credit terms", url=m.group(2), original=m.group(3),
                      status="PERMISSION_REQUIRED", note=ECHOORG_TERMS, modifications="resized (max. 1000 px), re-encoded JPEG"))
    # 5. Schema drawings (derived by scripts/schema_raster.py)
    for f in sorted(os.listdir(os.path.join(ASSETS, "schema"))):
        if not f.endswith(".webp"):
            continue
        view = re.sub(r"(_es)?_(dark|light)\.webp$", "", f)
        path = f"schema/{f}"
        if view == "subcostal_ivc":
            add(entry(path, "schema", "EchoScope", "EchoScope project", "EchoScope project original",
                      original="scripts/reference/subcostal_ivc_drawing.svg",
                      modifications="drawn for EchoScope; transducer drawn by scripts/schema_raster.py"))
        else:
            add(entry(path, "schema", "Echocardiographer.org (derived)", "Atif Qasim, MD MSCE",
                      "Echocardiographer.org reuse-with-credit terms", status="PERMISSION_REQUIRED", note=ECHOORG_TERMS,
                      original="view drawing (assets/schema/ATTRIBUTION.txt)",
                      modifications="dark colour mapping, transducer redrawn" + (", LV/RV warped to end-systole" if "_es_" in f else "")
                      + (", short-axis level cut out and set into a sector" if view.startswith("psax") else "")))
    # 6. Atlas illustrations (diagramCredit of the views)
    for v in views:
        a = v.get("diagramAsset")
        if not a or not a.startswith("diagrams/"):
            continue
        credit = (v.get("diagramCredit") or {}).get("en", "")
        if "Lynch" in credit:
            add(entry(a, "diagram", "Patrick J. Lynch & C. Carl Jaffe, MD (Yale)", "Patrick J. Lynch, C. Carl Jaffe",
                      "CC BY 2.5", url="https://creativecommons.org/licenses/by/2.5/", modifications="light background card, labels"))
        else:
            add(entry(a, "diagram", "unknown (supplied for the app)", None, "UNVERIFIED – source and licence to be confirmed",
                      status="LICENSE_UNVERIFIED", note=credit))
    # 7. project-original vector artwork
    for d in ("orientation", "svg"):
        for f in sorted(os.listdir(os.path.join(ASSETS, d))):
            if f.endswith(".svg"):
                add(entry(f"{d}/{f}", "illustration", "EchoScope", "EchoScope project", "EchoScope project original",
                          original="generated by scripts/gen_orientation_svg.py" if d == "orientation" else "generated schematic (legacy fallback)"))
    # 8. launcher icon (resources, not assets)
    for density in sorted(os.listdir(RES)):
        if density.startswith("mipmap-") and density != "mipmap-anydpi":
            for f in sorted(os.listdir(os.path.join(RES, density))):
                add(entry(f"res/{density}/{f}", "icon", "artwork supplied by the project owner", None,
                          "UNVERIFIED – rights of the supplied (AI-generated) artwork to be confirmed",
                          status="LICENSE_UNVERIFIED", original="brand_assets/echoscope_icon_master.png",
                          note="The master image carried an AI image generator's sparkle watermark (removed in "
                               "brand_assets/build_icon.py). Confirm the generator's terms allow commercial use and trademark registration."))

    # hand-maintained overrides: commercially licensed substitutes and the replacement mapping
    overrides = json.load(open(OVERRIDES, encoding="utf-8")) if os.path.exists(OVERRIDES) else {}
    for extra in overrides.get("assets", []):
        base = entry(extra["path"], extra.get("type", "video"), extra.get("source", "unknown"), extra.get("author"),
                     extra.get("license", "UNVERIFIED"), url=extra.get("sourceUrl"), original=extra.get("originalFilename"),
                     status=extra.get("commercialUse"))
        base.update({k: v for k, v in extra.items() if k in base})
        reg[base["path"]] = base
    for original, substitute in overrides.get("replacements", {}).items():
        if original not in reg:
            raise SystemExit(f"replacement for unregistered asset: {original}")
        if substitute not in reg:
            raise SystemExit(f"substitute not registered (add it to 'assets' in media_overrides.json): {substitute}")
        reg[original]["replacement"] = substitute
        reg[original]["replacementStatus"] = "REPLACED"

    # every bundled media file must be registered
    bundled = []
    for d, _, fs in os.walk(ASSETS):
        for f in fs:
            if f.lower().endswith(MEDIA_EXT):
                bundled.append(os.path.relpath(os.path.join(d, f), ASSETS).replace(os.sep, "/"))
    missing = sorted(set(bundled) - set(reg))
    if missing:
        raise SystemExit("unregistered media: " + ", ".join(missing))

    entries = sorted(reg.values(), key=lambda e: e["path"])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"schemaVersion": 1, "generatedBy": "scripts/build_media_registry.py",
                   "statuses": {"COMMERCIAL_ALLOWED": "licence permits commercial use (duties may apply)",
                                "NON_COMMERCIAL_ONLY": "licence forbids commercial use",
                                "PERMISSION_REQUIRED": "reuse with credit allowed; commercial use not expressly granted",
                                "LICENSE_UNVERIFIED": "source or licence not confirmed"},
                   "assets": entries}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    write_doc(entries)
    counts = {}
    for e in entries:
        counts[e["commercialUse"]] = counts.get(e["commercialUse"], 0) + 1
    print(len(entries), "media entries:", counts)


def write_doc(entries):
    order = {"NON_COMMERCIAL_ONLY": 0, "LICENSE_UNVERIFIED": 1, "PERMISSION_REQUIRED": 2, "COMMERCIAL_ALLOWED": 3}
    lines = ["# EchoScope – asset licences", "",
             "Generated by `scripts/build_media_registry.py` from the project's own attribution records "
             "(`echo_protocol_full.json`, `assets/*/ATTRIBUTION.txt`). Runtime copy: "
             "`app/src/main/assets/media/media_registry.json`. Unknown information is marked as such – nothing here is "
             "legal advice; confirm before any commercial release.", "",
             "| Status | Meaning |", "|---|---|",
             "| NON_COMMERCIAL_ONLY | licence forbids commercial use (CC BY-NC) |",
             "| LICENSE_UNVERIFIED | source or licence not confirmed |",
             "| PERMISSION_REQUIRED | reuse with credit allowed, commercial use not expressly granted |",
             "| COMMERCIAL_ALLOWED | licence permits commercial use; attribution / ShareAlike duties may apply |", ""]
    summary = {}
    for e in entries:
        summary.setdefault(e["commercialUse"], []).append(e)
    lines += ["## Summary", "", "| Status | Files |", "|---|---|"]
    for st in sorted(summary, key=lambda s: order[s]):
        lines.append(f"| {st} | {len(summary[st])} |")
    lines += ["", "## All assets", "",
              "| Asset | Current Path | Source | License | Commercial Use | Future Action |", "|---|---|---|---|---|---|"]
    for e in sorted(entries, key=lambda e: (order[e["commercialUse"]], e["path"])):
        action = {"NON_COMMERCIAL_ONLY": "Replace, or obtain a commercial licence from the rights holder",
                  "LICENSE_UNVERIFIED": "Confirm source and licence, else replace",
                  "PERMISSION_REQUIRED": "Obtain written commercial permission, else replace",
                  "COMMERCIAL_ALLOWED": "Keep; give attribution" + (" and keep ShareAlike licence" if e["shareAlike"] else "")}[e["commercialUse"]]
        src = e["source"] + (f" ({e['author']})" if e.get("author") else "")
        lines.append(f"| {e['originalFilename'][:70]} | `{e['path']}` | {src[:90]} | {e['license']} | "
                     f"{e['commercialUse']} | {action} |")
    with open(DOC, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
