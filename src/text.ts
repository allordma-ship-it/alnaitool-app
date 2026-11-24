import type { TextBox } from './types';

export const FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans Arabic', 'Noto Naskh Arabic', sans-serif";
export const LINE_HEIGHT = 1.45;

const RTL_CHAR = /[֐-ࣿיִ-﷿ﹰ-ﻼ]/;
const LTR_CHAR = /[A-Za-zÀ-ɏͰ-ϿЀ-ӿ]/;

/** Direction of a paragraph = direction of its first strong character (like dir="auto"). */
export function isRtl(s: string): boolean {
  for (const ch of s) {
    if (RTL_CHAR.test(ch)) return true;
    if (LTR_CHAR.test(ch)) return false;
  }
  return false;
}

export function fontOf(size: number, bold = false) {
  return `${bold ? '600 ' : ''}${size}px ${FONT}`;
}

let mctx: CanvasRenderingContext2D | null = null;
function measure(text: string, size: number, bold: boolean): number {
  mctx ??= document.createElement('canvas').getContext('2d')!;
  mctx.font = fontOf(size, bold);
  return mctx.measureText(text).width;
}

export interface Line {
  text: string;
  rtl: boolean;
  /** paragraph this line belongs to */
  para?: number;
  /** checklist item: room left for the box, and whether the item is done */
  indent?: number;
  done?: boolean;
  /** first line of a checklist item: draw the box here */
  box?: boolean;
}

/** "[ ] milk" / "[x] milk": a checklist item. The marker is plain text, so exports and copies stay readable. */
export const CHECK_RE = /^\[( |x|X)\][ \t]?/;
export const checkIndent = (size: number) => size * 1.5;

/** Greedy word wrap; every paragraph keeps its own direction. */
export function wrapText(text: string, size: number, maxW: number, bold = false): Line[] {
  const out: Line[] = [];
  text.split('\n').forEach((raw, pi) => {
    const m = CHECK_RE.exec(raw);
    const para = m ? raw.slice(m[0].length) : raw;
    const item = m ? { indent: checkIndent(size), done: m[1] !== ' ' } : null;
    const start = out.length;
    wrapPara(out, para, size, item ? maxW - item.indent : maxW, bold);
    for (let i = start; i < out.length; i++) {
      out[i].para = pi;
      if (item) Object.assign(out[i], item, { box: i === start });
    }
  });
  return out;
}

function wrapPara(out: Line[], para: string, size: number, maxW: number, bold: boolean) {
  {
    const rtl = isRtl(para);
    if (!para.trim()) {
      out.push({ text: '', rtl });
      return;
    }
    let line = '';
    for (const word of para.split(/(\s+)/)) {
      if (!word) continue;
      const candidate = line + word;
      if (!line || measure(candidate.trimEnd(), size, bold) <= maxW) {
        line = candidate;
      } else {
        out.push({ text: line.trimEnd(), rtl });
        line = word.trimStart();
      }
      // a single word longer than the box: break it by characters
      while (measure(line, size, bold) > maxW && line.length > 1) {
        let cut = line.length - 1;
        while (cut > 1 && measure(line.slice(0, cut), size, bold) > maxW) cut--;
        out.push({ text: line.slice(0, cut), rtl });
        line = line.slice(cut);
      }
    }
    out.push({ text: line.trimEnd(), rtl });
  }
}

export function textHeight(tb: TextBox): number {
  return wrapText(tb.text, tb.size, tb.w, tb.bold).length * tb.size * LINE_HEIGHT;
}

/** `coverColor` paints over the original PDF text of a correction box; null leaves the cover out (PDF export draws it itself). */
export function drawTextBox(ctx: CanvasRenderingContext2D, tb: TextBox, color = tb.color, coverColor: string | null = '#ffffff') {
  const lines = wrapText(tb.text, tb.size, tb.w, tb.bold);
  const lh = tb.size * LINE_HEIGHT;
  ctx.save();
  if (tb.cover && coverColor) {
    const [x0, y0, x1, y1] = tb.cover;
    ctx.fillStyle = coverColor;
    ctx.fillRect(x0, y0, x1 - x0, y1 - y0);
  }
  ctx.font = fontOf(tb.size, tb.bold);
  ctx.fillStyle = color;
  ctx.textBaseline = 'middle';
  lines.forEach((l, i) => {
    const ind = l.indent ?? 0;
    const cy = tb.y + lh * (i + 0.5);
    ctx.globalAlpha = l.done ? 0.5 : 1;
    if (l.box) drawCheckbox(ctx, l.rtl ? tb.x + tb.w - tb.size : tb.x, cy - tb.size / 2, tb.size, !!l.done, color);
    ctx.direction = l.rtl ? 'rtl' : 'ltr';
    ctx.textAlign = l.rtl ? 'right' : 'left';
    const tx = l.rtl ? tb.x + tb.w - ind : tb.x + ind;
    ctx.fillText(l.text, tx, cy);
    if (l.done && l.text) {
      // a done item is struck through
      const w = ctx.measureText(l.text).width;
      ctx.fillRect(l.rtl ? tx - w : tx, cy - tb.size * 0.02, w, Math.max(1, tb.size * 0.07));
    }
  });
  ctx.restore();
}

function drawCheckbox(ctx: CanvasRenderingContext2D, x: number, y: number, s: number, on: boolean, color: string) {
  const r = s * 0.18, p = s * 0.08;
  ctx.save();
  ctx.globalAlpha = 1;
  ctx.lineWidth = Math.max(1, s * 0.09);
  ctx.strokeStyle = on ? '#0891b2' : color;
  ctx.beginPath();
  ctx.roundRect(x + p, y + p, s - 2 * p, s - 2 * p, r);
  if (on) {
    ctx.fillStyle = '#0891b2';
    ctx.fill();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = Math.max(1.2, s * 0.12);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(x + s * 0.28, y + s * 0.52);
    ctx.lineTo(x + s * 0.44, y + s * 0.68);
    ctx.lineTo(x + s * 0.74, y + s * 0.34);
  }
  ctx.stroke();
  ctx.restore();
}

/** The checklist item whose box is at (x, y), as its paragraph index; null if none. Generous for a fingertip. */
export function checkboxAt(tb: TextBox, x: number, y: number, slop = 6): number | null {
  const lines = wrapText(tb.text, tb.size, tb.w, tb.bold);
  const lh = tb.size * LINE_HEIGHT;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (!l.box) continue;
    const bx = l.rtl ? tb.x + tb.w - tb.size : tb.x;
    const by = tb.y + lh * (i + 0.5) - tb.size / 2;
    if (x >= bx - slop && x <= bx + tb.size + slop && y >= by - slop && y <= by + tb.size + slop) return l.para ?? null;
  }
  return null;
}

/** Ticks or unticks the checklist item in paragraph `para`. */
export function toggleCheck(text: string, para: number): string {
  const paras = text.split('\n');
  const m = CHECK_RE.exec(paras[para] ?? '');
  if (!m) return text;
  paras[para] = (m[1] === ' ' ? '[x] ' : '[ ] ') + paras[para].slice(m[0].length);
  return paras.join('\n');
}

/** Turns the paragraph at `para` into a checklist item, or back into plain text. */
export function toggleItem(text: string, para: number): string {
  const paras = text.split('\n');
  const p = paras[para] ?? '';
  const m = CHECK_RE.exec(p);
  paras[para] = m ? p.slice(m[0].length) : `[ ] ${p}`;
  return paras.join('\n');
}

export const BUBBLE_PAD = 7;

/** Inner height of a box: bubbles keep a minimum so there is room to handwrite inside. */
export function boxHeight(tb: TextBox): number {
  const th = tb.text ? textHeight(tb) : tb.size * LINE_HEIGHT;
  return tb.bubble ? Math.max(tb.bubble.h, th) : th;
}

/** Outer rectangle of a note bubble: [x, y, w, h]. */
export function bubbleRect(tb: TextBox): [number, number, number, number] {
  return [tb.x - BUBBLE_PAD, tb.y - BUBBLE_PAD, tb.w + BUBBLE_PAD * 2, boxHeight(tb) + BUBBLE_PAD * 2];
}

/** Speech-bubble background with a tail pointing at the anchored word. */
export function drawBubble(ctx: CanvasRenderingContext2D, tb: TextBox) {
  if (!tb.bubble) return;
  const [x, y, w, h] = bubbleRect(tb);
  const { ax, ay } = tb.bubble;
  const qx = Math.max(x, Math.min(x + w, ax)), qy = Math.max(y, Math.min(y + h, ay));
  const fill = '#fff6bf', edge = '#d9a400';
  ctx.save();
  ctx.lineJoin = 'round';
  ctx.lineWidth = 1;
  ctx.fillStyle = fill;
  ctx.strokeStyle = edge;
  // thin leader from the word to the bubble: stays readable even when it crosses text
  if (qx !== ax || qy !== ay) {
    ctx.beginPath();
    ctx.moveTo(ax, ay);
    ctx.lineTo(qx, qy);
    ctx.lineWidth = 1.3;
    ctx.stroke();
    ctx.lineWidth = 1;
  }
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, 7);
  ctx.fill();
  ctx.stroke();
  ctx.fillStyle = edge;
  ctx.beginPath();
  ctx.arc(ax, ay, 1.8, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

export function textBoxHit(tb: TextBox, x: number, y: number, pad = 4): boolean {
  if (tb.bubble) pad += BUBBLE_PAD;
  return x >= tb.x - pad && x <= tb.x + tb.w + pad && y >= tb.y - pad && y <= tb.y + boxHeight(tb) + pad;
}
