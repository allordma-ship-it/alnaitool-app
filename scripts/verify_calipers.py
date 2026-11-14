#!/usr/bin/env python3
"""
EchoScope – visual verification of every caliper overlay.

For every guided step with a "caliperOverlay" in app/src/main/assets/echo_protocol_full.json:
  1. extracts the exact frame the app freezes on (ED = first R-wave, ES = first T-wave end,
     EXP = freezeAtMs, LIVE = first frame) from app/src/main/assets/loops/<view>.mp4, using
     the same beat logic as the app (EchoEcgStrip.kt: beatsFor),
  2. draws the caliper(s) onto that frame (yellow "+-----+" lines, teal Doppler cursors with a
     red "=||=" gate, red colour boxes) together with a 2.5x zoom of the measured region on a
     0.01 grid, and the same caliper(s) on the vector schema and the atlas illustration,
  3. writes debug_calipers/<view>_s<step>_<label>.png for visual inspection, plus
     debug_calipers/index.png (contact sheet).

Usage:
  python3 scripts/verify_calipers.py                 # all overlays
  python3 scripts/verify_calipers.py plax_standard   # one view
  python3 scripts/verify_calipers.py --grid VIEW PHASE x0 y0 x1 y1   # gridded frame crop for tracing

Requires ffmpeg, Pillow and (for the schema panel) Node with the pre-installed Playwright.
"""

from __future__ import annotations

import json
import math
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
OUT = os.path.join(ROOT, "debug_calipers")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

YELLOW, TEAL, RED, BLACK = (255, 214, 10), (46, 230, 214), (255, 77, 109), (0, 0, 0)


# ---------------------------------------------------------------------------------------------
# Timing (mirror of EchoEcgStrip.kt beatsFor / LoopState.applyPending)
# ---------------------------------------------------------------------------------------------

def duration_ms(path: str) -> int:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True)
    return int(round(float(out.stdout.strip()) * 1000))


def beats_for(ecg: dict, window_ms: int):
    regular = max(1, ecg.get("beatMs", 833))
    ed, es = ecg.get("edMs", 0), ecg.get("esMs", 325)
    fraction = (es - ed) / regular
    starts = sorted(ecg.get("rWavesMs") or [])
    if not starts:
        starts, t = [], ed
        while t < max(1, window_ms):
            starts.append(t)
            t += regular
    beats = []
    for i, start in enumerate(starts):
        nxt = starts[i + 1] if i + 1 < len(starts) else start + (start - starts[i - 1] if i > 0 else regular)
        length = nxt - start
        beats.append((start, length, start + round(length * fraction)))
    return beats


def freeze_ms(view: dict, step: dict, loop: str) -> int:
    phase = step.get("requiredPhase", "LIVE")
    if phase in ("ED", "ES"):
        first = beats_for(view.get("ecg", {}), duration_ms(loop))[0]
        return first[0] if phase == "ED" else first[2]
    if phase == "EXP":
        return step.get("freezeAtMs") or 0
    return 0


def extract_frame(loop: str, ms: int) -> Image.Image:
    with tempfile.TemporaryDirectory() as tmp:
        png = os.path.join(tmp, "f.png")
        # -ss after -i decodes up to the exact frame (like ExoPlayer's exact seek).
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", loop, "-ss", f"{ms / 1000:.3f}", "-frames:v", "1", png],
                       check=True)
        return Image.open(png).convert("RGB")


# ---------------------------------------------------------------------------------------------
# Drawing (mirror of CaliperOverlays.kt)
# ---------------------------------------------------------------------------------------------

def overlays_of(step: dict) -> list[dict]:
    first = step.get("caliperOverlay")
    return ([first] if first else []) + list(step.get("extraOverlays", []))


def draw_overlay(im: Image.Image, ov: dict, coords: list[float], scale: float = 1.0, label: bool = True):
    d = ImageDraw.Draw(im)
    W, H = im.size
    a = (coords[0] * W, coords[1] * H)
    b = (coords[2] * W, coords[3] * H)
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy) or 1.0
    ux, uy = dx / n, dy / n
    nx, ny = -uy, ux
    w = max(2, round(2 * scale))
    kind = ov["type"]
    if kind == "AREA":
        box = [min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1])]
        d.rectangle(box, outline=BLACK, width=w + 2)
        d.rectangle(box, outline=RED, width=w)
    elif kind in ("DOPPLER_GATE", "CURSOR"):
        steps = int(n // (10 * scale)) + 1
        for i in range(steps):
            t0, t1 = i * 10 * scale / n, min(1.0, (i * 10 * scale + 6 * scale) / n)
            p = (a[0] + dx * t0, a[1] + dy * t0)
            q = (a[0] + dx * t1, a[1] + dy * t1)
            d.line([p, q], fill=BLACK, width=w + 2)
            d.line([p, q], fill=TEAL, width=w)
        if kind == "DOPPLER_GATE":
            for s in (-3 * scale, 3 * scale):
                c = (b[0] + ux * s, b[1] + uy * s)
                bar = 7 * scale
                d.line([(c[0] - nx * bar, c[1] - ny * bar), (c[0] + nx * bar, c[1] + ny * bar)], fill=RED, width=w)
    else:
        d.line([a, b], fill=BLACK, width=w + 2)
        d.line([a, b], fill=YELLOW, width=w)
        t = 6 * scale
        for p in (a, b):
            d.line([(p[0] - nx * t, p[1] - ny * t), (p[0] + nx * t, p[1] + ny * t)], fill=YELLOW, width=w)
            d.line([(p[0] - ux * t * .6, p[1] - uy * t * .6), (p[0] + ux * t * .6, p[1] + uy * t * .6)], fill=YELLOW, width=w)
    if label:
        f = ImageFont.truetype(FONT, max(11, round(11 * scale)))
        x, y = b[0] + 8 * scale, b[1] - 8 * scale
        tw = d.textlength(ov["label"], font=f)
        if x + tw > W:
            x = b[0] - 8 * scale - tw
        d.rectangle([x - 2, y - 2, x + tw + 2, y + 14 * scale], fill=(0, 0, 0))
        d.text((x, y), ov["label"], fill=YELLOW if kind == "LINE" else TEAL, font=f)


def grid_crop(im: Image.Image, box: tuple[float, float, float, float], width: int = 520, enhance: bool = False) -> Image.Image:
    """Crop of normalised [x0, y0, x1, y1] with a 0.01 grid (0.05 lines labelled)."""
    W, H = im.size
    x0, y0, x1, y1 = box
    crop = im.crop((int(x0 * W), int(y0 * H), int(x1 * W), int(y1 * H)))
    if enhance:  # stretch dark loops so endocardial borders are visible
        crop = ImageOps.autocontrast(crop, cutoff=1).point(lambda v: int(255 * (v / 255) ** 0.55))
    s = width / max(1, crop.width)
    crop = crop.resize((width, max(1, round(crop.height * s))), Image.LANCZOS)
    d = ImageDraw.Draw(crop)
    f = ImageFont.truetype(FONT, 10)
    fine = (x1 - x0) <= 0.45  # 0.01 lines only on close-ups, otherwise 0.05
    for k in range(0, 101):
        v = k / 100
        major = k % 5 == 0
        if not fine and not major:
            continue
        col = (200, 50, 50) if major else (55, 55, 95)
        if x0 <= v <= x1:
            X = (v - x0) / (x1 - x0) * crop.width
            d.line([(X, 0), (X, crop.height)], fill=col)
            if major:
                d.text((X + 2, 2), f"{v:.2f}", fill=(255, 255, 0), font=f)
        if y0 <= v <= y1:
            Y = (v - y0) / (y1 - y0) * crop.height
            d.line([(0, Y), (crop.width, Y)], fill=col)
            if major:
                d.text((2, Y + 1), f"{v:.2f}", fill=(255, 255, 0), font=f)
    return crop


def render_svg(svg_path: str, width: int) -> Image.Image | None:
    """Rasterises an SVG with the pre-installed Chromium (Playwright)."""
    if not os.path.exists(svg_path):
        return None
    with tempfile.TemporaryDirectory() as tmp:
        png = os.path.join(tmp, "s.png")
        js = os.path.join(tmp, "r.js")
        with open(js, "w") as f:
            f.write("""
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [svg, out, width] = process.argv.slice(2);
  const text = fs.readFileSync(svg, 'utf8');
  const m = text.match(/viewBox="([\\d.\\s-]+)"/);
  const vb = m ? m[1].trim().split(/\\s+/).map(Number) : [0, 0, 600, 420];
  const w = Number(width), h = Math.round(w * vb[3] / vb[2]);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  await page.setContent(`<html><body style="margin:0">${text.replace('<svg ', `<svg style="display:block;width:${w}px;height:${h}px" `)}</body></html>`);
  await page.screenshot({ path: out });
  await browser.close();
})();
""")
        env = dict(os.environ, NODE_PATH=subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
        r = subprocess.run(["node", js, svg_path, png, str(width)], capture_output=True, text=True, env=env)
        if r.returncode != 0:
            print("   svg render failed:", r.stderr.strip()[:200])
            return None
        return Image.open(png).convert("RGB")


def bbox(coords_list: list[list[float]], pad: float = 0.08):
    xs = [c[i] for c in coords_list for i in (0, 2)]
    ys = [c[i] for c in coords_list for i in (1, 3)]
    return (max(0.0, min(xs) - pad), max(0.0, min(ys) - pad), min(1.0, max(xs) + pad), min(1.0, max(ys) + pad))


def panel_title(im: Image.Image, text: str) -> Image.Image:
    out = Image.new("RGB", (im.width, im.height + 22), (18, 22, 30))
    out.paste(im, (0, 22))
    ImageDraw.Draw(out).text((6, 4), text, fill=(230, 230, 230), font=ImageFont.truetype(FONT, 13))
    return out


def hstack(images: list[Image.Image], height: int) -> Image.Image:
    scaled = [i.resize((max(1, round(i.width * height / i.height)), height), Image.LANCZOS) for i in images]
    out = Image.new("RGB", (sum(i.width for i in scaled) + 8 * (len(scaled) - 1), height), (8, 10, 14))
    x = 0
    for i in scaled:
        out.paste(i, (x, 0))
        x += i.width + 8
    return out


# ---------------------------------------------------------------------------------------------

def verify(view_filter: str | None = None):
    os.makedirs(OUT, exist_ok=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    sheets = []
    for window in db["windows"]:
        for view in window["views"]:
            if view_filter and view["id"] != view_filter:
                continue
            loop = os.path.join(ASSETS, view["videoAsset"])
            schema_svg = os.path.join(ASSETS, view.get("schemaAsset") or "")
            schema = render_svg(schema_svg, 720) if view.get("schemaAsset") else None
            atlas = Image.open(os.path.join(ASSETS, view["diagramAsset"])).convert("RGB") if view.get("diagramAsset") else None
            for i, step in enumerate(view["steps"]):
                ovs = overlays_of(step)
                if not ovs:
                    continue
                ms = freeze_ms(view, step, loop)
                frame = extract_frame(loop, ms)
                drawn = frame.copy()
                for ov in ovs:
                    draw_overlay(drawn, ov, ov["video"])
                video_box = bbox([o["video"] for o in ovs])
                zoom = frame.copy()
                for ov in ovs:
                    draw_overlay(zoom, ov, ov["video"], label=False)
                zoom = grid_crop(zoom, video_box)
                raw_zoom = grid_crop(frame, video_box, enhance=True)
                panels = [
                    panel_title(drawn, f"{view['id']} · step {i + 1} · {step['requiredPhase']} @ {ms} ms"),
                    panel_title(raw_zoom, "frame (no caliper)"),
                    panel_title(zoom, "caliper zoom"),
                ]
                if schema is not None:
                    s = schema.copy()
                    x0, y0, x1, y1 = view.get("schemaCrop") or [0, 0, 1, 1]
                    for ov in ovs:
                        v = ov["video"]
                        draw_overlay(s, ov, [(v[0] - x0) / (x1 - x0), (v[1] - y0) / (y1 - y0),
                                             (v[2] - x0) / (x1 - x0), (v[3] - y0) / (y1 - y0)])
                    panels.append(panel_title(s, "schema"))
                if atlas is not None:
                    a = atlas.copy()
                    for ov in ovs:
                        if ov.get("diagram"):
                            draw_overlay(a, ov, ov["diagram"], scale=a.width / 720)
                    panels.append(panel_title(a, "atlas"))
                sheet = hstack(panels, 380)
                name = re.sub(r"[^A-Za-z0-9]+", "_", ovs[0]["label"]).strip("_")
                path = os.path.join(OUT, f"{view['id']}_s{i + 1}_{name}.png")
                sheet.save(path)
                sheets.append(sheet)
                print(f"{os.path.relpath(path, ROOT)}  ({step['requiredPhase']} @ {ms} ms, {len(ovs)} caliper(s))")
    if sheets and not view_filter:
        w = max(s.width for s in sheets)
        thumbs = [s.resize((w // 2, s.height // 2)) if s.width == w else s.resize((s.width // 2, s.height // 2)) for s in sheets]
        index = Image.new("RGB", (w // 2, sum(t.height + 4 for t in thumbs)), (0, 0, 0))
        y = 0
        for t in thumbs:
            index.paste(t, (0, y))
            y += t.height + 4
        index.save(os.path.join(OUT, "index.png"))
    print(f"{len(sheets)} caliper sheet(s) in {os.path.relpath(OUT, ROOT)}/")


def grid(view_id: str, phase: str, box: list[float]):
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    view = next(v for w in db["windows"] for v in w["views"] if v["id"] == view_id)
    loop = os.path.join(ASSETS, view["videoAsset"])
    ms = freeze_ms(view, {"requiredPhase": phase, "freezeAtMs": 0}, loop)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"grid_{view_id}_{phase}.png")
    grid_crop(extract_frame(loop, ms), tuple(box), width=900, enhance=True).save(path)
    print(path, f"{phase} @ {ms} ms")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--grid":
        grid(sys.argv[2], sys.argv[3], [float(v) for v in sys.argv[4:8]])
    else:
        verify(sys.argv[1] if len(sys.argv) > 1 else None)
