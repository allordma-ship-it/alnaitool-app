#!/usr/bin/env python3
"""
EchoScope brand icon – one geometry, three outputs:

  brand_assets/echoscope_icon.svg                       master artwork (preview / store graphics)
  app/src/main/res/drawable/ic_launcher_background.xml  adaptive-icon background (vector)
  app/src/main/res/drawable/ic_launcher_foreground.xml  adaptive-icon foreground (vector)
  app/src/main/res/drawable/ic_launcher_monochrome.xml  themed icon (Android 13+, alpha only)

108 x 108 dp adaptive canvas; everything important stays inside the 66 dp safe circle.
Usage: python3 brand_assets/build_icon.py   (then render_previews.js for PNG previews)
"""

import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")

TIP = (35.0, 33.0)            # transducer face centre (probe tip)
HEART_SHIFT = (-4.0, -4.0)    # heart group offset to sit inside the safe zone

BLUE, BLUE_LIGHT, EMERALD, EMERALD_LIGHT = "#1E88E5", "#42A5F5", "#00897B", "#26A69A"
HEART, GROOVE, ECG_CORE, ECG_EDGE = "#F8FAFC", "#9FB3C8", "#1DE9B6", "#0B1118"

# sector arcs around the probe tip, aimed down-right (45°): (radius, colour, alpha, width)
ARCS = [(13, BLUE_LIGHT, 0.95, 2.4), (21, EMERALD, 0.80, 2.2), (29, BLUE, 0.60, 2.0), (37, EMERALD, 0.45, 1.8)]
ARC_FROM, ARC_TO = 22.0, 68.0  # degrees (SVG: 0° = +x, clockwise)

HEART_BODY = ("M50 50 C41 52 36 60 37 69 C38 79 47 87 58 88 C68 89 79 82 82 71 "
              "C85 60 80 50 71 47 C64 45 56 48 50 50 Z")
AORTA = "M60 50 C60 40 64 34 71 34 C77 34 79 39 79 45"
PULMONARY = "M54 51 C51 45 48 42 44 41"
BRANCHES = ["M66 35.5 L65 29.5", "M71 34 L71 28", "M76 36 L78.5 30.5"]
GROOVE_PATH = "M63 50 C60 62 55 74 50 84"
ECG = "M22 70 L38 70 L41.5 65 L44.5 70 L48 70 L51.5 47 L55.5 84 L58.5 65.5 L61.5 70 L88 70"

# probe drawn pointing down at the tip, then rotated -45° about the tip (→ down-right)
PROBE_BODY = "M29.5 31 L29.5 18 C29.5 15.5 31.5 13.5 34 13.5 L36 13.5 C38.5 13.5 40.5 15.5 40.5 18 L40.5 31 Z"
PROBE_FACE = "M30 30.2 L40 30.2 L40 33 C40 33.6 39.6 34 39 34 L31 34 C30.4 34 30 33.6 30 33 Z"
PROBE_GRIP = ["M32 20 L38 20", "M32 23 L38 23"]
PROBE_CABLE = "M35 13.5 C35 9 33 6.5 30 5"


def arc_path(r, a0, a1):
    import math
    x0, y0 = TIP[0] + r * math.cos(math.radians(a0)), TIP[1] + r * math.sin(math.radians(a0))
    x1, y1 = TIP[0] + r * math.cos(math.radians(a1)), TIP[1] + r * math.sin(math.radians(a1))
    return f"M{x0:.2f} {y0:.2f} A{r} {r} 0 0 1 {x1:.2f} {y1:.2f}"


def wedge_path(r):
    import math
    a0, a1 = ARC_FROM - 4, ARC_TO + 4
    x0, y0 = TIP[0] + r * math.cos(math.radians(a0)), TIP[1] + r * math.sin(math.radians(a0))
    x1, y1 = TIP[0] + r * math.cos(math.radians(a1)), TIP[1] + r * math.sin(math.radians(a1))
    return f"M{TIP[0]} {TIP[1]} L{x0:.2f} {y0:.2f} A{r} {r} 0 0 1 {x1:.2f} {y1:.2f} Z"


# ---------------------------------------------------------------- SVG master
def svg():
    hx, hy = HEART_SHIFT
    arcs = "\n".join(f'    <path d="{arc_path(r, ARC_FROM, ARC_TO)}" fill="none" stroke="{c}" stroke-opacity="{a}" '
                     f'stroke-width="{w}" stroke-linecap="round"/>' for r, c, a, w in ARCS)
    branches = "\n".join(f'      <path d="{b}" stroke="{HEART}" stroke-width="2.6" stroke-linecap="round"/>' for b in BRANCHES)
    grips = "\n".join(f'      <path d="{g}" stroke="#94A3B8" stroke-width="1.1" stroke-linecap="round"/>' for g in PROBE_GRIP)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#2A3B4F"/><stop offset="1" stop-color="#0B1118"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.55" cy="0.56" r="0.42">
      <stop offset="0" stop-color="{BLUE}" stop-opacity="0.38"/><stop offset="1" stop-color="{BLUE}" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="beam" x1="0.3" y1="0.3" x2="0.8" y2="0.8">
      <stop offset="0" stop-color="{BLUE}" stop-opacity="0.32"/><stop offset="1" stop-color="{EMERALD}" stop-opacity="0.04"/>
    </linearGradient>
    <clipPath id="squircle"><path d="M54 2 C92 2 106 16 106 54 C106 92 92 106 54 106 C16 106 2 92 2 54 C2 16 16 2 54 2 Z"/></clipPath>
  </defs>
  <g clip-path="url(#squircle)">
    <rect width="108" height="108" fill="url(#bg)"/>
    <rect width="108" height="108" fill="url(#glow)"/>
    <path d="{wedge_path(42)}" fill="url(#beam)"/>
    <g transform="translate({hx} {hy})" fill="none">
      <path d="{HEART_BODY}" fill="{HEART}"/>
      <path d="{AORTA}" stroke="{HEART}" stroke-width="6.5" stroke-linecap="round"/>
      <path d="{PULMONARY}" stroke="{HEART}" stroke-width="5.5" stroke-linecap="round"/>
{branches}
      <path d="{GROOVE_PATH}" stroke="{GROOVE}" stroke-width="1.6" stroke-linecap="round"/>
    </g>
{arcs}
    <path d="{ECG}" fill="none" stroke="{ECG_CORE}" stroke-opacity="0.28" stroke-width="6" stroke-linejoin="round" stroke-linecap="round"/>
    <path d="{ECG}" fill="none" stroke="{ECG_EDGE}" stroke-width="3.6" stroke-linejoin="round" stroke-linecap="round"/>
    <path d="{ECG}" fill="none" stroke="{ECG_CORE}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>
    <g transform="rotate(-45 {TIP[0]} {TIP[1]})">
      <path d="{PROBE_CABLE}" fill="none" stroke="#64748B" stroke-width="2.4" stroke-linecap="round"/>
      <path d="{PROBE_BODY}" fill="#E2E8F0" stroke="#94A3B8" stroke-width="0.8"/>
{grips}
      <path d="{PROBE_FACE}" fill="{BLUE}"/>
    </g>
  </g>
</svg>
'''


# ---------------------------------------------------------------- VectorDrawables
def vd_path(d, fill=None, stroke=None, width=None, alpha=None, cap=True, join=False, fill_alpha=None):
    a = [f'android:pathData="{d}"']
    if fill:
        a.append(f'android:fillColor="{fill}"')
    if fill_alpha is not None:
        a.append(f'android:fillAlpha="{fill_alpha}"')
    if stroke:
        a.append(f'android:strokeColor="{stroke}"')
        a.append(f'android:strokeWidth="{width}"')
        if cap:
            a.append('android:strokeLineCap="round"')
        if join:
            a.append('android:strokeLineJoin="round"')
    if alpha is not None:
        a.append(f'android:strokeAlpha="{alpha}"')
    return "        <path " + "\n            ".join(a) + " />"


def foreground(mono=False):
    hx, hy = HEART_SHIFT
    white = "#FFFFFFFF"
    parts = []
    if not mono:
        parts.append(f'''        <path android:pathData="{wedge_path(42)}">
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="35" android:startY="33" android:endX="80" android:endY="78">
                    <item android:offset="0" android:color="#521E88E5" />
                    <item android:offset="1" android:color="#0A00897B" />
                </gradient>
            </aapt:attr>
        </path>''')
    heart = [vd_path(HEART_BODY, fill=white if mono else HEART),
             vd_path(AORTA, stroke=white if mono else HEART, width=6.5),
             vd_path(PULMONARY, stroke=white if mono else HEART, width=5.5)]
    heart += [vd_path(b, stroke=white if mono else HEART, width=2.6) for b in BRANCHES]
    if not mono:
        heart.append(vd_path(GROOVE_PATH, stroke=GROOVE, width=1.6))
    parts.append(f'        <group android:translateX="{hx}" android:translateY="{hy}">\n' +
                 "\n".join("    " + h for h in heart) + "\n        </group>")
    # acoustic sector waves over the heart (translucent, so the white heart shows through)
    if not mono:
        for r, c, a, w in ARCS:
            parts.append(vd_path(arc_path(r, ARC_FROM, ARC_TO), stroke=c, width=w, alpha=a))
    else:
        for r, _, _, w in ARCS[:2]:
            parts.append(vd_path(arc_path(r, ARC_FROM, ARC_TO), stroke=white, width=w))
    if not mono:
        parts.append(vd_path(ECG, stroke=ECG_CORE, width=6, alpha=0.28, join=True))
        parts.append(vd_path(ECG, stroke=ECG_EDGE, width=3.6, join=True))
        parts.append(vd_path(ECG, stroke=ECG_CORE, width=2, join=True))
    probe = [vd_path(PROBE_CABLE, stroke=white if mono else "#64748B", width=2.4),
             vd_path(PROBE_BODY, fill=white if mono else "#E2E8F0", stroke=None if mono else "#94A3B8", width=0.8, cap=False)]
    if not mono:
        probe += [vd_path(g, stroke="#94A3B8", width=1.1) for g in PROBE_GRIP]
        probe.append(vd_path(PROBE_FACE, fill=BLUE))
    parts.append(f'        <group android:rotation="-45" android:pivotX="{TIP[0]}" android:pivotY="{TIP[1]}">\n' +
                 "\n".join("    " + p for p in probe) + "\n        </group>")
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if not mono else ""
    title = "monochrome (themed icon)" if mono else "foreground"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- EchoScope launcher {title}: generated by brand_assets/build_icon.py – edit there. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group>
{chr(10).join(parts)}
    </group>
</vector>
'''


BACKGROUND = '''<?xml version="1.0" encoding="utf-8"?>
<!-- EchoScope launcher background: charcoal-slate gradient with a medical-blue glow
     (generated by brand_assets/build_icon.py). The launcher masks it to its squircle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FF2A3B4F" />
                <item android:offset="1" android:color="#FF0B1118" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="59.4" android:centerY="60.5" android:gradientRadius="45.4">
                <item android:offset="0" android:color="#611E88E5" />
                <item android:offset="1" android:color="#001E88E5" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


if __name__ == "__main__":
    with open(os.path.join(HERE, "echoscope_icon.svg"), "w") as f:
        f.write(svg())
    with open(os.path.join(RES, "ic_launcher_foreground.xml"), "w") as f:
        f.write(foreground())
    with open(os.path.join(RES, "ic_launcher_monochrome.xml"), "w") as f:
        f.write(foreground(mono=True))
    with open(os.path.join(RES, "ic_launcher_background.xml"), "w") as f:
        f.write(BACKGROUND)
    print("icon written")
