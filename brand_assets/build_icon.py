#!/usr/bin/env python3
"""
EchoScope brand icon – built from the approved master artwork (brand_assets/echoscope_icon_master.png:
white anatomical heart, transducer on the upper right with its cable to the top-right corner,
cyan → emerald acoustic waves leaving the transducer face into the heart, cyan → emerald ECG
behind the heart, navy-slate squircle).

Outputs
  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png   adaptive foreground (108 dp layer)
  res/mipmap-{…}/ic_launcher_monochrome.png                     Android 13 themed icon (alpha only)
  res/values/ic_launcher_colors.xml                             background colour #16222F
  brand_assets/echoscope_icon_512.png                           store / preview icon

Processing
  * the generator's sparkle watermark (bottom-right) and the white page outside the rounded
    corners are filled in from the surrounding navy (OpenCV inpainting), giving a full-bleed square;
  * the square is placed at 80 / 108 of the adaptive layer (larger than the 72 dp visible area): the launcher mask shows the central
    72 dp, so heart, probe and waves stay inside every mask shape (circle, squircle, teardrop);
  * the monochrome layer keeps the bright artwork (heart, probe, waves, ECG) as alpha.

The Top App Bar uses the vector rendition in EchoScopeLogo.kt (crisp at any size).
Usage: python3 brand_assets/build_icon.py
"""

import os

import cv2
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
MASTER = os.path.join(HERE, "echoscope_icon_master.png")
BACKGROUND = "#16222F"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
ART_FRACTION = 80 / 108


def full_bleed(path: str) -> Image.Image:
    a = np.asarray(Image.open(path).convert("RGB"))
    h, w = a.shape[:2]
    # white page outside the rounded corners (connected to the image corners)
    white = ((a.min(axis=2) > 225)).astype(np.uint8)
    n, labels = cv2.connectedComponents(white, connectivity=4)
    corner_ids = {labels[0, 0], labels[0, -1], labels[-1, 0], labels[-1, -1]} - {0}
    mask = np.isin(labels, list(corner_ids)).astype(np.uint8)
    # sparkle watermark in the bottom-right corner (light grey, low saturation)
    y0, x0 = int(h * 0.90), int(w * 0.90)
    region = a[y0:, x0:].astype(int)
    grey = (region.min(axis=2) > 110) & (region.max(axis=2) - region.min(axis=2) < 30)
    mask[y0:, x0:] |= grey.astype(np.uint8)
    mask = cv2.dilate(mask, np.ones((9, 9), np.uint8))
    fixed = cv2.inpaint(cv2.cvtColor(a, cv2.COLOR_RGB2BGR), mask * 255, 25, cv2.INPAINT_TELEA)
    return Image.fromarray(cv2.cvtColor(fixed, cv2.COLOR_BGR2RGB))


def layer(art: Image.Image, size: int, mode: str) -> Image.Image:
    inner = round(size * ART_FRACTION)
    art = art.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    if mode == "foreground":
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        # feather the outer 3 % so the square edge never shows against the background colour
        alpha = np.full((inner, inner), 255, np.float32)
        ramp = max(2, inner // 33)
        for i in range(ramp):
            v = 255 * (i + 1) / (ramp + 1)
            alpha[i, :] = np.minimum(alpha[i, :], v)
            alpha[-1 - i, :] = np.minimum(alpha[-1 - i, :], v)
            alpha[:, i] = np.minimum(alpha[:, i], v)
            alpha[:, -1 - i] = np.minimum(alpha[:, -1 - i], v)
        rgba = art.convert("RGBA")
        rgba.putalpha(Image.fromarray(alpha.astype(np.uint8)))
        out.paste(rgba, (off, off), rgba)
        return out
    # monochrome: bright artwork (heart, probe, waves, ECG) as white with alpha
    a = np.asarray(art.convert("RGB")).astype(np.float32)
    lum = a @ np.array([0.299, 0.587, 0.114], np.float32)
    sat = (a.max(axis=2) - a.min(axis=2)) / np.maximum(a.max(axis=2), 1)
    bright = np.clip((lum - 95) / 80, 0, 1)
    glow = np.clip((sat - 0.45) / 0.3, 0, 1) * np.clip((lum - 70) / 60, 0, 1)
    alpha = (np.maximum(bright, glow) * 255).astype(np.uint8)
    mono = Image.new("RGBA", (inner, inner), (255, 255, 255, 0))
    mono.putalpha(Image.fromarray(alpha))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(mono, (off, off), mono)
    return out


def main():
    art = full_bleed(MASTER)
    for density, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        layer(art, px, "foreground").save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
        layer(art, px, "monochrome").save(os.path.join(folder, "ic_launcher_monochrome.png"), optimize=True)
    with open(os.path.join(RES, "values", "ic_launcher_colors.xml"), "w") as f:
        f.write(f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <!-- Adaptive launcher icon background (brand_assets/build_icon.py). -->\n'
                f'    <color name="ic_launcher_background">{BACKGROUND}</color>\n</resources>\n')
    # store icon: full-bleed art masked to a rounded square
    store = art.resize((512, 512), Image.LANCZOS).convert("RGBA")
    m = Image.new("L", (512, 512), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(m).rounded_rectangle([0, 0, 511, 511], radius=96, fill=255)
    store.putalpha(m)
    store.save(os.path.join(HERE, "echoscope_icon_512.png"), optimize=True)
    art.resize((1024, 1024), Image.LANCZOS).save(os.path.join(HERE, "echoscope_icon_fullbleed_1024.png"), optimize=True)
    print("launcher icon written")


if __name__ == "__main__":
    main()
