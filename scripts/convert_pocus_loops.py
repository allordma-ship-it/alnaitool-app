#!/usr/bin/env python3
"""
EchoScope – real cine loops from The POCUS Atlas (https://www.thepocusatlas.com, CC BY-NC 4.0;
non-commercial use approved for this educational app). Downloads: temp_real/pocus/<name>.gif
with captions in temp_real/pocus/meta.json (sitemap image captions = credits).

  * 2D view loops replace the simulated loops (psax_av; apical_5c is in convert_pocus_a5c.py),
  * colour Doppler loops go to assets/loops/color/ for the valve module and the colour toggle.

None of the clips has an ECG: R-waves are set from valve motion (aortic valve opening) and the
timing is flagged nominal. Every clip was checked frame by frame (debug_calipers/*).
Usage: python3 scripts/convert_pocus_loops.py
"""

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from convert_real_loops import write_attribution  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "temp_real", "pocus")
LOOPS = os.path.join(ROOT, "app", "src", "main", "assets", "loops")
DB = os.path.join(ROOT, "app", "src", "main", "assets", "echo_protocol_full.json")
LICENSE = {"license": "CC BY-NC 4.0", "licenseUrl": "https://creativecommons.org/licenses/by-nc/4.0/"}

# view id -> (gif, first frame, last frame, author, ecg)
VIEW_LOOPS = {
    # Philips S4-2 PSAX at aortic-valve level: AV opens at frames 1–2 and 12–13 → R at 0 / 1100 ms.
    "psax_av": ("psax_av_2", 0, 20, "Justin Bowra MBBS, FACEM, CCPU (RNSH) et al.",
                {"beatMs": 1100, "edMs": 0, "esMs": 350, "rWavesMs": [0, 1100], "nominal": True}),
}

# Nyquist limits read from each clip's colour bar; views whose cockpit offers the colour toggle.
COLOR_INFO = {
    "a4c_mv_color": (52, ["apical_4c"], "MV"),
    "a4c_tv_color": (60, ["apical_4c"], "TV"),
    "a5c_av_color": (60, ["apical_5c"], "AV"),
    "plax_color": (65, ["plax_standard"], "AV"),
}

# colour loop id -> (gif, first, last, author)
COLOR_LOOPS = {
    # A4C (GE), colour box over the mitral valve: moderate MR jet into the LA.
    "a4c_mv_color": ("mr_moderate_2", 0, 29, "Sukh Singh, MD"),
    # A4C (Philips), colour box over the tricuspid valve: TR jet into the RA.
    "a4c_tv_color": ("tr_modsev_a4c", 0, 13, "Justin Bowra MBBS, FACEM, CCPU (RNSH) et al."),
    # A5C (Philips, labelled A5C), colour box over LVOT / aortic valve: mild AR jet.
    "a5c_av_color": ("ar_mild", 0, 11, "Justin Bowra MBBS, FACEM, CCPU (RNSH) et al. (Dr. Mo Haywood)"),
    # PLAX (Philips, labelled PLAX), colour over LVOT / aortic valve: moderate AR jet.
    "plax_color": ("ar_moderate", 0, 19, "Justin Bowra MBBS, FACEM, CCPU (RNSH) et al. (Dr. Giles, Dr. Jacob)"),
}


def encode(gif, first, last, out):
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", os.path.join(SRC, gif + ".gif"),
        "-vf", f"select=between(n\\,{first}\\,{last}),setpts=N/(10*TB),scale=720:-2,setsar=1,format=yuv420p",
        "-r", "10", "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-an",
        "-movflags", "+faststart", "-map_metadata", "-1", out], check=True)


def main():
    with open(os.path.join(SRC, "meta.json"), encoding="utf-8") as f:
        meta = json.load(f)
    with open(DB, encoding="utf-8") as f:
        db = json.load(f)
    views = {v["id"]: v for w in db["windows"] for v in w["views"]}
    for vid, (gif, a, b, author, ecg) in VIEW_LOOPS.items():
        encode(gif, a, b, os.path.join(LOOPS, f"{vid}.mp4"))
        m = meta[gif]
        views[vid]["videoSource"] = {"type": "real", "title": m["caption"].split(" Justin")[0][:120],
                                     "author": author, **LICENSE, "sourceUrl": m["page"]}
        views[vid]["ecg"] = ecg
        print(f"real  {vid} <- {gif}")
    os.makedirs(os.path.join(LOOPS, "color"), exist_ok=True)
    color = {}
    for cid, (gif, a, b, author) in COLOR_LOOPS.items():
        encode(gif, a, b, os.path.join(LOOPS, "color", f"{cid}.mp4"))
        m = meta[gif]
        nyquist, views_for, valve = COLOR_INFO[cid]
        color[cid] = {"asset": f"loops/color/{cid}.mp4", "title": m["caption"][:160], "author": author,
                      **LICENSE, "sourceUrl": m["page"], "nyquistCmS": nyquist, "views": views_for, "valve": valve}
        print(f"color {cid} <- {gif}")
    db["colorLoops"] = color
    with open(DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    write_attribution(views)
    with open(os.path.join(LOOPS, "ATTRIBUTION.txt"), "a", encoding="utf-8") as f:
        f.write("\nColour Doppler loops (The POCUS Atlas, CC BY-NC 4.0):\n")
        for cid, c in color.items():
            f.write(f"loops/color/{cid}.mp4\n  Source : {c['title']}\n  Author : {c['author']}\n  URL    : {c['sourceUrl']}\n")


if __name__ == "__main__":
    main()
