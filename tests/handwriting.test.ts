import { describe, it, expect } from 'vitest';
import { makeStroke } from '../src/geometry';
import { groupLines } from '../src/handwriting';
import { isRtl } from '../src/text';

const word = (x: number, y: number) => makeStroke('pen', '#000', 2, [x, y, 0.5, x + 20, y + 14, 0.5, x + 40, y, 0.5]);

describe('handwriting', () => {
  it('groups strokes into lines and keeps writing order within a line', () => {
    const a = word(200, 100), b = word(100, 102), c = word(120, 160), dot = makeStroke('pen', '#000', 2, [130, 104, 0.5]);
    const lines = groupLines([a, b, c, dot]);
    expect(lines).toHaveLength(2);
    expect(lines[0].map((s) => s.id)).toEqual([a.id, b.id, dot.id]);
    expect(lines[1][0].id).toBe(c.id);
  });

  it('detects paragraph direction from the first strong character', () => {
    expect(isRtl('مرحبا world')).toBe(true);
    expect(isRtl('12. Hello مرحبا')).toBe(false);
    expect(isRtl('123')).toBe(false);
  });
});
