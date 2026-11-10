import type { DocMeta, PageData, Stroke, Tool, InkTool } from './types';
import { A4, uid } from './types';
import { getPages, putDoc, putPages, deletePages } from './db';
import { drawStrokes, drawTemplate, drawPenSegments, drawHighlighterPath, inkColorForPaper, drawStroke } from './ink';
import { makeStroke, strokeHit, eraseFromStroke, lassoSelects, translateStroke, unionBBox } from './geometry';
import { settings, saveSettings, type ButtonAction } from './settings';
import { pdfForDoc, renderPdfPage, closePdf } from './pdf';
import { exportDocToPdf, shareOrDownload } from './exportPdf';
import { openSettings } from './settingsDialog';
import { icons } from './icons';
import { t } from './i18n';
import { h, toast } from './ui';

const GAP = 20;
const MAX_CANVAS_PX = 12_000_000;
const MAX_SCALE = 6;
const PEN_SEEN_KEY = 'alnai-pen-seen';

interface Layout { x: number; y: number; w: number; h: number }
interface PageView {
  el: HTMLDivElement;
  bg: HTMLCanvasElement;
  ink: HTMLCanvasElement;
  scale: number;
  bgJob: { cancelled: boolean };
}
interface HistoryEntry { pageId: string; before: Stroke[]; after: Stroke[] }

type Mode =
  | { kind: 'idle' }
  | { kind: 'ink'; id: number; page: number; tool: InkTool; color: string; width: number; pts: number[]; settled: number; lastP: number; pen: boolean }
  | { kind: 'erase'; id: number; page: number; before: Stroke[]; changed: boolean }
  | { kind: 'lasso'; id: number; page: number; poly: number[] }
  | { kind: 'move'; id: number; page: number; start: [number, number]; d: [number, number]; before: Stroke[]; moving: Stroke[] }
  | { kind: 'pan'; id: number; last: [number, number] };

let penSeen = (() => {
  try { return localStorage.getItem(PEN_SEEN_KEY) === '1'; } catch { return false; }
})();

export class Editor {
  root: HTMLElement;
  private viewport!: HTMLDivElement;
  private world!: HTMLDivElement;
  private wet!: HTMLCanvasElement;
  private pred!: HTMLCanvasElement;
  private wctx!: CanvasRenderingContext2D;
  private pctx!: CanvasRenderingContext2D;
  private toolbar!: HTMLDivElement;
  private optionsBar!: HTMLDivElement;
  private selBar!: HTMLDivElement;
  private pageLabel!: HTMLButtonElement;
  private selBox!: HTMLDivElement;

  private pages: PageData[] = [];
  private layout: Layout[] = [];
  private contentW = A4.w;
  private contentH = A4.h;
  private views = new Map<number, PageView>();
  private cam = { s: 1, tx: 0, ty: 0 };
  private dpr = Math.min(window.devicePixelRatio || 1, 3);
  private vw = 0;
  private vh = 0;

  private tool: Tool = 'pen';
  private mode: Mode = { kind: 'idle' };
  private touches = new Map<number, { x: number; y: number; sx: number; sy: number }>();
  private ignoredTouches = new Set<number>();
  private gesture: { t0: number; max: number; moved: number; pinch?: { s0: number; d0: number; wx: number; wy: number } } | null = null;
  private lastPenContact = 0;
  private lastProcessedTs = new Map<number, number>();
  private velocity: [number, number, number] = [0, 0, 0];
  private fling = 0;

  private undoStack: HistoryEntry[] = [];
  private redoStack: HistoryEntry[] = [];
  private selection: { page: number; ids: Set<string> } | null = null;

  private dirty = new Set<string>();
  private saveTimer = 0;
  private metaDirty = false;
  private rerenderTimer = 0;
  private rafPending = false;
  private inkRaf = new Set<number>();
  private disposers: (() => void)[] = [];
  private closed = false;

  constructor(private meta: DocMeta, private onClose: () => void) {
    this.root = h('div', { class: 'editor' });
  }

  async open() {
    this.buildUI();
    this.pages = await getPages(this.meta.pageIds);
    this.pages = this.pages.filter(Boolean);
    this.relayout();
    requestAnimationFrame(() => {
      this.resize();
      this.fitWidth();
      const lp = this.meta.lastPage ?? 0;
      if (lp > 0 && this.layout[lp]) {
        this.cam.ty = 16 - this.layout[lp].y * this.cam.s;
        this.clampCamera();
        this.applyCamera();
      }
    });
  }

  // ---------------------------------------------------------------- UI
  private buildUI() {
    this.viewport = h('div', { class: 'viewport' });
    this.world = h('div', { class: 'world' });
    this.wet = h('canvas', { class: 'wet' });
    this.pred = h('canvas', { class: 'wet' });
    this.selBox = h('div', { class: 'sel-box', hidden: true });
    this.viewport.append(this.world, this.wet, this.pred);
    // desynchronized = low-latency front-buffer rendering on Chrome/Android
    this.wctx = this.wet.getContext('2d', { desynchronized: true })!;
    this.pctx = this.pred.getContext('2d', { desynchronized: true })!;

    const btn = (icon: string, title: string, on: () => void, cls = '') => {
      const b = h('button', { class: `icon-btn ${cls}`, title, 'aria-label': title });
      b.innerHTML = icon;
      b.addEventListener('click', on);
      return b;
    };

    const toolBtn = (tool: Tool) => {
      const b = btn(icons[tool], t(tool), () => {
        if (this.tool === tool) this.optionsBar.classList.toggle('collapsed');
        else this.setTool(tool);
      }, 'tool');
      b.dataset.tool = tool;
      return b;
    };

    this.pageLabel = h('button', { class: 'page-label', title: t('fitWidth'), dir: 'ltr' });
    this.pageLabel.addEventListener('click', () => this.fitWidth());

    const title = h('div', { class: 'doc-title' }, this.meta.title);
    this.toolbar = h('div', { class: 'toolbar' },
      btn(icons.back, t('back'), () => this.close()),
      title,
      h('div', { class: 'tool-group' }, toolBtn('pen'), toolBtn('highlighter'), toolBtn('eraser'), toolBtn('lasso')),
      h('div', { class: 'spacer' }),
      btn(icons.undo, t('undo'), () => this.undo()),
      btn(icons.redo, t('redo'), () => this.redo()),
      btn(icons.plus, t('addPage'), () => this.addPage()),
      btn(icons.trash, t('deletePage'), () => this.deleteCurrentPage()),
      btn(icons.share, t('exportPdf'), () => this.exportPdf()),
      btn(icons.settings, t('settings'), () => openSettings(() => this.onSettingsChanged())),
    );
    this.optionsBar = h('div', { class: 'options-bar' });
    this.selBar = h('div', { class: 'sel-bar', hidden: true });

    this.root.append(this.toolbar, this.optionsBar, this.viewport, this.selBar, this.pageLabel);
    this.setTool('pen');

    const vp = this.viewport;
    const on = <K extends keyof HTMLElementEventMap>(el: EventTarget, type: K | string, fn: (e: never) => void, opts?: AddEventListenerOptions) => {
      el.addEventListener(type, fn as EventListener, opts);
      this.disposers.push(() => el.removeEventListener(type, fn as EventListener, opts));
    };
    on(vp, 'pointerdown', (e: PointerEvent) => this.onDown(e));
    on(vp, 'pointermove', (e: PointerEvent) => this.onMove(e));
    if ('onpointerrawupdate' in window) on(vp, 'pointerrawupdate', (e: PointerEvent) => this.onRaw(e));
    on(vp, 'pointerup', (e: PointerEvent) => this.onUp(e, false));
    on(vp, 'pointercancel', (e: PointerEvent) => this.onUp(e, true));
    on(vp, 'pointerleave', (e: PointerEvent) => { if (e.pointerType === 'pen') this.clearPred(); });
    on(vp, 'contextmenu', (e: Event) => e.preventDefault());
    on(vp, 'wheel', (e: WheelEvent) => this.onWheel(e), { passive: false });
    on(window, 'resize', () => this.resize());
    on(window, 'keydown', (e: KeyboardEvent) => this.onKey(e));
    on(document, 'visibilitychange', () => { if (document.hidden) void this.flush(); });
    on(window, 'pagehide', () => void this.flush());
  }

  private setTool(tool: Tool) {
    this.tool = tool;
    if (tool !== 'lasso') this.clearSelection();
    for (const b of this.toolbar.querySelectorAll<HTMLButtonElement>('[data-tool]'))
      b.classList.toggle('active', b.dataset.tool === tool);
    this.optionsBar.classList.remove('collapsed');
    this.renderOptions();
  }

  private renderOptions() {
    const bar = this.optionsBar;
    bar.replaceChildren();
    const tool = this.tool;
    if (tool === 'pen' || tool === 'highlighter') {
      const swatches = h('div', { class: 'swatches' });
      for (const c of settings.palette) {
        const sw = h('button', { class: 'swatch', style: `--c:${c}`, 'aria-label': c });
        if (c.toLowerCase() === settings.colors[tool].toLowerCase()) sw.classList.add('active');
        sw.addEventListener('click', () => { settings.colors[tool] = c; saveSettings(); this.renderOptions(); });
        swatches.append(sw);
      }
      const custom = h('input', { type: 'color', class: 'color-input', value: settings.colors[tool], title: t('color') });
      custom.addEventListener('input', () => { settings.colors[tool] = custom.value; saveSettings(); });
      custom.addEventListener('change', () => this.renderOptions());
      swatches.append(custom);
      const widths = tool === 'pen' ? [1, 2, 3.5, 6] : [8, 14, 22];
      const wg = h('div', { class: 'widths' });
      for (const w of widths) {
        const b = h('button', { class: 'width-btn', 'aria-label': `${w}` });
        b.append(h('span', { style: `width:${Math.min(22, 3 + w * (tool === 'pen' ? 2.5 : 0.8))}px;height:${Math.min(22, 3 + w * (tool === 'pen' ? 2.5 : 0.8))}px;background:${settings.colors[tool]};${tool === 'highlighter' ? 'opacity:.5;border-radius:3px' : ''}` }));
        if (settings.widths[tool] === w) b.classList.add('active');
        b.addEventListener('click', () => { settings.widths[tool] = w; saveSettings(); this.renderOptions(); });
        wg.append(b);
      }
      bar.append(swatches, wg);
    } else if (tool === 'eraser') {
      const modes = h('div', { class: 'seg' });
      for (const m of ['stroke', 'precise'] as const) {
        const b = h('button', { class: settings.eraserMode === m ? 'active' : '' }, t(m === 'stroke' ? 'strokeEraser' : 'preciseEraser'));
        b.addEventListener('click', () => { settings.eraserMode = m; saveSettings(); this.renderOptions(); });
        modes.append(b);
      }
      const wg = h('div', { class: 'widths' });
      for (const w of [6, 12, 24, 40]) {
        const b = h('button', { class: 'width-btn', 'aria-label': `${w}` });
        b.append(h('span', { style: `width:${Math.min(24, 4 + w / 2)}px;height:${Math.min(24, 4 + w / 2)}px;border:1.5px solid currentColor;background:transparent` }));
        if (settings.eraserSize === w) b.classList.add('active');
        b.addEventListener('click', () => { settings.eraserSize = w; saveSettings(); this.renderOptions(); });
        wg.append(b);
      }
      bar.append(modes, wg);
    } else {
      bar.append(h('span', { class: 'hint' }, t('lasso')));
    }
  }

  private onSettingsChanged() {
    this.renderOptions();
    for (const i of this.views.keys()) this.renderInk(i);
  }

  // ---------------------------------------------------------------- layout & camera
  private relayout() {
    this.contentW = Math.max(...this.pages.map((p) => p.w), 100);
    let y = 0;
    this.layout = this.pages.map((p) => {
      const l = { x: (this.contentW - p.w) / 2, y, w: p.w, h: p.h };
      y += p.h + GAP;
      return l;
    });
    this.contentH = Math.max(y - GAP, 100);
    for (const [i, v] of this.views) {
      v.el.remove();
      v.bgJob.cancelled = true;
      this.views.delete(i);
    }
    this.scheduleUpdate();
  }

  private resize() {
    const r = this.viewport.getBoundingClientRect();
    this.vw = r.width;
    this.vh = r.height;
    for (const c of [this.wet, this.pred]) {
      c.width = Math.round(r.width * this.dpr);
      c.height = Math.round(r.height * this.dpr);
    }
    this.clampCamera();
    this.applyCamera();
  }

  private fitScale() {
    return Math.max(0.1, (this.vw - 24) / this.contentW);
  }

  fitWidth() {
    const center = this.currentPage();
    this.cam.s = this.fitScale();
    this.cam.ty = 12 - (this.layout[center]?.y ?? 0) * this.cam.s;
    this.clampCamera();
    this.applyCamera();
  }

  private clampCamera() {
    const c = this.cam;
    c.s = Math.min(MAX_SCALE, Math.max(this.fitScale() * 0.4, c.s));
    const cw = this.contentW * c.s, ch = this.contentH * c.s;
    if (cw <= this.vw) c.tx = (this.vw - cw) / 2;
    else c.tx = Math.min(24, Math.max(this.vw - cw - 24, c.tx));
    const top = 12, bottom = this.vh - ch - 80;
    if (ch + 92 <= this.vh) c.ty = top;
    else c.ty = Math.min(top, Math.max(bottom, c.ty));
  }

  private applyCamera() {
    const { s, tx, ty } = this.cam;
    this.world.style.transform = `translate3d(${tx}px,${ty}px,0) scale(${s})`;
    this.scheduleUpdate();
    clearTimeout(this.rerenderTimer);
    this.rerenderTimer = window.setTimeout(() => this.rerenderForScale(), 180);
  }

  private scheduleUpdate() {
    if (this.rafPending) return;
    this.rafPending = true;
    requestAnimationFrame(() => {
      this.rafPending = false;
      this.updateVisible();
    });
  }

  private screenToWorld(cx: number, cy: number): [number, number] {
    const r = this.viewport.getBoundingClientRect();
    return [(cx - r.left - this.cam.tx) / this.cam.s, (cy - r.top - this.cam.ty) / this.cam.s];
  }

  private pageAt(wx: number, wy: number): number {
    let best = -1, bestD = Infinity;
    this.layout.forEach((l, i) => {
      const dx = Math.max(l.x - wx, 0, wx - (l.x + l.w));
      const dy = Math.max(l.y - wy, 0, wy - (l.y + l.h));
      const d = dx + dy;
      if (d < bestD) { bestD = d; best = i; }
    });
    return best;
  }

  private currentPage(): number {
    if (!this.layout.length) return 0;
    const wy = (this.vh / 2 - this.cam.ty) / this.cam.s;
    return Math.max(0, this.pageAt(this.contentW / 2, wy));
  }

  private updateVisible() {
    const { s, ty } = this.cam;
    const margin = this.vh;
    const y0 = (-ty - margin) / s, y1 = (this.vh - ty + margin) / s;
    const keep = new Set<number>();
    this.layout.forEach((l, i) => {
      if (l.y + l.h >= y0 && l.y <= y1) keep.add(i);
    });
    for (const [i, v] of this.views) {
      if (!keep.has(i)) {
        v.bgJob.cancelled = true;
        v.el.remove();
        this.views.delete(i);
      }
    }
    for (const i of keep) if (!this.views.has(i)) this.mount(i);
    const cur = this.currentPage();
    this.pageLabel.textContent = `${cur + 1} / ${this.pages.length}`;
    if (this.meta.lastPage !== cur) {
      this.meta.lastPage = cur;
      this.metaDirty = true;
    }
    this.positionSelection();
  }

  private pixelScale(i: number) {
    const l = this.layout[i];
    return Math.min(this.cam.s * this.dpr, Math.sqrt(MAX_CANVAS_PX / (l.w * l.h)));
  }

  private mount(i: number) {
    const l = this.layout[i];
    const el = h('div', { class: 'page', style: `left:${l.x}px;top:${l.y}px;width:${l.w}px;height:${l.h}px` });
    const bg = h('canvas', { class: 'bg' });
    const ink = h('canvas', { class: 'ink' });
    el.append(bg, ink);
    this.world.append(el);
    const v: PageView = { el, bg, ink, scale: 0, bgJob: { cancelled: false } };
    this.views.set(i, v);
    this.renderPage(i);
  }

  private rerenderForScale() {
    for (const [i, v] of this.views) {
      const want = this.pixelScale(i);
      if (Math.abs(want / v.scale - 1) > 0.12) this.renderPage(i);
    }
  }

  private renderPage(i: number) {
    const v = this.views.get(i);
    if (!v) return;
    v.scale = this.pixelScale(i);
    this.renderBg(i);
    this.renderInk(i);
  }

  private renderBg(i: number) {
    const v = this.views.get(i)!;
    const p = this.pages[i];
    const sc = v.scale;
    v.bgJob.cancelled = true;
    const job = { cancelled: false };
    v.bgJob = job;
    if (p.bg.kind === 'pdf') {
      const no = p.bg.pdfPage;
      if (!v.bg.width) {
        v.bg.width = 1; v.bg.height = 1;
      }
      pdfForDoc(this.meta.id)
        .then((doc) => renderPdfPage(doc, no, v.bg, sc, job))
        .catch(() => { if (!job.cancelled) toast(t('pdfError')); });
    } else {
      v.bg.width = Math.round(p.w * sc);
      v.bg.height = Math.round(p.h * sc);
      const ctx = v.bg.getContext('2d')!;
      ctx.setTransform(sc, 0, 0, sc, 0, 0);
      drawTemplate(ctx, p.w, p.h, p.bg.template, p.bg.paper);
    }
  }

  private renderInk(i: number, exclude?: Set<string>) {
    const v = this.views.get(i);
    if (!v) return;
    const p = this.pages[i];
    const sc = v.scale;
    const W = Math.round(p.w * sc), H = Math.round(p.h * sc);
    if (v.ink.width !== W || v.ink.height !== H) {
      v.ink.width = W;
      v.ink.height = H;
    }
    const ctx = v.ink.getContext('2d')!;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, W, H);
    ctx.setTransform(sc, 0, 0, sc, 0, 0);
    const strokes = (exclude ? p.strokes.filter((s) => !exclude.has(s.id)) : p.strokes).map((s) => this.display(p, s));
    drawStrokes(ctx, strokes, settings.pressure);
  }

  private scheduleInk(i: number) {
    if (this.inkRaf.has(i)) return;
    this.inkRaf.add(i);
    requestAnimationFrame(() => {
      this.inkRaf.delete(i);
      this.renderInk(i);
    });
  }

  private display(p: PageData, s: Stroke): Stroke {
    const c = inkColorForPaper(p.bg, s.color);
    return c === s.color ? s : { ...s, color: c };
  }

  // ---------------------------------------------------------------- input
  private penOnly() {
    return settings.penOnly === 'on' || (settings.penOnly === 'auto' && penSeen);
  }

  private buttonAction(e: PointerEvent): ButtonAction | null {
    // 32 = eraser end / eraser button (Pointer Events spec), 2 = first barrel button, 4 = second
    if (e.buttons & 32 || e.button === 5) return 'eraser';
    if (e.buttons & 2) return settings.barrelButton;
    if (e.buttons & 4) return settings.secondButton;
    return null;
  }

  private effectiveTool(e: PointerEvent): Tool {
    const a = this.buttonAction(e);
    if (a && a !== 'none') return a;
    return this.tool;
  }

  private local(e: { clientX: number; clientY: number }, page: number): [number, number] {
    const [wx, wy] = this.screenToWorld(e.clientX, e.clientY);
    const l = this.layout[page];
    return [wx - l.x, wy - l.y];
  }

  private onDown(e: PointerEvent) {
    cancelAnimationFrame(this.fling);
    if (e.pointerType === 'pen') {
      if (!penSeen) {
        penSeen = true;
        try { localStorage.setItem(PEN_SEEN_KEY, '1'); } catch { /* ignore */ }
      }
      this.lastPenContact = performance.now();
      // palm rejection: a pen touching the screen cancels any finger gesture
      if (this.touches.size) {
        for (const id of this.touches.keys()) this.ignoredTouches.add(id);
        this.touches.clear();
        this.gesture = null;
        if (this.mode.kind === 'pan') this.mode = { kind: 'idle' };
      }
      if (this.mode.kind !== 'idle') this.cancelMode();
      this.startTool(e);
      return;
    }

    if (e.pointerType === 'touch') {
      const palm = (e.width > 45 && e.height > 45) || performance.now() - this.lastPenContact < 300 ||
        (this.mode.kind !== 'idle' && this.mode.kind !== 'pan' && this.isPenMode());
      if (palm) {
        this.ignoredTouches.add(e.pointerId);
        return;
      }
      this.touches.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY });
      this.viewport.setPointerCapture(e.pointerId);
      if (!this.gesture) this.gesture = { t0: performance.now(), max: 0, moved: 0 };
      this.gesture.max = Math.max(this.gesture.max, this.touches.size);

      if (!this.penOnly() && this.touches.size === 1) {
        this.startTool(e);
        return;
      }
      if (this.touches.size >= 2 && this.mode.kind !== 'idle' && this.mode.kind !== 'pan') {
        // second finger: the first finger was the start of a gesture, not a drawing
        this.cancelMode();
      }
      this.beginGesture();
      return;
    }

    // mouse
    if (e.button === 0) this.startTool(e);
    else {
      this.mode = { kind: 'pan', id: e.pointerId, last: [e.clientX, e.clientY] };
      this.viewport.setPointerCapture(e.pointerId);
    }
  }

  private isPenMode() {
    const m = this.mode;
    return 'id' in m && m.kind !== 'pan' && (m.kind !== 'ink' || m.pen);
  }

  private beginGesture() {
    const pts = [...this.touches.values()];
    this.velocity = [0, 0, performance.now()];
    if (pts.length === 1) {
      this.mode = { kind: 'pan', id: -1, last: [pts[0].x, pts[0].y] };
    } else {
      const [a, b] = pts;
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      const [wx, wy] = this.screenToWorld(mx, my);
      this.gesture!.pinch = { s0: this.cam.s, d0: Math.max(10, Math.hypot(a.x - b.x, a.y - b.y)), wx, wy };
      this.mode = { kind: 'pan', id: -1, last: [mx, my] };
    }
  }

  private startTool(e: PointerEvent) {
    const tool = this.effectiveTool(e);
    const [wx, wy] = this.screenToWorld(e.clientX, e.clientY);
    const page = this.pageAt(wx, wy);
    if (page < 0) return;
    this.viewport.setPointerCapture(e.pointerId);
    const [x, y] = this.local(e, page);
    this.lastProcessedTs.set(e.pointerId, e.timeStamp);

    if (tool === 'pen' || tool === 'highlighter') {
      this.clearSelection();
      const p = this.pressure(e, 0.5);
      this.mode = {
        kind: 'ink', id: e.pointerId, page, tool, pen: e.pointerType === 'pen',
        color: inkColorForPaper(this.pages[page].bg, settings.colors[tool]),
        width: settings.widths[tool], pts: [x, y, p], settled: 1, lastP: p,
      };
      this.drawWet([]);
    } else if (tool === 'eraser') {
      this.clearSelection();
      this.mode = { kind: 'erase', id: e.pointerId, page, before: this.pages[page].strokes, changed: false };
      this.eraseAt(page, x, y);
      this.drawEraserCursor(e.clientX, e.clientY);
    } else if (tool === 'lasso') {
      const sel = this.selection;
      if (sel && sel.page === page && this.insideSelection(page, x, y)) {
        const before = this.pages[page].strokes;
        const moving = before.filter((s) => sel.ids.has(s.id));
        this.mode = { kind: 'move', id: e.pointerId, page, start: [x, y], d: [0, 0], before, moving };
        this.renderInk(page, sel.ids);
        this.selBox.hidden = true;
        this.drawMoving();
      } else {
        this.clearSelection();
        this.mode = { kind: 'lasso', id: e.pointerId, page, poly: [x, y] };
      }
    }
  }

  private pressure(e: PointerEvent, prev: number) {
    if (e.pointerType !== 'pen') return 0.5;
    const raw = Math.max(0.05, Math.min(1, e.pressure || 0.05));
    return prev * 0.35 + raw * 0.65;
  }

  /** pointerrawupdate: fires without waiting for the next frame → lowest latency for ink */
  private onRaw(e: PointerEvent) {
    const m = this.mode;
    if (m.kind === 'ink' && m.id === e.pointerId) this.extendInk(e);
  }

  private onMove(e: PointerEvent) {
    if (e.pointerType === 'pen') {
      this.lastPenContact = e.buttons ? performance.now() : this.lastPenContact;
      if (this.mode.kind === 'idle') {
        this.hover(e);
        return;
      }
    }
    if (this.ignoredTouches.has(e.pointerId)) return;
    const m = this.mode;

    if (e.pointerType === 'touch' && this.touches.has(e.pointerId)) {
      const tp = this.touches.get(e.pointerId)!;
      tp.x = e.clientX;
      tp.y = e.clientY;
      if (this.gesture) this.gesture.moved = Math.max(this.gesture.moved, Math.hypot(tp.x - tp.sx, tp.y - tp.sy));
      if (m.kind === 'pan') return this.updateGesture();
    }

    if (!('id' in m)) return;
    if (m.kind === 'pan' && m.id === e.pointerId) {
      this.cam.tx += e.clientX - m.last[0];
      this.cam.ty += e.clientY - m.last[1];
      m.last = [e.clientX, e.clientY];
      this.clampCamera();
      this.applyCamera();
      return;
    }
    if (m.id !== e.pointerId) return;
    if (m.kind === 'ink') return this.extendInk(e);
    const events = e.getCoalescedEvents?.() ?? [e];
    if (m.kind === 'erase') {
      for (const ce of events.length ? events : [e]) {
        const [x, y] = this.local(ce, m.page);
        this.eraseAt(m.page, x, y);
      }
      this.drawEraserCursor(e.clientX, e.clientY);
    } else if (m.kind === 'lasso') {
      for (const ce of events.length ? events : [e]) {
        const [x, y] = this.local(ce, m.page);
        m.poly.push(x, y);
      }
      this.drawLasso(m.page, m.poly);
    } else if (m.kind === 'move') {
      const [x, y] = this.local(e, m.page);
      m.d = [x - m.start[0], y - m.start[1]];
      this.drawMoving();
    }
  }

  private extendInk(e: PointerEvent) {
    const m = this.mode;
    if (m.kind !== 'ink') return;
    const last = this.lastProcessedTs.get(e.pointerId) ?? 0;
    const events = e.getCoalescedEvents?.() ?? [];
    const list = events.length ? events : [e];
    const minDist = 0.35 / this.cam.s;
    for (const ce of list) {
      if (ce.timeStamp <= last && list.length > 1) continue;
      const [x, y] = this.local(ce, m.page);
      const n = m.pts.length;
      const p = this.pressure(ce, m.lastP);
      m.lastP = p;
      if (Math.hypot(x - m.pts[n - 3], y - m.pts[n - 2]) < minDist) {
        m.pts[n - 1] = Math.max(m.pts[n - 1], p);
        continue;
      }
      m.pts.push(x, y, p);
    }
    this.lastProcessedTs.set(e.pointerId, list[list.length - 1].timeStamp);
    const predicted: number[] = [];
    for (const pe of (e.getPredictedEvents?.() ?? []).slice(0, 2)) {
      const [x, y] = this.local(pe, m.page);
      predicted.push(x, y, m.lastP);
    }
    this.drawWet(predicted);
  }

  private wetTransform(ctx: CanvasRenderingContext2D, page: number) {
    const l = this.layout[page];
    const { s, tx, ty } = this.cam;
    const d = this.dpr;
    ctx.setTransform(d * s, 0, 0, d * s, d * (tx + l.x * s), d * (ty + l.y * s));
  }

  private clearCanvas(ctx: CanvasRenderingContext2D) {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);
  }

  private clearPred() {
    this.clearCanvas(this.pctx);
  }

  /** Settled segments go to the wet canvas incrementally; the live tail + prediction is redrawn each event. */
  private drawWet(predicted: number[]) {
    const m = this.mode;
    if (m.kind !== 'ink') return;
    const n = m.pts.length / 3;
    if (m.tool === 'highlighter') {
      this.clearCanvas(this.wctx);
      this.wetTransform(this.wctx, m.page);
      drawHighlighterPath(this.wctx, m.pts.concat(predicted), m.color, m.width);
      return;
    }
    this.wetTransform(this.wctx, m.page);
    if (n - 1 > m.settled) {
      drawPenSegments(this.wctx, m.pts, m.settled, n - 1, m.color, m.width, settings.pressure);
      m.settled = n - 1;
    }
    this.clearCanvas(this.pctx);
    this.wetTransform(this.pctx, m.page);
    if (n < 3) {
      drawPenSegments(this.pctx, m.pts.concat(predicted), 1, Infinity, m.color, m.width, settings.pressure);
    } else {
      const tail = m.pts.slice((n - 3) * 3).concat(predicted);
      drawPenSegments(this.pctx, tail, 2, Infinity, m.color, m.width, settings.pressure);
    }
  }

  private hover(e: PointerEvent) {
    const a = this.buttonAction(e);
    const tool = a && a !== 'none' ? a : this.tool;
    if (tool === 'eraser') this.drawEraserCursor(e.clientX, e.clientY);
    else this.clearPred();
  }

  private drawEraserCursor(cx: number, cy: number) {
    const r = this.viewport.getBoundingClientRect();
    const ctx = this.pctx;
    this.clearCanvas(ctx);
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.beginPath();
    ctx.arc(cx - r.left, cy - r.top, settings.eraserSize, 0, Math.PI * 2);
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = 'rgba(80,90,110,.8)';
    ctx.fillStyle = 'rgba(255,255,255,.25)';
    ctx.fill();
    ctx.stroke();
  }

  private drawLasso(page: number, poly: number[]) {
    const ctx = this.wctx;
    this.clearCanvas(ctx);
    this.wetTransform(ctx, page);
    ctx.beginPath();
    ctx.moveTo(poly[0], poly[1]);
    for (let i = 2; i < poly.length; i += 2) ctx.lineTo(poly[i], poly[i + 1]);
    ctx.setLineDash([6 / this.cam.s, 5 / this.cam.s]);
    ctx.lineWidth = 1.5 / this.cam.s;
    ctx.strokeStyle = '#2156d1';
    ctx.stroke();
    ctx.setLineDash([]);
  }

  private drawMoving() {
    const m = this.mode;
    if (m.kind !== 'move') return;
    const ctx = this.wctx;
    this.clearCanvas(ctx);
    this.wetTransform(ctx, m.page);
    ctx.translate(m.d[0], m.d[1]);
    const p = this.pages[m.page];
    drawStrokes(ctx, m.moving.map((s) => this.display(p, s)), settings.pressure);
    const bb = unionBBox(m.moving);
    if (bb) {
      ctx.setLineDash([5 / this.cam.s, 4 / this.cam.s]);
      ctx.lineWidth = 1.2 / this.cam.s;
      ctx.strokeStyle = '#2156d1';
      ctx.strokeRect(bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1]);
      ctx.setLineDash([]);
    }
  }

  private updateGesture() {
    const pts = [...this.touches.values()];
    const m = this.mode;
    if (m.kind !== 'pan' || !this.gesture) return;
    const now = performance.now();
    if (pts.length >= 2 && this.gesture.pinch) {
      const [a, b] = pts;
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      const d = Math.max(10, Math.hypot(a.x - b.x, a.y - b.y));
      const pz = this.gesture.pinch;
      const r = this.viewport.getBoundingClientRect();
      this.cam.s = Math.min(MAX_SCALE, Math.max(this.fitScale() * 0.4, pz.s0 * (d / pz.d0)));
      this.cam.tx = mx - r.left - pz.wx * this.cam.s;
      this.cam.ty = my - r.top - pz.wy * this.cam.s;
    } else if (pts.length === 1) {
      const dx = pts[0].x - m.last[0], dy = pts[0].y - m.last[1];
      this.cam.tx += dx;
      this.cam.ty += dy;
      const dt = Math.max(1, now - this.velocity[2]);
      this.velocity = [dx / dt, dy / dt, now];
      m.last = [pts[0].x, pts[0].y];
    }
    this.clampCamera();
    this.applyCamera();
  }

  private onUp(e: PointerEvent, cancelled: boolean) {
    if (this.ignoredTouches.delete(e.pointerId)) return;
    if (e.pointerType === 'pen') this.lastPenContact = performance.now();

    if (e.pointerType === 'touch' && this.touches.has(e.pointerId)) {
      this.touches.delete(e.pointerId);
      const g = this.gesture;
      if (this.touches.size === 0) {
        this.gesture = null;
        if (g && !cancelled && g.max >= 2 && g.moved < 12 && performance.now() - g.t0 < 350 && settings.twoFingerUndo) {
          if (g.max === 2) this.undo();
          else this.redo();
        }
        if (this.mode.kind === 'pan') {
          this.mode = { kind: 'idle' };
          if (g && g.max === 1) this.startFling();
          return;
        }
      } else if (this.mode.kind === 'pan') {
        // continue with remaining fingers from a fresh baseline
        if (g) g.pinch = undefined;
        this.beginGesture();
        return;
      }
    }

    const m = this.mode;
    if (!('id' in m) || m.id !== e.pointerId) return;
    this.mode = { kind: 'idle' };
    if (cancelled && m.kind === 'ink') {
      this.clearCanvas(this.wctx);
      this.clearPred();
      return;
    }
    switch (m.kind) {
      case 'ink': return this.commitInk(m);
      case 'erase': {
        this.clearPred();
        if (m.changed) this.commit(m.page, m.before, this.pages[m.page].strokes, false);
        return;
      }
      case 'lasso': {
        this.clearCanvas(this.wctx);
        const strokes = this.pages[m.page].strokes;
        const ids = new Set(strokes.filter((s) => lassoSelects(s, m.poly)).map((s) => s.id));
        if (ids.size) this.setSelection(m.page, ids);
        return;
      }
      case 'move': {
        this.clearCanvas(this.wctx);
        const [dx, dy] = m.d;
        if (Math.abs(dx) + Math.abs(dy) > 0.01) {
          const moved = new Map(m.moving.map((s) => [s.id, translateStroke(s, dx, dy)]));
          const after = m.before.map((s) => moved.get(s.id) ?? s);
          this.commit(m.page, m.before, after);
        } else this.renderInk(m.page);
        this.setSelection(m.page, new Set(m.moving.map((s) => s.id)));
        return;
      }
      case 'pan':
        return;
    }
  }

  private cancelMode() {
    const m = this.mode;
    this.mode = { kind: 'idle' };
    this.clearCanvas(this.wctx);
    this.clearPred();
    if (m.kind === 'erase' && m.changed) {
      this.pages[m.page].strokes = m.before;
      this.renderInk(m.page);
    }
    if (m.kind === 'move') this.renderInk(m.page);
  }

  private commitInk(m: Extract<Mode, { kind: 'ink' }>) {
    const page = this.pages[m.page];
    const stroke = makeStroke(m.tool, settings.colors[m.tool], m.width, m.pts);
    const before = page.strokes;
    const after = [...before, stroke];
    page.strokes = after;
    // draw just this stroke onto the page; highlighters need a full redraw to stay under ink
    const v = this.views.get(m.page);
    if (v && m.tool === 'pen') {
      const ctx = v.ink.getContext('2d')!;
      ctx.setTransform(v.scale, 0, 0, v.scale, 0, 0);
      drawStroke(ctx, this.display(page, stroke), settings.pressure);
    } else this.renderInk(m.page);
    this.clearCanvas(this.wctx);
    this.clearPred();
    this.pushHistory({ pageId: page.id, before, after });
    this.markDirty(page.id);
  }

  private eraseAt(page: number, x: number, y: number) {
    const m = this.mode;
    if (m.kind !== 'erase') return;
    const p = this.pages[page];
    const r = settings.eraserSize / this.cam.s;
    let changed = false;
    let next: Stroke[];
    if (settings.eraserMode === 'stroke') {
      next = p.strokes.filter((s) => !strokeHit(s, x, y, r));
      changed = next.length !== p.strokes.length;
    } else {
      next = [];
      for (const s of p.strokes) {
        const res = eraseFromStroke(s, x, y, r);
        if (res) { changed = true; next.push(...res); } else next.push(s);
      }
    }
    if (changed) {
      p.strokes = next;
      m.changed = true;
      this.scheduleInk(page);
    }
  }

  // ---------------------------------------------------------------- selection
  private setSelection(page: number, ids: Set<string>) {
    this.selection = { page, ids };
    const v = this.views.get(page);
    if (v && this.selBox.parentElement !== v.el) v.el.append(this.selBox);
    this.positionSelection();
    this.selBar.hidden = false;
    this.selBar.replaceChildren();
    const mk = (icon: string, label: string, fn: () => void) => {
      const b = h('button', { class: 'icon-btn', title: label, 'aria-label': label });
      b.innerHTML = icon;
      b.addEventListener('click', fn);
      return b;
    };
    this.selBar.append(
      mk(icons.trash, t('delete'), () => this.selectionDelete()),
      mk(icons.copy, t('duplicate'), () => this.selectionDuplicate()),
    );
    for (const c of settings.palette.slice(0, 6)) {
      const sw = h('button', { class: 'swatch', style: `--c:${c}`, 'aria-label': c });
      sw.addEventListener('click', () => this.selectionRecolor(c));
      this.selBar.append(sw);
    }
  }

  private positionSelection() {
    const sel = this.selection;
    if (!sel) return;
    const p = this.pages[sel.page];
    const bb = unionBBox(p.strokes.filter((s) => sel.ids.has(s.id)));
    const v = this.views.get(sel.page);
    if (!bb || !v) {
      this.selBox.hidden = true;
      return;
    }
    if (this.selBox.parentElement !== v.el) v.el.append(this.selBox);
    Object.assign(this.selBox.style, {
      left: `${bb[0]}px`, top: `${bb[1]}px`, width: `${bb[2] - bb[0]}px`, height: `${bb[3] - bb[1]}px`,
      borderWidth: `${1.5 / this.cam.s}px`,
    });
    this.selBox.hidden = this.mode.kind === 'move';
  }

  private insideSelection(page: number, x: number, y: number) {
    const sel = this.selection!;
    const bb = unionBBox(this.pages[page].strokes.filter((s) => sel.ids.has(s.id)));
    const pad = 8 / this.cam.s;
    return !!bb && x >= bb[0] - pad && x <= bb[2] + pad && y >= bb[1] - pad && y <= bb[3] + pad;
  }

  private clearSelection() {
    this.selection = null;
    this.selBox.hidden = true;
    this.selBar.hidden = true;
  }

  private selectionDelete() {
    const sel = this.selection;
    if (!sel) return;
    const before = this.pages[sel.page].strokes;
    this.commit(sel.page, before, before.filter((s) => !sel.ids.has(s.id)));
    this.clearSelection();
  }

  private selectionDuplicate() {
    const sel = this.selection;
    if (!sel) return;
    const before = this.pages[sel.page].strokes;
    const copies = before.filter((s) => sel.ids.has(s.id)).map((s) => translateStroke(s, 16, 16, true));
    this.commit(sel.page, before, [...before, ...copies]);
    this.setSelection(sel.page, new Set(copies.map((s) => s.id)));
  }

  private selectionRecolor(color: string) {
    const sel = this.selection;
    if (!sel) return;
    const before = this.pages[sel.page].strokes;
    this.commit(sel.page, before, before.map((s) => (sel.ids.has(s.id) ? { ...s, color } : s)));
  }

  // ---------------------------------------------------------------- history & persistence
  private commit(page: number, before: Stroke[], after: Stroke[], apply = true) {
    const p = this.pages[page];
    if (apply) p.strokes = after;
    this.renderInk(page);
    this.pushHistory({ pageId: p.id, before, after });
    this.markDirty(p.id);
    this.positionSelection();
  }

  private pushHistory(e: HistoryEntry) {
    this.undoStack.push(e);
    if (this.undoStack.length > 200) this.undoStack.shift();
    this.redoStack = [];
  }

  undo() { this.step(this.undoStack, this.redoStack, 'before'); }
  redo() { this.step(this.redoStack, this.undoStack, 'after'); }

  private step(from: HistoryEntry[], to: HistoryEntry[], which: 'before' | 'after'): void {
    if (this.mode.kind !== 'idle' && this.mode.kind !== 'pan') return;
    const e = from.pop();
    if (!e) return;
    const i = this.pages.findIndex((p) => p.id === e.pageId);
    if (i < 0) return this.step(from, to, which);
    this.pages[i].strokes = e[which];
    to.push(e);
    this.clearSelection();
    this.renderInk(i);
    this.markDirty(e.pageId);
    // bring the change into view if it's off-screen
    if (!this.views.has(i)) {
      this.cam.ty = 12 - this.layout[i].y * this.cam.s;
      this.clampCamera();
      this.applyCamera();
    }
  }

  private markDirty(pageId: string) {
    this.dirty.add(pageId);
    this.metaDirty = true;
    clearTimeout(this.saveTimer);
    this.saveTimer = window.setTimeout(() => void this.flush(), 800);
  }

  async flush() {
    clearTimeout(this.saveTimer);
    const ids = [...this.dirty];
    this.dirty.clear();
    if (ids.length) await putPages(this.pages.filter((p) => ids.includes(p.id)));
    if (this.metaDirty || ids.length) {
      this.metaDirty = false;
      if (ids.length) this.meta.updatedAt = Date.now();
      await putDoc(this.meta);
    }
  }

  // ---------------------------------------------------------------- pages
  private addPage() {
    const cur = this.currentPage();
    const ref = this.pages[cur];
    const page: PageData = {
      id: uid(),
      w: ref?.bg.kind === 'template' ? ref.w : A4.w,
      h: ref?.bg.kind === 'template' ? ref.h : A4.h,
      bg: { kind: 'template', template: this.meta.template, paper: this.meta.paper },
      strokes: [],
    };
    this.pages.splice(cur + 1, 0, page);
    this.meta.pageIds = this.pages.map((p) => p.id);
    this.relayout();
    this.cam.ty = 12 - this.layout[cur + 1].y * this.cam.s;
    this.clampCamera();
    this.applyCamera();
    this.markDirty(page.id);
  }

  private async deleteCurrentPage() {
    if (this.pages.length <= 1) return;
    if (!confirm(t('confirmDeletePage'))) return;
    const cur = this.currentPage();
    const [removed] = this.pages.splice(cur, 1);
    this.meta.pageIds = this.pages.map((p) => p.id);
    this.undoStack = this.undoStack.filter((e) => e.pageId !== removed.id);
    this.redoStack = this.redoStack.filter((e) => e.pageId !== removed.id);
    this.clearSelection();
    this.relayout();
    this.clampCamera();
    this.applyCamera();
    this.metaDirty = true;
    await deletePages([removed.id]);
    await this.flush();
  }

  private async exportPdf() {
    toast(t('exporting'));
    try {
      await this.flush();
      const bytes = await exportDocToPdf(this.meta, this.pages);
      await shareOrDownload(bytes, `${this.meta.title.replace(/[\\/:*?"<>|]+/g, '_') || 'notes'}.pdf`);
    } catch (err) {
      console.error(err);
      toast(String(err));
    }
  }

  // ---------------------------------------------------------------- misc input
  private onWheel(e: WheelEvent) {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) {
      const r = this.viewport.getBoundingClientRect();
      const [wx, wy] = this.screenToWorld(e.clientX, e.clientY);
      this.cam.s *= Math.exp(-e.deltaY * 0.0025);
      this.cam.s = Math.min(MAX_SCALE, Math.max(this.fitScale() * 0.4, this.cam.s));
      this.cam.tx = e.clientX - r.left - wx * this.cam.s;
      this.cam.ty = e.clientY - r.top - wy * this.cam.s;
    } else {
      this.cam.tx -= e.deltaX;
      this.cam.ty -= e.deltaY;
    }
    this.clampCamera();
    this.applyCamera();
  }

  private startFling() {
    let [vx, vy] = this.velocity;
    if (performance.now() - this.velocity[2] > 80) return;
    let prev = performance.now();
    const tick = (now: number) => {
      const dt = now - prev;
      prev = now;
      this.cam.tx += vx * dt;
      this.cam.ty += vy * dt;
      const decay = Math.pow(0.995, dt);
      vx *= decay;
      vy *= decay;
      this.clampCamera();
      this.applyCamera();
      if (Math.hypot(vx, vy) > 0.02) this.fling = requestAnimationFrame(tick);
    };
    this.fling = requestAnimationFrame(tick);
  }

  private onKey(e: KeyboardEvent) {
    if ((e.target as HTMLElement)?.closest?.('input,textarea,dialog')) return;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? this.redo() : this.undo(); }
    else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); this.redo(); }
    else if (!mod) {
      const map: Record<string, Tool> = { p: 'pen', h: 'highlighter', e: 'eraser', l: 'lasso' };
      if (map[e.key]) this.setTool(map[e.key]);
      if ((e.key === 'Delete' || e.key === 'Backspace') && this.selection) this.selectionDelete();
    }
  }

  // ---------------------------------------------------------------- close
  private async makeThumb(): Promise<string | undefined> {
    const p = this.pages[0];
    if (!p) return;
    const W = 240, sc = W / p.w;
    const c = document.createElement('canvas');
    c.width = W;
    c.height = Math.round(p.h * sc);
    const ctx = c.getContext('2d')!;
    if (p.bg.kind === 'pdf') {
      const tmp = document.createElement('canvas');
      await renderPdfPage(await pdfForDoc(this.meta.id), p.bg.pdfPage, tmp, sc);
      ctx.drawImage(tmp, 0, 0, c.width, c.height);
    } else {
      ctx.setTransform(sc, 0, 0, sc, 0, 0);
      drawTemplate(ctx, p.w, p.h, p.bg.template, p.bg.paper);
    }
    ctx.setTransform(sc, 0, 0, sc, 0, 0);
    drawStrokes(ctx, p.strokes.map((s) => this.display(p, s)), settings.pressure);
    return c.toDataURL('image/jpeg', 0.75);
  }

  async close() {
    if (this.closed) return;
    this.closed = true;
    try {
      this.meta.thumb = (await this.makeThumb()) ?? this.meta.thumb;
      this.metaDirty = true;
    } catch (err) {
      console.warn('thumbnail failed', err);
    }
    await this.flush();
    for (const d of this.disposers) d();
    cancelAnimationFrame(this.fling);
    for (const v of this.views.values()) v.bgJob.cancelled = true;
    closePdf(this.meta.id);
    this.root.remove();
    this.onClose();
  }
}
