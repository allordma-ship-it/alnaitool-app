#!/usr/bin/env python3
"""
EchoScope – simulated B-mode cine-loop generator.

Creates schematic, clearly labelled *simulated* echocardiography loops for the
nine standard TTE views so the app works out of the box without any patient
data. Real, anonymised clips can replace them at any time (see
app/src/main/assets/loops/README_LOOPS.txt).

Pipeline:
  1. Render frames (speckle texture, beating chambers, valve motion, ECG) into
     raw_loops/<view_id>.mp4 at 960x780. The top 60 px contain a dummy
     "header" bar so scripts/prepare_loops.sh exercises its crop step.
  2. Run scripts/prepare_loops.sh to crop, scale to 720 px, strip audio and
     encode the final H.264 assets.

Requirements: python3, numpy, Pillow, ffmpeg.
Usage: python3 scripts/simulate_loops.py [--skip-prepare]
"""

from __future__ import annotations

import math
import zlib
import os
import subprocess
import sys
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RAW_DIR = os.path.join(ROOT, "raw_loops")

W, H = 960, 780          # raw frame size (top 60 px = dummy header)
HEADER = 60
S = 2.4                  # SVG unit (400x300 design space) -> pixels
FPS = 30
FRAMES_PER_BEAT = 25     # 72 bpm at 30 fps
APEX = (200.0, 22.0)     # sector apex in design units
RADIUS = 268.0
HALF_ANGLE = math.radians(40)

TISSUE = 38              # generic soft-tissue reflectivity
MYO = 165                # myocardium
BRIGHT = 235             # pericardium / valves
BLOOD = 4


def px(p):
    return (p[0] * S, p[1] * S + HEADER)


# ---------------------------------------------------------------------------
# Cardiac timing (t in [0, 1) = one beat, R-wave at t = 0)
# ---------------------------------------------------------------------------

def smoothstep(a, b, x):
    if x <= a:
        return 0.0
    if x >= b:
        return 1.0
    u = (x - a) / (b - a)
    return u * u * (3 - 2 * u)


def contraction(t):
    """0 = end-diastole, 1 = end-systole (ventricles)."""
    if t < 0.06:
        return 0.0
    if t < 0.36:
        return smoothstep(0.06, 0.36, t)
    if t < 0.44:
        return 1.0
    if t < 0.62:
        return 1.0 - 0.88 * smoothstep(0.44, 0.62, t)
    if t < 0.85:
        return 0.12 - 0.04 * smoothstep(0.62, 0.85, t)
    return 0.08 * (1.0 - smoothstep(0.85, 0.97, t))


def atrial_kick(t):
    return math.sin(math.pi * (t - 0.82) / 0.14) if 0.82 <= t <= 0.96 else 0.0


def av_valve_open(t):
    """Atrioventricular valves: E-wave opening, partial closure in diastasis, A-wave."""
    e = smoothstep(0.44, 0.50, t) * (1.0 - 0.55 * smoothstep(0.58, 0.70, t))
    a = 0.45 * atrial_kick(t)
    closed = 1.0 - smoothstep(0.95, 1.0, t)
    return max(0.0, min(1.0, (e + a) * closed))


def sl_valve_open(t):
    """Semilunar valves: open during ejection."""
    return smoothstep(0.07, 0.11, t) * (1.0 - smoothstep(0.33, 0.37, t))


def ecg(t):
    v = 0.0
    v += 0.12 * math.exp(-((t - 0.88) / 0.025) ** 2)          # P
    v -= 0.10 * math.exp(-((t + 0.02) / 0.008) ** 2)          # Q
    v += 1.00 * math.exp(-((t - 0.00) / 0.010) ** 2)          # R
    v += 1.00 * math.exp(-((t - 1.00) / 0.010) ** 2)          # R (wrap)
    v -= 0.20 * math.exp(-((t - 0.025) / 0.010) ** 2)         # S
    v += 0.25 * math.exp(-((t - 0.30) / 0.045) ** 2)          # T
    return v


# ---------------------------------------------------------------------------
# Scene description
# ---------------------------------------------------------------------------

@dataclass
class Shape:
    kind: str                       # ellipse | poly | tube | blob
    pts: list                       # ellipse: [(cx, cy), (rx, ry)]; poly/tube: points
    group: str = "S"                # V (ventricle), A (atrium), S (static), P (pulsatile vessel)
    anchor: tuple | None = None     # scaling origin
    wall: float = 3.0               # wall thickness (design units)
    width: float = 0.0              # tube width
    level: int = MYO                # wall reflectivity


@dataclass
class Valve:
    p1: tuple
    p2: tuple
    direction: tuple                # unit-ish vector the leaflets swing into when open
    kind: str = "AV"                # AV | SL | TRI (aortic valve en face)
    anchor_group: str = "S"


@dataclass
class Scene:
    label: str
    shapes: list
    valves: list = field(default_factory=list)
    tissue_regions: list = field(default_factory=list)   # (poly, level)
    bright_lines: list = field(default_factory=list)     # polylines (pericardium)


def scale_point(p, anchor, k):
    return (anchor[0] + (p[0] - anchor[0]) * k, anchor[1] + (p[1] - anchor[1]) * k)


def group_scale(group, t):
    c = contraction(t)
    if group == "V":
        return 1.0 - 0.17 * c
    if group == "A":
        return 0.93 + 0.11 * c - 0.07 * atrial_kick(t)
    if group == "P":
        return 1.0 + 0.05 * c
    return 1.0


def shape_geometry(sh: Shape, t):
    k = group_scale(sh.group, t)
    if sh.kind in ("ellipse", "blob"):
        (cx, cy), (rx, ry) = sh.pts
        anchor = sh.anchor or (cx, cy)
        ncx, ncy = scale_point((cx, cy), anchor, k)
        return ("ellipse", (ncx, ncy, rx * k, ry * k))
    anchor = sh.anchor
    if anchor is None:
        xs = [p[0] for p in sh.pts]
        ys = [p[1] for p in sh.pts]
        anchor = (sum(xs) / len(xs), sum(ys) / len(ys))
    pts = [scale_point(p, anchor, k) for p in sh.pts]
    width = sh.width * (k if sh.group == "P" else 1.0)
    return (sh.kind, (pts, width))


def draw_shape(draw, geom, grow, fill):
    kind, data = geom
    if kind == "ellipse":
        cx, cy, rx, ry = data
        x0, y0 = px((cx - rx - grow, cy - ry - grow))
        x1, y1 = px((cx + rx + grow, cy + ry + grow))
        draw.ellipse([x0, y0, x1, y1], fill=fill)
    elif kind == "poly":
        pts, _ = data
        poly = [px(p) for p in pts]
        draw.polygon(poly, fill=fill)
        if grow > 0:
            draw.line(poly + [poly[0]], fill=fill, width=int(2 * grow * S), joint="curve")
    elif kind == "tube":
        # Stamp discs along the centre line: robust round joints for any bend.
        pts, width = data
        r = (width / 2 + grow) * S
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            steps = max(1, int(math.dist((x0, y0), (x1, y1)) / 1.5))
            for i in range(steps + 1):
                u = i / steps
                x, y = px((x0 + (x1 - x0) * u, y0 + (y1 - y0) * u))
                draw.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def draw_valves(draw, scene: Scene, t):
    for v in scene.valves:
        if v.kind == "TRI":
            (cx, cy), r = v.p1, v.p2[0]
            o = sl_valve_open(t)
            for ang in (-90, 30, 150):
                a = math.radians(ang)
                comm = (cx + r * math.cos(a), cy + r * math.sin(a))
                # closed: commissure -> centre ("Y"); open: leaflets retract toward the wall
                mid_a = math.radians(ang + 60)
                tip_closed = (cx, cy)
                tip_open = (cx + 0.82 * r * math.cos(mid_a), cy + 0.82 * r * math.sin(mid_a))
                tip = (tip_closed[0] + (tip_open[0] - tip_closed[0]) * o,
                       tip_closed[1] + (tip_open[1] - tip_closed[1]) * o)
                draw.line([px(comm), px(tip)], fill=BRIGHT, width=int(1.6 * S))
            continue
        o = av_valve_open(t) if v.kind == "AV" else sl_valve_open(t)
        mid = ((v.p1[0] + v.p2[0]) / 2, (v.p1[1] + v.p2[1]) / 2)
        half = math.dist(v.p1, v.p2) / 2
        dn = math.hypot(*v.direction) or 1.0
        d = (v.direction[0] / dn, v.direction[1] / dn)
        for hinge in (v.p1, v.p2):
            closed_tip = (mid[0] + (hinge[0] - mid[0]) * 0.08, mid[1] + (hinge[1] - mid[1]) * 0.08)
            open_tip = (hinge[0] + d[0] * half * 0.95 + (mid[0] - hinge[0]) * 0.12,
                        hinge[1] + d[1] * half * 0.95 + (mid[1] - hinge[1]) * 0.12)
            tip = (closed_tip[0] + (open_tip[0] - closed_tip[0]) * o,
                   closed_tip[1] + (open_tip[1] - closed_tip[1]) * o)
            draw.line([px(hinge), px(tip)], fill=BRIGHT, width=int(1.5 * S))


# ---------------------------------------------------------------------------
# Static image layers
# ---------------------------------------------------------------------------

def sector_mask():
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    ax, ay = px(APEX)
    dx, dy = xx - ax, yy - ay
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.arctan2(dx, dy)
    inside = (r <= RADIUS * S) & (np.abs(ang) <= HALF_ANGLE) & (dy > 0) & (yy >= HEADER)
    depth = np.clip(r / (RADIUS * S), 0, 1)
    return inside, depth


def speckle_field(seed):
    rng = np.random.default_rng(seed)
    coarse = rng.rayleigh(scale=1.0, size=(H // 3, W // 7)).astype(np.float32)
    img = Image.fromarray(np.clip(coarse * 80, 0, 255).astype(np.uint8)).resize((W, H), Image.BILINEAR)
    fine = rng.rayleigh(scale=1.0, size=(H, W)).astype(np.float32)
    field_ = np.asarray(img, dtype=np.float32) / 80.0 * 0.7 + fine * 0.3
    return field_ / field_.mean()


try:
    FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
    FONT_S = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 17)
except OSError:  # pragma: no cover - fallback when DejaVu is unavailable
    FONT = ImageFont.load_default()
    FONT_S = ImageFont.load_default()


def render_frame(scene: Scene, t, inside, depth, speckle, frame_idx):
    refl = Image.new("L", (W, H), TISSUE)
    d = ImageDraw.Draw(refl)
    for poly, level in scene.tissue_regions:
        d.polygon([px(p) for p in poly], fill=level)
    for line in scene.bright_lines:
        d.line([px(p) for p in line], fill=BRIGHT, width=int(2.2 * S), joint="curve")

    geoms = [(sh, shape_geometry(sh, t)) for sh in scene.shapes]
    for sh, g in geoms:                       # walls first
        if sh.kind != "blob":
            draw_shape(d, g, sh.wall, sh.level)
    for sh, g in geoms:                       # blood pools
        if sh.kind != "blob":
            draw_shape(d, g, 0, BLOOD)
    for sh, g in geoms:                       # papillary muscles etc.
        if sh.kind == "blob":
            draw_shape(d, g, 0, MYO)
    draw_valves(d, scene, t)

    refl = refl.filter(ImageFilter.GaussianBlur(2.2))
    r = np.asarray(refl, dtype=np.float32)

    jitter = np.roll(speckle, shift=(frame_idx % 3) - 1, axis=1)
    img = r * (0.8 * speckle + 0.2 * jitter)
    tgc = 1.05 - 0.35 * depth
    img = 255.0 * np.power(np.clip(img * tgc / 255.0, 0, 1), 0.85)
    img[~inside] = 0
    rgb = np.stack([img, img, img * 1.03], axis=-1).clip(0, 255).astype(np.uint8)
    out = Image.fromarray(rgb, "RGB")

    od = ImageDraw.Draw(out)
    # Dummy header (removed by prepare_loops.sh crop=in_w:in_h-60:0:60)
    od.rectangle([0, 0, W, HEADER - 1], fill=(28, 36, 52))
    od.text((16, 18), "DEMO HEADER - REMOVED BY CROP - NO PATIENT DATA", font=FONT, fill=(200, 210, 225))

    # Orientation marker, depth scale, labels
    ax, ay = px(APEX)
    od.ellipse([ax + 52, ay - 2, ax + 66, ay + 12], fill=(255, 138, 61))
    for cm in range(1, 17):
        y = ay + cm * (RADIUS * S / 16)
        if y < H - 10:
            od.line([W - 34, y, W - 24 if cm % 5 else W - 16, y], fill=(150, 160, 175), width=2)
    od.text((18, HEADER + 14), scene.label, font=FONT, fill=(0, 210, 184))
    od.text((18, HEADER + 44), "SIMULATED LOOP - EDUCATIONAL", font=FONT_S, fill=(255, 138, 61))
    od.text((W - 150, HEADER + 14), "HR 72 bpm", font=FONT_S, fill=(0, 210, 184))

    # ECG sweep
    base_y = H - 40
    pts = []
    for i in range(0, W - 60, 3):
        tt = (i / (W - 60)) * 2.0
        pts.append((30 + i, base_y - 34 * ecg(tt % 1.0)))
    od.line(pts, fill=(52, 211, 153), width=2)
    cursor = 30 + (W - 60) * ((frame_idx % (2 * FRAMES_PER_BEAT)) / (2 * FRAMES_PER_BEAT))
    od.line([cursor, base_y - 44, cursor, base_y + 10], fill=(251, 191, 36), width=2)
    return out


# ---------------------------------------------------------------------------
# View geometry (design units, 400x300, apex at top)
# ---------------------------------------------------------------------------

def scenes():
    liver = lambda: [([(60, 38), (340, 38), (330, 100), (200, 82), (70, 104)], 80)]
    return {
        "subcostal_4c": [Scene(
            "SUBCOSTAL 4C",
            tissue_regions=liver(),
            bright_lines=[[(96, 104), (200, 84), (308, 104)]],
            shapes=[
                Shape("ellipse", [(150, 128), (42, 28)], "A", (196, 128), 3),
                Shape("ellipse", [(245, 122), (48, 30)], "V", (196, 125), 5),
                Shape("ellipse", [(148, 196), (40, 27)], "A", (197, 198), 3),
                Shape("ellipse", [(252, 196), (52, 32)], "V", (197, 198), 9),
            ],
            valves=[Valve((196, 106), (198, 146), (1, 0)), Valve((196, 178), (198, 216), (1, 0))],
        )],
        "subcostal_ivc": [Scene(
            "SUBCOSTAL IVC",
            tissue_regions=[([(40, 40), (360, 40), (360, 150), (40, 150)], 80)],
            shapes=[
                Shape("tube", [(40, 208), (150, 192), (262, 176)], "P", None, 2, width=30),
                Shape("tube", [(150, 100), (166, 140), (186, 176)], "P", None, 1.5, width=13),
                Shape("ellipse", [(300, 162), (46, 40)], "A", (262, 176), 3),
            ],
            valves=[Valve((268, 190), (282, 168), (0.4, -0.9), "AV")],
        )],
        "plax_standard": [Scene(
            "PLAX",
            bright_lines=[[(50, 202), (240, 198), (300, 236)]],
            shapes=[
                Shape("poly", [(80, 64), (330, 62), (338, 90), (72, 96)], "V", (200, 96), 4),
                Shape("poly", [(62, 116), (232, 112), (246, 146), (228, 180), (64, 186), (48, 150)], "V", (240, 150), 9),
                Shape("poly", [(252, 102), (360, 98), (362, 146), (254, 150)], "P", None, 4),
                Shape("ellipse", [(306, 198), (46, 30)], "A", (250, 165), 3),
                Shape("ellipse", [(330, 258), (13, 13)], "P", None, 3),
            ],
            valves=[
                Valve((250, 150), (236, 188), (-1, 0.05), "AV"),
                Valve((258, 104), (258, 148), (1, 0), "SL"),
            ],
        )],
        "psax_av": [Scene(
            "PSAX AV LEVEL",
            shapes=[
                Shape("poly", [(70, 150), (84, 96), (130, 64), (200, 58), (270, 62), (312, 92), (290, 112),
                               (260, 96), (200, 94), (140, 100), (112, 124), (110, 150)], "V", None, 4),
                Shape("ellipse", [(110, 196), (40, 30)], "A", None, 3),
                Shape("ellipse", [(208, 230), (52, 25)], "A", None, 3),
                Shape("tube", [(306, 108), (330, 140), (334, 176)], "P", None, 3, width=26),
                Shape("tube", [(334, 176), (320, 222)], "P", None, 2, width=12),
                Shape("tube", [(334, 176), (364, 208)], "P", None, 2, width=12),
                Shape("ellipse", [(200, 150), (38, 38)], "S", None, 4),
            ],
            valves=[
                Valve((200, 150), (38,), (0, 0), "TRI"),
                Valve((292, 98), (312, 116), (0.7, 0.4), "SL"),
                Valve((116, 150), (140, 168), (0.6, -0.8), "AV"),
            ],
        )],
        "psax_papillary": [Scene(
            "PSAX PAPILLARY",
            bright_lines=[[(150, 236), (220, 244), (290, 232)]],
            shapes=[
                Shape("poly", [(92, 140), (102, 100), (140, 78), (196, 72), (165, 100), (152, 150),
                               (160, 200), (170, 230), (120, 212), (96, 180)], "V", (150, 150), 4),
                Shape("ellipse", [(220, 160), (64, 64)], "V", (220, 160), 10),
                Shape("blob", [(262, 186), (13, 10)], "V", (220, 160)),
                Shape("blob", [(180, 196), (13, 10)], "V", (220, 160)),
            ],
        )],
        "apical_4c": [Scene(
            "APICAL 4C",
            shapes=[
                Shape("poly", [(196, 46), (170, 64), (146, 100), (128, 140), (120, 168), (192, 166)], "V", (156, 168), 4),
                Shape("poly", [(204, 46), (240, 58), (270, 90), (288, 140), (294, 176), (208, 178)], "V", (250, 178), 8),
                Shape("ellipse", [(150, 222), (44, 34)], "A", (156, 176), 3),
                Shape("ellipse", [(252, 224), (46, 34)], "A", (250, 182), 3),
                Shape("tube", [(232, 252), (222, 284)], "S", None, 1, width=7),
                Shape("tube", [(272, 252), (284, 284)], "S", None, 1, width=7),
            ],
            valves=[Valve((122, 168), (192, 168), (0, -1)), Valve((208, 180), (292, 180), (0, -1))],
        )],
        "apical_5c": [Scene(
            "APICAL 5C",
            shapes=[
                Shape("poly", [(196, 46), (150, 80), (116, 166), (176, 164)], "V", (146, 166), 4),
                Shape("poly", [(204, 46), (262, 70), (300, 176), (226, 178)], "V", (262, 178), 8),
                Shape("tube", [(203, 176), (203, 262)], "P", None, 3, width=34),
                Shape("ellipse", [(132, 222), (40, 32)], "A", (146, 172), 3),
                Shape("ellipse", [(274, 226), (40, 32)], "A", (262, 184), 3),
            ],
            valves=[
                Valve((122, 168), (176, 168), (0, -1)),
                Valve((228, 182), (296, 182), (0, -1)),
                Valve((187, 186), (219, 186), (0, 1), "SL"),
            ],
        )],
        "apical_2c_3c": [
            Scene(
                "APICAL 2C",
                shapes=[
                    Shape("poly", [(200, 44), (160, 80), (140, 130), (136, 186), (266, 186), (262, 130), (242, 80)],
                          "V", (200, 186), 8),
                    Shape("ellipse", [(200, 232), (56, 34)], "A", (200, 190), 3),
                    Shape("ellipse", [(272, 222), (15, 9)], "A", (200, 190), 2),
                ],
                valves=[Valve((138, 190), (264, 190), (0, -1))],
            ),
            Scene(
                "APICAL 3C (APLAX)",
                shapes=[
                    Shape("poly", [(200, 44), (160, 80), (142, 130), (146, 184), (226, 186), (258, 120), (240, 74)],
                          "V", (196, 186), 8),
                    Shape("tube", [(242, 184), (300, 228)], "P", None, 3, width=34),
                    Shape("ellipse", [(176, 232), (44, 30)], "A", (186, 190), 3),
                ],
                valves=[Valve((148, 190), (226, 190), (0, -1)), Valve((230, 172), (254, 196), (0.8, 0.6), "SL")],
            ),
        ],
        "suprasternal_arch": [
            Scene(
                "SSN AORTIC ARCH",
                shapes=[
                    Shape("tube", [(70, 290), (70, 170), (80, 126), (110, 101), (160, 94), (210, 101),
                                   (240, 126), (250, 180), (252, 290)], "P", None, 4, width=30),
                    Shape("tube", [(118, 92), (104, 40)], "P", None, 2, width=11),
                    Shape("tube", [(156, 88), (156, 36)], "P", None, 2, width=10),
                    Shape("tube", [(196, 92), (212, 40)], "P", None, 2, width=10),
                    Shape("ellipse", [(160, 176), (24, 18)], "P", None, 3),
                ],
            ),
            Scene(
                "SSN CRAB VIEW",
                shapes=[
                    Shape("ellipse", [(200, 186), (56, 38)], "A", None, 3),
                    Shape("tube", [(160, 168), (112, 140)], "S", None, 2, width=10),
                    Shape("tube", [(160, 206), (112, 236)], "S", None, 2, width=10),
                    Shape("tube", [(240, 168), (288, 140)], "S", None, 2, width=10),
                    Shape("tube", [(240, 206), (288, 236)], "S", None, 2, width=10),
                    Shape("ellipse", [(200, 104), (22, 16)], "P", None, 3),
                ],
            ),
        ],
    }


def encode(view_id, scene_list):
    inside, depth = sector_mask()
    speckle = speckle_field(zlib.crc32(view_id.encode()))
    os.makedirs(RAW_DIR, exist_ok=True)
    out = os.path.join(RAW_DIR, f"{view_id}.mp4")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-c:v", "libx264", "-crf", "12", "-preset", "medium", "-pix_fmt", "yuv420p", out]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    frame_idx = 0
    beats_per_scene = 2
    for scene in scene_list:
        for f in range(beats_per_scene * FRAMES_PER_BEAT):
            t = (f % FRAMES_PER_BEAT) / FRAMES_PER_BEAT
            img = render_frame(scene, t, inside, depth, speckle, frame_idx)
            proc.stdin.write(img.tobytes())
            frame_idx += 1
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit(f"ffmpeg failed for {view_id}")
    print(f"raw  {view_id}: {frame_idx} frames -> {os.path.relpath(out, ROOT)}")


def main():
    for view_id, scene_list in scenes().items():
        encode(view_id, scene_list)
    if "--skip-prepare" not in sys.argv:
        subprocess.run([os.path.join(ROOT, "scripts", "prepare_loops.sh"), RAW_DIR], check=True)


if __name__ == "__main__":
    main()
