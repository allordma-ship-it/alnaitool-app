#!/usr/bin/env python3
"""
EchoScope – clean vector "Schema" diagrams (assets/schema/<view>.svg).

Each schema is minimalist line-art traced from the frozen end-diastolic frame of the view's
bundled cine loop (real clips) or taken from the simulator geometry (psax_av, apical_5c), in
the SAME normalised coordinate space as the video frame. The caliper overlays in
echo_protocol_full.json ("caliperOverlay.video") therefore land on the same structures in the
loop and in the schema. Each SVG is cropped ("schemaCrop" = [x0, y0, x1, y1] of the video frame)
to the anatomy and calipers so the heart is large in the small stage panel; the app maps video
coordinates into the crop. Tracing was checked with scripts/verify_calipers.py.

Usage: python3 scripts/gen_schema_svg.py
"""

from __future__ import annotations

import json
import math
import os
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "schema")
DB = os.path.join(ASSETS, "echo_protocol_full.json")

# Anatomical B-mode style (reference: subcostal_4ch_ohne_labels.svg spec): acoustic black sector,
# speckled warm-taupe myocardium with crisp endocardial borders, ultra-dark navy right-heart /
# venous lumens, ultra-dark burgundy left-heart / arterial lumens, tapered ivory valves with
# chordae to brown papillary muscles, ivory pericardium, olive-tan speckled liver with dark
# hepatic veins; emerald-teal sector walls, light-slate probe bar with a glowing blue index dot.
BG = "#0F1419"
CAVITY = "#0A0E12"
SECTOR = "#12A08C"     # reference sector frame (emerald-teal)
RIGHT_LUMEN = "#0B192C"
LEFT_LUMEN = "#1F0D12"
MYO_BASE, MYO_LIGHT, MYO_DARK = "#8D7368", "#A1887F", "#6F5A51"
ENDO = "#D7CCC8"
PERI = "#E2E8F0"
VALVE = "#E6DFD5"
CHORD = "#9E9E9E"
PM_BASE, PM_LIGHT = "#795548", "#8D6E63"
LIVER_BASE, LIVER_LIGHT, LIVER_DARK = "#786B52", "#8E8064", "#5F5440"
HEPATIC_VEIN = "#111820"
PROBE = "#CDD5DE"
INDEX_DOT, INDEX_GLOW = "#38B6F5", "#29B6F6"
OUTLINE = "#F4EEE2"    # epicardial / pericardial ivory outline
BG_NEAR, BG_FAR = "#16232A", "#0A1217"
MYO_TOP, MYO_BOTTOM = "#C9A48F", "#A47F6E"
LIVER_TOP, LIVER_BOTTOM = "#8A7A5C", "#6B5F49"
BLOOD_R = ("#0B1622", "#10253A")
BLOOD_L = ("#1D0F12", "#2C1418")
# Views drawn as one organic heart body (myocardium) with the chambers carved out of it.
HEART_BODY = {"subcostal_4c", "plax_standard", "psax_av", "psax_papillary", "apical_4c", "apical_5c",
              "apical_2c", "apical_3c"}
LUMEN = {"L": LEFT_LUMEN, "A": LEFT_LUMEN, "R": RIGHT_LUMEN, "RA": RIGHT_LUMEN}
# Kept for the view specs (wall lines); all walls are drawn as speckled myocardium now.
ARTERY_WALL = VEIN_WALL = MYO_BASE
LABEL = "#F1F5F9"
PILL = "#0F1419"


def speckle_pattern(pid: str, base: str | None, light: str, dark: str, size: int = 14) -> str:
    """Fine acoustic speckle: an irregular scatter of tiny, semi-transparent light and dark grains
    (the reference uses feTurbulence filters, which Android's SVG renderer ignores)."""
    dots = []
    seed = 7
    for k in range(size * 2):
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        x = seed % (size * 100) / 100
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        y = seed % (size * 100) / 100
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        r = 0.35 + (seed % 100) / 160
        op = 0.25 + (seed % 37) / 80
        dots.append(f'<ellipse cx="{x:.2f}" cy="{y:.2f}" rx="{r * 1.6:.2f}" ry="{r:.2f}" '
                    f'fill="{light if k % 2 == 0 else dark}" fill-opacity="{op:.2f}"/>')
    return (f'<pattern id="{pid}" width="{size}" height="{size}" patternUnits="userSpaceOnUse">'
            + (f'<rect width="{size}" height="{size}" fill="{base}"/>' if base else "") + "".join(dots) + "</pattern>")


def wall_stroke(d: str, width: float) -> str:
    """Speckle-coloured wall line with crisp endocardial borders on both sides."""
    return (f'<path d="{d}" fill="none" stroke="{ENDO}" stroke-width="{width + 3:.1f}" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<path d="{d}" fill="none" stroke="{MYO_BASE}" stroke-width="{width:.1f}" stroke-linecap="round" stroke-linejoin="round"/>')


def taper_path(pts: list[tuple[float, float]], w0: float, w1: float) -> str:
    """Leaflet: polygon along the points, width tapering from w0 (hinge) to w1 (tip)."""
    n = len(pts)
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        a = pts[max(0, i - 1)]
        b = pts[min(n - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length, dx / length
        w = (w0 + (w1 - w0) * i / max(1, n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    poly = left + right[::-1]
    return "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in poly) + " Z"


# ---------------------------------------------------------------------------------------------
# Geometry helpers (normalised 0–1 coordinates → SVG pixels)
# ---------------------------------------------------------------------------------------------

class Canvas:
    def __init__(self, width: int, height: int):
        self.w, self.h = width, height

    def p(self, pt):
        return pt[0] * self.w, pt[1] * self.h

    def smooth(self, pts, closed: bool) -> str:
        """Catmull-Rom spline through the points as cubic Béziers."""
        P = [self.p(q) for q in pts]
        n = len(P)
        if n < 3:
            return "M " + " L ".join(f"{x:.1f} {y:.1f}" for x, y in P) + (" Z" if closed else "")
        d = f"M {P[0][0]:.1f} {P[0][1]:.1f}"
        rng = range(n) if closed else range(n - 1)
        for i in rng:
            p0 = P[(i - 1) % n] if closed or i > 0 else P[i]
            p1, p2 = P[i], P[(i + 1) % n]
            p3 = P[(i + 2) % n] if closed or i + 2 < n else p2
            c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
            d += f" C {c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p2[0]:.1f} {p2[1]:.1f}"
        return d + (" Z" if closed else "")


def sector_path(c: Canvas, apex, half_deg: float, radius_y: float) -> str:
    """Ultrasound sector with apex (normalised) and radius given in normalised height units."""
    ax, ay = c.p(apex)
    r = radius_y * c.h
    a = math.radians(half_deg)
    lx, ly = ax - r * math.sin(a), ay + r * math.cos(a)
    rx, ry = ax + r * math.sin(a), ay + r * math.cos(a)
    return f"M {ax:.1f} {ay:.1f} L {lx:.1f} {ly:.1f} A {r:.1f} {r:.1f} 0 0 0 {rx:.1f} {ry:.1f} Z"


# ---------------------------------------------------------------------------------------------
# Views. Shapes: ("liver", pts) ("hv", pts, width) ("cav", pts, kind) ("myo", outer, inner)
#   ("band", top, bottom) ("trab", pts) ("pm", c, r) ("chord", from, [to…]) ("valve", pts)
#   ("peri", pts) ("line", pts, colour, width) ("vessel" = venous / PA tube, pts, width)
#   ("artery", pts, width) ("ellipse", c, r, kind, wall)
#   ("label", text, (x, y)[, size])
# ---------------------------------------------------------------------------------------------

VIEWS = {
    # Parasternal long axis, traced from the ED frame (R-wave) of loops/plax_standard.mp4.
    "plax_standard": dict(
        sector=((0.50, 0.04), 39, 0.75),
        shapes=[
            # LV inflow between the mitral leaflets (part of the LV cavity).
            ("cav", [(0.50, 0.478), (0.585, 0.47), (0.60, 0.57), (0.475, 0.545)], "L"),
            ("cav", [(0.36, 0.20), (0.42, 0.105), (0.58, 0.105), (0.64, 0.24), (0.55, 0.258),
                     (0.47, 0.243), (0.40, 0.226)], "R"),
            ("band", [(0.40, 0.078), (0.50, 0.068), (0.60, 0.078)], [(0.60, 0.105), (0.50, 0.095), (0.40, 0.105)], "R"),
            ("cav", [(0.10, 0.24), (0.34, 0.258), (0.40, 0.272), (0.47, 0.291), (0.55, 0.315), (0.60, 0.31),
                     (0.645, 0.30), (0.70, 0.31), (0.72, 0.39), (0.654, 0.412), (0.60, 0.432), (0.57, 0.462),
                     (0.50, 0.478), (0.475, 0.545), (0.436, 0.553), (0.38, 0.535), (0.30, 0.50), (0.22, 0.47),
                     (0.10, 0.42)], "L"),
            ("cav", [(0.585, 0.47), (0.66, 0.44), (0.75, 0.42), (0.84, 0.40), (0.84, 0.55), (0.74, 0.565),
                     (0.66, 0.585), (0.60, 0.57)], "A"),
            # Septum continuing into the anterior aortic wall.
            ("band", [(0.10, 0.20), (0.34, 0.215), (0.40, 0.226), (0.47, 0.243), (0.55, 0.255), (0.60, 0.256),
                      (0.626, 0.255), (0.66, 0.24), (0.70, 0.22)],
                     [(0.70, 0.27), (0.66, 0.29), (0.645, 0.30), (0.60, 0.31), (0.55, 0.315), (0.47, 0.291),
                      (0.40, 0.272), (0.34, 0.258), (0.10, 0.245)]),
            # Posterior aortic wall continuing into the anterior mitral leaflet.
            ("band", [(0.57, 0.452), (0.60, 0.432), (0.654, 0.412), (0.72, 0.395), (0.84, 0.375)],
                     [(0.84, 0.40), (0.73, 0.423), (0.68, 0.44), (0.62, 0.455), (0.585, 0.47)]),
            ("valve", [(0.585, 0.462), (0.54, 0.468), (0.50, 0.478)]),
            ("valve", [(0.60, 0.565), (0.54, 0.548), (0.475, 0.545)]),
            ("valve", [(0.648, 0.305), (0.662, 0.37)]),
            ("valve", [(0.672, 0.405), (0.662, 0.37)]),
            # Posterior LV wall and posterior LA wall.
            ("band", [(0.10, 0.42), (0.22, 0.47), (0.30, 0.50), (0.38, 0.535), (0.436, 0.553), (0.50, 0.56),
                      (0.56, 0.565), (0.60, 0.57), (0.66, 0.585), (0.74, 0.565), (0.86, 0.55)],
                     [(0.86, 0.59), (0.74, 0.605), (0.66, 0.615), (0.58, 0.61), (0.50, 0.602), (0.43, 0.595),
                      (0.38, 0.583), (0.30, 0.55), (0.22, 0.515), (0.10, 0.465)]),
            ("peri", [(0.10, 0.48), (0.22, 0.53), (0.30, 0.565), (0.43, 0.61), (0.58, 0.625), (0.70, 0.63),
                      (0.86, 0.61)]),
            ("label", "RV", (0.52, 0.18)),
            ("label", "IVS", (0.36, 0.247), 12),
            ("label", "LV", (0.30, 0.40)),
            ("label", "Ao", (0.69, 0.355), 13),
            ("label", "LA", (0.75, 0.50)),
            ("label", "MV", (0.545, 0.515), 12),
            ("label", "PW", (0.30, 0.533), 12),
            # Descending aorta in cross-section behind the LA; posteromedial papillary muscle + chordae.
            ("ellipse", (0.705, 0.668), (0.03, 0.032), "A", "wall"),
            ("pm", (0.33, 0.492), (0.038, 0.02)),
            ("chord", (0.355, 0.49), [(0.475, 0.545), (0.50, 0.478), (0.44, 0.548)]),
        ],
    ),
    # Parasternal short axis, papillary level; ED frame of loops/psax_papillary.mp4.
    "psax_papillary": dict(
        sector=((0.50, 0.04), 40, 0.76),
        shapes=[
            ("cav", [(0.36, 0.30), (0.32, 0.36), (0.28, 0.46), (0.30, 0.56), (0.35, 0.60), (0.375, 0.52),
                     (0.37, 0.42), (0.385, 0.34)], "R"),
            ("ellipse", (0.515, 0.45), (0.165, 0.175), "myo", None),
            ("ellipse", (0.512, 0.46), (0.107, 0.105), "L", None),
            # Papillary muscles where they are on the real frame (≈ 3–4 and 4–5 o'clock).
            ("pm", (0.537, 0.47), (0.035, 0.03)),
            ("pm", (0.553, 0.527), (0.03, 0.028)),
            ("peri", [(0.24, 0.60), (0.36, 0.645), (0.50, 0.665), (0.64, 0.645), (0.76, 0.60)]),
            ("label", "RV", (0.315, 0.47)),
            ("label", "LV", (0.425, 0.53)),
            ("label", "IVS", (0.385, 0.29), 12),
            ("label", "PM", (0.60, 0.47), 11),
            ("label", "PM", (0.60, 0.545), 11),
            ("label", "ANT", (0.515, 0.315), 11),
            ("label", "INF", (0.515, 0.598), 11),
        ],
    ),
    # Apical 4-chamber; ED frame of loops/apical_4c.mp4.
    "apical_4c": dict(
        sector=((0.50, 0.05), 39, 0.75),
        shapes=[
            ("cav", [(0.43, 0.20), (0.36, 0.30), (0.31, 0.40), (0.29, 0.50), (0.30, 0.565), (0.42, 0.575),
                     (0.40, 0.52), (0.395, 0.40), (0.42, 0.25), (0.45, 0.17)], "R"),
            ("cav", [(0.48, 0.15), (0.50, 0.13), (0.52, 0.13), (0.60, 0.25), (0.645, 0.40), (0.65, 0.50),
                     (0.645, 0.585), (0.55, 0.59), (0.455, 0.585), (0.435, 0.52), (0.43, 0.40), (0.45, 0.25)], "L"),
            ("cav", [(0.30, 0.57), (0.42, 0.58), (0.45, 0.62), (0.44, 0.68), (0.37, 0.71), (0.31, 0.67),
                     (0.285, 0.61)], "RA"),
            ("cav", [(0.47, 0.60), (0.645, 0.59), (0.635, 0.66), (0.60, 0.74), (0.53, 0.77), (0.48, 0.72),
                     (0.465, 0.65)], "A"),
            ("myo", [(0.42, 0.58), (0.40, 0.52), (0.395, 0.40), (0.42, 0.25), (0.46, 0.14), (0.50, 0.10),
                     (0.54, 0.11), (0.64, 0.24), (0.69, 0.40), (0.70, 0.50), (0.69, 0.59)],
                    [(0.645, 0.585), (0.65, 0.50), (0.645, 0.40), (0.60, 0.25), (0.52, 0.13), (0.50, 0.13),
                     (0.48, 0.15), (0.45, 0.25), (0.43, 0.40), (0.435, 0.52), (0.455, 0.585)]),
            ("band", [(0.42, 0.18), (0.34, 0.29), (0.28, 0.40), (0.255, 0.50), (0.26, 0.57)],
                     [(0.30, 0.565), (0.29, 0.50), (0.31, 0.40), (0.36, 0.30), (0.43, 0.20)], "R"),
            ("band", [(0.45, 0.60), (0.46, 0.68), (0.47, 0.76)], [(0.485, 0.76), (0.475, 0.68), (0.465, 0.60)]),
            ("valve", [(0.305, 0.565), (0.33, 0.53)]),
            ("valve", [(0.42, 0.578), (0.395, 0.545)]),
            ("valve", [(0.455, 0.592), (0.50, 0.56)]),
            ("valve", [(0.645, 0.585), (0.60, 0.555)]),
            ("label", "LV", (0.56, 0.33)),
            ("label", "RV", (0.345, 0.43)),
            ("label", "RA", (0.365, 0.645)),
            ("label", "LA", (0.585, 0.695)),
            ("label", "TV", (0.36, 0.53), 11),
            ("label", "MV", (0.62, 0.535), 11),
            ("label", "IAS", (0.425, 0.735), 11),
            # RV moderator band; anterolateral papillary muscle with chordae to both mitral leaflets.
            ("trab", [(0.305, 0.44), (0.395, 0.465)]),
            ("pm", (0.625, 0.43), (0.022, 0.045)),
            ("chord", (0.622, 0.47), [(0.60, 0.555), (0.50, 0.56), (0.62, 0.567)]),
        ],
    ),
    # Apical 2-chamber; ED frame of loops/apical_2c.mp4.
    "apical_2c": dict(
        sector=((0.50, 0.05), 39, 0.74),
        shapes=[
            ("cav", [(0.48, 0.13), (0.50, 0.125), (0.52, 0.13), (0.58, 0.25), (0.62, 0.40), (0.63, 0.50),
                     (0.62, 0.565), (0.53, 0.59), (0.455, 0.60), (0.44, 0.50), (0.43, 0.40), (0.45, 0.25)], "L"),
            ("cav", [(0.455, 0.605), (0.62, 0.57), (0.645, 0.65), (0.60, 0.73), (0.52, 0.75), (0.465, 0.70)], "A"),
            ("myo", [(0.41, 0.62), (0.37, 0.52), (0.355, 0.40), (0.40, 0.22), (0.46, 0.10), (0.50, 0.09),
                     (0.55, 0.11), (0.63, 0.24), (0.67, 0.40), (0.68, 0.50), (0.67, 0.575)],
                    [(0.62, 0.565), (0.63, 0.50), (0.62, 0.40), (0.58, 0.25), (0.52, 0.13), (0.50, 0.125),
                     (0.48, 0.13), (0.45, 0.25), (0.43, 0.40), (0.44, 0.50), (0.455, 0.60)]),
            ("valve", [(0.455, 0.60), (0.50, 0.565)]),
            ("valve", [(0.62, 0.567), (0.58, 0.545)]),
            ("label", "LV", (0.555, 0.36)),
            ("label", "LA", (0.575, 0.68)),
            ("label", "MV", (0.66, 0.535), 11),
            ("label", "INF", (0.355, 0.31), 11),
            ("label", "ANT", (0.665, 0.30), 11),
            # Papillary muscles on the inferior (left) and anterior (right) walls with chordae.
            ("pm", (0.455, 0.42), (0.02, 0.042)),
            ("pm", (0.60, 0.42), (0.02, 0.042)),
            ("chord", (0.457, 0.46), [(0.50, 0.565), (0.48, 0.58)]),
            ("chord", (0.598, 0.46), [(0.58, 0.545), (0.60, 0.56)]),
        ],
    ),
    # Apical 3-chamber (apical long axis); ED frame of loops/apical_3c.mp4. Inferolateral wall
    # left, anteroseptum right, LA bottom left, LVOT / aortic valve / aorta bottom right.
    "apical_3c": dict(
        sector=((0.48, 0.03), 38, 0.75),
        shapes=[
            ("cav", [(0.475, 0.12), (0.50, 0.11), (0.53, 0.14), (0.56, 0.25), (0.585, 0.38), (0.59, 0.50),
                     (0.56, 0.565), (0.50, 0.52), (0.45, 0.50), (0.43, 0.42), (0.43, 0.32), (0.45, 0.20)], "L"),
            ("cav", [(0.44, 0.585), (0.54, 0.59), (0.55, 0.70), (0.52, 0.77), (0.46, 0.76), (0.43, 0.68)], "A"),
            ("cav", [(0.57, 0.585), (0.635, 0.56), (0.655, 0.66), (0.66, 0.77), (0.60, 0.78), (0.575, 0.68)], "L"),
            ("myo", [(0.40, 0.56), (0.37, 0.45), (0.38, 0.32), (0.42, 0.18), (0.47, 0.08), (0.51, 0.07), (0.56, 0.11),
                     (0.61, 0.24), (0.645, 0.37), (0.665, 0.52)],
                    [(0.62, 0.52), (0.60, 0.40), (0.575, 0.27), (0.535, 0.14), (0.50, 0.11), (0.475, 0.12), (0.45, 0.20),
                     (0.43, 0.32), (0.43, 0.42), (0.44, 0.53)]),
            ("band", [(0.545, 0.56), (0.555, 0.67), (0.565, 0.78)], [(0.578, 0.78), (0.568, 0.67), (0.562, 0.565)]),
            ("line", [(0.635, 0.53), (0.65, 0.65), (0.665, 0.78)], ARTERY_WALL, 4),
            ("valve", [(0.555, 0.565), (0.52, 0.535), (0.50, 0.52)]),
            ("valve", [(0.44, 0.555), (0.465, 0.53)]),
            ("valve", [(0.57, 0.59), (0.60, 0.612)]),
            ("valve", [(0.635, 0.585), (0.605, 0.612)]),
            ("pm", (0.447, 0.42), (0.018, 0.042)),
            ("chord", (0.449, 0.46), [(0.465, 0.53), (0.50, 0.52)]),
        ],
    ),
    # Apical 5-chamber; systolic frame (400 ms) of the real POCUS Atlas loop loops/apical_5c.mp4.
    # RV / RA left, LV / LA right, aortic root ("fifth chamber") between the atria.
    "apical_5c": dict(
        sector=((0.52, 0.05), 45, 0.90),
        shapes=[
            ("cav", [(0.43, 0.22), (0.36, 0.32), (0.30, 0.45), (0.27, 0.56), (0.33, 0.585), (0.42, 0.575),
                     (0.46, 0.45), (0.47, 0.30)], "R"),
            ("cav", [(0.50, 0.22), (0.58, 0.30), (0.66, 0.42), (0.72, 0.53), (0.66, 0.585), (0.60, 0.585),
                     (0.53, 0.56), (0.50, 0.45), (0.49, 0.32)], "L"),
            ("ellipse", (0.36, 0.705), (0.095, 0.075), "RA", "wall"),
            ("ellipse", (0.735, 0.69), (0.08, 0.075), "A", "wall"),
            ("band", [(0.42, 0.18), (0.32, 0.28), (0.24, 0.42), (0.20, 0.57)],
                     [(0.25, 0.585), (0.29, 0.44), (0.36, 0.31), (0.44, 0.22)], "R"),
            ("band", [(0.47, 0.22), (0.475, 0.40), (0.485, 0.56)], [(0.51, 0.56), (0.50, 0.40), (0.495, 0.22)]),
            ("band", [(0.52, 0.18), (0.64, 0.27), (0.74, 0.40), (0.80, 0.55)],
                     [(0.75, 0.57), (0.70, 0.43), (0.61, 0.31), (0.51, 0.22)]),
            ("ellipse", (0.585, 0.635), (0.045, 0.045), "L", "wall"),
            ("valve", [(0.30, 0.60), (0.35, 0.575)]),
            ("valve", [(0.44, 0.60), (0.405, 0.575)]),
            ("valve", [(0.64, 0.60), (0.675, 0.575)]),
            ("valve", [(0.78, 0.60), (0.745, 0.575)]),
            ("valve", [(0.555, 0.635), (0.585, 0.612)]),
            ("valve", [(0.615, 0.635), (0.585, 0.612)]),
            ("trab", [(0.29, 0.45), (0.45, 0.47)]),
            ("pm", (0.665, 0.46), (0.02, 0.04)),
            ("chord", (0.663, 0.50), [(0.675, 0.575), (0.645, 0.598)]),
        ],
    ),
    # PSAX at aortic-valve level; closed-valve frame (400 ms) of the real POCUS Atlas loop.
    # Aortic valve centre, RVOT arching over it from the tricuspid valve (left) to the pulmonary
    # valve and main PA (right); RA left, LA below.
    "psax_av": dict(
        sector=((0.53, 0.12), 45, 0.70),
        shapes=[
            ("cav", [(0.36, 0.48), (0.42, 0.40), (0.50, 0.36), (0.60, 0.36), (0.68, 0.41), (0.70, 0.46),
                     (0.64, 0.46), (0.58, 0.44), (0.52, 0.44), (0.46, 0.46), (0.42, 0.50)], "R"),
            ("band", [(0.33, 0.47), (0.40, 0.37), (0.50, 0.32), (0.60, 0.32), (0.70, 0.38), (0.74, 0.45)],
                     [(0.70, 0.46), (0.68, 0.41), (0.60, 0.36), (0.50, 0.36), (0.42, 0.40), (0.36, 0.48)], "R"),
            ("vessel", [(0.68, 0.46), (0.72, 0.53), (0.74, 0.62)], 30, True),
            ("ellipse", (0.40, 0.585), (0.065, 0.06), "RA", "wall"),
            ("ellipse", (0.575, 0.66), (0.09, 0.05), "A", "wall"),
            ("ellipse", (0.57, 0.52), (0.06, 0.065), "L", "wall"),
            ("valve", [(0.57, 0.52), (0.57, 0.457)]),
            ("valve", [(0.57, 0.52), (0.62, 0.553)]),
            ("valve", [(0.57, 0.52), (0.52, 0.553)]),
            ("valve", [(0.40, 0.49), (0.44, 0.465)]),
            ("valve", [(0.665, 0.455), (0.70, 0.47)]),
            # Interatrial septum between RA and LA.
            ("band", [(0.455, 0.605), (0.49, 0.632)], [(0.50, 0.622), (0.465, 0.592)]),
        ],
    ),
    # Subcostal 4-chamber; ED frame of loops/subcostal_4c.mp4.
    "subcostal_4c": dict(
        sector=((0.50, 0.03), 37, 0.75),
        shapes=[
            ("liver", [(0.0, 0.0), (1.0, 0.0), (1.0, 0.285), (0.55, 0.296), (0.40, 0.308), (0.0, 0.33)]),
            ("cav", [(0.38, 0.335), (0.54, 0.33), (0.59, 0.352), (0.55, 0.39), (0.45, 0.405), (0.40, 0.40)], "R"),
            ("cav", [(0.34, 0.43), (0.40, 0.41), (0.43, 0.45), (0.43, 0.515), (0.38, 0.53), (0.34, 0.51)], "RA"),
            ("cav", [(0.36, 0.57), (0.45, 0.55), (0.49, 0.575), (0.47, 0.63), (0.40, 0.645), (0.355, 0.615)], "A"),
            ("cav", [(0.47, 0.47), (0.56, 0.42), (0.62, 0.40), (0.66, 0.45), (0.66, 0.55), (0.60, 0.62),
                     (0.52, 0.61), (0.48, 0.56)], "L"),
            ("band", [(0.20, 0.335), (0.30, 0.322), (0.40, 0.314), (0.47, 0.30), (0.55, 0.302), (0.70, 0.29)],
                     [(0.70, 0.322), (0.55, 0.33), (0.47, 0.333), (0.40, 0.336), (0.30, 0.345), (0.20, 0.36)], "R"),
            ("band", [(0.415, 0.50), (0.47, 0.42), (0.53, 0.38), (0.59, 0.352)],
                     [(0.625, 0.375), (0.56, 0.415), (0.50, 0.455), (0.45, 0.525)]),
            ("band", [(0.33, 0.535), (0.44, 0.52)], [(0.45, 0.548), (0.34, 0.562)]),
            ("band", [(0.64, 0.36), (0.71, 0.45), (0.70, 0.58), (0.62, 0.665), (0.50, 0.665)],
                     [(0.50, 0.635), (0.60, 0.625), (0.665, 0.555), (0.665, 0.45), (0.62, 0.40)]),
            ("peri", [(0.20, 0.33), (0.30, 0.318), (0.40, 0.31), (0.47, 0.298), (0.55, 0.298), (0.70, 0.286)]),
            ("valve", [(0.40, 0.41), (0.43, 0.40)]),
            ("valve", [(0.47, 0.56), (0.50, 0.545)]),
            ("label", "Liver", (0.46, 0.20)),
            ("label", "RV", (0.53, 0.37), 13),
            ("label", "RA", (0.385, 0.475)),
            ("label", "LA", (0.42, 0.605)),
            ("label", "LV", (0.585, 0.51)),
            ("label", "IAS", (0.27, 0.55), 11),
            # Hepatic veins branching in the liver; LV papillary muscle; RV trabeculation.
            ("hv", [(0.62, 0.02), (0.585, 0.14), (0.55, 0.26)], 9),
            ("hv", [(0.585, 0.14), (0.67, 0.19)], 5),
            ("hv", [(0.40, 0.06), (0.45, 0.17), (0.49, 0.27)], 6),
            ("trab", [(0.46, 0.345), (0.50, 0.385)]),
            ("pm", (0.625, 0.515), (0.03, 0.02)),
            ("chord", (0.60, 0.515), [(0.50, 0.545), (0.47, 0.56)]),
        ],
    ),
    # Subcostal IVC long axis; first frame (end-expiration) of loops/subcostal_ivc.mp4.
    "subcostal_ivc": dict(
        sector=((0.50, 0.0), 40, 0.97),
        shapes=[
            ("liver", [(0.0, 0.0), (1.0, 0.0), (1.0, 0.56), (0.70, 0.575), (0.62, 0.583), (0.555, 0.583),
                       (0.50, 0.575), (0.40, 0.53), (0.30, 0.46), (0.22, 0.43), (0.0, 0.40)]),
            ("cav", [(0.0, 0.40), (0.22, 0.43), (0.30, 0.46), (0.40, 0.53), (0.50, 0.575), (0.555, 0.583),
                     (0.62, 0.585), (0.72, 0.57), (0.78, 0.62), (0.76, 0.74), (0.66, 0.76), (0.62, 0.68),
                     (0.536, 0.652), (0.45, 0.635), (0.38, 0.60), (0.28, 0.53), (0.0, 0.47)], "RA"),
            ("line", [(0.0, 0.40), (0.22, 0.43), (0.30, 0.46), (0.40, 0.53), (0.50, 0.575), (0.555, 0.583),
                      (0.62, 0.585), (0.72, 0.57)], VEIN_WALL, 3),
            ("line", [(0.0, 0.47), (0.28, 0.53), (0.38, 0.60), (0.45, 0.635), (0.536, 0.652), (0.62, 0.68)],
             VEIN_WALL, 3),
            ("peri", [(0.0, 0.52), (0.20, 0.58), (0.40, 0.67), (0.60, 0.70), (1.0, 0.74)]),
            ("label", "Liver", (0.48, 0.30)),
            ("label", "IVC", (0.36, 0.555), 13),
            ("label", "RA", (0.70, 0.67)),
            ("label", "Diaphragm", (0.36, 0.71), 11),
            # Hepatic vein joining the IVC just proximal to the RA junction, plus branches.
            ("hv", [(0.68, 0.28), (0.645, 0.44), (0.615, 0.58)], 12),
            ("hv", [(0.645, 0.44), (0.75, 0.40)], 6),
            ("hv", [(0.30, 0.18), (0.36, 0.32), (0.40, 0.40)], 5),
        ],
    ),
    # Suprasternal long axis of the arch; ED frame of loops/suprasternal_arch.mp4.
    "suprasternal_arch": dict(
        sector=((0.50, 0.04), 40, 0.75),
        shapes=[
            ("cav", [(0.40, 0.12), (0.58, 0.10), (0.61, 0.16), (0.40, 0.19)], "RA"),
            ("cav", [(0.25, 0.60), (0.26, 0.35), (0.33, 0.24), (0.44, 0.215), (0.55, 0.20), (0.60, 0.24),
                     (0.57, 0.33), (0.555, 0.45), (0.55, 0.60), (0.475, 0.60), (0.475, 0.40), (0.46, 0.35),
                     (0.40, 0.345), (0.35, 0.37), (0.335, 0.45), (0.33, 0.60)], "L"),
            ("line", [(0.25, 0.60), (0.26, 0.35), (0.33, 0.24), (0.44, 0.215), (0.55, 0.20), (0.60, 0.24),
                      (0.57, 0.33), (0.555, 0.45), (0.55, 0.60)], ARTERY_WALL, 5),
            ("line", [(0.33, 0.60), (0.335, 0.45), (0.35, 0.37), (0.40, 0.345), (0.46, 0.35), (0.475, 0.40),
                      (0.475, 0.60)], ARTERY_WALL, 5),
            ("line", [(0.40, 0.19), (0.61, 0.16)], VEIN_WALL, 3),
            ("label", "BCV", (0.50, 0.155), 11),
            ("label", "Arch", (0.52, 0.275)),
            ("label", "Asc Ao", (0.295, 0.50), 12),
            ("label", "Desc Ao", (0.515, 0.53), 12),
            # Right pulmonary artery in cross-section under the arch concavity.
            ("ellipse", (0.405, 0.43), (0.035, 0.034), "R", "wall"),
            # Brachiocephalic trunk, left common carotid and left subclavian artery from the arch top,
            # crossing the brachiocephalic vein (drawn last so they stay visible in the narrow near field).
            ("artery", [(0.44, 0.222), (0.443, 0.16), (0.447, 0.10)], 13),
            ("artery", [(0.483, 0.214), (0.485, 0.15), (0.487, 0.09)], 11),
            ("artery", [(0.522, 0.207), (0.527, 0.15), (0.532, 0.10)], 11),
        ],
    ),
}


def simulated_views() -> dict:
    """All views now have real loops; kept for scripts that still import it."""
    return {}


# ---------------------------------------------------------------------------------------------

def sector_decor(c: Canvas, apex, half_deg: float, radius_y: float) -> str:
    """Solid teal side walls, dashed curved base arc, light-slate probe bar and index dot (screen right)."""
    ax, ay = c.p(apex)
    r = radius_y * c.h
    a = math.radians(half_deg)
    lx, ly = ax - r * math.sin(a), ay + r * math.cos(a)
    rx, ry = ax + r * math.sin(a), ay + r * math.cos(a)
    return (f'<path d="M {lx:.1f} {ly:.1f} L {ax:.1f} {ay:.1f} L {rx:.1f} {ry:.1f}" fill="none" stroke="{SECTOR}" '
            f'stroke-width="2.6" stroke-linejoin="round"/>'
            f'<path d="M {lx:.1f} {ly:.1f} A {r:.1f} {r:.1f} 0 0 0 {rx:.1f} {ry:.1f}" fill="none" stroke="{SECTOR}" '
            f'stroke-width="2.6" stroke-dasharray="14 10"/>'
            f'<rect x="{ax - 34:.1f}" y="{ay - 15:.1f}" width="68" height="17" rx="8.5" fill="{PROBE}"/>'
            f'<circle cx="{ax + 46:.1f}" cy="{ay - 6.5:.1f}" r="7" fill="{INDEX_DOT}"/>')


def crop_box(spec_id: str, spec: dict, overlays: list[list[float]], width: int, height: int,
             pad: float = 0.03, labels: bool = True) -> list[float]:
    """Region around the anatomy and every caliper, so the small stage panel shows the heart large."""
    xs, ys = [], []
    for shape in spec["shapes"]:
        kind = shape[0]
        if kind in ("liver",):
            continue
        if kind in ("cav", "valve", "peri", "line", "vessel", "artery", "trab"):
            pts = shape[1]
        elif kind in ("myo", "band"):
            pts = shape[1] + shape[2]
        elif kind in ("ellipse", "pm"):
            (cx, cy), (rx, ry) = shape[1], shape[2]
            pts = [(cx - rx, cy - ry), (cx + rx, cy + ry)]
        else:
            pts = []
        xs += [p[0] for p in pts]
        ys += [p[1] for p in pts]
    for text, pos, size, target in LABELS.get(spec_id, []):
        w, h = pill_size(text, size)
        xs += [pos[0] - w / 2 / width, pos[0] + w / 2 / width]
        ys += [pos[1] - h / 2 / height, pos[1] + h / 2 / height]
        if target:
            xs.append(target[0])
            ys.append(target[1])
    (ax, ay), _, _ = spec["sector"]
    xs += [ax - 0.06, ax + 0.075]         # probe bar and index dot at the apex
    ys += [max(0.0, ay - 0.035)]
    for c in overlays:
        xs += [c[0], c[2]]
        ys += [c[1], c[3]]
    box = [max(0.0, min(xs) - pad), min(ys) - pad, min(1.0, max(xs) + pad), min(1.0, max(ys) + pad)]
    return [round(v, 3) for v in box]


def cardiac_points(spec: dict) -> list[tuple[float, float]]:
    pts = []
    for shape in spec["shapes"]:
        kind = shape[0]
        if kind in ("cav", "valve"):
            pts += shape[1]
        elif kind in ("myo", "band"):
            pts += shape[1] + shape[2]
        elif kind in ("ellipse", "pm"):
            (cx, cy), (rx, ry) = shape[1], shape[2]
            pts += [(cx + rx * math.cos(a / 8 * math.pi), cy + ry * math.sin(a / 8 * math.pi)) for a in range(16)]
        elif kind == "line" and shape[2] == ARTERY_WALL:
            pts += shape[1]
    return pts


def heart_body(c: Canvas, spec: dict, grow: float = 7.0) -> str:
    """Smooth organic outline around every cardiac structure (convex hull, pushed out by `grow` px)."""
    P = sorted(set(c.p(q) for q in cardiac_points(spec)))
    if len(P) < 3:
        return ""

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for q in P:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], q) <= 0:
            lower.pop()
        lower.append(q)
    for q in reversed(P):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], q) <= 0:
            upper.pop()
        upper.append(q)
    hull = lower[:-1] + upper[:-1]
    cx = sum(x for x, _ in hull) / len(hull)
    cy = sum(y for _, y in hull) / len(hull)
    grown = []
    for x, y in hull:
        d = math.hypot(x - cx, y - cy) or 1.0
        grown.append(((x + (x - cx) / d * grow) / c.w, (y + (y - cy) / d * grow) / c.h))
    # resample so the spline stays round between distant hull points
    dense = []
    for k in range(len(grown)):
        a, b = grown[k], grown[(k + 1) % len(grown)]
        n = max(1, int(math.hypot((b[0] - a[0]) * c.w, (b[1] - a[1]) * c.h) // 40))
        dense += [(a[0] + (b[0] - a[0]) * t / n, a[1] + (b[1] - a[1]) * t / n) for t in range(n)]
    return c.smooth(dense, True)


# RASTER = True: the SVG is only an intermediate for scripts/export_schema_png.py (Chromium), so the
# reference's real SVG filters are used – feTurbulence acoustic speckle and Gaussian glow – which
# Android's own SVG renderer would ignore.
RASTER = False
_clip_ids = [0]


def speckled(d: str, base_fill: str, pattern: str, light: float = 0.38, dark: float = 0.42) -> str:
    """Area fill + speckle: filter noise clipped to the shape (raster) or a speckle pattern (vector)."""
    if not RASTER:
        return f'<path d="{d}" fill="{base_fill}"/><path d="{d}" fill="url(#{pattern})"/>'
    _clip_ids[0] += 1
    cid = f"cl{_clip_ids[0]}"
    return (f'<clipPath id="{cid}"><path d="{d}"/></clipPath><path d="{d}" fill="{base_fill}"/>'
            f'<g clip-path="url(#{cid})"><rect x="-200" y="-200" width="1400" height="1200" filter="url(#spk)" opacity="{light}"/>'
            f'<rect x="-200" y="-200" width="1400" height="1200" filter="url(#spkD)" opacity="{dark}"/></g>')


def tissue(d: str, outline: bool = True) -> str:
    """Myocardium: warm-taupe gradient + speckle, ivory epicardial outline with a soft glow."""
    out = speckled(d, "url(#myoG)", "myoS")
    if outline:
        halo = (f'<path d="{d}" fill="none" stroke="{OUTLINE}" stroke-opacity="0.22" stroke-width="16" '
                f'stroke-linejoin="round"{" filter=\"url(#glow)\"" if RASTER else ""}/>')
        out = halo + out + f'<path d="{d}" fill="none" stroke="{OUTLINE}" stroke-width="3.2" stroke-linejoin="round"/>'
    return out


def render(view_id: str, spec: dict, width: int, height: int, crop: list[float] | None = None,
           labels: bool = False) -> str:
    c = Canvas(width, height)
    apex, half, radius = spec["sector"]
    sector = sector_path(c, apex, half, radius)
    ax, ay = c.p(apex)
    body = []
    in_body = view_id in HEART_BODY
    hull_drawn = False
    for shape in spec["shapes"]:
        kind = shape[0]
        if in_body and not hull_drawn and kind not in ("liver", "hv"):
            body.append(tissue(heart_body(c, spec)))
            hull_drawn = True
        if kind == "liver":
            d = c.smooth(shape[1], True)
            body.append(speckled(d, "url(#liverG)", "liverS", 0.30, 0.45))
        elif kind == "hv":
            pts, w = shape[1], shape[2]
            body.append(f'<path d="{c.smooth(pts, False)}" fill="none" stroke="{HEPATIC_VEIN}" stroke-width="{w * 1.6:.1f}" '
                        f'stroke-linecap="round" stroke-linejoin="round"/>')
        elif kind == "cav":
            grad = "bloodR" if shape[2] in ("R", "RA") else "bloodL"
            edge = "" if in_body else f' stroke="{OUTLINE}" stroke-width="2.5"'
            body.append(f'<path d="{c.smooth(shape[1], True)}" fill="url(#{grad})"{edge}/>')
        elif kind in ("myo", "band"):
            if kind == "myo":
                d = c.smooth(shape[1] + shape[2], True)
            else:
                d = c.smooth(shape[1], False) + " L " + c.smooth(shape[2], False)[2:] + " Z"
            body.append(tissue(d, outline=not in_body))
        elif kind == "trab":
            body.append(f'<path d="{c.smooth(shape[1], False)}" fill="none" stroke="{MYO_TOP}" stroke-width="6" stroke-linecap="round"/>')
        elif kind == "pm":
            (cx, cy), (rx, ry) = shape[1], shape[2]
            X, Y = c.p((cx, cy))
            body.append(f'<ellipse cx="{X:.1f}" cy="{Y:.1f}" rx="{rx * c.w:.1f}" ry="{ry * c.h:.1f}" fill="url(#myoG)"/>'
                        f'<ellipse cx="{X:.1f}" cy="{Y:.1f}" rx="{rx * c.w:.1f}" ry="{ry * c.h:.1f}" fill="url(#myoS)"/>')
        elif kind == "chord":
            (fx, fy) = c.p(shape[1])
            for t in shape[2]:
                tx, ty = c.p(t)
                body.append(f'<path d="M {fx:.1f} {fy:.1f} L {tx:.1f} {ty:.1f}" stroke="{OUTLINE}" stroke-opacity="0.55" '
                            f'stroke-width="1.4" fill="none"/>')
        elif kind == "valve":
            pts = [c.p(q) for q in shape[1]]
            if len(pts) == 2:
                (x0, y0), (x1, y1) = pts
                pts = [(x0 + (x1 - x0) * k / 6, y0 + (y1 - y0) * k / 6) for k in range(7)]
            body.append(f'<path d="{taper_path(pts, 6.0, 1.2)}" fill="#F7F1E6" stroke="#F7F1E6" stroke-width="1" stroke-linejoin="round"/>')
        elif kind == "peri":
            d = c.smooth(shape[1], False)
            body.append(f'<path d="{d}" fill="none" stroke="#F1E9D8" stroke-opacity="0.35" stroke-width="12" stroke-linecap="round"/>'
                        f'<path d="{d}" fill="none" stroke="{OUTLINE}" stroke-opacity="0.9" stroke-width="4" stroke-linecap="round"/>')
        elif kind == "line":
            d = c.smooth(shape[1], False)
            w = shape[3] + 5
            body.append(f'<path d="{d}" fill="none" stroke="{OUTLINE}" stroke-width="{w + 4:.1f}" stroke-linecap="round" stroke-linejoin="round"/>'
                        f'<path d="{d}" fill="none" stroke="{MYO_TOP}" stroke-width="{w:.1f}" stroke-linecap="round" stroke-linejoin="round"/>'
                        f'<path d="{d}" fill="none" stroke="url(#myoS)" stroke-width="{w:.1f}" stroke-linecap="round"/>')
        elif kind in ("vessel", "artery"):
            pts, w = shape[1], shape[2] * c.w / 400
            d = c.smooth(pts, False)
            lumen = BLOOD_R[1] if kind == "vessel" else BLOOD_L[1]
            body.append(f'<path d="{d}" fill="none" stroke="{OUTLINE}" stroke-width="{w + 12:.1f}" stroke-linecap="round"/>'
                        f'<path d="{d}" fill="none" stroke="{MYO_TOP}" stroke-width="{w + 8:.1f}" stroke-linecap="round"/>'
                        f'<path d="{d}" fill="none" stroke="{lumen}" stroke-width="{w:.1f}" stroke-linecap="round"/>')
        elif kind == "ellipse":
            (cx, cy), (rx, ry), fill, wall = shape[1], shape[2], shape[3], shape[4]
            X, Y = c.p((cx, cy))
            e = f'cx="{X:.1f}" cy="{Y:.1f}" rx="{rx * c.w:.1f}" ry="{ry * c.h:.1f}"'
            if fill == "myo":
                body.append(f'<ellipse {e} fill="url(#myoG)"/><ellipse {e} fill="url(#myoS)"/>'
                            + ("" if in_body else f'<ellipse {e} fill="none" stroke="{OUTLINE}" stroke-width="3"/>'))
            else:
                if wall and not in_body:
                    body.append(f'<ellipse {e} fill="none" stroke="{OUTLINE}" stroke-width="11"/>'
                                f'<ellipse {e} fill="none" stroke="{MYO_TOP}" stroke-width="7"/>')
                elif wall:
                    body.append(f'<ellipse {e} fill="none" stroke="{MYO_TOP}" stroke-width="7"/>')
                grad = "bloodR" if fill in ("R", "RA") else "bloodL"
                body.append(f'<ellipse {e} fill="url(#{grad})"/>')
    x0, y0, x1, y1 = crop or [0.0, 0.0, 1.0, 1.0]
    vb = (x0 * width, y0 * height, (x1 - x0) * width, (y1 - y0) * height)
    label_svg = label_layer(c, LABELS.get(view_id, [])) if labels else ""
    title = f"{view_id} schema" + ("" if labels else " (ohne Labels)")
    r = radius * c.h
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{vb[2]:.0f}" height="{vb[3]:.0f}" viewBox="{vb[0]:.1f} {vb[1]:.1f} {vb[2]:.1f} {vb[3]:.1f}">
  <title>{title}</title>
  <defs>
    <clipPath id="sector"><path d="{sector}"/></clipPath>
    <radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="{ax:.1f}" cy="{ay:.1f}" r="{r:.1f}">
      <stop offset="0" stop-color="{BG_NEAR}"/><stop offset="1" stop-color="{BG_FAR}"/></radialGradient>
    <radialGradient id="depth" gradientUnits="userSpaceOnUse" cx="{ax:.1f}" cy="{ay:.1f}" r="{r:.1f}">
      <stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="0.55" stop-color="#000" stop-opacity="0.08"/>
      <stop offset="1" stop-color="#000" stop-opacity="0.55"/></radialGradient>
    <linearGradient id="myoG" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="{MYO_TOP}"/><stop offset="1" stop-color="{MYO_BOTTOM}"/></linearGradient>
    <linearGradient id="liverG" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="{LIVER_TOP}"/><stop offset="1" stop-color="{LIVER_BOTTOM}"/></linearGradient>
    <radialGradient id="bloodR" cx="0.5" cy="0.5" r="0.7">
      <stop offset="0" stop-color="{BLOOD_R[0]}"/><stop offset="1" stop-color="{BLOOD_R[1]}"/></radialGradient>
    <radialGradient id="bloodL" cx="0.45" cy="0.5" r="0.7">
      <stop offset="0" stop-color="{BLOOD_L[0]}"/><stop offset="1" stop-color="{BLOOD_L[1]}"/></radialGradient>
    {speckle_pattern("myoS", None, "#F0DED2", "#6F5A51", 23)}
    <filter id="spk" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB">
      <feTurbulence type="fractalNoise" baseFrequency="0.22 0.34" numOctaves="2" seed="7"/>
      <feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  2.4 0 0 0 -1.05"/></filter>
    <filter id="spkD" x="0" y="0" width="100%" height="100%" color-interpolation-filters="sRGB">
      <feTurbulence type="fractalNoise" baseFrequency="0.2 0.3" numOctaves="2" seed="23"/>
      <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 2.4 0 0 -1.0"/></filter>
    <filter id="glow"><feGaussianBlur stdDeviation="5"/></filter>
    {speckle_pattern("liverS", None, "#B3A585", "#4E4434", 27)}
  </defs>
  <rect x="{vb[0]:.1f}" y="{vb[1]:.1f}" width="{vb[2]:.1f}" height="{vb[3]:.1f}" fill="{BG}"/>
  <g clip-path="url(#sector)">
    <path d="{sector}" fill="url(#bg)"/>
    {chr(10).join("    " + b for b in body).strip()}
    {('<rect x="-200" y="-200" width="1400" height="1200" filter="url(#spk)" opacity="0.10"/>') if RASTER else ""}
    <path d="{sector}" fill="url(#depth)"/>
  </g>
  {sector_decor(c, apex, half, radius)}
  <!--labels-->{label_svg}
</svg>
'''


# ---------------------------------------------------------------------------------------------
# Labels: white text on a dark pill (#0F172A, 80 %), with a leader line + dot when the pill sits
# beside a small structure. (text, pill centre, font size, leader target or None), normalised to
# the video frame. No stroke / paint-order: Android's SVG renderer ignores paint-order and would
# paint a dark outline over the letters.
# ---------------------------------------------------------------------------------------------

BIG, SMALL = 24, 19

LABELS = {
    "plax_standard": [
        ("RV", (0.52, 0.165), BIG, None),
        ("IVS", (0.27, 0.16), SMALL, (0.38, 0.245)),
        ("LV", (0.27, 0.38), BIG, None),
        ("Ao", (0.81, 0.29), SMALL, (0.67, 0.355)),
        ("LA", (0.76, 0.49), BIG, None),
        ("MV", (0.565, 0.39), SMALL, (0.535, 0.468)),
        ("PW", (0.20, 0.63), SMALL, (0.30, 0.53)),
        ("DAo", (0.83, 0.70), SMALL, (0.705, 0.668)),
    ],
    "psax_papillary": [
        ("RV", (0.31, 0.47), BIG, None),
        ("LV", (0.425, 0.525), SMALL, None),
        ("IVS", (0.30, 0.28), SMALL, (0.385, 0.36)),
        ("PM", (0.735, 0.43), SMALL, (0.545, 0.47)),
        ("PM", (0.735, 0.565), SMALL, (0.56, 0.53)),
        ("ANT", (0.64, 0.255), SMALL, (0.515, 0.30)),
        ("INF", (0.64, 0.665), SMALL, (0.515, 0.60)),
    ],
    "apical_4c": [
        ("LV", (0.575, 0.30), BIG, None),
        ("RV", (0.345, 0.40), BIG, None),
        ("RA", (0.37, 0.65), BIG, None),
        ("LA", (0.59, 0.70), BIG, None),
        ("TV", (0.235, 0.68), SMALL, (0.33, 0.55)),
        ("MV", (0.73, 0.65), SMALL, (0.62, 0.567)),
        ("IAS", (0.40, 0.775), SMALL, (0.468, 0.70)),
    ],
    "apical_3c": [
        ("LV", (0.51, 0.30), BIG, None),
        ("LA", (0.49, 0.69), BIG, None),
        ("Ao", (0.75, 0.70), SMALL, (0.62, 0.70)),
        ("LVOT", (0.76, 0.55), SMALL, (0.59, 0.545)),
        ("AV", (0.76, 0.62), SMALL, (0.605, 0.605)),
        ("MV", (0.33, 0.64), SMALL, (0.50, 0.53)),
        ("IL", (0.29, 0.30), SMALL, (0.40, 0.32)),
        ("AS", (0.72, 0.30), SMALL, (0.625, 0.34)),
    ],
    "apical_2c": [
        ("LV", (0.575, 0.36), BIG, None),
        ("LA", (0.575, 0.68), BIG, None),
        ("MV", (0.70, 0.62), SMALL, (0.60, 0.556)),
        ("INF", (0.32, 0.25), SMALL, (0.39, 0.32)),
        ("ANT", (0.72, 0.25), SMALL, (0.65, 0.32)),
    ],
    "subcostal_4c": [
        ("Liver", (0.43, 0.20), BIG, None),
        ("RV", (0.63, 0.255), SMALL, (0.53, 0.365)),
        ("RA", (0.385, 0.475), BIG, None),
        ("LA", (0.42, 0.60), BIG, None),
        ("LV", (0.585, 0.51), BIG, None),
        ("IVS", (0.68, 0.36), SMALL, (0.54, 0.40)),
        ("IAS", (0.22, 0.50), SMALL, (0.36, 0.545)),
    ],
    "subcostal_ivc": [
        ("Liver", (0.50, 0.30), BIG, None),
        ("IVC", (0.36, 0.556), SMALL, None),
        ("RA", (0.70, 0.67), BIG, None),
        ("Diaphragm", (0.16, 0.73), SMALL, (0.24, 0.60)),
        ("HV", (0.78, 0.48), SMALL, (0.645, 0.44)),
    ],
    "suprasternal_arch": [
        ("Arch", (0.51, 0.275), BIG, None),
        ("Asc Ao", (0.295, 0.50), SMALL, None),
        ("Desc Ao", (0.62, 0.52), SMALL, (0.515, 0.53)),
        ("BCA", (0.31, 0.12), SMALL, (0.444, 0.135)),
        ("LCCA", (0.66, 0.06), SMALL, (0.486, 0.12)),
        ("LSA", (0.68, 0.14), SMALL, (0.53, 0.135)),
        ("RPA", (0.30, 0.43), SMALL, (0.405, 0.43)),
    ],
    "psax_av": [
        ("RVOT", (0.53, 0.25), BIG, None),
        ("AV", (0.57, 0.52), SMALL, None),
        ("RA", (0.26, 0.60), BIG, (0.37, 0.59)),
        ("LA", (0.58, 0.76), BIG, None),
        ("TV", (0.25, 0.42), SMALL, (0.42, 0.475)),
        ("PV", (0.86, 0.38), SMALL, (0.68, 0.46)),
        ("MPA", (0.88, 0.58), SMALL, (0.735, 0.57)),
    ],
    "apical_5c": [
        ("RV", (0.38, 0.42), BIG, None),
        ("LV", (0.61, 0.42), BIG, None),
        ("RA", (0.36, 0.72), BIG, None),
        ("LA", (0.75, 0.71), BIG, None),
        ("IVS", (0.27, 0.22), SMALL, (0.485, 0.33)),
        ("LVOT", (0.80, 0.30), SMALL, (0.565, 0.55)),
        ("AV", (0.86, 0.50), SMALL, (0.605, 0.625)),
        ("Ao", (0.585, 0.86), SMALL, (0.585, 0.66)),
    ],
}


def pill_size(text: str, size: float) -> tuple[float, float]:
    return len(text) * size * 0.62 + size * 0.9, size * 1.5


def label_layer(c: "Canvas", labels) -> str:
    leaders, pills = [], []
    for text, pos, size, target in labels:
        X, Y = c.p(pos)
        w, h = pill_size(text, size)
        if target:
            TX, TY = c.p(target)
            leaders.append(f'<path d="M {X:.1f} {Y:.1f} L {TX:.1f} {TY:.1f}" stroke="{LABEL}" stroke-opacity="0.85" '
                           f'stroke-width="2" fill="none"/>'
                           f'<circle cx="{TX:.1f}" cy="{TY:.1f}" r="4" fill="{LABEL}"/>')
        pills.append(f'<rect x="{X - w / 2:.1f}" y="{Y - h / 2:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{h / 2:.1f}" '
                     f'fill="{PILL}" fill-opacity="0.72"/>'
                     f'<text x="{X:.1f}" y="{Y + size * 0.36:.1f}" font-family="sans-serif" font-weight="700" '
                     f'font-size="{size}" fill="{LABEL}" text-anchor="middle">{escape(text)}</text>')
    return "\n  ".join(leaders + pills)


def main():
    import subprocess

    os.makedirs(OUT, exist_ok=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    specs = {**VIEWS, **simulated_views()}
    for window in db["windows"]:
        for view in window["views"]:
            loop = os.path.join(ASSETS, view["videoAsset"])
            probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries",
                                    "stream=width,height", "-of", "csv=p=0", loop], capture_output=True, text=True)
            width, height = (int(v) for v in probe.stdout.strip().split(","))
            overlays = [o["video"] for st in view["steps"]
                        for o in ([st["caliperOverlay"]] if st.get("caliperOverlay") else []) + st.get("extraOverlays", [])
                        if o.get("video")]
            # Same crop for both variants so calipers and the AN/AUS toggle line up exactly.
            crop = crop_box(view["id"], specs[view["id"]], overlays, width, height)
            path = os.path.join(OUT, f"{view['id']}.svg")
            with open(path, "w", encoding="utf-8") as f:
                f.write(render(view["id"], specs[view["id"]], width, height, crop, labels=False))
            with open(os.path.join(OUT, f"{view['id']}_labels.svg"), "w", encoding="utf-8") as f:
                f.write(render(view["id"], specs[view["id"]], width, height, crop, labels=True))
            view["schemaAsset"] = f"schema/{view['id']}.svg"
            # The SVG shows only this part of the video frame; the app maps video calipers into it.
            view["schemaCrop"] = crop
            view["schemaAspect"] = round((crop[2] - crop[0]) * width / ((crop[3] - crop[1]) * height), 4)
            print(f"{os.path.relpath(path, ROOT)}  crop {crop}")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    # The app ships pre-rendered PNGs (assets/diagrams/schema/); this module only holds the anatomy.
    import export_schema_png
    export_schema_png.main()
