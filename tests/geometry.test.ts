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
