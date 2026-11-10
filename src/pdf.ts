import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { getFile } from './db';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

const cache = new Map<string, Promise<PDFDocumentProxy>>();
const tasks = new WeakMap<PDFDocumentProxy, { destroy(): Promise<void> }>();

export async function loadPdf(data: ArrayBuffer): Promise<PDFDocumentProxy> {
  // pdf.js transfers the buffer to its worker, so hand it a copy.
  const task = pdfjs.getDocument({ data: new Uint8Array(data.slice(0)) });
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
