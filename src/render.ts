import type { DocMeta, PageData, Stroke } from './types';
import { getFile } from './db';
import { drawStrokes, drawTemplate, inkColorForPaper } from './ink';
import { drawTextBox, drawBubble } from './text';
import { pdfForDoc, renderPdfPage } from './pdf';
import { settings } from './settings';

const bitmaps = new Map<string, Promise<ImageBitmap>>();

export function imageBitmapFor(fileId: string, mime: string): Promise<ImageBitmap> {
  let p = bitmaps.get(fileId);
  if (!p) {
    p = getFile(fileId).then((buf) => {
      if (!buf) throw new Error('missing image');
      return createImageBitmap(new Blob([buf], { type: mime }));
    });
    bitmaps.set(fileId, p);
    p.catch(() => bitmaps.delete(fileId));
  }
  return p;
}

export function clearImageCache() {
  for (const p of bitmaps.values()) p.then((b) => b.close()).catch(() => {});
  bitmaps.clear();
}

export function displayStroke(p: PageData, s: Stroke): Stroke {
  const c = inkColorForPaper(p.bg, s.color);
  return c === s.color ? s : { ...s, color: c };
}

/** Ink + typed text of a page; ctx already carries the page→pixel transform. */
export function drawPageContent(ctx: CanvasRenderingContext2D, p: PageData, exclude?: Set<string>) {
  const strokes = (exclude ? p.strokes.filter((s) => !exclude.has(s.id)) : p.strokes).map((s) => displayStroke(p, s));
  // note bubbles sit under the ink, so you can handwrite inside them
  for (const tb of p.texts ?? []) if (tb.bubble && !exclude?.has(tb.id)) drawBubble(ctx, tb);
  drawStrokes(ctx, strokes, settings.pressure);
  for (const tb of p.texts ?? []) {
    if (exclude?.has(tb.id)) continue;
    drawTextBox(ctx, tb, tb.bubble ? tb.color : inkColorForPaper(p.bg, tb.color));
  }
}

/** Full page (background + ink + text) on a fresh canvas — for thumbnails and image export. */
export async function renderPageToCanvas(meta: DocMeta, p: PageData, scale: number): Promise<HTMLCanvasElement> {
  const c = document.createElement('canvas');
  c.width = Math.round(p.w * scale);
  c.height = Math.round(p.h * scale);
  const ctx = c.getContext('2d')!;
  if (p.bg.kind === 'pdf') {
    const tmp = document.createElement('canvas');
    await renderPdfPage(await pdfForDoc(meta.id), p.bg.pdfPage, tmp, scale);
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(tmp, 0, 0, c.width, c.height);
  } else if (p.bg.kind === 'image') {
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(await imageBitmapFor(p.bg.fileId, p.bg.mime), 0, 0, c.width, c.height);
  } else {
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    drawTemplate(ctx, p.w, p.h, p.bg.template, p.bg.paper, p.bg.rtl);
  }
  ctx.setTransform(scale, 0, 0, scale, 0, 0);
  drawPageContent(ctx, p);
  return c;
}

export function canvasToBytes(c: HTMLCanvasElement, type = 'image/png', quality?: number): Promise<Uint8Array> {
  return new Promise((res, rej) =>
    c.toBlob(async (b) => (b ? res(new Uint8Array(await b.arrayBuffer())) : rej(new Error('encode failed'))), type, quality),
  );
}
