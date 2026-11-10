import { PDFDocument } from 'pdf-lib';
import type { Clip, DocMeta, PageData } from './types';
import { uid, A4 } from './types';
import { listClips, putClip, deleteClip, putFile, getFile } from './db';
import { pdfTextLines, pdfTextIn, paragraphAt, joinLines } from './pdf';
import { renderPageToCanvas, canvasToBytes } from './render';
import { drawTextBox, textHeight } from './text';
import { shareFiles } from './share';
import { settings } from './settings';
import { icons } from './icons';
import { t } from './i18n';
import { h, modal, toast } from './ui';

type Rect = [number, number, number, number];

// ---------------------------------------------------------------- creating clips

/** Crops a region of the fully rendered page (background + ink + text) into a JPEG. */
async function snapshot(meta: DocMeta, page: PageData, r: Rect): Promise<string> {
  const scale = Math.min(3, 1100 / Math.max(40, r[2] - r[0]));
  const full = await renderPageToCanvas(meta, page, scale);
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round((r[2] - r[0]) * scale));
  c.height = Math.max(1, Math.round((r[3] - r[1]) * scale));
  const ctx = c.getContext('2d')!;
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.drawImage(full, -r[0] * scale, -r[1] * scale);
  full.width = 0;
  return c.toDataURL('image/jpeg', 0.85);
}

const clampRect = (r: Rect, p: PageData): Rect => [
  Math.max(0, r[0]), Math.max(0, r[1]), Math.min(p.w, r[2]), Math.min(p.h, r[3]),
];

/**
 * Pen gesture → clip. A flat, wide stroke (an underline) clips the whole paragraph above it on PDF
 * pages; any other shape clips the area it frames.
 */
export async function clipFromStroke(meta: DocMeta, pages: PageData[], index: number, pts: number[]): Promise<Clip | null> {
  const page = pages[index];
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity, sy = 0;
  for (let i = 0; i < pts.length; i += 3) {
    x0 = Math.min(x0, pts[i]); x1 = Math.max(x1, pts[i]);
    y0 = Math.min(y0, pts[i + 1]); y1 = Math.max(y1, pts[i + 1]);
    sy += pts[i + 1];
  }
  sy /= pts.length / 3;
  if (x1 - x0 < 8 && y1 - y0 < 8) return null;
  const lineLike = y1 - y0 < Math.max(18, (x1 - x0) * 0.25);
  let rect: Rect;
  let text = '';
  if (page.bg.kind === 'pdf') {
    try {
      if (lineLike) {
        const lines = await pdfTextLines(meta.id, page.bg.pdfPage);
        const para = paragraphAt(lines, x0, x1, sy);
        if (para) {
          rect = [Math.min(...para.map((l) => l.x0)) - 6, para[0].y0 - 5, Math.max(...para.map((l) => l.x1)) + 6, para[para.length - 1].y1 + 5];
          text = joinLines(para);
        }
      }
      rect ??= lineLike ? [x0 - 8, sy - 34, x1 + 8, sy + 8] : [x0, y0, x1, y1];
      if (!text) text = await pdfTextIn(meta.id, page.bg.pdfPage, rect);
    } catch {
      rect ??= [x0, y0, x1, y1];
    }
  } else {
    rect = lineLike ? [x0 - 8, sy - 34, x1 + 8, sy + 8] : [x0, y0, x1, y1];
    // typed text inside the region counts as the clip's text
    text = (page.texts ?? [])
      .filter((tb) => tb.x < rect[2] && tb.x + tb.w > rect[0] && tb.y < rect[3] && tb.y + textHeight(tb) > rect[1])
      .map((tb) => tb.text)
      .join('\n');
  }
  return clipFromRect(meta, pages, index, rect!, text);
}

export async function clipFromRect(meta: DocMeta, pages: PageData[], index: number, r: Rect, text = ''): Promise<Clip> {
  const page = pages[index];
  const rect = clampRect(r, page);
  if (!text && page.bg.kind === 'pdf') text = await pdfTextIn(meta.id, page.bg.pdfPage, rect).catch(() => '');
  const clip: Clip = {
    id: uid(), kind: 'clip', docId: meta.id, docTitle: meta.title, page: index, rect,
    image: await snapshot(meta, page, rect), text, note: '', createdAt: Date.now(),
  };
  await putClip(clip);
  return clip;
}

// ---------------------------------------------------------------- voice memos

export class VoiceRecorder {
  private rec: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private started = 0;
  get recording() { return !!this.rec; }
  get seconds() { return this.rec ? Math.floor((Date.now() - this.started) / 1000) : 0; }

  async start() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mime = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg'].find((m) => MediaRecorder.isTypeSupported?.(m)) ?? '';
    this.rec = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
    this.chunks = [];
    this.rec.ondataavailable = (e) => e.data.size && this.chunks.push(e.data);
    this.rec.start(1000);
    this.started = Date.now();
  }

  stop(): Promise<{ data: ArrayBuffer; mime: string; duration: number } | null> {
    const rec = this.rec;
    if (!rec) return Promise.resolve(null);
    this.rec = null;
    const duration = (Date.now() - this.started) / 1000;
    return new Promise((res) => {
      rec.onstop = async () => {
        rec.stream.getTracks().forEach((tr) => tr.stop());
        const blob = new Blob(this.chunks, { type: rec.mimeType || 'audio/webm' });
        res(blob.size ? { data: await blob.arrayBuffer(), mime: blob.type, duration } : null);
      };
      rec.stop();
    });
  }
}

export async function saveVoiceMemo(meta: DocMeta, page: number, rec: { data: ArrayBuffer; mime: string; duration: number }) {
  const audioId = uid();
  await putFile(audioId, rec.data);
  const clip: Clip = {
    id: uid(), kind: 'audio', docId: meta.id, docTitle: meta.title, page, note: '',
    audioId, audioMime: rec.mime, duration: rec.duration, createdAt: Date.now(),
  };
  await putClip(clip);
  return clip;
}

export const fmtTime = (sec: number) => `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(Math.floor(sec % 60)).padStart(2, '0')}`;

function ago(ts: number) {
  const rtf = new Intl.RelativeTimeFormat(settings.lang, { numeric: 'auto' });
  const s = (ts - Date.now()) / 1000;
  if (s > -60) return rtf.format(Math.round(s), 'second');
  if (s > -3600) return rtf.format(Math.round(s / 60), 'minute');
  if (s > -86400) return rtf.format(Math.round(s / 3600), 'hour');
  return rtf.format(Math.round(s / 86400), 'day');
}

// ---------------------------------------------------------------- side panel

export interface PanelHost {
  meta: DocMeta;
  /** jump within the open document */
  jump(page: number, rect?: Rect): void;
  /** open another document at a page */
  openDoc(docId: string, page: number): void;
  close(): void;
}

export class NotesPanel {
  root = h('aside', { class: 'notes-panel' });
  private list = h('div', { class: 'np-list' });
  private count = h('span', { class: 'np-count' });
  private scope: 'doc' | 'all' = 'doc';
  private clips: Clip[] = [];
  private urls: string[] = [];

  constructor(private host: PanelHost) {
    const seg = h('div', { class: 'seg small' });
    for (const [v, k] of [['doc', 'thisDoc'], ['all', 'allDocs']] as const) {
      const b = h('button', { class: this.scope === v ? 'active' : '', 'data-scope': v }, t(k));
      b.addEventListener('click', () => {
        this.scope = v;
        seg.querySelectorAll('button').forEach((x) => x.classList.toggle('active', x === b));
        void this.refresh();
      });
      seg.append(b);
    }
    const iconBtn = (icon: string, title: string, fn: () => void, cls = '') => {
      const b = h('button', { class: `icon-btn ${cls}`, title, 'aria-label': title });
      b.innerHTML = icon;
      b.addEventListener('click', fn);
      return b;
    };
    const mark = h('div', { class: 'np-mark' });
    mark.innerHTML = icons.notebook;
    this.root.append(
      h('div', { class: 'np-head' },
        mark,
        h('div', { class: 'np-title' }, h('strong', {}, t('notes')), this.count),
        iconBtn(icons.cards, t('flashcards'), () => void openFlashcards(this.clips.filter((c) => c.kind === 'clip'), this.host)),
        iconBtn(icons.download, t('exportSummary'), () => void exportSummary(this.clips, this.scope === 'doc' ? this.host.meta.title : t('allDocs'))),
        iconBtn(icons.close, t('back'), () => this.host.close()),
      ),
      h('div', { class: 'np-sub' }, seg),
      this.list,
    );
  }

  async refresh(highlight?: string) {
    this.clips = await listClips(this.scope === 'doc' ? this.host.meta.id : undefined);
    for (const u of this.urls) URL.revokeObjectURL(u);
    this.urls = [];
    this.count.textContent = `${this.clips.length} ${t('clipsCount')}`;
    this.list.replaceChildren();
    if (!this.clips.length) {
      const empty = h('div', { class: 'np-empty' });
      empty.innerHTML = icons.clip;
      empty.append(h('p', {}, t('notesEmpty')));
      this.list.append(empty);
      return;
    }
    for (const c of this.clips) this.list.append(this.card(c, c.id === highlight));
  }

  private card(c: Clip, fresh: boolean) {
    const card = h('article', { class: `np-card${fresh ? ' fresh' : ''}`, 'data-id': c.id });
    const here = c.docId === this.host.meta.id;
    const go = () => (here ? this.host.jump(c.page, c.rect) : this.host.openDoc(c.docId, c.page));
    const chip = h('button', { class: 'np-chip', title: t('jumpToSource') });
    chip.innerHTML = c.kind === 'audio' ? icons.mic : icons.link;
    chip.append(h('span', {}, `${here ? '' : `${c.docTitle} · `}${t('page')} ${c.page + 1}`));
    chip.addEventListener('click', go);
    const del = h('button', { class: 'icon-btn small', title: t('delete'), 'aria-label': t('delete') });
    del.innerHTML = icons.trash;
    del.addEventListener('click', async () => {
      await deleteClip(c);
      card.classList.add('removing');
      setTimeout(() => void this.refresh(), 180);
    });
    card.append(h('div', { class: 'np-row' }, chip, h('span', { class: 'np-time' }, ago(c.createdAt)), del));

    if (c.image) {
      const img = h('img', { src: c.image, alt: '', class: 'np-img', loading: 'lazy' });
      img.addEventListener('click', go);
      card.append(img);
    }
    if (c.text) {
      const p = h('p', { class: 'np-text', dir: 'auto' }, c.text);
      p.addEventListener('click', () => p.classList.toggle('open'));
      card.append(p);
    }
    if (c.kind === 'audio' && c.audioId) {
      const audio = h('audio', { controls: true, preload: 'none', class: 'np-audio' });
      void getFile(c.audioId).then((buf) => {
        if (!buf) return;
        const url = URL.createObjectURL(new Blob([buf], { type: c.audioMime || 'audio/webm' }));
        this.urls.push(url);
        audio.src = url;
      });
      card.append(h('div', { class: 'np-voice' }, h('span', {}, `${t('voiceMemo')} · ${fmtTime(c.duration ?? 0)}`), audio));
    }
    const note = h('textarea', { class: 'np-note', dir: 'auto', rows: 2, placeholder: t('notePlaceholder') });
    note.value = c.note;
    let timer = 0;
    note.addEventListener('input', () => {
      note.style.height = 'auto';
      note.style.height = `${note.scrollHeight}px`;
      clearTimeout(timer);
      timer = window.setTimeout(() => { c.note = note.value; void putClip(c); }, 400);
    });
    note.addEventListener('blur', () => { clearTimeout(timer); if (c.note !== note.value) { c.note = note.value; void putClip(c); } });
    card.append(note);
    if (fresh) requestAnimationFrame(() => card.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
    return card;
  }

  dispose() {
    for (const u of this.urls) URL.revokeObjectURL(u);
  }
}

// ---------------------------------------------------------------- flashcards

export async function openFlashcards(all: Clip[], host: PanelHost) {
  if (!all.length) return toast(t('notesEmpty'));
  const queue = [...all].sort(() => Math.random() - 0.5);
  let done = 0;
  const total = queue.length;
  const progress = h('div', { class: 'fc-progress' });
  const bar = h('div', { class: 'fc-bar' }, h('span'));
  const card = h('div', { class: 'fc-card' });
  const front = h('div', { class: 'fc-face fc-front' });
  const back = h('div', { class: 'fc-face fc-back' });
  card.append(front, back);
  const flip = h('button', { type: 'button', class: 'primary' }, t('flip'));
  const again = h('button', { type: 'button' }, t('again'));
  const knew = h('button', { type: 'button', class: 'primary' }, t('knewIt'));
  const actions = h('div', { class: 'actions fc-actions' }, flip);
  const form = h('form', { method: 'dialog', class: 'flashcards' },
    h('div', { class: 'fc-head' }, h('h2', {}, t('flashcards')), progress),
    bar, card, actions,
  );
  const show = () => {
    card.classList.remove('flipped');
    actions.replaceChildren(flip);
    const c = queue[0];
    progress.textContent = `${done + 1} / ${total}`;
    (bar.firstChild as HTMLElement).style.width = `${(done / total) * 100}%`;
    front.replaceChildren(
      h('div', { class: 'fc-tag' }, `${c.docTitle} · ${t('page')} ${c.page + 1}`),
      h('p', { class: 'fc-q', dir: 'auto' }, c.note.trim() || t('flashcardFront')),
    );
    if (!c.note.trim() && c.text) front.append(h('p', { class: 'fc-hint', dir: 'auto' }, `${c.text.split(/\s+/).slice(0, 6).join(' ')}…`));
    back.replaceChildren();
    if (c.image) back.append(h('img', { src: c.image, alt: '' }));
    if (c.text) back.append(h('p', { dir: 'auto' }, c.text));
    const jump = h('button', { type: 'button', class: 'np-chip' });
    jump.innerHTML = icons.link;
    jump.append(h('span', {}, t('jumpToSource')));
    jump.addEventListener('click', () => {
      dlg.close();
      c.docId === host.meta.id ? host.jump(c.page, c.rect) : host.openDoc(c.docId, c.page);
    });
    back.append(jump);
  };
  const finish = () => {
    (bar.firstChild as HTMLElement).style.width = '100%';
    card.replaceChildren(h('div', { class: 'fc-done' }, h('div', { class: 'fc-big' }, '🎉'), h('p', {}, t('reviewDone')), h('p', { class: 'hint' }, `${total} / ${total}`)));
    actions.replaceChildren(h('button', { value: 'ok', class: 'primary' }, 'OK'));
  };
  flip.addEventListener('click', () => {
    card.classList.add('flipped');
    actions.replaceChildren(again, knew);
  });
  card.addEventListener('click', () => { if (!card.classList.contains('flipped') && queue.length) flip.click(); });
  again.addEventListener('click', () => { queue.push(queue.shift()!); show(); });
  knew.addEventListener('click', () => {
    queue.shift();
    done++;
    if (queue.length) show();
    else finish();
  });
  const { dlg } = modal(form, 'fc-modal');
  show();
}

// ---------------------------------------------------------------- export summary

/** Study summary PDF: every clip with its picture, text and your note, rendered as images so Arabic shapes correctly. */
export async function exportSummary(clips: Clip[], title: string) {
  if (!clips.length) return toast(t('notesEmpty'));
  toast(t('preparing'));
  const S = 2.5;
  const W = A4.w - 72;
  const out = await PDFDocument.create();
  let page = out.addPage([A4.w, A4.h]);
  let y = 36;
  const place = async (c: HTMLCanvasElement, hgt: number) => {
    if (y + hgt > A4.h - 36 && y > 36) {
      page = out.addPage([A4.w, A4.h]);
      y = 36;
    }
    const img = await out.embedPng(await canvasToBytes(c));
    page.drawImage(img, { x: 36, y: A4.h - y - hgt, width: W, height: hgt });
    y += hgt + 14;
  };
  const block = (draw: (ctx: CanvasRenderingContext2D) => void, hgt: number) => {
    const c = document.createElement('canvas');
    c.width = Math.ceil(W * S);
    c.height = Math.ceil(hgt * S);
    const ctx = c.getContext('2d')!;
    ctx.scale(S, S);
    draw(ctx);
    return c;
  };
  const rtl = settings.lang === 'ar';
  const header = { id: 'h', x: 0, y: 0, w: W, text: `${t('summaryTitle')} — ${title}`, size: 18, color: '#0f172a', bold: true };
  await place(block((ctx) => drawTextBox(ctx, header), textHeight(header) + 4), textHeight(header) + 4);
  for (const c of [...clips].reverse()) {
    const label = { id: 'l', x: 0, y: 0, w: W, text: `${c.docTitle} · ${t('page')} ${c.page + 1}${c.kind === 'audio' ? ` · ${t('voiceMemo')} ${fmtTime(c.duration ?? 0)}` : ''}`, size: 9, color: '#0891b2', bold: true };
    const note = c.note.trim() ? { id: 'n', x: 10, y: 0, w: W - 20, text: c.note.trim(), size: 11, color: '#0f172a' } : null;
    const text = c.text && !c.image ? { id: 't', x: 0, y: 0, w: W, text: c.text, size: 10, color: '#334155' } : null;
    let imgH = 0;
    let bmp: ImageBitmap | null = null;
    if (c.image) {
      bmp = await createImageBitmap(await (await fetch(c.image)).blob());
      imgH = Math.min(420, (W * bmp.height) / bmp.width);
    }
    const lh = textHeight(label) + 4;
    const th = text ? textHeight(text) + 6 : 0;
    const nh = note ? textHeight(note) + 16 : 0;
    const hgt = lh + imgH + (imgH ? 6 : 0) + th + nh;
    await place(block((ctx) => {
      drawTextBox(ctx, label);
      let yy = lh;
      if (bmp) {
        const iw = Math.min(W, (imgH * bmp.width) / bmp.height);
        ctx.drawImage(bmp, rtl ? W - iw : 0, yy, iw, imgH);
        ctx.strokeStyle = '#cbd5e1';
        ctx.strokeRect(rtl ? W - iw : 0, yy, iw, imgH);
        yy += imgH + 6;
      }
      if (text) { drawTextBox(ctx, { ...text, y: yy }); yy += th; }
      if (note) {
        ctx.fillStyle = '#ecfeff';
        ctx.strokeStyle = '#06b6d4';
        ctx.beginPath();
        ctx.roundRect(0, yy, W, nh - 4, 6);
        ctx.fill();
        ctx.stroke();
        drawTextBox(ctx, { ...note, y: yy + 6 });
      }
    }, hgt), hgt);
    bmp?.close();
  }
  out.setTitle(`${t('summaryTitle')} — ${title}`);
  const bytes = await out.save();
  const name = `${title.replace(/[\\/:*?"<>|]+/g, '_')} — ${t('summaryTitle')}.pdf`;
  await shareFiles([{ name, bytes, mime: 'application/pdf' }], name);
}

