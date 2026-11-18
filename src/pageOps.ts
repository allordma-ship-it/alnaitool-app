import type { Clip, PageData } from './types';
import { uid } from './types';

/** Moves one item to a new position (the position it should have afterwards). */
export function moveItem<T>(arr: T[], from: number, to: number): T[] {
  const out = arr.slice();
  if (from < 0 || from >= out.length) return out;
  const [x] = out.splice(from, 1);
  out.splice(Math.max(0, Math.min(out.length, to)), 0, x);
  return out;
}

/** A full copy of a page with new ids. Image pages keep pointing at the same stored picture. */
export function duplicatePage(p: PageData): PageData {
  const copy: PageData = structuredClone(p);
  copy.id = uid();
  copy.strokes = copy.strokes.map((s) => ({ ...s, id: uid() }));
  if (copy.texts) copy.texts = copy.texts.map((tb) => ({ ...tb, id: uid() }));
  return copy;
}

/**
 * After pages were moved, inserted or deleted: the clips whose page index changed, with the index fixed.
 * A clip whose page was deleted keeps the nearest page that still exists.
 */
export function remapClips(clips: Clip[], oldIds: string[], newIds: string[]): Clip[] {
  const changed: Clip[] = [];
  for (const c of clips) {
    const id = c.pageId ?? oldIds[c.page];
    let page = id ? newIds.indexOf(id) : -1;
    let pageId = id;
    if (page < 0) {
      // the page is gone: the nearest surviving page before it, else the first page
      page = 0;
      for (let i = Math.min(c.page, oldIds.length) - 1; i >= 0; i--) {
        const j = newIds.indexOf(oldIds[i]);
        if (j >= 0) { page = j; break; }
      }
      pageId = newIds[page];
    }
    if (page !== c.page || pageId !== c.pageId) changed.push({ ...c, page, pageId });
  }
  return changed;
}

/** True if no other page still shows this stored picture (so it may be deleted). */
export function fileUnused(pages: PageData[], fileId: string): boolean {
  return !pages.some((p) => p.bg.kind === 'image' && p.bg.fileId === fileId);
}
