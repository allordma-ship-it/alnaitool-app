import { uid, type Stroke } from './types';

export function computeBBox(pts: ArrayLike<number>, pad = 0): [number, number, number, number] {
  let a = Infinity, b = Infinity, c = -Infinity, d = -Infinity;
  for (let i = 0; i < pts.length; i += 3) {
    const x = pts[i], y = pts[i + 1];
    if (x < a) a = x;
    if (y < b) b = y;
    if (x > c) c = x;
    if (y > d) d = y;
  }
  return [a - pad, b - pad, c + pad, d + pad];
}

export function makeStroke(tool: Stroke['tool'], color: string, width: number, pts: number[]): Stroke {
  return { id: uid(), tool, color, width, pts, bbox: computeBBox(pts, width) };
}

function distSqToSegment(px: number, py: number, ax: number, ay: number, bx: number, by: number) {
  const dx = bx - ax, dy = by - ay;
  const len = dx * dx + dy * dy;
  let t = len ? ((px - ax) * dx + (py - ay) * dy) / len : 0;
  t = Math.max(0, Math.min(1, t));
  const x = ax + t * dx - px, y = ay + t * dy - py;
  return x * x + y * y;
}

function bboxHit(s: Stroke, x: number, y: number, r: number) {
  const [a, b, c, d] = s.bbox;
  return x >= a - r && x <= c + r && y >= b - r && y <= d + r;
}

/** True if a circle (x,y,r) touches the stroke's centerline (+ half its width). */
export function strokeHit(s: Stroke, x: number, y: number, r: number): boolean {
  if (!bboxHit(s, x, y, r)) return false;
  const rr = r + s.width / 2;
  const p = s.pts;
  if (p.length === 3) return (p[0] - x) ** 2 + (p[1] - y) ** 2 <= rr * rr;
  for (let i = 3; i < p.length; i += 3) {
    if (distSqToSegment(x, y, p[i - 3], p[i - 2], p[i], p[i + 1]) <= rr * rr) return true;
  }
  return false;
}

/**
 * Precise erase: removes the parts of a stroke inside the eraser circle.
 * Returns null if untouched, otherwise the list of remaining pieces (possibly empty).
 */
export function eraseFromStroke(s: Stroke, x: number, y: number, r: number): Stroke[] | null {
  if (!bboxHit(s, x, y, r + s.width / 2)) return null;
  const rr = (r + s.width / 4) ** 2;
  const p = s.pts;
  const pieces: number[][] = [];
  let cur: number[] = [];
  let touched = false;
  for (let i = 0; i < p.length; i += 3) {
    let inside = (p[i] - x) ** 2 + (p[i + 1] - y) ** 2 <= rr;
    // also treat a point as erased if the segment leading to it crosses the circle
    if (!inside && i >= 3 && distSqToSegment(x, y, p[i - 3], p[i - 2], p[i], p[i + 1]) <= rr) {
      touched = true;
      if (cur.length) pieces.push(cur);
      cur = [];
    }
    if (inside) {
      touched = true;
      if (cur.length) pieces.push(cur);
      cur = [];
    } else {
      cur.push(p[i], p[i + 1], p[i + 2]);
    }
  }
  if (cur.length) pieces.push(cur);
  if (!touched) return null;
  return pieces
    .filter((pts) => pts.length >= 6 || s.pts.length === 3)
    .map((pts) => ({ ...s, id: uid(), pts, bbox: computeBBox(pts, s.width) }));
}

export function pointInPolygon(x: number, y: number, poly: number[]): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 2; i < poly.length; j = i, i += 2) {
    const xi = poly[i], yi = poly[i + 1], xj = poly[j], yj = poly[j + 1];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** A stroke is lasso-selected when at least half of its points lie inside the polygon. */
export function lassoSelects(s: Stroke, poly: number[]): boolean {
  let inside = 0;
  const n = s.pts.length / 3;
  for (let i = 0; i < s.pts.length; i += 3) if (pointInPolygon(s.pts[i], s.pts[i + 1], poly)) inside++;
  return inside * 2 >= n;
}

export function translateStroke(s: Stroke, dx: number, dy: number, newId = false): Stroke {
  const pts = s.pts.slice();
  for (let i = 0; i < pts.length; i += 3) {
    pts[i] += dx;
    pts[i + 1] += dy;
  }
  const [a, b, c, d] = s.bbox;
  return { ...s, id: newId ? uid() : s.id, pts, bbox: [a + dx, b + dy, c + dx, d + dy] };
}

export function unionBBox(strokes: Stroke[]): [number, number, number, number] | null {
  if (!strokes.length) return null;
  let a = Infinity, b = Infinity, c = -Infinity, d = -Infinity;
  for (const s of strokes) {
    a = Math.min(a, s.bbox[0]); b = Math.min(b, s.bbox[1]);
    c = Math.max(c, s.bbox[2]); d = Math.max(d, s.bbox[3]);
  }
  return [a, b, c, d];
}

/**
 * If a stroke is "meant" to be a straight line, returns straightened points, else null.
 * Highlighter: any direction, snapped to horizontal/vertical when close.
 * Pen: only near-horizontal lines (underlines), so handwriting itself is never altered.
 */
export function straightenStroke(pts: number[], tool: Stroke['tool']): number[] | null {
  const n = pts.length / 3;
  if (n < 3) return null;
  let ax = pts[0], ay = pts[1], bx = pts[(n - 1) * 3], by = pts[(n - 1) * 3 + 1];
  const len = Math.hypot(bx - ax, by - ay);
  if (len < (tool === 'pen' ? 28 : 14)) return null;
  // reject scribbles and curves: path barely longer than the chord, and no point far from it
  let path = 0, maxDev = 0, sumY = 0, sumX = 0, sumP = 0;
  for (let i = 0; i < n; i++) {
    const x = pts[i * 3], y = pts[i * 3 + 1];
    if (i) path += Math.hypot(x - pts[(i - 1) * 3], y - pts[(i - 1) * 3 + 1]);
    maxDev = Math.max(maxDev, Math.abs((bx - ax) * (ay - y) - (ax - x) * (by - ay)) / len);
    sumX += x; sumY += y; sumP += pts[i * 3 + 2];
  }
  const tolerance = tool === 'pen' ? Math.max(2.5, len * 0.05) : Math.max(5, len * 0.12);
  if (path > len * (tool === 'pen' ? 1.12 : 1.3) || maxDev > tolerance) return null;
  const angle = Math.abs(Math.atan2(by - ay, bx - ax));
  const deg = (Math.min(angle, Math.PI - angle) * 180) / Math.PI; // 0 = horizontal, 90 = vertical
  if (deg < 12) ay = by = sumY / n;
  else if (tool === 'pen') return null;
  else if (deg > 80) ax = bx = sumX / n;
  // resample so erasing and selecting still work along the whole line
  const steps = Math.max(2, Math.ceil(len / 5));
  const p = tool === 'pen' ? sumP / n : 0.5;
  const out: number[] = [];
  for (let i = 0; i <= steps; i++) out.push(ax + ((bx - ax) * i) / steps, ay + ((by - ay) * i) / steps, p);
  return out;
}
