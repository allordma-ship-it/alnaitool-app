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

import { curveThrough, curveWarp, bend, detectEdgeMids, splitHalves, curveLength } from '../src/scanMath';

describe('curved book pages', () => {
  it('the edge curve passes through its middle handle', () => {
    const p = curveThrough([0, 0], [50, -20], [100, 0], 0.5);
    expect(p[0]).toBeCloseTo(50, 6);
    expect(p[1]).toBeCloseTo(-20, 6);
    expect(curveThrough([0, 0], [50, -20], [100, 0], 0)).toEqual([0, 0]);
  });

  it('a straight page has no bend; a flat curve warp equals the plain stretch', () => {
    const q: Quad = [[0, 0], [8, 0], [8, 8], [0, 8]];
    expect(bend(q, [4, 0], [4, 8])).toBe(0);
    const sw = 8, sh = 8, src = new Uint8ClampedArray(64 * 4);
    for (let i = 0; i < 64; i++) { src[i * 4] = i * 3; src[i * 4 + 3] = 255; }
    const out = curveWarp(src, sw, sh, q, [4, 0], [4, 8], 8, 8);
    expect(out[(5 * 8 + 6) * 4]).toBe(src[(5 * 8 + 6) * 4]);
  });

  it('a bent top edge is followed: the curved line comes out straight', () => {
    // a dark line along a curve y = 10 + 0.002 (x - 50)^2 … sagging in the middle like a book page
    const sw = 100, sh = 60, src = new Uint8ClampedArray(sw * sh * 4).fill(255);
    const curveY = (x: number) => 15 - 0.004 * (x - 50) ** 2 + 10;
    for (let x = 0; x < sw; x++) { const y = Math.round(curveY(x)); const i = (y * sw + x) * 4; src[i] = src[i + 1] = src[i + 2] = 0; }
    const q: Quad = [[0, curveY(0) - 5], [99, curveY(99) - 5], [99, 59], [0, 59]];
    const out = curveWarp(src, sw, sh, q, [50, curveY(50) - 5], [50, 59], 100, 50);
    // after straightening, the dark line sits at one height across the page
    const rowOf = (x: number) => { let best = 0, v = 999; for (let y = 0; y < 50; y++) { const c = out[(y * 100 + x) * 4]; if (c < v) { v = c; best = y; } } return best; };
    expect(Math.abs(rowOf(10) - rowOf(50))).toBeLessThanOrEqual(1);
    expect(Math.abs(rowOf(90) - rowOf(50))).toBeLessThanOrEqual(1);
  });

  it('finds the real middle of a sagging top edge', () => {
    const w = 80, h = 100, g = new Uint8Array(w * h).fill(30);
    for (let x = 10; x < 70; x++) { const top = 10 + Math.round(0.01 * (x - 40) ** 2 * -1 + 9); for (let y = top; y < 90; y++) g[y * w + x] = 230; }
    const q: Quad = [[10, 10], [70, 10], [70, 90], [10, 90]];
    const { top, bottom } = detectEdgeMids(g, w, h, q);
    expect(top[1]).toBeGreaterThan(15); // sags below the straight line between the corners
    expect(Math.abs(bottom[1] - 90)).toBeLessThanOrEqual(1);
  });

  it('splits an open book in two and measures curved edges', () => {
    expect(splitHalves(101)).toEqual([{ x: 0, w: 50 }, { x: 50, w: 51 }]);
    expect(curveLength([0, 0], [50, 0], [100, 0])).toBeCloseTo(100, 6);
    expect(curveLength([0, 0], [50, 20], [100, 0])).toBeGreaterThan(100);
  });
});
