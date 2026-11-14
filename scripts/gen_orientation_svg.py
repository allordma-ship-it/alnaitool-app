#!/usr/bin/env python3
"""
EchoScope – "How the probe sees the heart" orientation illustrations for step 1 of every view.

For each view writes assets/orientation/<view>.svg (English), <view>_de.svg and <view>_ar.svg:
  * frontal torso outline with sternum, clavicles, ribs and a heart silhouette (RV anterior,
    LV, great vessels),
  * the transducer on the view's acoustic window with its blue index marker pointing in the
    view's clock direction (12 = head, 3 = patient's left, as seen facing the patient),
  * the scan plane as a translucent fan: in-plane spread along the index direction, extending
    in the projected beam direction, so it shows how the beam cuts the chambers,
  * a compass (superior / inferior / right / left, patient's sides) and a clock inset.

Clock hours come from "notchHours" in echo_protocol_full.json.
Usage: python3 scripts/gen_orientation_svg.py
"""

from __future__ import annotations

import json
import math
import os
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "orientation")
DB = os.path.join(ASSETS, "echo_protocol_full.json")

W, H = 600, 420
BG = "#0B1220"
BODY, BODY_EDGE = "#182437", "#7C8BA1"
BONE = "#3A4B66"
INDEX_BLUE = "#3B82F6"
FAN = "#2EE6D6"
BEAM_RED = "#FF4D6D"
TEXT = "#F8FAFC"
MUTED = "#94A3B8"

# Probe position on the torso per acoustic window.
WINDOW_PROBE = {"subcostal": (300, 292), "parasternal": (330, 178), "apical": (396, 262), "suprasternal": (300, 70)}
# The IVC view sits slightly to the patient's right so the sagittal plane runs through the IVC.
VIEW_PROBE = {"subcostal_ivc": (287, 300)}
PILL = "#0F172A"

# Scan-plane fan per view: projected beam direction (screen), foreshortening of the beam in the
# frontal view (1 = beam runs in the frontal plane, small = beam goes straight into the chest,
# drawn as a short oblique depth vector across the index line so the slice stays readable)
# and fan radius. The fan is drawn as P + R·(cos φ · f · beam + sin φ · index), φ = ±40°, so it
# spreads along the index line exactly like the real sector.
BEAM = {
    "subcostal_4c": ((0.32, -0.95), 1.0, 175),
    "subcostal_ivc": ((1.0, -0.25), 0.22, 135),
    "plax_standard": ((-0.5, 0.87), 0.45, 140),
    "psax_av": ((-0.5, -0.87), 0.45, 125),
    "psax_papillary": ((0.5, 0.87), 0.45, 125),
    "apical_4c": ((-0.55, -0.83), 1.0, 175),
    "apical_5c": ((-0.50, -0.86), 0.95, 175),
    "apical_2c_3c": ((-0.55, -0.83), 0.9, 175),
    "suprasternal_arch": ((0.05, 1.0), 0.85, 170),
}
HALF_FAN = math.radians(40)

TEXTS = {
    "en": dict(sup="Superior", inf="Inferior", right="Right", left="Left", index="Index marker",
               clock="o'clock", plane="Scan plane",
               windows={"subcostal": "Subcostal window", "parasternal": "Left parasternal · 3rd–4th ICS",
                        "apical": "Apical window (apex beat)", "suprasternal": "Suprasternal notch"}),
    "de": dict(sup="Kranial", inf="Kaudal", right="Rechts", left="Links", index="Markierung",
               clock="Uhr", plane="Schallebene",
               windows={"subcostal": "Subkostales Fenster", "parasternal": "Links parasternal · 3.–4. ICR",
                        "apical": "Apikales Fenster (Herzspitzenstoß)", "suprasternal": "Jugulum (suprasternal)"}),
    "ar": dict(sup="علوي", inf="سفلي", right="يمين", left="يسار", index="المؤشر",
               clock="الساعة", plane="مستوى المسح",
               windows={"subcostal": "النافذة تحت الضلعية", "parasternal": "جانب القص الأيسر · المسافة الوربية 3–4",
                        "apical": "النافذة القمية (صدمة القمة)", "suprasternal": "الثلمة فوق القص"}),
}


def pill(x: float, y: float, text: str, size: float, fill: str = TEXT, anchor: str = "middle",
         opacity: float = 0.8) -> str:
    """Label on a dark rounded pill. No stroke / paint-order: Android's SVG renderer ignores
    paint-order, which would paint the outline over the glyphs."""
    w = len(text) * size * 0.54 + size * 0.9
    h = size * 1.45
    left = x - w / 2 if anchor == "middle" else (x if anchor == "start" else x - w)
    tx = left + w / 2
    return (f'<rect x="{left:.1f}" y="{y - h / 2:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{h / 2:.1f}" '
            f'fill="{PILL}" fill-opacity="{opacity}"/>'
            f'<text x="{tx:.1f}" y="{y + size * 0.36:.1f}" font-family="sans-serif" font-weight="700" '
            f'font-size="{size}" fill="{fill}" text-anchor="middle">{escape(text)}</text>')


def unit(hours: float):
    """Screen direction of a clock hour: 12 = up, 3 = right (patient's left)."""
    a = math.radians(hours * 30.0)
    return math.sin(a), -math.cos(a)


def torso() -> str:
    ribs = []
    for k in range(6):
        y = 112 + k * 27
        spread = 150 + k * 8
        ribs.append(f'<path d="M 290 {y} Q {300 - spread * 0.55:.0f} {y - 6} {300 - spread:.0f} {y + 42}" />')
        ribs.append(f'<path d="M 310 {y} Q {300 + spread * 0.55:.0f} {y - 6} {300 + spread:.0f} {y + 42}" />')
    return f'''
  <path d="M 262 0 L 262 38 C 228 54 122 58 76 94 C 54 116 58 205 74 262 C 86 322 118 378 128 420
           L 472 420 C 482 378 514 322 526 262 C 542 205 546 116 524 94 C 478 58 372 54 338 38 L 338 0"
        fill="{BODY}" stroke="{BODY_EDGE}" stroke-width="2.5" stroke-linejoin="round"/>
  <g fill="none" stroke="{BONE}" stroke-width="5" stroke-linecap="round" opacity="0.9">{''.join(ribs)}</g>
  <path d="M 296 86 C 250 74 196 72 150 82 M 304 86 C 350 74 404 72 450 82" fill="none" stroke="{BONE}"
        stroke-width="7" stroke-linecap="round"/>
  <rect x="289" y="88" width="22" height="156" rx="9" fill="{BONE}"/>
  <path d="M 291 242 L 309 242 L 300 262 Z" fill="{BONE}"/>'''


def heart() -> str:
    return '''
  <path d="M 275 158 L 275 104" stroke="#5B7BD5" stroke-width="11" stroke-linecap="round" fill="none"/>
  <path d="M 284 262 L 287 336" stroke="#5B7BD5" stroke-width="13" stroke-linecap="round" fill="none"/>
  <path d="M 300 162 C 298 120 304 104 326 104 C 350 104 356 122 352 146" stroke="#D9566B" stroke-width="13"
        stroke-linecap="round" fill="none"/>
  <path d="M 268 160 C 240 176 236 232 262 260 C 290 292 352 300 402 270 C 422 256 412 224 386 204
           C 366 184 346 160 312 152 C 296 148 280 152 268 160 Z" fill="#7A2A3A" stroke="#F4A9B7" stroke-width="2.5"/>
  <path d="M 276 178 C 262 204 266 246 292 268 C 322 286 356 280 372 262 C 346 240 320 214 306 182 Z"
        fill="#2B4A8F" opacity="0.85"/>
  <path d="M 318 158 C 348 168 378 196 396 226 C 410 250 402 264 386 270 C 366 252 336 226 320 194 Z"
        fill="#B84A5E" opacity="0.9"/>
  <path d="M 318 160 C 322 146 330 136 342 132" stroke="#6A5ACD" stroke-width="10" stroke-linecap="round" fill="none"/>
  <g font-family="sans-serif" font-weight="700" font-size="13" fill="#F8FAFC" text-anchor="middle">
    <text x="306" y="248">RV</text><text x="372" y="236">LV</text>
  </g>'''


def fan(view_id: str, probe, hours: float, plane_label: str) -> str:
    (dx, dy), f, radius = BEAM[view_id]
    n = math.hypot(dx, dy)
    dx, dy = dx / n * f, dy / n * f
    ux, uy = unit(hours)
    px, py = probe
    pts = []
    for k in range(25):
        phi = -HALF_FAN + 2 * HALF_FAN * k / 24
        c, s_ = math.cos(phi), math.sin(phi)
        pts.append((px + radius * (c * dx + s_ * ux), py + radius * (c * dy + s_ * uy)))
    path = f"M {px:.1f} {py:.1f} " + " ".join(f"L {x:.1f} {y:.1f}" for x, y in pts) + " Z"
    beam_end = (px + radius * dx, py + radius * dy)
    lx, ly = pts[-1][0] + ux * 14, pts[-1][1] + uy * 14
    return f'''
  <path d="{path}" fill="{FAN}" fill-opacity="0.26" stroke="{FAN}" stroke-width="2" stroke-linejoin="round"/>
  <path d="M {px:.1f} {py:.1f} L {beam_end[0]:.1f} {beam_end[1]:.1f}" stroke="{BEAM_RED}" stroke-width="2.2"
        stroke-dasharray="7 5" fill="none"/>
  {pill(lx, ly, plane_label, 12, FAN)}'''


def probe_svg(probe, hours: list[float], badge: str) -> str:
    ux, uy = unit(hours[0])
    ang = math.degrees(math.atan2(uy, ux))
    px, py = probe
    dots = []
    for i, h in enumerate(hours):
        hx, hy = unit(h)
        cx, cy = px + hx * 36, py + hy * 36
        if i == 0:
            # Glowing index marker: soft halos under a bright dot.
            dots.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="17" fill="{INDEX_BLUE}" fill-opacity="0.16"/>'
                        f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="12" fill="{INDEX_BLUE}" fill-opacity="0.32"/>'
                        f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="7.5" fill="#60A5FA" stroke="#DBEAFE" stroke-width="2"/>')
            bx, by = px + hx * 72, py + hy * 72
            dots.append(pill(bx, by, badge, 13, "#93C5FD", opacity=0.9))
        else:
            dots.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="6.5" fill="none" stroke="{INDEX_BLUE}" stroke-width="3"/>')
    return f'''
  <g transform="translate({px} {py}) rotate({ang:.1f})">
    <rect x="-30" y="-11" width="60" height="22" rx="10" fill="#E2E8F0" stroke="#0F172A" stroke-width="2"/>
    <rect x="-24" y="-5" width="48" height="10" rx="5" fill="#94A3B8"/>
  </g>
  {''.join(dots)}'''


def compass(t: dict) -> str:
    cx, cy = 528, 62
    return f'''
  <g stroke="{MUTED}" stroke-width="2.5" fill="none" stroke-linecap="round">
    <path d="M {cx} {cy - 30} L {cx} {cy + 30} M {cx - 30} {cy} L {cx + 30} {cy}"/>
    <path d="M {cx - 6} {cy - 23} L {cx} {cy - 31} L {cx + 6} {cy - 23}"/>
  </g>
  <g font-family="sans-serif" font-size="12" font-weight="700" fill="{TEXT}" text-anchor="middle">
    <text x="{cx}" y="{cy - 38}">{t["sup"]}</text>
    <text x="{cx}" y="{cy + 47}">{t["inf"]}</text>
    <text x="{cx - 34}" y="{cy + 4}" text-anchor="end">{t["right"]}</text>
    <text x="{cx + 34}" y="{cy + 4}" text-anchor="start">{t["left"]}</text>
  </g>'''


def clock_inset(hours: list[float], label: str, t: dict) -> str:
    cx, cy, r = 62, 352, 40
    ticks = []
    for h in range(12):
        x1, y1 = unit(h)
        inner = r - (7 if h % 3 == 0 else 4)
        ticks.append(f'<path d="M {cx + x1 * inner:.1f} {cy + y1 * inner:.1f} L {cx + x1 * r:.1f} {cy + y1 * r:.1f}"/>')
    hands = []
    for i, h in enumerate(hours):
        hx, hy = unit(h)
        colour = INDEX_BLUE
        hands.append(f'<path d="M {cx} {cy} L {cx + hx * (r - 9):.1f} {cy + hy * (r - 9):.1f}" stroke="{colour}" '
                     f'stroke-width="{4 if i == 0 else 3}" stroke-linecap="round"'
                     f'{"" if i == 0 else " stroke-dasharray=\"4 3\""}/>')
        hands.append(f'<circle cx="{cx + hx * (r - 9):.1f}" cy="{cy + hy * (r - 9):.1f}" r="4.5" '
                     f'fill="{colour if i == 0 else BG}" stroke="{colour}" stroke-width="2"/>')
    nums = "".join(
        f'<text x="{cx + unit(h)[0] * (r + 11):.1f}" y="{cy + unit(h)[1] * (r + 11) + 4:.1f}">{h}</text>'
        for h in (12, 3, 6, 9))
    return f'''
  <circle cx="{cx}" cy="{cy}" r="{r}" fill="#0F1A2B" stroke="{MUTED}" stroke-width="2"/>
  <g stroke="{MUTED}" stroke-width="2">{''.join(ticks)}</g>
  {''.join(hands)}
  <circle cx="{cx}" cy="{cy}" r="3" fill="{TEXT}"/>
  <g font-family="sans-serif" font-size="10" font-weight="700" fill="{MUTED}" text-anchor="middle">{nums}</g>
  {pill(cx + r + 18, cy - 9, "● " + t["index"], 13, "#93C5FD", anchor="start")}
  {pill(cx + r + 18, cy + 13, f"{label} {t['clock']}", 13, TEXT, anchor="start")}'''


def render(view: dict, window_id: str, lang: str) -> str:
    t = TEXTS[lang]
    hours = view["notchHours"] or [12.0]
    probe = VIEW_PROBE.get(view["id"], WINDOW_PROBE[window_id])
    # Android's SVG renderer drops an escaped "&" from text, so show it as "+".
    title = (view["title"].get(lang) or view["title"]["en"]).replace(" & ", " + ")
    primary = view["notchClock"].split("/")[0].strip()
    badge = f"{t['clock']} {primary}" if lang == "ar" else f"{primary} {t['clock']}"
    # Every text node is XML-escaped: titles such as "IVC & bicaval" made the files invalid
    # (blank panel on Android), although lenient browsers still showed them.
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <title>{escape(title)}</title>
  <rect width="{W}" height="{H}" fill="{BG}"/>{torso()}{heart()}{fan(view["id"], probe, hours[0], t["plane"])}{probe_svg(probe, hours, badge)}{compass(t)}{clock_inset(hours, view["notchClock"], t)}
  {pill(10, 24, title, 15, TEXT, anchor="start")}
  {pill(10, 48, t["windows"][window_id], 12, FAN, anchor="start")}
</svg>
'''


# Index directions the guide teaches (clock face seen facing the patient). Apical 2C: rotate
# counter-clockwise from 3 o'clock to 12–1 o'clock; apical 3C: on to 10–11 o'clock.
NOTCH = {
    "apical_2c_3c": ("12–1 / 10–11", [12.5, 10.5], {
        "en": "A2C: notch at 12–1 o'clock. A3C: notch at 10–11 o'clock.",
        "de": "2K: Markierung bei 12–1 Uhr. 3K: Markierung bei 10–11 Uhr.",
        "ar": "ثنائي الحجرات: العلامة عند الساعة 12–1. ثلاثي الحجرات: العلامة عند الساعة 10–11.",
    }),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    for window in db["windows"]:
        for view in window["views"]:
            if view["id"] in NOTCH:
                clock, hours, text = NOTCH[view["id"]]
                view["notchClock"], view["notchHours"], view["notchDescription"] = clock, hours, text
            for lang in ("en", "de", "ar"):
                name = f"{view['id']}.svg" if lang == "en" else f"{view['id']}_{lang}.svg"
                with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
                    f.write(render(view, window["id"], lang))
            view["orientationAsset"] = f"orientation/{view['id']}.svg"
            print(f"orientation/{view['id']}[_de|_ar].svg  index {view['notchClock']}")
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
