import { putDoc, putPages, putFile } from './db';
import { A4, uid, type DocMeta, type PageData, type TextBox } from './types';
import { loadPdf, pdfPageSizes, destroyPdf, renderPdfPage } from './pdf';
import { wrapText, LINE_HEIGHT } from './text';
import { renderPageToCanvas, canvasToBytes } from './render';

const MAX_IMAGE_SIDE = 2400;
const MARGIN = 56;

type Kind = 'pdf' | 'image' | 'docx' | 'text' | null;

export function kindOf(f: File): Kind {
  const name = f.name.toLowerCase();
  if (f.type === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
  if (f.type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp|avif)$/.test(name)) return 'image';
  if (name.endsWith('.docx') || f.type.includes('wordprocessingml')) return 'docx';
  if (f.type.startsWith('text/') || /\.(txt|md|markdown|csv|log)$/.test(name)) return 'text';
  return null;
}

const baseName = (f: File) => f.name.replace(/\.[^.]+$/, '');

async function importPdf(file: File): Promise<DocMeta> {
  const buf = await file.arrayBuffer();
  const pdf = await loadPdf(buf);
  const sizes = await pdfPageSizes(pdf);
  let thumb: string | undefined;
  try {
    const c = document.createElement('canvas');
    await renderPdfPage(pdf, 1, c, 240 / sizes[0].w);
    thumb = c.toDataURL('image/jpeg', 0.75);
  } catch { /* thumbnail is optional */ }
  destroyPdf(pdf);
  const id = uid();
  const pages: PageData[] = sizes.map((s, i) => ({ id: uid(), w: s.w, h: s.h, bg: { kind: 'pdf', pdfPage: i + 1 }, strokes: [] }));
  const now = Date.now();
  const doc: DocMeta = {
    id, title: baseName(file), kind: 'pdf', createdAt: now, updatedAt: now,
    pageIds: pages.map((p) => p.id), template: 'blank', paper: 'white', thumb, reading: true,
  };
  await putFile(id, buf);
  await putPages(pages);
  await putDoc(doc);
  return doc;
}

/** Several pictures chosen together become one document, one page per picture (e.g. photographed pages). */
async function importImages(files: File[]): Promise<DocMeta> {
  const pages: PageData[] = [];
  const fileIds: string[] = [];
  for (const f of files) {
    const bmp = await createImageBitmap(f);
    const k = Math.min(1, MAX_IMAGE_SIDE / Math.max(bmp.width, bmp.height));
    const c = document.createElement('canvas');
    c.width = Math.round(bmp.width * k);
    c.height = Math.round(bmp.height * k);
    const ctx = c.getContext('2d')!;
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.drawImage(bmp, 0, 0, c.width, c.height);
    bmp.close();
    const bytes = await canvasToBytes(c, 'image/jpeg', 0.9);
    const fileId = uid();
    await putFile(fileId, bytes.buffer as ArrayBuffer);
    fileIds.push(fileId);
    // landscape pictures get a landscape page so they fill the screen
    const w = c.width >= c.height ? A4.h : A4.w;
    pages.push({ id: uid(), w, h: (w * c.height) / c.width, bg: { kind: 'image', fileId, mime: 'image/jpeg' }, strokes: [] });
  }
  const now = Date.now();
  const doc: DocMeta = {
    id: uid(), title: baseName(files[0]), kind: 'images', createdAt: now, updatedAt: now,
    pageIds: pages.map((p) => p.id), template: 'blank', paper: 'white', fileIds, reading: true,
  };
  await putPages(pages);
  doc.thumb = (await renderPageToCanvas(doc, pages[0], 240 / pages[0].w)).toDataURL('image/jpeg', 0.75);
  await putDoc(doc);
  return doc;
}

interface Block { text: string; size: number; bold?: boolean; gap?: number }

/** Lays text blocks out on A4 pages as editable text boxes (Arabic paragraphs run right-to-left). */
async function importBlocks(title: string, blocks: Block[]): Promise<DocMeta> {
  const W = A4.w - MARGIN * 2;
  const bottom = A4.h - MARGIN;
  const pages: PageData[] = [];
  let texts: TextBox[] = [];
  let y = MARGIN;
  const flush = () => {
    pages.push({ id: uid(), w: A4.w, h: A4.h, bg: { kind: 'template', template: 'blank', paper: 'white' }, strokes: [], texts });
    texts = [];
    y = MARGIN;
  };
  for (const b of blocks) {
    const lh = b.size * LINE_HEIGHT;
    let lines = wrapText(b.text, b.size, W, b.bold).map((l) => l.text);
    y += b.gap ?? 0;
    while (lines.length) {
      let fit = Math.floor((bottom - y) / lh);
      if (fit < 1) {
        flush();
        fit = Math.floor((bottom - y) / lh);
      }
      const part = lines.slice(0, fit);
      lines = lines.slice(fit);
      texts.push({ id: uid(), x: MARGIN, y, w: W, text: part.join('\n'), size: b.size, color: '#1a1a2e', bold: b.bold });
      y += part.length * lh;
      if (lines.length) flush();
    }
  }
  if (texts.length || !pages.length) flush();
  const now = Date.now();
  const doc: DocMeta = {
    id: uid(), title, kind: 'notebook', createdAt: now, updatedAt: now,
    pageIds: pages.map((p) => p.id), template: 'blank', paper: 'white', reading: true,
  };
  await putPages(pages);
  doc.thumb = (await renderPageToCanvas(doc, pages[0], 240 / pages[0].w)).toDataURL('image/jpeg', 0.75);
  await putDoc(doc);
  return doc;
}

async function importText(file: File): Promise<DocMeta> {
  const raw = (await file.text()).replace(/\r\n?/g, '\n');
  const blocks: Block[] = raw.split(/\n{2,}/).map((para) => {
    const m = /^(#{1,3})\s+(.*)$/s.exec(para); // markdown headings
    if (m) return { text: m[2], size: [22, 18, 15][m[1].length - 1], bold: true, gap: 10 };
    return { text: para, size: 12, gap: 8 };
  });
  return importBlocks(baseName(file), blocks);
}

async function importDocx(file: File): Promise<DocMeta> {
  const mammoth = await import('mammoth/mammoth.browser');
  const { value: html } = await mammoth.convertToHtml({ arrayBuffer: await file.arrayBuffer() });
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  const blocks: Block[] = [];
  const walk = (el: Element, prefix = '') => {
    for (const child of el.children) {
      const tag = child.tagName.toLowerCase();
      const text = (child.textContent ?? '').replace(/\s+/g, ' ').trim();
      if (/^h[1-6]$/.test(tag)) {
        if (text) blocks.push({ text, size: [22, 18, 15, 13, 12, 12][+tag[1] - 1], bold: true, gap: 12 });
      } else if (tag === 'p') {
        if (text) blocks.push({ text: prefix + text, size: 12, gap: prefix ? 3 : 8 });
      } else if (tag === 'ul' || tag === 'ol') {
        let n = 0;
        for (const li of child.children) {
          n++;
          const own = [...li.childNodes].filter((c) => !(c instanceof Element && /^(ul|ol)$/i.test(c.tagName))).map((c) => c.textContent ?? '').join(' ').replace(/\s+/g, ' ').trim();
          if (own) blocks.push({ text: `${tag === 'ol' ? `${n}.` : '•'} ${own}`, size: 12, gap: 3 });
          walk(li, '');
        }
      } else if (tag === 'table') {
        for (const tr of child.querySelectorAll('tr')) {
          const row = [...tr.children].map((td) => (td.textContent ?? '').replace(/\s+/g, ' ').trim()).join('  |  ');
          if (row.trim()) blocks.push({ text: row, size: 11, gap: 3 });
        }
      } else walk(child, prefix);
    }
  };
  walk(body);
  if (!blocks.length) blocks.push({ text: '', size: 12 });
  return importBlocks(baseName(file), blocks);
}

/** Imports any mix of files. Returns the created documents and the names that could not be read. */
export async function importFiles(files: File[]): Promise<{ docs: DocMeta[]; failed: string[]; unsupported: string[] }> {
  const docs: DocMeta[] = [], failed: string[] = [], unsupported: string[] = [];
  const images = files.filter((f) => kindOf(f) === 'image');
  const attempt = async (name: string, fn: () => Promise<DocMeta>) => {
    try {
      docs.push(await fn());
    } catch (err) {
      console.error(err);
      failed.push(name);
    }
  };
  if (images.length) await attempt(images[0].name, () => importImages(images));
  for (const f of files) {
    const k = kindOf(f);
    if (k === 'pdf') await attempt(f.name, () => importPdf(f));
    else if (k === 'docx') await attempt(f.name, () => importDocx(f));
    else if (k === 'text') await attempt(f.name, () => importText(f));
    else if (k === null) unsupported.push(f.name);
  }
  return { docs, failed, unsupported };
}
