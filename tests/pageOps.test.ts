import { describe, it, expect } from 'vitest';
import { moveItem, duplicatePage, remapClips, fileUnused } from '../src/pageOps';
import type { Clip, PageData } from '../src/types';

const clip = (page: number, pageId?: string): Clip => ({ id: `c${page}`, kind: 'clip', docId: 'd', docTitle: 'D', page, pageId, note: '', createdAt: 0 });

describe('page operations', () => {
  it('moves items forwards and backwards', () => {
    expect(moveItem(['a', 'b', 'c', 'd'], 0, 2)).toEqual(['b', 'c', 'a', 'd']);
    expect(moveItem(['a', 'b', 'c', 'd'], 3, 0)).toEqual(['d', 'a', 'b', 'c']);
    expect(moveItem(['a', 'b'], 1, 99)).toEqual(['a', 'b']);
  });

  it('duplicates a page deeply with fresh ids', () => {
    const p: PageData = { id: 'p1', w: 10, h: 10, bg: { kind: 'pdf', pdfPage: 3 }, strokes: [{ id: 's', tool: 'pen', color: '#000', width: 1, pts: [1, 2, 3], bbox: [0, 0, 1, 1] }] };
    const d = duplicatePage(p);
    expect(d.id).not.toBe('p1');
    expect(d.strokes[0].id).not.toBe('s');
    d.strokes[0].pts.push(9);
    expect(p.strokes[0].pts).toEqual([1, 2, 3]);
    expect(d.bg).toEqual({ kind: 'pdf', pdfPage: 3 });
  });

  it('keeps clips on their page when pages move', () => {
    const changed = remapClips([clip(0), clip(2, 'c')], ['a', 'b', 'c'], ['c', 'a', 'b']);
    expect(changed.map((c) => [c.page, c.pageId])).toEqual([[1, 'a'], [0, 'c']]);
  });

  it('moves clips of a deleted page to the nearest page before it', () => {
    const [c] = remapClips([clip(2)], ['a', 'b', 'c', 'd'], ['a', 'b', 'd']);
    expect([c.page, c.pageId]).toEqual([1, 'b']);
    const [first] = remapClips([clip(0)], ['a', 'b'], ['b']);
    expect([first.page, first.pageId]).toEqual([0, 'b']);
  });

  it('reports nothing when nothing changed', () => {
    expect(remapClips([clip(1, 'b')], ['a', 'b'], ['a', 'b'])).toEqual([]);
  });

  it('knows when a picture is still used by a duplicated page', () => {
    const img = (id: string): PageData => ({ id, w: 1, h: 1, bg: { kind: 'image', fileId: 'f', mime: 'image/jpeg' }, strokes: [] });
    expect(fileUnused([img('x')], 'f')).toBe(false);
    expect(fileUnused([], 'f')).toBe(true);
  });
});

import { placeImage } from '../src/pageImages';

describe('placing a picture', () => {
  const page = { w: 595, h: 842 };
  it('is at most 60 % of the page wide, keeps its shape and stays on the page', () => {
    const r = placeImage(4000, 3000, page, 300, 400);
    expect(r.w).toBeCloseTo(357, 0);
    expect(r.h / r.w).toBeCloseTo(0.75, 3);
    expect(r.x).toBeGreaterThanOrEqual(20);
    expect(r.x + r.w).toBeLessThanOrEqual(575);
  });
  it('shrinks a very tall picture to fit the page height', () => {
    const r = placeImage(1000, 10000, page, 300, 400);
    expect(r.h).toBeLessThanOrEqual(802);
    expect(r.y).toBeGreaterThanOrEqual(20);
  });
  it('small pictures are not blown up past half their pixel size', () => {
    expect(placeImage(200, 100, page, 300, 400).w).toBe(100);
  });
});
