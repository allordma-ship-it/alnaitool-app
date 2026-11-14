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

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "schema")
DB = os.path.join(ASSETS, "echo_protocol_full.json")

BG = "#0B1220"
SECTOR = "#111C2E"
SECTOR_EDGE = "#33435C"
MYO = "#B84A5E"
MYO_EDGE = "#F4A9B7"
CAV = {"L": "#33171F", "R": "#14213D", "A": "#2A1520", "RA": "#18284A"}
ARTERY_WALL = "#E07A8A"
VEIN_WALL = "#86A2DC"
VALVE = "#F1F5F9"
PERI = "#E2E8F0"
LIVER = "#3D2C24"
LABEL = "#F8FAFC"


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
# Views. Shapes: ("liver", pts) ("cav", pts, kind) ("myo", outer, inner) ("band", top, bottom)
#   ("valve", pts) ("peri", pts) ("vessel", pts, width, artery?) ("ellipse", c, r, kind, wall)
#   ("label", text, (x, y)[, size])
# ---------------------------------------------------------------------------------------------

VIEWS = {
    # Parasternal long axis, traced from the ED frame (R-wave) of loops/plax_standard.mp4.
    "plax_standard": dict(
        sector=((0.50, 0.04), 39, 0.75),
        shapes=[
            ("cav", [(0.36, 0.20), (0.42, 0.105), (0.58, 0.105), (0.64, 0.24), (0.55, 0.258),
                     (0.47, 0.243), (0.40, 0.226)], "R"),
            ("band", [(0.40, 0.078), (0.50, 0.068), (0.60, 0.078)], [(0.60, 0.105), (0.50, 0.095), (0.40, 0.105)]),
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
            ("ellipse", (0.537, 0.47), (0.035, 0.03), "myo", None),
            ("ellipse", (0.553, 0.527), (0.03, 0.028), "myo", None),
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
                     [(0.30, 0.565), (0.29, 0.50), (0.31, 0.40), (0.36, 0.30), (0.43, 0.20)]),
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
        ],
    ),
    # Apical 2-chamber; ED frame of the first (A2C) clip in loops/apical_2c_3c.mp4.
    "apical_2c_3c": dict(
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
                     [(0.70, 0.322), (0.55, 0.33), (0.47, 0.333), (0.40, 0.336), (0.30, 0.345), (0.20, 0.36)]),
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
        ],
    ),
}


def simulated_views() -> dict:
    """psax_av and apical_5c use the simulator's own geometry (design units x/400, y/300)."""
    def n(p):
        return (p[0] / 400.0, p[1] / 300.0)

    sector = ((0.5, 22 / 300), 40, 268 / 300)
    psax_av = dict(sector=sector, shapes=[
        ("ellipse", n((110, 196)), (40 / 400, 30 / 300), "RA", "wall"),
        ("ellipse", n((208, 230)), (52 / 400, 25 / 300), "A", "wall"),
        ("myo", [n(p) for p in [(66, 152), (80, 94), (128, 60), (200, 54), (272, 58), (316, 90), (290, 116),
                                (260, 100), (200, 98), (140, 104), (114, 126), (114, 152)]],
                [n(p) for p in [(110, 150), (112, 124), (140, 100), (200, 94), (260, 96), (290, 112),
                                (312, 92), (270, 62), (200, 58), (130, 64), (84, 96), (70, 150)]]),
        ("cav", [n(p) for p in [(84, 96), (130, 64), (200, 58), (270, 62), (312, 92), (290, 112), (260, 96),
                                (200, 94), (140, 100), (112, 124), (110, 150), (70, 150)]], "R"),
        ("vessel", [n(p) for p in [(268, 114), (282, 140), (290, 168)]], 26, True),
        ("vessel", [n(p) for p in [(290, 168), (280, 214)]], 12, True),
        ("vessel", [n(p) for p in [(290, 168), (322, 198)]], 12, True),
        ("ellipse", n((200, 150)), (38 / 400, 38 / 300), "A", "wall"),
        ("valve", [n((200, 150)), n((200, 112))]),
        ("valve", [n((200, 150)), n((233, 169))]),
        ("valve", [n((200, 150)), n((167, 169))]),
        ("valve", [n((256, 118)), n((278, 106))]),
        ("valve", [n((116, 150)), n((140, 168))]),
        ("label", "RVOT", (0.50, 0.255)),
        ("label", "AV", (0.50, 0.44), 12),
        ("label", "RA", (0.275, 0.66)),
        ("label", "LA", (0.52, 0.78)),
        ("label", "PA", (0.775, 0.50), 12),
        ("label", "PV", (0.735, 0.33), 11),
        ("label", "TV", (0.37, 0.53), 11),
    ])
    apical_5c = dict(sector=sector, shapes=[
        ("cav", [n(p) for p in [(196, 46), (150, 80), (116, 166), (176, 164)]], "R"),
        ("cav", [n(p) for p in [(204, 46), (262, 70), (300, 176), (226, 178)]], "L"),
        ("ellipse", n((132, 222)), (40 / 400, 32 / 300), "RA", "wall"),
        ("ellipse", n((274, 226)), (40 / 400, 32 / 300), "A", "wall"),
        ("band", [n(p) for p in [(192, 40), (144, 76), (108, 168)]], [n(p) for p in [(116, 166), (150, 80), (196, 46)]]),
        ("band", [n(p) for p in [(196, 46), (186, 105), (176, 164)]], [n(p) for p in [(226, 178), (214, 110), (204, 46)]]),
        ("band", [n(p) for p in [(208, 40), (268, 66), (308, 178)]], [n(p) for p in [(300, 176), (262, 70), (204, 46)]]),
        ("vessel", [n((203, 176)), n((203, 262))], 34, True),
        ("valve", [n((122, 168)), n((176, 168))]),
        ("valve", [n((228, 182)), n((296, 182))]),
        ("valve", [n((187, 186)), n((203, 196))]),
        ("valve", [n((219, 186)), n((203, 196))]),
        ("label", "LV", (0.64, 0.36)),
        ("label", "RV", (0.38, 0.38)),
        ("label", "RA", (0.33, 0.75)),
        ("label", "LA", (0.685, 0.76)),
        ("label", "LVOT", (0.555, 0.68), 11),
        ("label", "AV", (0.555, 0.62), 11),
    ])
    return {"psax_av": psax_av, "apical_5c": apical_5c}


# ---------------------------------------------------------------------------------------------

def crop_box(spec: dict, overlays: list[list[float]], pad: float = 0.05) -> list[float]:
    """Region around the anatomy and every caliper, so the small stage panel shows the heart large."""
    xs, ys = [], []
    for shape in spec["shapes"]:
        kind = shape[0]
        if kind in ("liver",):
            continue
        if kind in ("cav", "valve", "peri", "line", "vessel"):
            pts = shape[1]
        elif kind in ("myo", "band"):
            pts = shape[1] + shape[2]
        elif kind == "ellipse":
            (cx, cy), (rx, ry) = shape[1], shape[2]
            pts = [(cx - rx, cy - ry), (cx + rx, cy + ry)]
        elif kind == "label":
            pts = [shape[2]]
        else:
            pts = []
        xs += [p[0] for p in pts]
        ys += [p[1] for p in pts]
    for c in overlays:
        xs += [c[0], c[2]]
        ys += [c[1], c[3]]
    box = [max(0.0, min(xs) - pad), max(0.0, min(ys) - pad), min(1.0, max(xs) + pad), min(1.0, max(ys) + pad)]
    return [round(v, 3) for v in box]


def render(view_id: str, spec: dict, width: int, height: int, crop: list[float] | None = None) -> str:
    c = Canvas(width, height)
    apex, half, radius = spec["sector"]
    sector = sector_path(c, apex, half, radius)
    body = []
    labels = []
    for shape in spec["shapes"]:
        kind = shape[0]
        if kind == "liver":
            body.append(f'<path d="{c.smooth(shape[1], True)}" fill="{LIVER}"/>')
        elif kind == "cav":
            body.append(f'<path d="{c.smooth(shape[1], True)}" fill="{CAV[shape[2]]}"/>')
        elif kind == "myo":
            outer, inner = shape[1], shape[2]
            d = c.smooth(outer + inner, True)
            body.append(f'<path d="{d}" fill="{MYO}" stroke="{MYO_EDGE}" stroke-width="1.6" stroke-linejoin="round"/>')
        elif kind == "band":
            top, bottom = shape[1], shape[2]
            d = c.smooth(top, False) + " L " + c.smooth(bottom, False)[2:] + " Z"
            body.append(f'<path d="{d}" fill="{MYO}" stroke="{MYO_EDGE}" stroke-width="1.6" stroke-linejoin="round"/>')
        elif kind == "valve":
            body.append(f'<path d="{c.smooth(shape[1], False)}" fill="none" stroke="{VALVE}" stroke-width="3.5" '
                        f'stroke-linecap="round"/>')
        elif kind == "peri":
            body.append(f'<path d="{c.smooth(shape[1], False)}" fill="none" stroke="{PERI}" stroke-width="2.2" '
                        f'stroke-opacity="0.85" stroke-linecap="round"/>')
        elif kind == "line":
            body.append(f'<path d="{c.smooth(shape[1], False)}" fill="none" stroke="{shape[2]}" '
                        f'stroke-width="{shape[3]}" stroke-linecap="round"/>')
        elif kind == "vessel":
            pts, w = shape[1], shape[2] * c.w / 400
            d = c.smooth(pts, False)
            body.append(f'<path d="{d}" fill="none" stroke="{ARTERY_WALL}" stroke-width="{w + 8:.1f}" stroke-linecap="round"/>')
            body.append(f'<path d="{d}" fill="none" stroke="{CAV["L"]}" stroke-width="{w:.1f}" stroke-linecap="round"/>')
        elif kind == "ellipse":
            (cx, cy), (rx, ry), fill, wall = shape[1], shape[2], shape[3], shape[4]
            X, Y = c.p((cx, cy))
            colour = MYO if fill == "myo" else CAV[fill]
            stroke = MYO_EDGE if fill == "myo" else (ARTERY_WALL if wall else "none")
            body.append(f'<ellipse cx="{X:.1f}" cy="{Y:.1f}" rx="{rx * c.w:.1f}" ry="{ry * c.h:.1f}" fill="{colour}" '
                        f'stroke="{stroke}" stroke-width="{4 if wall else 1.6}"/>')
        elif kind == "label":
            text, (x, y) = shape[1], shape[2]
            size = shape[3] if len(shape) > 3 else 16
            X, Y = c.p((x, y))
            labels.append(f'<text x="{X:.1f}" y="{Y:.1f}" font-size="{size}" text-anchor="middle" '
                          f'dominant-baseline="middle">{text}</text>')
    x0, y0, x1, y1 = crop or [0.0, 0.0, 1.0, 1.0]
    vb = (x0 * width, y0 * height, (x1 - x0) * width, (y1 - y0) * height)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{vb[2]:.0f}" height="{vb[3]:.0f}" viewBox="{vb[0]:.1f} {vb[1]:.1f} {vb[2]:.1f} {vb[3]:.1f}">
  <title>{view_id} schema</title>
  <defs><clipPath id="sector"><path d="{sector}"/></clipPath></defs>
  <rect x="{vb[0]:.1f}" y="{vb[1]:.1f}" width="{vb[2]:.1f}" height="{vb[3]:.1f}" fill="{BG}"/>
  <path d="{sector}" fill="{SECTOR}"/>
  <g clip-path="url(#sector)">
    {chr(10).join("    " + b for b in body).strip()}
  </g>
  <path d="{sector}" fill="none" stroke="{SECTOR_EDGE}" stroke-width="1.5"/>
  <g font-family="sans-serif" font-weight="700" fill="{LABEL}" stroke="{BG}" stroke-width="3" paint-order="stroke">
    {chr(10).join("    " + t for t in labels).strip()}
  </g>
</svg>
'''


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
            crop = crop_box(specs[view["id"]], overlays)
            svg = render(view["id"], specs[view["id"]], width, height, crop)
            path = os.path.join(OUT, f"{view['id']}.svg")
            with open(path, "w", encoding="utf-8") as f:
                f.write(svg)
            view["schemaAsset"] = f"schema/{view['id']}.svg"
            # The SVG shows only this part of the video frame; the app maps video calipers into it.
            view["schemaCrop"] = crop
            view["schemaAspect"] = round((crop[2] - crop[0]) * width / ((crop[3] - crop[1]) * height), 4)
            print(f"{os.path.relpath(path, ROOT)}  crop {crop}")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
