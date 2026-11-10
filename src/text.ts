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

export interface Line { text: string; rtl: boolean }

/** Greedy word wrap; every paragraph keeps its own direction. */
export function wrapText(text: string, size: number, maxW: number, bold = false): Line[] {
  const out: Line[] = [];
  for (const para of text.split('\n')) {
    const rtl = isRtl(para);
    if (!para.trim()) {
      out.push({ text: '', rtl });
      continue;
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
  return out;
}

export function textHeight(tb: TextBox): number {
  return wrapText(tb.text, tb.size, tb.w, tb.bold).length * tb.size * LINE_HEIGHT;
}

export function drawTextBox(ctx: CanvasRenderingContext2D, tb: TextBox, color = tb.color) {
  const lines = wrapText(tb.text, tb.size, tb.w, tb.bold);
  const lh = tb.size * LINE_HEIGHT;
  ctx.save();
  ctx.font = fontOf(tb.size, tb.bold);
  ctx.fillStyle = color;
  ctx.textBaseline = 'middle';
  lines.forEach((l, i) => {
    ctx.direction = l.rtl ? 'rtl' : 'ltr';
    ctx.textAlign = l.rtl ? 'right' : 'left';
    ctx.fillText(l.text, l.rtl ? tb.x + tb.w : tb.x, tb.y + lh * (i + 0.5));
  });
  ctx.restore();
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
