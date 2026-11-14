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

# view id -> one entry per step: (phase, overlay or None[, freezeAtMs])
STEPS = {
    "subcostal_4c": [
        ("LIVE", None),
        ("LIVE", None),
        ("LIVE", ov(A, "Color · IAS", [0.33, 0.36, 0.56, 0.50], [0.48, 0.70, 0.62, 0.86])),
        ("ED", ov(L, "Pericardium", [0.50, 0.272, 0.50, 0.31], [0.535, 0.375, 0.565, 0.415])),
    ],
    "subcostal_ivc": [
        ("LIVE", None),
        ("EXP", ov(L, "IVC", [0.56, 0.585, 0.56, 0.67], [0.36, 0.42, 0.36, 0.535]), 0),
        ("LIVE", ov(C, "M-mode · IVC", [0.47, 0.05, 0.58, 0.90], [0.36, 0.30, 0.36, 0.70])),
    ],
    "plax_standard": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", None),
        ("ED", ov(L, "IVSd", [0.47, 0.245, 0.47, 0.285], [0.47, 0.555, 0.47, 0.605])),
        ("ED", ov(L, "LVIDd", [0.47, 0.29, 0.47, 0.555], [0.47, 0.612, 0.47, 0.800])),
        ("ED", ov(L, "LVPWd", [0.47, 0.56, 0.47, 0.605], [0.47, 0.807, 0.47, 0.890])),
        ("ED", ov(L, "Ao root", [0.66, 0.295, 0.66, 0.405], [0.60, 0.45, 0.71, 0.43])),
        ("ES", ov(L, "LA", [0.63, 0.42, 0.63, 0.58], [0.74, 0.52, 0.74, 0.78])),
    ],
    "psax_av": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "MPA", [0.851, 0.441, 0.799, 0.493], [0.78, 0.56, 0.91, 0.56])),
        ("LIVE", ov(G, "PW · PV", [0.50, 0.073, 0.755, 0.357], [0.80, 0.28, 0.83, 0.43])),
        ("LIVE", ov(A, "Color · PDA", [0.76, 0.50, 0.93, 0.70], [0.75, 0.45, 0.93, 0.65])),
    ],
    "psax_papillary": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "LVIDd (FS)", [0.47, 0.37, 0.47, 0.60], [0.68, 0.17, 0.68, 0.52])),
        ("ED", ov(L, "D1 (EI)", [0.33, 0.48, 0.64, 0.48], [0.53, 0.36, 0.79, 0.36])),
        ("LIVE", None),
    ],
    "apical_4c": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "LV length (EDV)", [0.49, 0.14, 0.54, 0.57], [0.69, 0.15, 0.76, 0.62])),
        ("LIVE", ov(C, "M-mode · TAPSE", [0.48, 0.08, 0.20, 0.60], [0.68, 0.06, 0.47, 0.62])),
        ("ED", ov(L, "RVD1", [0.235, 0.47, 0.385, 0.47], [0.505, 0.50, 0.595, 0.50])),
        ("LIVE", ov(G, "PW · E/A", [0.48, 0.08, 0.53, 0.50], [0.68, 0.06, 0.76, 0.55])),
        ("LIVE", ov(G, "TDI · e'", [0.48, 0.08, 0.45, 0.58], [0.68, 0.06, 0.66, 0.63])),
        ("ES", ov(L, "LA length", [0.52, 0.565, 0.52, 0.765], [0.78, 0.66, 0.78, 0.95])),
    ],
    "apical_5c": [
        ("LIVE", None),
        ("LIVE", ov(G, "PW · LVOT", [0.50, 0.073, 0.5075, 0.573], [0.68, 0.06, 0.66, 0.57])),
        ("LIVE", ov(C, "CW · AV", [0.50, 0.073, 0.5075, 0.873], [0.68, 0.06, 0.66, 0.72])),
    ],
    "apical_2c_3c": [
        ("LIVE", None),
        ("ED", ov(L, "LV length (A2C)", [0.49, 0.11, 0.52, 0.59], [0.55, 0.15, 0.56, 0.64])),
        ("LIVE", None),
        ("LIVE", None),
    ],
    "suprasternal_arch": [
        ("LIVE", None),
        ("LIVE", None),
        ("ED", ov(L, "Arch", [0.44, 0.205, 0.44, 0.34], [0.50, 0.296, 0.50, 0.44])),
        ("ED", ov(L, "Isthmus", [0.50, 0.38, 0.58, 0.38], [0.57, 0.50, 0.66, 0.48])),
        ("LIVE", ov(C, "CW · DAo", [0.49, 0.04, 0.55, 0.62], [0.50, 0.20, 0.62, 0.72])),
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
                if overlay:
                    step["caliperOverlay"] = overlay
                    overlays += 1
                else:
                    step.pop("caliperOverlay", None)
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"phases {count}, {overlays} caliper overlays")


if __name__ == "__main__":
    main()
