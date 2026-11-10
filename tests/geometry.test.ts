import { describe, it, expect } from 'vitest';
import { makeStroke, strokeHit, eraseFromStroke, lassoSelects, translateStroke, pointInPolygon } from '../src/geometry';

const line = () => makeStroke('pen', '#000', 2, [0, 0, 0.5, 10, 0, 0.5, 20, 0, 0.5, 30, 0, 0.5, 40, 0, 0.5]);

describe('geometry', () => {
  it('hits a stroke near its centerline only', () => {
    expect(strokeHit(line(), 15, 2, 2)).toBe(true);
    expect(strokeHit(line(), 15, 20, 2)).toBe(false);
  });

  it('precise eraser splits a stroke in two', () => {
    const parts = eraseFromStroke(line(), 20, 0, 3)!;
    expect(parts).toHaveLength(2);
    expect(parts[0].pts.slice(-3)[0]).toBeLessThan(20);
    expect(parts[1].pts[0]).toBeGreaterThan(20);
  });

  it('precise eraser leaves untouched strokes alone', () => {
    expect(eraseFromStroke(line(), 20, 50, 3)).toBeNull();
  });

  it('erasing a whole stroke yields no pieces', () => {
    expect(eraseFromStroke(line(), 20, 0, 40)).toEqual([]);
  });

  it('lasso selects strokes mostly inside', () => {
    const poly = [-5, -5, 50, -5, 50, 5, -5, 5];
    expect(pointInPolygon(1, 1, poly)).toBe(true);
    expect(lassoSelects(line(), poly)).toBe(true);
    expect(lassoSelects(line(), [100, 100, 200, 100, 200, 200])).toBe(false);
  });

  it('translates points and bbox', () => {
    const s = translateStroke(line(), 5, 7);
    expect(s.pts[0]).toBe(5);
    expect(s.pts[1]).toBe(7);
    expect(s.bbox[1]).toBe(line().bbox[1] + 7);
  });
});

import { straightenStroke } from '../src/geometry';

describe('straightenStroke', () => {
  const wobbly = (n: number, f: (i: number) => [number, number]) =>
    Array.from({ length: n }, (_, i) => [...f(i), 0.5]).flat();

  it('flattens a slightly wobbly, slightly tilted highlight to a horizontal line', () => {
    const out = straightenStroke(wobbly(40, (i) => [100 + i * 4, 200 + i * 0.1 + Math.sin(i) * 1.5]), 'highlighter')!;
    const ys = out.filter((_, i) => i % 3 === 1);
    expect(Math.max(...ys) - Math.min(...ys)).toBeLessThan(1e-9);
    expect(out[0]).toBeCloseTo(100);
    expect(out.at(-3)).toBeCloseTo(256);
  });

  it('straightens a pen underline but leaves handwriting alone', () => {
    expect(straightenStroke(wobbly(30, (i) => [50 + i * 3, 300 + Math.sin(i / 2)]), 'pen')).not.toBeNull();
    // a wave (like a written word) is not a line
    expect(straightenStroke(wobbly(30, (i) => [50 + i * 3, 300 + Math.sin(i / 2) * 9]), 'pen')).toBeNull();
    // a vertical pen stroke (e.g. alif, l) is never touched
    expect(straightenStroke(wobbly(30, (i) => [50, 300 + i * 2]), 'pen')).toBeNull();
    // short dashes stay as drawn
    expect(straightenStroke(wobbly(5, (i) => [50 + i * 3, 300]), 'pen')).toBeNull();
  });

  it('keeps a diagonal highlight diagonal but straight', () => {
    const out = straightenStroke(wobbly(30, (i) => [i * 3, i * 3 + Math.sin(i)]), 'highlighter')!;
    expect(out.at(-2)! - out[1]).toBeGreaterThan(60);
  });
});
