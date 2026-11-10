import { h } from './ui';

export type Edge = 'top' | 'bottom';
interface Touch { x: number; y: number }

const MM = 72 / 25.4; // page points per millimetre

/**
 * An on-screen ruler (screen space, like a real ruler lying on the tablet). One finger moves it, two
 * fingers rotate it; a pen stroke that starts next to one of its long edges is drawn along that edge.
 */
export class Ruler {
  el = h('div', { class: 'ruler', hidden: true });
  private canvas = h('canvas');
  private badge = h('span', { class: 'ruler-badge', dir: 'ltr' });
  visible = false;
  cx = 0;
  cy = 0;
  angle = -Math.PI / 18;
  len = 900;
  readonly thick = 78;
  private scale = 1;
  private start: { cx: number; cy: number; angle: number; pts: Touch[] } | null = null;

  constructor() {
    this.el.append(this.canvas, this.badge);
  }

  show(vw: number, vh: number) {
    this.visible = true;
    this.len = Math.max(520, Math.min(1400, Math.hypot(vw, vh) * 0.7));
    this.cx = vw / 2;
    this.cy = vh * 0.45;
    this.el.hidden = false;
    this.layout();
  }

  hide() {
    this.visible = false;
    this.el.hidden = true;
    this.start = null;
  }

  setScale(s: number) {
    if (Math.abs(s / this.scale - 1) < 0.01) return;
    this.scale = s;
    if (this.visible) this.drawTicks();
  }

  /** point in the ruler's own frame: u along it, v across it (0 = centre line) */
  private frame(x: number, y: number) {
    const dx = x - this.cx, dy = y - this.cy;
    const c = Math.cos(this.angle), s = Math.sin(this.angle);
    return { u: dx * c + dy * s, v: -dx * s + dy * c };
  }

  private fromFrame(u: number, v: number): [number, number] {
    const c = Math.cos(this.angle), s = Math.sin(this.angle);
    return [this.cx + u * c - v * s, this.cy + u * s + v * c];
  }

  hit(x: number, y: number) {
    if (!this.visible) return false;
    const { u, v } = this.frame(x, y);
    return Math.abs(u) <= this.len / 2 && Math.abs(v) <= this.thick / 2;
  }

  /** the long edge a pen at (x, y) is drawing along, if any */
  edgeNear(x: number, y: number, tol = 28): Edge | null {
    if (!this.visible) return null;
    const { u, v } = this.frame(x, y);
    if (Math.abs(u) > this.len / 2 + 12) return null;
    const half = this.thick / 2;
    if (v < -half + 6 && v > -half - tol) return 'top';
    if (v > half - 6 && v < half + tol) return 'bottom';
    return null;
  }

  /** moves a pen point onto the edge, `off` pixels outside it so the ink lies against the ruler */
  project(x: number, y: number, edge: Edge, off: number): [number, number] {
    const { u } = this.frame(x, y);
    const half = this.thick / 2;
    return this.fromFrame(Math.max(-this.len / 2, Math.min(this.len / 2, u)), edge === 'top' ? -half - off : half + off);
  }

  begin(pts: Touch[]) {
    this.start = { cx: this.cx, cy: this.cy, angle: this.angle, pts: pts.map((p) => ({ ...p })) };
    this.el.classList.add('active');
  }

  update(pts: Touch[]) {
    const st = this.start;
    if (!st) return;
    if (pts.length >= 2 && st.pts.length >= 2) {
      const a0 = Math.atan2(st.pts[1].y - st.pts[0].y, st.pts[1].x - st.pts[0].x);
      const a1 = Math.atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x);
      let ang = st.angle + (a1 - a0);
      // gentle snap to 0°, 15°, 30°, 45°…
      const step = Math.PI / 12, snapped = Math.round(ang / step) * step;
      if (Math.abs(ang - snapped) < (2.5 * Math.PI) / 180) ang = snapped;
      this.angle = ang;
      this.cx = st.cx + (pts[0].x + pts[1].x - st.pts[0].x - st.pts[1].x) / 2;
      this.cy = st.cy + (pts[0].y + pts[1].y - st.pts[0].y - st.pts[1].y) / 2;
    } else if (pts.length) {
      this.cx = st.cx + pts[0].x - st.pts[0].x;
      this.cy = st.cy + pts[0].y - st.pts[0].y;
    }
    this.layout(false);
  }

  /** re-baseline when fingers are added or lifted mid-gesture */
  rebase(pts: Touch[]) {
    if (pts.length) this.begin(pts);
    else this.end();
  }

  end() {
    this.start = null;
    this.el.classList.remove('active');
  }

  private layout(redraw = true) {
    Object.assign(this.el.style, {
      left: `${this.cx - this.len / 2}px`, top: `${this.cy - this.thick / 2}px`,
      width: `${this.len}px`, height: `${this.thick}px`, transform: `rotate(${this.angle}rad)`,
    });
    let deg = ((-this.angle * 180) / Math.PI) % 180;
    if (deg > 90) deg -= 180;
    if (deg <= -90) deg += 180;
    this.badge.textContent = `${Math.round(deg)}°`;
    if (redraw) this.drawTicks();
  }

  private drawTicks() {
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const W = this.len, H = this.thick;
    this.canvas.width = Math.round(W * dpr);
    this.canvas.height = Math.round(H * dpr);
    const ctx = this.canvas.getContext('2d')!;
    ctx.scale(dpr, dpr);
    const mm = MM * this.scale; // screen px per millimetre at the current zoom
    const every = mm < 2.2 ? 5 : 1;
    ctx.strokeStyle = 'rgba(226,240,255,.85)';
    ctx.fillStyle = 'rgba(226,240,255,.9)';
    ctx.lineWidth = 1;
    ctx.font = '600 10px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    const x0 = 14;
    for (let i = 0; x0 + i * mm <= W - 8; i += every) {
      const x = Math.round(x0 + i * mm) + 0.5;
      const len = i % 10 === 0 ? 18 : i % 5 === 0 ? 12 : 7;
      ctx.beginPath();
      ctx.moveTo(x, 0); ctx.lineTo(x, len);
      ctx.moveTo(x, H); ctx.lineTo(x, H - len);
      ctx.stroke();
      if (i % 10 === 0 && (mm * 10 > 22 || i % 50 === 0)) ctx.fillText(String(i / 10), x, 21);
    }
  }
}
