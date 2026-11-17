#!/usr/bin/env python3
"""
EchoScope – Schema images without any re-drawing (assets/schema/).

  * subcostal_4c: the clean reference vector drawing scripts/reference/subcostal_4ch_ohne_labels.svg,
    copied unchanged (it is already a dark ultrasound-style drawing; labels are drawn by the app).
  * every other view: the original Echocardiographer.org view drawing (PSAX levels cut from its
    short-axis drawing, IVC drawn for EchoScope – see harmonize_schema_dark_theme.source_image),
    upscaled with Lanczos only. No contour tracing, no thresholds, no sharpening, no palette
    reduction – the original anti-aliasing and gradients are kept:
      - light theme: the drawing as it is (white page);
      - dark theme: one smooth per-pixel colour mapping. Neutral pixels are mapped by luminance
        (white page → card #171E26, light-grey sector → slate, black ink → #F1F5F9) and coloured
        pixels keep their colour, blended by a smoothstep of their saturation, so every edge
        fades exactly as in the original.
  * end-systole (ES) versions for views with a left ventricle: a smooth radial warp around the LV
    cavity centre (cv2.remap, bicubic) that shrinks the cavity to 72 % (−28 %) and moves the
    endocardium inwards so the wall thickens; everything outside the LV wall is untouched.
    LV_PARAMS gives apex / base / half-width / wall per view (normalised, read on the drawings).

Usage: python3 scripts/schema_raster.py   (then integrate_library.py and schema_overlays.py)
"""

from __future__ import annotations

import math
import os
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import harmonize_schema_dark_theme as hz  # noqa: E402  (source drawings)

ROOT = hz.ROOT
OUT = os.path.join(hz.ASSETS, "schema")
REFERENCE_SVG = os.path.join(ROOT, "scripts", "reference", "subcostal_4ch_ohne_labels.svg")

BG = np.array([0x17, 0x1E, 0x26], np.float32)
INK = np.array([0xF1, 0xF5, 0xF9], np.float32)
ES_CAVITY = 0.72          # end-systolic cavity size relative to end-diastole

# view -> (apex x, y), (base x, y), cavity half-width (fraction of image width), wall (fraction of width)
LV_PARAMS = {
    "plax_standard": ((0.12, 0.565), (0.62, 0.585), 0.110, 0.045),
    "psax_papillary": ((0.475, 0.42), (0.475, 0.73), 0.130, 0.045),
    "apical_4c": ((0.45, 0.255), (0.57, 0.66), 0.120, 0.040),
    "apical_5c": ((0.44, 0.21), (0.50, 0.63), 0.110, 0.045),
    "apical_2c": ((0.40, 0.275), (0.42, 0.70), 0.100, 0.045),
    "apical_3c": ((0.40, 0.29), (0.48, 0.70), 0.100, 0.045),
}


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def to_dark(im: Image.Image) -> Image.Image:
    a = np.asarray(im.convert("RGB"), np.float32)
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = (mx - mn) / np.maximum(mx, 1)
    lum = (a @ np.array([0.299, 0.587, 0.114], np.float32)) / 255.0
    neutral = INK * (1 - lum[..., None]) + BG * lum[..., None]
    w = smoothstep(0.10, 0.40, sat)[..., None]
    out = a * w + neutral * (1 - w)
    return Image.fromarray(np.clip(out + 0.5, 0, 255).astype(np.uint8))


def lv_frame(view_id, W, H):
    (ax, ay), (bx, by), half, wall = LV_PARAMS[view_id]
    apex = np.array([ax * W, ay * H])
    base = np.array([bx * W, by * H])
    centre = (apex + base) / 2
    along = base - apex
    a_len = np.linalg.norm(along) / 2
    ang = math.atan2(along[1], along[0])
    return centre, ang, a_len, half * W, wall * W


def es_radius(rho_src, rho_e):
    """End-systolic position (normalised ellipse radius) of a point at end-diastolic radius rho_src."""
    k = ES_CAVITY
    return np.where(rho_src <= 1, k * rho_src,
                    np.where(rho_src <= rho_e, k + (rho_src - 1) * (rho_e - k) / (rho_e - 1), rho_src))


def ed_radius(rho_out, rho_e):
    """Inverse of es_radius (used by the image warp)."""
    k = ES_CAVITY
    return np.where(rho_out <= k, rho_out / k,
                    np.where(rho_out <= rho_e, 1 + (rho_out - k) * (rho_e - 1) / (rho_e - k), rho_out))


def _ellipse_coords(view_id, W, H, x, y):
    centre, ang, a_len, b_len, wall = lv_frame(view_id, W, H)
    c, s = math.cos(ang), math.sin(ang)
    dx, dy = x - centre[0], y - centre[1]
    u = (dx * c + dy * s) / a_len
    v = (-dx * s + dy * c) / b_len
    rho = np.sqrt(u * u + v * v)
    rho_e = 1 + wall / b_len
    return centre, ang, a_len, b_len, u, v, rho, rho_e


def systolic(view_id, im: Image.Image) -> Image.Image:
    W, H = im.size
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    centre, ang, a_len, b_len, u, v, rho, rho_e = _ellipse_coords(view_id, W, H, xs, ys)
    # soft fade of the warp at the outer wall so no seam appears
    scale = np.where(rho > 1e-6, ed_radius(rho, rho_e) / np.maximum(rho, 1e-6), 1 / ES_CAVITY)
    us, vs = u * scale, v * scale
    c, s = math.cos(ang), math.sin(ang)
    map_x = (centre[0] + us * a_len * c - vs * b_len * s).astype(np.float32)
    map_y = (centre[1] + us * a_len * s + vs * b_len * c).astype(np.float32)
    src = np.asarray(im.convert("RGB"))
    out = cv2.remap(src, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT)
    return Image.fromarray(out)


def to_systolic_point(view_id, W, H, x, y):
    """Normalised point (x, y) on the ED drawing → its position on the ES drawing."""
    if view_id not in LV_PARAMS:
        return x, y
    centre, ang, a_len, b_len, u, v, rho, rho_e = _ellipse_coords(view_id, W, H, np.float64(x * W), np.float64(y * H))
    if rho < 1e-9:
        return x, y
    f = float(es_radius(rho, rho_e)) / float(rho)
    c, s = math.cos(ang), math.sin(ang)
    px = centre[0] + (u * f) * a_len * c - (v * f) * b_len * s
    py = centre[1] + (u * f) * a_len * s + (v * f) * b_len * c
    return float(px / W), float(py / H)


def source(view_id):
    key = hz.DRAWINGS[view_id]
    src = hz.source_image(key)
    scale = max(1, round(hz.TARGET_WIDTH / src.width))
    return src.resize((src.width * scale, src.height * scale), Image.LANCZOS) if scale > 1 else src


def save(im, name):
    """Lossless WebP: pixel-exact (no compression artefacts on the fine lines), ~ 40 % smaller than PNG."""
    im.save(os.path.join(OUT, name.replace(".png", ".webp")), "WEBP", lossless=True, quality=100, method=6)


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    shutil.copyfile(REFERENCE_SVG, os.path.join(OUT, "subcostal_4c.svg"))
    for view_id in hz.DRAWINGS:
        if view_id == "subcostal_4c":
            continue
        im = source(view_id).convert("RGB")
        save(im, f"{view_id}_light.png")
        save(to_dark(im), f"{view_id}_dark.png")
        if view_id in LV_PARAMS:
            es = systolic(view_id, im)
            save(es, f"{view_id}_es_light.png")
            save(to_dark(es), f"{view_id}_es_dark.png")
        print(view_id, im.size, "ES" if view_id in LV_PARAMS else "")


if __name__ == "__main__":
    main()
