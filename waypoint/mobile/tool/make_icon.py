"""Renders the Wanees (ونيس) app icon: a winding road joining two travellers.

Outputs (1024 px):
  assets/icon/icon.png        full icon (gradient background) for iOS / legacy Android
  assets/icon/foreground.png  transparent foreground for Android adaptive icons

Run:  python3 tool/make_icon.py   (requires Pillow)
"""
import math
import pathlib

from PIL import Image, ImageDraw, ImageFilter

SIZE = 1024
SS = 4  # supersampling factor for smooth edges
BLUE = (11, 110, 153)
TEAL = (0, 168, 143)
WHITE = (255, 255, 255)
SUN = (255, 200, 87)

out = pathlib.Path(__file__).resolve().parent.parent / "assets" / "icon"
out.mkdir(parents=True, exist_ok=True)


def bezier(p0, p1, p2, p3, steps=400):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def thick_line(draw, pts, width, fill):
    r = width / 2
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        draw.line([(x0, y0), (x1, y1)], fill=fill, width=int(width))
    for x, y in pts[:: max(1, len(pts) // 200)] + [pts[-1]]:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def foreground(scale: float) -> Image.Image:
    """Road + travellers centred, `scale` = fraction of the canvas used."""
    S = SIZE * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = S / 2
    k = S * scale

    def P(x, y):  # coordinates in [-0.5, 0.5] around the centre
        return (c + x * k, c + y * k)

    road = bezier(P(-0.36, 0.40), P(0.30, 0.38), P(-0.40, -0.10), P(0.30, -0.30))
    thick_line(d, road, 0.16 * k, WHITE)
    # Dashed centre line
    dash = (*BLUE, 255)
    seg = len(road) // 14
    for i in range(1, 14, 2):
        part = road[i * seg : (i + 1) * seg - seg // 3]
        if len(part) > 1:
            thick_line(d, part, 0.022 * k, dash)

    # Start: a traveller waiting (circle with ring)
    sx, sy = P(-0.36, 0.40)
    r = 0.085 * k
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=WHITE)
    r2 = 0.045 * k
    d.ellipse([sx - r2, sy - r2, sx + r2, sy + r2], fill=(*TEAL, 255))

    # Destination: map pin
    ex, ey = P(0.30, -0.30)
    pr = 0.11 * k
    tip = (ex, ey + 0.20 * k)
    d.polygon([(ex - pr * 0.82, ey + pr * 0.55), (ex + pr * 0.82, ey + pr * 0.55), tip], fill=(*SUN, 255))
    d.ellipse([ex - pr, ey - pr, ex + pr, ey + pr], fill=(*SUN, 255))
    hr = 0.045 * k
    d.ellipse([ex - hr, ey - hr, ex + hr, ey + hr], fill=WHITE)

    return img.resize((SIZE, SIZE), Image.LANCZOS)


def background() -> Image.Image:
    S = SIZE
    grad = Image.new("RGB", (S, S))
    px = grad.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2 * S)
            px[x, y] = tuple(int(BLUE[i] + (TEAL[i] - BLUE[i]) * t) for i in range(3))
    # Soft light from the top-left
    glow = Image.new("L", (S, S), 0)
    ImageDraw.Draw(glow).ellipse([-S * 0.3, -S * 0.3, S * 0.7, S * 0.7], fill=60)
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.12))
    return Image.composite(Image.new("RGB", (S, S), WHITE), grad, glow)


bg = background()
full = bg.convert("RGBA")
full.alpha_composite(foreground(0.78))
full.convert("RGB").save(out / "icon.png", optimize=True)

# Adaptive icons crop to a circle/squircle: keep content in the inner ~60 %.
foreground(0.48).save(out / "foreground.png", optimize=True)
print("written:", out / "icon.png", out / "foreground.png")
