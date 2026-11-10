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

// ---------------------------------------------------------------- smart shapes

type P = [number, number];

function rdp(pts: P[], eps: number): P[] {
  if (pts.length < 3) return pts;
  const [a, b] = [pts[0], pts[pts.length - 1]];
  const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1e-9;
  let maxD = -1, idx = 0;
  for (let i = 1; i < pts.length - 1; i++) {
    const d = Math.abs((b[0] - a[0]) * (a[1] - pts[i][1]) - (a[0] - pts[i][0]) * (b[1] - a[1])) / len;
    if (d > maxD) { maxD = d; idx = i; }
  }
  if (maxD <= eps) return [a, b];
  return [...rdp(pts.slice(0, idx + 1), eps).slice(0, -1), ...rdp(pts.slice(idx), eps)];
}

function sample(poly: P[], closed: boolean, step = 4): number[] {
  const out: number[] = [];
  const ring = closed ? [...poly, poly[0]] : poly;
  for (let i = 0; i < ring.length - 1; i++) {
    const [x0, y0] = ring[i], [x1, y1] = ring[i + 1];
    const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / step));
    for (let k = 0; k < n; k++) out.push(x0 + ((x1 - x0) * k) / n, y0 + ((y1 - y0) * k) / n, 0.5);
  }
  const last = ring[ring.length - 1];
  out.push(last[0], last[1], 0.5);
  return out;
}

function snapAngle(a: P, b: P): P {
  const ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
  const step = Math.PI / 4;
  const snapped = Math.round(ang / step) * step;
  if (Math.abs(ang - snapped) > (7 * Math.PI) / 180) return b;
  const len = Math.hypot(b[0] - a[0], b[1] - a[1]);
  return [a[0] + Math.cos(snapped) * len, a[1] + Math.sin(snapped) * len];
}

export type ShapeKind = 'line' | 'circle' | 'ellipse' | 'rectangle' | 'triangle' | 'polygon';

/**
 * Turns a hand-drawn stroke into a clean shape. Returns null when the stroke looks like nothing
 * in particular (it is then kept as drawn).
 */
export function recognizeShape(flat: number[]): { kind: ShapeKind; pts: number[] } | null {
  const pts: P[] = [];
  for (let i = 0; i < flat.length; i += 3) pts.push([flat[i], flat[i + 1]]);
  if (pts.length < 4) return null;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity, path = 0;
  pts.forEach(([x, y], i) => {
    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
    if (i) path += Math.hypot(x - pts[i - 1][0], y - pts[i - 1][1]);
  });
  const diag = Math.hypot(x1 - x0, y1 - y0);
  if (diag < 12) return null;
  const a = pts[0], b = pts[pts.length - 1];
  const chord = Math.hypot(b[0] - a[0], b[1] - a[1]);

  if (chord > 16 && path / chord < 1.15) return { kind: 'line', pts: sample([a, snapAngle(a, b)], false) };

  const closed = chord < Math.max(18, diag * 0.3);
  if (!closed) return null;

  // ellipse test: every point should sit near radius 1 in the bbox-normalised frame
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, rx = Math.max(1, (x1 - x0) / 2), ry = Math.max(1, (y1 - y0) / 2);
  const rs = pts.map(([x, y]) => Math.hypot((x - cx) / rx, (y - cy) / ry));
  const mean = rs.reduce((s, r) => s + r, 0) / rs.length;
  const sd = Math.sqrt(rs.reduce((s, r) => s + (r - mean) ** 2, 0) / rs.length);

  let corners = rdp(pts, diag * 0.07);
  if (corners.length > 2 && Math.hypot(corners[0][0] - corners[corners.length - 1][0], corners[0][1] - corners[corners.length - 1][1]) < diag * 0.15)
    corners = corners.slice(0, -1);
  // drop nearly straight vertices (the start point usually lies mid-edge)
  const turn = (p: P, q: P, r: P) => {
    const v1 = [q[0] - p[0], q[1] - p[1]], v2 = [r[0] - q[0], r[1] - q[1]];
    return Math.abs(Math.atan2(v1[0] * v2[1] - v1[1] * v2[0], v1[0] * v2[0] + v1[1] * v2[1]));
  };
  for (let changed = true; changed && corners.length > 3; ) {
    changed = false;
    for (let i = 0; i < corners.length; i++) {
      const p = corners[(i - 1 + corners.length) % corners.length], q = corners[i], r = corners[(i + 1) % corners.length];
      if (turn(p, q, r) < (25 * Math.PI) / 180) {
        corners.splice(i, 1);
        changed = true;
        break;
      }
    }
  }

  if ((sd < 0.09 && corners.length !== 3 && corners.length !== 4) || sd < 0.06) {
    const circle = Math.abs(rx - ry) / Math.max(rx, ry) < 0.18;
    const Rx = circle ? (rx + ry) / 2 : rx, Ry = circle ? (rx + ry) / 2 : ry;
    const n = Math.max(24, Math.ceil((Math.PI * 2 * Math.max(Rx, Ry)) / 4));
    const ring: P[] = Array.from({ length: n }, (_, i) => [cx + Rx * Math.cos((i / n) * Math.PI * 2), cy + Ry * Math.sin((i / n) * Math.PI * 2)]);
    return { kind: circle ? 'circle' : 'ellipse', pts: sample(ring, true) };
  }
  if (corners.length === 3) return { kind: 'triangle', pts: sample(corners, true) };
  if (corners.length === 4) {
    const axis = corners.every((p, i) => {
      const q = corners[(i + 1) % 4];
      const ang = Math.abs(Math.atan2(q[1] - p[1], q[0] - p[0])) % (Math.PI / 2);
      return Math.min(ang, Math.PI / 2 - ang) < (14 * Math.PI) / 180;
    });
    const rect: P[] = axis ? [[x0, y0], [x1, y0], [x1, y1], [x0, y1]] : corners;
    return { kind: 'rectangle', pts: sample(rect, true) };
  }
  if (corners.length >= 5 && corners.length <= 8) return { kind: 'polygon', pts: sample(corners, true) };
  return null;
}
