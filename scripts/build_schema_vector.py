#!/usr/bin/env python3
"""
EchoScope – vector Schema for every TTE view (assets/schema_vector/<view>_{dark,light}.svg).

The anatomy is traced from the medical view drawings (Echocardiographer.org; PSAX levels cut from
its short-axis drawing; IVC drawn for EchoScope – see scripts/harmonize_schema_dark_theme.py):
the myocardium, valves, sector and probe are segmented, their contours simplified and turned
into smooth cubic Bézier paths (Catmull-Rom → cubicTo), and each chamber is filled by region
growing from its label:

  * left heart (LV, LA, LVOT, Ao, DA)   fill #E53935 @ 18 %, stroke #EF5350
  * right heart (RV, RA, RVOT, PA, IVC) fill #1E88E5 @ 18 %, stroke #42A5F5
  * myocardium                          translucent rose with a lighter rim
  * valve leaflets                      off-white strokes
  * ultrasound sector                   dark slate cone (#1E293B → #0F172A) / light slate in light mode

The canvas keeps the size of the source drawing, so the calipers / Doppler cursors placed on the
drawing (scripts/schema_overlays.py) stay on the same structures. Labels are not baked in: they go
to the protocol ("labels" of the Schema page) and are drawn by the app as pills that scale with
the font size and never sit under a caliper label.

Usage: python3 scripts/build_schema_vector.py [view_id]   (then scripts/integrate_library.py)
"""

from __future__ import annotations

import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import harmonize_schema_dark_theme as hz  # noqa: E402  (source drawings, upscaling)

ROOT = hz.ROOT
ASSETS = hz.ASSETS
OUT = os.path.join(ASSETS, "schema_vector")
LABELS_JSON = os.path.join(ROOT, "scripts", "schema_labels.json")

LEFT = {"LV", "LA", "LVOT", "Ao", "Aorta", "DA", "Desc. Ao", "LAA"}
RIGHT = {"RV", "RA", "RVOT", "PA", "IVC", "HV", "RPA"}

# Label positions on each drawing (normalised; read on a 0.025 grid). kind: chamber / valve / other.
LABELS = {
    "apical_4c": [("RV", .26, .44), ("LV", .47, .40), ("RA", .28, .75), ("LA", .55, .77), ("DA", .83, .865),
                  ("TV", .30, .585, "valve"), ("MV", .60, .605, "valve")],
    "apical_5c": [("RV", .27, .47), ("LV", .50, .42), ("RA", .27, .78), ("LA", .56, .80), ("LVOT", .465, .61),
                  ("TV", .31, .595, "valve"), ("MV", .57, .625, "valve"), ("AV", .40, .725, "valve")],
    "apical_2c": [("LV", .40, .38), ("LA", .42, .84), ("LAA", .69, .75), ("MV", .42, .66, "valve")],
    "apical_3c": [("LV", .44, .42), ("LA", .44, .89), ("RVOT", .71, .58), ("Ao", .72, .78), ("LVOT", .53, .66),
                  ("MV", .40, .76, "valve"), ("AV", .57, .735, "valve")],
    "plax_standard": [("RVOT", .47, .38), ("LV", .20, .51), ("LA", .75, .60), ("Ao", .73, .37), ("DA", .655, .80),
                      ("LVOT", .55, .51), ("MV", .60, .66, "valve"), ("AV", .63, .465, "valve")],
    "psax_av": [("RVOT", .43, .40), ("RA", .35, .72), ("LA", .51, .76), ("PA", .69, .62),
                ("PV", .62, .43, "valve"), ("TV", .30, .52, "valve"), ("AV", .495, .535, "valve")],
    "psax_papillary": [("RV", .29, .46), ("LV", .47, .60), ("ALPM", .66, .55, "other"), ("PMPM", .50, .76, "other")],
    "subcostal_4c": [("RV", .50, .53), ("LV", .71, .65), ("RA", .27, .70), ("LA", .45, .84),
                     ("TV", .42, .605, "valve"), ("MV", .59, .74, "valve"), ("Liver", .18, .40, "other")],
    "subcostal_ivc": [("IVC", .42, .655), ("RA", .79, .745), ("HV", .556, .53), ("Liver", .46, .40, "other")],
    "suprasternal_arch": [("Ao", .39, .43), ("RPA", .36, .575), ("LA", .21, .82)],
}
# Chamber seeds that differ from the label position (label sits on a wall / outside the lumen).
SEEDS = {
    ("plax_standard", "Ao"): [(.75, .32)], ("plax_standard", "DA"): [(.665, .77)],
    ("apical_4c", "DA"): [(.83, .855)],
    ("suprasternal_arch", "Ao"): [(.12, .56), (.30, .42), (.48, .44), (.60, .60)],
    ("subcostal_ivc", "IVC"): [(.40, .62), (.25, .55)], ("subcostal_ivc", "HV"): [(.621, .493)],
    ("apical_3c", "Ao"): [(.70, .75)], ("psax_av", "PA"): [(.67, .66)],
}

THEMES = {
    "dark": {"page": "#171E26", "cone_top": "#1E293B", "cone_bottom": "#0F172A", "cone_edge": "#26C6DA",
             "myo": "#C2475B", "myo_op": .62, "myo_rim": "#F48FB1", "valve": "#E2E8F0",
             "left_fill": "#E53935", "left_stroke": "#EF5350", "right_fill": "#1E88E5", "right_stroke": "#42A5F5",
             "probe": "#94A3B8", "probe_face": "#1E88E5", "vessel": "#9575CD"},
    "light": {"page": "#FFFFFF", "cone_top": "#F1F5F9", "cone_bottom": "#CBD5E1", "cone_edge": "#0097A7",
              "myo": "#D9566E", "myo_op": .55, "myo_rim": "#AD1457", "valve": "#334155",
              "left_fill": "#E53935", "left_stroke": "#C62828", "right_fill": "#1E88E5", "right_stroke": "#1565C0",
              "probe": "#64748B", "probe_face": "#1565C0", "vessel": "#7E57C2"},
}


# ---------------------------------------------------------------------------------------------
# segmentation
# ---------------------------------------------------------------------------------------------
def masks(im: Image.Image):
    a = np.asarray(im.convert("RGB"))
    hsv = cv2.cvtColor(a, cv2.COLOR_RGB2HSV)
    h, s, v = hsv[..., 0].astype(int), hsv[..., 1].astype(int), hsv[..., 2].astype(int)
    lum = a.astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)
    mn = a.min(axis=2)
    near_white = ((mn >= 238) & (s < 16)).astype(np.uint8)
    n, lab = cv2.connectedComponents(near_white, connectivity=4)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    page = np.isin(lab, list(border))
    red = ((h >= 140) | (h <= 10)) & (s >= 70) & (v >= 70)
    purple = (h >= 120) & (h < 140) & (s >= 70)
    blue = (h >= 90) & (h < 120) & (s >= 70) & (v >= 60)
    lavender = (s >= 18) & (s < 90) & (lum < 175) & (lum > 70) & (h >= 100) & (h < 150)
    tan = (h >= 8) & (h <= 26) & (s >= 18) & (s <= 130) & (lum > 120)
    k = np.ones((3, 3), np.uint8)
    wall = cv2.morphologyEx(red.astype(np.uint8), cv2.MORPH_OPEN, k)
    vessel = cv2.morphologyEx(purple.astype(np.uint8), cv2.MORPH_OPEN, k)
    valve = cv2.morphologyEx((blue | lavender).astype(np.uint8), cv2.MORPH_OPEN, k)
    probe = cv2.morphologyEx(tan.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    probe = keep_large(probe, 0.002)
    sector = (~page).astype(np.uint8)
    sector = cv2.morphologyEx(sector, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
    sector[probe > 0] = 0
    sector = keep_large(sector, 0.05)
    return {"page": page, "wall": keep_large(wall, 0.0004), "vessel": keep_large(vessel, 0.0004),
            "valve": keep_large(valve, 0.00015), "probe": probe, "sector": sector}


def keep_large(m, frac):
    n, lab, stats, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), connectivity=8)
    out = np.zeros_like(m, np.uint8)
    min_area = frac * m.size
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] >= min_area:
            out[lab == i] = 1
    return out


# Closing lines for vessels / chambers that the drawing leaves open (normalised segments).
def _ivc(x, y):
    return (x / 720, (y + 64) / 556)


BARRIERS = {
    "subcostal_ivc": [(_ivc(170, 212), _ivc(150, 248)), (_ivc(505, 276), _ivc(488, 340)),
                      (_ivc(410, 150), _ivc(442, 146)), (_ivc(452, 276), _ivc(478, 272))],
    "plax_standard": [((.81, .20), (.885, .28))],
    "suprasternal_arch": [((.03, .685), (.14, .70)), ((.53, .745), (.67, .715))],
    "apical_3c": [((.83, .755), (.80, .83))],
}


def chambers(view_id, mk, shape):
    """
    Chambers by marker-based watershed: one marker per chamber label, flooding the lumen inside
    the heart outline (myocardium closed and hole-filled), split where neighbouring chambers meet
    (open valves) – never outside the heart.
    """
    H, W = shape
    struct = ((mk["wall"] | mk["valve"] | mk["vessel"]) > 0).astype(np.uint8)
    for (x0, y0), (x1, y1) in BARRIERS.get(view_id, []):
        cv2.line(struct, (int(x0 * W), int(y0 * H)), (int(x1 * W), int(y1 * H)), 1, 7)
    seeds = []
    for entry in LABELS[view_id]:
        name, x, y = entry[:3]
        kind = entry[3] if len(entry) > 3 else "chamber"
        if kind == "chamber" and (name in LEFT or name in RIGHT):
            seeds.append((name, SEEDS.get((view_id, name), [(x, y)])))
    # heart outline: the smallest closing of the myocardium whose filled shape holds every chamber
    # label; the drawing's convex hull if the outline stays open
    heart = None
    for frac in (0.05, 0.08, 0.11, 0.14):
        k = max(15, int(frac * max(H, W)) | 1)
        closed = cv2.morphologyEx(struct, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        filled = fill_holes(closed)
        if all(filled[min(H - 1, int(sy * H)), min(W - 1, int(sx * W))] for _, pts in seeds for sx, sy in pts):
            heart = filled
            break
    if heart is None:
        pts = cv2.findNonZero(struct)
        heart = np.zeros((H, W), np.uint8)
        cv2.fillConvexPoly(heart, cv2.convexHull(pts), 1)
    barrier = cv2.dilate(struct, np.ones((5, 5), np.uint8))
    free = ((mk["sector"] > 0) & (heart > 0) & (barrier == 0)).astype(np.uint8)
    markers = np.zeros((H, W), np.int32)
    markers[free == 0] = 1                                   # walls / outside: background marker
    ys, xs = np.nonzero(free)
    for i, (name, pts) in enumerate(seeds):
        if len(xs) == 0:
            break
        for sx, sy in pts:
            j = np.argmin((xs - sx * W) ** 2 + (ys - sy * H) ** 2)
            cv2.circle(markers, (int(xs[j]), int(ys[j])), 4, i + 2, -1)
    markers[(free == 0)] = 1
    relief = cv2.cvtColor((barrier * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
    cv2.watershed(relief, markers)
    regions = []
    for i, (name, _) in enumerate(seeds):
        region = ((markers == i + 2) & (free > 0)).astype(np.uint8)
        region = cv2.morphologyEx(region, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
        # grow back under the barrier margin, but never into the myocardium or out of the heart
        region = cv2.dilate(region, np.ones((5, 5), np.uint8))
        region[(mk["wall"] > 0) | (heart == 0) | (mk["sector"] == 0)] = 0
        regions.append((name, "left" if name in LEFT else "right", region))
    return regions


def fill_holes(m):
    h, w = m.shape
    flood = m.copy().astype(np.uint8)
    mask = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(flood, mask, (0, 0), 1)
    return (m | (1 - flood)).astype(np.uint8)


# ---------------------------------------------------------------------------------------------
# vectorisation
# ---------------------------------------------------------------------------------------------
def smooth_path(mask, eps=1.6, min_area=60):
    """Contours (with holes) → closed cubic Bézier sub-paths (Catmull-Rom spline)."""
    m = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), 1.2)
    m = (m > 0.5).astype(np.uint8)
    contours, _ = cv2.findContours(m, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    parts = []
    for c in contours:
        if abs(cv2.contourArea(c)) < min_area:
            continue
        p = cv2.approxPolyDP(c, eps, True)[:, 0, :].astype(float)
        if len(p) < 3:
            continue
        parts.append(catmull_rom(p))
    return " ".join(parts)


def catmull_rom(p):
    n = len(p)
    d = [f"M{p[0][0]:.1f} {p[0][1]:.1f}"]
    for i in range(n):
        p0, p1, p2, p3 = p[(i - 1) % n], p[i], p[(i + 1) % n], p[(i + 2) % n]
        c1 = p1 + (p2 - p0) / 6.0
        c2 = p2 - (p3 - p1) / 6.0
        d.append(f"C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p2[0]:.1f} {p2[1]:.1f}")
    d.append("Z")
    return " ".join(d)


def svg(view_id, im, mk, regions, theme):
    W, H = im.size
    t = THEMES[theme]
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">',
           "<defs>",
           f'<linearGradient id="cone" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{t["cone_top"]}"/>'
           f'<stop offset="1" stop-color="{t["cone_bottom"]}"/></linearGradient>',
           f'<linearGradient id="probe" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{t["probe"]}"/>'
           f'<stop offset="0.5" stop-color="#E2E8F0"/><stop offset="1" stop-color="{t["probe"]}"/></linearGradient>',
           "</defs>",
           f'<rect width="{W}" height="{H}" fill="{t["page"]}"/>']
    sector = smooth_path(mk["sector"], eps=2.5)
    out.append(f'<path d="{sector}" fill="url(#cone)" stroke="{t["cone_edge"]}" stroke-opacity="0.55" stroke-width="3"/>')
    for name, side, region in regions:
        d = smooth_path(region, eps=2.0, min_area=200)
        if d:
            fill, stroke = (t["left_fill"], t["left_stroke"]) if side == "left" else (t["right_fill"], t["right_stroke"])
            out.append(f'<path d="{d}" fill="{fill}" fill-opacity="0.18" stroke="{stroke}" stroke-width="3" '
                       f'stroke-linejoin="round" fill-rule="evenodd"/>')
    wall = smooth_path(mk["wall"], eps=1.5)
    out.append(f'<path d="{wall}" fill="{t["myo"]}" fill-opacity="{t["myo_op"]}" stroke="{t["myo_rim"]}" '
               f'stroke-opacity="0.8" stroke-width="2" stroke-linejoin="round" fill-rule="evenodd"/>')
    vessel = smooth_path(mk["vessel"], eps=1.5)
    if vessel:
        out.append(f'<path d="{vessel}" fill="{t["vessel"]}" fill-opacity="0.8" stroke="{t["vessel"]}" stroke-width="1.5" fill-rule="evenodd"/>')
    valve = smooth_path(mk["valve"], eps=1.2, min_area=25)
    if valve:
        out.append(f'<path d="{valve}" fill="{t["valve"]}" stroke="{t["valve"]}" stroke-width="1.5" '
                   f'stroke-linejoin="round" fill-rule="evenodd"/>')
    probe = smooth_path(mk["probe"], eps=2.0)
    if probe:
        out.append(f'<path d="{probe}" fill="url(#probe)" stroke="{t["probe"]}" stroke-width="3"/>')
    out.append("</svg>")
    return "\n".join(out)


def main(only=None):
    os.makedirs(OUT, exist_ok=True)
    labels = {}
    for view_id, key in hz.DRAWINGS.items():
        if only and view_id != only:
            continue
        src = hz.source_image(key)
        scale = max(1, round(hz.TARGET_WIDTH / src.width))
        im = src.resize((src.width * scale, src.height * scale), Image.LANCZOS) if scale > 1 else src
        mk = masks(im)
        regions = chambers(view_id, mk, (im.height, im.width))
        for theme in THEMES:
            with open(os.path.join(OUT, f"{view_id}_{theme}.svg"), "w") as f:
                f.write(svg(view_id, im, mk, regions, theme))
        labels[view_id] = [{"text": e[0], "x": e[1], "y": e[2],
                            "kind": e[3] if len(e) > 3 else ("left" if e[0] in LEFT else "right" if e[0] in RIGHT else "other")}
                           for e in LABELS[view_id]]
        print(view_id, im.size, "chambers:", ", ".join(n for n, _, _ in regions))
    old = json.load(open(LABELS_JSON)) if os.path.exists(LABELS_JSON) else {}
    old.update(labels)
    with open(LABELS_JSON, "w") as f:
        json.dump(old, f, indent=1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
