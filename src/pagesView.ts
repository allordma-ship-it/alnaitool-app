import type { DocMeta, PageData } from './types';
import type { OutlineItem } from './pdf';
import { renderPageToCanvas } from './render';
import { icons } from './icons';
import { t } from './i18n';
import { h, modal } from './ui';

export interface PagesHost {
  meta: DocMeta;
  pages(): PageData[];
  current(): number;
  go(i: number): void;
  goOutline(item: OutlineItem): void;
  outline(): Promise<OutlineItem[]>;
  move(from: number, to: number): Promise<void>;
  duplicate(i: number): Promise<void>;
  insertAfter(i: number): Promise<void>;
  remove(i: number): Promise<void>;
  toggleBookmark(i: number): void;
  saveTemplate(i: number): Promise<void>;
}

type Tab = 'pages' | 'contents' | 'bookmarks';
const THUMB_W = 132;

/**
 * Full-screen overview of a document: every page as a thumbnail (tap to go, drag the handle to reorder,
 * menu to duplicate / insert / delete, star to bookmark), the PDF's table of contents, and the bookmarks.
 */
export class PagesView {
  root = h('dialog', { class: 'pages-view' });
  private body = h('div', { class: 'pv-body' });
  private tabs = h('div', { class: 'seg' });
  private tab: Tab = 'pages';
  private io: IntersectionObserver | null = null;
  private queue: (() => Promise<void>)[] = [];
  private busy = false;
  /** rendered thumbnails by page id; the stroke count tells when a page changed */
  private cache = new Map<string, { key: string; url: string }>();

  constructor(private host: PagesHost, private onClose: () => void) {
    const close = h('button', { class: 'icon-btn', title: t('close'), 'aria-label': t('close') });
    close.innerHTML = icons.close;
    close.addEventListener('click', () => this.close());
    this.root.append(
      h('header', { class: 'pv-head' }, h('h2', {}, host.meta.title), this.tabs, h('div', { class: 'spacer' }), close),
      this.body,
    );
    // Escape and the Android back gesture close the dialog; clean up whichever way it closes
    this.root.addEventListener('close', () => {
      this.io?.disconnect();
      this.queue = [];
      this.root.remove();
      this.onClose();
    });
  }

  open(tab: Tab = 'pages') {
    document.body.append(this.root);
    this.root.showModal();
    this.show(tab);
  }

  close() {
    if (this.root.open) this.root.close();
  }

  private show(tab: Tab) {
    this.tab = tab;
    this.tabs.replaceChildren();
    const items: [Tab, string][] = [['pages', t('pagesTitle')], ...(this.host.meta.kind === 'pdf' ? [['contents', t('contents')] as [Tab, string]] : []), ['bookmarks', t('bookmarks')]];
    for (const [v, label] of items) {
      const b = h('button', { type: 'button', class: v === tab ? 'active' : '' }, label);
      b.addEventListener('click', () => this.show(v));
      this.tabs.append(b);
    }
    this.io?.disconnect();
    this.queue = [];
    this.body.replaceChildren();
    if (tab === 'pages') this.renderPages();
    else if (tab === 'bookmarks') this.renderBookmarks();
    else void this.renderContents();
  }

  // ---------------------------------------------------------------- thumbnails

  private thumbImg(p: PageData) {
    const box = h('div', { class: 'pv-img', style: `aspect-ratio:${p.w} / ${p.h}` });
    const key = `${p.strokes.length}:${p.texts?.length ?? 0}:${p.strokes.at(-1)?.id ?? ''}`;
    const hit = this.cache.get(p.id);
    if (hit && hit.key === key) {
      box.append(h('img', { src: hit.url, alt: '' }));
      return box;
    }
    (box as HTMLElement & { _load?: () => Promise<void> })._load = async () => {
      const c = await renderPageToCanvas(this.host.meta, p, (THUMB_W * 2) / p.w);
      const url = c.toDataURL('image/jpeg', 0.75);
      c.width = 0;
      this.cache.set(p.id, { key, url });
      box.replaceChildren(h('img', { src: url, alt: '' }));
    };
    this.io ??= new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        this.io!.unobserve(e.target);
        const load = (e.target as HTMLElement & { _load?: () => Promise<void> })._load;
        if (load) this.enqueue(load);
      }
    }, { root: this.body, rootMargin: '400px' });
    this.io.observe(box);
    return box;
  }

  /** Thumbnails render one after another, so a 600-page PDF does not choke the tablet. */
  private enqueue(job: () => Promise<void>) {
    this.queue.push(job);
    if (this.busy) return;
    this.busy = true;
    void (async () => {
      while (this.queue.length) {
        const next = this.queue.shift()!;
        try { await next(); } catch (e) { console.warn('thumb', e); }
      }
      this.busy = false;
    })();
  }

  private isMarked(p: PageData) {
    return (this.host.meta.bookmarks ?? []).includes(p.id);
  }

  private star(i: number, p: PageData, after: () => void) {
    const b = h('button', { type: 'button', class: `icon-btn pv-star ${this.isMarked(p) ? 'on' : ''}`, title: t('bookmark'), 'aria-label': t('bookmark'), 'aria-pressed': String(this.isMarked(p)) });
    b.innerHTML = this.isMarked(p) ? icons.bookmarkFill : icons.bookmark;
    b.addEventListener('click', (e) => { e.stopPropagation(); this.host.toggleBookmark(i); after(); });
    return b;
  }

  private renderPages() {
    const pages = this.host.pages();
    const cur = this.host.current();
    const grid = h('div', { class: 'pv-grid' });
    pages.forEach((p, i) => {
      const grip = h('button', { type: 'button', class: 'icon-btn pv-grip', title: t('dragToReorder'), 'aria-label': t('dragToReorder') });
      grip.innerHTML = icons.grip;
      const more = h('button', { type: 'button', class: 'icon-btn pv-more', 'aria-label': '…' });
      more.innerHTML = icons.more;
      more.addEventListener('click', (e) => { e.stopPropagation(); this.pageMenu(i); });
      const cell = h('div', { class: `pv-cell ${i === cur ? 'current' : ''}`, 'data-i': i },
        this.thumbImg(p),
        h('div', { class: 'pv-foot' }, grip, h('span', { class: 'pv-num' }, String(i + 1)), this.star(i, p, () => this.show('pages')), more),
      );
      cell.querySelector('.pv-img')!.addEventListener('click', () => { this.host.go(i); this.close(); });
      this.dragFrom(grip, cell, i, grid);
      grid.append(cell);
    });
    this.body.append(grid);
    requestAnimationFrame(() => grid.querySelector('.current')?.scrollIntoView({ block: 'center' }));
  }

  /** Drag by the handle: works with finger, pen and mouse; the grid scrolls when the page nears an edge. */
  private dragFrom(grip: HTMLElement, cell: HTMLElement, from: number, grid: HTMLElement) {
    grip.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      grip.setPointerCapture(e.pointerId);
      const r = cell.getBoundingClientRect();
      const ghost = cell.cloneNode(true) as HTMLElement;
      ghost.classList.add('pv-ghost');
      ghost.style.width = `${r.width}px`;
      document.body.append(ghost);
      cell.classList.add('dragging');
      let target = from;
      let scroll = 0;
      const place = (x: number, y: number) => {
        ghost.style.transform = `translate(${x - r.width / 2}px, ${y - 30}px)`;
        const under = document.elementFromPoint(x, y)?.closest<HTMLElement>('.pv-cell');
        grid.querySelectorAll('.drop-before, .drop-after').forEach((el) => el.classList.remove('drop-before', 'drop-after'));
        if (under && under !== cell) {
          const i = Number(under.dataset.i);
          const ur = under.getBoundingClientRect();
          const rtl = getComputedStyle(grid).direction === 'rtl';
          const before = rtl ? x > ur.left + ur.width / 2 : x < ur.left + ur.width / 2;
          under.classList.add(before ? 'drop-before' : 'drop-after');
          const to = before ? i : i + 1;
          target = to > from ? to - 1 : to;
        } else if (under === cell) target = from;
        const br = this.body.getBoundingClientRect();
        scroll = y < br.top + 60 ? -12 : y > br.bottom - 60 ? 12 : 0;
      };
      const tick = () => { if (scroll) this.body.scrollTop += scroll; if (ghost.isConnected) requestAnimationFrame(tick); };
      requestAnimationFrame(tick);
      place(e.clientX, e.clientY);
      const move = (ev: PointerEvent) => place(ev.clientX, ev.clientY);
      const up = async () => {
        grip.removeEventListener('pointermove', move);
        grip.removeEventListener('pointerup', up);
        grip.removeEventListener('pointercancel', up);
        ghost.remove();
        cell.classList.remove('dragging');
        if (target !== from) {
          await this.host.move(from, target);
          this.show('pages');
        } else grid.querySelectorAll('.drop-before, .drop-after').forEach((el) => el.classList.remove('drop-before', 'drop-after'));
      };
      grip.addEventListener('pointermove', move);
      grip.addEventListener('pointerup', up);
      grip.addEventListener('pointercancel', up);
    });
  }

  private pageMenu(i: number) {
    const item = (icon: string, label: string, value: string, cls = '') => {
      const b = h('button', { value, class: `menu-item ${cls}` });
      b.innerHTML = icon;
      b.append(h('span', {}, label));
      return b;
    };
    const many = this.host.pages().length > 1;
    const form = h('form', { method: 'dialog', class: 'menu' },
      h('h2', {}, `${t('page')} ${i + 1}`),
      item(icons.copy, t('duplicatePage'), 'dup'),
      item(icons.plus, t('insertPage'), 'insert'),
      item(icons.pages, t('saveAsTemplate'), 'template'),
      ...(i > 0 ? [item(icons.prev, t('moveToStart'), 'first', 'flip-rtl')] : []),
      ...(i < this.host.pages().length - 1 ? [item(icons.next, t('moveToEnd'), 'last', 'flip-rtl')] : []),
      ...(many ? [item(icons.trash, t('deletePage'), 'delete', 'danger')] : []),
      h('button', { value: 'cancel' }, t('cancel')),
    );
    void modal(form).closed.then(async (v) => {
      if (v === 'dup') await this.host.duplicate(i);
      else if (v === 'insert') await this.host.insertAfter(i);
      else if (v === 'template') { await this.host.saveTemplate(i); return; }
      else if (v === 'first') await this.host.move(i, 0);
      else if (v === 'last') await this.host.move(i, this.host.pages().length - 1);
      else if (v === 'delete') {
        if (!confirm(`${t('deletePage')} ${i + 1}?`)) return;
        await this.host.remove(i);
      } else return;
      this.show('pages');
    });
  }

  // ---------------------------------------------------------------- bookmarks, contents

  private renderBookmarks() {
    const pages = this.host.pages();
    const marked = pages.map((p, i) => [p, i] as const).filter(([p]) => this.isMarked(p));
    if (!marked.length) {
      const empty = h('div', { class: 'pv-empty' });
      empty.innerHTML = icons.bookmark;
      empty.append(h('p', {}, t('noBookmarks')));
      this.body.append(empty);
      return;
    }
    const grid = h('div', { class: 'pv-grid' });
    for (const [p, i] of marked) {
      const cell = h('div', { class: 'pv-cell' }, this.thumbImg(p),
        h('div', { class: 'pv-foot' }, h('span', { class: 'pv-num' }, `${t('page')} ${i + 1}`), this.star(i, p, () => this.show('bookmarks'))));
      cell.querySelector('.pv-img')!.addEventListener('click', () => { this.host.go(i); this.close(); });
      grid.append(cell);
    }
    this.body.append(grid);
  }

  private async renderContents() {
    this.body.append(h('p', { class: 'hint pv-pad' }, t('loading')));
    let items: OutlineItem[] = [];
    try {
      items = await this.host.outline();
    } catch (e) {
      console.warn('outline', e);
    }
    if (this.tab !== 'contents') return;
    this.body.replaceChildren();
    if (!items.length) {
      this.body.append(h('div', { class: 'pv-empty' }, h('p', {}, t('noOutline'))));
      return;
    }
    const list = (its: OutlineItem[], depth: number): HTMLElement => {
      const ul = h('ul', { class: `pv-toc depth-${Math.min(depth, 3)}` });
      for (const it of its) {
        const row = h('button', { type: 'button', class: 'pv-toc-row', disabled: it.pdfPage == null, dir: 'auto' },
          h('span', { class: 'pv-toc-title' }, it.title),
          h('span', { class: 'pv-toc-page' }, it.pdfPage != null ? String(it.pdfPage) : ''));
        row.addEventListener('click', (e) => { e.preventDefault(); this.host.goOutline(it); this.close(); });
        let li: HTMLElement;
        if (it.items.length && depth >= 1) {
          // deeper levels fold: the caret opens them, the title jumps
          li = h('li', {}, h('details', {}, h('summary', {}, h('span', { class: 'pv-caret', 'aria-hidden': 'true' }), row), list(it.items, depth + 1)));
        } else {
          li = h('li', {}, row);
          if (it.items.length) li.append(list(it.items, depth + 1));
        }
        ul.append(li);
      }
      return ul;
    };
    this.body.append(h('div', { class: 'pv-pad' }, list(items, 0)));
  }
}
