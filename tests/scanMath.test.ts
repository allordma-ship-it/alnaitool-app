import { describe, it, expect } from 'vitest';
import { otsu, detectPage, homography, mapPoint, warp, outputSize, documentFilter, defaultQuad, type Quad } from '../src/scanMath';

describe('scanner maths', () => {
  it('splits dark and light with Otsu', () => {
    const g = new Uint8Array([...Array(50).fill(30), ...Array(50).fill(220)]);
    const t = otsu(g);
    expect(t).toBeGreaterThanOrEqual(30);
    expect(t).toBeLessThan(220);
  });

  it('finds a tilted bright page on a dark desk', () => {
    const w = 100, h = 120, g = new Uint8Array(w * h).fill(40);
    // a quadrilateral page: corners near (20,10) (85,18) (78,110) (12,100)
    const page: Quad = [[20, 10], [85, 18], [78, 110], [12, 100]];
    const inside = (x: number, y: number) => {
      // point in convex polygon
      for (let i = 0; i < 4; i++) {
        const [ax, ay] = page[i], [bx, by] = page[(i + 1) % 4];
        if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0) return false;
      }
      return true;
    };
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (inside(x + 0.5, y + 0.5)) g[y * w + x] = 235;
    const q = detectPage(g, w, h)!;
    expect(q).not.toBeNull();
    q.forEach((p, i) => {
      expect(Math.abs(p[0] - page[i][0])).toBeLessThan(4);
      expect(Math.abs(p[1] - page[i][1])).toBeLessThan(4);
    });
  });

  it('gives up when there is no page', () => {
    expect(detectPage(new Uint8Array(400).fill(200), 20, 20)).toBeNull();
  });

  it('maps the output corners exactly onto the quad', () => {
    const q: Quad = [[10, 5], [90, 12], [95, 140], [3, 120]];
    const H = homography(q, 200, 300);
    const out = [mapPoint(H, 0, 0), mapPoint(H, 200, 0), mapPoint(H, 200, 300), mapPoint(H, 0, 300)];
    out.forEach((p, i) => { expect(p[0]).toBeCloseTo(q[i][0], 6); expect(p[1]).toBeCloseTo(q[i][1], 6); });
  });

  it('straightens: a sub-rectangle warps to its own pixels', () => {
    const sw = 8, sh = 8, src = new Uint8ClampedArray(sw * sh * 4);
    for (let i = 0; i < sw * sh; i++) { src[i * 4] = i; src[i * 4 + 3] = 255; }
    const out = warp(src, sw, sh, [[2, 2], [6, 2], [6, 6], [2, 6]], 4, 4);
    expect(out[0]).toBe(2 * 8 + 2); // pixel (2,2)
    expect(out[(3 * 4 + 3) * 4]).toBe(5 * 8 + 5); // pixel (5,5)
  });

  it('keeps the page shape when sizing the output', () => {
    expect(outputSize([[0, 0], [1000, 0], [1000, 1414], [0, 1414]])).toEqual({ w: 1000, h: 1414 });
    expect(outputSize([[0, 0], [3000, 0], [3000, 4000], [0, 4000]]).h).toBe(2000);
    expect(defaultQuad(100, 200)[2]).toEqual([96, 192]);
  });

  it('document filter: shadowed paper turns white, ink stays dark', () => {
    const w = 64, h = 64, px = new Uint8ClampedArray(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const light = 130 + x * 1.8; // a shadow on the left
      const ink = x > 28 && x < 34 && y > 20 && y < 44;
      const v = ink ? light * 0.25 : light;
      const i = (y * w + x) * 4;
      px[i] = px[i + 1] = px[i + 2] = v; px[i + 3] = 255;
    }
    documentFilter(px, w, h);
    expect(px[(10 * w + 4) * 4]).toBeGreaterThan(235); // shadowed paper
    expect(px[(10 * w + 60) * 4]).toBeGreaterThan(235); // bright paper
    expect(px[(30 * w + 31) * 4]).toBeLessThan(40); // ink
  });
});
