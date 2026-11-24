import type { PageData } from './types';
import { bubbleRect, boxHeight } from './text';

const CELL = 6; // page points per grid cell

/**
 * Marks which parts of a page are taken: handwriting, typed text, bubbles and — for PDFs and
 * pictures — the printed content itself (read from the rendered background).
 */
export function buildOccupancy(p: PageData, bg?: HTMLCanvasElement | null) {
  const cols = Math.ceil(p.w / CELL), rows = Math.ceil(p.h / CELL);
  const grid = new Uint8Array(cols * rows);
  const mark = (x0: number, y0: number, x1: number, y1: number) => {
    const c0 = Math.max(0, Math.floor(x0 / CELL)), c1 = Math.min(cols - 1, Math.floor(x1 / CELL));
    const r0 = Math.max(0, Math.floor(y0 / CELL)), r1 = Math.min(rows - 1, Math.floor(y1 / CELL));
    for (let r = r0; r <= r1; r++) grid.fill(1, r * cols + c0, r * cols + c1 + 1);
  };
  for (const s of p.strokes) {
    const half = s.width / 2;
    for (let i = 0; i < s.pts.length; i += 3) mark(s.pts[i] - half, s.pts[i + 1] - half, s.pts[i] + half, s.pts[i + 1] + half);
  }
  for (const tb of p.texts ?? []) {
    if (tb.bubble) {
      const [x, y, w, h] = bubbleRect(tb);
      mark(x, y, x + w, y + h);
    } else mark(tb.x, tb.y, tb.x + tb.w, tb.y + boxHeight(tb));
  }
  if (bg && bg.width > 1 && p.bg.kind !== 'template') {
    try {
      const k = 2; // samples per cell
      const c = document.createElement('canvas');
      c.width = cols * k;
      c.height = rows * k;
      const ctx = c.getContext('2d', { willReadFrequently: true })!;
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, c.width, c.height);
      ctx.drawImage(bg, 0, 0, c.width, c.height);
      const d = ctx.getImageData(0, 0, c.width, c.height).data;
      const lum = new Uint8Array(c.width * c.height);
      const hist = new Uint32Array(256);
      for (let i = 0; i < lum.length; i++) {
        lum[i] = (d[i * 4] * 3 + d[i * 4 + 1] * 6 + d[i * 4 + 2]) / 10;
        hist[lum[i]]++;
      }
      let paper = 255, best = 0; // the most common brightness is the paper
      for (let v = 0; v < 256; v++) if (hist[v] > best) { best = hist[v]; paper = v; }
      for (let r = 0; r < rows; r++)
        for (let col = 0; col < cols; col++)
          for (let j = 0; j < k * k; j++) {
            const v = lum[(r * k + ((j / k) | 0)) * c.width + col * k + (j % k)];
            if (Math.abs(v - paper) > 28) { grid[r * cols + col] = 1; break; }
          }
    } catch { /* background unreadable: fall back to ink and text only */ }
  }
  return { grid, cols, rows };
}

/**
 * Nearest free rectangle (w × h, page points) to the anchor, preferring the part of the page that is
 * on screen. If the page has no completely free spot, returns the emptiest one nearby.
 */
export function findFreeSpot(
  p: PageData, bg: HTMLCanvasElement | null | undefined, ax: number, ay: number, w: number, h: number,
  visible?: [number, number, number, number],
) {
  const { grid, cols, rows } = buildOccupancy(p, bg);
  // summed-area table → occupancy of any rectangle in O(1)
  const sat = new Uint32Array((cols + 1) * (rows + 1));
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < cols; c++)
      sat[(r + 1) * (cols + 1) + c + 1] = grid[r * cols + c] + sat[r * (cols + 1) + c + 1] + sat[(r + 1) * (cols + 1) + c] - sat[r * (cols + 1) + c];
  const sum = (c0: number, r0: number, c1: number, r1: number) =>
    sat[r1 * (cols + 1) + c1] - sat[r0 * (cols + 1) + c1] - sat[r1 * (cols + 1) + c0] + sat[r0 * (cols + 1) + c0];

  const cw = Math.ceil(w / CELL) + 2, ch = Math.ceil(h / CELL) + 2; // one cell of breathing room all round
  const margin = 1;
  let best = { x: Math.max(4, Math.min(p.w - w - 4, ax + 20)), y: Math.max(4, Math.min(p.h - h - 4, ay + 20)), score: Infinity };
  for (let r = margin; r + ch <= rows - margin; r++) {
    for (let c = margin; c + cw <= cols - margin; c++) {
      const x = (c + 1) * CELL, y = (r + 1) * CELL;
      const dx = Math.max(x - ax, 0, ax - (x + w)), dy = Math.max(y - ay, 0, ay - (y + h));
      const dist = Math.hypot(dx, dy);
      if (dist < 14) continue; // never on top of the word itself
      // prefer: free and on screen → free but off screen → partly covered
      const offscreen = visible && (x < visible[0] || y < visible[1] || x + w > visible[2] || y + h > visible[3]) ? 150 : 0;
      const score = sum(c, r, c + cw, r + ch) * 400 + offscreen + dist;
      if (score < best.score) best = { x, y, score };
    }
  }
  return best;
}
