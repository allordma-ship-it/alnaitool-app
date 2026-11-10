import type { Stroke, PageBackground, Template, PaperColor } from './types';

export const HIGHLIGHT_ALPHA = 0.35;

/** Width at a given pressure. Pressure 0.5 = nominal width. */
export function widthAt(base: number, p: number, usePressure: boolean): number {
  if (!usePressure) return base;
  return base * (0.35 + 1.3 * p);
}

/**
 * Draws pen segments [from, to) of a pressure-sensitive stroke, smoothed with quadratic curves
 * through segment midpoints. The final segment of `pts` runs to the real last point.
 * `pts` are flat [x,y,p] triples; ctx is expected to already carry the page->pixel transform.
 */
export function drawPenSegments(
  ctx: CanvasRenderingContext2D,
  pts: ArrayLike<number>,
  from: number,
  to: number,
  color: string,
  width: number,
  usePressure = true,
) {
  const n = pts.length / 3;
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  if (n === 1) {
    const r = widthAt(width, pts[2], usePressure) / 2;
    ctx.beginPath();
    ctx.arc(pts[0], pts[1], r, 0, Math.PI * 2);
    ctx.fill();
    return;
  }
  for (let i = Math.max(1, from); i < Math.min(n, to); i++) {
    const x0 = pts[(i - 1) * 3], y0 = pts[(i - 1) * 3 + 1];
    const x1 = pts[i * 3], y1 = pts[i * 3 + 1];
    let sx = x0, sy = y0;
    if (i >= 2) {
      sx = (pts[(i - 2) * 3] + x0) / 2;
      sy = (pts[(i - 2) * 3 + 1] + y0) / 2;
    }
    const last = i === n - 1;
    const ex = last ? x1 : (x0 + x1) / 2;
    const ey = last ? y1 : (y0 + y1) / 2;
    const p = (pts[(i - 1) * 3 + 2] + pts[i * 3 + 2]) / 2;
    ctx.lineWidth = widthAt(width, p, usePressure);
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    if (i >= 2) ctx.quadraticCurveTo(x0, y0, ex, ey);
    else ctx.lineTo(ex, ey);
    ctx.stroke();
  }
}

/** Highlighter: constant width, translucent, drawn as one path so overlaps don't darken. */
export function drawHighlighterPath(ctx: CanvasRenderingContext2D, pts: ArrayLike<number>, color: string, width: number) {
  const n = pts.length / 3;
  ctx.save();
  ctx.globalAlpha = HIGHLIGHT_ALPHA;
  ctx.globalCompositeOperation = 'multiply';
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = 'butt';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(pts[0], pts[1]);
  if (n === 1) ctx.lineTo(pts[0] + 0.1, pts[1]);
  for (let i = 1; i < n; i++) ctx.lineTo(pts[i * 3], pts[i * 3 + 1]);
  ctx.stroke();
  ctx.restore();
}

export function drawStroke(ctx: CanvasRenderingContext2D, s: Stroke, usePressure = true) {
  if (s.tool === 'highlighter') drawHighlighterPath(ctx, s.pts, s.color, s.width);
  else drawPenSegments(ctx, s.pts, 1, Infinity, s.color, s.width, usePressure);
}

/** Highlighters go below pen ink, like a real marker under ink. */
export function drawStrokes(ctx: CanvasRenderingContext2D, strokes: Stroke[], usePressure = true) {
  for (const s of strokes) if (s.tool === 'highlighter') drawStroke(ctx, s, usePressure);
  for (const s of strokes) if (s.tool !== 'highlighter') drawStroke(ctx, s, usePressure);
}

export const PAPER: Record<PaperColor, { bg: string; line: string; strong: string }> = {
  white: { bg: '#ffffff', line: '#c9d3e0', strong: '#e8a0a0' },
  cream: { bg: '#fbf6e9', line: '#d8cdb3', strong: '#e2a48f' },
  dark: { bg: '#23262d', line: '#3d434f', strong: '#6b4a4a' },
};

/** Draws a page template in page coordinates. Also used (via a recording ctx) for PDF export. */
export function drawTemplate(
  ctx: {
    fillRect(x: number, y: number, w: number, h: number): void;
    beginPath(): void; moveTo(x: number, y: number): void; lineTo(x: number, y: number): void; stroke(): void;
    arc(x: number, y: number, r: number, a0: number, a1: number): void; fill(): void;
    fillStyle: string | CanvasGradient | CanvasPattern; strokeStyle: string | CanvasGradient | CanvasPattern; lineWidth: number;
  },
  w: number,
  h: number,
  template: Template,
  paper: PaperColor,
  rtl = false,
) {
  const c = PAPER[paper];
  // Arabic notebooks: margin on the right-hand side
  const mx = (x: number) => (rtl ? w - x : x);
  ctx.fillStyle = c.bg;
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = c.line;
  ctx.fillStyle = c.line;
  ctx.lineWidth = 0.6;
  const gap = 24;
  if (template === 'lined' || template === 'cornell') {
    const top = template === 'cornell' ? 90 : 72;
    const bottom = template === 'cornell' ? h - 130 : h - 36;
    ctx.beginPath();
    for (let y = top; y < bottom; y += gap) {
      ctx.moveTo(mx(template === 'cornell' ? 150 : 36), y);
      ctx.lineTo(mx(w - 36), y);
    }
    ctx.stroke();
    ctx.strokeStyle = c.strong;
    ctx.lineWidth = 1;
    ctx.beginPath();
    if (template === 'cornell') {
      ctx.moveTo(mx(140), 60); ctx.lineTo(mx(140), h - 130);
      ctx.moveTo(24, 60); ctx.lineTo(w - 24, 60);
      ctx.moveTo(24, h - 130); ctx.lineTo(w - 24, h - 130);
    } else {
      ctx.moveTo(mx(72), 24); ctx.lineTo(mx(72), h - 24);
    }
    ctx.stroke();
  } else if (template === 'grid') {
    const g = 18;
    ctx.beginPath();
    for (let x = g; x < w; x += g) { ctx.moveTo(x, 0); ctx.lineTo(x, h); }
    for (let y = g; y < h; y += g) { ctx.moveTo(0, y); ctx.lineTo(w, y); }
    ctx.stroke();
  } else if (template === 'dots') {
    const g = 18;
    for (let y = g; y < h; y += g) {
      for (let x = g; x < w; x += g) {
        ctx.beginPath();
        ctx.arc(x, y, 0.9, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }
}

export function inkColorForPaper(bg: PageBackground, color: string): string {
  // Keep stored colour, but make default near-black ink visible on dark paper.
  if (bg.kind === 'template' && bg.paper === 'dark' && color.toLowerCase() === '#1a1a2e') return '#f2f2f2';
  return color;
}
