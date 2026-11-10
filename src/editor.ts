import type { DocMeta, PageData, Stroke, Tool, InkTool, TextBox } from './types';
import { A4, uid } from './types';
import { getPages, putDoc, putPages, deletePages, deleteFile } from './db';
import { drawStrokes, drawTemplate, drawPenSegments, drawHighlighterPath, inkColorForPaper, drawStroke } from './ink';
import { drawPageContent, displayStroke, imageBitmapFor, clearImageCache, renderPageToCanvas } from './render';
import { drawTextBox, textBoxHit, fontOf, LINE_HEIGHT, isRtl, drawBubble, boxHeight, BUBBLE_PAD } from './text';
import { openShareDialog } from './share';
import { convertHandwriting } from './handwriting';
import { makeStroke, strokeHit, eraseFromStroke, lassoSelects, translateStroke, unionBBox, straightenStroke, recognizeShape } from './geometry';
import { Ruler, type Edge } from './ruler';
import { NotesPanel, VoiceRecorder, clipFromStroke, clipFromRect, saveVoiceMemo, fmtTime } from './clips';
import { findFreeSpot } from './freeSpace';
import { settings, saveSettings, type ButtonAction } from './settings';
import { pdfForDoc, renderPdfPage, closePdf } from './pdf';
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
interface HistoryEntry { pageId: string; before: Stroke[]; after: Stroke[]; tBefore?: TextBox[]; tAfter?: TextBox[] }

type Mode =
  | { kind: 'idle' }
  | { kind: 'ink'; id: number; page: number; tool: InkTool; color: string; width: number; pts: number[]; settled: number; lastP: number; pen: boolean; shape: boolean; edge: Edge | null; vr: DOMRect; tilt: number; tiltN: number }
  | { kind: 'laser'; id: number }
  | { kind: 'clip'; id: number; page: number; pts: number[] }
  | { kind: 'erase'; id: number; page: number; before: Stroke[]; changed: boolean }
  | { kind: 'lasso'; id: number; page: number; poly: number[] }
  | { kind: 'move'; id: number; page: number; start: [number, number]; d: [number, number]; before: Stroke[]; moving: Stroke[] }
  | { kind: 'text'; id: number; page: number; start: [number, number]; hit: TextBox | null; d: [number, number]; moved: boolean }
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
  private toolbar!: HTMLElement;
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
  private reading = false;
  private fitMode: 'width' | 'page' | null = 'width';
  private editing: { page: number; tb: TextBox; orig: TextBox | null; el: HTMLTextAreaElement } | null = null;
  private readBtn!: HTMLButtonElement;
  private hold: { id: number; cx: number; cy: number; timer: number } | null = null;
  private stage!: HTMLDivElement;
  private dock!: HTMLDivElement;
  private colorDot!: HTMLButtonElement;
  private docSub!: HTMLDivElement;
  private hud!: { el: HTMLDivElement; bar: HTMLElement; val: HTMLElement; tilt: HTMLElement; btn: HTMLElement };
  private hudPending: PointerEvent | null = null;
  private toggles: Record<string, HTMLButtonElement> = {};
  private panel!: NotesPanel;
  private ruler = new Ruler();
  private rulerTouches = new Map<number, { x: number; y: number }>();
  private laser!: HTMLCanvasElement;
  private lctx!: CanvasRenderingContext2D;
  private trail: { x: number; y: number; t: number; gap?: boolean }[] = [];
  private laserLoop = 0;
  private recorder = new VoiceRecorder();
  private recBtn!: HTMLButtonElement;
  private recTimer = 0;
  private dockH = 60;
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

  constructor(private meta: DocMeta, private onClose: () => void, private openDocAt?: (docId: string, page: number) => void) {
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
    this.laser = h('canvas', { class: 'wet' });
    this.selBox = h('div', { class: 'sel-box', hidden: true });
    this.viewport.append(this.world, this.wet, this.pred, this.laser);
    // desynchronized = low-latency front-buffer rendering on Chrome/Android
    this.wctx = this.wet.getContext('2d', { desynchronized: true })!;
    this.pctx = this.pred.getContext('2d', { desynchronized: true })!;
    this.lctx = this.laser.getContext('2d', { desynchronized: true })!;

    const btn = (icon: string, title: string, on: () => void, cls = '') => {
      const b = h('button', { class: `icon-btn ${cls}`, title, 'aria-label': title });
      b.innerHTML = icon;
      b.addEventListener('click', on);
      return b;
    };
    const labelled = (b: HTMLButtonElement, text: string) => {
      b.classList.add('labelled');
      b.append(h('span', {}, text));
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

    // ---- header
    const mark = h('div', { class: 'brand-mark', 'aria-hidden': 'true' });
    mark.innerHTML = icons.pen;
    this.docSub = h('div', { class: 'doc-sub' });
    const hudBar = h('i');
    this.hud = {
      el: h('div', { class: 'hud', title: `${t('pressureLabel')} / ${t('tiltLabel')}` }),
      bar: hudBar,
      val: h('b', { dir: 'ltr' }, '—'),
      tilt: h('b', { dir: 'ltr' }, '—'),
      btn: h('span', { class: 'hud-btn' }),
    };
    this.hud.el.append(
      h('span', { class: 'hud-pen' }, 'Pen Plus'),
      h('span', { class: 'hud-k' }, t('pressureLabel')), h('span', { class: 'hud-meter' }, hudBar), this.hud.val,
      h('span', { class: 'hud-k' }, t('tiltLabel')), this.hud.tilt, this.hud.btn,
    );
    this.hud.el.hidden = !settings.showHud;

    this.readBtn = labelled(btn(icons.book, t('readingMode'), () => this.setReading(!this.reading), 'read-toggle'), t('readingMode'));
    this.toggles.palm = btn(icons.palm, t('palmRejection'), () => this.togglePalm(), 'toggle');
    this.toggles.spread = btn(icons.columns, t('spread'), () => this.toggleSpread(), 'toggle');
    this.toggles.eye = btn(icons.moon, t('eyeCare'), () => this.toggleEyeCare(), 'toggle');
    this.toggles.notes = labelled(btn(icons.notebook, t('notes'), () => this.toggleNotes(!settings.notesPanel), 'toggle notes-toggle'), 'QalamNote');
    const backBtn = btn(icons.back, t('back'), () => this.close(), 'back-btn');
    this.toolbar = h('header', { class: 'topbar' },
      backBtn,
      mark,
      h('div', { class: 'doc-head' }, h('div', { class: 'doc-title' }, this.meta.title), this.docSub),
      this.hud.el,
      h('div', { class: 'spacer' }),
      this.readBtn,
      h('div', { class: 'toggle-group' }, this.toggles.palm, this.toggles.spread, this.toggles.eye),
      this.toggles.notes,
      btn(icons.share, t('share'), () => this.share()),
      btn(icons.settings, t('settings'), () => openSettings(() => this.onSettingsChanged())),
    );

    // ---- floating dock
    this.colorDot = h('button', { class: 'color-dot', title: t('color'), 'aria-label': t('color') });
    this.colorDot.addEventListener('click', () => this.optionsBar.classList.toggle('collapsed'));
    this.recBtn = btn(icons.mic, t('record'), () => void this.toggleRecording(), 'rec-btn');
    this.recBtn.append(h('span', { class: 'rec-time' }));
    const rulerBtn = btn(icons.ruler, t('ruler'), () => this.toggleRuler(), 'ruler-btn');
    this.dock = h('div', { class: 'dock' },
      h('div', { class: 'tool-group' },
        toolBtn('pen'), toolBtn('highlighter'), toolBtn('shape'), toolBtn('laser'),
        toolBtn('eraser'), toolBtn('lasso'), toolBtn('clip'), toolBtn('text'),
      ),
      h('i', { class: 'sep' }), this.colorDot,
      h('i', { class: 'sep' }), rulerBtn, this.recBtn,
      h('i', { class: 'sep' }), btn(icons.undo, t('undo'), () => this.undo()), btn(icons.redo, t('redo'), () => this.redo()),
    );
    this.pageLabel = h('button', { class: 'page-label', title: t('fitWidth'), dir: 'ltr' });
    this.pageLabel.addEventListener('click', () => this.fitWidth());
    const nav = h('div', { class: 'nav-pill' },
      btn(icons.prev, t('prevPage'), () => this.goPage(-1), 'flip-rtl'),
      this.pageLabel,
      btn(icons.next, t('nextPage'), () => this.goPage(1), 'flip-rtl'),
      h('i', { class: 'sep' }),
      btn(icons.zoomOut, t('zoomOut'), () => this.zoomBy(1 / 1.25)),
      btn(icons.zoomIn, t('zoomIn'), () => this.zoomBy(1.25)),
      btn(icons.fit, t('fitScreen'), () => this.toggleFit()),
      h('i', { class: 'sep edit-only' }),
      btn(icons.plus, t('addPage'), () => this.addPage(), 'edit-only'),
      btn(icons.trash, t('deletePage'), () => this.deleteCurrentPage(), 'edit-only'),
    );
    this.optionsBar = h('div', { class: 'options-bar collapsed' });
    this.selBar = h('div', { class: 'sel-bar', hidden: true });
    const dockWrap = h('div', { class: 'dock-wrap' }, nav, this.dock);
    this.stage = h('div', { class: 'stage' }, this.viewport, this.ruler.el, this.selBar, this.optionsBar, dockWrap);
    // the dock wraps onto two rows on a portrait screen: keep the popover, toasts and page clamp above it
    const ro = new ResizeObserver(() => {
      this.dockH = dockWrap.offsetHeight;
      document.documentElement.style.setProperty('--dock-h', `${this.dockH}px`);
    });
    ro.observe(dockWrap);
    this.disposers.push(() => ro.disconnect());
    this.panel = new NotesPanel({
      meta: this.meta,
      jump: (page, rect) => this.jumpTo(page, rect),
      openDoc: (docId, page) => void this.openOther(docId, page),
      close: () => this.toggleNotes(false),
    });

    this.root.append(this.toolbar, h('div', { class: 'workspace' }, this.stage, this.panel.root));
    this.setTool('pen');
    this.setReading(this.meta.reading ?? false, true);
    this.applyToggles();
    if (settings.notesPanel) void this.panel.refresh();
    // keep the keyboard open while changing text size/colour
    for (const type of ['pointerdown', 'mousedown'])
      this.optionsBar.addEventListener(type, (e) => { if (this.editing) e.preventDefault(); });

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
    // the options popover gets out of the way once you start drawing (text keeps it: size, colour, delete)
    on(vp, 'pointerdown', () => { if (!this.editing && this.tool !== 'text') this.optionsBar.classList.add('collapsed'); }, { capture: true });
    on(vp, 'contextmenu', (e: Event) => e.preventDefault());
    on(vp, 'wheel', (e: WheelEvent) => this.onWheel(e), { passive: false });
    on(window, 'resize', () => this.resize());
    on(window, 'keydown', (e: KeyboardEvent) => this.onKey(e));
    on(document, 'visibilitychange', () => { if (document.hidden) void this.flush(); });
    on(window, 'pagehide', () => void this.flush());
  }

  private setReading(on: boolean, initial = false) {
    this.finishEditing();
    this.reading = on;
    this.root.classList.toggle('reading', on);
    this.readBtn.classList.toggle('active', on);
    this.clearSelection();
    this.clearPred();
    if (!initial) {
      this.meta.reading = on;
      this.metaDirty = true;
      clearTimeout(this.saveTimer);
      this.saveTimer = window.setTimeout(() => void this.flush(), 800);
      requestAnimationFrame(() => this.resize());
    }
  }

  private applyToggles() {
    this.toggles.palm.classList.toggle('active', this.penOnly());
    this.toggles.spread.classList.toggle('active', settings.spread === 'dual');
    this.toggles.spread.innerHTML = settings.spread === 'dual' ? icons.columns : icons.single;
    this.toggles.eye.classList.toggle('active', settings.eyeCare);
    this.toggles.notes.classList.toggle('active', settings.notesPanel);
    this.root.classList.toggle('eye-care', settings.eyeCare);
    this.root.classList.toggle('with-notes', settings.notesPanel);
    this.hud.el.hidden = !settings.showHud;
  }

  private togglePalm() {
    settings.penOnly = this.penOnly() ? 'off' : 'on';
    saveSettings();
    this.applyToggles();
    toast(`${t('palmRejection')}: ${t(this.penOnly() ? 'on' : 'off')}`);
  }

  private toggleSpread() {
    const cur = this.currentPage();
    settings.spread = settings.spread === 'dual' ? 'single' : 'dual';
    saveSettings();
    this.applyToggles();
    this.relayout();
    this.fitMode = settings.spread === 'dual' ? 'page' : 'width';
    this.scrollToPage(cur);
    if (this.fitMode === 'page') this.fitPage(); else this.fitWidth();
  }

  private toggleEyeCare() {
    settings.eyeCare = !settings.eyeCare;
    saveSettings();
    this.applyToggles();
    for (const i of this.views.keys()) this.renderInk(i);
  }

  private toggleNotes(on: boolean) {
    settings.notesPanel = on;
    saveSettings();
    this.applyToggles();
    if (on) void this.panel.refresh();
    requestAnimationFrame(() => this.resize());
  }

  private toggleRuler() {
    if (this.ruler.visible) this.ruler.hide();
    else {
      this.ruler.setScale(this.cam.s);
      this.ruler.show(this.vw, this.vh);
      toast(t('rulerHint'));
    }
    this.stage.querySelector('.ruler-btn')?.classList.toggle('active', this.ruler.visible);
  }

  private async toggleRecording() {
    if (this.recorder.recording) {
      clearInterval(this.recTimer);
      this.recBtn.classList.remove('recording');
      this.recBtn.title = t('record');
      const res = await this.recorder.stop();
      if (!res) return;
      const clip = await saveVoiceMemo(this.meta, this.currentPage(), res);
      if (!settings.notesPanel) this.toggleNotes(true);
      void this.panel.refresh(clip.id);
      toast(`${t('voiceMemo')} · ${fmtTime(res.duration)}`);
      return;
    }
    try {
      await this.recorder.start();
    } catch (err) {
      console.warn(err);
      toast(t('micDenied'));
      return;
    }
    this.recBtn.classList.add('recording');
    this.recBtn.title = t('stopRecording');
    const time = this.recBtn.querySelector('.rec-time')!;
    const tick = () => (time.textContent = fmtTime(this.recorder.seconds));
    tick();
    this.recTimer = window.setInterval(tick, 500);
  }

  private setTool(tool: Tool) {
    this.finishEditing();
    this.tool = tool;
    if (tool !== 'lasso') this.clearSelection();
    for (const b of this.dock.querySelectorAll<HTMLButtonElement>('[data-tool]'))
      b.classList.toggle('active', b.dataset.tool === tool);
    this.renderOptions();
    this.optionsBar.classList.toggle('collapsed', !['text', 'eraser', 'clip', 'laser', 'lasso', 'shape'].includes(tool));
  }

  private updateColorDot() {
    const tool = this.tool;
    const c = tool === 'pen' || tool === 'highlighter' || tool === 'shape' ? settings.colors[tool]
      : tool === 'text' ? settings.textColor : tool === 'laser' ? '#f43f5e' : null;
    this.colorDot.style.setProperty('--c', c ?? 'transparent');
    this.colorDot.classList.toggle('none', !c);
  }

  private renderOptions() {
    const bar = this.optionsBar;
    bar.replaceChildren();
    const tool = this.tool;
    this.updateColorDot();
    if (tool === 'pen' || tool === 'highlighter' || tool === 'shape') {
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
      const widths = tool === 'highlighter' ? [8, 14, 22] : [1, 2, 3.5, 6];
      const wg = h('div', { class: 'widths' });
      for (const w of widths) {
        const b = h('button', { class: 'width-btn', 'aria-label': `${w}` });
        const k = tool === 'highlighter' ? 0.8 : 2.5;
        b.append(h('span', { style: `width:${Math.min(22, 3 + w * k)}px;height:${Math.min(22, 3 + w * k)}px;background:${settings.colors[tool]};${tool === 'highlighter' ? 'opacity:.5;border-radius:3px' : ''}` }));
        if (settings.widths[tool] === w) b.classList.add('active');
        b.addEventListener('click', () => { settings.widths[tool] = w; saveSettings(); this.renderOptions(); });
        wg.append(b);
      }
      bar.append(swatches, wg);
      if (tool === 'shape') bar.append(h('span', { class: 'hint' }, t('shapeHint')));
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
    } else if (tool === 'text') {
      const swatches = h('div', { class: 'swatches' });
      for (const c of settings.palette.slice(0, 6)) {
        const sw = h('button', { class: 'swatch', style: `--c:${c}`, 'aria-label': c });
        if (c.toLowerCase() === settings.textColor.toLowerCase()) sw.classList.add('active');
        sw.addEventListener('click', () => { settings.textColor = c; saveSettings(); this.restyleEditing(); this.renderOptions(); });
        swatches.append(sw);
      }
      const sizes = h('div', { class: 'seg' });
      for (const sz of [12, 16, 22, 32]) {
        const b = h('button', { class: settings.textSize === sz ? 'active' : '', style: `font-size:${10 + sz / 4}px` }, 'Aa');
        b.addEventListener('click', () => { settings.textSize = sz; saveSettings(); this.restyleEditing(); this.renderOptions(); });
        sizes.append(b);
      }
      bar.append(swatches, sizes);
      if (this.editing) {
        const del = h('button', { class: 'icon-btn danger', title: t('deleteText'), 'aria-label': t('deleteText') });
        del.innerHTML = icons.trash;
        del.addEventListener('click', () => this.deleteEditing());
        bar.append(del);
      }
      bar.append(h('span', { class: 'hint' }, t('textHint')));
    } else {
      const hint = tool === 'laser' ? 'laserHint' : tool === 'clip' ? 'clipHint' : 'lassoHint';
      bar.append(h('span', { class: 'hint' }, t(hint)));
    }
  }

  private onSettingsChanged() {
    this.renderOptions();
    this.applyToggles();
    for (const i of this.views.keys()) this.renderInk(i);
  }

  // ---------------------------------------------------------------- layout & camera
  private relayout() {
    const dual = settings.spread === 'dual' && this.pages.length > 1;
    // rows of one or two pages; a two-page row reads left → right
    const rows: number[][] = [];
    for (let i = 0; i < this.pages.length; i += dual ? 2 : 1) rows.push(dual ? [i, i + 1].filter((j) => j < this.pages.length) : [i]);
    const rowW = (r: number[]) => r.reduce((sum, i) => sum + this.pages[i].w, 0) + GAP * (r.length - 1);
    this.contentW = Math.max(...rows.map(rowW), 100);
    let y = 0;
    this.layout = [];
    for (const r of rows) {
      let x = (this.contentW - rowW(r)) / 2;
      const rh = Math.max(...r.map((i) => this.pages[i].h));
      for (const i of r) {
        const p = this.pages[i];
        this.layout[i] = { x, y: y + (rh - p.h) / 2, w: p.w, h: p.h };
        x += p.w + GAP;
      }
      y += rh + GAP;
    }
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
    const prevW = this.vw;
    this.vw = r.width;
    this.vh = r.height;
    for (const c of [this.wet, this.pred, this.laser]) {
      c.width = Math.round(r.width * this.dpr);
      c.height = Math.round(r.height * this.dpr);
    }
    if (this.fitMode && Math.abs(r.width - prevW) > 1) return this.fitMode === 'page' ? this.fitPage() : this.fitWidth();
    this.clampCamera();
    this.applyCamera();
    this.keepEditingVisible();
  }

  /** When the on-screen keyboard shrinks the viewport, scroll the text being typed back into view. */
  private keepEditingVisible() {
    if (!this.editing) return;
    const r = this.editing.el.getBoundingClientRect();
    const vr = this.viewport.getBoundingClientRect();
    if (r.bottom > vr.bottom - 12) {
      this.cam.ty -= r.bottom - vr.bottom + 28;
      this.applyCamera();
    }
  }

  private fitScale() {
    return Math.max(0.1, (this.vw - 24) / this.contentW);
  }

  /** Whole page visible at once. */
  fitPage() {
    const i = this.currentPage();
    const l = this.layout[i];
    if (!l) return;
    this.fitMode = 'page';
    // the whole row (both pages in a spread) must fit
    const row = this.layout.filter((o) => o.y < l.y + l.h && o.y + o.h > l.y);
    const top = Math.min(...row.map((o) => o.y)), bottom = Math.max(...row.map((o) => o.y + o.h));
    const left = Math.min(...row.map((o) => o.x)), right = Math.max(...row.map((o) => o.x + o.w));
    const bottomPad = this.dockH + 30;
    this.cam.s = Math.max(0.1, Math.min((this.vw - 24) / (right - left), (this.vh - 24 - bottomPad) / (bottom - top)));
    this.cam.tx = (this.vw - this.contentW * this.cam.s) / 2;
    this.cam.ty = (this.vh - bottomPad - (bottom - top) * this.cam.s) / 2 - top * this.cam.s;
    this.clampCamera();
    this.applyCamera();
  }

  private toggleFit() {
    if (this.fitMode === 'width') this.fitPage();
    else this.fitWidth();
    toast(t(this.fitMode === 'page' ? 'fitPage' : 'fitWidth'));
  }

  fitWidth() {
    const center = this.currentPage();
    this.fitMode = 'width';
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
    const pad = this.dockH + 36;
    const top = 12, bottom = this.vh - ch - pad;
    if (ch + pad + 12 <= this.vh) c.ty = top;
    else c.ty = Math.min(top, Math.max(bottom, c.ty));
  }

  private applyCamera() {
    const { s, tx, ty } = this.cam;
    this.world.style.transform = `translate3d(${tx}px,${ty}px,0) scale(${s})`;
    this.ruler.setScale(s);
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
    const kind = t(this.meta.kind === 'pdf' ? 'kindPdf' : this.meta.kind === 'images' ? 'kindImages' : 'kindNotebook');
    this.docSub.textContent = `${kind} · ${t('page')} ${cur + 1} / ${this.pages.length}`;
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
    const pg = this.pages[i];
    const kind = pg.bg.kind === 'template' ? (pg.bg.paper === 'dark' ? 'paper-dark' : 'paper') : pg.bg.kind;
    const el = h('div', { class: `page ${kind}`, style: `left:${l.x}px;top:${l.y}px;width:${l.w}px;height:${l.h}px` });
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
    } else if (p.bg.kind === 'image') {
      imageBitmapFor(p.bg.fileId, p.bg.mime)
        .then((bmp) => {
          if (job.cancelled) return;
          v.bg.width = Math.round(p.w * sc);
          v.bg.height = Math.round(p.h * sc);
          v.bg.getContext('2d')!.drawImage(bmp, 0, 0, v.bg.width, v.bg.height);
        })
        .catch(() => { if (!job.cancelled) toast(t('fileError')); });
    } else {
      v.bg.width = Math.round(p.w * sc);
      v.bg.height = Math.round(p.h * sc);
      const ctx = v.bg.getContext('2d')!;
      ctx.setTransform(sc, 0, 0, sc, 0, 0);
      drawTemplate(ctx, p.w, p.h, p.bg.template, p.bg.paper, p.bg.rtl);
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
    if (this.editing?.page === i) (exclude ??= new Set()).add(this.editing.tb.id);
    drawPageContent(ctx, p, exclude, true);
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
    return displayStroke(p, s, true);
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
    if ((e.target as HTMLElement).closest?.('textarea')) return;
    if (e.pointerType === 'pen') this.queueHud(e);
    if (this.editing) {
      // a tap outside the text box finishes typing
      this.finishEditing();
      return;
    }
    cancelAnimationFrame(this.fling);
    if (this.reading && e.pointerType !== 'touch') {
      // reading mode: pen and mouse scroll, nothing draws
      this.mode = { kind: 'pan', id: e.pointerId, last: [e.clientX, e.clientY] };
      this.viewport.setPointerCapture(e.pointerId);
      return;
    }
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
      // fingers on the ruler move/rotate it (a second finger anywhere joins in to rotate)
      if (this.ruler.visible && !this.touches.size && (this.rulerTouches.size || this.ruler.hit(...this.vpPoint(e)))) {
        if (performance.now() - this.lastPenContact < 300) return;
        const [x, y] = this.vpPoint(e);
        this.rulerTouches.set(e.pointerId, { x, y });
        this.viewport.setPointerCapture(e.pointerId);
        this.ruler.begin([...this.rulerTouches.values()]);
        return;
      }
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

      if (!this.penOnly() && !this.reading && this.touches.size === 1) {
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

    if (tool === 'pen' || tool === 'highlighter' || tool === 'shape') {
      this.clearSelection();
      const shape = tool === 'shape';
      const ink: InkTool = shape ? 'pen' : tool;
      const p = shape ? 0.5 : this.pressure(e, 0.5);
      const vr = this.viewport.getBoundingClientRect();
      const width = settings.widths[tool];
      // a stroke that starts against a ruler edge follows that edge
      const edge = shape ? null : this.ruler.edgeNear(e.clientX - vr.left, e.clientY - vr.top);
      this.mode = {
        kind: 'ink', id: e.pointerId, page, tool: ink, pen: e.pointerType === 'pen', shape, edge, vr,
        color: this.display(this.pages[page], { color: settings.colors[tool] } as Stroke).color,
        width, pts: [], settled: 1, lastP: p, tilt: 0, tiltN: 0,
      };
      const [sx, sy] = this.inkPoint(e, this.mode);
      this.mode.pts.push(sx, sy, p);
      this.trackTilt(e, this.mode);
      this.drawWet([]);
      if (!shape && !edge) this.armHold(e, page, x, y);
    } else if (tool === 'laser') {
      this.clearSelection();
      this.mode = { kind: 'laser', id: e.pointerId };
      this.addLaser(e, true);
    } else if (tool === 'clip') {
      this.clearSelection();
      this.mode = { kind: 'clip', id: e.pointerId, page, pts: [x, y, 0.5] };
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
    } else if (tool === 'text') {
      this.clearSelection();
      const texts = this.pages[page].texts ?? [];
      const hit = [...texts].reverse().find((tb) => textBoxHit(tb, x, y, 6 / this.cam.s)) ?? null;
      this.mode = { kind: 'text', id: e.pointerId, page, start: [x, y], hit, d: [0, 0], moved: false };
    }
  }

  private pressure(e: PointerEvent, prev: number) {
    if (e.pointerType !== 'pen') return 0.5;
    const raw = Math.pow(Math.max(0.05, Math.min(1, e.pressure || 0.05)), settings.pressureCurve);
    return prev * 0.35 + raw * 0.65;
  }

  private vpPoint(e: { clientX: number; clientY: number }): [number, number] {
    const r = this.viewport.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top];
  }

  /** Page point for an ink event; snapped onto the ruler edge when drawing along it. */
  private inkPoint(e: { clientX: number; clientY: number }, m: Extract<Mode, { kind: 'ink' }>): [number, number] {
    if (!m.edge) return this.local(e, m.page);
    const off = (m.width * this.cam.s) / 2 + 1;
    const [sx, sy] = this.ruler.project(e.clientX - m.vr.left, e.clientY - m.vr.top, m.edge, off);
    return this.local({ clientX: sx + m.vr.left, clientY: sy + m.vr.top }, m.page);
  }

  private static tiltOf(e: PointerEvent) {
    const alt = (e as PointerEvent & { altitudeAngle?: number }).altitudeAngle;
    if (typeof alt === 'number' && alt > 0 && alt < Math.PI / 2 - 1e-3) return 90 - (alt * 180) / Math.PI;
    return Math.max(Math.abs(e.tiltX || 0), Math.abs(e.tiltY || 0));
  }

  private trackTilt(e: PointerEvent, m: Extract<Mode, { kind: 'ink' }>) {
    if (e.pointerType !== 'pen') return;
    m.tilt += Editor.tiltOf(e);
    m.tiltN++;
  }

  /** Live pen readout in the header (batched to one update per frame). */
  private queueHud(e: PointerEvent) {
    if (!settings.showHud) return;
    const first = !this.hudPending;
    this.hudPending = e;
    if (!first) return;
    requestAnimationFrame(() => {
      const ev = this.hudPending;
      this.hudPending = null;
      if (!ev) return;
      const p = ev.buttons ? Math.max(0, Math.min(1, ev.pressure)) : 0;
      this.hud.bar.style.width = `${Math.round(p * 100)}%`;
      this.hud.val.textContent = ev.buttons ? `${Math.round(p * 4096)}` : '—';
      this.hud.tilt.textContent = `${Math.round(Editor.tiltOf(ev))}°`;
      const btn = this.buttonAction(ev);
      this.hud.btn.classList.toggle('on', !!btn);
      this.hud.btn.textContent = btn ? t(btn === 'none' ? 'none' : btn) : '';
      this.hud.el.classList.add('live');
    });
  }

  // ---------------------------------------------------------------- laser pointer
  private addLaser(e: PointerEvent, gap = false) {
    const list = e.getCoalescedEvents?.() ?? [];
    for (const ce of list.length ? list : [e]) {
      const [x, y] = this.vpPoint(ce);
      this.trail.push({ x, y, t: performance.now(), gap });
      gap = false;
    }
    if (!this.laserLoop) this.laserLoop = requestAnimationFrame(() => this.drawLaser());
  }

  private drawLaser() {
    const LIFE = 700;
    const now = performance.now();
    const holding = this.mode.kind === 'laser';
    this.trail = this.trail.filter((p) => now - p.t < LIFE);
    const ctx = this.lctx;
    this.clearCanvas(ctx);
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const [w, color, blur] of [[7, 'rgba(244,63,94,', 14], [2.4, 'rgba(255,241,242,', 0]] as const) {
      ctx.shadowColor = '#f43f5e';
      ctx.shadowBlur = blur;
      ctx.lineWidth = w;
      for (let i = 1; i < this.trail.length; i++) {
        const a = this.trail[i - 1], b = this.trail[i];
        if (b.gap) continue;
        ctx.strokeStyle = `${color}${Math.max(0, 1 - (now - b.t) / LIFE).toFixed(3)})`;
        ctx.beginPath();
        ctx.moveTo(a.x, a.y);
        ctx.lineTo(b.x, b.y);
        ctx.stroke();
      }
    }
    const last = this.trail[this.trail.length - 1];
    if (holding && last) {
      ctx.shadowBlur = 18;
      ctx.fillStyle = '#ff4d6d';
      ctx.beginPath();
      ctx.arc(last.x, last.y, 5, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.shadowBlur = 0;
    this.laserLoop = this.trail.length || holding ? requestAnimationFrame(() => this.drawLaser()) : 0;
  }

  /** pointerrawupdate: fires without waiting for the next frame → lowest latency for ink */
  private onRaw(e: PointerEvent) {
    if (e.pointerType === 'pen') this.queueHud(e);
    const m = this.mode;
    if (m.kind === 'ink' && m.id === e.pointerId) this.extendInk(e);
  }

  private onMove(e: PointerEvent) {
    if (this.rulerTouches.has(e.pointerId)) {
      this.rulerTouches.set(e.pointerId, { x: this.vpPoint(e)[0], y: this.vpPoint(e)[1] });
      this.ruler.update([...this.rulerTouches.values()]);
      return;
    }
    if (e.pointerType === 'pen' && !('onpointerrawupdate' in window)) this.queueHud(e);
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
    if (m.kind === 'laser') return this.addLaser(e);
    const events = e.getCoalescedEvents?.() ?? [e];
    if (m.kind === 'clip') {
      for (const ce of events.length ? events : [e]) {
        const [x, y] = this.local(ce, m.page);
        m.pts.push(x, y, 0.5);
      }
      return this.drawClipGesture(m.page, m.pts);
    }
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
    } else if (m.kind === 'text') {
      const [x, y] = this.local(e, m.page);
      m.d = [x - m.start[0], y - m.start[1]];
      if (m.hit && !m.moved && Math.hypot(m.d[0], m.d[1]) * this.cam.s > 6) {
        m.moved = true;
        this.renderInk(m.page, new Set([m.hit.id]));
      }
      if (m.hit && m.moved) this.drawMovingText(m.page, m.hit, m.d);
    }
  }

  private drawMovingText(page: number, tb: TextBox, d: [number, number]) {
    const ctx = this.wctx;
    this.clearCanvas(ctx);
    this.wetTransform(ctx, page);
    const moved = { ...tb, x: tb.x + d[0], y: tb.y + d[1] };
    drawBubble(ctx, moved);
    drawTextBox(ctx, moved, tb.bubble ? tb.color : inkColorForPaper(this.pages[page].bg, tb.color));
    ctx.setLineDash([5 / this.cam.s, 4 / this.cam.s]);
    ctx.lineWidth = 1.2 / this.cam.s;
    ctx.strokeStyle = '#2156d1';
    if (!tb.bubble) ctx.strokeRect(moved.x - 3, moved.y - 2, moved.w + 6, boxHeight(tb) + 4);
    ctx.setLineDash([]);
  }

  // ---------------------------------------------------------------- hold → note bubble
  private armHold(e: PointerEvent, page: number, x: number, y: number) {
    this.clearHold();
    if (!settings.holdBubble) return;
    this.hold = {
      id: e.pointerId, cx: e.clientX, cy: e.clientY,
      timer: window.setTimeout(() => this.fireHold(page, x, y), 2000),
    };
  }

  private clearHold() {
    if (this.hold) clearTimeout(this.hold.timer);
    this.hold = null;
  }

  /** The pen rested on one spot for two seconds: drop the dot it drew and open a note bubble there. */
  private fireHold(page: number, x: number, y: number) {
    this.hold = null;
    if (this.mode.kind !== 'ink') return;
    this.cancelMode();
    navigator.vibrate?.(20);
    const p = this.pages[page];
    const l = this.layout[page];
    const { s, tx, ty } = this.cam;
    const visible: [number, number, number, number] = [-tx / s - l.x, -ty / s - l.y, (this.vw - tx) / s - l.x, (this.vh - this.dockH - 40 - ty) / s - l.y];
    // try a wide bubble and a narrow one that fits page margins; keep whichever lands closer and freer
    const bg = this.views.get(page)?.bg;
    const fits = ([[150, 62], [104, 96]] as const).map(([w, hh]) => ({
      w, hh, spot: findFreeSpot(p, bg, x, y, w + BUBBLE_PAD * 2, hh + BUBBLE_PAD * 2, visible),
    }));
    const { w: W, hh: H, spot } = fits.reduce((a, b) => (b.spot.score < a.spot.score - 25 ? b : a));
    const tb: TextBox = {
      id: uid(), x: spot.x + BUBBLE_PAD, y: spot.y + BUBBLE_PAD, w: W, text: '', size: 12, color: '#1a1a2e',
      bubble: { ax: x, ay: y, h: H },
    };
    this.commitTexts(page, [...(p.texts ?? []), tb]);
    // the only free spot may be off screen: bring the bubble into view
    const top = (l.y + spot.y) * s + ty, bottom = (l.y + spot.y + H + BUBBLE_PAD * 2) * s + ty;
    if (bottom > this.vh - this.dockH - 40) this.cam.ty -= bottom - (this.vh - this.dockH - 40);
    else if (top < 8) this.cam.ty += 8 - top;
    this.clampCamera();
    this.applyCamera();
    toast(t('bubbleHint'));
  }

  private extendInk(e: PointerEvent) {
    const m = this.mode;
    if (m.kind !== 'ink') return;
    // a resting pen still jitters by a few pixels; real movement cancels the hold
    if (this.hold && Math.hypot(e.clientX - this.hold.cx, e.clientY - this.hold.cy) > 8) this.clearHold();
    const last = this.lastProcessedTs.get(e.pointerId) ?? 0;
    const events = e.getCoalescedEvents?.() ?? [];
    const list = events.length ? events : [e];
    const minDist = 0.35 / this.cam.s;
    for (const ce of list) {
      if (ce.timeStamp <= last && list.length > 1) continue;
      const [x, y] = this.inkPoint(ce, m);
      this.trackTilt(ce, m);
      const n = m.pts.length;
      const p = m.shape ? 0.5 : this.pressure(ce, m.lastP);
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
      const [x, y] = this.inkPoint(pe, m);
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
    if (this.reading) return;
    const a = this.buttonAction(e);
    const tool = a && a !== 'none' ? a : this.tool;
    if (tool === 'eraser') this.drawEraserCursor(e.clientX, e.clientY);
    else if (tool === 'laser') {
      const [x, y] = this.vpPoint(e);
      const ctx = this.pctx;
      this.clearCanvas(ctx);
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.shadowColor = '#f43f5e';
      ctx.shadowBlur = 10;
      ctx.fillStyle = 'rgba(244,63,94,.55)';
      ctx.beginPath();
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;
    } else this.clearPred();
  }

  private drawClipGesture(page: number, pts: number[]) {
    const ctx = this.wctx;
    this.clearCanvas(ctx);
    this.wetTransform(ctx, page);
    ctx.beginPath();
    ctx.moveTo(pts[0], pts[1]);
    for (let i = 3; i < pts.length; i += 3) ctx.lineTo(pts[i], pts[i + 1]);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.shadowColor = '#06b6d4';
    ctx.shadowBlur = 8;
    ctx.setLineDash([7 / this.cam.s, 4 / this.cam.s]);
    ctx.lineWidth = 2.6 / this.cam.s;
    ctx.strokeStyle = '#06b6d4';
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.shadowBlur = 0;
  }

  private async finishClip(page: number, pts: number[]) {
    try {
      const clip = await clipFromStroke(this.meta, this.pages, page, pts);
      this.clearCanvas(this.wctx);
      if (!clip) return;
      this.afterClip(clip.id, page, clip.rect);
    } catch (err) {
      console.error(err);
      this.clearCanvas(this.wctx);
      toast(String(err));
    }
  }

  private afterClip(id: string, page: number, rect?: [number, number, number, number]) {
    if (rect) this.flashRect(page, rect);
    if (!settings.notesPanel) this.toggleNotes(true);
    void this.panel.refresh(id);
    toast(`${t('clipped')} ${page + 1}`);
  }

  /** Briefly outlines a region of a page (new clip, jump target). */
  private flashRect(page: number, r: [number, number, number, number]) {
    const v = this.views.get(page);
    if (!v) return;
    const el = h('div', { class: 'flash-rect', style: `left:${r[0]}px;top:${r[1]}px;width:${r[2] - r[0]}px;height:${r[3] - r[1]}px` });
    v.el.append(el);
    setTimeout(() => el.remove(), 2400);
  }

  private scrollToPage(i: number, rect?: [number, number, number, number]) {
    const l = this.layout[i];
    if (!l) return;
    if (rect) this.cam.ty = this.vh * 0.38 - (l.y + (rect[1] + rect[3]) / 2) * this.cam.s;
    else this.cam.ty = 12 - l.y * this.cam.s;
    this.clampCamera();
    this.applyCamera();
  }

  jumpTo(page: number, rect?: [number, number, number, number]) {
    if (!this.layout[page]) return;
    this.scrollToPage(page, rect);
    if (rect) requestAnimationFrame(() => requestAnimationFrame(() => this.flashRect(page, rect)));
  }

  private async openOther(docId: string, page: number) {
    if (!this.openDocAt) return;
    const open = this.openDocAt;
    await this.close();
    open(docId, page);
  }

  private goPage(dir: 1 | -1) {
    const cur = this.currentPage();
    const step = settings.spread === 'dual' ? 2 : 1;
    const next = Math.max(0, Math.min(this.pages.length - 1, cur + dir * step));
    if (this.fitMode === 'page') {
      this.scrollToPage(next);
      this.fitPage();
    } else this.scrollToPage(next);
  }

  private zoomBy(f: number) {
    const cx = this.vw / 2, cy = this.vh / 2;
    const wx = (cx - this.cam.tx) / this.cam.s, wy = (cy - this.cam.ty) / this.cam.s;
    this.fitMode = null;
    this.cam.s = Math.min(MAX_SCALE, Math.max(this.fitScale() * 0.4, this.cam.s * f));
    this.cam.tx = cx - wx * this.cam.s;
    this.cam.ty = cy - wy * this.cam.s;
    this.clampCamera();
    this.applyCamera();
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
      this.fitMode = null;
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
    if (this.rulerTouches.delete(e.pointerId)) {
      this.ruler.rebase([...this.rulerTouches.values()]);
      return;
    }
    if (this.hold?.id === e.pointerId) this.clearHold();
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
      case 'laser': return;
      case 'clip': return void this.finishClip(m.page, m.pts);
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
      case 'text': {
        this.clearCanvas(this.wctx);
        const texts = this.pages[m.page].texts ?? [];
        if (m.hit && m.moved) {
          const hit = m.hit;
          this.commitTexts(m.page, texts.map((tb) => (tb.id === hit.id ? { ...tb, x: tb.x + m.d[0], y: tb.y + m.d[1] } : tb)));
        } else if (m.hit) this.editText(m.page, m.hit, false);
        else this.editText(m.page, this.newTextBox(m.page, m.start[0], m.start[1]), true);
        return;
      }
      case 'pan':
        return;
    }
  }

  // ---------------------------------------------------------------- typed text
  private newTextBox(page: number, x: number, y: number): TextBox {
    const p = this.pages[page];
    const size = settings.textSize;
    const rtl = settings.lang === 'ar';
    // Arabic: the box grows leftwards from where you tapped
    const w = Math.max(140, Math.min(400, rtl ? x - 16 : p.w - x - 16));
    const bx = Math.max(4, Math.min(p.w - w - 4, rtl ? x - w : x));
    return { id: uid(), x: bx, y: y - (size * LINE_HEIGHT) / 2, w, text: '', size, color: settings.textColor };
  }

  private editText(page: number, tb: TextBox, isNew: boolean) {
    const v = this.views.get(page);
    if (!v) return;
    const ta = h('textarea', { class: 'text-edit', dir: 'auto', rows: 1, spellcheck: 'false', autocapitalize: 'sentences' });
    ta.value = tb.text;
    if (!tb.text && settings.lang === 'ar') ta.dir = 'rtl';
    ta.addEventListener('input', () => {
      if (ta.value) ta.dir = isRtl(ta.value) ? 'rtl' : 'ltr';
      this.growEditing();
    });
    ta.addEventListener('blur', () => this.finishEditing());
    ta.addEventListener('keydown', (e) => { if (e.key === 'Escape') ta.blur(); });
    v.el.append(ta);
    this.editing = { page, tb, orig: isNew ? null : tb, el: ta };
    if (tb.bubble) {
      ta.classList.add('bubble-edit');
      ta.style.minHeight = `${tb.bubble.h}px`;
    }
    this.restyleEditing(false);
    this.renderInk(page);
    if (this.tool === 'text') {
      this.renderOptions();
      this.optionsBar.classList.remove('collapsed');
    }
    ta.focus({ preventScroll: true });
    ta.setSelectionRange(ta.value.length, ta.value.length);
    this.growEditing();
  }

  private growEditing() {
    const ta = this.editing?.el;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${ta.scrollHeight}px`;
    this.keepEditingVisible();
  }

  /** Apply the current size/colour from the options bar to the box being typed. */
  private restyleEditing(fromOptions = true) {
    const ed = this.editing;
    if (!ed) return;
    if (fromOptions) ed.tb = { ...ed.tb, size: settings.textSize, color: settings.textColor };
    const tb = ed.tb;
    Object.assign(ed.el.style, {
      left: `${tb.x}px`, top: `${tb.y}px`, width: `${tb.w}px`,
      font: fontOf(tb.size, tb.bold), lineHeight: String(LINE_HEIGHT),
      color: tb.bubble ? tb.color : inkColorForPaper(this.pages[ed.page].bg, tb.color),
    });
    this.growEditing();
  }

  private finishEditing() {
    const ed = this.editing;
    if (!ed) return;
    this.editing = null;
    const text = ed.el.value.replace(/\s+$/, '');
    ed.el.remove();
    const p = this.pages[ed.page];
    const before = p.texts ?? [];
    const next: TextBox = { ...ed.tb, text };
    let after = before;
    // an empty bubble stays (it may hold handwriting); an empty plain text box is removed
    if (!text && !ed.tb.bubble) {
      if (ed.orig) after = before.filter((tb) => tb.id !== ed.tb.id);
    } else if (!ed.orig) after = [...before, next];
    else if (JSON.stringify(ed.orig) !== JSON.stringify(next)) after = before.map((tb) => (tb.id === next.id ? next : tb));
    if (after !== before) this.commitTexts(ed.page, after);
    else this.renderInk(ed.page);
    if (this.tool === 'text') this.renderOptions();
  }

  private deleteEditing() {
    const ed = this.editing;
    if (!ed) return;
    ed.el.value = '';
    ed.tb = { ...ed.tb, bubble: undefined };
    this.finishEditing();
  }

  private commitTexts(page: number, after: TextBox[]) {
    const p = this.pages[page];
    const before = p.texts ?? [];
    p.texts = after;
    this.renderInk(page);
    this.pushHistory({ pageId: p.id, before: p.strokes, after: p.strokes, tBefore: before, tAfter: after });
    this.markDirty(p.id);
  }

  private cancelMode() {
    this.clearHold();
    const m = this.mode;
    if (m.kind === 'laser') {
      this.mode = { kind: 'idle' };
      return;
    }
    this.mode = { kind: 'idle' };
    this.clearCanvas(this.wctx);
    this.clearPred();
    if (m.kind === 'erase' && m.changed) {
      this.pages[m.page].strokes = m.before;
      this.renderInk(m.page);
    }
    if (m.kind === 'move' || m.kind === 'text') this.renderInk(m.page);
  }

  private commitInk(m: Extract<Mode, { kind: 'ink' }>) {
    const page = this.pages[m.page];
    let pts = m.pts;
    let width = m.width;
    if (m.shape) {
      pts = recognizeShape(m.pts)?.pts ?? m.pts;
    } else if (!m.edge && settings.straighten) {
      // highlights and underlines snap to a clean straight line
      pts = straightenStroke(m.pts, m.tool) ?? m.pts;
    }
    // chisel marker: a tilted pen lays down a wider highlight
    if (m.tool === 'highlighter' && settings.tiltHighlighter && m.tiltN) width *= 1 + (Math.min(60, m.tilt / m.tiltN) / 90) * 0.85;
    const stroke = makeStroke(m.tool, settings.colors[m.shape ? 'shape' : m.tool], width, pts);
    const before = page.strokes;
    const after = [...before, stroke];
    page.strokes = after;
    // draw just this stroke onto the page; highlighters need a full redraw to stay under ink
    const v = this.views.get(m.page);
    if (v && m.tool === 'pen' && pts === m.pts) {
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
      mk(icons.toText, t('toText'), () => void this.selectionToText()),
      mk(icons.clip, t('clip'), () => void this.selectionToClip()),
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

  /** Handwriting → typed text (on-device recognition in the Android app). */
  private async selectionToText() {
    const sel = this.selection;
    if (!sel) return;
    const page = sel.page;
    const p = this.pages[page];
    const strokes = p.strokes.filter((s) => sel.ids.has(s.id) && s.tool === 'pen');
    const bb = unionBBox(strokes);
    if (!bb) return;
    const res = await convertHandwriting(strokes);
    if (!res || this.pages[page] !== p) return;
    const w = Math.max(160, Math.min(bb[2] - bb[0] + 24, p.w - 8));
    const x = Math.max(4, Math.min(p.w - w - 4, isRtl(res.text) ? bb[2] - w : bb[0]));
    const tb: TextBox = { id: uid(), x, y: bb[1], w, text: res.text, size: res.size, color: strokes[0].color };
    const before = p.strokes, tBefore = p.texts ?? [];
    const ids = new Set(strokes.map((s) => s.id));
    const after = res.replace ? before.filter((s) => !ids.has(s.id)) : before;
    const tAfter = [...tBefore, tb];
    p.strokes = after;
    p.texts = tAfter;
    this.clearSelection();
    this.renderInk(page);
    this.pushHistory({ pageId: p.id, before, after, tBefore, tAfter });
    this.markDirty(p.id);
  }

  private async selectionToClip() {
    const sel = this.selection;
    if (!sel) return;
    const bb = unionBBox(this.pages[sel.page].strokes.filter((s) => sel.ids.has(s.id)));
    if (!bb) return;
    const page = sel.page;
    this.clearSelection();
    const clip = await clipFromRect(this.meta, this.pages, page, [bb[0] - 10, bb[1] - 10, bb[2] + 10, bb[3] + 10]);
    this.afterClip(clip.id, page, clip.rect);
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
    this.finishEditing();
    const e = from.pop();
    if (!e) return;
    const i = this.pages.findIndex((p) => p.id === e.pageId);
    if (i < 0) return this.step(from, to, which);
    this.pages[i].strokes = e[which];
    const texts = which === 'before' ? e.tBefore : e.tAfter;
    if (texts) this.pages[i].texts = texts;
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
      bg: { kind: 'template', template: this.meta.template, paper: this.meta.paper, rtl: settings.lang === 'ar' },
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
    if (removed.bg.kind === 'image') {
      const fid = removed.bg.fileId;
      this.meta.fileIds = (this.meta.fileIds ?? []).filter((f) => f !== fid);
      await deleteFile(fid);
    }
    await this.flush();
  }

  private async share() {
    this.finishEditing();
    await this.flush();
    openShareDialog(this.meta, this.pages, this.currentPage());
  }

  // ---------------------------------------------------------------- misc input
  private onWheel(e: WheelEvent) {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) {
      const r = this.viewport.getBoundingClientRect();
      const [wx, wy] = this.screenToWorld(e.clientX, e.clientY);
      this.fitMode = null;
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
      const map: Record<string, Tool> = { p: 'pen', h: 'highlighter', s: 'shape', k: 'laser', e: 'eraser', l: 'lasso', c: 'clip', t: 'text' };
      if (map[e.key] && !this.reading) this.setTool(map[e.key]);
      if ((e.key === 'Delete' || e.key === 'Backspace') && this.selection) this.selectionDelete();
    }
  }

  // ---------------------------------------------------------------- close
  private async makeThumb(): Promise<string | undefined> {
    const p = this.pages[0];
    if (!p) return;
    return (await renderPageToCanvas(this.meta, p, 240 / p.w)).toDataURL('image/jpeg', 0.75);
  }

  async close() {
    if (this.closed) return;
    this.closed = true;
    this.finishEditing();
    if (this.recorder.recording) await this.toggleRecording();
    cancelAnimationFrame(this.laserLoop);
    this.panel.dispose();
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
    clearImageCache();
    this.root.remove();
    this.onClose();
  }
}
