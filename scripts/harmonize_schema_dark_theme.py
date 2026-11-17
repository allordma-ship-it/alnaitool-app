#!/usr/bin/env python3
"""
EchoScope – dark-theme versions of the Schema drawings ("Schnittzeichnung").

The Echocardiographer.org view drawings (assets/echoorg/*.jpg, free reuse with credit) are drawn on
a bright white page with a light-grey ultrasound sector. In a dark scanning room that glare clashes
with the app's dark surfaces, so every drawing is re-coloured:

  * white page around the sector (flood-filled from the image border)  -> card surface #171E26
  * light-grey sector / blood pool (neutral pixels, by luminance)       -> slate #1E293B … #0F172A
  * dark label text and thin outlines (neutral, dark)                     -> pure white
  * saturated pixels (pink / red myocardium, blue valves, purple veins,
    tan probe head, cyan sector edge)                                     -> kept unchanged
  * soft anti-aliased fringes                                             -> blended by saturation

Drawings are upscaled (Lanczos, to ~1000 px wide) first so labels stay readable on tablets.

Three views have no view drawing on Echocardiographer.org, so it is built from material on the site:
  * psax_av        – "A. Aortic Valve Level" cut from the short-axis levels drawing (psax_01.jpg)
  * psax_papillary – "C. Mid-Ventricular Level" from the same drawing, set into a sector like the
                     other drawings
  * subcostal_ivc  – drawn here in the same style (scripts/reference/subcostal_ivc_drawing.svg,
                     rendered with Chromium)

Light theme (daylight / ward): the same drawings re-coloured the other way round – page
#FFFFFF, sector #EEF2F6 … #CBD5E1, labels slate #171E26 – into assets/schema_light/<view_id>.png;
the app picks the variant of the active theme.

The app shows the drawings made by scripts/schema_raster.py, which imports this
module for the source drawings (DRAWINGS, source_image, TARGET_WIDTH). Running this script writes
the re-coloured PNGs to temp_real/schema_png/ as a reference only (not bundled).

Usage: python3 scripts/harmonize_schema_dark_theme.py
"""

from __future__ import annotations

import os
import subprocess
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SRC = os.path.join(ASSETS, "echoorg")
OUT = os.path.join(ROOT, "temp_real", "schema_png", "dark")
OUT_LIGHT = os.path.join(ROOT, "temp_real", "schema_png", "light")
REF = os.path.join(ROOT, "scripts", "reference")
os.makedirs(OUT, exist_ok=True)

CARD = np.array([0x17, 0x1E, 0x26], np.float32)
SLATE_LIGHT = np.array([0x1E, 0x29, 0x3B], np.float32)   # light grey of the sector
SLATE_DARK = np.array([0x0F, 0x17, 0x2A], np.float32)    # darker grey
WHITE = np.array([255, 255, 255], np.float32)
# light theme
L_PAGE = np.array([0xFF, 0xFF, 0xFF], np.float32)
L_SLATE_LIGHT = np.array([0xEE, 0xF2, 0xF6], np.float32)
L_SLATE_DARK = np.array([0xCB, 0xD5, 0xE1], np.float32)
L_INK = np.array([0x17, 0x1E, 0x26], np.float32)
TARGET_WIDTH = 1000   # small site drawings are upscaled (Lanczos) to about this width

# view id -> drawing source
DRAWINGS = {
    "subcostal_4c": "subcostal_02.jpg",
    "subcostal_ivc": "@ivc",
    "plax_standard": "plax_03.jpg",
    "psax_av": "@psax_av",
    "psax_papillary": "@psax_pm",
    "apical_4c": "apical4_02.jpg",
    "apical_5c": "apical5_02.jpg",
    "apical_2c": "apical2_02.jpg",
    "apical_3c": "apical-long-axis_02.jpg",
    "suprasternal_arch": "suprasternal_02.jpg",
}

# Crops of psax_01.jpg (612 x 792 px): the level drawings A and C.
PSAX_CROPS = {"@psax_av": (333, 38, 600, 262), "@psax_pm": (318, 558, 578, 778)}


def render_svg(svg: str, width: int) -> Image.Image:
    """Rasterise an SVG with the pre-installed Chromium (Playwright), white page."""
    out = os.path.join(OUT, "_render.png")
    js = f"""
const {{ chromium }} = require('playwright');
(async () => {{
  const b = await chromium.launch();
  const p = await b.newPage({{ deviceScaleFactor: 1 }});
  const svg = require('fs').readFileSync({svg!r}, 'utf8');
  await p.setContent('<html><body style="margin:0;background:#fff">' + svg.replace('<svg', '<svg id="s" width="{width}"') + '</body></html>');
  const el = await p.$('#s');
  await el.screenshot({{ path: {out!r} }});
  await b.close();
}})();
"""
    subprocess.run(["node", "-e", js], check=True, cwd="/tmp")
    im = Image.open(out).convert("RGB")
    os.remove(out)
    return im


def sector_frame(drawing: Image.Image, label_pos=None) -> Image.Image:
    """Puts a short-axis level drawing into a sector with the probe on top (house style)."""
    dw, dh = drawing.size
    W, H = int(dw * 1.45), int(dh * 1.62)
    page = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(page)
    apex = (W / 2, H * 0.11)
    r = H * 0.86
    import math
    pts = [apex]
    for a in np.linspace(-38, 38, 40):
        pts.append((apex[0] + r * math.sin(math.radians(a)), apex[1] + r * math.cos(math.radians(a))))
    # grey gradient sector (light near the probe, darker far field) with the cyan edge
    grad = Image.new("RGB", (W, H))
    gd = ImageDraw.Draw(grad)
    for y in range(H):
        v = int(236 - 60 * y / H)
        gd.line([(0, y), (W, y)], fill=(v, v, v + 2))
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    page.paste(grad, (0, 0), mask)
    d.line(pts + [apex], fill=(150, 205, 200), width=max(2, W // 260))
    # probe head
    pw, ph = W * 0.16, H * 0.10
    d.rounded_rectangle([apex[0] - pw / 2, apex[1] - ph - 2, apex[0] + pw / 2, apex[1] + 2], radius=8,
                        fill=(214, 190, 170), outline=(150, 128, 110), width=max(1, W // 400))
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", max(10, int(ph * 0.32)))
    d.text((apex[0], apex[1] - ph / 2), "Probe", fill=(60, 50, 45), anchor="mm", font=font)
    # drawing: white of the drawing becomes sector grey (multiply)
    x0, y0 = int((W - dw) / 2), int(H * 0.30)
    region = page.crop((x0, y0, x0 + dw, y0 + dh))
    mult = Image.fromarray((np.asarray(region, np.float32) * np.asarray(drawing, np.float32) / 255).astype(np.uint8))
    page.paste(mult, (x0, y0))
    return page


def source_image(key: str) -> Image.Image:
    if key == "@ivc":
        return render_svg(os.path.join(REF, "subcostal_ivc_drawing.svg"), 1000)
    if key in PSAX_CROPS:
        im = Image.open(os.path.join(SRC, "psax_01.jpg")).convert("RGB").crop(PSAX_CROPS[key])
        # drawn 4x supersampled and reduced with Lanczos: smooth (anti-aliased) sector and probe edges
        big = sector_frame(im.resize((im.width * 4, im.height * 4), Image.LANCZOS))
        return big.resize((big.width // 4, big.height // 4), Image.LANCZOS)
    return Image.open(os.path.join(SRC, key)).convert("RGB")


def harmonize(im: Image.Image, dark: bool = True) -> Image.Image:
    scale = max(1, round(TARGET_WIDTH / im.width))
    if scale > 1:
        im = im.resize((im.width * scale, im.height * scale), Image.LANCZOS).filter(ImageFilter.UnsharpMask(2, 60, 2))
    a = np.asarray(im, np.float32)
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = (mx - mn) / np.maximum(mx, 1)
    lum = a @ np.array([0.299, 0.587, 0.114], np.float32)

    # white page = near-white neutral pixels connected to the image border
    near_white = ((mn >= 238) & (sat < 0.06)).astype(np.uint8)
    n, labels = cv2.connectedComponents(near_white, connectivity=4)
    border = set(np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))) - {0}
    page = np.isin(labels, list(border))
    # grow the page mask a little so JPEG fringes around the sector edge go too
    page_soft = cv2.GaussianBlur(page.astype(np.float32), (0, 0), 1.2)

    # neutral pixels: dark -> white text, light -> slate (light grey -> #1E293B, darker -> #0F172A)
    t_slate = np.clip((lum - 150) / (245 - 150), 0, 1)[..., None]
    lo, hi, ink, card = (SLATE_DARK, SLATE_LIGHT, WHITE, CARD) if dark else (L_SLATE_DARK, L_SLATE_LIGHT, L_INK, L_PAGE)
    slate = lo + (hi - lo) * t_slate
    t_text = np.clip((lum - 95) / (165 - 95), 0, 1)[..., None]       # 0 = ink, 1 = background grey
    neutral = ink * (1 - t_text) + slate * t_text
    neutral = neutral * (1 - page_soft[..., None]) + card * page_soft[..., None]

    # saturated colours are kept; weak tints (anti-aliasing against white / grey) fade into the neutral map
    w = np.clip((sat - 0.10) / 0.22, 0, 1)[..., None]
    w = w * (1 - page[..., None])
    out = a * w + neutral * (1 - w)

    # tan probe head: kept tan but darkened so its (now white) "Probe" label stays readable
    hue = cv2.cvtColor(np.clip(a, 0, 255).astype(np.uint8), cv2.COLOR_RGB2HSV)[..., 0]
    tan = (hue >= 8) & (hue <= 26) & (sat >= 0.07) & (sat <= 0.5) & (lum > 120) & ~page
    tan = cv2.morphologyEx(tan.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8)).astype(bool) & (lum > 120)
    out[tan] = a[tan] * 0.5
    head = cv2.morphologyEx(tan.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8)).astype(bool)
    ink = head & ~tan & (lum < 175)
    out[ink] = WHITE   # white label on the darkened probe head (both themes)
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def main(only: str | None = None):
    for folder in (OUT, OUT_LIGHT):
        os.makedirs(folder, exist_ok=True)
    for view_id, key in DRAWINGS.items():
        if only and view_id != only:
            continue
        src = source_image(key)
        for folder, dark in ((OUT, True), (OUT_LIGHT, False)):
            im = harmonize(src, dark)
            path = os.path.join(folder, f"{view_id}.png")
            im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(path, optimize=True)
            print(os.path.relpath(path, ROOT), im.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
