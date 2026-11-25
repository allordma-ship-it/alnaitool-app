import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { getFile } from './db';

let lib: Promise<typeof import('pdfjs-dist')> | null = null;
/** pdf.js is large (~430 KB): it is loaded the first time a PDF is needed, not when the app starts. */
function pdfjs() {
  return lib ??= import('pdfjs-dist').then((m) => {
    m.GlobalWorkerOptions.workerSrc = workerUrl;
    return m;
  });
}

const cache = new Map<string, Promise<PDFDocumentProxy>>();
const tasks = new WeakMap<PDFDocumentProxy, { destroy(): Promise<void> }>();

export async function loadPdf(data: ArrayBuffer): Promise<PDFDocumentProxy> {
  // pdf.js transfers the buffer to its worker, so hand it a copy.
  const task = (await pdfjs()).getDocument({ data: new Uint8Array(data.slice(0)) });
  const doc = await task.promise;
  tasks.set(doc, task);
  return doc;
}

export function destroyPdf(doc: PDFDocumentProxy) {
  void tasks.get(doc)?.destroy();
}

export function pdfForDoc(docId: string): Promise<PDFDocumentProxy> {
  let p = cache.get(docId);
  if (!p) {
    p = getFile(docId).then((buf) => {
      if (!buf) throw new Error('missing pdf');
      return loadPdf(buf);
    });
    cache.set(docId, p);
    p.catch(() => cache.delete(docId));
  }
  return p;
}

export function closePdf(docId: string) {
  for (const k of linkCache.keys()) if (k.startsWith(`${docId}:`)) linkCache.delete(k);
  const p = cache.get(docId);
  cache.delete(docId);
  p?.then(destroyPdf).catch(() => {});
}

/** Page sizes in PDF points, honouring /Rotate. */
export async function pdfPageSizes(doc: PDFDocumentProxy) {
  const sizes: { w: number; h: number }[] = [];
  for (let i = 1; i <= doc.numPages; i++) {
    const vp = (await doc.getPage(i)).getViewport({ scale: 1 });
    sizes.push({ w: vp.width, h: vp.height });
  }
  return sizes;
}

/** Renders a PDF page into `canvas` at `scale` device pixels per PDF point. */
export async function renderPdfPage(doc: PDFDocumentProxy, pageNo: number, canvas: HTMLCanvasElement, scale: number, signal?: { cancelled: boolean }) {
  const page = await doc.getPage(pageNo);
  if (signal?.cancelled) return;
  const viewport = page.getViewport({ scale });
  const tmp = document.createElement('canvas');
  tmp.width = Math.round(viewport.width);
  tmp.height = Math.round(viewport.height);
  await page.render({ canvas: tmp, viewport }).promise;
  if (signal?.cancelled) return;
  // swap in one go to avoid a blank flash while rendering
  canvas.width = tmp.width;
  canvas.height = tmp.height;
  canvas.getContext('2d')!.drawImage(tmp, 0, 0);
}

export interface TextLine {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
  /** font height */
  h: number;
  text: string;
}

interface Item { x0: number; y0: number; x1: number; y1: number; base: number; h: number; str: string; rtl: boolean }

const RTL = /[֐-ࣿיִ-﷿ﹰ-ﻼ]/;

/** Text items of a PDF page as boxes in our page coordinates (pdf.js viewport at scale 1). */
async function pdfItems(docId: string, pageNo: number): Promise<Item[]> {
  return itemsOf(await pdfForDoc(docId), pageNo);
}

async function itemsOf(doc: PDFDocumentProxy, pageNo: number): Promise<Item[]> {
  const page = await doc.getPage(pageNo);
  const vp = page.getViewport({ scale: 1 });
  const content = await page.getTextContent();
  const { Util } = await pdfjs();
  const out: Item[] = [];
  for (const it of content.items) {
    if (!('str' in it) || !it.str.trim()) continue;
    const m = Util.transform(vp.transform, it.transform);
    const h = Math.hypot(m[2], m[3]) || Math.abs(it.height) || 10;
    // horizontal text only (rotated labels in figures are skipped)
    if (Math.abs(m[1]) > Math.abs(m[0]) * 0.2) continue;
    const w = Math.abs(it.width) * (Math.hypot(m[0], m[1]) / (Math.hypot(it.transform[0], it.transform[1]) || 1));
    const x = m[4], base = m[5];
    out.push({ x0: Math.min(x, x + w), x1: Math.max(x, x + w), y0: base - h, y1: base + h * 0.22, base, h, str: it.str, rtl: it.dir === 'rtl' || RTL.test(it.str) });
  }
  return out;
}

/** Groups items into visual lines; right-to-left lines are ordered right to left. */
function toLines(items: Item[]): TextLine[] {
  const sorted = [...items].sort((a, b) => a.base - b.base || a.x0 - b.x0);
  const groups: Item[][] = [];
  for (const it of sorted) {
    const g = groups.find((l) => Math.abs(l[0].base - it.base) < Math.min(l[0].h, it.h) * 0.45);
    if (g) g.push(it);
    else groups.push([it]);
  }
  return groups
    .map((g) => {
      const rtl = g.filter((i) => i.rtl).length * 2 > g.length;
      g.sort((a, b) => (rtl ? b.x0 - a.x0 : a.x0 - b.x0));
      let text = '';
      g.forEach((it, i) => {
        if (i) {
          const prev = g[i - 1];
          const gap = rtl ? prev.x0 - it.x1 : it.x0 - prev.x1;
          if (gap > it.h * 0.12 && !text.endsWith(' ') && !it.str.startsWith(' ')) text += ' ';
        }
        text += it.str;
      });
      return {
        x0: Math.min(...g.map((i) => i.x0)), x1: Math.max(...g.map((i) => i.x1)),
        y0: Math.min(...g.map((i) => i.y0)), y1: Math.max(...g.map((i) => i.y1)),
        h: Math.max(...g.map((i) => i.h)), text: text.replace(/\s+/g, ' ').trim(),
      };
    })
    .sort((a, b) => a.y0 - b.y0);
}

export async function pdfTextLines(docId: string, pageNo: number): Promise<TextLine[]> {
  return toLines(await pdfItems(docId, pageNo));
}

/**
 * The text of every page, read from a separate copy of the PDF. It must not use (and then close) the copy the
 * reader is showing: closing that one while the document is open made every further page fail to load.
 */
export async function pdfAllText(docId: string): Promise<string[]> {
  const buf = await getFile(docId);
  if (!buf) throw new Error('missing pdf');
  const doc = await loadPdf(buf);
  const out: string[] = [];
  try {
    for (let i = 1; i <= doc.numPages; i++) {
      try {
        out.push(joinLines(toLines(await itemsOf(doc, i))));
      } catch {
        out.push('');
      }
    }
  } finally {
    destroyPdf(doc);
  }
  return out;
}

/** Text inside a rectangle (items whose centre lies inside it). */
export async function pdfTextIn(docId: string, pageNo: number, r: [number, number, number, number]): Promise<string> {
  const items = (await pdfItems(docId, pageNo)).filter((i) => {
    const cx = (i.x0 + i.x1) / 2, cy = (i.y0 + i.y1) / 2;
    return cx >= r[0] && cx <= r[2] && cy >= r[1] && cy <= r[3];
  });
  return joinLines(toLines(items));
}

/** Joins lines into flowing text, mending words hyphenated across lines. */
export function joinLines(lines: TextLine[]): string {
  let out = '';
  for (const l of lines) {
    if (!out) out = l.text;
    else if (/[A-Za-z]-$/.test(out)) out = out.slice(0, -1) + l.text;
    else out += ' ' + l.text;
  }
  return out;
}

/**
 * The paragraph a pen line was drawn under/through: start at the closest text line, then grow up and
 * down while the line spacing stays tight and the font size stays the same.
 */
export function paragraphAt(lines: TextLine[], sx0: number, sx1: number, sy: number): TextLine[] | null {
  let seed = -1, best = Infinity;
  lines.forEach((l, i) => {
    if (Math.min(l.x1, sx1) - Math.max(l.x0, sx0) <= 0) return;
    const d = sy < l.y0 ? l.y0 - sy : sy > l.y1 ? sy - l.y1 : 0;
    if (d < best && d < l.h * 1.1) { best = d; seed = i; }
  });
  if (seed < 0) return null;
  const same = (a: TextLine, b: TextLine) =>
    Math.abs(a.h - b.h) < Math.max(a.h, b.h) * 0.22 && Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0) > 0;
  let lo = seed, hi = seed;
  while (lo > 0 && same(lines[lo - 1], lines[lo]) && lines[lo].y0 - lines[lo - 1].y1 < lines[lo].h * 0.75) lo--;
  while (hi < lines.length - 1 && same(lines[hi + 1], lines[hi]) && lines[hi + 1].y0 - lines[hi].y1 < lines[hi].h * 0.75) hi++;
  return lines.slice(lo, hi + 1);
}

export interface OutlineItem {
  title: string;
  /** PDF page number (1-based), null if the entry points nowhere */
  pdfPage: number | null;
  /** where on that page the entry starts, in our page coordinates (top = 0), if the PDF says */
  y: number | null;
  items: OutlineItem[];
}

/** A destination inside the PDF (a name or an explicit array) as page number + height on the page. */
async function resolveDest(doc: PDFDocumentProxy, raw: unknown): Promise<{ pdfPage: number | null; y: number | null }> {
  try {
    const dest = typeof raw === 'string' ? await doc.getDestination(raw) : raw;
    if (!Array.isArray(dest) || dest[0] == null) return { pdfPage: null, y: null };
    const ref = dest[0];
    const idx = typeof ref === 'number' ? ref : await doc.getPageIndex(ref);
    const pdfPage = idx + 1;
    const kind = (dest[1] as { name?: string } | undefined)?.name;
    const top = kind === 'XYZ' ? dest[3] : kind === 'FitH' || kind === 'FitBH' ? dest[2] : null;
    let y: number | null = null;
    if (typeof top === 'number') {
      const vp = (await doc.getPage(pdfPage)).getViewport({ scale: 1 });
      y = Math.max(0, vp.convertToViewportPoint(0, top)[1]);
    }
    return { pdfPage, y };
  } catch {
    return { pdfPage: null, y: null };
  }
}

/** The PDF's own table of contents (bookmarks panel in other readers), with every entry resolved to a page. */
export async function pdfOutline(docId: string): Promise<OutlineItem[]> {
  const doc = await pdfForDoc(docId);
  const raw = await doc.getOutline();
  if (!raw?.length) return [];
  let budget = 3000; // very large outlines: stop resolving after this many entries
  type Raw = (typeof raw)[number];
  const resolve = async (it: Raw, depth: number): Promise<OutlineItem> => {
    const target = budget-- > 0 ? await resolveDest(doc, it.dest) : { pdfPage: null, y: null };
    const kids = depth < 6 ? await Promise.all((it.items ?? []).map((k) => resolve(k, depth + 1))) : [];
    return { title: (it.title || '—').trim(), ...target, items: kids };
  };
  return Promise.all(raw.map((it) => resolve(it, 0)));
}

export interface PdfLink {
  /** our page coordinates [x0, y0, x1, y1] */
  rect: [number, number, number, number];
  /** a web address … */
  url?: string;
  /** … or a place in this PDF */
  pdfPage?: number;
  y?: number | null;
}

const linkCache = new Map<string, Promise<PdfLink[]>>();

/** Clickable links on a PDF page: web addresses and jumps inside the document (references, contents). */
export function pdfLinks(docId: string, pageNo: number): Promise<PdfLink[]> {
  const key = `${docId}:${pageNo}`;
  let p = linkCache.get(key);
  if (!p) {
    p = (async () => {
      const doc = await pdfForDoc(docId);
      const page = await doc.getPage(pageNo);
      const vp = page.getViewport({ scale: 1 });
      const out: PdfLink[] = [];
      for (const a of await page.getAnnotations({ intent: 'display' })) {
        if (a.subtype !== 'Link' || !a.rect) continue;
        const [x0, y0] = vp.convertToViewportPoint(a.rect[0], a.rect[1]);
        const [x1, y1] = vp.convertToViewportPoint(a.rect[2], a.rect[3]);
        const rect: PdfLink['rect'] = [Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)];
        if (rect[2] - rect[0] < 2 || rect[3] - rect[1] < 2) continue;
        const url = (a.url || a.unsafeUrl) as string | undefined;
        if (url && /^(https?:|mailto:)/i.test(url)) out.push({ rect, url });
        else if (a.dest) {
          const t = await resolveDest(doc, a.dest);
          if (t.pdfPage) out.push({ rect, pdfPage: t.pdfPage, y: t.y });
        }
      }
      return out;
    })();
    linkCache.set(key, p);
    p.catch(() => linkCache.delete(key));
  }
  return p;
}
