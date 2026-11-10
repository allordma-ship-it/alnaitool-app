import { PDFDocument, rgb, LineCapStyle, BlendMode, type PDFPage } from 'pdf-lib';
import type { DocMeta, PageData, Stroke } from './types';
import { getFile } from './db';
import { drawTemplate, HIGHLIGHT_ALPHA, widthAt, inkColorForPaper } from './ink';
import { settings } from './settings';
import { Capacitor } from '@capacitor/core';

type Map2 = (x: number, y: number) => { x: number; y: number };

function hex(c: string) {
  const m = /^#?([0-9a-f]{6})$/i.exec(c);
  const n = m ? parseInt(m[1], 16) : 0;
  return rgb(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255);
}

/** Map from our page coords (pdf.js viewport at scale 1, y down) to PDF user space. */
function pageMapper(page: PDFPage): Map2 {
  const box = page.getCropBox();
  const x0 = box.x, y0 = box.y, x1 = box.x + box.width, y1 = box.y + box.height;
  const r = ((page.getRotation().angle % 360) + 360) % 360;
  switch (r) {
    case 90: return (vx, vy) => ({ x: x0 + vy, y: y0 + vx });
    case 180: return (vx, vy) => ({ x: x1 - vx, y: y0 + vy });
    case 270: return (vx, vy) => ({ x: x1 - vy, y: y1 - vx });
    default: return (vx, vy) => ({ x: x0 + vx, y: y1 - vy });
  }
}

function drawInk(page: PDFPage, strokes: Stroke[], map: Map2, colorFor: (c: string) => string) {
  const ordered = [...strokes.filter((s) => s.tool === 'highlighter'), ...strokes.filter((s) => s.tool !== 'highlighter')];
  for (const s of ordered) {
    const p = s.pts;
    const color = hex(colorFor(s.color));
    if (s.tool === 'highlighter') {
      let d = '';
      for (let i = 0; i < p.length; i += 3) {
        const q = map(p[i], p[i + 1]);
        d += `${i ? 'L' : 'M'}${q.x.toFixed(2)} ${(-q.y).toFixed(2)} `;
      }
      if (p.length === 3) d += 'l0.1 0';
      page.drawSvgPath(d, { x: 0, y: 0, borderColor: color, borderWidth: s.width, borderOpacity: HIGHLIGHT_ALPHA, borderLineCap: LineCapStyle.Butt, blendMode: BlendMode.Multiply });
      continue;
    }
    if (p.length === 3) {
      const q = map(p[0], p[1]);
      page.drawCircle({ x: q.x, y: q.y, size: widthAt(s.width, p[2], settings.pressure) / 2, color });
      continue;
    }
    for (let i = 3; i < p.length; i += 3) {
      page.drawLine({
        start: map(p[i - 3], p[i - 2]),
        end: map(p[i], p[i + 1]),
        thickness: widthAt(s.width, (p[i - 1] + p[i + 2]) / 2, settings.pressure),
        color,
        lineCap: LineCapStyle.Round,
      });
    }
  }
}

/** Canvas-like adapter so drawTemplate() can emit vector PDF content. */
function templateAdapter(page: PDFPage, h: number) {
  let path: number[][] = [];
  let arcs: [number, number, number][] = [];
  const parse = (c: unknown) => hex(String(c));
  const ad = {
    fillStyle: '#fff' as unknown as string,
    strokeStyle: '#000' as unknown as string,
    lineWidth: 1,
    fillRect(x: number, y: number, w: number, hh: number) {
      page.drawRectangle({ x, y: h - y - hh, width: w, height: hh, color: parse(ad.fillStyle) });
    },
    beginPath() { path = []; arcs = []; },
    moveTo(x: number, y: number) { path.push([x, y]); },
    lineTo(x: number, y: number) { path[path.length - 1]?.push(x, y); },
    stroke() {
      for (const seg of path)
        for (let i = 2; i < seg.length; i += 2)
          page.drawLine({ start: { x: seg[i - 2], y: h - seg[i - 1] }, end: { x: seg[i], y: h - seg[i + 1] }, thickness: ad.lineWidth, color: parse(ad.strokeStyle) });
    },
    arc(x: number, y: number, r: number) { arcs.push([x, y, r]); },
    fill() { for (const [x, y, r] of arcs) page.drawCircle({ x, y: h - y, size: r, color: parse(ad.fillStyle) }); },
  };
  return ad;
}

export async function exportDocToPdf(meta: DocMeta, pages: PageData[]): Promise<Uint8Array> {
  const out = await PDFDocument.create();
  let src: PDFDocument | null = null;
  if (meta.kind === 'pdf') {
    const buf = await getFile(meta.id);
    if (buf) src = await PDFDocument.load(buf, { ignoreEncryption: true });
  }
  for (const pd of pages) {
    let page: PDFPage;
    let map: Map2;
    let colorFor = (c: string) => c;
    if (pd.bg.kind === 'pdf' && src) {
      [page] = await out.copyPages(src, [pd.bg.pdfPage - 1]);
      out.addPage(page);
      map = pageMapper(page);
    } else {
      page = out.addPage([pd.w, pd.h]);
      const bg = pd.bg.kind === 'template' ? pd.bg : { template: 'blank' as const, paper: 'white' as const };
      drawTemplate(templateAdapter(page, pd.h), pd.w, pd.h, bg.template, bg.paper);
      map = (x, y) => ({ x, y: pd.h - y });
      colorFor = (c) => inkColorForPaper(pd.bg, c);
    }
    drawInk(page, pd.strokes, map, colorFor);
  }
  out.setTitle(meta.title);
  out.setProducer('Alnai Notes');
  return out.save();
}

function toBase64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

/** Inside the Android app: write to the cache dir and open the system share sheet (save to Files, Drive, mail…). */
async function shareNative(bytes: Uint8Array, filename: string) {
  const { Filesystem, Directory } = await import('@capacitor/filesystem');
  const { Share } = await import('@capacitor/share');
  const { uri } = await Filesystem.writeFile({ path: filename, data: toBase64(bytes), directory: Directory.Cache });
  try {
    await Share.share({ title: filename, files: [uri], dialogTitle: filename });
  } catch (e) {
    if (!/cancel/i.test(String(e))) throw e;
  }
}

export async function shareOrDownload(bytes: Uint8Array, filename: string) {
  if (Capacitor.isNativePlatform()) return shareNative(bytes, filename);
  const blob = new Blob([bytes as BlobPart], { type: 'application/pdf' });
  const file = new File([blob], filename, { type: 'application/pdf' });
  const nav = navigator as Navigator & { canShare?: (d: ShareData) => boolean };
  if (nav.canShare?.({ files: [file] })) {
    try {
      await nav.share({ files: [file], title: filename });
      return;
    } catch (e) {
      if ((e as DOMException).name === 'AbortError') return;
    }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}
