import { listDocs, putDoc, putPages, putFile, deleteDoc } from './db';
import { A4, uid, type DocMeta, type PageData, type Template, type PaperColor } from './types';
import { loadPdf, pdfPageSizes, destroyPdf, renderPdfPage } from './pdf';
import { drawTemplate } from './ink';
import { openSettings } from './settingsDialog';
import { icons } from './icons';
import { t } from './i18n';
import { h, modal, toast } from './ui';

export class Library {
  root = h('div', { class: 'library' });
  private grid = h('div', { class: 'grid' });
  private query = '';

  constructor(private openDoc: (d: DocMeta) => void) {}

  async mount() {
    const btn = (icon: string, label: string, fn: () => void, cls = '') => {
      const b = h('button', { class: `btn ${cls}` });
      b.innerHTML = icon;
      b.append(h('span', {}, label));
      b.addEventListener('click', fn);
      return b;
    };
    const fileInput = h('input', { type: 'file', accept: 'application/pdf,.pdf', multiple: true, hidden: true });
    fileInput.addEventListener('change', async () => {
      for (const f of fileInput.files ?? []) await this.importPdf(f);
      fileInput.value = '';
    });
    const search = h('input', { type: 'search', class: 'search', placeholder: t('search') });
    search.addEventListener('input', () => { this.query = search.value.trim().toLowerCase(); void this.refresh(); });
    const settingsBtn = h('button', { class: 'icon-btn', title: t('settings'), 'aria-label': t('settings') });
    settingsBtn.innerHTML = icons.settings;
    settingsBtn.addEventListener('click', () => openSettings(() => {}));

    this.root.append(
      h('header', { class: 'lib-head' },
        h('h1', {}, t('appName')),
        search,
        btn(icons.plus, t('newNotebook'), () => this.newNotebookDialog(), 'primary'),
        btn(icons.file, t('importPdf'), () => fileInput.click()),
        settingsBtn,
        fileInput,
      ),
      this.grid,
    );
    // drag & drop PDFs (desktop)
    this.root.addEventListener('dragover', (e) => e.preventDefault());
    this.root.addEventListener('drop', async (e) => {
      e.preventDefault();
      for (const f of e.dataTransfer?.files ?? []) if (f.type === 'application/pdf') await this.importPdf(f);
    });
    await this.refresh();
  }

  async refresh() {
    const docs = (await listDocs()).filter((d) => !this.query || d.title.toLowerCase().includes(this.query));
    this.grid.replaceChildren();
    if (!docs.length) {
      this.grid.append(h('p', { class: 'empty' }, t('empty')));
      return;
    }
    for (const d of docs) {
      const thumb = h('div', { class: 'thumb' });
      if (d.thumb) thumb.append(h('img', { src: d.thumb, alt: '' }));
      else thumb.innerHTML = d.kind === 'pdf' ? icons.file : icons.pen;
      const more = h('button', { class: 'icon-btn more', 'aria-label': '…' });
      more.innerHTML = icons.more;
      more.addEventListener('click', (e) => { e.stopPropagation(); this.docMenu(d); });
      const card = h('div', { class: 'card', tabindex: 0, role: 'button' },
        thumb,
        h('div', { class: 'card-meta' },
          h('div', { class: 'card-title' }, d.title),
          h('div', { class: 'card-sub' }, `${d.kind === 'pdf' ? 'PDF · ' : ''}${d.pageIds.length} ${t('pages')} · ${new Date(d.updatedAt).toLocaleDateString(document.documentElement.lang)}`),
        ),
        more,
      );
      card.addEventListener('click', () => this.openDoc(d));
      card.addEventListener('keydown', (e) => { if (e.key === 'Enter') this.openDoc(d); });
      this.grid.append(card);
    }
  }

  private docMenu(d: DocMeta) {
    const form = h('form', { method: 'dialog', class: 'menu' },
      h('h2', {}, d.title),
      h('button', { value: 'rename' }, t('rename')),
      h('button', { value: 'delete', class: 'danger' }, t('delete')),
      h('button', { value: 'cancel' }, t('cancel')),
    );
    modal(form).closed.then(async (v) => {
      if (v === 'rename') {
        const name = prompt(t('title'), d.title)?.trim();
        if (name) { d.title = name; await putDoc(d); }
      } else if (v === 'delete' && confirm(t('confirmDelete'))) {
        await deleteDoc(d);
      }
      await this.refresh();
    });
  }

  private newNotebookDialog() {
    let template: Template = 'lined';
    let paper: PaperColor = 'white';
    const preview = (tp: Template, pp: PaperColor) => {
      const c = h('canvas', { width: 90, height: 127 });
      const ctx = c.getContext('2d')!;
      ctx.scale(90 / A4.w, 127 / A4.h);
      drawTemplate(ctx, A4.w, A4.h, tp, pp);
      return c;
    };
    const tplRow = h('div', { class: 'tpl-row' });
    const renderTpls = () => {
      tplRow.replaceChildren();
      for (const tp of ['blank', 'lined', 'grid', 'dots', 'cornell'] as Template[]) {
        const b = h('button', { type: 'button', class: `tpl ${tp === template ? 'active' : ''}` }, preview(tp, paper), h('span', {}, t(tp)));
        b.addEventListener('click', () => { template = tp; renderTpls(); });
        tplRow.append(b);
      }
    };
    const paperRow = h('div', { class: 'seg' });
    const renderPaper = () => {
      paperRow.replaceChildren();
      for (const pp of ['white', 'cream', 'dark'] as PaperColor[]) {
        const b = h('button', { type: 'button', class: pp === paper ? 'active' : '' }, t(pp));
        b.addEventListener('click', () => { paper = pp; renderPaper(); renderTpls(); });
        paperRow.append(b);
      }
    };
    renderTpls();
    renderPaper();
    const title = h('input', { type: 'text', value: t('untitled'), class: 'text' });
    const form = h('form', { method: 'dialog', class: 'new-nb' },
      h('h2', {}, t('newNotebook')),
      h('label', { class: 'row col' }, h('span', {}, t('title')), title),
      h('div', { class: 'row col' }, h('span', {}, t('template')), tplRow),
      h('div', { class: 'row col' }, h('span', {}, t('paper')), paperRow),
      h('div', { class: 'actions' },
        h('button', { value: 'cancel', type: 'submit' }, t('cancel')),
        h('button', { value: 'create', type: 'submit', class: 'primary' }, t('create')),
      ),
    );
    modal(form).closed.then(async (v) => {
      if (v !== 'create') return;
      const page: PageData = { id: uid(), w: A4.w, h: A4.h, bg: { kind: 'template', template, paper }, strokes: [] };
      const now = Date.now();
      const doc: DocMeta = {
        id: uid(), title: title.value.trim() || t('untitled'), kind: 'notebook',
        createdAt: now, updatedAt: now, pageIds: [page.id], template, paper,
      };
      await putPages([page]);
      await putDoc(doc);
      this.openDoc(doc);
    });
  }

  async importPdf(file: File) {
    toast(t('loading'));
    try {
      const buf = await file.arrayBuffer();
      const pdf = await loadPdf(buf);
      const sizes = await pdfPageSizes(pdf);
      let thumb: string | undefined;
      try {
        const c = document.createElement('canvas');
        await renderPdfPage(pdf, 1, c, 240 / sizes[0].w);
        thumb = c.toDataURL('image/jpeg', 0.75);
      } catch { /* thumbnail is optional */ }
      destroyPdf(pdf);
      const id = uid();
      const pages: PageData[] = sizes.map((s, i) => ({ id: uid(), w: s.w, h: s.h, bg: { kind: 'pdf', pdfPage: i + 1 }, strokes: [] }));
      const now = Date.now();
      const doc: DocMeta = {
        id, title: file.name.replace(/\.pdf$/i, ''), kind: 'pdf', createdAt: now, updatedAt: now,
        pageIds: pages.map((p) => p.id), template: 'blank', paper: 'white', thumb,
      };
      await putFile(id, buf);
      await putPages(pages);
      await putDoc(doc);
      await this.refresh();
      return doc;
    } catch (err) {
      console.error(err);
      toast(t('pdfError'));
    }
  }
}
