import { putDoc, putPages, putFile } from './db';
import { detectPage, defaultQuad, outputSize, warp, documentFilter, colorFilter, type Quad, type Pt } from './scanMath';
import { canvasToBytes, renderPageToCanvas } from './render';
import { A4, uid, type DocMeta, type PageData } from './types';
import { icons } from './icons';
import { t } from './i18n';
import { h, toast } from './ui';

type Filter = 'document' | 'color' | 'original';

interface ScanPage {
  id: string;
  /** the photo, scaled to at most MAX_SRC on its long side */
  src: HTMLCanvasElement;
  quad: Quad;
  filter: Filter;
  /** quarter turns clockwise */
  rot: number;
  /** processed result, made when needed */
  result: HTMLCanvasElement | null;
}

const MAX_SRC = 3000;

/**
 * Scanner (like a document-scanner app): photograph pages, the page is found and straightened, shadows are
 * removed, and the pages become one document (exportable as PDF).
 */
export class ScanView {
  root = h('dialog', { class: 'scan-view' });
  private pages: ScanPage[] = [];
  private cur = -1;
  private stage = h('div', { class: 'scan-stage' });
  private strip = h('div', { class: 'scan-strip' });
  private tools = h('div', { class: 'scan-tools' });
  private saveBtn = h('button', { type: 'button', class: 'btn primary' });
  private mode: 'edges' | 'result' = 'edges';

  constructor(private onDone: (doc: DocMeta) => void) {
    const close = h('button', { type: 'button', class: 'icon-btn', title: t('close'), 'aria-label': t('close') });
    close.innerHTML = icons.close;
    close.addEventListener('click', () => this.close());
    this.saveBtn.addEventListener('click', () => void this.save());
    this.root.append(
      h('header', { class: 'pv-head' }, h('h2', {}, t('scanTitle')), h('div', { class: 'spacer' }), this.saveBtn, close),
      this.stage, this.tools, this.strip,
    );
    this.root.addEventListener('close', () => this.root.remove());
  }

  open() {
    document.body.append(this.root);
    this.root.showModal();
    this.render();
  }

  private close() {
    if (this.pages.length && !confirm(t('scanDiscard'))) return;
    this.root.close();
  }

  // ---------------------------------------------------------------- adding photos

  private pick(camera: boolean) {
    const input = h('input', { type: 'file', accept: 'image/*', hidden: true, multiple: !camera, ...(camera ? { capture: 'environment' } : {}) });
    input.addEventListener('change', async () => {
      const files = [...(input.files ?? [])];
      input.remove();
      for (const f of files) {
        try {
          await this.add(f);
        } catch (e) {
          console.error(e);
          toast(`${t('fileError')}: ${f.name}`);
        }
      }
      this.render();
      // straight on to the next photo when using the camera
    });
    this.root.append(input);
    input.click();
  }

  private async add(f: Blob) {
    const bmp = await createImageBitmap(f);
    const k = Math.min(1, MAX_SRC / Math.max(bmp.width, bmp.height));
    const src = document.createElement('canvas');
    src.width = Math.round(bmp.width * k);
    src.height = Math.round(bmp.height * k);
    src.getContext('2d')!.drawImage(bmp, 0, 0, src.width, src.height);
    bmp.close();
    const quad = findPage(src) ?? defaultQuad(src.width, src.height);
    this.pages.push({ id: uid(), src, quad, filter: 'document', rot: 0, result: null });
    this.cur = this.pages.length - 1;
    this.mode = 'edges';
  }

  // ---------------------------------------------------------------- screens

  private render() {
    this.saveBtn.textContent = `${t('scanSave')}${this.pages.length ? ` (${this.pages.length})` : ''}`;
    this.saveBtn.disabled = !this.pages.length;
    this.renderStrip();
    this.renderTools();
    this.stage.replaceChildren();
    const p = this.pages[this.cur];
    if (!p) {
      const cam = h('button', { type: 'button', class: 'scan-big primary' });
      cam.innerHTML = icons.camera;
      cam.append(h('span', {}, t('scanCamera')));
      cam.addEventListener('click', () => this.pick(true));
      const gal = h('button', { type: 'button', class: 'scan-big' });
      gal.innerHTML = icons.image;
      gal.append(h('span', {}, t('scanGallery')));
      gal.addEventListener('click', () => this.pick(false));
      this.stage.append(h('div', { class: 'scan-empty' }, h('p', { class: 'hint' }, t('scanHint')), h('div', { class: 'scan-big-row' }, cam, gal)));
      return;
    }
    if (this.mode === 'edges') this.renderEdges(p);
    else this.renderResult(p);
  }

  private renderTools() {
    this.tools.replaceChildren();
    const p = this.pages[this.cur];
    if (!p) return;
    const seg = h('div', { class: 'seg' });
    for (const [v, k] of [['edges', 'scanEdges'], ['result', 'scanResult']] as const) {
      const b = h('button', { type: 'button', class: this.mode === v ? 'active' : '' }, t(k));
      b.addEventListener('click', () => { this.mode = v; this.render(); });
      seg.append(b);
    }
    const filters = h('div', { class: 'seg' });
    for (const [v, k] of [['document', 'scanFilterDoc'], ['color', 'scanFilterColor'], ['original', 'scanFilterOriginal']] as const) {
      const b = h('button', { type: 'button', class: p.filter === v ? 'active' : '' }, t(k));
      b.addEventListener('click', () => { p.filter = v; p.result = null; this.mode = 'result'; this.render(); });
      filters.append(b);
    }
    const btn = (icon: string, label: string, fn: () => void, cls = '') => {
      const b = h('button', { type: 'button', class: `icon-btn ${cls}`, title: label, 'aria-label': label });
      b.innerHTML = icon;
      b.addEventListener('click', fn);
      return b;
    };
    this.tools.append(seg, filters,
      btn(icons.rotate, t('scanRotate'), () => { p.rot = (p.rot + 1) % 4; p.result = null; this.render(); }),
      btn(icons.fit, t('scanAuto'), () => { p.quad = findPage(p.src) ?? defaultQuad(p.src.width, p.src.height); p.result = null; this.mode = 'edges'; this.render(); }),
      btn(icons.trash, t('scanDelete'), () => {
        this.pages.splice(this.cur, 1);
        this.cur = Math.min(this.cur, this.pages.length - 1);
        this.render();
      }, 'danger'));
  }

  private renderStrip() {
    this.strip.replaceChildren();
    this.pages.forEach((p, i) => {
      const c = h('button', { type: 'button', class: `scan-thumb ${i === this.cur ? 'active' : ''}` }, h('span', {}, String(i + 1)));
      void this.result(p).then((r) => {
        const img = h('img', { alt: '' });
        img.src = r.toDataURL('image/jpeg', 0.6);
        c.prepend(img);
      });
      c.addEventListener('click', () => { this.cur = i; this.render(); });
      this.strip.append(c);
    });
    for (const [icon, label, cam] of [[icons.camera, t('scanCamera'), true], [icons.image, t('scanGallery'), false]] as const) {
      const b = h('button', { type: 'button', class: 'scan-add' });
      b.innerHTML = icon;
      b.append(h('span', {}, label));
      b.addEventListener('click', () => this.pick(cam));
      this.strip.append(b);
    }
  }

  /** The photo with the page's four corners: drag them onto the page's real corners. */
  private renderEdges(p: ScanPage) {
    const wrap = h('div', { class: 'scan-photo' });
    const view = document.createElement('canvas');
    const overlay = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    overlay.classList.add('scan-quad');
    wrap.append(view, overlay);
    this.stage.append(wrap);
    requestAnimationFrame(() => {
      const box = this.stage.getBoundingClientRect();
      const k = Math.min((box.width - 40) / p.src.width, (box.height - 40) / p.src.height);
      const w = Math.round(p.src.width * k), hh = Math.round(p.src.height * k);
      const dpr = window.devicePixelRatio || 1;
      view.width = w * dpr; view.height = hh * dpr;
      Object.assign(view.style, { width: `${w}px`, height: `${hh}px` });
      view.getContext('2d')!.drawImage(p.src, 0, 0, view.width, view.height);
      Object.assign(wrap.style, { width: `${w}px`, height: `${hh}px` });
      overlay.setAttribute('viewBox', `0 0 ${w} ${hh}`);
      const poly = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
      overlay.append(poly);
      const handles = p.quad.map((_, i) => {
        const el = h('div', { class: 'scan-handle', role: 'slider', 'aria-label': `${t('scanCorner')} ${i + 1}` });
        wrap.append(el);
        return el;
      });
      const place = () => {
        poly.setAttribute('points', p.quad.map(([x, y]) => `${x * k},${y * k}`).join(' '));
        p.quad.forEach(([x, y], i) => Object.assign(handles[i].style, { left: `${x * k}px`, top: `${y * k}px` }));
      };
      place();
      handles.forEach((el, i) => {
        el.addEventListener('pointerdown', (e) => {
          e.preventDefault();
          el.setPointerCapture(e.pointerId);
          el.classList.add('drag');
          const r = view.getBoundingClientRect();
          const move = (ev: PointerEvent) => {
            const x = Math.max(0, Math.min(p.src.width, (ev.clientX - r.left) / k));
            const y = Math.max(0, Math.min(p.src.height, (ev.clientY - r.top) / k));
            p.quad[i] = [x, y] as Pt;
            p.result = null;
            place();
          };
          const up = () => {
            el.classList.remove('drag');
            el.removeEventListener('pointermove', move);
            el.removeEventListener('pointerup', up);
            el.removeEventListener('pointercancel', up);
            this.renderStrip();
          };
          el.addEventListener('pointermove', move);
          el.addEventListener('pointerup', up);
          el.addEventListener('pointercancel', up);
        });
      });
    });
  }

  private renderResult(p: ScanPage) {
    const holder = h('div', { class: 'scan-result' }, h('p', { class: 'hint' }, t('loading')));
    this.stage.append(holder);
    void this.result(p).then((r) => {
      const img = h('img', { alt: '' });
      img.src = r.toDataURL('image/jpeg', 0.85);
      holder.replaceChildren(img);
    });
  }

  // ---------------------------------------------------------------- processing

  private async result(p: ScanPage): Promise<HTMLCanvasElement> {
    if (p.result) return p.result;
    await new Promise((r) => setTimeout(r, 0)); // let the screen update first
    const { w, h: hh } = outputSize(p.quad);
    const sctx = p.src.getContext('2d')!;
    const src = sctx.getImageData(0, 0, p.src.width, p.src.height).data;
    const px = warp(src, p.src.width, p.src.height, p.quad, w, hh);
    if (p.filter === 'document') documentFilter(px, w, hh);
    else if (p.filter === 'color') colorFilter(px, w, hh);
    const flat = document.createElement('canvas');
    flat.width = w; flat.height = hh;
    flat.getContext('2d')!.putImageData(new ImageData(px, w, hh), 0, 0);
    const turned = p.rot % 2 === 1;
    const out = document.createElement('canvas');
    out.width = turned ? hh : w;
    out.height = turned ? w : hh;
    const octx = out.getContext('2d')!;
    octx.translate(out.width / 2, out.height / 2);
    octx.rotate((p.rot * Math.PI) / 2);
    octx.drawImage(flat, -w / 2, -hh / 2);
    flat.width = 0;
    p.result = out;
    return out;
  }

  private async save() {
    if (!this.pages.length) return;
    const d = new Date();
    const title = prompt(t('title'), `${t('scanTitle')} ${d.toLocaleDateString(document.documentElement.lang)}`)?.trim();
    if (title == null) return;
    this.saveBtn.disabled = true;
    toast(t('preparing'));
    try {
      const pages: PageData[] = [];
      const fileIds: string[] = [];
      for (const p of this.pages) {
        const c = await this.result(p);
        const bytes = await canvasToBytes(c, 'image/jpeg', 0.88);
        const fileId = uid();
        await putFile(fileId, bytes.slice().buffer);
        fileIds.push(fileId);
        const w = c.width >= c.height ? A4.h : A4.w;
        pages.push({ id: uid(), w, h: (w * c.height) / c.width, bg: { kind: 'image', fileId, mime: 'image/jpeg' }, strokes: [] });
      }
      const now = Date.now();
      const doc: DocMeta = {
        id: uid(), title: title || t('scanTitle'), kind: 'images', createdAt: now, updatedAt: now,
        pageIds: pages.map((p) => p.id), template: 'blank', paper: 'white', fileIds, reading: true,
      };
      await putPages(pages);
      doc.thumb = (await renderPageToCanvas(doc, pages[0], 240 / pages[0].w)).toDataURL('image/jpeg', 0.75);
      await putDoc(doc);
      this.pages = [];
      this.root.close();
      this.onDone(doc);
    } catch (e) {
      console.error(e);
      toast(String(e));
      this.saveBtn.disabled = false;
    }
  }
}

/** The page in a photo, found on a small grey copy. */
function findPage(src: HTMLCanvasElement): Quad | null {
  const k = 256 / Math.max(src.width, src.height);
  const w = Math.max(8, Math.round(src.width * k)), hh = Math.max(8, Math.round(src.height * k));
  const c = document.createElement('canvas');
  c.width = w; c.height = hh;
  const ctx = c.getContext('2d')!;
  ctx.drawImage(src, 0, 0, w, hh);
  const d = ctx.getImageData(0, 0, w, hh).data;
  const g = new Uint8ClampedArray(w * hh);
  for (let i = 0; i < w * hh; i++) g[i] = 0.299 * d[i * 4] + 0.587 * d[i * 4 + 1] + 0.114 * d[i * 4 + 2];
  const q = detectPage(g, w, hh);
  return q ? (q.map(([x, y]) => [x / k, y / k]) as Quad) : null;
}
