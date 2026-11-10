import { PDFDocument, rgb, LineCapStyle, BlendMode, type PDFPage } from 'pdf-lib';
import type { DocMeta, PageData, Stroke, TextBox } from './types';
import { getFile } from './db';
import { drawTemplate, HIGHLIGHT_ALPHA, widthAt, inkColorForPaper } from './ink';
import { settings } from './settings';
import { drawTextBox, textHeight, drawBubble, bubbleRect } from './text';
import { canvasToBytes } from './render';

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

/**
 * Typed text is embedded as a crisp image: the browser shapes Arabic and mixed-direction text correctly,
 * which a PDF library without a shaping engine cannot.
 */
async function drawTexts(out: PDFDocument, page: PDFPage, texts: TextBox[], map: Map2, bubbleColor: (c: string) => string, colorFor: (c: string) => string) {
  const S = 3;
  for (const tb of texts) {
    const hgt = textHeight(tb);
    if (!tb.text.trim() || hgt <= 0) continue;
    const pad = 2;
    const c = document.createElement('canvas');
    c.width = Math.ceil((tb.w + pad * 2) * S);
    c.height = Math.ceil((hgt + pad * 2) * S);
    const ctx = c.getContext('2d')!;
    ctx.setTransform(S, 0, 0, S, (pad - tb.x) * S, (pad - tb.y) * S);
    drawTextBox(ctx, tb, (tb.bubble ? bubbleColor : colorFor)(tb.color));
    const img = await out.embedPng(await canvasToBytes(c));
    // corners in our page space → PDF space (also correct on rotated PDF pages for 0°)
    const bl = map(tb.x - pad, tb.y + hgt + pad);
    page.drawImage(img, { x: bl.x, y: bl.y, width: tb.w + pad * 2, height: hgt + pad * 2 });
  }
}

/** Note bubbles go under the ink (so handwriting inside them stays visible), also as images. */
async function drawBubbles(out: PDFDocument, page: PDFPage, texts: TextBox[], map: Map2) {
  const S = 3;
  for (const tb of texts) {
    if (!tb.bubble) continue;
    const [bx, by, bw, bh] = bubbleRect(tb);
    const x0 = Math.min(bx, tb.bubble.ax) - 4, y0 = Math.min(by, tb.bubble.ay) - 4;
    const x1 = Math.max(bx + bw, tb.bubble.ax) + 4, y1 = Math.max(by + bh, tb.bubble.ay) + 4;
    const c = document.createElement('canvas');
    c.width = Math.ceil((x1 - x0) * S);
    c.height = Math.ceil((y1 - y0) * S);
    const ctx = c.getContext('2d')!;
    ctx.setTransform(S, 0, 0, S, -x0 * S, -y0 * S);
    drawBubble(ctx, tb);
    const img = await out.embedPng(await canvasToBytes(c));
    const bl = map(x0, y1);
    page.drawImage(img, { x: bl.x, y: bl.y, width: x1 - x0, height: y1 - y0 });
  }
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
    } else if (pd.bg.kind === 'image') {
      page = out.addPage([pd.w, pd.h]);
      const buf = await getFile(pd.bg.fileId);
      if (buf) {
        const img = pd.bg.mime === 'image/png' ? await out.embedPng(buf) : await out.embedJpg(buf);
        page.drawImage(img, { x: 0, y: 0, width: pd.w, height: pd.h });
      }
      map = (x, y) => ({ x, y: pd.h - y });
    } else {
      page = out.addPage([pd.w, pd.h]);
      const bg = pd.bg.kind === 'template' ? pd.bg : { template: 'blank' as const, paper: 'white' as const, rtl: false };
      drawTemplate(templateAdapter(page, pd.h), pd.w, pd.h, bg.template, bg.paper, bg.rtl);
      map = (x, y) => ({ x, y: pd.h - y });
      colorFor = (c) => inkColorForPaper(pd.bg, c);
    }
    if (pd.texts?.length) await drawBubbles(out, page, pd.texts, map);
    drawInk(page, pd.strokes, map, colorFor);
    if (pd.texts?.length) await drawTexts(out, page, pd.texts, map, (c) => c, colorFor);
  }
  out.setTitle(meta.title);
  out.setProducer('Alnai Notes');
  return out.save();
}
