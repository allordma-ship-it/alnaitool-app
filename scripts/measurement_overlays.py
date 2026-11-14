#!/usr/bin/env python3
"""
EchoScope – freeze phase and caliper overlays for every guided-exam step.

Adds to each step in app/src/main/assets/echo_protocol_full.json:
  * "requiredPhase": "ED" | "ES" | "EXP" | "LIVE"
      ED  – pause the cine loop on the first R-wave (end-diastole)
      ES  – pause on the first end-systole (T-wave end)
      EXP – pause on "freezeAtMs" (end-expiration; respiration-dependent IVC)
      LIVE – keep the loop playing (orientation, Doppler, colour steps)
  * "freezeAtMs": explicit frame time for EXP steps
  * "extraOverlays": further calipers shown together with the first (e.g. D1 + D2, e' septal + lateral)
  * "caliperOverlay": {
        "type":  "LINE" | "DOPPLER_GATE" | "AREA" | "CURSOR",
        "label": short measurement name,
        "video":   [x1, y1, x2, y2]  normalised (0–1) on the cine-loop frame,
        "diagram": [x1, y1, x2, y2]  normalised (0–1) on the atlas illustration
    }
      LINE         – caliper between two landmarks (+ end marks)
      DOPPLER_GATE – dashed cursor from (x1, y1) to a PW/TDI sample gate at (x2, y2)
      AREA         – colour-Doppler box with corners (x1, y1) and (x2, y2)
      CURSOR       – dashed M-mode / CW line from (x1, y1) to (x2, y2)

Video coordinates were read from the frozen ED / ES frames of the bundled loops
(real clips) or computed from the simulator geometry (psax_av, apical_5c: x/400, y/300);
diagram coordinates were read from assets/diagrams/<view>.png.

Usage: python3 scripts/measurement_overlays.py
"""

import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")


def ov(kind, label, video, diagram):
    return {"type": kind, "label": label, "video": video, "diagram": diagram}


L, G, A, C = "LINE", "DOPPLER_GATE", "AREA", "CURSOR"

# view id -> one entry per step: (phase, overlay | [overlays] | None[, freezeAtMs])
# Every video coordinate below was read from the frozen frame with scripts/verify_calipers.py
# (debug_calipers/*.png); lines are drawn perpendicular to the structure they measure.
STEPS = {
    "subcostal_4c": [
        ("LIVE", None),
        ("LIVE", None),
        # Colour box over the interatrial septum between RA (upper left) and LA (below).
        ("LIVE", ov(A, "Color · IAS", [0.32, 0.47, 0.48, 0.62], [0.48, 0.70, 0.62, 0.86])),
        # Liver / pericardium interface to RV endocardium at end-diastole.
        ("ED", ov(L, "Pericardium", [0.47, 0.298, 0.47, 0.333], [0.535, 0.375, 0.565, 0.415])),
    ],
    "subcostal_ivc": [
        ("LIVE", None),
        # Perpendicular to the IVC ≈ 1.5–2 cm caudal to the RA junction, end-expiration.
        ("EXP", ov(L, "IVC", [0.555, 0.583, 0.536, 0.652], [0.36, 0.42, 0.36, 0.535]), 0),
        ("LIVE", ov(C, "M-mode · IVC", [0.50, 0.02, 0.567, 0.92], [0.36, 0.30, 0.36, 0.70])),
    ],
    "plax_standard": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", None),
        # One continuous line at the mitral leaflet tips, perpendicular to the LV long axis.
        ("ED", ov(L, "IVSd", [0.478, 0.243, 0.472, 0.291], [0.47, 0.555, 0.47, 0.605])),
        ("ED", ov(L, "LVIDd", [0.472, 0.291, 0.436, 0.553], [0.47, 0.612, 0.47, 0.800])),
        ("ED", ov(L, "LVPWd", [0.436, 0.553, 0.430, 0.595], [0.47, 0.807, 0.47, 0.890])),
        # Sinuses of Valsalva, leading edge to leading edge, perpendicular to the root.
        ("ED", ov(L, "Ao root", [0.626, 0.255, 0.654, 0.412], [0.612, 0.455, 0.705, 0.528])),
        # Posterior aortic wall to posterior LA wall at end-systole.
        ("ES", ov(L, "LA", [0.645, 0.405, 0.684, 0.575], [0.74, 0.52, 0.74, 0.78])),
    ],
    "psax_av": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "MPA", [0.7315, 0.4523, 0.6785, 0.481], [0.78, 0.56, 0.91, 0.56])),
        ("LIVE", ov(G, "PW · PV", [0.50, 0.073, 0.675, 0.40], [0.80, 0.28, 0.83, 0.43])),
        ("LIVE", ov(A, "Color · PDA", [0.66, 0.50, 0.84, 0.68], [0.75, 0.45, 0.93, 0.65])),
    ],
    "psax_papillary": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "LVIDd (FS)", [0.465, 0.355, 0.465, 0.565], [0.725, 0.20, 0.725, 0.51])),
        # D1 parallel and D2 perpendicular to the septum, endocardium to endocardium,
        # clear of the papillary muscles (bright mass right of centre).
        ("ED", [ov(L, "D1", [0.46, 0.355, 0.46, 0.565], [0.735, 0.20, 0.735, 0.51]),
                ov(L, "D2", [0.405, 0.415, 0.585, 0.415], [0.618, 0.255, 0.808, 0.255])]),
        ("ES", [ov(L, "D1", [0.455, 0.375, 0.455, 0.548], [0.735, 0.20, 0.735, 0.51]),
                ov(L, "D2", [0.42, 0.40, 0.51, 0.40], [0.618, 0.255, 0.808, 0.255])]),
        ("LIVE", None),
    ],
    "apical_4c": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "LV length (EDV)", [0.495, 0.13, 0.55, 0.59], [0.69, 0.19, 0.725, 0.595])),
        # M-mode through the lateral tricuspid annulus.
        ("LIVE", ov(C, "M-mode · TAPSE", [0.50, 0.07, 0.272, 0.64], [0.68, 0.06, 0.475, 0.665])),
        # Basal RV: free-wall to septal endocardium just above the tricuspid annulus.
        ("ED", ov(L, "RVD1", [0.29, 0.52, 0.40, 0.52], [0.50, 0.55, 0.588, 0.55])),
        # PW sample volume at the mitral leaflet tips.
        ("LIVE", ov(G, "PW · E/A", [0.50, 0.07, 0.545, 0.55], [0.68, 0.06, 0.72, 0.52])),
        # TDI at the septal and lateral mitral annulus.
        ("LIVE", [ov(G, "e' septal", [0.50, 0.07, 0.455, 0.592], [0.68, 0.06, 0.675, 0.60]),
                  ov(G, "e' lateral", [0.50, 0.07, 0.645, 0.585], [0.68, 0.06, 0.785, 0.575])]),
        ("ES", ov(L, "LA length", [0.53, 0.555, 0.525, 0.765], [0.78, 0.66, 0.78, 0.95])),
    ],
    "apical_5c": [
        ("LIVE", None),
        ("LIVE", ov(G, "PW · LVOT", [0.50, 0.073, 0.5075, 0.573], [0.68, 0.06, 0.66, 0.57])),
        ("LIVE", ov(C, "CW · AV", [0.50, 0.073, 0.5075, 0.873], [0.68, 0.06, 0.66, 0.72])),
    ],
    "apical_2c_3c": [
        ("LIVE", None),
        ("ED", ov(L, "LV length (A2C)", [0.495, 0.125, 0.53, 0.595], [0.55, 0.15, 0.56, 0.64])),
        ("LIVE", None),
        ("LIVE", None),
    ],
    "suprasternal_arch": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "Arch", [0.44, 0.215, 0.44, 0.345], [0.50, 0.296, 0.50, 0.44])),
        ("ED", ov(L, "Isthmus", [0.475, 0.40, 0.545, 0.40], [0.57, 0.50, 0.66, 0.48])),
        ("LIVE", ov(C, "CW · DAo", [0.49, 0.04, 0.525, 0.66], [0.50, 0.20, 0.62, 0.72])),
    ],
}


def main():
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    count = {"ED": 0, "ES": 0, "EXP": 0, "LIVE": 0}
    overlays = 0
    for window in db["windows"]:
        for view in window["views"]:
            spec = STEPS[view["id"]]
            assert len(spec) == len(view["steps"]), view["id"]
            for step, entry in zip(view["steps"], spec):
                phase, overlay = entry[0], entry[1]
                step["requiredPhase"] = phase
                count[phase] += 1
                if len(entry) > 2:
                    step["freezeAtMs"] = entry[2]
                else:
                    step.pop("freezeAtMs", None)
                group = overlay if isinstance(overlay, list) else ([overlay] if overlay else [])
                if group:
                    step["caliperOverlay"] = group[0]
                    overlays += len(group)
                else:
                    step.pop("caliperOverlay", None)
                if len(group) > 1:
                    step["extraOverlays"] = group[1:]
                else:
                    step.pop("extraOverlays", None)
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"phases {count}, {overlays} caliper overlays")


if __name__ == "__main__":
    main()
