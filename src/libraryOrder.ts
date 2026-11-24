import { DOC_COLORS, type DocMeta } from './types';

export type SortMode = 'edited' | 'created' | 'name' | 'color';
export const SORT_MODES: SortMode[] = ['edited', 'created', 'name', 'color'];

/** Pinned documents first (most recently pinned order kept by the mode), then the chosen order. */
export function sortDocs(docs: DocMeta[], mode: SortMode, locale?: string): DocMeta[] {
  const coll = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' });
  const colorRank = (d: DocMeta) => (d.color ? DOC_COLORS.indexOf(d.color) : DOC_COLORS.length);
  const by: Record<SortMode, (a: DocMeta, b: DocMeta) => number> = {
    edited: (a, b) => b.updatedAt - a.updatedAt,
    created: (a, b) => b.createdAt - a.createdAt,
    name: (a, b) => coll.compare(a.title, b.title),
    color: (a, b) => colorRank(a) - colorRank(b) || b.updatedAt - a.updatedAt,
  };
  return docs.slice().sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned) || by[mode](a, b));
}

/** Every tag in use, most used first, then alphabetically. */
export function allTags(docs: DocMeta[], locale?: string): string[] {
  const count = new Map<string, number>();
  for (const d of docs) for (const t of d.tags ?? []) count.set(t, (count.get(t) ?? 0) + 1);
  const coll = new Intl.Collator(locale, { sensitivity: 'base' });
  return [...count.keys()].sort((a, b) => count.get(b)! - count.get(a)! || coll.compare(a, b));
}

/** Cleans tags typed by the user: trimmed, no "#", no empty ones, no duplicates (ignoring case). */
export function cleanTags(input: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of input) {
    const t = raw.trim().replace(/^#+/, '').replace(/\s+/g, ' ').trim().slice(0, 40);
    const key = t.toLocaleLowerCase();
    if (t && !seen.has(key)) { seen.add(key); out.push(t); }
  }
  return out;
}
