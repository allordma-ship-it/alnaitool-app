/**
 * Text matching for library search. Pure functions, no storage, so they are easy to test.
 *
 * Arabic is matched the way people type it: diacritics (tashkeel) and tatweel are ignored, the alef forms
 * (أ إ آ ٱ) count as ا, ى as ي and ة as ه. Latin letters ignore case and accents (ü = u).
 */
const DROP = /[ً-ٰٟـ̀-ͯ]/;
const MAP: Record<string, string> = { 'أ': 'ا', 'إ': 'ا', 'آ': 'ا', 'ٱ': 'ا', 'ى': 'ي', 'ة': 'ه', 'ؤ': 'و', 'ئ': 'ي', 'ß': 'ss' };

/** Normalised text plus, for every normalised character, its position in the original. */
export function normalize(s: string): { norm: string; at: number[] } {
  let norm = '';
  const at: number[] = [];
  const src = s.normalize('NFD');
  // NFD can change lengths, so positions refer to the NFD form; snippets are cut from it too
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (DROP.test(ch)) continue;
    let out = MAP[ch] ?? ch.toLowerCase();
    if (/\s/.test(out)) {
      if (norm.endsWith(' ')) continue;
      out = ' ';
    }
    for (const c of out) { norm += c; at.push(i); }
  }
  return { norm, at };
}

export function normQuery(q: string): string {
  return normalize(q).norm.trim();
}

export interface Snippet {
  before: string;
  match: string;
  after: string;
}

/** Finds `q` (already normalised) in `text`; returns up to `max` snippets with some context around each hit. */
export function findSnippets(text: string, q: string, max = 3, context = 48): { count: number; snippets: Snippet[] } {
  if (!q || !text) return { count: 0, snippets: [] };
  const src = text.normalize('NFD');
  const { norm, at } = normalize(text);
  const snippets: Snippet[] = [];
  let count = 0;
  for (let i = norm.indexOf(q); i >= 0; i = norm.indexOf(q, i + q.length)) {
    count++;
    if (snippets.length >= max) continue;
    const s = at[i], e = at[i + q.length - 1] + 1;
    let b = Math.max(0, s - context), a = Math.min(src.length, e + context);
    // do not cut words in half
    while (b > 0 && b > s - context - 12 && !/\s/.test(src[b - 1])) b--;
    while (a < src.length && a < e + context + 12 && !/\s/.test(src[a])) a++;
    snippets.push({
      before: (b > 0 ? '…' : '') + src.slice(b, s).normalize('NFC'),
      match: src.slice(s, e).normalize('NFC'),
      after: src.slice(e, a).normalize('NFC') + (a < src.length ? '…' : ''),
    });
  }
  return { count, snippets };
}
