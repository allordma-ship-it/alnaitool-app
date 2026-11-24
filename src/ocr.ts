import { getTextIndex, putTextIndex, getPages } from './db';
import { renderPageToCanvas } from './render';
import { settings } from './settings';
import type { DocMeta, OcrPage, PageData, TextIndex } from './types';

/** Languages of the bundled models (Tesseract codes). */
export type OcrLang = 'ara' | 'deu' | 'eng';
export const OCR_LANGS: OcrLang[] = ['ara', 'deu', 'eng'];

const LANG_KEY = 'notiva-ocr-langs';

export function ocrLangs(): OcrLang[] {
  try {
    const v = JSON.parse(localStorage.getItem(LANG_KEY) ?? 'null') as OcrLang[] | null;
    if (Array.isArray(v) && v.length && v.every((l) => OCR_LANGS.includes(l))) return v;
  } catch { /* fall through */ }
  return settings.lang === 'ar' ? ['ara', 'eng'] : settings.lang === 'de' ? ['deu', 'eng'] : ['eng'];
}
export function setOcrLangs(l: OcrLang[]) {
  try { localStorage.setItem(LANG_KEY, JSON.stringify(l)); } catch { /* private mode */ }
}

/** WebAssembly SIMD (every recent Android WebView): picks the faster engine build. */
function hasSimd(): boolean {
  try {
    return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]));
  } catch {
    return false;
  }
}

type Worker = Awaited<ReturnType<typeof import('tesseract.js').createWorker>>;
let worker: { langs: string; w: Promise<Worker> } | null = null;

async function getWorker(langs: OcrLang[]): Promise<Worker> {
  const key = [...langs].sort().join('+');
  if (worker?.langs === key) return worker.w;
  if (worker) void worker.w.then((w) => w.terminate()).catch(() => {});
  const base = new URL('ocr/', location.href).href;
  const w = import('tesseract.js').then(({ createWorker, OEM }) =>
    createWorker(key, OEM.LSTM_ONLY, {
      // everything comes from inside the app: no internet needed
      workerPath: `${base}worker.min.js`,
      corePath: `${base}${hasSimd() ? 'tesseract-core-simd-lstm.wasm.js' : 'tesseract-core-lstm.wasm.js'}`,
      langPath: base.replace(/\/$/, ''),
      gzip: true,
      cacheMethod: 'none',
      workerBlobURL: false,
    }),
  );
  worker = { langs: key, w };
  w.catch(() => { if (worker?.w === w) worker = null; });
  return w;
}

/** Frees the engine's memory (it is large) when you are done. */
export async function stopOcr() {
  const w = worker;
  worker = null;
  if (w) await w.w.then((x) => x.terminate()).catch(() => {});
}

interface BBoxLine { text: string; bbox: { x0: number; y0: number; x1: number; y1: number } }

/** Reads the text of one picture. `k` turns picture pixels into page coordinates. */
export async function recognize(img: HTMLCanvasElement, langs: OcrLang[], k: number): Promise<OcrPage> {
  const w = await getWorker(langs);
  const { data } = await w.recognize(img, {}, { text: true, blocks: true });
  const lines: OcrPage['lines'] = [];
  for (const b of data.blocks ?? []) for (const p of b.paragraphs ?? []) for (const l of (p.lines ?? []) as BBoxLine[]) {
    const text = l.text.replace(/\s+/g, ' ').trim();
    if (text) lines.push({ text, x0: l.bbox.x0 * k, y0: l.bbox.y0 * k, x1: l.bbox.x1 * k, y1: l.bbox.y1 * k });
  }
  return { text: lines.map((l) => l.text).join('\n') || data.text.trim(), lines, langs };
}

/** Pages worth reading: pictures and PDF pages (a scanned PDF has pictures of text). */
const readable = (p: PageData) => p.bg.kind === 'image' || p.bg.kind === 'pdf';

/**
 * Reads every picture page of a document and keeps the text with the search index, so it can be searched,
 * clipped and shared as text. `onProgress(done, total)` reports each page.
 */
export async function ocrDocument(doc: DocMeta, langs: OcrLang[], onProgress?: (done: number, total: number) => void): Promise<number> {
  // a PDF's own text first, so the index has it (recognition only adds what the PDF lacks)
  if (doc.kind === 'pdf') await (await import('./search')).indexDoc(doc).catch(() => null);
  const pages = (await getPages(doc.pageIds)).filter((p) => p && readable(p));
  const ix: TextIndex = (await getTextIndex(doc.id)) ?? { docId: doc.id, byPdfPage: [], pageMap: [], key: '' };
  ix.ocr ??= {};
  let found = 0;
  for (let i = 0; i < pages.length; i++) {
    onProgress?.(i, pages.length);
    const p = pages[i];
    // about 2000 px across: sharp enough for small print, small enough for a tablet's memory
    const scale = Math.min(3, 2000 / p.w);
    const c = await renderPageToCanvas(doc, p, scale);
    const res = await recognize(c, langs, 1 / scale);
    c.width = 0;
    ix.ocr[p.id] = res;
    if (res.text) found++;
    await putTextIndex(ix);
  }
  onProgress?.(pages.length, pages.length);
  return found;
}

/** Text of a scanned page inside a rectangle (for clips), from its recognised lines. */
export async function ocrTextIn(docId: string, pageId: string, r: [number, number, number, number]): Promise<string> {
  const page = (await getTextIndex(docId))?.ocr?.[pageId];
  if (!page) return '';
  return page.lines
    .filter((l) => { const cx = (l.x0 + l.x1) / 2, cy = (l.y0 + l.y1) / 2; return cx >= r[0] && cx <= r[2] && cy >= r[1] && cy <= r[3]; })
    .map((l) => l.text).join(' ');
}

/** All recognised text of a document, page by page (for "share as text"). */
export async function ocrText(doc: DocMeta): Promise<string> {
  const o = (await getTextIndex(doc.id))?.ocr;
  if (!o) return '';
  return doc.pageIds.map((id) => o[id]?.text ?? '').filter(Boolean).join('\n\n');
}
