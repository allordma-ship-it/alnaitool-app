/**
 * Scanner maths, no DOM: find the page in a photo, straighten it (perspective), clean it up.
 * Points are [x, y]; corners are always in the order top-left, top-right, bottom-right, bottom-left.
 */
export type Pt = [number, number];
export type Quad = [Pt, Pt, Pt, Pt];

/** Otsu's threshold of 8-bit grey values: the split that best separates dark from light. */
export function otsu(gray: Uint8Array | Uint8ClampedArray): number {
  const hist = new Array(256).fill(0);
  for (const v of gray) hist[v]++;
  const total = gray.length;
  let sum = 0;
  for (let i = 0; i < 256; i++) sum += i * hist[i];
  let sumB = 0, wB = 0, best = 0, t = 127;
  for (let i = 0; i < 256; i++) {
    wB += hist[i];
    if (!wB) continue;
    const wF = total - wB;
    if (!wF) break;
    sumB += i * hist[i];
    const mB = sumB / wB, mF = (sum - sumB) / wF;
    const between = wB * wF * (mB - mF) ** 2;
    if (between > best) { best = between; t = i; }
  }
  return t;
}

/**
 * The page in a photo: the largest bright region (paper on a darker desk), its four extreme corners.
 * `gray` is a small grey image (w × h). Returns null when no clear page is found.
 */
export function detectPage(gray: Uint8Array | Uint8ClampedArray, w: number, h: number): Quad | null {
  const t = otsu(gray);
  const seen = new Uint8Array(w * h);
  let bestPixels: number[] = [];
  const stack: number[] = [];
  for (let start = 0; start < w * h; start++) {
    if (seen[start] || gray[start] <= t) continue;
    const pix: number[] = [];
    stack.push(start);
    seen[start] = 1;
    while (stack.length) {
      const i = stack.pop()!;
      pix.push(i);
      const x = i % w, y = (i / w) | 0;
      const nb = [x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1];
      for (const n of nb) if (n >= 0 && !seen[n] && gray[n] > t) { seen[n] = 1; stack.push(n); }
    }
    if (pix.length > bestPixels.length) bestPixels = pix;
  }
  // too small (no page) or the whole picture (page fills the photo, or no contrast): no detection
  const frac = bestPixels.length / (w * h);
  if (frac < 0.12 || frac > 0.97) return null;
  let tl = Infinity, br = -Infinity, tr = -Infinity, bl = Infinity;
  let pTL: Pt = [0, 0], pBR: Pt = [0, 0], pTR: Pt = [0, 0], pBL: Pt = [0, 0];
  for (const i of bestPixels) {
    const x = i % w, y = (i / w) | 0;
    if (x + y < tl) { tl = x + y; pTL = [x, y]; }
    if (x + y > br) { br = x + y; pBR = [x + 1, y + 1]; }
    if (x - y > tr) { tr = x - y; pTR = [x + 1, y]; }
    if (x - y < bl) { bl = x - y; pBL = [x, y + 1]; }
  }
  return [pTL, pTR, pBR, pBL];
}

/** Corners a little inside the picture edges: the starting frame when no page was found. */
export function defaultQuad(w: number, h: number, inset = 0.04): Quad {
  const dx = w * inset, dy = h * inset;
  return [[dx, dy], [w - dx, dy], [w - dx, h - dy], [dx, h - dy]];
}

const dist = (a: Pt, b: Pt) => Math.hypot(a[0] - b[0], a[1] - b[1]);

/** Size of the straightened page: the longer of each pair of opposite edges, at most `max` on the long side. */
export function outputSize(q: Quad, max = 2000): { w: number; h: number } {
  let w = Math.max(dist(q[0], q[1]), dist(q[3], q[2]));
  let h = Math.max(dist(q[0], q[3]), dist(q[1], q[2]));
  const k = Math.min(1, max / Math.max(w, h));
  w = Math.max(1, Math.round(w * k));
  h = Math.max(1, Math.round(h * k));
  return { w, h };
}

/**
 * Homography that maps the output rectangle (0,0)-(w,h) onto the quad in the photo: [a..h] for
 * x' = (a x + b y + c) / (g x + h y + 1),  y' = (d x + e y + f) / (g x + h y + 1).
 */
export function homography(q: Quad, w: number, h: number): number[] {
  const src: Pt[] = [[0, 0], [w, 0], [w, h], [0, h]];
  const A: number[][] = [];
  const B: number[] = [];
  for (let i = 0; i < 4; i++) {
    const [x, y] = src[i], [u, v] = q[i];
    A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]); B.push(u);
    A.push([0, 0, 0, x, y, 1, -v * x, -v * y]); B.push(v);
  }
  return solve(A, B);
}

/** Gaussian elimination with partial pivoting. */
function solve(A: number[][], B: number[]): number[] {
  const n = B.length;
  const M = A.map((row, i) => [...row, B[i]]);
  for (let c = 0; c < n; c++) {
    let p = c;
    for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    [M[c], M[p]] = [M[p], M[c]];
    const d = M[c][c] || 1e-12;
    for (let r = 0; r < n; r++) {
      if (r === c) continue;
      const f = M[r][c] / d;
      if (f) for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k];
    }
  }
  return M.map((row, i) => row[n] / (row[i] || 1e-12));
}

export function mapPoint(H: number[], x: number, y: number): Pt {
  const z = H[6] * x + H[7] * y + 1;
  return [(H[0] * x + H[1] * y + H[2]) / z, (H[3] * x + H[4] * y + H[5]) / z];
}

/** Straightens the quad of an RGBA image into a w × h rectangle (bilinear sampling). */
export function warp(src: Uint8ClampedArray, sw: number, sh: number, q: Quad, w: number, h: number): Uint8ClampedArray<ArrayBuffer> {
  const H = homography(q, w, h);
  const out = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const [u, v] = mapPoint(H, x + 0.5, y + 0.5);
      const fx = Math.min(sw - 1.001, Math.max(0, u - 0.5)), fy = Math.min(sh - 1.001, Math.max(0, v - 0.5));
      const x0 = fx | 0, y0 = fy | 0, ax = fx - x0, ay = fy - y0;
      const i00 = (y0 * sw + x0) * 4, i10 = i00 + 4, i01 = i00 + sw * 4, i11 = i01 + 4;
      const o = (y * w + x) * 4;
      for (let c = 0; c < 3; c++) {
        const top = src[i00 + c] * (1 - ax) + src[i10 + c] * ax;
        const bot = src[i01 + c] * (1 - ax) + src[i11 + c] * ax;
        out[o + c] = top * (1 - ay) + bot * ay;
      }
      out[o + 3] = 255;
    }
  }
  return out;
}

/** Box blur of a grey image (separable, running sums): used to estimate the paper's uneven lighting. */
function boxBlur(g: Float32Array, w: number, h: number, r: number): Float32Array {
  const tmp = new Float32Array(w * h), out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    let s = 0;
    const row = y * w;
    for (let x = -r; x <= r; x++) s += g[row + Math.min(w - 1, Math.max(0, x))];
    for (let x = 0; x < w; x++) {
      tmp[row + x] = s / (2 * r + 1);
      s += g[row + Math.min(w - 1, x + r + 1)] - g[row + Math.max(0, x - r)];
    }
  }
  for (let x = 0; x < w; x++) {
    let s = 0;
    for (let y = -r; y <= r; y++) s += tmp[Math.min(h - 1, Math.max(0, y)) * w + x];
    for (let y = 0; y < h; y++) {
      out[y * w + x] = s / (2 * r + 1);
      s += tmp[Math.min(h - 1, y + r + 1) * w + x] - tmp[Math.max(0, y - r) * w + x];
    }
  }
  return out;
}

/**
 * "Document" look: shadows and uneven light removed (each pixel divided by the local paper brightness),
 * paper pure white, ink dark and crisp. In place, RGBA.
 */
export function documentFilter(px: Uint8ClampedArray, w: number, h: number) {
  const g = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) g[i] = 0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2];
  // a blur wider than the letters, but closer than the shadows
  const bg = boxBlur(g, w, h, Math.max(8, Math.round(Math.max(w, h) / 40)));
  for (let i = 0; i < w * h; i++) {
    const r = Math.min(1.2, g[i] / Math.max(1, bg[i]));
    // levels: everything near the paper becomes white, ink deepens
    const v = Math.max(0, Math.min(1, (r - 0.55) / 0.35));
    const out = 255 * Math.pow(v, 1.6);
    px[i * 4] = px[i * 4 + 1] = px[i * 4 + 2] = out;
  }
}

/** "Colour" look: same lighting fix, colours kept and a little stronger (for pages with diagrams). */
export function colorFilter(px: Uint8ClampedArray, w: number, h: number) {
  const g = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) g[i] = 0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2];
  const bg = boxBlur(g, w, h, Math.max(8, Math.round(Math.max(w, h) / 40)));
  for (let i = 0; i < w * h; i++) {
    const k = 245 / Math.max(40, bg[i]);
    for (let c = 0; c < 3; c++) {
      const v = px[i * 4 + c] * k;
      px[i * 4 + c] = Math.max(0, Math.min(255, (v - 128) * 1.15 + 128));
    }
  }
}

// ---------------------------------------------------------------- curved book pages

/** Quadratic curve from a to b that passes through m at its middle (t = 0.5). */
export function curveThrough(a: Pt, m: Pt, b: Pt, t: number): Pt {
  // control point so that B(0.5) = m
  const c: Pt = [2 * m[0] - (a[0] + b[0]) / 2, 2 * m[1] - (a[1] + b[1]) / 2];
  const u = 1 - t;
  return [u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]];
}

export const midOf = (a: Pt, b: Pt): Pt => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];

/** How far the middle handles are from the straight edges: 0 means a flat page. */
export function bend(q: Quad, top: Pt, bottom: Pt): number {
  const d = (p: Pt, a: Pt, b: Pt) => Math.hypot(p[0] - (a[0] + b[0]) / 2, p[1] - (a[1] + b[1]) / 2);
  return Math.max(d(top, q[0], q[1]), d(bottom, q[3], q[2]));
}

/**
 * Straightens a curved page: the top and bottom edges follow curves through `top` and `bottom`, the sides are
 * straight. Each output column runs from the top curve to the bottom curve.
 */
export function curveWarp(src: Uint8ClampedArray, sw: number, sh: number, q: Quad, top: Pt, bottom: Pt, w: number, h: number): Uint8ClampedArray<ArrayBuffer> {
  const out = new Uint8ClampedArray(w * h * 4);
  for (let x = 0; x < w; x++) {
    const t = (x + 0.5) / w;
    const a = curveThrough(q[0], top, q[1], t), b = curveThrough(q[3], bottom, q[2], t);
    for (let y = 0; y < h; y++) {
      const v = (y + 0.5) / h;
      const u0 = a[0] + (b[0] - a[0]) * v, v0 = a[1] + (b[1] - a[1]) * v;
      const fx = Math.min(sw - 1.001, Math.max(0, u0 - 0.5)), fy = Math.min(sh - 1.001, Math.max(0, v0 - 0.5));
      const x0 = fx | 0, y0 = fy | 0, ax = fx - x0, ay = fy - y0;
      const i00 = (y0 * sw + x0) * 4, i10 = i00 + 4, i01 = i00 + sw * 4, i11 = i01 + 4;
      const o = (y * w + x) * 4;
      for (let c = 0; c < 3; c++) {
        const tp = src[i00 + c] * (1 - ax) + src[i10 + c] * ax;
        const bt = src[i01 + c] * (1 - ax) + src[i11 + c] * ax;
        out[o + c] = tp * (1 - ay) + bt * ay;
      }
      out[o + 3] = 255;
    }
  }
  return out;
}

/** Length of a curved edge (for the output size). */
export function curveLength(a: Pt, m: Pt, b: Pt, steps = 24): number {
  let len = 0, prev = a;
  for (let i = 1; i <= steps; i++) {
    const p = curveThrough(a, m, b, i / steps);
    len += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
    prev = p;
  }
  return len;
}

/**
 * Where the page's top and bottom edges really are half way across: the first and last bright pixel in the
 * column between the corners (for a bent book page these sit off the straight line).
 */
export function detectEdgeMids(gray: Uint8Array | Uint8ClampedArray, w: number, h: number, q: Quad): { top: Pt; bottom: Pt } {
  const t = otsu(gray);
  const top = midOf(q[0], q[1]), bottom = midOf(q[3], q[2]);
  const x = Math.max(0, Math.min(w - 1, Math.round((top[0] + bottom[0]) / 2)));
  const y0 = Math.max(0, Math.floor(Math.min(top[1], bottom[1]) - h * 0.15));
  const y1 = Math.min(h - 1, Math.ceil(Math.max(top[1], bottom[1]) + h * 0.15));
  // a few neighbouring columns, so one dark letter does not fool it
  const bright = (y: number) => {
    let n = 0;
    for (let dx = -2; dx <= 2; dx++) { const xx = Math.max(0, Math.min(w - 1, x + dx)); if (gray[y * w + xx] > t) n++; }
    return n >= 3;
  };
  let ty = -1, by = -1;
  for (let y = y0; y <= y1; y++) if (bright(y)) { ty = y; break; }
  for (let y = y1; y >= y0; y--) if (bright(y)) { by = y + 1; break; }
  return { top: ty >= 0 ? [x, ty] : top, bottom: by >= 0 ? [x, by] : bottom };
}

/** An open book in one photo: the left and right halves of the straightened image. */
export function splitHalves(w: number): [{ x: number; w: number }, { x: number; w: number }] {
  const half = Math.floor(w / 2);
  return [{ x: 0, w: half }, { x: half, w: w - half }];
}
