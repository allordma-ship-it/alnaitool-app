import { describe, it, expect } from 'vitest';
import { wrapText, toggleCheck, toggleItem, CHECK_RE } from '../src/text';

// measuring needs a canvas; a fixed-width font is enough for these tests
(globalThis as unknown as { document: unknown }).document = {
  createElement: () => ({ getContext: () => ({ font: '', measureText: (s: string) => ({ width: s.length * 8 }) }) }),
};

describe('checklists', () => {
  it('recognises items and their state', () => {
    expect(CHECK_RE.exec('[ ] milk')?.[1]).toBe(' ');
    expect(CHECK_RE.exec('[x] milk')?.[1]).toBe('x');
    expect(CHECK_RE.exec('milk [ ]')).toBeNull();
  });

  it('wraps an item with room for its box and marks only the first line', () => {
    const lines = wrapText('Title\n[x] Inhalationstechnik prüfen und dokumentieren', 10, 160);
    expect(lines[0]).toMatchObject({ text: 'Title', para: 0 });
    expect(lines[0].box).toBeUndefined();
    const item = lines.filter((l) => l.para === 1);
    expect(item.length).toBeGreaterThan(1);
    expect(item[0]).toMatchObject({ box: true, done: true, indent: 15 });
    expect(item[1].box).toBe(false);
    expect(item.every((l) => !l.text.startsWith('[x]'))).toBe(true);
  });

  it('ticks, unticks and converts items', () => {
    expect(toggleCheck('a\n[ ] b', 1)).toBe('a\n[x] b');
    expect(toggleCheck('a\n[x] b', 1)).toBe('a\n[ ] b');
    expect(toggleCheck('a\nb', 1)).toBe('a\nb');
    expect(toggleItem('مراجعة الدرس', 0)).toBe('[ ] مراجعة الدرس');
    expect(toggleItem('[x] done', 0)).toBe('done');
  });
});
