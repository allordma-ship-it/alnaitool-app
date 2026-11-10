import { describe, it, expect } from 'vitest';
import { recognizeShape } from '../src/geometry';

const jitter = (i: number) => Math.sin(i * 1.7) * 1.2;
const flat = (pts: [number, number][]) => pts.flatMap(([x, y], i) => [x + jitter(i), y + jitter(i + 3), 0.5]);
const along = (poly: [number, number][], per = 12, close = true) => {
  const ring = close ? [...poly, poly[0]] : poly;
  const out: [number, number][] = [];
  for (let i = 0; i < ring.length - 1; i++)
    for (let k = 0; k < per; k++) out.push([ring[i][0] + ((ring[i + 1][0] - ring[i][0]) * k) / per, ring[i][1] + ((ring[i + 1][1] - ring[i][1]) * k) / per]);
  out.push(ring[ring.length - 1]);
  return out;
};

describe('recognizeShape', () => {
  it('line, snapped to horizontal', () => {
    const r = recognizeShape(flat(along([[10, 10], [200, 18]], 30, false)))!;
    expect(r.kind).toBe('line');
    expect(r.pts.at(-2)).toBeCloseTo(r.pts[1], 5);
  });
  it('rectangle (starting mid-edge) becomes axis aligned', () => {
    const r = recognizeShape(flat(along([[60, 20], [150, 20], [150, 110], [20, 110], [20, 20]], 10)))!;
    expect(r.kind).toBe('rectangle');
  });
  it('circle', () => {
    const pts: [number, number][] = Array.from({ length: 50 }, (_, i) => [100 + 60 * Math.cos((i / 48) * Math.PI * 2), 100 + 60 * Math.sin((i / 48) * Math.PI * 2)]);
    expect(recognizeShape(flat(pts))!.kind).toBe('circle');
  });
  it('ellipse', () => {
    const pts: [number, number][] = Array.from({ length: 50 }, (_, i) => [100 + 90 * Math.cos((i / 48) * Math.PI * 2), 100 + 40 * Math.sin((i / 48) * Math.PI * 2)]);
    expect(recognizeShape(flat(pts))!.kind).toBe('ellipse');
  });
  it('triangle', () => {
    expect(recognizeShape(flat(along([[100, 20], [180, 160], [20, 160]], 14)))!.kind).toBe('triangle');
  });
  it('scribble stays freehand', () => {
    const pts: [number, number][] = Array.from({ length: 40 }, (_, i) => [i * 5, (i % 4) * 12]);
    expect(recognizeShape(flat(pts))).toBeNull();
  });
});
