#!/usr/bin/env python3
"""
EchoScope – pre-rendered Schema images (assets/diagrams/schema/).

The schema anatomy is traced in scripts/gen_schema_svg.py (in the cine loop's own coordinates,
so the step calipers line up). This script renders it OFFLINE in Chromium with the reference's
real SVG filters (feTurbulence speckle, Gaussian glow – see scripts/reference/
subcostal_4ch_ohne_labels.svg) into fixed PNGs, so the app shows identical pixels on every phone
and needs no SVG renderer:

  diagrams/schema/<view>_schema.png         clean schema ("ohne Labels"), 1080 px wide
  diagrams/schema/<view>_schema_labels.png  transparent label layer (pills + leader lines),
                                            same size, drawn on top when Labels = AN

The views' "schemaAsset" / "schemaAspect" / "schemaCrop" are updated; the crop is unchanged,
so caliper coordinates stay valid.
Usage: python3 scripts/export_schema_png.py   (requires Node + the pre-installed Playwright)
"""

import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(__file__))
import gen_schema_svg as g  # noqa: E402

OUT = os.path.join(g.ASSETS, "diagrams", "schema")
WIDTH = 1080

JS = r"""
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const jobs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const j of jobs) {
    const svg = fs.readFileSync(j.svg, 'utf8');
    await page.setViewportSize({ width: j.w, height: j.h });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ',
      `<svg style="display:block;width:${j.w}px;height:${j.h}px" `)}</body></html>`);
    await page.screenshot({ path: j.png, omitBackground: true });
  }
  await browser.close();
})();
"""


def label_only(svg: str) -> str:
    """Strip everything but the label layer (keeps the viewBox so pills land on the same pixels)."""
    head = svg[:svg.index(">") + 1]
    start = svg.index("<!--labels-->") if "<!--labels-->" in svg else None
    if start is None:
        raise SystemExit("label marker missing")
    return head + svg[start:]


def main():
    g.RASTER = True
    os.makedirs(OUT, exist_ok=True)
    with open(g.DB, encoding="utf-8") as f:
        db = json.load(f)
    specs = {**g.VIEWS, **g.simulated_views()}
    jobs = []
    tmp = tempfile.mkdtemp()
    for window in db["windows"]:
        for view in window["views"]:
            vid = view["id"]
            loop = os.path.join(g.ASSETS, view["videoAsset"])
            probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries",
                                    "stream=width,height", "-of", "csv=p=0", loop], capture_output=True, text=True)
            width, height = (int(v) for v in probe.stdout.strip().split(","))
            overlays = [o["video"] for st in view["steps"]
                        for o in ([st["caliperOverlay"]] if st.get("caliperOverlay") else []) + st.get("extraOverlays", [])
                        if o.get("video")]
            crop = g.crop_box(vid, specs[vid], overlays, width, height)
            clean = g.render(vid, specs[vid], width, height, crop, labels=False)
            labelled = g.render(vid, specs[vid], width, height, crop, labels=True)
            aspect = (crop[2] - crop[0]) * width / ((crop[3] - crop[1]) * height)
            h = round(WIDTH / aspect)
            for name, svg in ((f"{vid}_schema", clean), (f"{vid}_schema_labels", label_only(labelled))):
                path = os.path.join(tmp, name + ".svg")
                with open(path, "w", encoding="utf-8") as f:
                    f.write(svg)
                jobs.append({"svg": path, "png": os.path.join(OUT, name + ".png"), "w": WIDTH, "h": h})
            view["schemaAsset"] = f"diagrams/schema/{vid}_schema.png"
            view["schemaCrop"] = crop
            view["schemaAspect"] = round(aspect, 4)
    jobs_path = os.path.join(tmp, "jobs.json")
    with open(jobs_path, "w") as f:
        json.dump(jobs, f)
    js = os.path.join(tmp, "r.js")
    with open(js, "w") as f:
        f.write(JS)
    env = dict(os.environ, NODE_PATH=subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip())
    subprocess.run(["node", js, jobs_path], check=True, env=env)
    # Clean schema: opaque, so drop the alpha channel and compress.
    from PIL import Image
    for j in jobs:
        im = Image.open(j["png"])
        if j["png"].endswith("_schema.png"):
            im = im.convert("RGB").quantize(colors=200, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)
        im.save(j["png"], optimize=True)
    with open(g.DB, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=2)
        f.write("\n")
    total = sum(os.path.getsize(j["png"]) for j in jobs)
    print(f"{len(jobs)} PNGs in {os.path.relpath(OUT, g.ROOT)} ({total / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
