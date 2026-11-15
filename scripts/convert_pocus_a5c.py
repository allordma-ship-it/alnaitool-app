#!/usr/bin/env python3
"""
EchoScope – real apical 5-chamber loop from The POCUS Atlas (replaces the simulated A5C loop).

Source: "Normal Cardiac Anatomy – Apical 5 Chamber – Normal", The POCUS Atlas
  https://www.thepocusatlas.com/normal-cardiac-anatomy
  Hannah Kopinski (MS4), Dr. Lindsay Davis (NYU/Bellevue Department of Emergency Ultrasound),
  Dr. Matthew Riscinti (Kings County Emergency Medicine). Licence CC BY-NC 4.0 (The POCUS
  Atlas); non-commercial use approved for this educational app.

The GIF (521x487, 10 fps, 50 frames, no ECG, no patient data) sweeps slightly between beats.
Frames 9–30 hold two whole beats of ≈ 1.1 s in a steady 5-chamber plane, starting at end-
diastole, so they loop seamlessly. R-waves are at 0 and 1100 ms; end-systole (aortic root /
LVOT fully shown, just before AV closure) at ≈ 400 ms. There is no ECG on the clip, so the
timing is marked nominal (visually estimated from valve motion).

Input:  temp_real/POCUSAtlas_apical5_normal.gif
        (https://images.squarespace-cdn.com/content/v1/58118909e3df282037abfad7/
         1533340735596-NXETNR654CKDAUXS3RD9/apical+5+normal.gif)
Usage:  python3 scripts/convert_pocus_a5c.py
"""

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from convert_real_loops import write_attribution  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "temp_real", "POCUSAtlas_apical5_normal.gif")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "loops", "apical_5c.mp4")
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")
FIRST, LAST, FPS = 9, 30, 10


def main():
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", SRC,
        "-vf", f"select=between(n\\,{FIRST}\\,{LAST}),setpts=N/({FPS}*TB),scale=720:-2,setsar=1,format=yuv420p",
        "-r", str(FPS), "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-an",
        "-movflags", "+faststart", "-map_metadata", "-1", OUT], check=True)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    views = {v["id"]: v for w in db["windows"] for v in w["views"]}
    view = views["apical_5c"]
    view["videoSource"] = {
        "type": "real",
        "title": "Normal Cardiac Anatomy – Apical 5 Chamber – Normal",
        "author": "Hannah Kopinski, Lindsay Davis (NYU/Bellevue), Matthew Riscinti (Kings County)",
        "license": "CC BY-NC 4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by-nc/4.0/",
        "sourceUrl": "https://www.thepocusatlas.com/normal-cardiac-anatomy",
    }
    view["ecg"] = {"beatMs": 1100, "edMs": 0, "esMs": 400, "rWavesMs": [0, 1100], "nominal": True}
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    write_attribution(views)
    print(f"apical_5c: real POCUS Atlas loop, frames {FIRST}-{LAST} -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
