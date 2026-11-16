#!/usr/bin/env python3
"""
EchoScope – visual audit of every arrow, caliper, PW gate and CW cursor.

For each caliper overlay (caliperOverlay + extraOverlays) of every guided step this script
  1. extracts the frame where the measurement is physiologically made:
       * ED / ES / EXP steps: the frame the app freezes on (same logic as the app),
       * LIVE Doppler steps: the phase of the flow being sampled – mitral inflow and TDI in early
         diastole (mitral leaflets open), LVOT / aortic valve / pulmonary valve in mid-systole,
         TR in systole – see INSPECT,
  2. draws the overlay on that frame: LINE '+---+' (yellow), PW / TDI gate '=||=' (red bars on
     a dashed teal cursor), CW cursor '--◇--' (dashed teal line with a diamond at the focus
     point), colour box (red); a 3x zoom of the target region with a 0.05 grid is added,
  3. renders the same overlay on the dark Schema drawing (schema coordinates,
     scripts/schema_overlays.py) and on the atlas illustration (diagram coordinates),
and writes debug_overlays/<view>_s<step>_<n>_<label>.png plus debug_overlays/index.png.

Every image is inspected by eye before echo_protocol_full.json is changed
(scripts/measurement_overlays.py holds the coordinates).

Usage: python3 scripts/verify_doppler_and_calipers.py [view_id]
"""

from __future__ import annotations

import json
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
import verify_calipers as vc  # noqa: E402  (frame extraction, beat logic, SVG rendering)

ROOT = vc.ROOT
OUT = os.path.join(ROOT, "debug_overlays")
FONT = vc.FONT

# label substring -> (phase, fraction of the beat after the R-wave) for LIVE Doppler overlays
INSPECT = [
    ("E/A", ("diastole", 0.62)),
    ("TDI", ("diastole", 0.62)),
    ("e'", ("diastole", 0.62)),
    ("LVOT", ("systole", 0.20)),
    ("AV", ("systole", 0.20)),
    ("TR", ("systole", 0.25)),
    ("PV", ("systole", 0.20)),
    ("TAPSE", ("ED", 0.0)),
]


# Frame of each colour loop where the jet is fully developed (checked by eye).
GUIDE_COLOUR_MS = {"a4c_mv_color.mp4": 1500, "plax_color.mp4": 1300, "a4c_tv_color.mp4": 600, "a5c_av_color.mp4": 600}


def inspect_ms(view: dict, step: dict, overlay: dict, loop: str) -> tuple[int, str]:
    phase = step.get("requiredPhase", "LIVE")
    if phase != "LIVE":
        return vc.freeze_ms(view, step, loop), phase
    for key, (name, frac) in INSPECT:
        if key in overlay["label"]:
            beat = vc.beats_for(view.get("ecg", {}), vc.duration_ms(loop))[0]
            return beat[0] + round(beat[1] * frac), name
    return 0, "LIVE"


def draw(im: Image.Image, ov: dict, coords: list[float], scale: float = 1.0, label: bool = True):
    vc.draw_overlay(im, ov, coords, scale, label)
    if ov["type"] == "CURSOR":   # CW: diamond at the focus point on the cursor
        d = ImageDraw.Draw(im)
        W, H = im.size
        x, y = coords[2] * W, coords[3] * H
        a = (coords[0] * W, coords[1] * H)
        t = 0.82   # focus marker at 82 % of the way to the end point (valve plane in the app)
        cx, cy = a[0] + (x - a[0]) * t, a[1] + (y - a[1]) * t
        r = 6 * scale
        d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], outline=vc.TEAL, fill=(0, 0, 0))


def main(only: str | None = None):
    os.makedirs(OUT, exist_ok=True)
    with open(vc.DB, encoding="utf-8") as f:
        db = json.load(f)
    sheets = []
    for window in db["windows"]:
        for view in window["views"]:
            if only and view["id"] != only:
                continue
            loop = os.path.join(vc.ASSETS, view["videoAsset"])
            drawing = os.path.join(vc.ASSETS, "schema_dark", f"{view['id']}.png")
            schema = Image.open(drawing).convert("RGB") if os.path.exists(drawing) else None
            atlas = Image.open(os.path.join(vc.ASSETS, view["diagramAsset"])).convert("RGB") if view.get("diagramAsset") else None
            for i, step in enumerate(view["steps"]):
                for n, ov in enumerate(vc.overlays_of(step)):
                    ms, phase = inspect_ms(view, step, ov, loop)
                    frame = vc.extract_frame(loop, ms)
                    full = frame.copy()
                    draw(full, ov, ov["video"])
                    zoom_src = vc.ImageOps.autocontrast(frame, cutoff=1).point(lambda v: int(255 * (v / 255) ** 0.6))
                    draw(zoom_src, ov, ov["video"], label=False)
                    zoom = vc.grid_crop(zoom_src, vc.bbox([ov["video"]], pad=0.10), width=520)
                    panels = [vc.panel_title(full, f"{view['id']} s{i + 1} · {ov['label']} · {phase} @ {ms} ms"),
                              vc.panel_title(zoom, "zoom")]
                    if schema is not None and ov.get("schema"):
                        s = schema.copy()
                        draw(s, ov, ov["schema"], scale=s.width / 720)
                        panels.append(vc.panel_title(s, "schema"))
                    if atlas is not None and ov.get("diagram"):
                        a = atlas.copy()
                        draw(a, ov, ov["diagram"], scale=a.width / 720)
                        panels.append(vc.panel_title(a, "atlas"))
                    sheet = vc.hstack(panels, 360)
                    name = re.sub(r"[^A-Za-z0-9]+", "_", ov["label"]).strip("_")
                    path = os.path.join(OUT, f"{view['id']}_s{i + 1}_{n}_{name}.png")
                    sheet.save(path)
                    sheets.append(sheet)
                    print(os.path.relpath(path, ROOT), f"({phase} @ {ms} ms)")
    # Valve measurement guide: the same overlays on the loop the guide plays (2D or colour).
    views = {v["id"]: v for w in db["windows"] for v in w["views"]}
    for valve in db.get("valves", []):
        for meas in valve.get("measurements", []):
            ov = meas.get("overlay")
            if not ov or (only and meas["view"] != only):
                continue
            loop = os.path.join(vc.ASSETS, meas["loop"])
            if "/color/" in meas["loop"]:
                ms, phase = GUIDE_COLOUR_MS.get(os.path.basename(meas["loop"]), 0), "colour"
            else:
                ms, phase = inspect_ms(views[meas["view"]], {"requiredPhase": "LIVE"}, ov, loop)
                if ov["type"] == "LINE":
                    ms, phase = round(vc.beats_for(views[meas["view"]]["ecg"], vc.duration_ms(loop))[0][1] * 0.25), "mid-systole"
            frame = vc.extract_frame(loop, ms)
            full = frame.copy()
            draw(full, ov, ov["video"])
            z = frame.copy()
            draw(z, ov, ov["video"], label=False)
            zoom = vc.grid_crop(z, vc.bbox([ov["video"]], pad=0.10), width=520)
            sheet = vc.hstack([vc.panel_title(full, f"guide {valve['id']} · {meas['id']} · {phase} @ {ms} ms"),
                               vc.panel_title(zoom, "zoom")], 360)
            path = os.path.join(OUT, f"guide_{valve['id']}_{meas['id']}.png")
            sheet.save(path)
            sheets.append(sheet)
            print(os.path.relpath(path, ROOT), f"({phase} @ {ms} ms)")
    if sheets and not only:
        w = max(s.width for s in sheets) // 2
        thumbs = [s.resize((s.width // 2, s.height // 2)) for s in sheets]
        index = Image.new("RGB", (w, sum(t.height + 4 for t in thumbs)))
        y = 0
        for t in thumbs:
            index.paste(t, (0, y))
            y += t.height + 4
        index.save(os.path.join(OUT, "index.png"))
    print(f"{len(sheets)} overlay sheet(s) in debug_overlays/")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
