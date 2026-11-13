#!/usr/bin/env python3
"""
EchoScope – medical-textbook anatomical SVGs for the 9 standard TTE views (viewBox 600x420).

Style:
  * one continuous myocardial mass per heart (septum = myocardium between the cavities),
    thick LV walls, thinner RV free wall, thin atrial walls
  * papillary muscles with chordae, RV trabeculations and moderator band
  * thin valve leaflets hinged at the annulus, aortic sinuses, MPA bifurcation, caval and
    pulmonary veins
  * textbook colouring: oxygenated (left heart, aorta, pulmonary veins) red,
    deoxygenated (right heart, caval veins, pulmonary artery) blue
  * everything clipped to the ultrasound sector, labels with leader lines,
    S/I/R/L orientation compass, notch marker and PW / CW / Color Doppler markers

Usage: python3 scripts/gen_anatomy_svg.py [output_dir]
"""

from __future__ import annotations

import math
from xml.sax.saxutils import escape
import os
import sys

W, H = 600, 420
BG = "#0A0E14"
TEXT = "#E2E8F0"
MUTED = "#94A3B8"
LEADER = "#7C8BA1"
NOTCH = "#FF8A3D"
VALVE = "#FBD38D"
DOPPLER = "#FF4D6D"
TEAL = "#00D2B8"

# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------


def f(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".")


def smooth(points, closed=True, tension=1.0) -> str:
    """Catmull-Rom spline through points as an SVG cubic Bezier path."""
    pts = list(points)
    n = len(pts)
    if n < 3:
        return "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts)
    d = [f"M{f(pts[0][0])},{f(pts[0][1])}"]
    last = n if closed else n - 1
    for i in range(last):
        p0 = pts[(i - 1) % n] if closed or i > 0 else pts[0]
        p1 = pts[i]
        p2 = pts[(i + 1) % n]
        p3 = pts[(i + 2) % n] if closed or i + 2 < n else pts[-1]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6 * tension, p1[1] + (p2[1] - p0[1]) / 6 * tension)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6 * tension, p2[1] - (p3[1] - p1[1]) / 6 * tension)
        d.append(f"C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(p2[0])},{f(p2[1])}")
    if closed:
        d.append("Z")
    return " ".join(d)


def scale_pts(points, center, k):
    cx, cy = center
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in points]


def centroid(points):
    return (sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points))


def ellipse_pts(cx, cy, rx, ry, n=10, rot=0.0, wobble=0.0):
    out = []
    for i in range(n):
        a = 2 * math.pi * i / n
        r = 1 + wobble * math.sin(3 * a + 0.7)
        x, y = rx * r * math.cos(a), ry * r * math.sin(a)
        c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        out.append((cx + x * c - y * s, cy + x * s + y * c))
    return out


def transform(points, fn):
    return [fn(x, y) for x, y in points]


# ---------------------------------------------------------------------------
# SVG building blocks
# ---------------------------------------------------------------------------

DEFS = f"""  <defs>
    <linearGradient id="myo" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#D45D70"/>
      <stop offset="1" stop-color="#8E2B3E"/>
    </linearGradient>
    <linearGradient id="myoThin" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#C9707E"/>
      <stop offset="1" stop-color="#9A4352"/>
    </linearGradient>
    <radialGradient id="bloodL" cx="0.45" cy="0.4" r="0.7">
      <stop offset="0" stop-color="#5E1824"/>
      <stop offset="1" stop-color="#2B0A11"/>
    </radialGradient>
    <radialGradient id="bloodR" cx="0.45" cy="0.4" r="0.7">
      <stop offset="0" stop-color="#1D3566"/>
      <stop offset="1" stop-color="#0C1630"/>
    </radialGradient>
    <linearGradient id="aortaWall" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#E07A86"/>
      <stop offset="1" stop-color="#B04456"/>
    </linearGradient>
    <linearGradient id="veinWall" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#7F9BD6"/>
      <stop offset="1" stop-color="#4C66A6"/>
    </linearGradient>
    <linearGradient id="liver" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#5A3B2E"/>
      <stop offset="1" stop-color="#3A271F"/>
    </linearGradient>
    <radialGradient id="sectorFill" cx="0.5" cy="0" r="1">
      <stop offset="0" stop-color="#16202E"/>
      <stop offset="1" stop-color="#0D141D"/>
    </radialGradient>
  </defs>"""


class Svg:
    def __init__(self, title: str):
        self.title = title
        self.clipped: list[str] = []   # anatomy, clipped to the sector
        self.overlay: list[str] = []   # labels, compass, Doppler markers
        self.sectors: list[tuple] = []

    # --- sector -------------------------------------------------------------
    def sector(self, apex=(300, 22), radius=392, half=44):
        self.sectors.append((apex, radius, half))

    @staticmethod
    def sector_path(apex, radius, half) -> str:
        ax, ay = apex
        a = math.radians(half)
        x1, y1 = ax - radius * math.sin(a), ay + radius * math.cos(a)
        x2, y2 = ax + radius * math.sin(a), ay + radius * math.cos(a)
        return f"M{f(ax)},{f(ay)} L{f(x1)},{f(y1)} A{radius},{radius} 0 0,0 {f(x2)},{f(y2)} Z"

    # --- anatomy primitives (clipped) ---------------------------------------
    def add(self, s: str):
        self.clipped.append("    " + s)

    def path(self, d, fill="none", stroke="none", sw=0.0, extra=""):
        self.add(f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{f(sw)}" '
                 f'stroke-linecap="round" stroke-linejoin="round"{extra}/>')

    def myocardium(self, outer, cavities, thin=False):
        """Filled muscle mass with blood cavities [(points, 'L'|'R')] cut into it."""
        self.path(smooth(outer), fill="url(#myoThin)" if thin else "url(#myo)", stroke="#E58A97", sw=1.2)
        for pts, side in cavities:
            self.path(smooth(pts), fill=f"url(#blood{side})", stroke="#3A0E18" if side == "L" else "#0A1734", sw=1.0)

    def chamber_wall(self, cavity, side, wall=1.08):
        """Thin-walled chamber (atrium): wall drawn as scaled outline behind the cavity."""
        c = centroid(cavity)
        self.path(smooth(scale_pts(cavity, c, wall)), fill="url(#myoThin)", stroke="#E58A97", sw=1.0)
        self.path(smooth(cavity), fill=f"url(#blood{side})")

    def vessel(self, centerline, width, side, wall=4.0):
        """Tube along a centre line (aorta, PA, veins) with a coloured wall."""
        wall_color = "url(#aortaWall)" if side == "L" else "url(#veinWall)"
        d = smooth(centerline, closed=False)
        self.path(d, stroke=wall_color, sw=width + 2 * wall)
        self.path(d, stroke="#2B0A11" if side == "L" else "#0C1630", sw=width)

    def blob(self, pts, fill="url(#myo)"):
        self.path(smooth(pts), fill=fill, stroke="#E58A97", sw=0.8)

    def leaflet(self, pts, sw=3.2):
        self.path(smooth(pts, closed=False), stroke=VALVE, sw=sw)

    def chordae(self, a, b):
        self.add(f'<line x1="{f(a[0])}" y1="{f(a[1])}" x2="{f(b[0])}" y2="{f(b[1])}" '
                 f'stroke="#F1E3C6" stroke-width="0.8" stroke-opacity="0.75"/>')

    def trabeculae(self, pts):
        for (x1, y1), (x2, y2) in zip(pts[::2], pts[1::2]):
            self.path(f"M{f(x1)},{f(y1)} Q{f((x1 + x2) / 2 + 3)},{f((y1 + y2) / 2 - 3)} {f(x2)},{f(y2)}",
                      stroke="#B9475B", sw=3.0)

    def pericardium(self, pts):
        self.path(smooth(pts, closed=False), stroke="#F4E1E5", sw=2.2, extra=' stroke-opacity="0.85"')

    def tissue(self, pts, fill="url(#liver)", label=None):
        self.path(smooth(pts), fill=fill, stroke="#6B4A3A", sw=1.0)

    # --- overlay (not clipped) ----------------------------------------------
    def o(self, s: str):
        self.overlay.append("  " + s)

    def big_label(self, x, y, text, side):
        color = "#FFB4BE" if side == "L" else "#A9C1FF"
        self.o(f'<text x="{f(x)}" y="{f(y)}" font-family="sans-serif" font-size="17" font-weight="bold" '
               f'fill="{color}" text-anchor="middle">{text}</text>')

    def label(self, text, tx, ty, target, anchor="start"):
        """Text at (tx, ty) with a leader line to target."""
        ex = tx - 4 if anchor == "start" else tx + 4 if anchor == "end" else tx
        ey = ty - 4
        self.o(f'<polyline points="{f(ex)},{f(ey)} {f(target[0])},{f(target[1])}" fill="none" '
               f'stroke="{LEADER}" stroke-width="1"/>')
        self.o(f'<circle cx="{f(target[0])}" cy="{f(target[1])}" r="2.6" fill="{TEXT}"/>')
        self.o(f'<text x="{f(tx)}" y="{f(ty)}" font-family="sans-serif" font-size="12.5" fill="{TEXT}" '
               f'text-anchor="{anchor}">{text}</text>')

    def compass(self, x, y, top, bottom, left, right, name, r=26):
        self.o(f'<g>')
        self.o(f'<circle cx="{x}" cy="{y}" r="{r}" fill="#111824" stroke="#334155" stroke-width="1.2"/>')
        for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            self.o(f'<line x1="{x}" y1="{y}" x2="{f(x + dx * (r - 15))}" y2="{f(y + dy * (r - 15))}" '
                   f'stroke="{TEAL}" stroke-width="1.6"/>')
        self.o(f'<circle cx="{x}" cy="{y}" r="2.4" fill="{TEAL}"/>')
        style = f'font-family="sans-serif" font-size="11" font-weight="bold" fill="{TEXT}" text-anchor="middle"'
        self.o(f'<text x="{x}" y="{f(y - r + 12)}" {style}>{top}</text>')
        self.o(f'<text x="{x}" y="{f(y + r - 3)}" {style}>{bottom}</text>')
        self.o(f'<text x="{f(x - r + 9)}" y="{f(y + 4)}" {style}>{left}</text>')
        self.o(f'<text x="{f(x + r - 9)}" y="{f(y + 4)}" {style}>{right}</text>')
        self.o(f'<text x="{x}" y="{f(y + r + 15)}" font-family="sans-serif" font-size="11" font-weight="bold" '
               f'fill="{TEAL}" text-anchor="middle">{name}</text>')
        self.o('</g>')

    def notch(self, apex):
        x, y = apex[0] + 30, apex[1] + 6
        self.o(f'<circle cx="{f(x)}" cy="{f(y)}" r="5" fill="{NOTCH}"/>')

    def pw(self, apex, gate, label, label_pos, anchor="start"):
        """Pulsed-wave Doppler: dashed cursor from the probe and a sample gate."""
        self._cursor(apex, gate)
        ang = math.atan2(gate[1] - apex[1], gate[0] - apex[0])
        nx, ny = -math.sin(ang), math.cos(ang)
        for k in (-5, 5):
            cx, cy = gate[0] + math.cos(ang) * k, gate[1] + math.sin(ang) * k
            self.o(f'<line x1="{f(cx - nx * 7)}" y1="{f(cy - ny * 7)}" x2="{f(cx + nx * 7)}" y2="{f(cy + ny * 7)}" '
                   f'stroke="{DOPPLER}" stroke-width="2.4" stroke-linecap="round"/>')
        self._doppler_label(label, label_pos, anchor)

    def cw(self, apex, end, focus, label, label_pos, anchor="start"):
        """Continuous-wave Doppler: dashed cursor through the jet with a focus diamond."""
        self._cursor(apex, end)
        fx, fy = focus
        self.o(f'<path d="M{f(fx)},{f(fy - 6)} L{f(fx + 6)},{f(fy)} L{f(fx)},{f(fy + 6)} L{f(fx - 6)},{f(fy)} Z" '
               f'fill="{DOPPLER}"/>')
        self._doppler_label(label, label_pos, anchor)

    def color_box(self, apex, r1, r2, a1, a2, label, label_pos, anchor="start"):
        """Colour Doppler region of interest: an annular sector between radii r1..r2, angles a1..a2."""
        ax, ay = apex

        def pt(r, a):
            return ax + r * math.sin(math.radians(a)), ay + r * math.cos(math.radians(a))

        p1, p2, p3, p4 = pt(r1, a1), pt(r1, a2), pt(r2, a2), pt(r2, a1)
        d = (f"M{f(p1[0])},{f(p1[1])} A{r1},{r1} 0 0,0 {f(p2[0])},{f(p2[1])} L{f(p3[0])},{f(p3[1])} "
             f"A{r2},{r2} 0 0,1 {f(p4[0])},{f(p4[1])} Z")
        self.o(f'<path d="{d}" fill="{DOPPLER}" fill-opacity="0.08" stroke="{DOPPLER}" stroke-width="1.6" '
               f'stroke-dasharray="6,4"/>')
        self._doppler_label(label, label_pos, anchor)

    def mmode(self, apex, end, label, label_pos, anchor="start"):
        self.o(f'<line x1="{f(apex[0])}" y1="{f(apex[1])}" x2="{f(end[0])}" y2="{f(end[1])}" stroke="{TEAL}" '
               f'stroke-width="1.6" stroke-dasharray="2,5" stroke-linecap="round"/>')
        self.o(f'<text x="{f(label_pos[0])}" y="{f(label_pos[1])}" font-family="sans-serif" font-size="11.5" '
               f'font-weight="bold" fill="{TEAL}" text-anchor="{anchor}">{label}</text>')

    def _cursor(self, a, b):
        self.o(f'<line x1="{f(a[0])}" y1="{f(a[1])}" x2="{f(b[0])}" y2="{f(b[1])}" stroke="{DOPPLER}" '
               f'stroke-width="1.4" stroke-dasharray="7,5" stroke-opacity="0.9"/>')

    def _doppler_label(self, text, pos, anchor):
        x, y = pos
        w = 8 + 7.2 * len(text)
        rx = x if anchor == "start" else x - w if anchor == "end" else x - w / 2
        self.o(f'<rect x="{f(rx)}" y="{f(y - 13)}" width="{f(w)}" height="18" rx="9" fill="{DOPPLER}" fill-opacity="0.18" '
               f'stroke="{DOPPLER}" stroke-width="1"/>')
        self.o(f'<text x="{f(rx + w / 2)}" y="{f(y)}" font-family="sans-serif" font-size="11.5" font-weight="bold" '
               f'fill="#FFC2CC" text-anchor="middle">{text}</text>')

    def legend(self, x=474, y=22):
        items = [("url(#myo)", "Myocardium"), ("#B03A4E", "Oxygenated"), ("#3A5BA8", "Deoxygenated"),
                 (VALVE, "Valves"), (DOPPLER, "Doppler")]
        self.o(f'<rect x="{x - 8}" y="{y - 13}" width="124" height="{len(items) * 13 + 8}" rx="6" '
               f'fill="#0A0E14" fill-opacity="0.85" stroke="#1F2A3A"/>')
        for i, (c, t) in enumerate(items):
            yy = y + i * 13
            self.o(f'<rect x="{x}" y="{yy - 8}" width="10" height="10" rx="2" fill="{c}"/>')
            self.o(f'<text x="{x + 16}" y="{yy + 1}" font-family="sans-serif" font-size="10" fill="{MUTED}">{t}</text>')

    def render(self) -> str:
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
               f'  <title>{escape(self.title)}</title>', DEFS,
               f'  <rect x="0" y="0" width="{W}" height="{H}" fill="{BG}"/>']
        clip_d = " ".join(self.sector_path(*s) for s in self.sectors)
        out.append(f'  <clipPath id="sectorClip"><path d="{clip_d}"/></clipPath>')
        for s in self.sectors:
            out.append(f'  <path d="{self.sector_path(*s)}" fill="url(#sectorFill)" stroke="#22314A" stroke-width="1.5"/>')
        out.append('  <g clip-path="url(#sectorClip)">')
        out.extend(self.clipped)
        out.append('  </g>')
        for s in self.sectors:
            out.append(f'  <path d="{self.sector_path(*s)}" fill="none" stroke="#2C3F5E" stroke-width="1.5"/>')
        out.extend(self.overlay)
        out.append('</svg>')
        return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# Shared four-chamber heart (apical orientation: apex up, LV right, RV left)
# ---------------------------------------------------------------------------

A4C = {
    "mass": [(300, 56), (346, 72), (392, 116), (418, 176), (430, 236), (428, 268), (372, 270), (300, 264),
             (226, 268), (184, 266), (182, 222), (196, 166), (222, 118), (258, 78)],
    "lv": [(316, 88), (352, 112), (380, 160), (396, 214), (400, 258), (360, 260), (316, 258), (313, 196), (312, 132)],
    "rv": [(292, 96), (268, 116), (238, 158), (212, 204), (198, 256), (246, 258), (294, 252), (296, 190), (296, 134)],
    "la": [(318, 280), (358, 274), (398, 284), (414, 318), (402, 356), (360, 370), (322, 356), (310, 318)],
    "ra": [(286, 282), (248, 274), (208, 282), (190, 316), (202, 352), (242, 366), (278, 352), (290, 318)],
}


def four_chamber(svg: Svg, tf=lambda x, y: (x, y), detail=True):
    g = {k: transform(v, tf) for k, v in A4C.items()}
    t = lambda pts: transform(pts, tf)
    svg.chamber_wall(g["la"], "L", 1.07)
    svg.chamber_wall(g["ra"], "R", 1.07)
    # pulmonary veins into LA
    svg.vessel(t([(338, 360), (332, 384), (322, 412)]), 12, "L", 2.5)
    svg.vessel(t([(390, 352), (404, 380), (420, 410)]), 12, "L", 2.5)
    svg.myocardium(g["mass"], [(g["lv"], "L"), (g["rv"], "R")])
    if detail:
        # moderator band and RV trabeculations
        svg.path(smooth(t([(296, 176), (268, 190), (232, 206), (214, 214)]), closed=False), stroke="url(#myo)", sw=6)
        svg.trabeculae(t([(214, 196), (226, 188), (222, 168), (236, 160), (236, 136), (250, 130)]))
        # LV papillary muscle with chordae
        pm = t([(398, 190), (382, 186), (366, 196), (370, 210), (388, 214), (398, 206)])
        svg.blob(pm)
        tip = tf(366, 198)
        for a in ((346, 226), (370, 232), (384, 230)):
            svg.chordae(tip, tf(*a))
    # mitral valve (open, diastole) and tricuspid valve (septal leaflet displaced apically)
    svg.leaflet(t([(316, 262), (328, 240), (342, 222)]))
    svg.leaflet(t([(400, 262), (392, 244), (384, 228)]))
    svg.leaflet(t([(296, 252), (286, 232), (276, 216)]))
    svg.leaflet(t([(196, 262), (210, 240), (222, 224)]))


# ---------------------------------------------------------------------------
# Views
# ---------------------------------------------------------------------------

def apical_4c() -> Svg:
    s = Svg("Apical 4-Chamber")
    apex = (300, 22)
    s.sector(apex)
    four_chamber(s)
    s.big_label(358, 196, "LV", "L"); s.big_label(250, 210, "RV", "R")
    s.big_label(362, 326, "LA", "L"); s.big_label(240, 324, "RA", "R")
    s.label("Apex", 360, 56, (318, 64))
    s.label("IVS", 330, 140, (304, 150))
    s.label("Moderator band", 112, 196, (226, 208), anchor="start")
    s.label("Papillary muscle", 446, 186, (396, 198))
    s.label("Mitral valve", 446, 240, (392, 246))
    s.label("Tricuspid valve", 100, 250, (206, 246))
    s.label("(apical offset)", 100, 264, (206, 246))
    s.label("Interatrial septum", 214, 404, (300, 330), anchor="middle")
    s.label("Pulmonary veins", 470, 404, (414, 398))
    s.pw(apex, (348, 222), "PW · mitral inflow", (420, 132))
    s.cw(apex, (232, 330), (262, 238), "CW · TR", (116, 120))
    s.notch(apex)
    s.compass(44, 46, "I", "S", "R", "L", "A4C")
    return s


def apical_5c() -> Svg:
    s = Svg("Apical 5-Chamber")
    apex = (300, 22)
    s.sector(apex)
    s.chamber_wall([(390, 290), (430, 282), (468, 300), (474, 336), (452, 366), (412, 368), (386, 340), (380, 310)], "L", 1.07)
    s.chamber_wall([(214, 290), (176, 282), (138, 300), (130, 336), (150, 366), (190, 368), (214, 340), (220, 310)], "R", 1.07)
    s.vessel([(422, 362), (432, 390), (444, 414)], 12, "L", 2.5)
    # aortic root with sinus bulge and ascending aorta
    s.path(smooth([(276, 262), (268, 288), (262, 318), (270, 346), (276, 380), (276, 420), (330, 420), (330, 380),
                   (336, 346), (344, 318), (338, 288), (330, 262)]), fill="url(#aortaWall)", stroke="#E58A97", sw=1)
    s.path(smooth([(283, 266), (276, 290), (271, 318), (278, 346), (284, 380), (284, 420), (322, 420), (322, 380),
                   (328, 346), (335, 318), (330, 290), (323, 266)]), fill="url(#bloodL)")
    mass = [(300, 56), (346, 72), (392, 116), (420, 176), (434, 236), (434, 282), (388, 284), (334, 262),
            (276, 262), (212, 284), (168, 282), (170, 230), (190, 166), (220, 116), (258, 76)]
    lv = [(316, 88), (352, 112), (382, 162), (400, 220), (404, 272), (372, 276), (330, 256), (312, 220), (310, 150)]
    rv = [(290, 96), (264, 118), (232, 162), (206, 214), (192, 274), (224, 276), (272, 256), (294, 200), (296, 140)]
    s.myocardium(mass, [(lv, "L"), (rv, "R")])
    # LVOT channel into the aortic root
    s.path(smooth([(312, 222), (328, 256), (324, 270), (284, 270), (282, 256), (300, 226)]), fill="url(#bloodL)")
    s.trabeculae([(212, 200), (224, 192), (226, 160), (238, 154)])
    # aortic valve (closed in diastole), mitral valve, tricuspid valve
    s.leaflet([(284, 268), (296, 280), (303, 284)], 3); s.leaflet([(322, 268), (310, 280), (303, 284)], 3)
    s.leaflet([(334, 262), (352, 248), (364, 232)])
    s.leaflet([(404, 280), (398, 258), (390, 242)])
    s.leaflet([(214, 280), (226, 258), (236, 240)])
    s.leaflet([(270, 262), (262, 244), (256, 228)])
    s.big_label(362, 200, "LV", "L"); s.big_label(244, 214, "RV", "R")
    s.big_label(426, 334, "LA", "L"); s.big_label(176, 334, "RA", "R")
    s.label("LVOT", 196, 150, (304, 242))
    s.label("Aortic valve", 456, 254, (322, 276))
    s.label("Aortic root", 350, 404, (318, 338))
    s.label("Anterior mitral leaflet", 452, 200, (352, 248))
    s.pw(apex, (304, 246), "PW · LVOT", (420, 120))
    s.cw(apex, (303, 400), (303, 284), "CW · AV", (108, 120))
    s.notch(apex)
    s.compass(44, 46, "I", "S", "R", "L", "A5C")
    return s


def apical_2c_3c() -> Svg:
    s = Svg("Apical 2-Chamber & 3-Chamber")
    a1, a2 = (150, 30), (450, 30)
    s.sector(a1, 384, 27)
    s.sector(a2, 384, 27)
    # --- A2C (left panel): inferior wall on the left, anterior wall on the right
    s.chamber_wall([(104, 284), (150, 276), (196, 286), (206, 322), (190, 358), (150, 368), (110, 356), (96, 320)], "L", 1.07)
    s.chamber_wall([(204, 300), (222, 292), (234, 304), (226, 318), (210, 316)], "L", 1.15)  # LAA
    s.vessel([(130, 360), (122, 390), (114, 416)], 11, "L", 2.5)
    mass = [(150, 52), (190, 74), (216, 130), (228, 200), (228, 272), (190, 276), (110, 276), (72, 272),
            (72, 200), (84, 130), (110, 74)]
    lv = [(150, 82), (178, 102), (196, 150), (204, 210), (204, 266), (96, 266), (96, 210), (104, 150), (122, 102)]
    s.myocardium(mass, [(lv, "L")])
    s.blob([(98, 208), (112, 200), (122, 214), (112, 226)])
    s.blob([(202, 196), (188, 192), (178, 206), (190, 220)])
    for tip, ends in (((120, 212), ((128, 246), (140, 250))), ((180, 204), ((168, 246), (158, 250)))):
        for e in ends:
            s.chordae(tip, e)
    s.leaflet([(98, 270), (112, 250), (126, 236)]); s.leaflet([(202, 270), (188, 250), (174, 236)])
    s.big_label(150, 180, "LV", "L"); s.big_label(150, 330, "LA", "L")
    s.label("Inferior", 18, 160, (78, 176))
    s.label("Anterior", 250, 140, (222, 168))
    s.label("LAA", 262, 300, (232, 304))
    # --- A3C (right panel): inferolateral wall left, anteroseptal wall right, LVOT/aorta right
    s.chamber_wall([(380, 288), (420, 278), (456, 290), (462, 326), (444, 360), (404, 366), (372, 348), (366, 316)], "L", 1.07)
    s.path(smooth([(470, 266), (496, 276), (528, 300), (548, 334), (560, 380), (540, 386), (518, 344), (494, 314),
                   (468, 300), (458, 282)]), fill="url(#aortaWall)", stroke="#E58A97", sw=1)
    s.path(smooth([(470, 274), (492, 284), (520, 306), (538, 338), (550, 380), (544, 382), (526, 344), (500, 316),
                   (472, 296), (464, 284)]), fill="url(#bloodL)")
    mass3 = [(450, 52), (490, 74), (516, 128), (526, 196), (520, 264), (470, 274), (414, 278), (374, 274),
             (372, 200), (384, 130), (410, 74)]
    lv3 = [(450, 82), (478, 102), (494, 150), (500, 210), (488, 260), (462, 276), (396, 268), (396, 210), (404, 150), (422, 102)]
    s.myocardium(mass3, [(lv3, "L")])
    s.leaflet([(398, 272), (412, 252), (426, 238)]); s.leaflet([(462, 272), (450, 252), (440, 238)])
    s.leaflet([(472, 286), (482, 296), (488, 300)], 2.6); s.leaflet([(468, 300), (480, 300), (488, 300)], 2.6)
    s.big_label(448, 186, "LV", "L"); s.big_label(414, 330, "LA", "L"); s.big_label(548, 412, "Ao", "L")
    s.label("Inferolateral", 300, 196, (380, 204))
    s.label("Anteroseptal", 560, 150, (518, 170), anchor="end")
    s.label("LVOT · AV", 560, 248, (480, 288), anchor="end")
    s.notch(a1); s.notch(a2)
    s.compass(36, 48, "I", "S", "I", "A", "A2C", r=22)
    s.compass(564, 48, "I", "S", "P", "A", "A3C", r=22)
    return s


def plax() -> Svg:
    s = Svg("Parasternal Long Axis")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    # chest wall / pericardium near field
    s.tissue([(0, 22), (600, 22), (600, 104), (300, 92), (0, 104)], fill="#2A2026")
    # descending aorta behind the AV groove
    s.vessel([(486, 336), (486, 340)], 30, "L", 4)
    # left atrium
    s.chamber_wall([(392, 262), (440, 252), (494, 262), (518, 290), (500, 316), (446, 322), (400, 306), (382, 284)], "L", 1.05)
    # aortic root: sinus bulge, ascending aorta
    s.path(smooth([(372, 156), (420, 140), (460, 146), (520, 140), (600, 140), (600, 228), (520, 226), (460, 232),
                   (420, 242), (374, 228)]), fill="url(#aortaWall)", stroke="#E58A97", sw=1)
    s.path(smooth([(378, 166), (420, 150), (460, 156), (520, 150), (600, 150), (600, 218), (520, 216), (460, 222),
                   (420, 232), (380, 220)]), fill="url(#bloodL)")
    # RV/RVOT, septum, LV, posterior wall as one myocardial mass
    mass = [(0, 112), (180, 104), (360, 110), (400, 132), (376, 160), (380, 226), (372, 252), (330, 286),
            (200, 292), (60, 290), (0, 286)]
    rv = [(0, 120), (180, 114), (352, 118), (382, 136), (360, 150), (200, 150), (0, 150)]
    lv = [(0, 170), (180, 168), (330, 172), (366, 186), (366, 230), (322, 262), (200, 266), (60, 264), (0, 262)]
    s.myocardium(mass, [(rv, "R"), (lv, "L")])
    s.pericardium([(0, 300), (200, 302), (340, 298), (384, 318), (440, 334)])
    # papillary muscle on the posterior wall with chordae
    s.blob([(200, 266), (214, 248), (232, 246), (240, 262)])
    for e in ((300, 226), (312, 238)):
        s.chordae((228, 250), e)
    # mitral valve (AML from aortic root, PML from posterior annulus) and aortic valve (closed)
    s.leaflet([(372, 228), (346, 216), (318, 214)])
    s.leaflet([(372, 254), (348, 244), (322, 236)])
    s.leaflet([(392, 154), (404, 172), (410, 188)], 2.8); s.leaflet([(392, 226), (404, 206), (410, 190)], 2.8)
    # coronary sinus in the AV groove
    s.vessel([(374, 290), (376, 290)], 12, "R", 2)
    s.big_label(190, 222, "LV", "L"); s.big_label(220, 140, "RVOT", "R")
    s.big_label(452, 292, "LA", "L"); s.big_label(540, 192, "Ao", "L")
    s.label("IVS", 120, 196, (236, 160))
    s.label("Posterior wall", 70, 312, (120, 278))
    s.label("Anterior mitral leaflet", 150, 352, (340, 216))
    s.label("Aortic valve (RCC / NCC)", 600, 146, (408, 186), anchor="end")
    s.label("Sinus of Valsalva", 600, 254, (458, 228), anchor="end")
    s.label("Coronary sinus", 300, 356, (374, 290))
    s.label("Descending aorta", 600, 396, (486, 352), anchor="end")
    s.label("Pericardium", 470, 372, (430, 330))
    s.color_box(apex, 170, 300, 6, 24, "Color · MV / AV", (430, 108))
    s.mmode(apex, (300, 300), "M-mode", (306, 330))
    s.notch(apex)
    s.compass(44, 46, "A", "P", "I·L", "S·R", "PLAX", r=28)
    return s


def psax_av() -> Svg:
    s = Svg("PSAX – Aortic Valve & Great Vessels")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    # RA, LA, interatrial septum
    s.chamber_wall([(196, 262), (232, 252), (262, 270), (266, 310), (240, 338), (196, 342), (166, 318), (166, 284)], "R", 1.06)
    s.chamber_wall([(264, 300), (304, 284), (352, 290), (382, 314), (372, 350), (318, 364), (270, 352), (256, 326)], "L", 1.06)
    # RV inflow / RVOT wrapping anteriorly, pulmonary valve and MPA with bifurcation
    s.path(smooth([(148, 248), (150, 190), (186, 132), (250, 104), (330, 100), (396, 114), (436, 140), (444, 168),
                   (420, 176), (392, 156), (330, 140), (262, 146), (214, 172), (192, 214), (190, 252)]),
           fill="url(#myo)", stroke="#E58A97", sw=1.2)
    s.path(smooth([(160, 244), (164, 196), (196, 146), (254, 118), (330, 114), (392, 128), (428, 150), (420, 164),
                   (390, 146), (330, 130), (260, 136), (208, 162), (182, 206), (178, 246)]), fill="url(#bloodR)")
    s.vessel([(430, 158), (452, 190), (466, 226)], 34, "R", 4)
    s.vessel([(466, 226), (440, 262), (404, 280)], 20, "R", 3)   # RPA courses behind the aorta
    s.vessel([(466, 226), (494, 262), (514, 300)], 20, "R", 3)   # LPA
    s.leaflet([(418, 150), (430, 162), (438, 172)], 2.6); s.leaflet([(444, 146), (442, 160), (438, 172)], 2.6)
    # tricuspid valve
    s.leaflet([(170, 250), (182, 236), (192, 226)]); s.leaflet([(190, 262), (196, 244), (198, 230)])
    # aortic root: three sinuses and closed cusps ("Mercedes" sign)
    s.path(smooth(ellipse_pts(300, 214, 52, 50, 12, wobble=0.06)), fill="url(#aortaWall)", stroke="#E58A97", sw=1)
    s.path(smooth(ellipse_pts(300, 214, 44, 42, 12, wobble=0.06)), fill="url(#bloodL)")
    for ang in (-90, 30, 150):
        a = math.radians(ang)
        s.leaflet([(300 + 44 * math.cos(a), 214 + 42 * math.sin(a)),
                   (300 + 20 * math.cos(a), 214 + 19 * math.sin(a)), (300, 214)], 2.8)
    # coronary ostia: left main ≈ 4 o'clock, RCA ≈ 10–11 o'clock
    s.vessel([(340, 238), (366, 252)], 7, "L", 1.5)
    s.vessel([(270, 182), (250, 168)], 7, "L", 1.5)
    s.big_label(208, 304, "RA", "R"); s.big_label(320, 332, "LA", "L"); s.big_label(300, 136, "RVOT", "R")
    s.big_label(470, 196, "MPA", "R")
    s.label("RCC", 300, 196, (300, 188), anchor="middle")
    s.label("NCC", 262, 238, (282, 228), anchor="end")
    s.label("LCC", 338, 238, (318, 228))
    s.label("Left main", 380, 270, (360, 248))
    s.label("RCA", 214, 160, (254, 170), anchor="end")
    s.label("Pulmonary valve", 480, 120, (436, 160))
    s.label("RPA", 404, 302, (420, 272))
    s.label("LPA", 540, 320, (508, 290))
    s.label("Tricuspid valve", 88, 228, (176, 242))
    s.label("Interatrial septum", 160, 384, (262, 330))
    s.pw(apex, (434, 168), "PW · PV", (330, 72))
    s.color_box(apex, 230, 310, 24, 40, "Color · PDA", (560, 360), anchor="end")
    s.notch(apex)
    s.compass(44, 46, "A", "P", "R", "L", "PSAX AV", r=26)
    return s


def psax_papillary() -> Svg:
    s = Svg("PSAX – Papillary Muscle Level")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    s.pericardium([(240, 362), (330, 374), (420, 352), (452, 316)])
    # RV crescent wrapping the septum
    s.path(smooth([(214, 312), (166, 262), (158, 196), (186, 138), (244, 106), (300, 110), (262, 136),
                   (232, 186), (226, 248), (244, 300)]), fill="url(#myo)", stroke="#E58A97", sw=1.2)
    s.path(smooth([(224, 298), (180, 258), (172, 198), (198, 146), (250, 120), (276, 122), (248, 150),
                   (220, 196), (216, 250), (234, 292)]), fill="url(#bloodR)")
    s.trabeculae([(186, 196), (198, 192), (184, 236), (198, 232)])
    # LV: thick circular myocardium
    s.path(smooth(ellipse_pts(320, 232, 108, 106, 14, wobble=0.02)), fill="url(#myo)", stroke="#E58A97", sw=1.2)
    s.path(smooth(ellipse_pts(320, 232, 76, 74, 14, wobble=0.03)), fill="url(#bloodL)")
    # papillary muscles: anterolateral ≈ 4 o'clock, posteromedial ≈ 8 o'clock
    s.blob(ellipse_pts(370, 270, 17, 12, 8, rot=-35))
    s.blob(ellipse_pts(268, 276, 17, 12, 8, rot=35))
    # segment dividers (mid-ventricular, 6 segments)
    for ang in (-120, -60, 0, 60, 120, 180):
        a = math.radians(ang - 90)
        x1, y1 = 320 + 78 * math.cos(a), 232 + 76 * math.sin(a)
        x2, y2 = 320 + 110 * math.cos(a), 232 + 108 * math.sin(a)
        s.add(f'<line x1="{f(x1)}" y1="{f(y1)}" x2="{f(x2)}" y2="{f(y2)}" stroke="#F4E1E5" stroke-width="1" '
              f'stroke-dasharray="3,3" stroke-opacity="0.7"/>')
    s.big_label(320, 238, "LV", "L"); s.big_label(196, 210, "RV", "R")
    for name, ang in (("Anterior", -90), ("Anterolateral", -30), ("Inferolateral", 30),
                      ("Inferior", 90), ("Inferoseptal", 150), ("Anteroseptal", 210)):
        a = math.radians(ang)
        x, y = 320 + 136 * math.cos(a), 232 + 132 * math.sin(a)
        anchor = "middle" if abs(math.cos(a)) < 0.3 else ("start" if math.cos(a) > 0 else "end")
        s.o(f'<text x="{f(x)}" y="{f(y + 4)}" font-family="sans-serif" font-size="11.5" fill="{MUTED}" '
            f'text-anchor="{anchor}">{name}</text>')
    s.label("Anterolateral PM", 470, 316, (380, 276))
    s.label("Posteromedial PM", 120, 352, (262, 282))
    s.label("Septum", 110, 120, (236, 170), anchor="start")
    s.mmode(apex, (320, 344), "M-mode (FS)", (334, 394))
    s.notch(apex)
    s.compass(44, 46, "A", "P", "R", "L", "PSAX PM", r=26)
    return s


def subcostal_4c() -> Svg:
    s = Svg("Subcostal 4-Chamber")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    s.tissue([(0, 20), (600, 20), (600, 128), (430, 116), (300, 110), (170, 122), (0, 140)])
    s.o(f'<text x="300" y="80" font-family="sans-serif" font-size="13" fill="#C8A995" text-anchor="middle">Liver</text>')
    # A4C geometry rotated 90° clockwise: apex → right, RV → near field, atria → left
    cx, cy = 300, 230

    def tf(x, y, k=0.82, ox=316, oy=244):
        dx, dy = x - cx, y - cy
        return ox + (-dy) * k, oy + dx * k

    four_chamber(s, tf)
    s.pericardium([tf(176, 240), tf(196, 160), tf(232, 104), tf(300, 52), tf(370, 66)])
    s.big_label(*tf(250, 214), "RV", "R"); s.big_label(*tf(356, 200), "LV", "L")
    s.big_label(*tf(240, 324), "RA", "R"); s.big_label(*tf(362, 326), "LA", "L")
    s.label("Interatrial septum", 30, 372, tf(300, 320))
    s.label("⟂ to the beam", 30, 386, tf(300, 320))
    s.label("Pericardium", 470, 150, tf(214, 150))
    s.label("Tricuspid valve", 110, 168, tf(206, 244), anchor="middle")
    s.label("Mitral valve", 200, 404, tf(392, 246), anchor="middle")
    s.color_box(apex, 250, 330, -6, -26, "Color · IAS", (40, 330))
    s.notch(apex)
    s.compass(44, 46, "I", "S", "R", "L", "Subcostal", r=26)
    return s


def subcostal_ivc() -> Svg:
    s = Svg("Subcostal IVC & Bicaval")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    s.tissue([(0, 20), (600, 20), (600, 300), (430, 316), (300, 330), (160, 352), (0, 360)])
    s.o(f'<text x="200" y="120" font-family="sans-serif" font-size="13" fill="#C8A995" text-anchor="middle">Liver</text>')
    s.pericardium([(330, 352), (420, 326), (500, 300), (600, 280)])   # diaphragm
    s.vessel([(30, 300), (160, 286), (300, 270), (420, 252)], 40, "R", 3)
    s.vessel([(206, 160), (240, 214), (290, 262)], 18, "R", 2.5)      # hepatic vein
    s.chamber_wall([(430, 214), (476, 196), (526, 206), (552, 240), (540, 280), (494, 296), (446, 286), (424, 252)], "R", 1.06)
    s.vessel([(452, 150), (458, 180), (466, 206)], 26, "R", 3)        # SVC (bicaval tilt)
    s.leaflet([(428, 270), (444, 262), (452, 250)], 2.8)               # Eustachian valve
    s.big_label(110, 296, "IVC", "R"); s.big_label(492, 250, "RA", "R"); s.big_label(430, 156, "SVC", "R")
    s.label("Hepatic vein", 120, 188, (226, 196), anchor="end")
    s.label("Eustachian valve", 352, 380, (446, 258))
    s.label("Diaphragm", 560, 330, (520, 296), anchor="end")
    s.mmode(apex, (380, 300), "M-mode · 1–2 cm from RA", (420, 236), anchor="end")
    s.pw(apex, (236, 210), "PW · hepatic vein", (60, 230))
    s.notch(apex)
    s.compass(44, 46, "A", "P", "I", "S", "IVC", r=26)
    return s


def suprasternal_arch() -> Svg:
    s = Svg("Suprasternal Aortic Arch")
    apex = (300, 22)
    s.sector(apex, 400, 46)
    s.vessel([(150, 104), (300, 98), (450, 108)], 22, "R", 3)                 # left brachiocephalic vein
    s.vessel([(170, 420), (170, 300), (186, 210), (236, 162), (300, 150), (364, 164), (408, 214), (424, 300), (426, 420)],
             50, "L", 6)
    s.vessel([(240, 156), (226, 60)], 18, "L", 3)                              # brachiocephalic trunk
    s.vessel([(296, 150), (296, 50)], 15, "L", 3)                              # left common carotid
    s.vessel([(356, 160), (378, 60)], 15, "L", 3)                              # left subclavian
    s.vessel([(300, 268), (300, 270)], 46, "R", 4)                             # RPA in cross-section
    s.big_label(150, 360, "AAo", "L"); s.big_label(300, 196, "Arch", "L"); s.big_label(470, 362, "DAo", "L")
    s.big_label(300, 276, "RPA", "R")
    s.label("Brachiocephalic trunk", 96, 100, (228, 84))
    s.label("Left common carotid", 296, 40, (296, 66), anchor="middle")
    s.label("Left subclavian", 530, 60, (372, 86), anchor="end")
    s.label("Left brachiocephalic vein", 530, 132, (420, 108), anchor="end")
    s.label("Isthmus", 530, 186, (412, 228), anchor="end")
    s.color_box(apex, 200, 290, 20, 34, "Color · isthmus", (470, 270))
    s.cw(apex, (432, 392), (420, 300), "CW · DAo", (520, 404), anchor="middle")
    # crab view inset
    s.o('<rect x="12" y="300" width="128" height="108" rx="8" fill="#0F1620" stroke="#2C3F5E"/>')
    s.o(f'<text x="76" y="318" font-family="sans-serif" font-size="11" font-weight="bold" fill="{TEAL}" '
        f'text-anchor="middle">Crab view (notch 3h)</text>')
    for (x1, y1, x2, y2) in ((60, 350, 30, 334), (60, 374, 30, 392), (92, 350, 122, 334), (92, 374, 122, 392)):
        s.o(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#C9707E" stroke-width="9" stroke-linecap="round"/>')
        s.o(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#2B0A11" stroke-width="5" stroke-linecap="round"/>')
    s.o('<ellipse cx="76" cy="362" rx="28" ry="20" fill="#5E1824" stroke="#C9707E" stroke-width="3"/>')
    s.o('<text x="76" y="367" font-family="sans-serif" font-size="12" font-weight="bold" fill="#FFB4BE" '
        'text-anchor="middle">LA</text>')
    s.o(f'<text x="76" y="404" font-family="sans-serif" font-size="9.5" fill="{MUTED}" '
        f'text-anchor="middle">4 pulmonary veins</text>')
    s.notch(apex)
    s.compass(44, 46, "S", "I", "R·A", "L·P", "SSN arch", r=28)
    return s


VIEWS = {
    "apical_4c": apical_4c,
    "apical_5c": apical_5c,
    "apical_2c_3c": apical_2c_3c,
    "plax_standard": plax,
    "psax_av": psax_av,
    "psax_papillary": psax_papillary,
    "subcostal_4c": subcostal_4c,
    "subcostal_ivc": subcostal_ivc,
    "suprasternal_arch": suprasternal_arch,
}


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "main", "assets", "svg")
    os.makedirs(out_dir, exist_ok=True)
    for name, build in VIEWS.items():
        svg = build()
        if name not in ("apical_2c_3c", "suprasternal_arch"):
            svg.legend()
        with open(os.path.join(out_dir, f"{name}.svg"), "w", encoding="utf-8") as fh:
            fh.write(svg.render())
    print(f"wrote {len(VIEWS)} SVGs to {os.path.relpath(out_dir)}")


if __name__ == "__main__":
    main()
