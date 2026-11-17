#!/usr/bin/env python3
"""
EchoScope – Schema images without any re-drawing (assets/schema/).

  * every view (subcostal_4c included, so all share one illustrative style): the original Echocardiographer.org view drawing (PSAX levels cut from its
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
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
import harmonize_schema_dark_theme as hz  # noqa: E402  (source drawings)

ROOT = hz.ROOT
OUT = os.path.join(hz.ASSETS, "schema")

BG = np.array([0x17, 0x1E, 0x26], np.float32)
INK = np.array([0xF1, 0xF5, 0xF9], np.float32)
ES_CAVITY = 0.75          # end-systolic cavity size relative to end-diastole (−25 %)

# view -> (apex x, y), (base x, y), cavity half-width (fraction of image width), wall (fraction of width)
LV_PARAMS = {
    "plax_standard": ((0.12, 0.565), (0.62, 0.585), 0.110, 0.045),
    "psax_papillary": ((0.475, 0.42), (0.475, 0.73), 0.130, 0.045),
    "apical_4c": ((0.45, 0.255), (0.57, 0.66), 0.120, 0.040),
    "apical_5c": ((0.44, 0.21), (0.50, 0.63), 0.110, 0.045),
    "apical_2c": ((0.40, 0.275), (0.42, 0.70), 0.100, 0.045),
    "apical_3c": ((0.40, 0.29), (0.48, 0.70), 0.100, 0.045),
}
# right ventricle (apical 4- / 5-chamber), warped after the LV
RV_PARAMS = {
    "apical_4c": ((0.27, 0.30), (0.25, 0.60), 0.060, 0.025),
    "apical_5c": ((0.27, 0.31), (0.26, 0.60), 0.055, 0.025),
}


def chambers(view_id):
    return [p for p in (LV_PARAMS.get(view_id), RV_PARAMS.get(view_id)) if p]


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


def _frame(params, W, H):
    (ax, ay), (bx, by), half, wall = params
    apex = np.array([ax * W, ay * H])
    base = np.array([bx * W, by * H])
    centre = (apex + base) / 2
    along = base - apex
    return centre, math.atan2(along[1], along[0]), np.linalg.norm(along) / 2, half * W, wall * W


def _ellipse(params, W, H, x, y):
    centre, ang, a_len, b_len, wall = _frame(params, W, H)
    c, s = math.cos(ang), math.sin(ang)
    dx, dy = x - centre[0], y - centre[1]
    u = (dx * c + dy * s) / a_len
    v = (-dx * s + dy * c) / b_len
    return centre, ang, a_len, b_len, u, v, np.sqrt(u * u + v * v), 1 + wall / b_len


def _warp(params, src):
    H, W = src.shape[:2]
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    centre, ang, a_len, b_len, u, v, rho, rho_e = _ellipse(params, W, H, xs, ys)
    scale = np.where(rho > 1e-6, ed_radius(rho, rho_e) / np.maximum(rho, 1e-6), 1 / ES_CAVITY)
    us, vs = u * scale, v * scale
    c, s = math.cos(ang), math.sin(ang)
    map_x = (centre[0] + us * a_len * c - vs * b_len * s).astype(np.float32)
    map_y = (centre[1] + us * a_len * s + vs * b_len * c).astype(np.float32)
    return cv2.remap(src, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT)


def systolic(view_id, im: Image.Image) -> Image.Image:
    """Cavity −25 %, wall thickened inwards: LV first, then RV (smooth bicubic remap, no seams)."""
    a = np.asarray(im.convert("RGB"))
    for params in chambers(view_id):
        a = _warp(params, a)
    return Image.fromarray(a)


def to_systolic_point(view_id, W, H, x, y):
    """Normalised point (x, y) on the ED drawing → its position on the ES drawing (same warps, same order)."""
    px, py = x * W, y * H
    for params in chambers(view_id):
        centre, ang, a_len, b_len, u, v, rho, rho_e = _ellipse(params, W, H, np.float64(px), np.float64(py))
        if rho < 1e-9:
            continue
        f = float(es_radius(rho, rho_e)) / float(rho)
        c, s = math.cos(ang), math.sin(ang)
        px = centre[0] + (u * f) * a_len * c - (v * f) * b_len * s
        py = centre[1] + (u * f) * a_len * s + (v * f) * b_len * c
    return float(px / W), float(py / H)


# ---------------------------------------------------------------------------------------------
# transducer
# ---------------------------------------------------------------------------------------------
def probe_sprite(head_w: float) -> Image.Image:
    """
    Phased-array transducer pointing down (face at the bottom centre): wide head with a dark lens,
    smooth neck, tapered handle, cable stub, and the blue index marker on the screen-right side
    (cardiology convention). Drawn 4x supersampled, reduced with Lanczos.
    """
    S = 4
    w = head_w * S
    W, H = int(w * 1.5), int(w * 1.75)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cx, face = W / 2, H - 2
    outline = (51, 65, 85, 255)
    lw = max(2, int(w * 0.035))
    # cable stub
    d.rounded_rectangle([cx - w * 0.07, 0, cx + w * 0.07, face - w * 1.25], radius=w * 0.05, fill=(71, 85, 105, 255))
    # handle (tapered) + neck
    handle = [(cx - w * 0.22, face - w * 1.32), (cx + w * 0.22, face - w * 1.32), (cx + w * 0.28, face - w * 0.78),
              (cx + w * 0.40, face - w * 0.50), (cx - w * 0.40, face - w * 0.50), (cx - w * 0.28, face - w * 0.78)]
    d.polygon(handle, fill=(203, 213, 225, 255), outline=outline, width=lw)
    d.rounded_rectangle([cx - w * 0.22, face - w * 1.40, cx + w * 0.22, face - w * 1.22], radius=w * 0.09,
                        fill=(203, 213, 225, 255), outline=outline, width=lw)
    for k in range(3):   # grip lines
        y = face - w * (1.10 - k * 0.10)
        d.line([(cx - w * 0.16, y), (cx + w * 0.16, y)], fill=(148, 163, 184, 255), width=max(1, lw // 2))
    # head: wide, rounded, touching the cone apex with its face
    d.rounded_rectangle([cx - w * 0.5, face - w * 0.52, cx + w * 0.5, face], radius=w * 0.16,
                        fill=(226, 232, 240, 255), outline=outline, width=lw)
    # acoustic lens (face)
    d.rounded_rectangle([cx - w * 0.44, face - w * 0.12, cx + w * 0.44, face], radius=w * 0.06, fill=(30, 41, 59, 255))
    # blue index marker on the screen-right side of the head
    r = w * 0.085
    mx, my = cx + w * 0.5 + r * 0.2, face - w * 0.33
    d.ellipse([mx - r, my - r, mx + r, my + r], fill=(30, 136, 229, 255), outline=(255, 255, 255, 255), width=max(1, lw // 2))
    return im.resize((W // S, H // S), Image.LANCZOS)


def place_probe(img: Image.Image, apex, angle_deg: float, head_w: float) -> Image.Image:
    """Pastes the transducer with its face centre on [apex], pointing along [angle_deg] (90 = straight down)."""
    sprite = probe_sprite(head_w)
    W, H = sprite.size
    side = int(math.hypot(W, H) * 2) + 4
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(sprite, (side // 2 - W // 2, side // 2 - H + 2), sprite)   # face centre at the canvas centre
    rotated = canvas.rotate(-(angle_deg - 90), resample=Image.BICUBIC, center=(side / 2, side / 2))
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    layer.paste(rotated, (int(round(apex[0] - side / 2)), int(round(apex[1] - side / 2))), rotated)
    out = img.convert("RGBA")
    out.alpha_composite(layer)
    return out.convert("RGB")


def replace_probe(im: Image.Image):
    """Erases the drawing's tan probe (and its label) and returns (clean image, apex, axis angle, head width)."""
    a = np.asarray(im.convert("RGB")).copy()
    hsv = cv2.cvtColor(a, cv2.COLOR_RGB2HSV)
    h, sat = hsv[..., 0].astype(int), hsv[..., 1].astype(int)
    lum = a.astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)
    tan = ((h >= 8) & (h <= 26) & (sat >= 18) & (sat <= 140) & (lum > 110)).astype(np.uint8)
    tan = cv2.morphologyEx(tan, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(tan, connectivity=8)
    if n <= 1:
        return im, None, None, None
    i = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    probe = (lab == i).astype(np.uint8)
    pts = cv2.findNonZero(probe)
    hull = np.zeros_like(probe)
    cv2.fillConvexPoly(hull, cv2.convexHull(pts), 1)
    ys, xs = np.nonzero(probe)
    bottom = ys.max()
    old_face = np.array([xs[ys >= bottom - 3].mean(), bottom], float)
    # sector: the large non-white region (before erasing, so its top edge is intact)
    sector = (a.min(axis=2) <= 238).astype(np.uint8)
    sector[hull > 0] = 0
    sector = cv2.morphologyEx(sector, cv2.MORPH_OPEN, np.ones((7, 7), np.uint8))
    n2, lab2, st2, _ = cv2.connectedComponentsWithStats(sector, connectivity=8)
    sector = lab2 == (1 + int(np.argmax(st2[1:, cv2.CC_STAT_AREA])))
    sy, sx = np.nonzero(sector)
    # erase the old probe with a generous margin (its outline lies outside the tan fill)
    erase = cv2.dilate(hull, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (25, 25)))
    erase[sector] = 0
    a[erase > 0] = 255
    # contact point = sector pixel nearest to the old probe face; axis = towards the sector centroid
    j = int(np.argmin((sx - old_face[0]) ** 2 + (sy - old_face[1]) ** 2))
    contact = np.array([sx[j], sy[j]], float)
    ang = math.degrees(math.atan2(sy.mean() - contact[1], sx.mean() - contact[0]))
    # the face sits on the cone top, 3 px into it
    apex = (float(contact[0] + 3 * math.cos(math.radians(ang))), float(contact[1] + 3 * math.sin(math.radians(ang))))
    head_w = float(xs.max() - xs.min()) * 0.95
    return Image.fromarray(a), apex, ang, head_w


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
    # every view, subcostal_4c included, uses its illustrative view drawing (one consistent style)
    for view_id in hz.DRAWINGS:
        clean, apex, ang, head_w = replace_probe(source(view_id).convert("RGB"))
        variants = {"": clean}
        if view_id in LV_PARAMS:
            variants["_es"] = systolic(view_id, clean)
        for suffix, im in variants.items():
            for theme, img in (("light", im), ("dark", to_dark(im))):
                if apex is not None:
                    img = place_probe(img, apex, ang, head_w)
                save(img, f"{view_id}{suffix}_{theme}.png")
        print(view_id, clean.size, f"probe apex={apex} angle={ang and round(ang)}", "ES" if view_id in LV_PARAMS else "")


if __name__ == "__main__":
    main()
