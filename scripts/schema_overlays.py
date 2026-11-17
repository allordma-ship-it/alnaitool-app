#!/usr/bin/env python3
"""
EchoScope – calipers, PW gates, CW / M-mode cursors and colour boxes on the Schema drawing.

The Schema tab's first page is the dark-theme view drawing (assets/schema_dark/<view>.png, from
scripts/harmonize_schema_dark_theme.py). Each caliper overlay of every guided step gets "schema"
coordinates (normalised to that image) placed on the anatomy of the drawing: walls, valves,
annulus, apex and the probe tip as the origin of every Doppler / M-mode cursor. They were read on a
0.025 grid of each drawing and checked by eye with scripts/verify_doppler_and_calipers.py.

CW cursors: the app draws the focus diamond at 82 % of the cursor (CW_FOCUS), so the end point is
chosen to put that diamond on the valve:  end = tip + (valve - tip) / 0.82.

Run after scripts/integrate_library.py.
"""

import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import schema_raster as sr  # noqa: E402  (ES warp)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DB = os.path.join(ASSETS, "echo_protocol_full.json")
CW_FOCUS = 0.82


def cw(tip, valve):
    """CW cursor from the probe tip with its focus diamond on the valve."""
    return [tip[0], tip[1], round(tip[0] + (valve[0] - tip[0]) / CW_FOCUS, 3),
            round(tip[1] + (valve[1] - tip[1]) / CW_FOCUS, 3)]


def ivc(v):
    """subcostal_ivc drawing = the loop's geometry (720 x 492) with 64 px added on top for the probe."""
    return [v[0], round((v[1] * 492 + 64) / 556, 3), v[2], round((v[3] * 492 + 64) / 556, 3)]


A4C_TIP, A5C_TIP, A3C_TIP, PSAX_TIP = (0.40, 0.17), (0.41, 0.15), (0.38, 0.17), (0.495, 0.11)

SCHEMA = {
    "subcostal_4c": {
        "Color · IAS": [0.22, 0.64, 0.47, 0.84],             # atrial septum between RA and LA
        "Pericardium": [0.50, 0.45, 0.50, 0.485],            # in front of the RV free wall
    },
    "subcostal_ivc": {
        "IVC": ivc([0.555, 0.583, 0.536, 0.652]),
        "M-mode · IVC": ivc([0.5, 0.02, 0.567, 0.92]),
    },
    "plax_standard": {                                       # at the mitral leaflet tips, ⟂ LV long axis
        "IVSd": [0.45, 0.42, 0.45, 0.465],
        "LVIDd": [0.45, 0.465, 0.45, 0.69],
        "LVPWd": [0.45, 0.69, 0.45, 0.73],
        "Ao root": [0.635, 0.37, 0.68, 0.445],               # sinus level, ⟂ aortic axis
        "LA": [0.69, 0.46, 0.84, 0.63],                      # posterior aortic wall -> LA back wall
    },
    "psax_av": {
        "MPA": [0.60, 0.56, 0.725, 0.56],                    # main PA distal to the pulmonary valve
        "PW · PV": [PSAX_TIP[0], PSAX_TIP[1], 0.66, 0.52],
        "Color · PDA": [0.60, 0.66, 0.80, 0.84],             # PA bifurcation
        "CW · TR": cw(PSAX_TIP, (0.32, 0.58)),               # through the tricuspid valve
    },
    "psax_papillary": {
        "LVIDd (FS)": [0.47, 0.42, 0.47, 0.73],
        "D1": [0.47, 0.42, 0.47, 0.73],
        "D2": [0.35, 0.645, 0.60, 0.645],             # below the anterolateral papillary muscle
    },
    "apical_4c": {
        "LV length (EDV)": [0.45, 0.255, 0.57, 0.66],        # apex -> mid mitral annulus
        "M-mode · TAPSE": [A4C_TIP[0], A4C_TIP[1], 0.185, 0.635],  # lateral tricuspid annulus, RV free wall
        "RVD1": [0.225, 0.565, 0.31, 0.565],                    # basal RV
        "PW · E/A": [A4C_TIP[0], A4C_TIP[1], 0.57, 0.62],    # mitral leaflet tips
        "e' septal": [A4C_TIP[0], A4C_TIP[1], 0.44, 0.665],
        "e' lateral": [A4C_TIP[0], A4C_TIP[1], 0.71, 0.665],
        "CW · TR": cw(A4C_TIP, (0.30, 0.625)),
        "LA length": [0.57, 0.665, 0.56, 0.885],
    },
    "apical_5c": {                                           # central LVOT / aortic valve, not the MV
        "PW · LVOT": [A5C_TIP[0], A5C_TIP[1], 0.455, 0.615],
        "CW · AV": cw(A5C_TIP, (0.435, 0.675)),
    },
    "apical_2c": {
        "LV length (A2C)": [0.40, 0.275, 0.42, 0.70],
        "LA length (A2C)": [0.42, 0.705, 0.43, 0.945],
    },
    "apical_3c": {
        "Color · AV/MV": [0.33, 0.62, 0.70, 0.82],
        "PW · LVOT": [A3C_TIP[0], A3C_TIP[1], 0.53, 0.69],
        "CW · AV": cw(A3C_TIP, (0.58, 0.745)),
    },
    "suprasternal_arch": {
        "Arch": [0.35, 0.38, 0.35, 0.49],
        "Isthmus": [0.55, 0.53, 0.67, 0.53],
        "CW · DAo": cw((0.30, 0.15), (0.61, 0.66)),
    },
}
# End-systolic steps are measured on the ES drawing (scripts/schema_raster.py warps the LV cavity
# to 72 %): their end-diastolic positions are moved with the same warp.


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    n = 0
    for w in db["windows"]:
        for view in w["views"]:
            for st in view["steps"]:
                for o in ([st["caliperOverlay"]] if st.get("caliperOverlay") else []) + st.get("extraOverlays", []):
                    coords = SCHEMA[view["id"]][o["label"]]
                    if st.get("requiredPhase") == "ES" and view["id"] in sr.LV_PARAMS:
                        W, H = Image.open(os.path.join(ASSETS, f"schema/{view['id']}_dark.webp")).size
                        x0, y0 = sr.to_systolic_point(view["id"], W, H, coords[0], coords[1])
                        x1, y1 = sr.to_systolic_point(view["id"], W, H, coords[2], coords[3])
                        coords = [x0, y0, x1, y1]
                    o["schema"] = [round(c, 3) for c in coords]
                    n += 1
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{n} overlays placed on the Schema drawings")


if __name__ == "__main__":
    main()
